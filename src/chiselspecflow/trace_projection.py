"""Deterministic CEX-to-Chisel projection for SpecFlow Stage 3.

The projection layer is deliberately model-free.  It validates the complete
Stage-2 identity chain, samples certified traces, and evaluates reviewed
Monitor IR using only exact object/state joins.  Missing or unknown values are
reported as incomplete evidence rather than guessed.
"""

from __future__ import annotations

import json
import re
import shutil
import subprocess
import tempfile
from pathlib import Path
from typing import Any, Dict, Mapping, Optional

from src.core.artifact_contract import file_sha256

from .assets import load_reviewed_assets
from .ir.expression import normalized_root


class TraceProjectionError(ValueError):
    """Raised when Stage-2 evidence identity is invalid."""


_BINARY_RE = re.compile(r"^[01]+$")
_VAR_RE = re.compile(
    r"^\$var\s+\S+\s+(\d+)\s+(\S+)\s+(.+?)\s+\$end$"
)


def project_stage2_evidence(
    run_dir: Path,
    round_id: int,
    *,
    fst2vcd_argv: Optional[list[str]] = None,
) -> Dict[str, Any]:
    """Build ``evidence_projection.v1`` from one completed Stage-2 directory."""

    run_dir = Path(run_dir).resolve()
    stage2 = run_dir / "rounds" / f"{round_id:04d}" / "02_compile_verify"
    manifest = _read_json(run_dir / "manifest.json")
    semantic = _read_json(run_dir / "indexes" / "chisel_semantic_index.json")
    project = _read_json(run_dir / "inputs" / "project_contract.json")
    package, package_path = _load_certified_package(stage2)
    certificate_path = stage2 / "elaboration_certificate.json"
    plan_path = stage2 / "verification_operation_plan.json"
    result_path = stage2 / "property_result_map.json"
    trace_manifest_path = stage2 / "trace_manifest.json"
    certificate = _read_json(certificate_path)
    plan = _read_json(plan_path)
    result_map = _read_json(result_path)
    trace_manifest = _read_json(trace_manifest_path)
    _validate_identity_chain(
        package_path,
        certificate_path,
        plan_path,
        trace_manifest_path,
        certificate,
        plan,
        result_map,
        trace_manifest,
    )
    observation_map = _build_observation_map(
        package,
        semantic,
        wrapper_top=certificate["wrapper_top"],
        semantic_index_sha256=file_sha256(
            run_dir / "indexes" / "chisel_semantic_index.json"
        ),
        certificate_sha256=file_sha256(certificate_path),
        package_sha256=file_sha256(package_path),
        formal_reset=str(project["formal"]["reset"]),
    )
    operations = {row["operation_id"]: row for row in plan["operations"]}
    results = {row["operation_id"]: row for row in result_map["operation_results"]}
    traces = {row["operation_id"]: row for row in trace_manifest["traces"]}
    monitor_by_obligation = {
        row["obligation_id"]: row for row in package.get("monitors", [])
    }
    obligation_by_id = {
        row["obligation_id"]: row for row in package.get("obligations", [])
    }
    object_by_id = {row["object_id"]: row for row in semantic.get("objects", [])}

    projected_traces = []
    projection_errors: list[Dict[str, str]] = []
    for operation_id, trace_row in sorted(traces.items()):
        operation = operations.get(operation_id)
        result = results.get(operation_id)
        if operation is None or result is None or result.get("status") != "cex":
            raise TraceProjectionError("trace is not bound to an exact CEX operation")
        monitor = monitor_by_obligation.get(operation["obligation_id"])
        obligation = obligation_by_id.get(operation["obligation_id"])
        if monitor is None or obligation is None:
            raise TraceProjectionError("operation has no reviewed monitor/obligation")
        try:
            projected = _project_trace(
                trace_row,
                operation,
                monitor,
                obligation,
                observation_map,
                object_by_id,
                certificate["wrapper_top"],
                manifest,
                fst2vcd_argv=fst2vcd_argv,
            )
            projected_traces.append(projected)
            projection_errors.extend(projected["errors"])
        except TraceProjectionError as exc:
            projection_errors.append(
                {"code": "trace_projection_failed", "detail": str(exc)}
            )
            projected_traces.append(
                {
                    "operation_id": operation_id,
                    "status": "incomplete",
                    "failure_cycle": None,
                    "failure_time": None,
                    "cycles": [],
                    "source_objects": [],
                    "monitor_states": [],
                    "spec_clause": obligation["clause_ref"],
                    "errors": [projection_errors[-1]],
                }
            )

    cex_ids = sorted(
        operation_id
        for operation_id, row in results.items()
        if row.get("status") == "cex"
    )
    traced_ids = sorted(traces)
    if cex_ids != traced_ids:
        projection_errors.append(
            {
                "code": "cex_trace_set_mismatch",
                "detail": f"cex={cex_ids}, traces={traced_ids}",
            }
        )
    status = (
        "complete"
        if not projection_errors
        and all(row["status"] == "complete" for row in projected_traces)
        else "incomplete"
    )
    return {
        "schema_version": "evidence_projection.v1",
        "round_id": round_id,
        "status": status,
        "identity": {
            "verification_package_sha256": file_sha256(package_path),
            "elaboration_certificate_sha256": file_sha256(certificate_path),
            "operation_plan_sha256": file_sha256(plan_path),
            "property_result_map_sha256": file_sha256(result_path),
            "trace_manifest_sha256": file_sha256(trace_manifest_path),
        },
        "observation_map": observation_map,
        "traces": projected_traces,
        "errors": projection_errors,
    }


def _load_certified_package(stage2: Path) -> tuple[Dict[str, Any], Path]:
    reference = _read_json(stage2 / "verification_package_ref.json")
    if reference.get("schema_version") != "verification_package_ref.v1":
        raise TraceProjectionError("invalid verification package reference")
    source_run = Path(reference.get("source_run", "")).resolve()
    relative = Path(reference.get("path", ""))
    if relative.is_absolute() or ".." in relative.parts:
        raise TraceProjectionError("verification package path escapes source run")
    package_path = (source_run / relative).resolve()
    try:
        package_path.relative_to(source_run)
    except ValueError as exc:
        raise TraceProjectionError("verification package path escapes source run") from exc
    if not package_path.is_file() or file_sha256(package_path) != reference.get("sha256"):
        raise TraceProjectionError("verification package hash drifted")
    package = _read_json(package_path)
    if (
        package.get("schema_version") != "verification_package.v1"
        or package.get("package_id") != reference.get("package_id")
    ):
        raise TraceProjectionError("verification package identity mismatch")
    return package, package_path


def _validate_identity_chain(
    package_path: Path,
    certificate_path: Path,
    plan_path: Path,
    trace_manifest_path: Path,
    certificate: Mapping[str, Any],
    plan: Mapping[str, Any],
    result_map: Mapping[str, Any],
    trace_manifest: Mapping[str, Any],
) -> None:
    package_sha = file_sha256(package_path)
    certificate_sha = file_sha256(certificate_path)
    plan_sha = file_sha256(plan_path)
    trace_sha = file_sha256(trace_manifest_path)
    if certificate.get("verification_package_sha256") != package_sha:
        raise TraceProjectionError("certificate/package identity mismatch")
    if (
        plan.get("verification_package_sha256") != package_sha
        or plan.get("certificate_sha256") != certificate_sha
    ):
        raise TraceProjectionError("operation plan identity mismatch")
    if (
        result_map.get("operation_plan_sha256") != plan_sha
        or result_map.get("certificate_sha256") != certificate_sha
        or result_map.get("trace_manifest_sha256") != trace_sha
    ):
        raise TraceProjectionError("property result identity mismatch")
    if trace_manifest.get("operation_plan_sha256") != plan_sha:
        raise TraceProjectionError("trace manifest identity mismatch")


def _build_observation_map(
    package: Mapping[str, Any],
    semantic: Mapping[str, Any],
    *,
    wrapper_top: str,
    semantic_index_sha256: str,
    certificate_sha256: str,
    package_sha256: str,
    formal_reset: str = "reset",
) -> Dict[str, Any]:
    objects = {row["object_id"]: row for row in semantic.get("objects", [])}
    bindings = []
    seen_objects = set()
    for binding in sorted(package.get("bindings", []), key=lambda row: row["binding_id"]):
        object_id = binding["object_id"]
        row = objects.get(object_id)
        if row is None or row.get("fact_status") != "elaboration_confirmed":
            raise TraceProjectionError("binding is not joined to a confirmed source object")
        if object_id in seen_objects:
            raise TraceProjectionError("multiple bindings alias one source object")
        seen_objects.add(object_id)
        emitted_signal = f"{wrapper_top}.{row['name']}"
        if row.get("accessibility") == "wrapper":
            # Wrapper-accessible internals are acquired with BoringUtils for
            # property compilation, while formal traces retain the exact DUT
            # register in the nested instance scope.  Join to that stable
            # semantic object rather than a CIRCT-generated bore wire name.
            if row.get("owner_module") == semantic.get("top"):
                emitted_signal = f"{wrapper_top}.dut.{row['name']}"
            else:
                emitted_signal = (
                    f"{wrapper_top}.dut."
                    + _nested_trace_path(binding, row)
                )
        bindings.append(
            {
                "binding_id": binding["binding_id"],
                "object_id": object_id,
                "source_anchor": row["source_anchor"],
                "type": {
                    "kind": row["chisel_type"]["kind"],
                    "width": row["chisel_type"]["width"],
                    "signed": row["chisel_type"]["signed"],
                },
                "emitted_signal": emitted_signal,
            }
        )
    states = []
    state_ids = set()
    for monitor in package.get("monitors", []):
        for state in monitor.get("state", []):
            state_id = state["state_id"]
            if state_id in state_ids:
                raise TraceProjectionError("duplicate monitor state identity")
            state_ids.add(state_id)
            states.append(
                {
                    "monitor_id": monitor["monitor_id"],
                    "state_id": state_id,
                    "type": state["type"],
                    "emitted_signal": f"{wrapper_top}.csf_{state_id}",
                }
            )
    return {
        "schema_version": "observation_map.v1",
        "verification_package_sha256": package_sha256,
        "semantic_index_sha256": semantic_index_sha256,
        "elaboration_certificate_sha256": certificate_sha256,
        "bindings": bindings,
        "monitor_states": sorted(states, key=lambda row: row["state_id"]),
        "clock_signal": f"{wrapper_top}.clock",
        "reset_signal": f"{wrapper_top}.{formal_reset}",
    }


def _nested_trace_path(
    binding: Mapping[str, Any], row: Mapping[str, Any]
) -> str:
    acquisition = binding.get("acquisition")
    adapter_id = (
        acquisition.get("adapter_id")
        if isinstance(acquisition, Mapping)
        else None
    )
    adapter = load_reviewed_assets().api_adapters.get(adapter_id)
    if adapter is None:
        raise TraceProjectionError("nested observer adapter is not reviewed")
    matches = [
        item
        for item in adapter.get("hierarchical_observers", ())
        if isinstance(item, Mapping)
        and item.get("owner_module") == row.get("owner_module")
        and item.get("name") == row.get("name")
        and item.get("source_path") == row.get("source_anchor", {}).get("path")
        and item.get("source_line") == row.get("source_anchor", {}).get("line_start")
    ]
    if len(matches) != 1:
        raise TraceProjectionError(
            "nested observer lacks one exact reviewed trace path"
        )
    path = str(matches[0].get("trace_path", ""))
    if any(
        re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", part) is None
        for part in path.split(".")
    ):
        raise TraceProjectionError("nested observer trace path is unsafe")
    return path


def _project_trace(
    trace_row: Mapping[str, Any],
    operation: Mapping[str, Any],
    monitor: Mapping[str, Any],
    obligation: Mapping[str, Any],
    observation_map: Mapping[str, Any],
    object_by_id: Mapping[str, Mapping[str, Any]],
    wrapper_top: str,
    manifest: Mapping[str, Any],
    *,
    fst2vcd_argv: Optional[list[str]],
) -> Dict[str, Any]:
    path = Path(trace_row["path"])
    if (
        not path.is_file()
        or path.stat().st_size != trace_row.get("bytes")
        or file_sha256(path) != trace_row.get("sha256")
    ):
        raise TraceProjectionError("trace hash or size drifted")
    samples = _read_trace_samples(
        path,
        str(trace_row.get("format", "")),
        observation_map["clock_signal"],
        fst2vcd_argv=fst2vcd_argv,
    )
    required = {
        observation_map["clock_signal"],
        observation_map["reset_signal"],
        *(row["emitted_signal"] for row in observation_map["bindings"]),
        *(row["emitted_signal"] for row in observation_map["monitor_states"]),
    }
    available = set().union(*(set(row["values"]) for row in samples)) if samples else set()
    missing = sorted(required - available)
    errors = [
        {"code": "missing_observation", "detail": signal} for signal in missing
    ]
    object_signals = {
        row["object_id"]: row["emitted_signal"] for row in observation_map["bindings"]
    }
    state_signals = {
        row["state_id"]: row["emitted_signal"] for row in observation_map["monitor_states"]
    }
    primary = next(
        (
            row
            for row in monitor["properties"]
            if row["source_property_id"] == operation["source_property_id"]
        ),
        None,
    )
    if primary is None:
        raise TraceProjectionError("operation has no exact Monitor IR property")
    cycle_rows = []
    failure_cycle = None
    failure_time = None
    reset_signal = observation_map["reset_signal"]
    for cycle, sample in enumerate(samples):
        values = sample["values"]
        object_values = {
            object_id: _logic_value(values.get(signal))
            for object_id, signal in object_signals.items()
        }
        state_values = {
            state_id: _logic_value(values.get(signal))
            for state_id, signal in state_signals.items()
        }
        reset_value = _logic_value(values.get(reset_signal))
        guard = _evaluate_expression(primary["guard_ir"], object_values, state_values)
        expected = _evaluate_expression(
            primary["expression_ir"], object_values, state_values
        )
        active = reset_value == 0 and guard is True
        failed = active and expected is False
        if failed and failure_cycle is None:
            failure_cycle = cycle
            failure_time = sample["time"]
        if reset_value is None or guard is None or (active and expected is None):
            errors.append(
                {
                    "code": "unknown_sample_value",
                    "detail": f"cycle={cycle}",
                }
            )
        cycle_rows.append(
            {
                "cycle": cycle,
                "time": sample["time"],
                "reset": reset_value,
                "guard": guard,
                "expected_relation": expected,
                "active": active,
                "failed": failed,
                "objects": object_values,
                "monitor_state": state_values,
                "monitor_transitions": [],
            }
        )
    _reconstruct_monitor_transitions(monitor, cycle_rows, errors)
    if failure_cycle is None:
        errors.append(
            {
                "code": "cex_not_reconstructed",
                "detail": operation["operation_id"],
            }
        )
    object_ids = sorted(set(monitor.get("required_observations", [])))
    binding_by_id = {
        row["binding_id"]: row for row in observation_map["bindings"]
    }
    source_objects = []
    for binding_id in object_ids:
        binding = binding_by_id.get(binding_id)
        if binding is None:
            errors.append({"code": "missing_binding_join", "detail": binding_id})
            continue
        row = object_by_id[binding["object_id"]]
        source_objects.append(
            {
                "binding_id": binding_id,
                "object_id": binding["object_id"],
                "name": row["name"],
                "source_anchor": row["source_anchor"],
                "evidence_ref": f"cycles/{failure_cycle}/objects/{binding['object_id']}",
            }
        )
    monitor_states = [
        {
            "state_id": state["state_id"],
            "emitted_signal": state_signals[state["state_id"]],
            "failure_value": (
                cycle_rows[failure_cycle]["monitor_state"].get(state["state_id"])
                if failure_cycle is not None
                else None
            ),
        }
        for state in monitor.get("state", [])
    ]
    unknown_samples = any(
        error["code"] in {"missing_observation", "unknown_sample_value"}
        for error in errors
    )
    return {
        "operation_id": operation["operation_id"],
        "emitted_property_id": operation["emitted_property_id"],
        "source_property_id": operation["source_property_id"],
        "status": "complete" if not errors else "incomplete",
        "trace": {
            "path": str(path),
            "format": trace_row["format"],
            "sha256": trace_row["sha256"],
        },
        "configuration_id": manifest.get("configuration_id"),
        "failure_cycle": failure_cycle,
        "failure_time": failure_time,
        "environment": {
            "status": "incomplete" if unknown_samples else "legal",
            "reset_valid_cycles": [
                row["cycle"] for row in cycle_rows if row["reset"] == 0
            ],
        },
        "cycles": cycle_rows,
        "source_objects": source_objects,
        "monitor_states": monitor_states,
        "spec_clause": obligation["clause_ref"],
        "errors": errors,
    }


def _reconstruct_monitor_transitions(
    monitor: Mapping[str, Any],
    cycles: list[Dict[str, Any]],
    errors: list[Dict[str, str]],
) -> None:
    """Check observed state against the reviewed init/clear/update expressions."""

    for index, cycle in enumerate(cycles):
        transitions = cycle["monitor_transitions"]
        for state in monitor.get("state", []):
            state_id = state["state_id"]
            observed = cycle["monitor_state"].get(state_id)
            if index == 0:
                transitions.append(
                    {
                        "state_id": state_id,
                        "status": "initial_observed" if observed is not None else "incomplete",
                        "expected": None,
                        "observed": observed,
                    }
                )
                continue
            previous = cycles[index - 1]
            reset = cycle["reset"]
            clear = _evaluate_expression(
                state["clear"], previous["objects"], previous["monitor_state"]
            )
            if reset == 1 or clear is True:
                expected = _evaluate_expression(
                    state["init"], previous["objects"], previous["monitor_state"]
                )
            elif reset == 0 and clear is not None:
                expected = _evaluate_expression(
                    state["update"], previous["objects"], previous["monitor_state"]
                )
            else:
                expected = None
            status = (
                "incomplete"
                if expected is None or observed is None
                else "matched"
                if int(expected) == observed
                else "mismatch"
            )
            transitions.append(
                {
                    "state_id": state_id,
                    "status": status,
                    "expected": expected,
                    "observed": observed,
                }
            )
            if status != "matched":
                errors.append(
                    {
                        "code": (
                            "monitor_state_incomplete"
                            if status == "incomplete"
                            else "monitor_state_mismatch"
                        ),
                        "detail": f"cycle={index},state={state_id}",
                    }
                )


def _read_trace_samples(
    path: Path,
    trace_format: str,
    clock_signal: str,
    *,
    fst2vcd_argv: Optional[list[str]],
) -> list[Dict[str, Any]]:
    normalized = trace_format.lower()
    if normalized == "vcd":
        return _parse_vcd(path, clock_signal)
    if normalized != "fst":
        raise TraceProjectionError(f"unsupported trace format: {trace_format}")
    converter = list(fst2vcd_argv or ["fst2vcd"])
    executable = shutil.which(converter[0])
    if executable is None:
        raise TraceProjectionError("fst2vcd is unavailable")
    converter[0] = executable
    with tempfile.TemporaryDirectory(prefix="specflow-trace-") as temporary:
        vcd = Path(temporary) / "trace.vcd"
        completed = subprocess.run(
            [*converter, "-f", str(path), "-o", str(vcd)],
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            check=False,
        )
        if completed.returncode != 0 or not vcd.is_file():
            raise TraceProjectionError(
                "fst2vcd failed: " + completed.stdout[-500:].strip()
            )
        return _parse_vcd(vcd, clock_signal)


def _parse_vcd(path: Path, clock_signal: str) -> list[Dict[str, Any]]:
    scopes: list[str] = []
    symbols: Dict[str, Dict[str, Any]] = {}
    values: Dict[str, str] = {}
    samples: list[Dict[str, Any]] = []
    current_time = 0
    in_dumpvars = False
    pending_sample = False
    clock_symbol = None
    definitions_done = False

    def commit_sample() -> None:
        nonlocal pending_sample
        if pending_sample:
            samples.append({"time": current_time, "values": dict(values)})
            pending_sample = False

    for raw in Path(path).read_text(encoding="utf-8", errors="strict").splitlines():
        line = raw.strip()
        if not line:
            continue
        if line == "$enddefinitions $end":
            definitions_done = True
            continue
        if line.startswith("$scope "):
            parts = line.split()
            scopes.append(parts[2])
            continue
        if line == "$upscope $end":
            if scopes:
                scopes.pop()
            continue
        match = _VAR_RE.match(line)
        if match:
            width, symbol, reference = match.groups()
            name = reference.split()[0]
            full_name = ".".join([*scopes, name])
            symbols[symbol] = {"name": full_name, "width": int(width)}
            if full_name == clock_signal:
                if clock_symbol is not None and clock_symbol != symbol:
                    raise TraceProjectionError("clock signal is not unique in VCD")
                clock_symbol = symbol
            continue
        if line.startswith("#"):
            commit_sample()
            try:
                current_time = int(line[1:])
            except ValueError as exc:
                raise TraceProjectionError("invalid VCD timestamp") from exc
            continue
        if not definitions_done:
            continue
        if line == "$dumpvars":
            in_dumpvars = True
            continue
        if line == "$end" and in_dumpvars:
            in_dumpvars = False
            if clock_symbol is not None and values.get(clock_signal) == "1":
                pending_sample = True
            continue
        if line.startswith("$"):
            continue
        symbol, value = _parse_vcd_value(line)
        info = symbols.get(symbol)
        if info is None:
            continue
        name = info["name"]
        previous = values.get(name)
        values[name] = value
        if symbol == clock_symbol and value == "1" and previous != "1":
            pending_sample = True
    commit_sample()
    if clock_symbol is None:
        raise TraceProjectionError(f"clock signal is missing from VCD: {clock_signal}")
    if not samples:
        raise TraceProjectionError("VCD contains no rising-edge samples")
    return samples


def _parse_vcd_value(line: str) -> tuple[str, str]:
    if line[0] in "01xXzZ":
        return line[1:], line[0].lower()
    if line[0] in "bB":
        parts = line[1:].split()
        if len(parts) != 2:
            raise TraceProjectionError("malformed vector VCD value")
        return parts[1], parts[0].lower()
    raise TraceProjectionError("unsupported VCD value record")


def _logic_value(value: Optional[str]) -> Optional[int]:
    if value is None or not _BINARY_RE.fullmatch(value):
        return None
    return int(value, 2)


def _evaluate_expression(
    expression: Mapping[str, Any],
    objects: Mapping[str, Optional[int]],
    states: Mapping[str, Optional[int]],
) -> Optional[bool | int]:
    node = normalized_root(expression)
    op = node["op"]
    if op == "literal":
        return node["value"]
    if op == "object_ref":
        return objects.get(node["object_id"])
    if op in {"past_valid", "previous_value"}:
        return states.get(node["state_id"])
    if op == "not":
        value = _evaluate_expression(node["arg"], objects, states)
        return None if value is None else not bool(value)
    if op in {"and", "or"}:
        values = [_evaluate_expression(row, objects, states) for row in node["args"]]
        if op == "and":
            if any(value is False or value == 0 for value in values):
                return False
            return None if any(value is None for value in values) else True
        if any(value is True or (isinstance(value, int) and value != 0) for value in values):
            return True
        return None if any(value is None for value in values) else False
    if op == "mux":
        condition = _evaluate_expression(node["condition"], objects, states)
        if condition is None:
            return None
        branch = node["when_true"] if bool(condition) else node["when_false"]
        return _evaluate_expression(branch, objects, states)
    if op in {
        "eq", "neq", "ult", "ule", "ugt", "uge", "slt", "sle", "sgt", "sge",
        "add", "sub",
    }:
        lhs = _evaluate_expression(node["lhs"], objects, states)
        rhs = _evaluate_expression(node["rhs"], objects, states)
        if lhs is None or rhs is None:
            return None
        if op in {"add", "sub"}:
            width = int(node["result_type"]["width"])
            raw = int(lhs) + int(rhs) if op == "add" else int(lhs) - int(rhs)
            return raw % (1 << width)
        operators = {
            "eq": lambda a, b: a == b,
            "neq": lambda a, b: a != b,
            "ult": lambda a, b: a < b,
            "ule": lambda a, b: a <= b,
            "ugt": lambda a, b: a > b,
            "uge": lambda a, b: a >= b,
            "slt": lambda a, b: a < b,
            "sle": lambda a, b: a <= b,
            "sgt": lambda a, b: a > b,
            "sge": lambda a, b: a >= b,
        }
        return operators[op](int(lhs), int(rhs))
    if op == "onehot":
        value = _evaluate_expression(node["arg"], objects, states)
        return None if value is None else int(value).bit_count() == 1
    if op == "popcount":
        value = _evaluate_expression(node["arg"], objects, states)
        return None if value is None else int(value).bit_count()
    if op == "bit_select":
        value = _evaluate_expression(node["arg"], objects, states)
        return None if value is None else (int(value) >> node["index"]) & 1
    if op == "slice":
        value = _evaluate_expression(node["arg"], objects, states)
        if value is None:
            return None
        width = node["high"] - node["low"] + 1
        return (int(value) >> node["low"]) & ((1 << width) - 1)
    if op == "bounded_counter_relation":
        value = states.get(node["counter_state_id"])
        if value is None:
            return None
        relations = {
            "lt": lambda a, b: a < b,
            "le": lambda a, b: a <= b,
            "eq": lambda a, b: a == b,
            "ge": lambda a, b: a >= b,
            "gt": lambda a, b: a > b,
        }
        return relations[node["relation"]](value, node["bound"])
    raise TraceProjectionError(f"unsupported projected expression operator: {op}")


def _read_json(path: Path) -> Dict[str, Any]:
    try:
        value = json.loads(Path(path).read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as exc:
        raise TraceProjectionError(f"cannot read JSON artifact: {path}") from exc
    if not isinstance(value, dict):
        raise TraceProjectionError(f"JSON object required: {path}")
    return value
