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

from .property_catalog import load_property_profile
from .rtl_property_labeler import label_rtl_properties
from .workspace import CoupledL2Workspace, build_protocol_evidence


INCONCLUSIVE_STATUSES = {"undetermined", "unknown", "error"}
MILL_VERSION = "0.11.5"


def _subprocess_output_text(output: Any) -> str:
    """Normalize subprocess output, including TimeoutExpired byte payloads."""
    if output is None:
        return ""
    if isinstance(output, bytes):
        return output.decode("utf-8", errors="replace")
    return str(output)


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
            scan = self.scan_generated_properties()
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

        stage2_dir = self._stage_dir("bind_properties")
        manifest_path = stage2_dir / "binding_manifest.json"
        traceability_path = stage2_dir / "assertion_traceability.json"
        if not manifest_path.is_file() or not traceability_path.is_file():
            return {
                "success": False,
                "summary": "Stage 2 traceability artifacts are missing",
                "verification_passed": False,
                "build_result": build,
            }
        manifest = self._load_json(manifest_path)
        traceability = self._load_json(traceability_path)
        catalog = load_property_profile(self.workspace.config.property_profile)
        traceability = _enrich_traceability(traceability, catalog)
        try:
            relabelled = label_rtl_properties(
                [Path(path) for path in build.get("generated_files", [])],
                manifest,
                catalog,
            )
        except ValueError as exc:
            return {
                "success": False,
                "summary": f"deterministic RTL relabelling failed: {exc}",
                "verification_passed": False,
                "build_result": build,
            }
        expected_labels = {
            item["rtl_label"]
            for prop in traceability.get("properties", [])
            for item in prop.get("rtl_properties", [])
        }
        actual_labels = {item.rtl_label for item in relabelled}
        if actual_labels != expected_labels:
            return {
                "success": False,
                "summary": "rebuilt RTL property labels do not match Stage 2 traceability",
                "verification_passed": False,
                "build_result": build,
                "expected_labels": sorted(expected_labels),
                "actual_labels": sorted(actual_labels),
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
        jaspergold_result = formal.get("jaspergold_result") or {}
        property_statuses = jaspergold_result.get("property_statuses")
        if not isinstance(property_statuses, dict) or not property_statuses:
            return {
                "success": False,
                "summary": formal.get(
                    "summary",
                    "JasperGold failed before producing property statuses",
                ),
                "verification_passed": False,
                "output": formal.get("output", ""),
                "build_result": build,
                "prepare_result": prepared,
                "formal_result": formal,
                "jaspergold_result": jaspergold_result,
                "cex_count": formal.get("cex_count", 0),
                "proven_count": formal.get("proven_count", 0),
                "fst_files": formal.get("fst_files", []),
                "counterexample_path": formal.get("counterexample_path"),
            }
        try:
            property_map = join_property_results(
                traceability,
                jaspergold_result,
            )
        except ValueError as exc:
            return {
                "success": False,
                "summary": f"JasperGold traceability join failed: {exc}",
                "verification_passed": False,
                "build_result": build,
                "prepare_result": prepared,
                "formal_result": formal,
            }
        self._write_json(
            self._stage_dir("invoke_verification") / "property_result_map.json",
            property_map,
        )
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
            "property_result_map": property_map,
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
            self._stage_dir("bind_properties"),
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
            output = _subprocess_output_text(exc.stdout)
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
            matched = [
                path for path in resolved.parent.glob(resolved.name) if path.is_file()
            ]
            if "**" in pattern:
                matched.extend(
                    path
                    for path in self.case_dir.glob(self._strip_workspace_case(pattern))
                    if path.is_file()
                )
            files.extend(matched)
            for path in matched:
                if Path(path).match("VerifyTop*.sv"):
                    files.extend(
                        sibling
                        for suffix in ("*.v", "*.sv")
                        for sibling in path.parent.glob(suffix)
                        if sibling.is_file()
                    )
        return _unique_sorted(files)

    def prepare_verification_inputs(self, top_module: Optional[str] = None) -> Dict[str, Any]:
        """Write the run-local Verilog file list and verify.tcl for JasperGold."""
        stage_dir = self._stage_dir("invoke_verification")
        stage_dir.mkdir(parents=True, exist_ok=True)
        source_files = self.discover_generated_verilog_files()
        if not source_files:
            result = {
                "success": False,
                "error": "no Verilog/SystemVerilog files available for JasperGold",
                "verilog_files": [],
                "top_module": top_module,
            }
            self._write_json(stage_dir / "verilog_files.json", result)
            return result

        prepared_dir = stage_dir / "rtl_inputs"
        verilog_files = []
        for index, source in enumerate(source_files):
            destination = prepared_dir / f"{index:02d}_{source.name}"
            materialize_jaspergold_input(source, destination)
            verilog_files.append(destination)

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

        file_records = [
            {
                "source_path": str(source),
                "path": str(path),
                "relative_to_verilog": self._rel_to_verilog(path),
            }
            for source, path in zip(source_files, verilog_files)
        ]
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
        lines = [
            "# Auto-generated by ChiselLMFV CoupledL2 backend.",
            "clear -all",
            f"analyze -sv {analyze_files}",
            f"elaborate -bbox_a 300000 -top {top_module}",
            "clock clock",
            "reset reset",
            "prove -all",
            "report",
        ]
        trace_properties = self._expected_trace_property_ids(top_module)
        if trace_properties:
            lines.extend(
                [
                    "file mkdir traces",
                    "set_trace_optimization standard",
                    "proc chisellmfv_save_trace {property filename} {",
                    "  if {[catch {visualize -violation -property $property} result]} {",
                    "    puts \"CHISELLMFV_TRACE_SKIP $property $result\"",
                    "    return",
                    "  }",
                    "  if {[catch {visualize -save -force -vcd $filename} result]} {",
                    "    puts \"CHISELLMFV_TRACE_SAVE_FAILED $property $result\"",
                    "  } else {",
                    "    puts \"CHISELLMFV_TRACE_SAVED $property $filename\"",
                    "  }",
                    "}",
                ]
            )
            for property_id in trace_properties:
                filename = "traces/" + _safe_trace_filename(property_id) + ".vcd"
                lines.append(
                    "chisellmfv_save_trace "
                    + _tcl_quote(property_id)
                    + " "
                    + _tcl_quote(filename)
                )
        lines.append("")
        return "\n".join(lines)

    def run_jaspergold(self, timeout_s: int = 3600) -> Dict[str, Any]:
        """Run JasperGold in the case-local Verilog directory and parse its log."""
        stage_dir = self._stage_dir("invoke_verification")
        stage_dir.mkdir(parents=True, exist_ok=True)
        project_dir = stage_dir / "jgproject"
        if project_dir.exists():
            shutil.rmtree(project_dir)
        command = ["jg", "-batch", "-proj", str(project_dir), "verify.tcl"]
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
            output = _subprocess_output_text(exc.stdout)
            returncode = None

        (stage_dir / "jg.log").write_text(output, encoding="utf-8")
        self._convert_vcd_trace_artifacts()
        trace_dir = self._collect_trace_artifacts(stage_dir)
        parsed = parse_jaspergold_report(output, trace_dir=trace_dir)
        if trace_dir:
            parsed["trace_dir"] = str(trace_dir)
        parsed["returncode"] = returncode
        parsed["command"] = command
        self._write_json(stage_dir / "formal_result.json", parsed)
        self._write_json(stage_dir / "property_status.json", parsed.get("property_statuses", {}))

        cex_count = parsed.get("cex_count", 0)
        proven_count = parsed.get("proven_count", 0)
        invalid_environment = bool(parsed.get("invalid_environment"))
        verification_passed = (
            returncode == 0
            and cex_count == 0
            and not parsed.get("inconclusive_count", 0)
            and not invalid_environment
        )
        summary = (
            f"Verification found {cex_count} counterexamples, {proven_count} proven"
            if cex_count
            else f"All {proven_count} assertions proven"
        )
        if invalid_environment:
            summary = "Invalid formal environment: assumption consistency failed"
        if returncode not in (0, None) and not parsed.get("property_statuses"):
            summary = "JasperGold failed before producing property statuses"

        traces = parsed.get("trace_artifacts", [])
        result = {
            "success": (returncode == 0 or bool(parsed.get("property_statuses"))) and not invalid_environment,
            "summary": summary,
            "verification_passed": verification_passed,
            "output": output,
            "jaspergold_result": parsed,
            "cex_count": cex_count,
            "proven_count": proven_count,
            "fst_files": traces,
            "counterexample_path": traces[0] if traces else None,
        }
        if invalid_environment:
            result["error_kind"] = "invalid_environment"
            result["invalid_environment"] = True
            result["environment_failure_kind"] = parsed.get("environment_failure_kind")
            result["environment_cex_properties"] = parsed.get("environment_cex_properties", [])
            result["counterexample_path"] = None
        return result

    def _collect_trace_artifacts(self, stage_dir: Path) -> Optional[Path]:
        """Copy JasperGold waveform artifacts into the stage-3 traces directory."""
        source_dir = self.case_dir / "Verilog"
        traces = [
            path
            for suffix in ("*.fst", "*.vcd")
            for root in (source_dir, source_dir / "traces")
            if root.is_dir()
            for path in root.glob(suffix)
            if path.is_file()
        ]
        if not traces:
            return None

        trace_dir = stage_dir / "traces"
        trace_dir.mkdir(parents=True, exist_ok=True)
        for path in traces:
            shutil.copy2(path, trace_dir / path.name)
        return trace_dir

    def _convert_vcd_trace_artifacts(self) -> None:
        """Best-effort convert JasperGold VCD traces to FST for waveform tools."""
        converter = shutil.which("vcd2fst")
        if not converter:
            return
        source_dir = self.case_dir / "Verilog"
        roots = [source_dir, source_dir / "traces"]
        for root in roots:
            if not root.is_dir():
                continue
            for vcd in sorted(root.glob("*.vcd")):
                fst = vcd.with_suffix(".fst")
                if fst.exists():
                    continue
                subprocess.run(
                    [converter, str(vcd), str(fst)],
                    cwd=root,
                    stdout=subprocess.PIPE,
                    stderr=subprocess.STDOUT,
                    text=True,
                    timeout=300,
                    check=False,
                )

    def _expected_trace_property_ids(self, top_module: str) -> List[str]:
        traceability_path = (
            self._stage_dir("bind_properties") / "assertion_traceability.json"
        )
        if not traceability_path.is_file():
            return []
        try:
            traceability = self._load_json(traceability_path)
        except (OSError, json.JSONDecodeError):
            return []
        labels = [
            item.get("rtl_label")
            for prop in traceability.get("properties", [])
            for item in prop.get("rtl_properties", [])
        ]
        property_ids = []
        for label in labels:
            if not isinstance(label, str) or not label:
                continue
            property_ids.append(f"{top_module}.{label}")
        return sorted(set(property_ids))

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

    def scan_generated_properties(self) -> Dict[str, Any]:
        files = self.discover_generated_verilog_files()
        generated_assertions = []
        for path in files:
            text = path.read_text(encoding="utf-8", errors="ignore")
            generated_assertions.extend(_scan_verilog_assertions(path, text))

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
        r"^\s*\[\d+\]\s+"
        r"(?P<name>\S+)\s+"
        r"(?P<status>proven|cex|covered|bounded_proven|unreachable|undetermined|unknown|error)\s+"
        r"(?P<engine>\S+)\s+"
        r"(?P<bound>.+?)\s+"
        r"(?P<time>\d+(?:\.\d+)?\s*\w*)\s*$",
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
            "bound": " ".join(match.group("bound").split()),
            "time": match.group("time").strip(),
        }
        if trace:
            property_statuses[name]["fst_file"] = str(trace)

    environment_cex = sorted(
        name
        for name, item in property_statuses.items()
        if item["status"] == "cex" and _is_environment_property(name, log_text)
    )
    proven = sorted(name for name, item in property_statuses.items() if item["status"] in {"proven", "covered", "bounded_proven", "unreachable"})
    failing = sorted(
        name
        for name, item in property_statuses.items()
        if item["status"] == "cex" and name not in environment_cex
    )
    inconclusive = sorted(name for name, item in property_statuses.items() if item["status"] in INCONCLUSIVE_STATUSES)
    trace_artifacts = []
    if trace_dir and trace_dir.exists():
        trace_artifacts = [str(path) for path in sorted(trace_dir.glob("*")) if path.suffix in {".fst", ".vcd"}]
    assertions = [dict(property_statuses[name]) for name in sorted(property_statuses)]
    cex_assertions = [dict(property_statuses[name]) for name in failing]
    timed_out = bool(re.search(r"\b(time(?:d)?\s*out|timeout)\b", log_text, flags=re.IGNORECASE))

    invalid_environment = bool(environment_cex) or _has_assumption_conflict(log_text)
    result = {
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
    if invalid_environment:
        result["invalid_environment"] = True
        result["environment_failure_kind"] = "assumption_conflict"
        result["environment_cex_properties"] = environment_cex
    return result


def join_property_results(
    traceability: Dict[str, Any],
    jaspergold_report: Dict[str, Any],
) -> Dict[str, Any]:
    """Join exact JasperGold property IDs to concrete Stage 2 RTL labels."""
    statuses = jaspergold_report.get("property_statuses")
    if not isinstance(statuses, dict):
        raise ValueError("JasperGold report has no property_statuses object")
    trace_entries: Dict[str, Dict[str, Any]] = {}
    for prop in traceability.get("properties", []):
        for rtl in prop.get("rtl_properties", []):
            label = rtl.get("rtl_label")
            if not isinstance(label, str) or label in trace_entries:
                raise ValueError("traceability contains a missing or duplicate RTL label")
            trace_entries[label] = prop
    if not trace_entries:
        raise ValueError("traceability contains no concrete RTL labels")

    matches: Dict[str, list[tuple[str, Dict[str, Any]]]] = {
        label: [] for label in trace_entries
    }
    unmapped_property_labels = []
    for property_id, status in statuses.items():
        matched = [
            label
            for label in trace_entries
            if re.search(rf"(?<![A-Za-z0-9_]){re.escape(label)}(?![A-Za-z0-9_])", property_id)
        ]
        if re.search(r"\b(?:CL2|TL)_[A-Z0-9_]+", property_id) and not matched:
            unmapped_property_labels.append(property_id)
        if len(matched) > 1:
            raise ValueError(f"JasperGold property matches multiple RTL labels: {property_id}")
        if matched:
            matches[matched[0]].append((property_id, status))
    if unmapped_property_labels:
        raise ValueError(
            f"unmapped JasperGold CL2/TL properties: {sorted(unmapped_property_labels)}"
        )
    for label, items in matches.items():
        primary = [
            item
            for item in items
            if re.search(rf"{re.escape(label)}$", item[0])
        ]
        if len(primary) != 1:
            raise ValueError(
                "RTL label must match exactly one primary JasperGold property: "
                f"{label}"
            )

    properties = []
    for prop in traceability.get("properties", []):
        joined = []
        for rtl in prop.get("rtl_properties", []):
            label = rtl["rtl_label"]
            for property_id, status in matches[label]:
                item = {
                    "rtl_label": label,
                    "jaspergold_property_id": property_id,
                    "property_role": (
                        "primary"
                        if re.search(rf"{re.escape(label)}$", property_id)
                        else "auxiliary"
                    ),
                    "status": status.get("status"),
                }
                trace = status.get("fst_file") or status.get("counterexample_path")
                if trace:
                    item["counterexample_path"] = trace
                joined.append(item)
        record = {
            key: prop[key]
            for key in (
                "instance_id",
                "property_schema_id",
                "template_id",
                "base_label",
            )
        }
        for key in ("binding_manifest_path", "source", "protocol_rule"):
            if key in prop:
                record[key] = prop[key]
        record["jaspergold_properties"] = joined
        properties.append(record)
    return {
        "schema_version": "property_result_map.v1",
        "properties": properties,
    }


def _enrich_traceability(traceability: Dict[str, Any], catalog: Any) -> Dict[str, Any]:
    """Attach repository schema and protocol-rule provenance before Stage 3 join."""
    enriched = dict(traceability)
    evidence_by_locator = {
        item.get("locator"): item
        for item in build_protocol_evidence(catalog).get("rules", [])
    }
    properties = []
    for original in traceability.get("properties", []):
        prop = dict(original)
        schema = catalog.schemas.get(prop.get("property_schema_id"))
        if schema is not None:
            source = schema["source"]
            prop.setdefault("source", source)
            protocol_rule = evidence_by_locator.get(source.get("locator"))
            if protocol_rule is not None:
                prop.setdefault("protocol_rule", protocol_rule)
        properties.append(prop)
    enriched["properties"] = properties
    return enriched


def _phase_failed(text: str, phase: str) -> bool:
    return bool(re.search(rf"\b{re.escape(phase)}\b.*\b(error|failed|failure)\b", text, flags=re.IGNORECASE))


def _is_environment_property(name: str, log_text: str) -> bool:
    if name in {":noConflict", ":noDeadEnd", ":live"}:
        return True
    lowered = name.lower()
    return (
        "noconflict" in lowered
        or "assumption" in lowered and "conflict" in lowered
        or _has_assumption_conflict(log_text) and lowered.startswith(":")
    )


def _has_assumption_conflict(text: str) -> bool:
    patterns = [
        r":noConflict\s+cex",
        r"\bassumption\b.*\b(conflict|inconsistent)\b",
        r"\breset\b.*\b(conflict|contradict)\b",
    ]
    return any(re.search(pattern, text, flags=re.IGNORECASE) for pattern in patterns)


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


def _safe_trace_filename(value: str) -> str:
    return re.sub(r"[^A-Za-z0-9_.-]+", "_", value).strip("_") or "trace"


def materialize_jaspergold_input(source: Path, destination: Path) -> None:
    """Copy CIRCT output while removing its non-Verilog resource file list."""
    text = Path(source).read_text(encoding="utf-8", errors="ignore")
    marker = re.search(
        r'(?m)^// ----- 8< ----- FILE "firrtl_black_box_resource_files\.f" ----- 8< -----\s*$',
        text,
    )
    if marker is not None:
        text = text[:marker.start()].rstrip() + "\n"
    text = re.sub(
        r"(?m)^(\s*parameter\s+string\s+[A-Za-z_][A-Za-z0-9_$]*\s*);",
        r'\1 = "";',
        text,
    )
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text(text, encoding="utf-8")
