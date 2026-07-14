"""CoupledL2 build and JasperGold backend.

This module keeps large-project build and proof commands behind a deterministic
Python API so LLM tools do not need arbitrary shell access.
"""

from __future__ import annotations

import json
import hashlib
import os
import re
import selectors
import signal
import shutil
import subprocess
import tempfile
import time
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

from .property_catalog import load_property_profile
from .rtl_property_labeler import label_rtl_properties
from .workspace import CoupledL2Workspace
from .artifacts import file_sha256
from .result_contract import (
    ResultContractError,
    reduce_property_result_map,
    validate_operation_plan,
)


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
        stage2_dir = self._stage_dir("bind_properties")
        manifest_path = stage2_dir / "binding_manifest.json"
        package_path = stage2_dir / "property_package.json"
        delta_path = stage2_dir / "assertion_delta.json"
        if not all(path.is_file() for path in (manifest_path, package_path, delta_path)):
            return {
                "success": False,
                "summary": "Stage 2 canonical property artifacts are missing",
                "execution_status": "tool_error",
                "formal_outcome": "not_run",
            }
        manifest = self._load_json(manifest_path)
        package = self._load_json(package_path)
        traceability = package.get("traceability")
        if not isinstance(traceability, dict):
            raise ValueError("property package has no traceability ledger")
        operation_plan = package.get("operation_plan")
        if not isinstance(operation_plan, dict):
            raise ValueError("property package has no verification operation plan")
        validate_operation_plan(operation_plan)
        catalog = load_property_profile(self.workspace.config.property_profile)
        build = self._run_build(self._stage_dir("invoke_verification"))
        if not build.get("success"):
            return self._finalize_stage3_without_tool(
                traceability,
                operation_plan,
                reason="compilation_failed",
                summary="Compilation failed",
                build_result=build,
            )
        try:
            relabelled = label_rtl_properties(
                [Path(path) for path in build.get("generated_files", [])],
                manifest,
                catalog,
                require_evidence=any(
                    item["role"] != "primary_assertion"
                    for item in operation_plan["operations"]
                ),
            )
        except ValueError as exc:
            return self._finalize_stage3_without_tool(
                traceability,
                operation_plan,
                reason="rtl_relabelling_failed",
                summary=f"deterministic RTL relabelling failed: {exc}",
                build_result=build,
            )
        expected_labels = {
            item["rtl_property_id"].rsplit(".", 1)[-1]
            for item in operation_plan["operations"]
        }
        actual_labels = {item.rtl_label for item in relabelled}
        if actual_labels != expected_labels:
            result = self._finalize_stage3_without_tool(
                traceability,
                operation_plan,
                reason="rtl_label_set_mismatch",
                summary="rebuilt RTL property labels do not match Stage 2 traceability",
                build_result=build,
            )
            result["expected_labels"] = sorted(expected_labels)
            result["actual_labels"] = sorted(actual_labels)
            return result
        prepared = self.prepare_verification_inputs(top_module=build.get("top_module"))
        if not prepared.get("success"):
            return self._finalize_stage3_without_tool(
                traceability,
                operation_plan,
                reason="verification_input_preparation_failed",
                summary=prepared.get("error", "failed to prepare verification inputs"),
                build_result=build,
                prepare_result=prepared,
            )

        formal = self.run_jaspergold()
        jaspergold_result = formal.get("jaspergold_result") or {}
        try:
            property_map = join_property_results(
                traceability,
                jaspergold_result,
                operation_plan=operation_plan,
                property_profile_id=self.workspace.config.property_profile,
                property_package_sha256=file_sha256(package_path),
                assertion_delta_sha256=file_sha256(delta_path),
            )
        except (ValueError, ResultContractError) as exc:
            return {
                "success": False,
                "summary": f"JasperGold traceability join failed: {exc}",
                "build_result": build,
                "prepare_result": prepared,
                "backend_result": formal,
            }
        self._write_json(
            self._stage_dir("invoke_verification") / "property_result_map.json",
            property_map,
        )
        return {
            "success": formal.get("success", False),
            "summary": formal.get("summary", ""),
            "output": formal.get("output", ""),
            "build_result": build,
            "prepare_result": prepared,
            "backend_result": jaspergold_result,
            "cex_count": formal.get("cex_count", 0),
            "proven_count": formal.get("proven_count", 0),
            "trace_paths": formal.get("trace_paths", []),
            "trace_path": formal.get("trace_path"),
            "property_result_map": property_map,
            "execution_status": property_map.get("execution_status"),
            "formal_outcome": property_map.get("formal_outcome"),
            "accounted_operation_count": property_map.get("accounted_operation_count"),
            "expected_operation_count": property_map.get("expected_operation_count"),
        }

    def _finalize_stage3_without_tool(
        self,
        traceability: Dict[str, Any],
        operation_plan: Dict[str, Any],
        *,
        reason: str,
        summary: str,
        build_result: Dict[str, Any],
        prepare_result: Optional[Dict[str, Any]] = None,
    ) -> Dict[str, Any]:
        """Persist a total tool-error ledger when Stage 3 cannot launch JG."""
        expected = self._expected_operations()
        operation_results = [
            {
                "operation_id": item["operation_id"],
                "observed_property_id": None,
                "status": "tool_error",
                "reason": reason,
                "engine": None,
                "bound": None,
                "runtime_s": None,
                "trace_path": None,
            }
            for item in expected
        ]
        primary = [
            {
                "rtl_label": item["target"],
                "expected_property_id": item["rtl_property_id"],
                **result,
            }
            for item, result in zip(expected, operation_results)
            if item["role"] == "primary_assertion"
        ]
        formal = {
            "analyze_ok": False,
            "elaborate_ok": False,
            "property_statuses": {},
            "operation_results": operation_results,
            "primary_results": primary,
            "auxiliary_results": [],
            "execution_status": "tool_error",
            "formal_outcome": "inconclusive",
            "expected_count": len(expected),
            "accounted_count": len(operation_results),
            "primary_proven_count": 0,
            "primary_cex_count": 0,
            "primary_inconclusive_count": 0,
            "primary_not_run_count": 0,
            "primary_tool_error_count": len(primary),
            "timed_out": False,
            "summary": (
                f"0 proven, 0 cex, 0 inconclusive, 0 not_run, "
                f"{len(operation_results)} tool_error ({len(operation_results)}/{len(expected)} accounted)"
            ),
        }
        contract = self._load_formal_contract_artifact()
        formal["formal_contract_path"] = "../../preflight/formal_contract.json"
        formal["formal_contract_sha256"] = contract["sha256"]
        stage2_dir = self._stage_dir("bind_properties")
        property_map = join_property_results(
            traceability,
            formal,
            operation_plan=operation_plan,
            property_profile_id=self.workspace.config.property_profile,
            property_package_sha256=file_sha256(stage2_dir / "property_package.json"),
            assertion_delta_sha256=file_sha256(stage2_dir / "assertion_delta.json"),
        )
        stage_dir = self._stage_dir("invoke_verification")
        (stage_dir / "jaspergold.log").write_text(
            f"JasperGold was not launched: {reason}\n", encoding="utf-8"
        )
        self._write_json(stage_dir / "property_result_map.json", property_map)
        events_path = stage_dir / "proof_events.jsonl"
        events_path.write_text(
            "".join(
                json.dumps(
                    {
                        "schema_version": "proof_event.v1",
                        "event": "property_finalized",
                        "sequence": index,
                        **item,
                    },
                    ensure_ascii=False,
                    sort_keys=True,
                )
                + "\n"
                for index, item in enumerate(operation_results)
            ),
            encoding="utf-8",
        )
        return {
            "success": True,
            "summary": summary,
            "output": build_result.get("output", ""),
            "build_result": build_result,
            "prepare_result": prepare_result,
            "backend_result": formal,
            "cex_count": 0,
            "proven_count": 0,
            "trace_paths": [],
            "trace_path": None,
            "property_result_map": property_map,
            "execution_status": property_map["execution_status"],
            "formal_outcome": property_map["formal_outcome"],
            "accounted_operation_count": property_map["accounted_operation_count"],
            "expected_operation_count": property_map["expected_operation_count"],
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
        target = target or self.build_contract.get("recommended_make_target")
        if not isinstance(target, str) or not target:
            raise ValueError("build contract has no recommended_make_target")
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

        if not top_module:
            result = {
                "success": False,
                "error": "build result did not declare a top module",
                "verilog_files": [str(path) for path in verilog_files],
                "top_module": None,
            }
            self._write_json(stage_dir / "verilog_files.json", result)
            return result

        delta_path = self._stage_dir("bind_properties") / "assertion_delta.json"
        if not delta_path.is_file():
            result = {
                "success": False,
                "error": "Stage 2 assertion_delta.json is missing",
                "verilog_files": [str(path) for path in verilog_files],
                "top_module": top_module,
            }
            self._write_json(stage_dir / "verilog_files.json", result)
            return result
        delta = self._load_json(delta_path)
        if delta.get("schema_version") != "assertion_delta.v2":
            result = {
                "success": False,
                "error": "unsupported assertion delta schema",
                "verilog_files": [str(path) for path in verilog_files],
                "top_module": top_module,
            }
            self._write_json(stage_dir / "verilog_files.json", result)
            return result
        delta_top = delta.get("top_module")
        if delta_top and delta_top != top_module:
            result = {
                "success": False,
                "error": "rebuilt top module does not match assertion delta",
                "verilog_files": [str(path) for path in verilog_files],
                "top_module": top_module,
                "delta_top_module": delta_top,
            }
            self._write_json(stage_dir / "verilog_files.json", result)
            return result
        package = self._load_json(
            self._stage_dir("bind_properties") / "property_package.json"
        )
        operation_plan = package.get("operation_plan")
        validate_operation_plan(operation_plan)
        operations = operation_plan["operations"]
        property_ids = [item.get("rtl_property_id") for item in operations]
        if not property_ids or len(property_ids) != len(set(property_ids)):
            result = {
                "success": False,
                "error": "assertion delta has no unique concrete properties",
                "verilog_files": [str(path) for path in verilog_files],
                "top_module": top_module,
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
        formal_contract = self._load_formal_contract_artifact()
        verify_tcl = self.build_verify_tcl(
            verilog_files,
            top_module,
            operations=operations,
            formal_contract=formal_contract,
        )
        (stage_dir / "verify.tcl").write_text(verify_tcl, encoding="utf-8")
        (self.case_dir / "Verilog").mkdir(parents=True, exist_ok=True)
        (self.case_dir / "Verilog" / "verify.tcl").write_text(verify_tcl, encoding="utf-8")

        result = {
            "success": True,
            "verilog_files": [str(path) for path in verilog_files],
            "file_records": file_records,
            "top_module": top_module,
            "verify_tcl": str(stage_dir / "verify.tcl"),
            "assertion_delta": "../02_bind_properties/assertion_delta.json",
            "property_ids": property_ids,
            "formal_contract": "../../preflight/formal_contract.json",
            "formal_contract_sha256": formal_contract["sha256"],
        }
        self._write_json(stage_dir / "verilog_files.json", result)
        return result

    def build_verify_tcl(
        self,
        verilog_files: List[Path],
        top_module: str,
        *,
        operations: List[Dict[str, Any]],
        formal_contract: Dict[str, Any],
    ) -> str:
        """Build an exact, role-grouped JasperGold operation schedule."""
        analyze_files = " ".join(_tcl_quote(self._rel_to_verilog(path)) for path in verilog_files)
        resources = formal_contract["resources"]
        lines = [
            "# Auto-generated by ChiselLMFV CoupledL2 backend.",
            f"# formal_contract_sha256 {formal_contract['sha256']}",
            "clear -all",
            f"analyze -sv {analyze_files}",
            f"elaborate -bbox_a 300000 -top {top_module}",
            f"clock {formal_contract['clock']['signal']}",
            f"reset {formal_contract['reset']['signal']}",
            *formal_contract["preserved_assumptions"],
            f"set_prove_time_limit {resources['per_property_timeout_s']}s",
            f"set_engine_threads {resources['engine_threads']}",
            f"set_proofgrid_per_engine_max_jobs {resources['max_jobs']}",
        ]
        role_order = {
            "assumption_sat": 0,
            "trigger_cover": 1,
            "observer_cover": 2,
            "state_cover": 3,
            "primary_assertion": 4,
        }
        schedule = sorted(
            operations,
            key=lambda item: (role_order.get(item["role"], 99), item["operation_id"]),
        )
        if schedule:
            lines.extend(
                [
                    "file mkdir traces",
                    "set_trace_optimization standard",
                    "set_trace_optimization -irrelevant_value_computation true",
                    "proc chisellmfv_save_trace {property filename kind} {",
                    "  if {$kind eq \"primary_assertion\"} {",
                    "    set command [list visualize -violation -property $property]",
                    "  } else {",
                    "    set command [list visualize -property $property]",
                    "  }",
                    "  if {[catch {eval $command} result]} {",
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
            current_budget = None
            by_role = resources["by_role"]
            for operation in schedule:
                property_id = operation["rtl_property_id"]
                role = operation["role"]
                budget = by_role[operation["budget_class"]]["timeout_s"]
                if budget != current_budget:
                    lines.append(f"set_prove_time_limit {budget}s")
                    current_budget = budget
                lines.extend(
                    [
                        f"puts \"CHISELLMFV_PROPERTY_BEGIN {property_id}\"",
                        "set chisellmfv_outcome "
                        f"[prove -property {_tcl_quote(property_id)}]",
                        "report",
                    ]
                )
                filename = "traces/" + _safe_trace_filename(operation["operation_id"]) + ".vcd"
                expected_outcome = "cex" if role == "primary_assertion" else "covered"
                trace_needed = role == "primary_assertion" or operation["trace_required"]
                lines.extend(
                    [
                        f'if {{$chisellmfv_outcome eq "{expected_outcome}"}} {{',
                        "  chisellmfv_save_trace "
                        + _tcl_quote(property_id)
                        + " "
                        + _tcl_quote(filename)
                        + " "
                        + _tcl_quote(role),
                        "}",
                    ]
                ) if trace_needed else None
                lines.append(f"puts \"CHISELLMFV_PROPERTY_END {property_id}\"")
        lines.append("")
        return "\n".join(lines)

    def _load_formal_contract_artifact(self) -> Dict[str, Any]:
        path = self.workspace.results_dir / "preflight" / "formal_contract.json"
        if not path.is_file():
            raise ValueError("preflight formal contract artifact is missing")
        artifact = self._load_json(path)
        asset_sha = artifact.get("sha256")
        if not isinstance(asset_sha, str) or len(asset_sha) != 64:
            raise ValueError("preflight formal contract has no sha256")
        return artifact

    def run_jaspergold(self, timeout_s: Optional[int] = None) -> Dict[str, Any]:
        """Run JasperGold in the case-local Verilog directory and parse its log."""
        stage_dir = self._stage_dir("invoke_verification")
        stage_dir.mkdir(parents=True, exist_ok=True)
        formal_contract = self._load_formal_contract_artifact()
        timeout_s = timeout_s or int(formal_contract["resources"]["global_timeout_s"])
        expected = self._expected_operations()
        project_dir = stage_dir / "jgproject"
        if project_dir.exists():
            shutil.rmtree(project_dir)
        run_trace_dir = self.case_dir / "Verilog" / "traces"
        if run_trace_dir.exists():
            shutil.rmtree(run_trace_dir)
        command = ["jg", "-batch", "-proj", str(project_dir), "verify.tcl"]
        events_path = stage_dir / "proof_events.jsonl"
        events_path.write_text("", encoding="utf-8")
        streamed: List[Dict[str, Any]] = []

        def record_completed_property(property_id: str, segment: str) -> None:
            match = next(
                (item for item in expected if item["rtl_property_id"] == property_id),
                None,
            )
            if match is None:
                return
            operation = _account_streamed_operation(match, segment)
            streamed.append(operation)
            event = {
                "schema_version": "proof_event.v1",
                "event": "property_completed",
                "sequence": len(streamed) - 1,
                **operation,
            }
            with events_path.open("a", encoding="utf-8") as handle:
                handle.write(json.dumps(event, ensure_ascii=False, sort_keys=True) + "\n")
                handle.flush()

        output, returncode, outer_timed_out = self._run_jaspergold_process(
            command,
            timeout_s=timeout_s,
            log_path=stage_dir / "jaspergold.log",
            on_property_complete=record_completed_property,
        )
        self._convert_vcd_trace_artifacts()
        trace_dir = self._collect_trace_artifacts(stage_dir)
        parsed = parse_jaspergold_report(output, trace_dir=trace_dir)
        if trace_dir:
            parsed["trace_dir"] = str(trace_dir)
        parsed["returncode"] = returncode
        parsed["command"] = command
        parsed["outer_timed_out"] = outer_timed_out
        accounted = account_expected_operations(
            expected,
            parsed,
            log_text=output,
            returncode=returncode,
            outer_timed_out=outer_timed_out,
            trace_dir=trace_dir,
        )
        parsed.update(accounted)
        parsed["formal_contract_path"] = "../../preflight/formal_contract.json"
        parsed["formal_contract_sha256"] = formal_contract["sha256"]
        version_match = re.search(
            r"JasperGold Apps\s+([^\r\n]+)", output, flags=re.IGNORECASE
        )
        parsed["tool"] = {
            "name": "jaspergold",
            "version": (
                version_match.group(1).strip()
                if version_match
                else formal_contract["tool"]["version"]
            ),
        }
        for index, operation in enumerate(parsed["operation_results"]):
            event = {
                "schema_version": "proof_event.v1",
                "event": "property_finalized",
                "sequence": index,
                **operation,
            }
            with events_path.open("a", encoding="utf-8") as handle:
                handle.write(json.dumps(event, ensure_ascii=False, sort_keys=True) + "\n")

        cex_count = parsed.get("primary_cex_count", 0)
        proven_count = parsed.get("primary_proven_count", 0)
        invalid_environment = bool(parsed.get("invalid_environment"))
        summary = parsed["summary"]
        if invalid_environment:
            summary = "Invalid formal environment: assumption consistency failed"

        traces = parsed.get("trace_artifacts", [])
        cex_traces = [
            item.get("trace_path")
            for item in parsed["primary_results"]
            if item.get("status") == "cex" and item.get("trace_path")
        ]
        result = {
            "success": (
                parsed["execution_status"] == "completed"
                and not parsed.get("timed_out")
                and parsed.get("primary_not_run_count", 0) == 0
                and parsed.get("primary_tool_error_count", 0) == 0
                and not invalid_environment
            ),
            "summary": summary,
            "output": output,
            "jaspergold_result": parsed,
            "cex_count": cex_count,
            "proven_count": proven_count,
            "trace_paths": cex_traces,
            "trace_path": cex_traces[0] if cex_traces else None,
            "execution_status": parsed["execution_status"],
            "formal_outcome": parsed["formal_outcome"],
            "accounted_count": parsed["accounted_count"],
            "expected_count": parsed["expected_count"],
        }
        if invalid_environment:
            result["error_kind"] = "invalid_environment"
            result["invalid_environment"] = True
            result["environment_failure_kind"] = parsed.get("environment_failure_kind")
            result["environment_cex_properties"] = parsed.get("environment_cex_properties", [])
            result["trace_path"] = None
        return result

    def _run_jaspergold_process(
        self,
        command: List[str],
        *,
        timeout_s: int,
        log_path: Path,
        on_property_complete: Any,
    ) -> Tuple[str, Optional[int], bool]:
        """Stream JasperGold output and checkpoint completed property scopes."""
        try:
            process = subprocess.Popen(
                command,
                cwd=self.case_dir / "Verilog",
                env=os.environ.copy(),
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                start_new_session=True,
            )
        except OSError as exc:
            output = f"failed to start JasperGold: {exc}\n"
            log_path.write_text(output, encoding="utf-8")
            return output, 127, False
        assert process.stdout is not None
        selector = selectors.DefaultSelector()
        selector.register(process.stdout, selectors.EVENT_READ)
        deadline = time.monotonic() + timeout_s
        chunks: List[str] = []
        line_buffer = ""
        active_property: Optional[str] = None
        active_lines: List[str] = []
        timed_out = False
        with log_path.open("w", encoding="utf-8") as log_handle:
            while True:
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    timed_out = True
                    os.killpg(process.pid, signal.SIGTERM)
                    break
                events = selector.select(timeout=min(0.25, remaining))
                for key, _mask in events:
                    data = os.read(key.fileobj.fileno(), 65536)
                    if not data:
                        selector.unregister(key.fileobj)
                        continue
                    text = data.decode("utf-8", errors="replace")
                    chunks.append(text)
                    log_handle.write(text)
                    log_handle.flush()
                    line_buffer += text
                    lines = line_buffer.splitlines(keepends=True)
                    if lines and not lines[-1].endswith(("\n", "\r")):
                        line_buffer = lines.pop()
                    else:
                        line_buffer = ""
                    for line in lines:
                        begin = re.search(r"CHISELLMFV_PROPERTY_BEGIN\s+(\S+)", line)
                        end = re.search(r"CHISELLMFV_PROPERTY_END\s+(\S+)", line)
                        if begin:
                            active_property = begin.group(1)
                            active_lines = [line]
                            continue
                        if active_property is not None:
                            active_lines.append(line)
                        if end and active_property == end.group(1):
                            on_property_complete(active_property, "".join(active_lines))
                            active_property = None
                            active_lines = []
                if process.poll() is not None and not selector.get_map():
                    break
            if timed_out:
                try:
                    process.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    os.killpg(process.pid, signal.SIGKILL)
                    process.wait(timeout=10)
            elif process.poll() is None:
                process.wait(timeout=10)
            if line_buffer:
                # The bytes are already present in chunks/log; only line framing
                # retained this suffix for marker parsing.
                pass
        selector.close()
        return "".join(chunks), (None if timed_out else process.returncode), timed_out

    def _expected_operations(self) -> List[Dict[str, Any]]:
        package_path = self._stage_dir("bind_properties") / "property_package.json"
        plan = self._load_json(package_path).get("operation_plan")
        validate_operation_plan(plan)
        return [dict(item) for item in plan["operations"]]

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

    @staticmethod
    def _write_json_atomic(path: Path, value: Dict[str, Any]) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        temporary = path.with_suffix(path.suffix + ".tmp")
        temporary.write_text(
            json.dumps(value, indent=2, ensure_ascii=False, sort_keys=True) + "\n",
            encoding="utf-8",
        )
        os.replace(temporary, path)

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
        original_home = Path(env.get("HOME") or Path.home()).resolve()
        if not _directory_is_writable(original_home):
            self._prepare_writable_tool_home(env, original_home)
        existing_path = env.get("PATH", "")
        env["PATH"] = str(wrapper_dir) + (os.pathsep + existing_path if existing_path else "")
        env["CHISELLMFV_MILL"] = str(mill)
        env["CHISELLMFV_MILL_VERSION"] = MILL_VERSION

    def _prepare_writable_tool_home(
        self, env: Dict[str, str], original_home: Path
    ) -> None:
        """Preserve offline caches when the execution sandbox mounts HOME read-only."""
        tool_home = self.workspace.run_dir / "tool_home"
        tool_home.mkdir(parents=True, exist_ok=True)

        seed_cache = original_home / ".cache" / "coursier"
        if seed_cache.is_dir():
            shared_cache = Path(tempfile.gettempdir()) / "chisellmfv-coursier-cache"
            if not (shared_cache / "v1").is_dir():
                temporary = shared_cache.with_name(
                    f"{shared_cache.name}.{os.getpid()}.tmp"
                )
                if temporary.exists():
                    shutil.rmtree(temporary)
                shutil.copytree(seed_cache, temporary)
                try:
                    os.replace(temporary, shared_cache)
                except OSError:
                    if temporary.exists():
                        shutil.rmtree(temporary)
            cache_link = tool_home / ".cache" / "coursier"
            cache_link.parent.mkdir(parents=True, exist_ok=True)
            if not cache_link.exists():
                cache_link.symlink_to(shared_cache, target_is_directory=True)

        mill_download = original_home / ".cache" / "mill" / "download"
        if mill_download.is_dir():
            env["MILL_DOWNLOAD_PATH"] = str(mill_download)
        env["HOME"] = str(tool_home)
        user_home_opt = f"-Duser.home={tool_home}"
        for key in ("JAVA_OPTS", "JAVA_TOOL_OPTIONS"):
            existing = env.get(key, "").strip()
            env[key] = " ".join(item for item in (existing, user_home_opt) if item)

    @staticmethod
    def _find_mill_executable() -> Optional[Path]:
        found = shutil.which("mill")
        if found:
            return Path(found).resolve()
        home_mill = Path.home() / "mill"
        if home_mill.is_file() and os.access(home_mill, os.X_OK):
            return home_mill.resolve()
        return None


def _directory_is_writable(path: Path) -> bool:
    """Probe effective mount writability instead of trusting mode bits."""
    probe = path / f".chisellmfv-write-probe-{os.getpid()}"
    try:
        probe.write_text("", encoding="utf-8")
        probe.unlink()
        return True
    except OSError:
        return False


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
    timed_out = bool(re.search(
        r"\b(time(?:d)?\s*out|timeout|time\s+limit\s+expired)\b",
        log_text,
        flags=re.IGNORECASE,
    ))

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


def account_expected_operations(
    expected: List[Dict[str, Any]],
    parsed_report: Dict[str, Any],
    *,
    log_text: str,
    returncode: Optional[int],
    outer_timed_out: bool,
    trace_dir: Optional[Path],
) -> Dict[str, Any]:
    """Account every exact operation, including cover and assumption goals."""
    statuses = parsed_report.get("property_statuses")
    statuses = statuses if isinstance(statuses, dict) else {}
    expected_ids = {item["rtl_property_id"] for item in expected}
    auxiliary_results = [
        {
            "observed_property_id": property_id,
            "status": raw.get("status"),
            "engine": raw.get("engine"),
            "bound": raw.get("bound"),
            "runtime_s": _runtime_seconds(raw.get("time")),
        }
        for property_id, raw in sorted(statuses.items())
        if property_id not in expected_ids
    ]
    per_property_timeout = bool(
        re.search(r"Per property time limit expired", log_text, re.IGNORECASE)
    )
    rows = []
    for item in expected:
        property_id = item["rtl_property_id"]
        raw = statuses.get(property_id)
        trace = _match_trace_for_property(item["operation_id"], trace_dir)
        if raw is not None:
            raw_status = str(raw.get("status") or "").lower()
            if item["role"] == "primary_assertion" and raw_status in {
                "proven",
                "bounded_proven",
            }:
                status, reason = "proven", "tool_reported_proven"
            elif item["role"] != "primary_assertion" and raw_status == "covered":
                status, reason = "covered", "tool_reported_covered"
            elif raw_status == "unreachable":
                status, reason = "unreachable", "tool_reported_unreachable"
            elif item["role"] == "primary_assertion" and raw_status == "cex":
                status, reason = (
                    ("cex", "tool_reported_cex")
                    if trace
                    else ("inconclusive", "counterexample_trace_missing")
                )
            elif raw_status in INCONCLUSIVE_STATUSES:
                status, reason = "inconclusive", f"tool_reported_{raw_status}"
            else:
                status, reason = "tool_error", f"unexpected_{item['role']}_status:{raw_status}"
        elif per_property_timeout and (property_id in log_text or not statuses):
            status, reason = "inconclusive", "per_property_timeout"
        elif outer_timed_out:
            status, reason = (
                ("inconclusive", "global_timeout")
                if property_id in log_text
                else ("not_run", "global_timeout_before_operation")
            )
        elif returncode not in (0, None):
            status, reason = "tool_error", f"jaspergold_exit_{returncode}"
        elif returncode is None:
            status, reason = "not_run", "signal_termination_or_missing_exit"
        else:
            status, reason = "tool_error", "no_results_row"
        row = {
            "operation_id": item["operation_id"],
            "status": status,
            "reason": reason,
            "observed_property_id": property_id if raw else None,
            "engine": raw.get("engine") if raw else None,
            "bound": raw.get("bound") if raw else None,
            "runtime_s": _runtime_seconds(raw.get("time")) if raw else None,
            "trace_path": str(trace) if trace else None,
        }
        rows.append(row)

    primary = [
        {
            "rtl_label": item["target"],
            "expected_property_id": item["rtl_property_id"],
            **row,
        }
        for item, row in zip(expected, rows)
        if item["role"] == "primary_assertion"
    ]
    counts = {
        status: sum(row["status"] == status for row in rows)
        for status in ("proven", "cex", "covered", "unreachable", "inconclusive", "not_run", "tool_error")
    }
    primary_statuses = [row["status"] for row in primary]
    formal_outcome = (
        "cex"
        if "cex" in primary_statuses
        else "all_proven"
        if primary_statuses and all(status == "proven" for status in primary_statuses)
        else "not_run"
        if primary_statuses and all(status == "not_run" for status in primary_statuses)
        else "inconclusive"
    )
    execution_status = (
        "tool_error"
        if returncode not in (0, None) or counts["tool_error"]
        else "partial"
        if outer_timed_out or returncode is None or counts["not_run"] or counts["inconclusive"]
        else "completed"
    )
    return {
        "operation_results": rows,
        "primary_results": primary,
        "auxiliary_results": auxiliary_results,
        "expected_count": len(expected),
        "accounted_count": len(rows),
        "primary_proven_count": sum(row["status"] == "proven" for row in primary),
        "primary_cex_count": sum(row["status"] == "cex" for row in primary),
        "primary_not_run_count": sum(row["status"] == "not_run" for row in primary),
        "primary_tool_error_count": sum(row["status"] == "tool_error" for row in primary),
        "execution_status": execution_status,
        "formal_outcome": formal_outcome,
        "timed_out": outer_timed_out or per_property_timeout,
        "summary": ", ".join(f"{counts[name]} {name}" for name in counts)
        + f" ({len(rows)}/{len(expected)} accounted)",
    }


def _account_streamed_operation(
    expected: Dict[str, Any], segment: str
) -> Dict[str, Any]:
    parsed = parse_jaspergold_report(segment)
    raw = parsed.get("property_statuses", {}).get(expected["rtl_property_id"])
    raw_status = str((raw or {}).get("status") or "").lower()
    if expected["role"] == "primary_assertion" and raw_status in {"proven", "bounded_proven"}:
        status, reason = "proven", "tool_reported_proven"
    elif expected["role"] != "primary_assertion" and raw_status == "covered":
        status, reason = "covered", "tool_reported_covered"
    elif raw_status == "unreachable":
        status, reason = "unreachable", "tool_reported_unreachable"
    elif expected["role"] == "primary_assertion" and raw_status == "cex":
        status, reason = "cex", "tool_reported_cex_pending_trace_collection"
    elif re.search(r"Per property time limit expired", segment, re.IGNORECASE):
        status, reason = "inconclusive", "per_property_timeout"
    else:
        status, reason = "tool_error", "no_results_row"
    return {
        "operation_id": expected["operation_id"],
        "status": status,
        "reason": reason,
        "observed_property_id": expected["rtl_property_id"] if raw else None,
        "engine": raw.get("engine") if raw else None,
        "bound": raw.get("bound") if raw else None,
        "runtime_s": _runtime_seconds(raw.get("time")) if raw else None,
        "trace_path": None,
    }


def _runtime_seconds(value: Any) -> Optional[float]:
    if value is None:
        return None
    match = re.search(r"\d+(?:\.\d+)?", str(value))
    return float(match.group(0)) if match else None


def join_property_results(
    traceability: Dict[str, Any],
    jaspergold_report: Dict[str, Any],
    *,
    operation_plan: Dict[str, Any],
    property_profile_id: str,
    property_package_sha256: str,
    assertion_delta_sha256: str,
) -> Dict[str, Any]:
    """Join the exact operation ledger into the V4 result contract."""
    operation_results = jaspergold_report.get("operation_results")
    auxiliary = jaspergold_report.get("auxiliary_results")
    if not isinstance(operation_results, list):
        raise ValueError("formal report has no total operation result ledger")
    if not isinstance(auxiliary, list):
        raise ValueError("formal report has no auxiliary result ledger")
    instance_metadata: Dict[str, Dict[str, Any]] = {}
    for prop in traceability.get("properties", []):
        record: Dict[str, Any] = {
            key: prop[key]
            for key in ("instance_id", "property_schema_id", "template_id", "base_label")
            if key in prop
        }
        refs: Dict[str, Any] = {}
        for key in ("binding_manifest_path", "source", "protocol_rule"):
            if key in prop:
                refs[key] = prop[key]
        if refs:
            record["refs"] = refs
        if isinstance(record.get("instance_id"), str):
            instance_metadata[record["instance_id"]] = record
    return reduce_property_result_map(
        operation_plan=operation_plan,
        operation_results=operation_results,
        property_profile_id=property_profile_id,
        property_package_sha256=property_package_sha256,
        assertion_delta_sha256=assertion_delta_sha256,
        instance_metadata=instance_metadata,
        execution_status_hint=jaspergold_report.get("execution_status"),
        formal_metadata={
            key: jaspergold_report.get(key)
            for key in (
                "tool",
                "formal_contract_path",
                "formal_contract_sha256",
                "timed_out",
                "summary",
                "returncode",
            )
            if jaspergold_report.get(key) is not None
        },
        unmatched_tool_results=auxiliary,
    )


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
    stem = _safe_trace_filename(name)
    for suffix in (".fst", ".vcd"):
        path = trace_dir / f"{stem}{suffix}"
        if path.is_file():
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
