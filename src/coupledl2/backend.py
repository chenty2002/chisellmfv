"""CoupledL2 build and JasperGold backend.

This module keeps large-project build and proof commands behind a deterministic
Python API so LLM tools do not need arbitrary shell access.
"""

from __future__ import annotations

import json
import os
import re
import shutil
import subprocess
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

from .workspace import CoupledL2Workspace


INCONCLUSIVE_STATUSES = {"undetermined", "unknown", "error"}
MILL_VERSION = "0.11.5"


class CoupledL2BuildOperations:
    """Build and verification operations for a copied CoupledL2 run workspace."""

    def __init__(self, workspace: CoupledL2Workspace, logger):
        self.workspace = workspace
        self.logger = logger
        self.case_dir = workspace.case_workspace
        self.chisel_dir = self.case_dir / "Chisel"
        self.work_dir = str(self.case_dir)
        self.generated_dir = str(self.chisel_dir / "generated")
        self.verilog_dir = str(self.case_dir / "Verilog")
        self.workspace_dir = str(workspace.workspace_dir)
        self._build_contract: Optional[Dict[str, Any]] = None

    @property
    def build_contract(self) -> Dict[str, Any]:
        if self._build_contract is None:
            self._build_contract = self._load_json(
                self.workspace.indexes_dir / "build_contract.json"
            )
        return self._build_contract

    def run_make(self, target: Optional[str] = None) -> Tuple[bool, str]:
        """Run the configured CoupledL2 build target and return a compact status tuple."""
        result = self.run_compilation(target=target)
        return bool(result.get("success")), str(result.get("output") or result.get("error") or "")

    def verify_compilation(self, require_assertions: bool = False) -> Dict[str, Any]:
        """Run the configured CoupledL2 build target and collect generated Verilog."""
        result = self.run_compilation()
        if require_assertions and result.get("success"):
            scan = self.scan_generated_assertions()
            if not scan["success"]:
                result = dict(result)
                result["success"] = False
                result["error"] = scan["error"]
                result["assertion_check"] = scan
        return result

    def run_full_verification_flow(self) -> Dict[str, Any]:
        """Run build, prepare run-local JasperGold inputs, and launch JasperGold."""
        build = self._run_build(self._stage_dir("invoke_verification"))
        if not build.get("success"):
            return {
                "success": False,
                "summary": "Compilation failed",
                "verification_passed": False,
                "output": build.get("output", ""),
                "build_result": build,
            }

        prepared = self.prepare_verification_inputs(top_module=build.get("top_module"))
        if not prepared.get("success"):
            return {
                "success": False,
                "summary": prepared.get("error", "failed to prepare verification inputs"),
                "verification_passed": False,
                "build_result": build,
                "prepare_result": prepared,
            }

        formal = self.run_jaspergold()
        return {
            "success": formal.get("success", False),
            "summary": formal.get("summary", ""),
            "verification_passed": formal.get("verification_passed", False),
            "output": formal.get("output", ""),
            "build_result": build,
            "prepare_result": prepared,
            "formal_result": formal,
            "jaspergold_result": formal.get("jaspergold_result"),
            "cex_count": formal.get("cex_count", 0),
            "proven_count": formal.get("proven_count", 0),
            "fst_files": formal.get("fst_files", []),
            "counterexample_path": formal.get("counterexample_path"),
        }

    def run_baseline_build(
        self,
        target: Optional[str] = None,
        timeout_s: int = 1800,
    ) -> Dict[str, Any]:
        """Build the cleaned workspace before any LLM stage is allowed to run."""
        return self._run_build(
            self.workspace.results_dir / "preflight",
            target=target,
            timeout_s=timeout_s,
            result_filename="baseline_build_result.json",
        )

    def run_compilation(
        self,
        target: Optional[str] = None,
        timeout_s: int = 1800,
    ) -> Dict[str, Any]:
        """Compile edits made by an agent stage."""
        return self._run_build(
            self._stage_dir("write_assertions"),
            target=target,
            timeout_s=timeout_s,
        )

    def _run_build(
        self,
        artifact_dir: Path,
        target: Optional[str] = None,
        timeout_s: int = 1800,
        result_filename: str = "build_result.json",
    ) -> Dict[str, Any]:
        artifact_dir.mkdir(parents=True, exist_ok=True)
        target = target or self.build_contract.get("recommended_make_target") or "auto"
        command = ["make", str(target)]
        env = os.environ.copy()
        env.update({str(k): str(v) for k, v in self.build_contract.get("env", {}).items()})
        self._prepare_mill_env(env)

        try:
            completed = subprocess.run(
                command,
                cwd=self.chisel_dir,
                env=env,
                text=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                timeout=timeout_s,
                check=False,
            )
            output = completed.stdout or ""
            returncode = completed.returncode
        except subprocess.TimeoutExpired as exc:
            output = exc.stdout or ""
            returncode = None
            result = {
                "success": False,
                "command": command,
                "cwd": str(self.chisel_dir),
                "env": self.build_contract.get("env", {}),
                "returncode": returncode,
                "output": output,
                "error": f"build timed out after {timeout_s}s",
                "generated_files": [],
                "top_module": None,
            }
            self._write_build_artifacts(artifact_dir, result, output, result_filename)
            return result

        generated_files = self.discover_generated_verilog_files()
        top_module = self.infer_top_module(generated_files)
        result = {
            "success": returncode == 0 and bool(generated_files),
            "command": command,
            "cwd": str(self.chisel_dir),
            "env": self.build_contract.get("env", {}),
            "returncode": returncode,
            "output": output,
            "generated_files": [str(path) for path in generated_files],
            "top_module": top_module,
        }
        if returncode != 0:
            result["error"] = self._tail(output)
        elif not generated_files:
            result["error"] = "build succeeded but no generated Verilog/SystemVerilog files were found"

        self._write_build_artifacts(artifact_dir, result, output, result_filename)
        return result

    def discover_generated_verilog_files(self) -> List[Path]:
        """Resolve build-contract globs to concrete Verilog/SystemVerilog files."""
        files: List[Path] = []
        for pattern in self.build_contract.get("generated_verilog_globs", []):
            resolved = self._resolve_workspace_case_pattern(pattern)
            files.extend(path for path in resolved.parent.glob(resolved.name) if path.is_file())
            if "**" in pattern:
                files.extend(path for path in self.case_dir.glob(self._strip_workspace_case(pattern)) if path.is_file())
        return _unique_sorted(files)

    def prepare_verification_inputs(self, top_module: Optional[str] = None) -> Dict[str, Any]:
        """Write the run-local Verilog file list and verify.tcl for JasperGold."""
        stage_dir = self._stage_dir("invoke_verification")
        stage_dir.mkdir(parents=True, exist_ok=True)
        verilog_files = self.discover_generated_verilog_files()
        if not verilog_files:
            result = {
                "success": False,
                "error": "no Verilog/SystemVerilog files available for JasperGold",
                "verilog_files": [],
                "top_module": top_module,
            }
            self._write_json(stage_dir / "verilog_files.json", result)
            return result

        top_module = top_module or self.infer_top_module(verilog_files)
        if not top_module:
            result = {
                "success": False,
                "error": "could not infer top module from generated Verilog",
                "verilog_files": [str(path) for path in verilog_files],
                "top_module": None,
            }
            self._write_json(stage_dir / "verilog_files.json", result)
            return result

        file_records = [{"path": str(path), "relative_to_verilog": self._rel_to_verilog(path)} for path in verilog_files]
        verify_tcl = self.build_verify_tcl(verilog_files, top_module)
        (stage_dir / "verify.tcl").write_text(verify_tcl, encoding="utf-8")
        (self.case_dir / "Verilog").mkdir(parents=True, exist_ok=True)
        (self.case_dir / "Verilog" / "verify.tcl").write_text(verify_tcl, encoding="utf-8")

        result = {
            "success": True,
            "verilog_files": [str(path) for path in verilog_files],
            "file_records": file_records,
            "top_module": top_module,
            "verify_tcl": str(stage_dir / "verify.tcl"),
        }
        self._write_json(stage_dir / "verilog_files.json", result)
        return result

    def build_verify_tcl(self, verilog_files: List[Path], top_module: str) -> str:
        """Build a conservative JasperGold Tcl script for the copied case."""
        analyze_files = " ".join(_tcl_quote(self._rel_to_verilog(path)) for path in verilog_files)
        return "\n".join(
            [
                "# Auto-generated by ChiselLMFV CoupledL2 backend.",
                "clear -all",
                f"analyze -sv {analyze_files}",
                f"elaborate -bbox_a 300000 -top {top_module}",
                "clock clock",
                "reset reset",
                "prove -all",
                "report",
                "",
            ]
        )

    def run_jaspergold(self, timeout_s: int = 3600) -> Dict[str, Any]:
        """Run JasperGold in the case-local Verilog directory and parse its log."""
        stage_dir = self._stage_dir("invoke_verification")
        stage_dir.mkdir(parents=True, exist_ok=True)
        command = ["jg", "-batch", "verify.tcl"]
        try:
            completed = subprocess.run(
                command,
                cwd=self.case_dir / "Verilog",
                env=os.environ.copy(),
                text=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                timeout=timeout_s,
                check=False,
            )
            output = completed.stdout or ""
            returncode = completed.returncode
        except subprocess.TimeoutExpired as exc:
            output = exc.stdout or ""
            returncode = None

        (stage_dir / "jg.log").write_text(output, encoding="utf-8")
        trace_dir = self._collect_trace_artifacts(stage_dir)
        parsed = parse_jaspergold_report(output, trace_dir=stage_dir / "traces")
        if trace_dir:
            parsed["trace_dir"] = str(trace_dir)
        parsed["returncode"] = returncode
        parsed["command"] = command
        self._write_json(stage_dir / "formal_result.json", parsed)
        self._write_json(stage_dir / "property_status.json", parsed.get("property_statuses", {}))

        cex_count = parsed.get("cex_count", 0)
        proven_count = parsed.get("proven_count", 0)
        verification_passed = returncode == 0 and cex_count == 0 and not parsed.get("inconclusive_count", 0)
        summary = (
            f"Verification found {cex_count} counterexamples, {proven_count} proven"
            if cex_count
            else f"All {proven_count} assertions proven"
        )
        if returncode not in (0, None) and not parsed.get("property_statuses"):
            summary = "JasperGold failed before producing property statuses"

        traces = parsed.get("trace_artifacts", [])
        return {
            "success": returncode == 0 or bool(parsed.get("property_statuses")),
            "summary": summary,
            "verification_passed": verification_passed,
            "output": output,
            "jaspergold_result": parsed,
            "cex_count": cex_count,
            "proven_count": proven_count,
            "fst_files": traces,
            "counterexample_path": traces[0] if traces else None,
        }

    def _collect_trace_artifacts(self, stage_dir: Path) -> Optional[Path]:
        """Copy JasperGold waveform artifacts into the stage-3 traces directory."""
        source_dir = self.case_dir / "Verilog"
        traces = [
            path
            for suffix in ("*.fst", "*.vcd")
            for path in source_dir.glob(suffix)
            if path.is_file()
        ]
        if not traces:
            return None

        trace_dir = stage_dir / "traces"
        trace_dir.mkdir(parents=True, exist_ok=True)
        for path in traces:
            shutil.copy2(path, trace_dir / path.name)
        return trace_dir

    def infer_top_module(self, verilog_files: List[Path]) -> Optional[str]:
        """Infer a top module, preferring VerifyTop variants."""
        modules: List[str] = []
        for path in verilog_files:
            try:
                text = path.read_text(encoding="utf-8", errors="ignore")
            except OSError:
                continue
            modules.extend(re.findall(r"^\s*module\s+([A-Za-z_][A-Za-z0-9_$]*)\b", text, flags=re.MULTILINE))
        for name in modules:
            if name.startswith("VerifyTop"):
                return name
        return modules[0] if modules else None

    def scan_generated_assertions(self) -> Dict[str, Any]:
        files = self.discover_generated_verilog_files()
        generated_assertions = []
        for path in files:
            text = path.read_text(encoding="utf-8", errors="ignore")
            generated_assertions.extend(_scan_verilog_assertions(path, text))

        formal_surface = self._load_json(self.workspace.indexes_dir / "formal_surface.json")
        source_assertions = formal_surface.get("assertions", [])
        source_count = int(formal_surface.get("assertion_count", len(source_assertions)))
        count = len(generated_assertions)
        success = count > 0
        result = {
            "success": success,
            "assertion_count": count,
            "assertion_files": sorted({item["file"] for item in generated_assertions}),
            "files_checked": [str(path) for path in files],
            "generated_assertions": generated_assertions,
        }
        if not success:
            result["error"] = "generated Verilog/SystemVerilog contains no assertions"

        assertion_map = {
            "source_assertions": source_assertions,
            "source_assertion_count": source_count,
            "generated_assertions": generated_assertions,
            "generated_assertion_count": count,
            "all_source_assertions_emitted": bool(source_count and count >= source_count),
        }
        assertion_plan = {
            "property_category": self.workspace.config.property_category,
            "source_assertion_count": source_count,
            "generated_verilog_files": [str(path) for path in files],
            "acceptance": {
                "requires_generated_assertions": True,
                "requires_stable_labels": True,
            },
        }
        stage_dir = self._stage_dir("write_assertions")
        self._write_json(stage_dir / "generated_assertion_scan.json", result)
        self._write_json(stage_dir / "assertion_map.json", assertion_map)
        self._write_json(stage_dir / "assertion_plan.json", assertion_plan)
        return result

    def _stage_dir(self, stage: str) -> Path:
        from .stages import get_stage_spec

        return self.workspace.results_dir / "by_stage" / get_stage_spec(stage).directory_name

    def _resolve_workspace_case_pattern(self, pattern: str) -> Path:
        return self.case_dir / self._strip_workspace_case(pattern)

    @staticmethod
    def _strip_workspace_case(pattern: str) -> str:
        prefix = "workspace/case/"
        return pattern[len(prefix):] if pattern.startswith(prefix) else pattern

    def _rel_to_verilog(self, path: Path) -> str:
        return os.path.relpath(str(path), str(self.case_dir / "Verilog")).replace(os.sep, "/")

    @staticmethod
    def _load_json(path: Path) -> Dict[str, Any]:
        return json.loads(path.read_text(encoding="utf-8"))

    @staticmethod
    def _write_json(path: Path, value: Dict[str, Any]) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(value, indent=2, ensure_ascii=False, sort_keys=True) + "\n", encoding="utf-8")

    def _write_build_artifacts(
        self,
        stage_dir: Path,
        result: Dict[str, Any],
        output: str,
        result_filename: str = "build_result.json",
    ) -> None:
        (stage_dir / "build.log").write_text(output, encoding="utf-8")
        self._write_json(stage_dir / result_filename, result)
        self._write_json(
            stage_dir / "generated_files.json",
            {
                "generated_files": result.get("generated_files", []),
                "top_module": result.get("top_module"),
            },
        )

    @staticmethod
    def _tail(text: str, max_lines: int = 40) -> str:
        lines = text.splitlines()
        return "\n".join(lines[-max_lines:])

    def _prepare_mill_env(self, env: Dict[str, str]) -> None:
        """Make case-local Makefiles resolve a pinned Mill launcher."""
        mill = self._find_mill_executable()
        if not mill:
            return

        wrapper_dir = self.workspace.run_dir / "tool_wrappers"
        wrapper_dir.mkdir(parents=True, exist_ok=True)
        wrapper = wrapper_dir / "mill"
        wrapper.write_text(
            "#!/usr/bin/env bash\n"
            f'exec "{mill}" --mill-version "{MILL_VERSION}" "$@"\n',
            encoding="utf-8",
        )
        wrapper.chmod(0o755)
        existing_path = env.get("PATH", "")
        env["PATH"] = str(wrapper_dir) + (os.pathsep + existing_path if existing_path else "")
        env["CHISELLMFV_MILL"] = str(mill)
        env["CHISELLMFV_MILL_VERSION"] = MILL_VERSION

    @staticmethod
    def _find_mill_executable() -> Optional[Path]:
        found = shutil.which("mill")
        if found:
            return Path(found).resolve()
        home_mill = Path.home() / "mill"
        if home_mill.is_file() and os.access(home_mill, os.X_OK):
            return home_mill.resolve()
        return None


def parse_jaspergold_report(log_text: str, trace_dir: Optional[Path] = None) -> Dict[str, Any]:
    """Parse common JasperGold report lines into a stage-3 FormalResult shape."""
    property_statuses: Dict[str, Dict[str, Any]] = {}
    pattern = re.compile(
        r"^\s*(?:\[\d+\]\s+)?"
        r"(?P<name>\S+)\s+"
        r"(?P<status>proven|cex|covered|bounded_proven|unreachable|undetermined|unknown|error)\s+"
        r"(?P<engine>\S+)\s+"
        r"(?P<bound>\S+)\s+"
        r"(?P<time>[\d.]+\s*\w*)",
        re.IGNORECASE | re.MULTILINE,
    )
    for match in pattern.finditer(log_text):
        status = match.group("status").lower()
        name = match.group("name")
        trace = _match_trace_for_property(name, trace_dir)
        property_statuses[name] = {
            "name": name,
            "status": status,
            "engine": match.group("engine"),
            "bound": match.group("bound"),
            "time": match.group("time").strip(),
        }
        if trace:
            property_statuses[name]["fst_file"] = str(trace)

    proven = sorted(name for name, item in property_statuses.items() if item["status"] in {"proven", "covered", "bounded_proven", "unreachable"})
    failing = sorted(name for name, item in property_statuses.items() if item["status"] == "cex")
    inconclusive = sorted(name for name, item in property_statuses.items() if item["status"] in INCONCLUSIVE_STATUSES)
    trace_artifacts = []
    if trace_dir and trace_dir.exists():
        trace_artifacts = [str(path) for path in sorted(trace_dir.glob("*")) if path.suffix in {".fst", ".vcd"}]
    assertions = [dict(property_statuses[name]) for name in sorted(property_statuses)]
    cex_assertions = [dict(property_statuses[name]) for name in failing]
    timed_out = bool(re.search(r"\b(time(?:d)?\s*out|timeout)\b", log_text, flags=re.IGNORECASE))

    return {
        "analyze_ok": _phase_failed(log_text, "analyze") is False,
        "elaborate_ok": _phase_failed(log_text, "elaborate") is False,
        "property_statuses": property_statuses,
        "assertions": assertions,
        "cex_assertions": cex_assertions,
        "proven_properties": proven,
        "failing_properties": failing,
        "inconclusive_properties": inconclusive,
        "proven_count": len(proven),
        "cex_count": len(failing),
        "inconclusive_count": len(inconclusive),
        "timed_out": timed_out,
        "trace_artifacts": trace_artifacts,
    }


def _phase_failed(text: str, phase: str) -> bool:
    return bool(re.search(rf"\b{re.escape(phase)}\b.*\b(error|failed|failure)\b", text, flags=re.IGNORECASE))


def _scan_verilog_assertions(path: Path, text: str) -> List[Dict[str, Any]]:
    assertions: List[Dict[str, Any]] = []
    pattern = re.compile(
        r"^\s*(?:(?P<label>[A-Za-z_][A-Za-z0-9_$]*)\s*:\s*)?"
        r"(?P<body>assert\s*(?:property|final)?\s*\([^;]*;?)",
        re.IGNORECASE | re.MULTILINE,
    )
    for match in pattern.finditer(text):
        line = text.count("\n", 0, match.start()) + 1
        label = match.group("label")
        assertions.append({
            "file": str(path),
            "line": line,
            "label": label,
            "text": " ".join(match.group("body").split())[:240],
        })
    return assertions


def _match_trace_for_property(name: str, trace_dir: Optional[Path]) -> Optional[Path]:
    if not trace_dir or not trace_dir.exists():
        return None
    normalized = re.sub(r"[^A-Za-z0-9]+", "_", name).strip("_").lower()
    for path in sorted(trace_dir.glob("*")):
        if path.suffix not in {".fst", ".vcd"}:
            continue
        candidate = re.sub(r"[^A-Za-z0-9]+", "_", path.stem).strip("_").lower()
        if normalized and (normalized in candidate or candidate in normalized):
            return path
    return None


def _unique_sorted(paths: List[Path]) -> List[Path]:
    return sorted({path.resolve() for path in paths}, key=lambda path: path.as_posix())


def _tcl_quote(value: str) -> str:
    escaped = value.replace("\\", "\\\\").replace("{", "\\{").replace("}", "\\}")
    return "{" + escaped + "}"
