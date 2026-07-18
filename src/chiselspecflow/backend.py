"""Small deterministic JasperGold adapter for SpecFlow Stage 2."""

from __future__ import annotations

import hashlib
import json
import re
import shutil
import subprocess
from pathlib import Path
from typing import Any, Dict, Mapping, Optional

from src.core.artifact_contract import file_sha256


class SpecFlowBackendError(RuntimeError):
    """Raised when the formal adapter cannot construct a trustworthy run."""


_RESULT_RE = re.compile(
    r"^\s*\[\d+\]\s+(?P<name>\S+)\s+"
    r"(?P<status>proven|cex|covered|bounded_proven|unreachable|undetermined|unknown|error)\s+"
    r"(?P<engine>\S+)\s+(?P<bound>.+?)\s+(?P<time>\d+(?:\.\d+)?\s*\w*)\s*$",
    re.IGNORECASE | re.MULTILINE,
)


class JasperGoldBackend:
    """Execute an already-certified operation plan; this class has no LLM hook."""

    def __init__(self, timeout_seconds: int = 300, per_property_seconds: int = 60):
        if timeout_seconds < 1 or per_property_seconds < 1:
            raise ValueError("formal timeouts must be positive")
        self.timeout_seconds = timeout_seconds
        self.per_property_seconds = per_property_seconds

    def run(
        self,
        stage_dir: Path,
        certificate: Mapping[str, Any],
        operation_plan: Mapping[str, Any],
        formal: Mapping[str, Any],
    ) -> Dict[str, Any]:
        stage_dir = Path(stage_dir).resolve()
        trace_dir = stage_dir / "traces"
        trace_dir.mkdir(parents=True, exist_ok=False)
        tcl_path = stage_dir / "verify.tcl"
        tcl_path.write_text(
            build_verify_tcl(
                certificate,
                operation_plan,
                formal,
                per_property_seconds=self.per_property_seconds,
            ),
            encoding="utf-8",
        )
        project_dir = stage_dir / "jgproject"
        command = ["jg", "-batch", "-proj", str(project_dir), str(tcl_path.name)]
        log_path = stage_dir / "jaspergold.log"
        timed_out = False
        returncode: Optional[int]
        try:
            completed = subprocess.run(
                command,
                cwd=stage_dir,
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                timeout=self.timeout_seconds,
                check=False,
            )
            output = _decode_output(completed.stdout)
            returncode = completed.returncode
        except subprocess.TimeoutExpired as exc:
            timed_out = True
            returncode = None
            output = _decode_output(exc.stdout) + _decode_output(exc.stderr)
            output += "\nSPECFLOW_GLOBAL_TIMEOUT\n"
        except OSError as exc:
            returncode = 127
            output = f"SPECFLOW_BACKEND_LAUNCH_ERROR {type(exc).__name__}: {exc}\n"
        log_path.write_text(output or "SPECFLOW_EMPTY_JASPERGOLD_OUTPUT\n", encoding="utf-8")
        _convert_vcd_traces(trace_dir)
        statuses = _parse_results(output)
        rows = []
        for operation in operation_plan["operations"]:
            observed = statuses.get(operation["emitted_property_id"])
            trace = _trace_for_operation(trace_dir, operation["operation_id"])
            rows.append(
                _account_operation(
                    operation,
                    observed,
                    trace,
                    returncode=returncode,
                    timed_out=timed_out,
                    log_text=output,
                )
            )
        trace_rows = []
        for operation, row in zip(operation_plan["operations"], rows):
            if row.get("trace_path"):
                path = Path(row["trace_path"])
                trace_rows.append(
                    {
                        "operation_id": operation["operation_id"],
                        "emitted_property_id": operation["emitted_property_id"],
                        "path": str(path),
                        "format": path.suffix.lstrip("."),
                        "sha256": file_sha256(path),
                        "bytes": path.stat().st_size,
                    }
                )
        trace_manifest = {
            "schema_version": "trace_manifest.v1",
            "operation_plan_sha256": file_sha256(
                stage_dir / "verification_operation_plan.json"
            ),
            "traces": trace_rows,
        }
        _write_json(stage_dir / "trace_manifest.json", trace_manifest)
        events_path = stage_dir / "proof_events.jsonl"
        with events_path.open("w", encoding="utf-8") as handle:
            for sequence, row in enumerate(rows):
                handle.write(
                    json.dumps(
                        {
                            "schema_version": "proof_event.v1",
                            "event": "property_finalized",
                            "sequence": sequence,
                            **row,
                        },
                        sort_keys=True,
                    )
                    + "\n"
                )
        version_match = re.search(r"JasperGold Apps\s+([^\r\n]+)", output, re.IGNORECASE)
        return {
            "operation_results": rows,
            "trace_manifest": trace_manifest,
            "tool": {
                "name": "jaspergold",
                "version": version_match.group(1).strip() if version_match else "unknown",
                "returncode": returncode,
                "timed_out": timed_out,
                "command": command,
            },
        }


def build_verify_tcl(
    certificate: Mapping[str, Any],
    operation_plan: Mapping[str, Any],
    formal: Mapping[str, Any],
    *,
    per_property_seconds: int,
) -> str:
    files = [Path(row["path"]).resolve() for row in certificate["generated_files"]]
    for path, row in zip(files, certificate["generated_files"]):
        if not path.is_file() or file_sha256(path) != row["sha256"]:
            raise SpecFlowBackendError("certified generated file is missing or hash-drifted")
    analyze_files = " ".join(_tcl_quote(str(path)) for path in files)
    reset_expression = (
        str(formal["reset"])
        if formal.get("reset_active_high") is True
        else "!(" + str(formal["reset"]) + ")"
    )
    lines = [
        "# Auto-generated by ChiselSpecFlow Stage 2.",
        "clear -all",
        f"analyze -sv {analyze_files}",
        f"elaborate -top {_tcl_quote(certificate['wrapper_top'])}",
        f"clock {_tcl_quote(formal['clock'])}",
        f"reset -expression {_tcl_quote(reset_expression)}",
        f"set_prove_time_limit {per_property_seconds}s",
        "file mkdir traces",
        "set_trace_optimization standard",
        "proc specflow_save_cex {property filename} {",
        "  if {[catch {visualize -violation -property $property} result]} {",
        '    puts "SPECFLOW_TRACE_SKIP $property $result"',
        "    return",
        "  }",
        "  if {[catch {visualize -save -force -vcd $filename} result]} {",
        '    puts "SPECFLOW_TRACE_SAVE_FAILED $property $result"',
        "  } else {",
        '    puts "SPECFLOW_TRACE_SAVED $property $filename"',
        "  }",
        "}",
    ]
    for operation in operation_plan["operations"]:
        property_id = operation["emitted_property_id"]
        filename = "traces/" + _safe_filename(operation["operation_id"]) + ".vcd"
        lines.extend(
            [
                f'puts "SPECFLOW_PROPERTY_BEGIN {property_id}"',
                f"set specflow_outcome [prove -property {_tcl_quote(property_id)}]",
                "report",
            ]
        )
        if operation["role"] == "primary_assertion":
            lines.extend(
                [
                    'if {$specflow_outcome eq "cex"} {',
                    f"  specflow_save_cex {_tcl_quote(property_id)} {_tcl_quote(filename)}",
                    "}",
                ]
            )
        lines.append(f'puts "SPECFLOW_PROPERTY_END {property_id}"')
    lines.append("exit")
    return "\n".join(lines) + "\n"


def _parse_results(text: str) -> Dict[str, Dict[str, Any]]:
    results = {}
    for match in _RESULT_RE.finditer(text):
        results[match.group("name")] = {
            "status": match.group("status").lower(),
            "engine": match.group("engine"),
            "bound": " ".join(match.group("bound").split()),
            "runtime_s": _runtime_seconds(match.group("time")),
        }
    return results


def _account_operation(
    operation: Mapping[str, Any],
    observed: Optional[Mapping[str, Any]],
    trace: Optional[Path],
    *,
    returncode: Optional[int],
    timed_out: bool,
    log_text: str,
) -> Dict[str, Any]:
    raw = str((observed or {}).get("status", "")).lower()
    primary = operation["role"] == "primary_assertion"
    if primary and raw in {"proven", "bounded_proven"}:
        status, reason = "proven", "tool_reported_proven"
    elif not primary and raw == "covered":
        status, reason = "covered", "tool_reported_covered"
    elif raw == "unreachable":
        status, reason = "unreachable", "tool_reported_unreachable"
    elif primary and raw == "cex" and trace is not None:
        status, reason = "cex", "tool_reported_cex_with_exact_trace"
    elif primary and raw == "cex":
        status, reason = "inconclusive", "counterexample_trace_missing"
    elif raw in {"undetermined", "unknown"}:
        status, reason = "inconclusive", f"tool_reported_{raw}"
    elif timed_out and re.search(r"waiting for license|license server", log_text, re.IGNORECASE):
        status, reason = "tool_error", "jaspergold_license_unavailable"
    elif timed_out:
        status, reason = "timeout", "global_timeout"
    elif re.search(r"per property time limit expired", log_text, re.IGNORECASE):
        status, reason = "timeout", "per_property_timeout"
    elif returncode not in (0, None):
        status, reason = "tool_error", f"jaspergold_exit_{returncode}"
    else:
        status, reason = "tool_error", "no_exact_results_row"
    return {
        "operation_id": operation["operation_id"],
        "status": status,
        "reason": reason,
        "observed_property_id": operation["emitted_property_id"] if observed else None,
        "engine": observed.get("engine") if observed else None,
        "bound": observed.get("bound") if observed else None,
        "runtime_s": observed.get("runtime_s") if observed else None,
        "trace_path": str(trace) if trace else None,
    }


def _trace_for_operation(trace_dir: Path, operation_id: str) -> Optional[Path]:
    stem = _safe_filename(operation_id)
    for suffix in (".fst", ".vcd"):
        path = trace_dir / (stem + suffix)
        if path.is_file() and path.stat().st_size > 0:
            return path
    return None


def _convert_vcd_traces(trace_dir: Path) -> None:
    converter = shutil.which("vcd2fst")
    if converter is None:
        return
    for vcd in trace_dir.glob("*.vcd"):
        fst = vcd.with_suffix(".fst")
        completed = subprocess.run(
            [converter, str(vcd), str(fst)],
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            check=False,
        )
        if completed.returncode == 0 and fst.is_file() and fst.stat().st_size > 0:
            vcd.unlink()
        elif fst.exists():
            fst.unlink()


def _safe_filename(value: str) -> str:
    safe = re.sub(r"[^A-Za-z0-9_.-]+", "_", value)
    return safe[:180] + "_" + hashlib.sha256(value.encode("utf-8")).hexdigest()[:12]


def _runtime_seconds(value: Any) -> Optional[float]:
    match = re.search(r"\d+(?:\.\d+)?", str(value))
    return float(match.group(0)) if match else None


def _decode_output(value: Any) -> str:
    if value is None:
        return ""
    if isinstance(value, bytes):
        return value.decode("utf-8", errors="replace")
    return str(value)


def _tcl_quote(value: str) -> str:
    return "{" + value.replace("}", "\\}") + "}"


def _write_json(path: Path, value: Mapping[str, Any]) -> None:
    Path(path).write_text(
        json.dumps(value, indent=2, sort_keys=True) + "\n", encoding="utf-8"
    )
