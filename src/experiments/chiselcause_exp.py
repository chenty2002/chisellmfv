"""Independent differential-CEX corpus builder for ``chiselcause_exp.md`` C1/C2."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import resource
import shutil
import subprocess
import time
from datetime import datetime
from pathlib import Path
from typing import Any, Iterable, Mapping, Sequence
from zoneinfo import ZoneInfo

from src.chiselspecflow.config import (
    GeneratorConfiguration,
    ProjectContract,
    load_generator_configuration,
    load_project_contract,
)
from src.chiselspecflow.elaboration import elaborate_baseline


CASE_SCHEMA = "chiselcause_case.v1"
CASES_SCHEMA = "chiselcause_cases.v1"
RESULT_SCHEMA = "chiselcause_result.v2"
SEARCH_TRACE_SCHEMA = "chiselcause_search_trace.v1"
METRICS_SCHEMA = "chiselcause_source_metrics.v3"
ABSOLUTE_BUDGET_CHECKPOINTS = (1, 5, 10, 25, 50, 100, 200)
METHODS = ("d0", "d1", "d2", "d3")
SEARCH_POLICY_ALIASES = {
    "CS0": "legacy_scalar_best_first_v1",
    "CS1": "edge_best_first_v1",
    "H0": "legacy_dfs_v1",
    "H1": "edge_best_first_v1",
    "H2": "chisel_hybrid_best_first_v1",
}
SEARCH_POLICY_IDS = tuple(SEARCH_POLICY_ALIASES.values())
POLICY_COMPARISON_ARMS = {
    policy_id: tuple(
        arm for arm, resolved in SEARCH_POLICY_ALIASES.items() if resolved == policy_id
    )
    for policy_id in dict.fromkeys(SEARCH_POLICY_IDS)
}
COUPLEDL2_CASES = {
    "deadlock-v0": {
        "endpoint": "VerifyTop.coupledL2.slices_0.mshrCtl._assert_1",
        "cycle": 1010,
        "predicate_members": [
            "VerifyTop.coupledL2.slices_0.mshrCtl.timers_0 [63:0]"
        ],
    },
    "deadlock-v1": {
        "endpoint": "VerifyTop.coupledL2.slices_0.mshrCtl._assert_1",
        "cycle": 1098,
        "predicate_members": [
            "VerifyTop.coupledL2.slices_0.mshrCtl.timers_0 [63:0]"
        ],
    },
    "deadlock-v2": {
        "endpoint": "VerifyTop.coupledL2.slices_0.mshrCtl._assert_1",
        "cycle": 8049,
        "predicate_members": [
            "VerifyTop.coupledL2.slices_0.mshrCtl.timers_0 [63:0]"
        ],
    },
    "deadlock-v3": {
        "endpoint": "VerifyTop.coupledL2.slices_0.mshrCtl._assert_1",
        "cycle": 14993,
        "predicate_members": [
            "VerifyTop.coupledL2.slices_0.mshrCtl.timers_0 [63:0]"
        ],
    },
    "deadlock-v4": {
        "endpoint": "VerifyTop.coupledL2_1.slices_0.mshrCtl._assert_5",
        "cycle": 620,
        "predicate_members": [
            "VerifyTop.coupledL2_1.slices_0.mshrCtl.mshrs_1.timer [63:0]"
        ],
    },
}
_SUPPORT_RTL_NAMES = (
    "ClockGate.v",
    "LogPerfHelper.v",
    "ResetCounter.sv",
    "STD_CLKGT_func.v",
    "TLLogWriter.v",
)
CASE_FIELDS = {
    "schema_version",
    "case_id",
    "family",
    "status",
    "clean_variant",
    "faulty_variant",
    "interface",
    "artifacts",
    "formal",
    "cex",
    "endpoint_projection",
    "independence",
}
RESULT_FIELDS = {
    "schema_version",
    "case_id",
    "method",
    "status",
    "input_identity",
    "source_ranking",
    "search_trace",
    "work",
    "metrics",
    "runtime_seconds",
    "peak_rss_bytes",
    "termination_reason",
    "failure_reason",
}
_SAFE_ID = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_.-]*$")


class ChiselCauseExperimentError(RuntimeError):
    pass


class UnsupportedInterface(ChiselCauseExperimentError):
    pass


class CounterexampleNotTriggered(ChiselCauseExperimentError):
    pass


class FormalToolError(ChiselCauseExperimentError):
    pass


def prepare(args: argparse.Namespace) -> Path:
    repo = Path(args.repo).resolve()
    project_path = (repo / args.project_contract).resolve()
    project = load_project_contract(project_path)
    clean_config = load_generator_configuration((repo / args.clean_config).resolve(), project)
    faulty_config = load_generator_configuration((repo / args.faulty_config).resolve(), project)
    case_id = _safe_id(args.case_id or f"{project.project_id}-{faulty_config.configuration_id}")
    experiment_id = _safe_id(
        args.experiment_id
        or datetime.now(ZoneInfo("Asia/Shanghai")).strftime("%Y%m%d-%H%M%S")
    )
    run_dir = repo / "runs/chiselcause-paper" / experiment_id
    if run_dir.exists():
        raise ChiselCauseExperimentError(f"experiment already exists: {run_dir}")
    raw_dir = run_dir / "raw" / case_id
    for path in (
        raw_dir / "clean",
        raw_dir / "faulty",
        raw_dir / "formal",
        *(raw_dir / method for method in METHODS),
        run_dir / "tables",
    ):
        path.mkdir(parents=True, exist_ok=False)
    (run_dir / "results.jsonl").touch()
    _freeze_schemas(run_dir)

    try:
        case = _prepare_case(
            repo=repo,
            run_dir=run_dir,
            raw_dir=raw_dir,
            case_id=case_id,
            project=project,
            clean_config=clean_config,
            faulty_config=faulty_config,
            witness_path=(repo / args.witness).resolve() if args.witness else None,
            witness_signals=tuple(args.witness_signal or ()),
            timeout_seconds=args.timeout_seconds,
            per_property_seconds=args.per_property_seconds,
        )
    except (UnsupportedInterface, CounterexampleNotTriggered, FormalToolError) as exc:
        if isinstance(exc, UnsupportedInterface):
            status = "unsupported_interface"
        elif isinstance(exc, CounterexampleNotTriggered):
            status = "not_triggered"
        else:
            status = "tool_error"
        _append_jsonl(
            run_dir / "admission.jsonl",
            {
                "schema_version": "chiselcause_admission.v1",
                "case_id": case_id,
                "status": status,
                "reason": str(exc),
                "case_sha256": None,
            },
        )
        _write_json(run_dir / "cases.json", {"schema_version": CASES_SCHEMA, "cases": []})
        _write_report(run_dir)
        return run_dir

    validate_case(case)
    _write_json(run_dir / "cases.json", {"schema_version": CASES_SCHEMA, "cases": [case]})
    _append_jsonl(
        run_dir / "admission.jsonl",
        {
            "schema_version": "chiselcause_admission.v1",
            "case_id": case_id,
            "status": "complete",
            "reason": "independent_differential_cex_complete",
            "case_sha256": _canonical_sha256(case),
        },
    )
    _write_report(run_dir)
    return run_dir


def _prepare_case(
    *,
    repo: Path,
    run_dir: Path,
    raw_dir: Path,
    case_id: str,
    project: ProjectContract,
    clean_config: GeneratorConfiguration,
    faulty_config: GeneratorConfiguration,
    witness_path: Path | None,
    witness_signals: Sequence[str],
    timeout_seconds: int,
    per_property_seconds: int,
) -> dict[str, Any]:
    clean = _elaborate_variant(project, clean_config, raw_dir / "clean")
    faulty = _elaborate_variant(project, faulty_config, raw_dir / "faulty")
    interface = _aligned_interface(clean["metadata"], faulty["metadata"], project)
    witness = _load_witness(witness_path, witness_signals, interface["inputs"])
    formal_dir = raw_dir / "formal"
    miter_path = formal_dir / "differential_miter.sv"
    clean_formal_rtl = _renamed_design(
        clean["rtl"], formal_dir / "clean_design.sv", clean["metadata"]["modules"], "ChiselCauseClean_"
    )
    faulty_formal_rtl = _renamed_design(
        faulty["rtl"], formal_dir / "faulty_design.sv", faulty["metadata"]["modules"], "ChiselCauseFaulty_"
    )
    miter_path.write_text(
        _render_miter(
            clean_rtl=clean_formal_rtl,
            faulty_rtl=faulty_formal_rtl,
            clean_modules=clean["metadata"]["modules"],
            faulty_modules=faulty["metadata"]["modules"],
            top=project.generator["top_name"],
            interface=interface,
            clock=project.formal["clock"],
            reset=project.formal["reset"],
            reset_active_high=project.formal["reset_active_high"],
            witness=witness,
        ),
        encoding="utf-8",
    )
    formal = _run_formal(
        formal_dir,
        miter_path,
        clock=project.formal["clock"],
        reset=project.formal["reset"],
        reset_active_high=project.formal["reset_active_high"],
        timeout_seconds=timeout_seconds,
        per_property_seconds=per_property_seconds,
    )
    if formal["outcome"] == "tool_error":
        raise FormalToolError("JasperGold failed before producing an exact property result")
    if formal["outcome"] != "cex":
        raise CounterexampleNotTriggered(
            f"JasperGold outcome was {formal['outcome']!r}; no case row admitted"
        )
    vcd_path = formal_dir / "counterexample.vcd"
    fst_path = formal_dir / "counterexample.fst"
    conversion = _convert_vcd(vcd_path, fst_path)
    failure = _first_violated_output(
        vcd_path, interface["outputs"], project.formal["clock"]
    )
    diagnosis_wrapper = formal_dir / "diagnosis_wrapper.sv"
    diagnosis_wrapper.write_text(
        _render_diagnosis_wrapper(
            faulty_top=f"ChiselCauseFaulty_{project.generator['top_name']}",
            interface=interface,
        ),
        encoding="utf-8",
    )
    rtl_rows = [_artifact(run_dir, faulty_formal_rtl), _artifact(run_dir, diagnosis_wrapper)]
    rtl_set_sha256 = _canonical_sha256(
        [{"path": row["path"], "sha256": row["sha256"]} for row in rtl_rows]
    )
    diagnosis_endpoint = f"ChiselCauseMiter.faulty_dut.{failure['output']}"
    projection = {
        "schema_version": "assertion_endpoint_projection",
        "endpoint_signal": diagnosis_endpoint,
        "endpoint_cycle": failure["cycle"],
        "clock_signal": f"ChiselCauseMiter.{project.formal['clock']}",
        "predicate_members": [diagnosis_endpoint],
        "rtl_set_sha256": rtl_set_sha256,
        "trace_sha256": _sha256(fst_path),
    }
    projection_path = formal_dir / "assertion_endpoint_projection.json"
    _write_json(projection_path, projection)
    return {
        "schema_version": CASE_SCHEMA,
        "case_id": case_id,
        "family": project.project_id,
        "status": "complete",
        "clean_variant": _variant_row(clean_config, clean, run_dir),
        "faulty_variant": _variant_row(faulty_config, faulty, run_dir),
        "interface": interface,
        "artifacts": {
            "diagnosis_rtl_set": rtl_rows,
            "clean_formal_rtl": _artifact(run_dir, clean_formal_rtl),
            "faulty_formal_rtl": _artifact(run_dir, faulty_formal_rtl),
            "miter": _artifact(run_dir, miter_path),
            "jaspergold_log": _artifact(run_dir, formal_dir / "jaspergold.log"),
            "vcd": _artifact(run_dir, vcd_path),
            "fst": _artifact(run_dir, fst_path),
            "vcd_to_fst": conversion,
            "endpoint_projection": _artifact(run_dir, projection_path),
        },
        "formal": formal,
        "cex": {
            "property": "ChiselCauseMiter.chiselcause_outputs_match",
            "failure_cycle": failure["cycle"],
            "failure_time": failure["time"],
            "violated_output": failure["output"],
            "assertion_endpoint": "ChiselCauseMiter.chiselcause_mismatch_any",
            "diagnosis_endpoint": diagnosis_endpoint,
        },
        "endpoint_projection": projection,
        "independence": {
            "cex_source": "differential_miter",
            "specflow_property_package_read": False,
            "model_calls": 0,
            "gold_read_during_prepare": False,
        },
    }


def _elaborate_variant(
    project: ProjectContract,
    configuration: GeneratorConfiguration,
    variant_dir: Path,
) -> dict[str, Any]:
    workspace = variant_dir / "workspace"
    shutil.copytree(
        project.project_root,
        workspace,
        ignore=shutil.ignore_patterns("target", ".bloop", ".metals", "specflow-generated"),
    )
    metadata_path = variant_dir / "elaboration.json"
    metadata = elaborate_baseline(project, configuration, workspace, metadata_path)
    generated = [workspace / row["path"] for row in metadata["generated_files"]]
    if len(generated) != 1:
        raise ChiselCauseExperimentError("C1/C2 requires one self-contained emitted SV file")
    return {"metadata": metadata, "metadata_path": metadata_path, "rtl": generated[0]}


def _aligned_interface(
    clean: Mapping[str, Any], faulty: Mapping[str, Any], project: ProjectContract
) -> dict[str, Any]:
    top = project.generator["top_name"]
    clean_ports = _top_ports(clean, top)
    faulty_ports = _top_ports(faulty, top)
    if clean_ports != faulty_ports:
        raise UnsupportedInterface("clean and faulty top-level public interfaces differ")
    if any(row["direction"] == "inout" for row in clean_ports):
        raise UnsupportedInterface("inout ports are not supported by the generic miter")
    by_name = {row["name"]: row for row in clean_ports}
    clock, reset = project.formal["clock"], project.formal["reset"]
    if clock not in by_name or reset not in by_name:
        raise UnsupportedInterface("formal clock/reset are absent from the emitted top")
    if by_name[clock]["direction"] != "input" or by_name[clock]["width"] != 1:
        raise UnsupportedInterface("formal clock must be a one-bit input")
    if by_name[reset]["direction"] != "input" or by_name[reset]["width"] != 1:
        raise UnsupportedInterface("formal reset must be a one-bit input")
    inputs = [row for row in clean_ports if row["direction"] == "input"]
    outputs = [row for row in clean_ports if row["direction"] == "output"]
    if not outputs:
        raise UnsupportedInterface("the emitted top has no public output")
    return {"top": top, "inputs": inputs, "outputs": outputs}


def _top_ports(metadata: Mapping[str, Any], top: str) -> list[dict[str, Any]]:
    return sorted(
        [
            {"name": row["name"], "direction": row["direction"], "width": row["width"]}
            for row in metadata["objects"]
            if row["owner_module"] == top and row["direction"] in {"input", "output", "inout"}
        ],
        key=lambda row: row["name"],
    )


def _render_miter(
    *,
    clean_rtl: Path,
    faulty_rtl: Path,
    clean_modules: Sequence[str],
    faulty_modules: Sequence[str],
    top: str,
    interface: Mapping[str, Any],
    clock: str,
    reset: str,
    reset_active_high: bool,
    witness: list[dict[str, int]],
) -> str:
    clean_map = {name: f"ChiselCauseClean_{name}" for name in clean_modules}
    faulty_map = {name: f"ChiselCauseFaulty_{name}" for name in faulty_modules}
    includes = f'`include "{clean_rtl.resolve()}"\n`include "{faulty_rtl.resolve()}"'
    inputs = interface["inputs"]
    outputs = interface["outputs"]
    declarations = ",\n".join(
        f"  input {_width(row['width'])}{row['name']}" for row in inputs
    )
    wires = "\n".join(
        f"  wire {_width(row['width'])}clean_{row['name']};\n"
        f"  wire {_width(row['width'])}faulty_{row['name']};"
        for row in outputs
    )
    clean_connections = _connections(inputs, outputs, "clean")
    faulty_connections = _connections(inputs, outputs, "faulty")
    mismatch_wires = "\n".join(
        f"  wire mismatch_{row['name']} = clean_{row['name']} !== faulty_{row['name']};"
        for row in outputs
    )
    mismatch_expression = " | ".join(f"mismatch_{row['name']}" for row in outputs)
    replacements = {
        "{{DESIGN_INCLUDES}}": includes,
        "{{INPUT_DECLARATIONS}}": declarations,
        "{{OUTPUT_WIRES}}": wires,
        "{{CLEAN_TOP}}": clean_map[top],
        "{{FAULTY_TOP}}": faulty_map[top],
        "{{CLEAN_CONNECTIONS}}": clean_connections,
        "{{FAULTY_CONNECTIONS}}": faulty_connections,
        "{{MISMATCH_WIRES}}": mismatch_wires,
        "{{MISMATCH_EXPRESSION}}": mismatch_expression,
        "{{INITIAL_OUTPUT_ALIGNMENT}}": " && ".join(
            f"(clean_{row['name']} == faulty_{row['name']})" for row in outputs
        ),
        "{{WITNESS_ASSUMPTIONS}}": _render_witness(
            witness, clock, reset, reset_active_high
        ),
        "{{CLOCK}}": clock,
        "{{RESET_EXPRESSION}}": reset if reset_active_high else f"!{reset}",
    }
    template = _asset("differential_miter.sv.j2").read_text(encoding="utf-8")
    for marker, value in replacements.items():
        template = template.replace(marker, value)
    if "{{" in template:
        raise ChiselCauseExperimentError("unresolved differential miter template marker")
    return template


def _renamed_design(source: Path, target: Path, modules: Sequence[str], prefix: str) -> Path:
    text = source.read_text(encoding="utf-8")
    for module in sorted(modules, key=len, reverse=True):
        text = re.sub(rf"\b{re.escape(module)}\b", prefix + module, text)
    target.write_text(text, encoding="utf-8")
    return target


def _render_diagnosis_wrapper(*, faulty_top: str, interface: Mapping[str, Any]) -> str:
    inputs = interface["inputs"]
    outputs = interface["outputs"]
    declarations = [f"  input {_width(row['width'])}{row['name']}" for row in inputs]
    declarations += [f"  output {_width(row['width'])}{row['name']}" for row in outputs]
    ports = ",\n".join(declarations)
    connections = ",\n".join(
        f"    .{row['name']}({row['name']})" for row in [*inputs, *outputs]
    )
    return (
        "// Faulty-only hierarchy matching the differential CEX endpoint.\n"
        f"module ChiselCauseMiter(\n{ports}\n);\n"
        f"  {faulty_top} faulty_dut (\n{connections}\n  );\n"
        "endmodule\n"
    )


def _connections(inputs: Sequence[Mapping[str, Any]], outputs: Sequence[Mapping[str, Any]], prefix: str) -> str:
    ports = [(row["name"], row["name"]) for row in inputs]
    ports += [(row["name"], f"{prefix}_{row['name']}") for row in outputs]
    return ",\n".join(f"    .{port}({signal})" for port, signal in ports)


def _width(width: int) -> str:
    return "" if width == 1 else f"[{width - 1}:0] "


def _load_witness(
    path: Path | None, signals: Sequence[str], inputs: Sequence[Mapping[str, Any]]
) -> list[dict[str, int]]:
    if path is None:
        if signals:
            raise ChiselCauseExperimentError("--witness-signal requires --witness")
        return []
    if not path.is_file() or not signals:
        raise ChiselCauseExperimentError("witness requires an existing file and signal order")
    widths = {row["name"]: row["width"] for row in inputs}
    if len(set(signals)) != len(signals) or any(signal not in widths for signal in signals):
        raise ChiselCauseExperimentError("witness signals must be unique top-level inputs")
    rows = []
    for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        if not line.strip():
            continue
        values = [item.strip() for item in line.split(",")]
        if len(values) != len(signals):
            raise ChiselCauseExperimentError(f"witness line {number} width mismatch")
        row = {}
        for signal, value in zip(signals, values):
            parsed = int(value, 0)
            if parsed < 0 or parsed >= 2 ** widths[signal]:
                raise ChiselCauseExperimentError(f"witness value out of range at line {number}")
            row[signal] = parsed
        rows.append(row)
    if not rows:
        raise ChiselCauseExperimentError("witness is empty")
    return rows


def _render_witness(
    rows: Sequence[Mapping[str, int]],
    clock: str,
    reset: str,
    reset_active_high: bool,
) -> str:
    if not rows:
        return "  // No external witness supplied; JasperGold searches the shared inputs."
    predicates = [
        " && ".join(f"({signal} == {value})" for signal, value in row.items())
        for row in rows
    ]
    reset_expression = reset if reset_active_high else f"!({reset})"
    width = max(1, len(rows).bit_length())
    lines = [
        f"  reg [{width - 1}:0] chiselcause_witness_cycle;",
        f"  always @(posedge {clock})",
        f"    if ({reset_expression})",
        f"      chiselcause_witness_cycle <= {width}'d0;",
        f"    else if (chiselcause_witness_cycle < {width}'d{max(0, len(rows) - 1)})",
        f"      chiselcause_witness_cycle <= chiselcause_witness_cycle + {width}'d1;",
        f"  assume property (@(posedge {clock}) {reset_expression} |-> ({predicates[0]}));",
    ]
    for index, predicate in enumerate(predicates[1:]):
        lines.append(
            f"  assume property (@(posedge {clock}) !({reset_expression}) && "
            f"chiselcause_witness_cycle == {width}'d{index} |-> ({predicate}));"
        )
    return "\n".join(lines)


def _run_formal(
    formal_dir: Path,
    miter_path: Path,
    *,
    clock: str,
    reset: str,
    reset_active_high: bool,
    timeout_seconds: int,
    per_property_seconds: int,
) -> dict[str, Any]:
    tcl_path = formal_dir / "verify.tcl"
    reset_expression = reset if reset_active_high else f"!({reset})"
    tcl_path.write_text(
        "\n".join(
            [
                "clear -all",
                f"analyze -sv {{{miter_path.resolve()}}}",
                "elaborate -top ChiselCauseMiter",
                f"clock {{{clock}}}",
                "reset -none",
                f"assume -bound 1 {{{reset_expression}}}",
                f"set_prove_time_limit {per_property_seconds}s",
                "set chiselcause_outcome [prove -property {ChiselCauseMiter.chiselcause_outputs_match}]",
                'puts "CHISELCAUSE_OUTCOME $chiselcause_outcome"',
                "report",
                'if {$chiselcause_outcome eq "cex"} {',
                "  visualize -violation -property {ChiselCauseMiter.chiselcause_outputs_match}",
                "  visualize -save -force -vcd {counterexample.vcd}",
                "}",
                "exit",
                "",
            ]
        ),
        encoding="utf-8",
    )
    command = ["jg", "-batch", "-proj", str(formal_dir / "jgproject"), tcl_path.name]
    try:
        completed = subprocess.run(
            command,
            cwd=formal_dir,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            timeout=timeout_seconds,
            check=False,
        )
        output = completed.stdout.decode("utf-8", errors="replace")
        returncode = completed.returncode
    except subprocess.TimeoutExpired as exc:
        output = (exc.stdout or b"").decode("utf-8", errors="replace") + "\nCHISELCAUSE_TIMEOUT\n"
        returncode = None
    (formal_dir / "jaspergold.log").write_text(output or "CHISELCAUSE_EMPTY_LOG\n", encoding="utf-8")
    match = re.search(r"^CHISELCAUSE_OUTCOME\s+(\S+)", output, re.MULTILINE)
    outcome = match.group(1).lower() if match else "tool_error"
    if returncode not in (0, None) and outcome != "cex":
        outcome = "tool_error"
    return {
        "backend": "jaspergold",
        "command": command,
        "returncode": returncode,
        "timeout_seconds": timeout_seconds,
        "per_property_seconds": per_property_seconds,
        "outcome": outcome,
    }


def _convert_vcd(vcd: Path, fst: Path) -> dict[str, Any]:
    if not vcd.is_file() or vcd.stat().st_size == 0:
        raise CounterexampleNotTriggered("JasperGold reported CEX without a VCD")
    command = ["vcd2fst", str(vcd.resolve()), str(fst.resolve())]
    completed = subprocess.run(command, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, check=False)
    if completed.returncode != 0 or not fst.is_file() or fst.stat().st_size == 0:
        raise ChiselCauseExperimentError("vcd2fst failed: " + completed.stdout[-2000:])
    return {
        "schema_version": "vcd_to_fst_conversion.v1",
        "command": command,
        "returncode": completed.returncode,
        "input_sha256": _sha256(vcd),
        "output_sha256": _sha256(fst),
    }


def _first_violated_output(vcd: Path, outputs: Sequence[Mapping[str, Any]], clock: str) -> dict[str, Any]:
    definitions, samples = _read_vcd(vcd)
    clock_code = _unique_vcd_code(definitions, f"ChiselCauseMiter.{clock}")
    assertion_code = _unique_vcd_code(
        definitions, "ChiselCauseMiter.chiselcause_outputs_match"
    )
    pairs = {
        row["name"]: (
            _unique_vcd_code(definitions, f"ChiselCauseMiter.clean_{row['name']}"),
            _unique_vcd_code(definitions, f"ChiselCauseMiter.faulty_{row['name']}"),
        )
        for row in outputs
    }
    values: dict[str, str] = {}
    previous_clock = "x"
    cycle = -1
    for time, changes in samples:
        values.update(changes)
        current_clock = values.get(clock_code, "x")
        if previous_clock != "1" and current_clock == "1":
            cycle += 1
            if values.get(assertion_code) != "0":
                previous_clock = current_clock
                continue
            for name in sorted(pairs):
                clean_value = values.get(pairs[name][0])
                faulty_value = values.get(pairs[name][1])
                if clean_value is not None and faulty_value is not None and clean_value != faulty_value:
                    return {"output": name, "cycle": cycle, "time": time}
        previous_clock = current_clock
    raise CounterexampleNotTriggered("CEX VCD contains no public-output mismatch on a rising edge")


def _read_vcd(path: Path) -> tuple[dict[str, str], list[tuple[int, dict[str, str]]]]:
    definitions: dict[str, str] = {}
    scopes: list[str] = []
    samples: list[tuple[int, dict[str, str]]] = []
    current_time = 0
    changes: dict[str, str] = {}
    in_definitions = True
    for raw in path.read_text(encoding="utf-8", errors="replace").splitlines():
        line = raw.strip()
        if in_definitions:
            if line.startswith("$scope "):
                scopes.append(line.split()[2])
            elif line.startswith("$upscope"):
                scopes.pop()
            elif line.startswith("$var "):
                parts = line.split()
                code, name = parts[3], parts[4]
                definitions[".".join([*scopes, name])] = code
            elif line.startswith("$enddefinitions"):
                in_definitions = False
            continue
        if line.startswith("#"):
            if changes:
                samples.append((current_time, changes))
            current_time = int(line[1:])
            changes = {}
        elif line and line[0] in "01xXzZ":
            changes[line[1:]] = line[0].lower()
        elif line and line[0] in "bBrR":
            value, code = line.split(None, 1)
            changes[code] = value[1:].lower()
    if changes:
        samples.append((current_time, changes))
    return definitions, samples


def _unique_vcd_code(definitions: Mapping[str, str], suffix: str) -> str:
    matches = {code for name, code in definitions.items() if name == suffix or name.endswith("." + suffix)}
    if len(matches) != 1:
        raise ChiselCauseExperimentError(f"VCD signal is missing or ambiguous: {suffix}")
    return next(iter(matches))


def validate_case(row: Mapping[str, Any]) -> None:
    _exact_fields(row, CASE_FIELDS, "case")
    if row["schema_version"] != CASE_SCHEMA or row["status"] != "complete":
        raise ChiselCauseExperimentError("case schema/status is invalid")
    if row["independence"] != {
        "cex_source": "differential_miter",
        "specflow_property_package_read": False,
        "model_calls": 0,
        "gold_read_during_prepare": False,
    }:
        raise ChiselCauseExperimentError("case is not independent from SpecFlow/gold inputs")


def validate_result(row: Mapping[str, Any]) -> None:
    _exact_fields(row, RESULT_FIELDS, "result")
    if row["schema_version"] != RESULT_SCHEMA or row["method"] not in METHODS:
        raise ChiselCauseExperimentError("result schema/method is invalid")
    if row["status"] not in {"complete", "incomplete", "unsupported", "tool_error"}:
        raise ChiselCauseExperimentError("result status is invalid")


def run_localization(args: argparse.Namespace) -> Path:
    if args.method != "d2":
        raise ChiselCauseExperimentError("C3 implements only the D2 ChiselCause method")
    policy_id = _resolve_search_policy(
        getattr(args, "search_policy", "legacy_dfs_v1")
    )
    run_dir = Path(args.run).resolve()
    cases = _read_json(run_dir / "cases.json")["cases"]
    matches = [row for row in cases if row["case_id"] == args.case_id]
    if len(matches) != 1:
        raise ChiselCauseExperimentError("case_id is absent or ambiguous")
    case = matches[0]
    existing = _read_jsonl(run_dir / "results.jsonl")
    if any(
        row["case_id"] == args.case_id
        and row["method"] == args.method
        and row.get("input_identity", {}).get("search_policy_id") == policy_id
        for row in existing
    ):
        raise ChiselCauseExperimentError("case/method result already exists")

    from verilog_causal_analysis import build_source_ranking, make_request, policy_identity, prepare_causal_session
    from verilog_causal_analysis.cycle_waveform import CycleAlignedWaveform
    from verilog_causal_analysis.identity import sha256_file

    method_dir = run_dir / "raw" / args.case_id / args.method / policy_id
    method_dir.mkdir(parents=True, exist_ok=False)
    rtl_files = []
    for index, row in enumerate(case["artifacts"]["diagnosis_rtl_set"], 1):
        path = (run_dir / row["path"]).resolve()
        digest, size = sha256_file(path)
        rtl_files.append(
            {
                "artifact_id": f"rtl_{index:04d}",
                "path": str(path),
                "sha256": digest,
                "bytes": size,
            }
        )
    trace_path = (run_dir / case["artifacts"]["fst"]["path"]).resolve()
    trace_sha256, trace_bytes = sha256_file(trace_path)
    with CycleAlignedWaveform(str(trace_path), "clock") as waveform:
        endpoint = waveform.resolve_signal(case["cex"]["diagnosis_endpoint"])
        clock = waveform.resolve_signal(case["endpoint_projection"]["clock_signal"])
    if endpoint.resolved_signal is None or clock.resolved_signal is None:
        raise ChiselCauseExperimentError("CEX endpoint or clock is not uniquely present in FST")
    request = make_request(
        trace={
            "artifact_id": "trace_0001",
            "path": str(trace_path),
            "format": "fst",
            "sha256": trace_sha256,
            "bytes": trace_bytes,
        },
        rtl_files=rtl_files,
        semantic_profile={
            "name": "chisel",
            "version": "chisel-semantic-profile",
            "features": [
                "instance_graph",
                "compiler_net_normalization",
                "register_transition",
                "aggregate",
                "handshake",
                "pipeline",
                "temporal_interval",
                "source_provenance",
            ],
        },
        clock={"signal": clock.resolved_signal, "edge": "rising"},
        endpoint={
            "signal": endpoint.resolved_signal,
            "cycle": case["cex"]["failure_cycle"],
            "projection": None,
        },
        semantic_inputs=[],
        search_policy=policy_identity(policy_id).to_dict(),
        bounds={
            "max_signal_depth": min(args.max_nodes, 256),
            "max_signal_nodes": args.max_nodes,
            "max_expanded_nodes": args.max_expanded_nodes or args.max_nodes,
            "max_candidate_evaluations": (
                args.max_candidate_evaluations or args.max_nodes * 8
            ),
            "max_intervention_evaluations": (
                args.max_intervention_evaluations or args.max_nodes * 32
            ),
            "max_semantic_nodes": args.max_semantic_nodes,
            "max_edges": args.max_edges,
            "max_seed_count": 8,
            "max_intervals_per_signal": 64,
            "max_temporal_samples": 64000,
            "max_waitfor_nodes": args.max_nodes,
            "max_waitfor_edges": args.max_edges,
            "max_scc_candidates": 8,
        },
        random_seed=0,
        strict=True,
    )
    started = time.perf_counter()
    before_rss = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss
    with prepare_causal_session(request, top_module="ChiselCauseMiter") as session:
        graph = session.build()
        search_trace = session.search_trace
    runtime = time.perf_counter() - started
    peak_rss = max(before_rss, resource.getrusage(resource.RUSAGE_SELF).ru_maxrss) * 1024
    _write_json(method_dir / "causal_graph.json", graph)
    _write_json(method_dir / "request.json", request.to_dict())
    _write_search_trace(method_dir / "search_trace.jsonl", search_trace, policy_id)
    elaboration_path = run_dir / case["faulty_variant"]["elaboration"]["path"]
    ranking = build_source_ranking(
        graph,
        _read_json(elaboration_path),
        case_id=args.case_id,
        method=args.method,
        source_root=elaboration_path.parent / "workspace",
    )
    _write_json(method_dir / "source_ranking.json", ranking)
    complete = graph["status"] == "complete" and ranking["status"] == "complete"
    summary = graph["search_summary"]
    row = {
        "schema_version": RESULT_SCHEMA,
        "case_id": args.case_id,
        "method": args.method,
        "status": "complete" if complete else "incomplete",
        "input_identity": {
            "case_sha256": _canonical_sha256(case),
            "request_sha256": request.request_sha256,
            "graph_sha256": _sha256(method_dir / "causal_graph.json"),
            "source_ranking_sha256": _sha256(method_dir / "source_ranking.json"),
            "search_trace_sha256": _sha256(method_dir / "search_trace.jsonl"),
            "search_policy_id": policy_id,
            "comparison_arms": list(POLICY_COMPARISON_ARMS[policy_id]),
            "policy_sha256": request.search_policy.policy_sha256,
        },
        "source_ranking": _artifact(run_dir, method_dir / "source_ranking.json"),
        "search_trace": _artifact(run_dir, method_dir / "search_trace.jsonl"),
        "work": {
            "graph_nodes": len(graph["signal_nodes"]) + len(graph["semantic_nodes"]),
            "graph_signal_nodes": len(graph["signal_nodes"]),
            "graph_semantic_nodes": len(graph["semantic_nodes"]),
            "graph_edges": len(graph["edges"]),
            "expanded_nodes": summary["expanded_nodes"],
            "candidate_evaluations": summary["candidate_evaluations"],
            "intervention_evaluations": summary["intervention_evaluations"],
        },
        "metrics": None,
        "runtime_seconds": round(runtime, 6),
        "peak_rss_bytes": peak_rss,
        "termination_reason": summary["termination_reason"],
        "failure_reason": None if complete else "graph_or_source_projection_incomplete",
    }
    validate_result(row)
    _append_jsonl(run_dir / "results.jsonl", row)
    _write_tables(run_dir)
    _write_report(run_dir)
    return method_dir / "source_ranking.json"


def run_coupledl2(args: argparse.Namespace) -> Path:
    """Run one hash-bound C4 D2 case with the shared paper bounds."""

    from verilog_causal_analysis import (
        build_causal_graph,
        build_source_ranking,
        make_request,
        policy_identity,
    )
    from verilog_causal_analysis.cycle_waveform import CycleAlignedWaveform
    from verilog_causal_analysis.identity import sha256_file, stable_set_sha256

    repo = Path(args.repo).resolve()
    case_id = args.case_id
    spec = COUPLEDL2_CASES[case_id]
    variant = case_id.rsplit("v", 1)[-1]
    case_root = (
        repo
        / args.cases_root
        / f"XiangShan-CoupledL2-deadlock-v{variant}"
    ).resolve()
    experiment_id = _safe_id(args.experiment_id)
    run_dir = repo / "runs/chiselcause-paper" / experiment_id
    if run_dir.exists():
        raise ChiselCauseExperimentError(f"experiment already exists: {run_dir}")
    method_dir = run_dir / "raw" / case_id / "d2"
    method_dir.mkdir(parents=True)

    primary = _coupledl2_primary_rtl(case_root, variant)
    rtl_paths = [primary, *_coupledl2_support_rtl(case_root, primary)]
    rtl_files = []
    for index, path in enumerate(rtl_paths, 1):
        digest, size = sha256_file(path)
        rtl_files.append(
            {
                "artifact_id": f"rtl_{index:04d}",
                "path": str(path),
                "sha256": digest,
                "bytes": size,
            }
        )
    rtl_set_sha256 = stable_set_sha256(
        [
            {
                "artifact_id": row["artifact_id"],
                "sha256": row["sha256"],
                "bytes": row["bytes"],
            }
            for row in rtl_files
        ]
    )
    trace_path = case_root / f"XiangShan-CoupledL2-deadlock-v{variant}.fst"
    trace_sha256, trace_bytes = sha256_file(trace_path)
    clock = "VerifyTop.clock"
    endpoint = str(spec["endpoint"])
    cycle = int(spec["cycle"])
    predicate_members = list(spec["predicate_members"])
    with CycleAlignedWaveform(str(trace_path), clock, exact_clock=True) as waveform:
        if waveform.get_cycle_count() != cycle + 1:
            raise ChiselCauseExperimentError("CoupledL2 failure cycle no longer matches the FST")
        if waveform.get_signal_value(endpoint, cycle) != "0":
            raise ChiselCauseExperimentError("CoupledL2 endpoint is not violated at the bound cycle")
        if any(not waveform.has_exact_signal(member) for member in predicate_members):
            raise ChiselCauseExperimentError("CoupledL2 projection member is absent from the FST")

    projection = {
        "schema_version": "assertion_endpoint_projection",
        "endpoint_signal": endpoint,
        "endpoint_cycle": cycle,
        "clock_signal": clock,
        "predicate_members": predicate_members,
        "rtl_set_sha256": rtl_set_sha256,
        "trace_sha256": trace_sha256,
    }
    projection_path = method_dir / "assertion_endpoint_projection.json"
    _write_json(projection_path, projection)
    projection_sha256, projection_bytes = sha256_file(projection_path)
    request = make_request(
        trace={
            "artifact_id": "trace_0001",
            "path": str(trace_path),
            "format": "fst",
            "sha256": trace_sha256,
            "bytes": trace_bytes,
        },
        rtl_files=rtl_files,
        semantic_profile={
            "name": "chisel",
            "version": "chisel-semantic-profile",
            "features": [
                "instance_graph",
                "endpoint_projection",
                "compiler_net_normalization",
                "register_transition",
                "aggregate",
                "handshake",
                "pipeline",
                "temporal_interval",
                "source_provenance",
            ],
        },
        clock={"signal": clock, "edge": "rising"},
        endpoint={
            "signal": endpoint,
            "cycle": cycle,
            "projection": {
                "mode": "controller_supplied_exact",
                "predicate_members": predicate_members,
                "evidence_ref": "projection_0001",
            },
        },
        semantic_inputs=[
            {
                "artifact_id": "projection_0001",
                "path": str(projection_path.resolve()),
                "sha256": projection_sha256,
                "bytes": projection_bytes,
                "kind": "assertion_endpoint_projection",
            }
        ],
        search_policy=policy_identity("legacy_dfs_v1").to_dict(),
        bounds={
            "max_signal_depth": min(args.max_nodes, 256),
            "max_signal_nodes": args.max_nodes,
            "max_expanded_nodes": args.max_nodes,
            "max_candidate_evaluations": args.max_nodes * 8,
            "max_intervention_evaluations": args.max_nodes * 32,
            "max_semantic_nodes": args.max_semantic_nodes,
            "max_edges": args.max_edges,
            "max_seed_count": 8,
            "max_intervals_per_signal": 64,
            "max_temporal_samples": 64000,
            "max_waitfor_nodes": args.max_nodes,
            "max_waitfor_edges": args.max_edges,
            "max_scc_candidates": 8,
        },
        random_seed=0,
        strict=True,
    )
    _write_json(method_dir / "request.json", request.to_dict())
    started = time.perf_counter()
    before_rss = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss
    graph = build_causal_graph(request, top_module="VerifyTop")
    runtime = time.perf_counter() - started
    peak_rss = max(before_rss, resource.getrusage(resource.RUSAGE_SELF).ru_maxrss) * 1024
    _write_json(method_dir / "causal_graph.json", graph)
    ranking = build_source_ranking(
        graph,
        {"objects": [], "source_locators": []},
        case_id=case_id,
        method="d2",
        source_root=case_root,
    )
    _write_json(method_dir / "source_ranking.json", ranking)
    summary = {
        "schema_version": "chiselcause_coupledl2_c4_result.v1",
        "case_id": case_id,
        "method": "d2",
        "status": (
            "complete"
            if graph["status"] == "complete" and ranking["status"] == "complete"
            else "incomplete"
        ),
        "request_sha256": request.request_sha256,
        "graph_sha256": _sha256(method_dir / "causal_graph.json"),
        "source_ranking_sha256": _sha256(method_dir / "source_ranking.json"),
        "rtl_files": [
            {"path": str(path), "sha256": _sha256(path), "bytes": path.stat().st_size}
            for path in rtl_paths
        ],
        "runtime_seconds": round(runtime, 6),
        "peak_rss_bytes": peak_rss,
        "graph_status": graph["status"],
        "source_ranking_status": ranking["status"],
        "bounds": dict(request.bounds),
    }
    _write_json(run_dir / "summary.json", summary)
    return run_dir


def _coupledl2_primary_rtl(case_root: Path, variant: str) -> Path:
    candidates = (
        case_root / "Chisel/Verilog/L2L3L2/VerifyTop.sv",
        case_root / "Verilog/VerifyTop_performance.sv",
    )
    matches = [path.resolve() for path in candidates if path.is_file()]
    if len(matches) != 1:
        raise ChiselCauseExperimentError(
            f"deadlock-v{variant} requires exactly one canonical emitted RTL"
        )
    return matches[0]


def _coupledl2_support_rtl(case_root: Path, primary: Path) -> list[Path]:
    """Select one nearby definition for each small external support module."""

    result = []
    for name in _SUPPORT_RTL_NAMES:
        candidates = [
            path.resolve()
            for path in case_root.rglob(name)
            if path.resolve() != primary
        ]
        if not candidates:
            continue
        candidates.sort(
            key=lambda path: (
                0 if path.parent == primary.parent else 1,
                0 if "Verilog" in path.parts else 1,
                len(path.parts),
                str(path),
            )
        )
        result.append(candidates[0])
    return result


def score(args: argparse.Namespace) -> Path:
    run_dir = Path(args.run).resolve()
    policy_id = (
        _resolve_search_policy(args.search_policy)
        if getattr(args, "search_policy", None)
        else None
    )
    gold = _read_json(Path(args.gold).resolve())
    locations = [row for row in gold.get("locations", []) if row.get("bug_id") == args.bug_id]
    if len(locations) != 1:
        raise ChiselCauseExperimentError("bug_id is absent or ambiguous in gold manifest")
    results = _read_jsonl(run_dir / "results.jsonl")
    matches = [
        row for row in results
        if row["case_id"] == args.case_id and row["method"] == args.method
        and (
            policy_id is None
            or row.get("input_identity", {}).get("search_policy_id") == policy_id
        )
    ]
    if len(matches) != 1:
        raise ChiselCauseExperimentError("one method/policy result is required before score")
    result = matches[0]
    ranking_path = run_dir / result["source_ranking"]["path"]
    ranking = _read_json(ranking_path)
    target = locations[0]
    ranked = [
        row for row in ranking["ordering"]
        if row["file"] == target["path"] and row["line"] == target["line"]
    ]
    evaluation = _evaluate_source_ranking(ranking, target)
    gold_rank = evaluation["gold_rank"]
    gold_row = ranked[0] if gold_rank is not None else None
    trace = (
        _read_jsonl(run_dir / result["search_trace"]["path"])
        if result.get("search_trace") is not None
        else []
    )
    first_gold = _first_gold_work(gold_row, trace)
    checkpoints = _budget_checkpoint_metrics(
        run_dir=run_dir,
        result=result,
        ranking=ranking,
        target=target,
        trace=trace,
        checkpoints=ABSOLUTE_BUDGET_CHECKPOINTS,
        enabled=evaluation["evaluation_status"] == "complete" and args.method == "d2",
    )
    metrics = {
        "schema_version": METRICS_SCHEMA,
        "case_id": args.case_id,
        "method": args.method,
        "bug_id": args.bug_id,
        "gold_source": {"file": target["path"], "line": target["line"]},
        **evaluation,
        "nodes_to_first_gold": first_gold["expanded_nodes"],
        "interventions_to_first_gold": first_gold["intervention_evaluations"],
        "budget_checkpoints": checkpoints,
        "ranking_sha256": _sha256(ranking_path),
        "gold_manifest_sha256": _sha256(Path(args.gold).resolve()),
    }
    metrics_path = ranking_path.with_name("metrics.json")
    _write_json(metrics_path, metrics)
    result["metrics"] = _artifact(run_dir, metrics_path)
    result["input_identity"]["gold_manifest_sha256"] = metrics["gold_manifest_sha256"]
    result["input_identity"]["bug_id"] = args.bug_id
    _write_jsonl(run_dir / "results.jsonl", results)
    _write_tables(run_dir)
    _write_report(run_dir)
    return metrics_path


def _evaluate_source_ranking(
    ranking: Mapping[str, Any], target: Mapping[str, Any]
) -> dict[str, Any]:
    matches = [
        row
        for row in ranking["ordering"]
        if row["file"] == target["path"] and row["line"] == target["line"]
    ]
    gold_row = matches[0] if len(matches) == 1 else None
    gold_reachable = bool(
        gold_row is not None
        and gold_row.get("positive_authoritative_evidence") is True
    )
    complete_graph = ranking.get("complete_graph") is True
    complete_projection = ranking.get("complete_source_projection") is True
    if not complete_graph:
        status, reason = "incomplete", "graph_incomplete"
    elif not complete_projection:
        status, reason = "incomplete", "source_projection_incomplete"
    elif not matches:
        status, reason = "gold_unreachable", "gold_not_in_statement_universe"
    elif len(matches) != 1:
        status, reason = "gold_unreachable", "gold_location_ambiguous"
    elif not gold_reachable:
        status, reason = (
            "gold_unreachable",
            "gold_without_positive_authoritative_evidence",
        )
    else:
        status, reason = "complete", None
    evaluable = status == "complete"
    rank = gold_row["rank"] if evaluable else None
    position = gold_row["position"] if evaluable else None
    count = int(ranking["statement_candidate_count"])
    return {
        "evaluation_status": status,
        "evaluation_reason": reason,
        "complete_graph": complete_graph,
        "complete_source_projection": complete_projection,
        "statement_candidate_count": count,
        "authoritative_candidate_count": int(
            ranking["authoritative_candidate_count"]
        ),
        "positive_authoritative_candidate_count": int(
            ranking["positive_authoritative_candidate_count"]
        ),
        "gold_reachable": gold_reachable,
        "gold_statement_id": gold_row.get("statement_id") if gold_row else None,
        "gold_rank": rank,
        "gold_position": position,
        "tie_size": gold_row["tie_size"] if evaluable else None,
        "mrr": round(1.0 / rank, 6) if evaluable else None,
        "exam_percent": round(100.0 * position / count, 6) if evaluable else None,
        "top_1": rank <= 1 if evaluable else None,
        "top_3": rank <= 3 if evaluable else None,
        "top_5": rank <= 5 if evaluable else None,
        "top_10": rank <= 10 if evaluable else None,
    }


def _write_search_trace(path: Path, events: Sequence[Mapping[str, Any]], policy_id: str) -> None:
    rows = [
        {
            "schema_version": SEARCH_TRACE_SCHEMA,
            "search_policy_id": policy_id,
            **dict(event),
        }
        for event in events
    ]
    _write_jsonl(path, rows)


def _first_gold_work(
    gold_row: Mapping[str, Any] | None, trace: Sequence[Mapping[str, Any]]
) -> dict[str, int | None]:
    if gold_row is None:
        return {"expanded_nodes": None, "intervention_evaluations": None}
    evidence = set(gold_row.get("evidence_node_ids", ()))
    for event in trace:
        observed = {
            event.get("node_id"),
            event.get("parent_node_id"),
            event.get("target_node_id"),
            event.get("edge_id"),
        }
        if evidence.intersection(observed):
            cumulative = event["cumulative"]
            return {
                "expanded_nodes": cumulative["expanded_nodes"],
                "intervention_evaluations": cumulative["intervention_evaluations"],
            }
    return {"expanded_nodes": None, "intervention_evaluations": None}


def _budget_checkpoint_metrics(
    *,
    run_dir: Path,
    result: Mapping[str, Any],
    ranking: Mapping[str, Any],
    target: Mapping[str, Any],
    trace: Sequence[Mapping[str, Any]],
    checkpoints: Sequence[int],
    enabled: bool,
) -> list[dict[str, Any]]:
    expanded = int((result.get("work") or {}).get("expanded_nodes", 0))
    rows = []
    for checkpoint in checkpoints:
        if not enabled:
            rows.append({"expanded_nodes": checkpoint, "status": "not_applicable", "gold_rank": None, "mrr": None})
            continue
        if checkpoint > expanded:
            rows.append({"expanded_nodes": checkpoint, "status": "not_reached", "gold_rank": None, "mrr": None})
            continue
        evidence = set()
        for event in trace:
            if int(event["cumulative"]["expanded_nodes"]) > checkpoint:
                continue
            evidence.update(
                value
                for value in (
                    event.get("node_id"),
                    event.get("parent_node_id"),
                    event.get("target_node_id"),
                    event.get("edge_id"),
                )
                if value is not None
            )
        discovered = [
            row
            for row in ranking["ordering"]
            if evidence.intersection(row.get("evidence_node_ids", ()))
        ]
        gold_positions = [
            index
            for index, row in enumerate(discovered, 1)
            if row["file"] == target["path"] and row["line"] == target["line"]
        ]
        gold_rank = gold_positions[0] if len(gold_positions) == 1 else None
        rows.append(
            {
                "expanded_nodes": checkpoint,
                "status": "complete",
                "gold_rank": gold_rank,
                "mrr": round(1.0 / gold_rank, 6) if gold_rank else 0.0,
            }
        )
    return rows


def _write_tables(run_dir: Path) -> None:
    result_rows = _read_jsonl(run_dir / "results.jsonl")
    smoke_rows = []
    budget_rows = []
    for result in result_rows:
        metrics = (
            _read_json(run_dir / result["metrics"]["path"])
            if result.get("metrics") is not None
            else None
        )
        base_smoke = {
                "case_id": result["case_id"],
                "method": result["method"],
                "search_policy_id": result.get("input_identity", {}).get("search_policy_id"),
                "status": result["status"],
                "termination_reason": result.get("termination_reason"),
                "work": result.get("work"),
                "runtime_seconds": result["runtime_seconds"],
                "peak_rss_bytes": result["peak_rss_bytes"],
                "gold_rank": metrics.get("gold_rank") if metrics else None,
                "gold_position": metrics.get("gold_position") if metrics else None,
                "tie_size": metrics.get("tie_size") if metrics else None,
                "gold_reachable": metrics.get("gold_reachable") if metrics else None,
                "evaluation_status": metrics.get("evaluation_status") if metrics else None,
                "evaluation_reason": metrics.get("evaluation_reason") if metrics else None,
                "mrr": metrics.get("mrr") if metrics else None,
                "exam_percent": metrics.get("exam_percent") if metrics else None,
                "top_1": metrics.get("top_1") if metrics else None,
                "top_3": metrics.get("top_3") if metrics else None,
                "top_5": metrics.get("top_5") if metrics else None,
                "top_10": metrics.get("top_10") if metrics else None,
            }
        arms = result.get("input_identity", {}).get("comparison_arms") or [None]
        smoke_rows.extend({**base_smoke, "comparison_arm": arm} for arm in arms)
        if metrics:
            for checkpoint in metrics["budget_checkpoints"]:
                base_budget = {
                        "case_id": result["case_id"],
                        "method": result["method"],
                        "search_policy_id": result.get("input_identity", {}).get("search_policy_id"),
                        **checkpoint,
                    }
                budget_rows.extend(
                    {**base_budget, "comparison_arm": arm} for arm in arms
                )
    _write_json(
        run_dir / "tables" / "localization_smoke.json",
        {"schema_version": "chiselcause_localization_smoke.v1", "rows": smoke_rows},
    )
    _write_jsonl(run_dir / "tables" / "budget_curve.jsonl", budget_rows)


def _freeze_schemas(run_dir: Path) -> None:
    rows = []
    for name in ("chiselcause_case.schema.json", "chiselcause_result.schema.json"):
        source = _asset(name)
        target = run_dir / name
        shutil.copyfile(source, target)
        rows.append(_artifact(run_dir, target))
    _write_json(run_dir / "schema_contract.json", {"schema_version": "chiselcause_schema_contract.v1", "schemas": rows})


def _variant_row(config: GeneratorConfiguration, elaborated: Mapping[str, Any], run_dir: Path) -> dict[str, Any]:
    return {
        "configuration_id": config.configuration_id,
        "parameters": dict(config.parameters),
        "configuration_sha256": _sha256(config.path),
        "elaboration": _artifact(run_dir, elaborated["metadata_path"]),
        "rtl": _artifact(run_dir, elaborated["rtl"]),
    }


def _artifact(run_dir: Path, path: Path) -> dict[str, Any]:
    path = path.resolve()
    return {
        "path": str(path.relative_to(run_dir.resolve())),
        "sha256": _sha256(path),
        "bytes": path.stat().st_size,
    }


def _write_report(run_dir: Path) -> None:
    cases = _read_json(run_dir / "cases.json")["cases"]
    lines = [
        "# ChiselCause experiment",
        "",
        f"- Complete independent cases: {len(cases)}",
        f"- Result rows: {sum(1 for line in (run_dir / 'results.jsonl').read_text().splitlines() if line.strip())}",
        f"- Complete source rankings: {sum(row.get('status') == 'complete' and row.get('source_ranking') is not None for row in _read_jsonl(run_dir / 'results.jsonl'))}",
        "- CEX source: clean/faulty differential miter; no SpecFlow property package",
        "",
    ]
    (run_dir / "report.md").write_text("\n".join(lines), encoding="utf-8")


def run(args: argparse.Namespace) -> None:
    if args.chiselcause_exp_action == "prepare":
        print(json.dumps({"run_dir": str(prepare(args))}, sort_keys=True))
        return
    if args.chiselcause_exp_action == "run":
        print(json.dumps({"source_ranking": str(run_localization(args))}, sort_keys=True))
        return
    if args.chiselcause_exp_action == "score":
        print(json.dumps({"metrics": str(score(args))}, sort_keys=True))
        return
    if args.chiselcause_exp_action == "coupledl2":
        print(json.dumps({"run_dir": str(run_coupledl2(args))}, sort_keys=True))
        return
    raise ChiselCauseExperimentError("unknown chiselcause-exp action")


def add_parser(subparsers: argparse._SubParsersAction[argparse.ArgumentParser]) -> None:
    parser = subparsers.add_parser("chiselcause-exp", help="independent ChiselCause paper experiment")
    actions = parser.add_subparsers(dest="chiselcause_exp_action", required=True)
    prepare_parser = actions.add_parser("prepare")
    prepare_parser.add_argument("--repo", default=".")
    prepare_parser.add_argument("--experiment-id")
    prepare_parser.add_argument("--case-id")
    prepare_parser.add_argument("--project-contract", required=True)
    prepare_parser.add_argument("--clean-config", required=True)
    prepare_parser.add_argument("--faulty-config", required=True)
    prepare_parser.add_argument("--witness")
    prepare_parser.add_argument("--witness-signal", action="append")
    prepare_parser.add_argument("--timeout-seconds", type=int, default=300)
    prepare_parser.add_argument("--per-property-seconds", type=int, default=60)
    run_parser = actions.add_parser("run")
    run_parser.add_argument("--run", required=True)
    run_parser.add_argument("--case-id", required=True)
    run_parser.add_argument("--method", choices=METHODS, required=True)
    run_parser.add_argument(
        "--search-policy",
        default="legacy_dfs_v1",
        help="local-search policy ID or CS0/CS1/H0/H1/H2 alias",
    )
    run_parser.add_argument("--max-nodes", type=int, default=240)
    run_parser.add_argument("--max-expanded-nodes", type=int)
    run_parser.add_argument("--max-candidate-evaluations", type=int)
    run_parser.add_argument("--max-intervention-evaluations", type=int)
    run_parser.add_argument("--max-semantic-nodes", type=int, default=480)
    run_parser.add_argument("--max-edges", type=int, default=960)
    score_parser = actions.add_parser("score")
    score_parser.add_argument("--run", required=True)
    score_parser.add_argument("--case-id", required=True)
    score_parser.add_argument("--method", choices=("d0", "d1", "d2"), default="d2")
    score_parser.add_argument("--bug-id", required=True)
    score_parser.add_argument("--gold", required=True)
    score_parser.add_argument("--search-policy")
    coupled_parser = actions.add_parser("coupledl2")
    coupled_parser.add_argument("--repo", default=".")
    coupled_parser.add_argument(
        "--cases-root", default="CoupledL2-Verification/code/CaseStudy_1"
    )
    coupled_parser.add_argument("--experiment-id", required=True)
    coupled_parser.add_argument("--case-id", choices=sorted(COUPLEDL2_CASES), required=True)
    coupled_parser.add_argument("--max-nodes", type=int, default=2000)
    coupled_parser.add_argument("--max-semantic-nodes", type=int, default=600)
    coupled_parser.add_argument("--max-edges", type=int, default=4000)


def _asset(name: str) -> Path:
    return Path(__file__).with_name("assets") / name


def _safe_id(value: str) -> str:
    if not _SAFE_ID.fullmatch(value):
        raise ChiselCauseExperimentError(f"unsafe identifier: {value}")
    return value


def _resolve_search_policy(value: str) -> str:
    if not isinstance(value, str) or not value:
        raise ChiselCauseExperimentError("search policy must be a non-empty string")
    alias = SEARCH_POLICY_ALIASES.get(value.upper())
    if alias is not None:
        return alias
    if value in SEARCH_POLICY_IDS:
        return value
    raise ChiselCauseExperimentError(
        f"unknown search policy {value!r}; expected one of "
        f"{', '.join(SEARCH_POLICY_ALIASES)} or {', '.join(SEARCH_POLICY_IDS)}"
    )


def _exact_fields(row: Mapping[str, Any], fields: set[str], label: str) -> None:
    if set(row) != fields:
        raise ChiselCauseExperimentError(
            f"{label} fields mismatch: missing={sorted(fields - set(row))}, extra={sorted(set(row) - fields)}"
        )


def _canonical_sha256(value: Any) -> str:
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(",", ":")).encode()).hexdigest()


def _sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _write_json(path: Path, value: Any) -> None:
    path.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def _read_json(path: Path) -> dict[str, Any]:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ChiselCauseExperimentError(f"JSON object required: {path}")
    return value


def _append_jsonl(path: Path, row: Mapping[str, Any]) -> None:
    with path.open("a", encoding="utf-8") as stream:
        stream.write(json.dumps(row, sort_keys=True) + "\n")


def _read_jsonl(path: Path) -> list[dict[str, Any]]:
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def _write_jsonl(path: Path, rows: Sequence[Mapping[str, Any]]) -> None:
    path.write_text(
        "".join(json.dumps(row, sort_keys=True) + "\n" for row in rows),
        encoding="utf-8",
    )
