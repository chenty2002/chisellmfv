"""Run-local native-Verilog corpus preparation for VerilogCause."""

from __future__ import annotations

import argparse
import csv
import difflib
import hashlib
import json
import math
import random
import re
import shutil
import subprocess
import time
from collections import defaultdict
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
from importlib.metadata import PackageNotFoundError, version
from pathlib import Path
from statistics import fmean, pstdev
from typing import Any, Callable, Sequence


FEATURES = (
    "fail_execution_rate",
    "pass_execution_rate",
    "execution_specificity",
    "causal_trace_coverage",
    "causal_support_mean",
    "causal_support_max",
    "near_failure_mean",
    "temporal_stability",
    "sequential_ratio",
    "relation_coverage",
)
W3_BASELINES = (
    "ochiai_fixed_pool",
    "tarsel_formula_fixed_pool",
    "unweighted_exact_slice",
)
W4_LEARNED_METHODS = ("coverage_only", "causal_only", "ml_relation")
W4_FEATURES = {
    "coverage_only": FEATURES[:3],
    "causal_only": FEATURES[3:],
    "ml_relation": FEATURES,
}
W4_SPLITS = {"lobo": "case_id", "lodo": "design_id", "lofo": "family_id"}
W4_EPOCHS = 20
VERILOG_38_INVENTORY_SHA256 = (
    "767d1ba6bbcbafb102e429ac059a243f57c6aba751769b9048ef429cbef85256"
)
EXCLUDED_CASES = {
    "counter-3": "missing_faulty_statement",
    "i2c-1": "missing_faulty_statement",
    "sdram-1": "declaration_error",
}
PILOT_CASES = (
    "i2c-2",
    "arbiter-3",
    "decoder_3_to_8_4",
    "i2c-3",
    "reed_solomon_decoder-1",
)
MAX_REPLAY_CANDIDATES_PER_CASE = 48
MAX_OPERATIONS_PER_CANDIDATE = 4
COUNTERFACTUAL_WORKERS = 8
MULTI_ENDPOINT_WINDOW = 2
MAX_ENDPOINTS_PER_TRACE = 8
TARGET_CASES = (
    "arbiter-1",
    "arbiter-2",
    "arbiter-3",
    "decoder_3_to_8_4",
    "i2c-2",
    "i2c-3",
    "i2c-4",
    "i2c-6",
    "led_controller-3",
    "reed_solomon_decoder-1",
    "reed_solomon_decoder-2",
    "sha3-2",
    "sha3-3",
)
WITNESS_BUDGETS = (1, 2, 4, 8)
WITNESS_POOL_SIZE = 16
HDL_PARSER_VERSIONS = {"hdlConvertor": "2.3", "hdlConvertorAst": "1.2"}
LEAKAGE_WORDS = ("buggy", "repair", "gold", "diff")
VERILATOR_FLAGS = (
    "-I.",
    "-f",
    "file_list.txt",
    "--cc",
    "-Wno-fatal",
    "--exe",
    "--build",
    "--binary",
    "-fno-table",
    "--coverage-line",
    "--trace",
    "--trace-structs",
    "--top",
    "testbench",
)
VCA_BOUNDS = {
    "max_signal_depth": 64,
    "max_signal_nodes": 480,
    "max_expanded_nodes": 480,
    "max_candidate_evaluations": 3840,
    "max_intervention_evaluations": 15360,
    "max_semantic_nodes": 480,
    "max_edges": 960,
    "max_seed_count": 8,
    "max_intervals_per_signal": 64,
    "max_temporal_samples": 4096,
    "max_waitfor_nodes": 480,
    "max_waitfor_edges": 960,
    "max_scc_candidates": 8,
}
DESIGN_DUT_CLOCKS = {
    design: f"testbench.DUT.{port}"
    for design, port in {
        "alu": "clk",
        "arbiter": "clk",
        "counter": "clk",
        "decoder_3_to_8": "clk",
        "fsm_16": "clk",
        "i2c": "wb_clk_i",
        "led_controller": "clk",
        "reed_solomon_decoder": "clk",
        "sha3": "clk",
    }.items()
} | {"sdram_controller": "testbench.sdram_controlleri.clk"}
_ENDPOINT_SAMPLING = "cycle_end_before_next_rising"
_SAFE_ID = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_.-]*$")


class VerilogCauseError(RuntimeError):
    pass


class PreparationError(VerilogCauseError):
    def __init__(self, status: str, message: str):
        super().__init__(message)
        self.status = status


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _canonical_sha256(value: Any) -> str:
    payload = json.dumps(value, sort_keys=True, separators=(",", ":")).encode()
    return hashlib.sha256(payload).hexdigest()


def _write_json(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def _append_jsonl(path: Path, row: dict[str, Any]) -> None:
    with path.open("a", encoding="utf-8") as stream:
        stream.write(json.dumps(row, sort_keys=True) + "\n")


def _read_jsonl(path: Path) -> list[dict[str, Any]]:
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines()]


def _write_jsonl(path: Path, rows: Sequence[dict[str, Any]]) -> None:
    path.write_text(
        "".join(json.dumps(row, sort_keys=True) + "\n" for row in rows),
        encoding="utf-8",
    )


def _git_head(path: Path) -> str:
    return subprocess.run(
        ["git", "-C", str(path), "rev-parse", "HEAD"],
        capture_output=True,
        text=True,
        timeout=10,
        check=True,
    ).stdout.strip()


def _vca_implementation_identity() -> dict[str, Any]:
    root = Path("VerilogCausalAnalysis")
    sources = [
        {"path": str(path.relative_to(root)), "sha256": _sha256(path)}
        for path in sorted((root / "src" / "verilog_causal_analysis").rglob("*.py"))
    ]
    status = subprocess.run(
        [
            "git",
            "-C",
            str(root),
            "status",
            "--short",
            "--untracked-files=no",
            "--",
            "src/verilog_causal_analysis",
        ],
        capture_output=True,
        text=True,
        timeout=10,
        check=True,
    ).stdout.splitlines()
    return {
        "vca_commit": _git_head(root),
        "vca_source_sha256": _canonical_sha256(sources),
        "vca_tracked_status": status,
    }


def _hdl_parser_versions() -> dict[str, str]:
    try:
        return {name: version(name) for name in HDL_PARSER_VERSIONS}
    except PackageNotFoundError as exc:
        raise VerilogCauseError(f"missing HDL parser package: {exc.name}") from exc


def validate_run_contract(run: Path) -> dict[str, Any]:
    contract_path = run / "dataset_contract.json"
    if not contract_path.is_file():
        raise VerilogCauseError("dataset contract is missing")
    contract = json.loads(contract_path.read_text(encoding="utf-8"))
    if contract.get("schema_version") != "verilogcause_dataset_contract.v1":
        raise VerilogCauseError("dataset contract schema is invalid")
    if contract.get("implementation_sha256") != _sha256(Path(__file__)):
        raise VerilogCauseError("run implementation hash drift")
    identity = contract.get("implementation_identity") or {}
    if {
        field: identity.get(field)
        for field in ("vca_commit", "vca_source_sha256", "vca_tracked_status")
    } != _vca_implementation_identity():
        raise VerilogCauseError("run VCA implementation drift")
    parsers = _hdl_parser_versions()
    if parsers != HDL_PARSER_VERSIONS or contract.get("hdl_parser_versions") != parsers:
        raise VerilogCauseError("run HDL parser version drift")
    if contract.get("w3_baselines") != list(W3_BASELINES):
        raise VerilogCauseError("W3 baseline contract is missing or stale")
    if contract.get("w4_learned_methods") != list(W4_LEARNED_METHODS):
        raise VerilogCauseError("W4 method contract is missing or stale")
    if contract.get("design_family_policy") != "sequential_flag_binary":
        raise VerilogCauseError("design/family policy is missing or stale")
    if contract.get("design_clock_map") != DESIGN_DUT_CLOCKS:
        raise VerilogCauseError("design clock contract is missing or stale")
    if contract.get("experiment_scope") != "native_verilog_38":
        raise VerilogCauseError("run is not the native_verilog_38 experiment")
    corpus = Path(str(contract.get("corpus_root", "")))
    inventory = contract.get("corpus_inventory")
    current_cases = [
        case
        for case in discover_cases(corpus)
        if case["case_id"] not in EXCLUDED_CASES
    ]
    if not corpus.is_dir() or inventory != _inventory_contract(current_cases, corpus):
        raise VerilogCauseError("run corpus inventory drift")
    if inventory.get("inventory_sha256") != VERILOG_38_INVENTORY_SHA256:
        raise VerilogCauseError("run corpus inventory is not the native Verilog 38 input")
    if contract.get("training") != {
        "epochs": W4_EPOCHS,
        "pair_normalization": "bug_then_positive_negative_pair",
        "splits": list(W4_SPLITS),
        "primary": "lofo_family_macro",
    }:
        raise VerilogCauseError("training settings are missing or stale")
    return contract


def _under(root: Path, raw: str, *, field: str) -> Path:
    path = (root / raw).resolve()
    try:
        path.relative_to(root)
    except ValueError as exc:
        raise VerilogCauseError(f"{field} escapes the Wit-HW root: {raw}") from exc
    if not path.is_file():
        raise VerilogCauseError(f"{field} is not a file: {raw}")
    return path


def discover_cases(corpus: Path, families: Sequence[str] | None = None) -> list[dict[str, Any]]:
    """Load Wit-HW metadata; its paths are relative to the Wit-HW root."""

    corpus = corpus.resolve()
    if not corpus.is_dir():
        raise VerilogCauseError(f"corpus is not a directory: {corpus}")
    wit_root = corpus.parent
    selected = set(families or ())
    cases = []
    seen = set()
    for metadata_path in sorted(corpus.rglob("bug-info-*.json")):
        relative = metadata_path.relative_to(corpus)
        family = relative.parts[0]
        if selected and family not in selected:
            continue
        data = json.loads(metadata_path.read_text(encoding="utf-8"))
        required = {
            "case_name",
            "module_name",
            "correct_design",
            "buggy_design",
            "testbench",
            "input_signals",
            "bug_trigger_input",
            "sequential_flag",
        }
        missing = sorted(required - data.keys())
        if missing:
            raise VerilogCauseError(f"{metadata_path}: missing fields {missing}")
        case_id = data["case_name"]
        if not isinstance(case_id, str) or not _SAFE_ID.fullmatch(case_id):
            raise VerilogCauseError(f"{metadata_path}: unsafe case_name {case_id!r}")
        if case_id in seen:
            raise VerilogCauseError(f"duplicate case_name: {case_id}")
        seen.add(case_id)
        signals = data["input_signals"]
        if not isinstance(signals, dict) or not signals or any(
            not isinstance(name, str) or not isinstance(width, int) or width < 1
            for name, width in signals.items()
        ):
            raise VerilogCauseError(f"{metadata_path}: invalid input_signals")
        paths = {
            field: _under(wit_root, data[field], field=field)
            for field in ("correct_design", "buggy_design", "testbench", "bug_trigger_input")
        }
        includes = [
            _under(wit_root, raw, field="include_files")
            for raw in data.get("include_files", [])
        ]
        cases.append(
            {
                "case_id": case_id,
                "family": family,
                "module_name": data["module_name"],
                "sequential": bool(data["sequential_flag"]),
                "input_signals": signals,
                "metadata": metadata_path.resolve(),
                **paths,
                "include_files": includes,
            }
        )
    unknown = selected - {case["family"] for case in cases}
    if unknown:
        raise VerilogCauseError(f"unknown or empty families: {sorted(unknown)}")
    if not cases:
        raise VerilogCauseError("no cases selected")
    return cases


def _inventory_contract(
    cases: Sequence[dict[str, Any]], corpus: Path
) -> dict[str, Any]:
    wit_root = corpus.resolve().parent

    def artifact(path: Path) -> dict[str, str]:
        return {
            "path": str(path.resolve().relative_to(wit_root)),
            "sha256": _sha256(path),
        }

    rows = [
        {
            "case_id": case["case_id"],
            "design_id": case["family"],
            "family_id": "sequential" if case["sequential"] else "combinational",
            "module_name": case["module_name"],
            "metadata": artifact(case["metadata"]),
            "correct_design": artifact(case["correct_design"]),
            "faulty_design": artifact(case["buggy_design"]),
            "testbench": artifact(case["testbench"]),
            "trigger": artifact(case["bug_trigger_input"]),
            "include_files": [artifact(path) for path in case["include_files"]],
        }
        for case in cases
    ]
    design_family = {}
    for row in rows:
        previous = design_family.setdefault(row["design_id"], row["family_id"])
        if previous != row["family_id"]:
            raise VerilogCauseError(f"design spans families: {row['design_id']}")
    return {
        "case_count": len(rows),
        "inventory_sha256": _canonical_sha256(rows),
        "design_count": len(design_family),
        "family_count": len(set(design_family.values())),
        "case_ids": [row["case_id"] for row in rows],
        "design_ids": sorted(design_family),
        "family_ids": sorted(set(design_family.values())),
        "design_family_map": dict(sorted(design_family.items())),
        "rows": rows,
    }


def sanitize_verilog(source: str) -> str:
    """Remove every comment while preserving strings, newlines, and token positions."""

    output: list[str] = []
    index = 0
    state = "code"
    while index < len(source):
        char = source[index]
        pair = source[index : index + 2]
        if state == "code":
            if pair == "//":
                output.extend("  ")
                index += 2
                state = "line"
                continue
            if pair == "/*":
                output.extend("  ")
                index += 2
                state = "block"
                continue
            output.append(char)
            if char == '"':
                state = "string"
        elif state == "string":
            output.append(char)
            if char == "\\" and index + 1 < len(source):
                index += 1
                output.append(source[index])
            elif char == '"':
                state = "code"
        elif state == "line":
            output.append(char if char in "\r\n" else " ")
            if char in "\r\n":
                state = "code"
        else:
            if pair == "*/":
                output.extend("  ")
                index += 1
                state = "code"
            else:
                output.append(char if char in "\r\n" else " ")
        index += 1
    if state == "block":
        raise VerilogCauseError("unterminated block comment")
    return "".join(output)


def _compile_sources(case: dict[str, Any], primary: str) -> list[Path]:
    excluded = {case["correct_design"].resolve(), case["buggy_design"].resolve()}
    sources = [case[primary]] + [
        path for path in case["include_files"] if path.resolve() not in excluded
    ]
    result = []
    seen = set()
    for path in sources:
        resolved = path.resolve()
        if resolved not in seen:
            result.append(resolved)
            seen.add(resolved)
    return result


def _copy_compile_inputs(
    destination: Path,
    sources: Sequence[Path],
    testbench: Path,
    *,
    sanitize: bool,
) -> list[dict[str, Any]]:
    destination.mkdir(parents=True, exist_ok=False)
    copied = []
    used_names = {"testbench.sv", "workload.in", "file_list.txt"}
    for index, source in enumerate(sources, 1):
        name = f"design{source.suffix}" if index == 1 else source.name
        if name in used_names:
            raise VerilogCauseError(f"compile filename collision: {name}")
        used_names.add(name)
        target = destination / name
        if sanitize:
            target.write_text(
                sanitize_verilog(source.read_text(encoding="utf-8")), encoding="utf-8"
            )
        else:
            shutil.copyfile(source, target)
        copied.append(
            {
                "artifact_id": f"rtl_{index:04d}",
                "compile_name": name,
                "path": str(target.resolve()),
                "sha256": _sha256(target),
                "bytes": target.stat().st_size,
            }
        )
    shutil.copyfile(testbench, destination / "testbench.sv")
    (destination / "file_list.txt").write_text(
        "".join(f"{row['compile_name']}\n" for row in copied) + "testbench.sv\n",
        encoding="utf-8",
    )
    return copied


def _run_command(
    argv: Sequence[str], cwd: Path, log_name: str, timeout: float
) -> dict[str, Any]:
    started = time.monotonic()
    try:
        result = subprocess.run(
            list(argv),
            cwd=cwd,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            timeout=timeout,
            check=False,
        )
        output = result.stdout
        returncode = result.returncode
        timed_out = False
    except subprocess.TimeoutExpired as exc:
        output = (exc.stdout or "") + (exc.stderr or "")
        returncode = None
        timed_out = True
    (cwd / log_name).write_text(output, encoding="utf-8")
    record = {
        "argv": list(argv),
        "cwd": str(cwd.resolve()),
        "timeout_seconds": timeout,
        "timed_out": timed_out,
        "returncode": returncode,
        "runtime_seconds": round(time.monotonic() - started, 6),
        "log": log_name,
    }
    _write_json(cwd / f"{log_name}.command.json", record)
    return record


def _compile(directory: Path, timeout: float) -> dict[str, Any]:
    compile_command = ["verilator", *VERILATOR_FLAGS]
    compile_command.insert(-2, "+define+DUMP_TRACE=1")
    compile_result = _run_command(compile_command, directory, "compile.log", timeout)
    if compile_result["returncode"] != 0:
        raise PreparationError("compile_error", f"Verilator compile failed in {directory}")
    return compile_result


def _run_trace(
    binary: Path, directory: Path, workload: bytes, timeout: float
) -> dict[str, Any]:
    directory.mkdir(parents=True, exist_ok=False)
    (directory / "workload.in").write_bytes(workload)
    simulation_result = _run_command(
        [str(binary.resolve())], directory, "simulation.log", timeout
    )
    if simulation_result["returncode"] != 0:
        raise PreparationError("simulation_error", f"simulation failed in {directory}")
    output = directory / "output-signals.txt"
    if not output.is_file():
        raise PreparationError("simulation_error", f"missing simulator output in {directory}")
    vcd = directory / "dump.vcd"
    coverage = directory / "coverage.dat"
    if not vcd.is_file() or not coverage.is_file():
        raise PreparationError("simulation_error", f"trace or coverage is missing in {directory}")
    fst = directory / "dump.fst"
    conversion = _run_command(
        ["vcd2fst", "dump.vcd", "dump.fst"], directory, "vcd2fst.log", timeout
    )
    if conversion["returncode"] != 0 or not fst.is_file():
        raise PreparationError("trace_conversion_error", f"vcd2fst failed in {directory}")
    return {"simulation": simulation_result, "conversion": conversion}


def _workload_pool(
    trigger: Path, input_signals: dict[str, int]
) -> list[dict[str, Any]]:
    raw = trigger.read_bytes()
    if not raw or raw.endswith((b"\n", b"\r")) or b"\r" in raw:
        raise VerilogCauseError("trigger must be non-empty LF-separated bytes without a trailing newline")
    rows = raw.split(b"\n")
    widths = tuple(input_signals.values())
    if any(
        len(cells := row.split(b",")) != len(widths)
        or any(not cell or set(cell) - {48, 49} for cell in cells)
        for row in rows
    ):
        raise VerilogCauseError(
            "trigger row does not match metadata input count or binary encoding"
        )
    zero = b",".join(b"0" * width for width in input_signals.values())
    one = b",".join(b"1" * width for width in input_signals.values())
    proposals = (
        ("trigger", raw),
        ("first_vector", rows[0]),
        ("all_zero", b"\n".join([zero] * len(rows))),
        ("all_one", b"\n".join([one] * len(rows))),
    )
    result = []
    seen = set()
    for workload_id, payload in proposals:
        digest = hashlib.sha256(payload).hexdigest()
        if digest in seen:
            continue
        seen.add(digest)
        result.append(
            {"workload_id": workload_id, "payload": payload, "sha256": digest}
        )
    return result


def compare_outputs(correct: Path, faulty: Path) -> dict[str, Any]:
    def read(path: Path) -> tuple[list[str], list[list[str]]]:
        with path.open(encoding="utf-8", newline="") as stream:
            rows = list(csv.reader(stream, skipinitialspace=True))
        if not rows or not rows[0]:
            raise VerilogCauseError(f"empty output table: {path}")
        width = len(rows[0])
        if any(len(row) != width for row in rows[1:]):
            raise VerilogCauseError(f"ragged output table: {path}")
        return [cell.strip() for cell in rows[0]], [
            [cell.strip() for cell in row] for row in rows[1:]
        ]

    correct_header, correct_rows = read(correct)
    faulty_header, faulty_rows = read(faulty)
    if correct_header != faulty_header:
        raise VerilogCauseError("output headers differ")
    for cycle in range(max(len(correct_rows), len(faulty_rows))):
        left = correct_rows[cycle] if cycle < len(correct_rows) else [None] * len(correct_header)
        right = faulty_rows[cycle] if cycle < len(faulty_rows) else [None] * len(faulty_header)
        for column, (expected, observed) in enumerate(zip(left, right)):
            if expected != observed:
                signal = correct_header[column]
                return {
                    "outcome": "failing",
                    "failure_endpoint": {
                        "signal": signal,
                        "cycle": cycle,
                        "time": left[0] if left else None,
                        "correct": expected,
                        "faulty": observed,
                    },
                }
    return {"outcome": "passing", "failure_endpoint": None}


def _output_signals(path: Path) -> list[str]:
    with path.open(encoding="utf-8", newline="") as stream:
        header = next(csv.reader(stream, skipinitialspace=True), [])
    signals = [cell.strip() for cell in header]
    if len(signals) < 2 or signals[0] != "time" or len(signals) != len(set(signals)):
        raise VerilogCauseError(f"invalid output header: {path}")
    return signals[1:]


def _waveform_endpoint_signal(
    waveform: Any, public_signal: str, dut_scope: str = "testbench.DUT"
) -> str:
    def base(name: str) -> str:
        return re.sub(r"\s*\[\d+:\d+\]$", "", name)

    public = [
        row
        for name, row in waveform.signals.by_name.items()
        if base(name) == f"testbench.{public_signal}"
    ]
    if len(public) != 1:
        raise VerilogCauseError(f"public endpoint is not exact: {public_signal}")
    same_name = [
        name
        for name, row in waveform.signals.by_name.items()
        if row.handle == public[0].handle
        and base(name) == f"{dut_scope}.{public_signal}"
    ]
    if len(same_name) == 1:
        return same_name[0]
    aliases = [
        name
        for name, row in waveform.signals.by_name.items()
        if row.handle == public[0].handle
        and name.startswith(f"{dut_scope}.")
        and "." not in name.removeprefix(f"{dut_scope}.")
    ]
    if len(aliases) != 1:
        raise VerilogCauseError(f"DUT endpoint alias is not exact: {public_signal}")
    return aliases[0]


def compare_waveforms(
    correct_fst: Path,
    faulty_fst: Path,
    public_signals: Sequence[str],
    clock_signal: str,
) -> dict[str, Any]:
    from verilog_causal_analysis.cycle_waveform import CycleAlignedWaveform

    try:
        with CycleAlignedWaveform(
            str(correct_fst), clock_signal, exact_clock=True
        ) as correct, CycleAlignedWaveform(
            str(faulty_fst), clock_signal, exact_clock=True
        ) as faulty:
            if correct.get_cycle_count() != faulty.get_cycle_count():
                raise VerilogCauseError(
                    "waveforms have different DUT clock cycle counts"
                )
            scope = clock_signal.rsplit(".", 1)[0]
            signal_pairs = [
                (
                    signal,
                    _waveform_endpoint_signal(correct, signal, scope),
                    _waveform_endpoint_signal(faulty, signal, scope),
                )
                for signal in public_signals
            ]
            endpoints = []
            first_cycle = None
            for cycle in range(correct.get_cycle_count()):
                if first_cycle is not None and cycle > first_cycle + MULTI_ENDPOINT_WINDOW:
                    break
                for public, correct_signal, faulty_signal in signal_pairs:
                    expected = correct.get_signal_value(correct_signal, cycle)
                    observed = faulty.get_signal_value(faulty_signal, cycle)
                    if expected != observed:
                        endpoint = {
                            "signal": public,
                            "cycle": cycle,
                            "correct": expected,
                            "faulty": observed,
                        }
                        if first_cycle is None:
                            first_cycle = cycle
                        if not any(row["signal"] == public for row in endpoints):
                            endpoints.append(endpoint)
                        if len(endpoints) >= MAX_ENDPOINTS_PER_TRACE:
                            break
            if endpoints:
                return {
                    "outcome": "failing",
                    "failure_endpoint": endpoints[0],
                    "failure_endpoints": endpoints,
                    "first_divergence": endpoints[0],
                }
    except ValueError as exc:
        raise VerilogCauseError(str(exc)) from exc
    return {
        "outcome": "passing",
        "failure_endpoint": None,
        "failure_endpoints": [],
        "first_divergence": None,
    }


def read_line_coverage(path: Path, allowed_sources: set[str]) -> list[dict[str, Any]]:
    """Read Verilator rows only when their exact source is in the compile manifest."""

    rows = []
    for raw in path.read_text(encoding="utf-8").splitlines():
        if not raw.startswith("C "):
            continue
        quoted = raw.split("'")
        if len(quoted) < 3:
            raise VerilogCauseError("invalid Verilator coverage row")
        fields = {}
        for part in quoted[1].split("\x01"):
            pair = part.split("\x02", 1)
            if len(pair) == 2:
                fields[pair[0]] = pair[1].strip()
        source = fields.get("f")
        if source not in allowed_sources:
            continue
        try:
            count = int(quoted[2].strip())
        except ValueError as exc:
            raise VerilogCauseError("invalid Verilator coverage count") from exc
        rows.append(
            {
                "source": source,
                "line": int(fields["l"]) if fields.get("l", "").isdigit() else None,
                "count": count,
            }
        )
    return rows


def propose_gold(
    correct: Path, original_faulty: Path, sanitized_faulty: Path, rtl_set_sha256: str
) -> dict[str, Any]:
    correct_lines = correct.read_text(encoding="utf-8").splitlines()
    faulty_lines = original_faulty.read_text(encoding="utf-8").splitlines()
    sanitized_lines = sanitized_faulty.read_text(encoding="utf-8").splitlines()
    hunks = []
    for operation, before_start, before_end, after_start, after_end in difflib.SequenceMatcher(
        None, correct_lines, faulty_lines, autojunk=False
    ).get_opcodes():
        if operation == "equal":
            continue
        hunks.append(
            {
                "operation": operation,
                "correct_line_start": before_start + 1,
                "correct_line_end": before_end,
                "faulty_line_start": after_start + 1,
                "faulty_line_end": after_end,
                "correct_lines": correct_lines[before_start:before_end],
                "faulty_lines": faulty_lines[after_start:after_end],
                "sanitized_faulty_lines": sanitized_lines[after_start:after_end],
            }
        )
    return {
        "schema_version": "verilogcause_gold_proposal.v1",
        "review_status": "pending",
        "reviewer": None,
        "rtl_set_sha256": rtl_set_sha256,
        "correct_sha256": _sha256(correct),
        "original_faulty_sha256": _sha256(original_faulty),
        "sanitized_faulty_sha256": _sha256(sanitized_faulty),
        "diff_hunks": hunks,
        "gold": [],
        "gold_representable": None,
        "decision": "stop_for_codex_review",
    }


def _candidate_universe(
    candidates: dict[str, Any],
) -> dict[tuple[str, str], dict[str, Any]]:
    if candidates.get("schema_version") != "rtl_candidate_universe.v2":
        raise VerilogCauseError("candidate schema is not rtl_candidate_universe.v2")
    if not candidates.get("rtl_set_sha256"):
        raise VerilogCauseError("candidate RTL hash is missing")
    required = {
        "artifact_id",
        "statement_id",
        "line_start",
        "line_end",
        "statement_kind",
        "executable",
        "snippet_sha256",
    }
    universe = {}
    for candidate in candidates.get("candidates", []):
        if not required <= candidate.keys() or candidate["executable"] is not True:
            raise VerilogCauseError("candidate contract is incomplete")
        key = (candidate["artifact_id"], candidate["statement_id"])
        if key in universe:
            raise VerilogCauseError("candidate identity is duplicated")
        universe[key] = candidate
    return universe


def join_reviewed_gold(
    candidates: dict[str, Any],
    review: dict[str, Any],
    *,
    proposal_sha256: str,
    candidates_sha256: str,
) -> tuple[set[tuple[str, str]], bool, str]:
    universe = _candidate_universe(candidates)
    if review.get("schema_version") != "verilogcause_gold_review.v1":
        raise VerilogCauseError("gold review schema is not verilogcause_gold_review.v1")
    if review.get("proposal_sha256") != proposal_sha256:
        raise VerilogCauseError("gold review/proposal hash mismatch")
    if review.get("candidates_sha256") != candidates_sha256:
        raise VerilogCauseError("gold review/candidates hash mismatch")
    if review.get("review_status") != "approved" or review.get("reviewer") != "codex":
        raise VerilogCauseError("gold is not approved by Codex")
    rtl_hash = candidates.get("rtl_set_sha256")
    if not rtl_hash or review.get("rtl_set_sha256") != rtl_hash:
        raise VerilogCauseError("gold/candidate RTL hash mismatch")
    fault_form = review.get("fault_form")
    if fault_form not in {
        "existing_faulty_statement",
        "missing_faulty_statement",
    }:
        raise VerilogCauseError("gold review fault form is missing or unknown")
    if not isinstance(review.get("evidence"), str) or not review["evidence"].strip():
        raise VerilogCauseError("gold review evidence is missing")
    representable = review.get("gold_representable")
    gold = {
        (row.get("artifact_id"), row.get("statement_id")) for row in review.get("gold", [])
    }
    if fault_form == "missing_faulty_statement" and (
        representable is not False or gold
    ):
        raise VerilogCauseError("missing faulty statement must be unrepresentable")
    if representable is True and (not gold or not gold <= universe.keys()):
        raise VerilogCauseError("representable gold does not exactly join candidates")
    if representable is False and gold:
        raise VerilogCauseError("unrepresentable gold must not name a candidate")
    if representable is not True and representable is not False:
        raise VerilogCauseError("gold representability is undecided")
    return gold, representable, fault_form


def _rtl_set_hash(payload: dict[str, Any]) -> str | None:
    return payload.get("rtl_set_sha256") or (payload.get("identity") or {}).get(
        "rtl_set_sha256"
    )


def resolve_rtl_evidence(
    candidates: dict[str, Any], graph: dict[str, Any]
) -> dict[tuple[str, str], list[dict[str, Any]]]:
    if _rtl_set_hash(candidates) != _rtl_set_hash(graph):
        raise VerilogCauseError("candidate/graph RTL hash mismatch")
    if any(
        "source_provenance"
        in str(
            node.get("type", node.get("kind", node.get("semantic_kind", "")))
        ).lower()
        for node in graph.get("semantic_nodes", [])
    ):
        raise VerilogCauseError("native graph contains Chisel source provenance")
    universe = _candidate_universe(candidates)
    result: dict[tuple[str, str], list[dict[str, Any]]] = defaultdict(list)
    semantic_nodes = {
        node.get("semantic_id"): node
        for node in graph.get("semantic_nodes", [])
        if node.get("semantic_id")
    }
    signal_nodes = {
        node.get("node_id"): node
        for node in graph.get("signal_nodes", [])
        if node.get("node_id")
    }
    seen_edges = set()
    for edge in graph.get("edges", []):
        relation = edge.get("relation")
        if relation in {"active_statement_write", "active_guard"}:
            required = (
                "artifact_id",
                "statement_id",
                "target_node_id",
                "cycle",
                "activation_status",
            )
            if any(edge.get(field) is None for field in required):
                raise VerilogCauseError("statement activation relation is incomplete")
            if edge.get("dst_node_id") != edge["target_node_id"]:
                raise VerilogCauseError("statement activation target identity drift")
            target = signal_nodes.get(edge["target_node_id"])
            if target is None or target.get("cycle") != edge["cycle"]:
                raise VerilogCauseError("statement activation target cycle drift")
            semantic = semantic_nodes.get(edge.get("src_semantic_id"))
            if semantic is None or semantic.get("type") != "rtl_statement_activation":
                raise VerilogCauseError("statement activation semantic node is missing")
            if edge["activation_status"] not in {
                "active_exact",
                "ambiguous",
                "unavailable",
            }:
                raise VerilogCauseError("unknown statement activation status")
            key = (edge["artifact_id"], edge["statement_id"])
            if key not in universe:
                raise VerilogCauseError(f"graph references unknown candidate: {key}")
            for field in (
                "artifact_id",
                "statement_id",
                "target_node_id",
                "cycle",
                "activation_status",
            ):
                if semantic.get(field) != edge[field]:
                    raise VerilogCauseError("statement activation semantic identity drift")
            edge_id = edge.get("edge_id")
            if not edge_id or edge_id in seen_edges:
                raise VerilogCauseError("graph edge identity is missing or duplicated")
            seen_edges.add(edge_id)
            result[key].append(edge)
            continue
        evidence = edge.get("rtl_evidence") or {}
        if not evidence.get("statement_id"):
            continue
        key = (evidence.get("artifact_id"), evidence.get("statement_id"))
        candidate = universe.get(key)
        if candidate is None:
            raise VerilogCauseError(f"graph references unknown candidate: {key}")
        for field in ("line_start", "line_end", "snippet_sha256"):
            if evidence.get(field) != candidate.get(field):
                raise VerilogCauseError(f"RTL evidence {field} drift for {key}")
        edge_id = edge.get("edge_id")
        if not edge_id or edge_id in seen_edges:
            raise VerilogCauseError("graph edge identity is missing or duplicated")
        seen_edges.add(edge_id)
        result[key].append(edge)
    return dict(result)


def set_valued_metrics(
    scores: dict[tuple[str, str], float],
    gold: set[tuple[str, str]],
    *,
    representable: bool,
) -> dict[str, Any]:
    if not representable or not gold or not gold <= scores.keys():
        return {
            "gold_rank": None,
            "tie_size": None,
            "mrr": 0.0,
            "exam_percent": 100.0,
            **{f"top_{limit}": False for limit in (1, 3, 5, 10)},
        }
    gold_score = max(scores[key] for key in gold)
    tied = sum(score == gold_score for score in scores.values())
    rank = 1 + sum(score > gold_score for score in scores.values()) + (tied - 1) / 2
    return {
        "gold_rank": rank,
        "tie_size": tied,
        "mrr": round(1 / rank, 6),
        "exam_percent": round(100 * rank / len(scores), 6),
        **{f"top_{limit}": rank <= limit for limit in (1, 3, 5, 10)},
    }


def aggregate_features(
    candidates: dict[str, Any],
    failing_traces: Sequence[dict[str, Any]],
    passing_traces: Sequence[dict[str, Any]],
) -> dict[tuple[str, str], dict[str, Any]]:
    if not failing_traces:
        raise VerilogCauseError("feature aggregation requires a failing trace")
    keys = [
        (row["artifact_id"], row["statement_id"])
        for row in candidates.get("candidates", [])
    ]
    result = {}
    resolved = [resolve_rtl_evidence(candidates, trace["graph"]) for trace in failing_traces]
    for key in keys:
        fail_rate = fmean(key in set(trace.get("executed_candidates", [])) for trace in failing_traces)
        pass_rate = (
            fmean(key in set(trace.get("executed_candidates", [])) for trace in passing_traces)
            if passing_traces
            else None
        )
        present = []
        support_values = []
        proximity = []
        offsets = []
        usable_count = mapped_count = sequential_count = 0
        per_trace_coverage = []
        unavailable = []
        for trace, mapping in zip(failing_traces, resolved):
            nodes = {row["node_id"]: row.get("cycle") for row in trace["graph"].get("signal_nodes", [])}
            failure_cycle = trace["failure_cycle"]
            mapped = mapping.get(key, [])
            activation = [
                edge
                for edge in mapped
                if edge.get("relation")
                in {"active_statement_write", "active_guard"}
            ]
            active_exact = [
                edge
                for edge in activation
                if edge.get("activation_status") == "active_exact"
                and isinstance(edge.get("cycle"), int)
                and edge["cycle"] <= failure_cycle
            ]
            mapped_count += len(activation)
            usable = []
            for edge in mapped:
                if edge.get("relation") in {
                    "active_statement_write",
                    "active_guard",
                }:
                    if edge.get("activation_status") != "active_exact":
                        unavailable.append(
                            f"activation_{edge.get('activation_status', 'unavailable')}"
                        )
                    continue
                contribution = edge.get("contribution_evidence") or {}
                score = contribution.get("score", edge.get("contribution_score"))
                cycle = nodes.get(edge.get("dst_node_id"))
                if not isinstance(score, (int, float)) or not math.isfinite(score) or not isinstance(cycle, int):
                    unavailable.append("relation_unavailable")
                    continue
                if cycle > failure_cycle:
                    continue
                usable.append((edge, float(score), cycle))
            usable_count += len(usable)
            sequential_count += sum(edge.get("dependency_type") == "sequential" for edge, _, _ in usable)
            per_trace_coverage.append(1.0 if active_exact else 0.0)
            present.append(bool(active_exact))
            supported = [
                (edge, score, cycle)
                for edge, score, cycle in usable
                if (edge.get("contribution_evidence") or {}).get("status")
                == "supported"
                and score > 0
            ]
            support_values.extend(score for _, score, _ in supported)
            proximity.append(
                max(
                    (1 / (1 + failure_cycle - cycle) for _, _, cycle in supported),
                    default=0.0,
                )
            )
            if supported:
                offsets.append(min(failure_cycle - cycle for _, _, cycle in supported))
        stability = 1 / (1 + pstdev(offsets)) if len(offsets) >= 2 else 0.0
        result[key] = {
            "relation_applicable": mapped_count > 0,
            "unavailable_reasons": sorted(set(unavailable)),
            "features": {
                "fail_execution_rate": fail_rate,
                "pass_execution_rate": pass_rate,
                "execution_specificity": fail_rate - pass_rate if pass_rate is not None else None,
                "causal_trace_coverage": fmean(present),
                "causal_support_mean": fmean(support_values) if support_values else 0.0,
                "causal_support_max": max(support_values, default=0.0),
                "near_failure_mean": fmean(proximity),
                "temporal_stability": stability,
                "sequential_ratio": sequential_count / usable_count if usable_count else 0.0,
                "relation_coverage": fmean(per_trace_coverage),
            },
            "contrast_status": "complete" if passing_traces else "contrast_incomplete",
            "mapped_edge_count": mapped_count,
            "usable_edge_count": usable_count,
        }
    return result


def trainer_visible_sample(
    bug_id: str, candidate: dict[str, Any], aggregate: dict[str, Any]
) -> dict[str, Any]:
    features = aggregate["features"]
    if tuple(features) != FEATURES or any(value is None for value in features.values()):
        raise VerilogCauseError("trainer sample has incomplete or unfrozen features")
    return {
        "bug_id": bug_id,
        "artifact_id": candidate["artifact_id"],
        "statement_id": candidate["statement_id"],
        "line_start": candidate["line_start"],
        "line_end": candidate["line_end"],
        "relation_applicable": aggregate["relation_applicable"],
        "unavailable_reasons": aggregate["unavailable_reasons"],
        "features": features,
    }


def relation_gate(case_rows: Sequence[dict[str, Any]]) -> dict[str, Any]:
    failures = []
    for row in case_rows:
        case_id = row.get("case_id", "<unknown>")
        if row.get("status") != "complete":
            failures.append(f"{case_id}:data_incomplete")
        if row.get("gold_representable") is None:
            failures.append(f"{case_id}:gold_unreviewed")
        if row.get("fault_form") not in {
            "existing_faulty_statement",
            "missing_faulty_statement",
        }:
            failures.append(f"{case_id}:fault_form_unreviewed")
        if (
            row.get("fault_form") == "existing_faulty_statement"
            and row.get("gold_representable") is False
        ):
            failures.append(f"{case_id}:existing_candidate_unrepresentable")
        if not row.get("has_failing_trace") or not row.get("has_passing_trace"):
            failures.append(f"{case_id}:contrast_incomplete")
        if not row.get("graph_complete"):
            failures.append(f"{case_id}:graph_incomplete")
        if row.get("activation_diagnostic_count"):
            failures.append(f"{case_id}:activation_not_exact")
    return {
        "decision": "failed_stop" if failures else "continue_to_baselines",
        "failures": failures,
    }


def _pairwise_summary(rows: Sequence[dict[str, Any]]) -> dict[str, Any]:
    counts: dict[str, dict[str, int]] = defaultdict(
        lambda: {"win": 0, "tie": 0, "loss": 0}
    )
    by_bug: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for row in rows:
        by_bug[row["bug_id"]].append(row)
    for bug_rows in by_bug.values():
        positives = [row for row in bug_rows if row["is_gold"]]
        negatives = [row for row in bug_rows if not row["is_gold"]]
        for positive in positives:
            for negative in negatives:
                outcome = (
                    "win"
                    if positive["score"] > negative["score"]
                    else "loss"
                    if positive["score"] < negative["score"]
                    else "tie"
                )
                keys = (
                    "overall",
                    f"design:{positive['design_id']}",
                    f"family:{positive['family_id']}",
                )
                for key in keys:
                    counts[key][outcome] += 1

    def summarize(key: str) -> dict[str, Any]:
        row = counts[key]
        total = sum(row.values())
        return {
            **row,
            "comparable_pairs": total,
            "win_rate": row["win"] / total if total else None,
        }

    designs = sorted(
        key.removeprefix("design:") for key in counts if key.startswith("design:")
    )
    families = sorted(
        key.removeprefix("family:") for key in counts if key.startswith("family:")
    )
    by_design = {design: summarize(f"design:{design}") for design in designs}
    by_family = {family: summarize(f"family:{family}") for family in families}
    return {
        "denominator_scope": "representable_gold_only",
        "evaluated_case_count": sum(
            any(row["is_gold"] for row in bug_rows) for bug_rows in by_bug.values()
        ),
        "overall": summarize("overall"),
        "by_design": by_design,
        "by_family": by_family,
        "design_macro_win_rate": (
            fmean(row["win_rate"] for row in by_design.values())
            if by_design
            and all(row["win_rate"] is not None for row in by_design.values())
            else None
        ),
        "family_macro_win_rate": (
            fmean(row["win_rate"] for row in by_family.values())
            if by_family
            and all(row["win_rate"] is not None for row in by_family.values())
            else None
        ),
    }


def run_after_gate(report: dict[str, Any], action: Callable[[], Any]) -> Any:
    if report.get("decision") != "continue_to_baselines":
        raise VerilogCauseError("relation gate is not open")
    return action()


def _validate_manifest_scope(
    contract: dict[str, Any], rows: Sequence[dict[str, Any]]
) -> list[dict[str, Any]]:
    inventory = contract["corpus_inventory"]
    cases = [row for row in rows if row.get("record_type") == "case"]
    if [row.get("case_id") for row in cases] != inventory["case_ids"]:
        raise VerilogCauseError("manifest case inventory drift")
    expected = {row["case_id"]: row for row in inventory["rows"]}
    for case in cases:
        frozen = expected[case["case_id"]]
        if (case.get("design_id"), case.get("family_id")) != (
            frozen["design_id"],
            frozen["family_id"],
        ):
            raise VerilogCauseError(f"{case['case_id']}: manifest taxonomy drift")
        inputs = case.get("oracle_only_inputs") or {}
        if inputs and any(
            (inputs.get(manifest_field) or {}).get("sha256")
            != frozen[inventory_field]["sha256"]
            for manifest_field, inventory_field in (
                ("metadata", "metadata"),
                ("correct_design", "correct_design"),
                ("original_faulty", "faulty_design"),
            )
        ):
            raise VerilogCauseError(f"{case['case_id']}: manifest input hash drift")
    if any(
        row.get("record_type") == "trace" and row.get("case_id") not in expected
        for row in rows
    ):
        raise VerilogCauseError("manifest trace inventory drift")
    return cases


def _artifact(path: Path, run: Path) -> dict[str, Any]:
    resolved = path.resolve()
    try:
        recorded_path = str(resolved.relative_to(run))
    except ValueError:
        recorded_path = str(resolved)
    return {
        "path": recorded_path,
        "sha256": _sha256(path),
        "bytes": path.stat().st_size,
    }


def _prepare_case(case: dict[str, Any], run: Path, timeout: float) -> list[dict[str, Any]]:
    try:
        clock_signal = DESIGN_DUT_CLOCKS[case["family"]]
    except KeyError as exc:
        raise VerilogCauseError(f"unfrozen design clock: {case['family']}") from exc
    case_dir = run / "cases" / case["case_id"]
    private = case_dir / "evaluator_private"
    model = case_dir / "model_inputs"
    correct_build = private / "build_correct"
    original_build = private / "build_original_faulty"
    sanitized_build = model / "build_sanitized_faulty"
    correct_sources = _compile_sources(case, "correct_design")
    faulty_sources = _compile_sources(case, "buggy_design")
    _copy_compile_inputs(
        correct_build, correct_sources, case["testbench"], sanitize=False
    )
    _copy_compile_inputs(
        original_build, faulty_sources, case["testbench"], sanitize=False
    )
    sanitized_manifest = _copy_compile_inputs(
        sanitized_build, faulty_sources, case["testbench"], sanitize=True
    )
    workload_dir = case_dir / "workloads"
    workload_dir.mkdir()
    workloads = _workload_pool(case["bug_trigger_input"], case["input_signals"])
    for workload in workloads:
        path = workload_dir / f"{workload['workload_id']}.in"
        path.write_bytes(workload["payload"])
        workload["path"] = path
    workload_records = [
        {
            "workload_id": row["workload_id"],
            "sha256": row["sha256"],
            "artifact": _artifact(row["path"], run),
        }
        for row in workloads
    ]
    workload_pool_sha256 = _canonical_sha256(
        [
            {"workload_id": row["workload_id"], "sha256": row["sha256"]}
            for row in workloads
        ]
    )
    workload_pool_path = model / "workload_pool.json"
    _write_json(
        workload_pool_path,
        {
            "schema_version": "verilogcause_workload_pool.v1",
            "generator": "trigger_first_zero_one_sha256_deduplicated",
            "input_widths": case["input_signals"],
            "workload_pool_sha256": workload_pool_sha256,
            "workloads": workload_records,
        },
    )
    rtl_set_sha256 = _canonical_sha256(
        [
            {
                "artifact_id": row["artifact_id"],
                "sha256": row["sha256"],
                "bytes": row["bytes"],
            }
            for row in sanitized_manifest
        ]
    )
    rtl_manifest_path = model / "rtl_manifest.json"
    _write_json(
        rtl_manifest_path,
        {
            "schema_version": "verilogcause_rtl_manifest.v1",
            "rtl_set_sha256": rtl_set_sha256,
            "sources": sanitized_manifest,
        },
    )
    gold_path = private / "gold_proposal.json"
    _write_json(
        gold_path,
        propose_gold(
            case["correct_design"],
            case["buggy_design"],
            sanitized_build / sanitized_manifest[0]["compile_name"],
            rtl_set_sha256,
        ),
    )
    compile_commands = {
        "correct": _compile(correct_build, timeout),
        "original_faulty": _compile(original_build, timeout),
        "sanitized_faulty": _compile(sanitized_build, timeout),
    }
    allowed_sources = {row["compile_name"] for row in sanitized_manifest}
    trace_rows = []
    run_commands: dict[str, dict[str, Any]] = {}
    for workload in workloads:
        workload_id = workload["workload_id"]
        directories = {
            "correct": private / "traces" / "correct" / workload_id,
            "original_faulty": private / "traces" / "original_faulty" / workload_id,
            "sanitized_faulty": model / "traces" / workload_id,
        }
        commands = {
            "correct": _run_trace(
                correct_build / "obj_dir" / "Vtestbench",
                directories["correct"],
                workload["payload"],
                timeout,
            ),
            "original_faulty": _run_trace(
                original_build / "obj_dir" / "Vtestbench",
                directories["original_faulty"],
                workload["payload"],
                timeout,
            ),
            "sanitized_faulty": _run_trace(
                sanitized_build / "obj_dir" / "Vtestbench",
                directories["sanitized_faulty"],
                workload["payload"],
                timeout,
            ),
        }
        run_commands[workload_id] = commands
        outputs = {
            name: directory / "output-signals.txt"
            for name, directory in directories.items()
        }
        public_signals = _output_signals(outputs["correct"])
        if any(
            _output_signals(outputs[name]) != public_signals
            for name in ("original_faulty", "sanitized_faulty")
        ):
            raise PreparationError("simulation_error", "output headers differ")
        fsts = {name: directory / "dump.fst" for name, directory in directories.items()}
        equivalent = compare_waveforms(
            fsts["original_faulty"],
            fsts["sanitized_faulty"],
            public_signals,
            clock_signal,
        )
        if equivalent["outcome"] != "passing":
            raise PreparationError(
                "sanitizer_mismatch", "sanitizer changed cycle-end external output"
            )
        comparison = compare_waveforms(
            fsts["correct"],
            fsts["sanitized_faulty"],
            public_signals,
            clock_signal,
        )
        sanitized_dir = directories["sanitized_faulty"]
        coverage_json = sanitized_dir / "line_coverage.json"
        _write_json(
            coverage_json,
            {
                "schema_version": "verilogcause_line_coverage.v1",
                "allowed_sources": sorted(allowed_sources),
                "rows": read_line_coverage(
                    sanitized_dir / "coverage.dat", allowed_sources
                ),
            },
        )
        oracle = {
            "endpoint_sampling": _ENDPOINT_SAMPLING,
            "clock": {"signal": clock_signal, "edge": "rising"},
            "correct_fst_sha256": _sha256(fsts["correct"]),
            "faulty_fst_sha256": _sha256(fsts["sanitized_faulty"]),
            "first_divergence": comparison["first_divergence"],
        }
        trace_rows.append(
            {
                "record_type": "trace",
                "schema_version": "verilogcause_manifest.v1",
                "case_id": case["case_id"],
                "workload_id": workload_id,
                "trace_id": _canonical_sha256(
                    {
                        "case_id": case["case_id"],
                        "workload_sha256": workload["sha256"],
                        "rtl_set_sha256": rtl_set_sha256,
                        "simulator": "verilator",
                    }
                ),
                "status": "complete",
                **comparison,
                "oracle": oracle,
                "workload": _artifact(workload["path"], run),
                "correct_output_sha256": _sha256(outputs["correct"]),
                "faulty_output_sha256": _sha256(outputs["sanitized_faulty"]),
                "oracle_only_traces": {
                    "correct_fst": _artifact(fsts["correct"], run),
                    "original_faulty_fst": _artifact(fsts["original_faulty"], run),
                },
                "sanitizer_equivalence": {
                    "endpoint_sampling": _ENDPOINT_SAMPLING,
                    "clock": {"signal": clock_signal, "edge": "rising"},
                    "original_faulty_fst_sha256": _sha256(fsts["original_faulty"]),
                    "sanitized_faulty_fst_sha256": _sha256(
                        fsts["sanitized_faulty"]
                    ),
                    "equivalent": True,
                },
                "coverage": _artifact(coverage_json, run),
                "vcd": _artifact(sanitized_dir / "dump.vcd", run),
                "fst": _artifact(fsts["sanitized_faulty"], run),
                "simulation_command": commands["sanitized_faulty"]["simulation"],
                "vca_graph": None,
            }
        )
    has_failing = any(row["outcome"] == "failing" for row in trace_rows)
    has_passing = any(row["outcome"] == "passing" for row in trace_rows)
    contrast_status = (
        "contrast_complete" if has_failing and has_passing else "contrast_incomplete"
    )
    for row in trace_rows:
        row["contrast_status"] = contrast_status
    case_row = {
        "record_type": "case",
        "schema_version": "verilogcause_manifest.v1",
        "case_id": case["case_id"],
        "design_id": case["family"],
        "family_id": "sequential" if case["sequential"] else "combinational",
        "sequential": case["sequential"],
        "status": (
            "gold_review_pending"
            if contrast_status == "contrast_complete"
            else "contrast_incomplete"
        ),
        "contrast_status": contrast_status,
        "rtl_set_sha256": rtl_set_sha256,
        "gold_review_status": "pending",
        "gold_representable": None,
        "oracle_only_inputs": {
            "metadata": _artifact(case["metadata"], run),
            "correct_design": _artifact(case["correct_design"], run),
            "original_faulty": _artifact(case["buggy_design"], run),
            "gold_proposal": _artifact(gold_path, run),
        },
        "model_inputs": {
            "rtl_manifest": _artifact(rtl_manifest_path, run),
            "workload_pool": _artifact(workload_pool_path, run),
        },
        "workload_pool_sha256": workload_pool_sha256,
        "simulation": {
            "compile_once": compile_commands,
            "workloads": run_commands,
            "sanitizer_output_equivalent": True,
        },
    }
    return [case_row, *trace_rows]


def prepare(run: Path, corpus: Path, families: Sequence[str], timeout: float) -> Path:
    run = run.resolve()
    if run.exists():
        raise VerilogCauseError(f"run already exists: {run}")
    discovered = discover_cases(corpus, families)
    cases = [case for case in discovered if case["case_id"] not in EXCLUDED_CASES]
    inventory = _inventory_contract(cases, corpus)
    if (
        inventory["case_count"] != 38
        or inventory["design_count"] != 10
        or inventory["family_ids"] != ["combinational", "sequential"]
        or inventory["inventory_sha256"] != VERILOG_38_INVENTORY_SHA256
    ):
        raise VerilogCauseError("selected corpus is not the native Verilog 38 inventory")
    parsers = _hdl_parser_versions()
    if parsers != HDL_PARSER_VERSIONS:
        raise VerilogCauseError(f"unexpected HDL parser versions: {parsers}")
    run.mkdir(parents=True, exist_ok=False)
    manifest_path = run / "manifest.jsonl"
    manifest_path.touch()
    try:
        version = subprocess.run(
            ["verilator", "--version"], capture_output=True, text=True, timeout=10, check=True
        ).stdout.strip()
    except (OSError, subprocess.SubprocessError) as exc:
        raise VerilogCauseError("Verilator is unavailable") from exc
    _write_json(
        run / "dataset_contract.json",
        {
            "schema_version": "verilogcause_dataset_contract.v1",
            "implementation_sha256": _sha256(Path(__file__)),
            "experiment_scope": "native_verilog_38",
            "corpus_root": str(corpus.resolve()),
            "corpus_inventory": inventory,
            "implementation_identity": {
                "parent_commit": _git_head(Path.cwd()),
                **_vca_implementation_identity(),
            },
            "hdl_parser_versions": parsers,
            "simulator": {"name": "verilator", "version": version, "flags": list(VERILATOR_FLAGS)},
            "trace_generation": {
                "seed": 0,
                "budget_per_case": 4,
                "source": "gold_blind_trigger_first_zero_one",
                "deduplication": "sha256",
                "endpoint_sampling": _ENDPOINT_SAMPLING,
                "design_clock_map": DESIGN_DUT_CLOCKS,
            },
            "sanitized_rtl_policy": "strip_all_comments_preserve_noncomment_bytes_and_newlines",
            "gold_review_policy": "proposal_then_codex_review",
            "candidate_schema": "rtl_candidate_universe.v2",
            "feature_schema": list(FEATURES),
            "split_policy": ["lobo", "lodo", "lofo"],
            "design_family_policy": "sequential_flag_binary",
            "design_clock_map": DESIGN_DUT_CLOCKS,
            "tie_policy": "average_rank",
            "unreachable_policy": {"mrr": 0, "exam_percent": 100},
            "w3_baselines": list(W3_BASELINES),
            "w4_learned_methods": list(W4_LEARNED_METHODS),
            "excluded_cases": EXCLUDED_CASES,
            "training": {
                "epochs": W4_EPOCHS,
                "pair_normalization": "bug_then_positive_negative_pair",
                "splits": list(W4_SPLITS),
                "primary": "lofo_family_macro",
            },
            "oracle_only_input_policy": ["correct_design", "original_faulty", "source_diff", "gold"],
            "completion_boundary": "stop_for_codex_gold_review",
        },
    )
    for case in cases:
        try:
            rows = _prepare_case(case, run, timeout)
        except (PreparationError, VerilogCauseError) as exc:
            _append_jsonl(
                manifest_path,
                {
                    "record_type": "case",
                    "schema_version": "verilogcause_manifest.v1",
                    "case_id": case["case_id"],
                    "design_id": case["family"],
                    "family_id": (
                        "sequential" if case["sequential"] else "combinational"
                    ),
                    "sequential": case["sequential"],
                    "status": getattr(exc, "status", "preparation_error"),
                    "contrast_status": "contrast_incomplete",
                    "reason": str(exc),
                    "gold_review_status": "pending",
                },
            )
            continue
        for row in rows:
            _append_jsonl(manifest_path, row)
    return run


def _vca_request(
    run: Path,
    trace: dict[str, Any],
    rtl: dict[str, Any],
    design_id: str,
    endpoint: dict[str, Any] | None = None,
):
    from verilog_causal_analysis import make_request, policy_identity
    from verilog_causal_analysis.cycle_waveform import CycleAlignedWaveform

    fst = run / trace["fst"]["path"]
    oracle = trace.get("oracle") or {}
    clock = oracle.get("clock") or {}
    clock_signal = clock.get("signal")
    if (
        _sha256(fst) != trace["fst"]["sha256"]
        or
        oracle.get("endpoint_sampling") != _ENDPOINT_SAMPLING
        or clock.get("edge") != "rising"
        or clock_signal != DESIGN_DUT_CLOCKS.get(design_id)
        or oracle.get("faulty_fst_sha256") != trace["fst"]["sha256"]
    ):
        raise VerilogCauseError("trace endpoint sampling contract is incomplete")
    endpoint = endpoint or trace["failure_endpoint"]
    with CycleAlignedWaveform(str(fst), clock_signal, exact_clock=True) as waveform:
        endpoint_signal = _waveform_endpoint_signal(
            waveform,
            endpoint["signal"],
            clock_signal.rsplit(".", 1)[0],
        )
        if waveform.get_signal_value(
            endpoint_signal, endpoint["cycle"]
        ) != endpoint["faulty"]:
            raise VerilogCauseError("VCA endpoint differs from faulty oracle value")
    return make_request(
        trace={
            "artifact_id": f"trace_{trace['trace_id']}",
            "path": str(fst.resolve()),
            "format": "fst",
            "sha256": trace["fst"]["sha256"],
            "bytes": trace["fst"]["bytes"],
        },
        rtl_files=[
            {
                key: source[key]
                for key in ("artifact_id", "path", "sha256", "bytes")
            }
            for source in rtl["sources"]
        ],
        semantic_profile={
            "name": "verilog",
            "version": "verilog-semantic-profile",
            "features": [
                "instance_graph",
                "register_transition",
                "temporal_interval",
            ],
        },
        clock={"signal": clock_signal, "edge": "rising"},
        endpoint={
            "signal": endpoint_signal,
            "cycle": endpoint["cycle"],
            "projection": None,
        },
        semantic_inputs=[],
        search_policy=policy_identity().to_dict(),
        bounds=VCA_BOUNDS,
        random_seed=0,
        strict=True,
    )


def build_candidates(run: Path) -> None:
    contract = validate_run_contract(run)
    from verilog_causal_analysis import build_rtl_candidates

    rows = _read_jsonl(run / "manifest.jsonl")
    _validate_manifest_scope(contract, rows)
    incomplete = [
        row["case_id"]
        for row in rows
        if row["record_type"] == "case"
        and row.get("contrast_status") != "contrast_complete"
    ]
    if incomplete:
        raise VerilogCauseError(f"contrast pool is incomplete: {sorted(incomplete)}")
    traces: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for trace in (row for row in rows if row["record_type"] == "trace"):
        traces[trace["case_id"]].append(trace)
    for row in rows:
        if row["record_type"] != "case" or row["status"] != "gold_review_pending":
            continue
        case_id = row["case_id"]
        failing = sorted(
            (trace for trace in traces[case_id] if trace["outcome"] == "failing"),
            key=lambda trace: (trace["workload_id"], trace["trace_id"]),
        )
        if not failing:
            raise VerilogCauseError(f"no failing trace for candidates: {case_id}")
        model = run / "cases" / case_id / "model_inputs"
        rtl = json.loads((model / "rtl_manifest.json").read_text(encoding="utf-8"))
        request = _vca_request(run, failing[0], rtl, row["design_id"])
        candidates = build_rtl_candidates(request)
        if candidates.get("schema_version") != "rtl_candidate_universe.v2":
            raise VerilogCauseError("VCA did not produce rtl_candidate_universe.v2")
        path = model / "rtl_candidates.json"
        _write_json(path, candidates)
        row["candidate_universe"] = _artifact(path, run)
        row["candidates_implementation_sha256"] = contract["implementation_sha256"]
        row["vca_request_sha256"] = request.request_sha256
        row["candidate_trace_id"] = failing[0]["trace_id"]
        failing[0]["vca_endpoint_signal"] = request.endpoint.signal
    _write_jsonl(run / "manifest.jsonl", rows)


def pilot(run: Path) -> dict[str, Any]:
    contract = validate_run_contract(run)
    from verilog_causal_analysis import build_causal_graph

    rows = _read_jsonl(run / "manifest.jsonl")
    _validate_manifest_scope(contract, rows)
    traces: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for trace in (row for row in rows if row["record_type"] == "trace"):
        traces[trace["case_id"]].append(trace)
    samples = []
    labels = []
    diagnostic_rows = []
    for case in (row for row in rows if row["record_type"] == "case"):
        case["pilot_implementation_sha256"] = contract["implementation_sha256"]
        case_id = case["case_id"]
        case_traces = traces[case_id]
        if case.get("contrast_status") != "contrast_complete":
            diagnostic_rows.append(
                {
                    "case_id": case_id,
                    "design_id": case["design_id"],
                    "family_id": case["family_id"],
                    "status": "contrast_incomplete",
                    "fault_form": None,
                    "gold_representable": None,
                    "gold_reachable": False,
                    "has_failing_trace": any(
                        trace["outcome"] == "failing" for trace in case_traces
                    ),
                    "has_passing_trace": any(
                        trace["outcome"] == "passing" for trace in case_traces
                    ),
                    "graph_complete": False,
                    "activation_diagnostic_count": 0,
                }
            )
            continue
        root = run / "cases" / case_id
        model = root / "model_inputs"
        private = root / "evaluator_private"
        rtl = json.loads((model / "rtl_manifest.json").read_text(encoding="utf-8"))
        candidates_path = model / "rtl_candidates.json"
        candidates = json.loads(candidates_path.read_text(encoding="utf-8"))
        review_path = private / "gold_review.json"
        if not review_path.is_file():
            raise VerilogCauseError(f"missing Codex gold review: {case_id}")
        review = json.loads(review_path.read_text(encoding="utf-8"))
        gold, representable, fault_form = join_reviewed_gold(
            candidates,
            review,
            proposal_sha256=case["oracle_only_inputs"]["gold_proposal"]["sha256"],
            candidates_sha256=_sha256(candidates_path),
        )
        failing = []
        passing = []
        activation_diagnostics = []
        graph_dir = model / "causal_graphs"
        graph_dir.mkdir()
        for trace in case_traces:
            coverage = json.loads(
                (run / trace["coverage"]["path"]).read_text(encoding="utf-8")
            )
            hit_lines = {
                (source["artifact_id"], coverage_row["line"])
                for source in rtl["sources"]
                for coverage_row in coverage["rows"]
                if coverage_row["source"] == source["compile_name"]
                and coverage_row["count"] > 0
            }
            executed = [
                (candidate["artifact_id"], candidate["statement_id"])
                for candidate in candidates["candidates"]
                if (candidate["artifact_id"], candidate["line_start"]) in hit_lines
            ]
            if trace["outcome"] == "passing":
                passing.append({"executed_candidates": executed})
                continue
            request = _vca_request(run, trace, rtl, case["design_id"])
            graph = build_causal_graph(request)
            graph_path = graph_dir / f"{trace['trace_id']}.json"
            _write_json(graph_path, graph)
            trace["vca_graph"] = _artifact(graph_path, run)
            trace["vca_endpoint_signal"] = request.endpoint.signal
            bad_diagnostics = [
                row
                for row in graph.get("diagnostics", [])
                if "activation" in str(row.get("code", ""))
                and any(
                    word in str(row).lower()
                    for word in ("ambiguous", "unavailable")
                )
            ]
            bad_diagnostics.extend(
                edge
                for edge in graph.get("edges", [])
                if edge.get("relation")
                in {"active_statement_write", "active_guard"}
                and edge.get("activation_status") in {"ambiguous", "unavailable"}
            )
            activation_diagnostics.extend(bad_diagnostics)
            failing.append(
                {
                    "graph": graph,
                    "failure_cycle": trace["failure_endpoint"]["cycle"],
                    "executed_candidates": executed,
                }
            )
        aggregate = aggregate_features(
            candidates,
            failing,
            passing,
        )
        mapped_gold = {
            key
            for key in gold
            if aggregate[key]["features"]["causal_trace_coverage"] > 0
        }
        end_to_end_metrics = set_valued_metrics(
            {
                key: item["features"]["causal_trace_coverage"]
                for key, item in aggregate.items()
            },
            gold,
            representable=representable,
        )
        for candidate in candidates["candidates"]:
            key = (candidate["artifact_id"], candidate["statement_id"])
            item = aggregate[key]
            samples.append(
                {
                    "bug_id": case_id,
                    **{field: candidate[field] for field in (
                        "artifact_id",
                        "statement_id",
                        "line_start",
                        "line_end",
                    )},
                    "relation_applicable": item["relation_applicable"],
                    "unavailable_reasons": item["unavailable_reasons"],
                    "features": item["features"],
                    "contrast_status": item["contrast_status"],
                }
            )
            labels.append({
                "bug_id": case_id,
                "artifact_id": key[0],
                "statement_id": key[1],
                "is_gold": key in gold,
            })

        reachable = bool(mapped_gold)
        case.update(
            {
                "status": "complete",
                "gold_review_status": "approved",
                "fault_form": fault_form,
                "gold_representable": representable,
                "gold_reachable": reachable,
                "gold_review": _artifact(review_path, run),
                "candidate_universe": _artifact(candidates_path, run),
            }
        )
        diagnostic_rows.append(
            {
                "case_id": case_id,
                "design_id": case["design_id"],
                "family_id": case["family_id"],
                "status": "complete",
                "fault_form": fault_form,
                "gold_representable": representable,
                "gold_reachable": reachable,
                "end_to_end_metrics": end_to_end_metrics,
                "has_failing_trace": bool(failing),
                "has_passing_trace": bool(passing),
                "graph_complete": all(
                    trace["graph"]["status"] == "complete" for trace in failing
                ),
                "activation_diagnostic_count": len(activation_diagnostics),
            }
        )

    samples_path = run / "samples.jsonl"
    labels_path = run / "evaluator_labels.jsonl"
    _write_jsonl(run / "manifest.jsonl", rows)
    _write_jsonl(samples_path, samples)
    _write_jsonl(labels_path, labels)
    label_by_key = {
        (row["bug_id"], row["artifact_id"], row["statement_id"]): row["is_gold"]
        for row in labels
    }
    case_by_id = {
        row["case_id"]: row for row in rows if row["record_type"] == "case"
    }
    pairwise = _pairwise_summary(
        [
            {
                "bug_id": row["bug_id"],
                "design_id": case_by_id[row["bug_id"]]["design_id"],
                "family_id": case_by_id[row["bug_id"]]["family_id"],
                "score": row["features"]["causal_trace_coverage"],
                "is_gold": label_by_key[
                    (row["bug_id"], row["artifact_id"], row["statement_id"])
                ],
            }
            for row in samples
        ]
    )
    gold_samples = [
        row
        for row in samples
        if label_by_key[(row["bug_id"], row["artifact_id"], row["statement_id"])]
    ]
    sample_text = samples_path.read_text(encoding="utf-8").lower()
    leakage = {word: sample_text.count(word) for word in LEAKAGE_WORDS}
    gate = relation_gate(diagnostic_rows)
    inventory = contract["corpus_inventory"]
    if len(diagnostic_rows) != inventory["case_count"]:
        gate["failures"].append(f"pilot_case_count:{len(diagnostic_rows)}")
    if sum(leakage.values()):
        gate["failures"].append("trainer_visible_label_leak")
    gate["failures"] = sorted(set(gate["failures"]))
    gate["decision"] = (
        "failed_stop" if gate["failures"] else "continue_to_baselines"
    )
    diagnostic = {
        "schema_version": "verilogcause_relation_diagnostic.v1",
        "experiment_scope": contract["experiment_scope"],
        "inventory_sha256": inventory["inventory_sha256"],
        "case_count": len(diagnostic_rows),
        "fault_form_counts": {
            form: sum(row["fault_form"] == form for row in diagnostic_rows)
            for form in ("existing_faulty_statement", "missing_faulty_statement")
        },
        "existing_candidate_unrepresentable_count": sum(
            row["fault_form"] == "existing_faulty_statement"
            and row["gold_representable"] is False
            for row in diagnostic_rows
        ),
        "task_boundary_unrepresentable_count": sum(
            row["fault_form"] == "missing_faulty_statement"
            and row["gold_representable"] is False
            for row in diagnostic_rows
        ),
        "gold_representable_count": sum(
            row["gold_representable"] is True for row in diagnostic_rows
        ),
        "gold_unrepresentable_count": sum(
            row["gold_representable"] is False for row in diagnostic_rows
        ),
        "gold_reachable_count": sum(row["gold_reachable"] for row in diagnostic_rows),
        "graph_complete_count": sum(row["graph_complete"] for row in diagnostic_rows),
        "zero_pass_count": sum(not row["has_passing_trace"] for row in diagnostic_rows),
        "zero_fail_count": sum(not row["has_failing_trace"] for row in diagnostic_rows),
        "activation_diagnostic_count": sum(
            row["activation_diagnostic_count"] for row in diagnostic_rows
        ),
        "relation_coverage": {
            "all_candidate": (
                fmean(row["features"]["relation_coverage"] for row in samples)
                if samples
                else None
            ),
            "gold_only": (
                fmean(row["features"]["relation_coverage"] for row in gold_samples)
                if gold_samples
                else None
            ),
        },
        "causal_trace_coverage_pairwise": pairwise,
        "label_leak_scan": {
            "visible_count": sum(leakage.values()),
            "token_counts": leakage,
        },
        "unknown_statement_reference_count": 0,
        "fuzzy_mapping_count": 0,
        "chisel_provenance_count": 0,
        "cases": diagnostic_rows,
        **gate,
    }
    method_rows = []
    for case in (row for row in rows if row["record_type"] == "case"):
        method_rows.append(
            {
                "record_type": "case",
                "case_id": case["case_id"],
                "design_id": case["design_id"],
                "family_id": case["family_id"],
                "rtl_manifest": case["model_inputs"]["rtl_manifest"],
                "workload_pool": case["model_inputs"]["workload_pool"],
                "candidate_universe": case["candidate_universe"],
            }
        )
        method_rows.extend(
            {
                "record_type": "trace",
                "case_id": trace["case_id"],
                "workload_id": trace["workload_id"],
                "trace_id": trace["trace_id"],
                "outcome": trace["outcome"],
                "failure_endpoint": trace["failure_endpoint"],
                "failure_endpoints": trace["failure_endpoints"],
                "workload": trace["workload"],
                "coverage": trace["coverage"],
                "fst": trace["fst"],
                "oracle": {
                    "endpoint_sampling": trace["oracle"]["endpoint_sampling"],
                    "clock": trace["oracle"]["clock"],
                    "faulty_fst_sha256": trace["oracle"]["faulty_fst_sha256"],
                },
                "vca_graph": trace["vca_graph"],
            }
            for trace in traces[case["case_id"]]
        )
    method_path = run / "method_input_manifest.jsonl"
    _write_jsonl(method_path, method_rows)
    method_text = method_path.read_text(encoding="utf-8").lower()
    method_leakage = {word: method_text.count(word) for word in LEAKAGE_WORDS}
    diagnostic["method_input_label_leak_scan"] = {
        "visible_count": sum(method_leakage.values()),
        "token_counts": method_leakage,
    }
    stage1_failures = []
    if len(diagnostic_rows) != 38:
        stage1_failures.append("case_count")
    if any(row.get("gold_representable") is not True for row in diagnostic_rows):
        stage1_failures.append("candidate_representability")
    if any(
        not row["has_failing_trace"]
        or not row["has_passing_trace"]
        or not row["graph_complete"]
        or row["activation_diagnostic_count"]
        for row in diagnostic_rows
    ):
        stage1_failures.append("trace_or_identity")
    if sum(method_leakage.values()):
        stage1_failures.append("method_input_isolation")
    stage1 = {
        "schema_version": "verilogcause_stage1_scope.v1",
        "case_count": len(diagnostic_rows),
        "excluded_cases": EXCLUDED_CASES,
        "representable_count": sum(
            row.get("gold_representable") is True for row in diagnostic_rows
        ),
        "failing_trace_case_count": sum(row["has_failing_trace"] for row in diagnostic_rows),
        "passing_trace_case_count": sum(row["has_passing_trace"] for row in diagnostic_rows),
        "exact_endpoint_case_count": sum(row["graph_complete"] for row in diagnostic_rows),
        "method_input_label_word_count": sum(method_leakage.values()),
        "failures": stage1_failures,
        "decision": "continue_to_stage_two" if not stage1_failures else "failed_stop",
    }
    _write_json(run / "stage1_scope.json", stage1)
    if stage1_failures:
        gate["failures"].extend(f"stage1:{item}" for item in stage1_failures)
        gate["decision"] = "failed_stop"
        diagnostic.update(gate)
    _write_json(run / "relation_diagnostic.json", diagnostic)
    gate_report = {
        "schema_version": "verilogcause_gate_report.v1",
        "decision": gate["decision"],
        "next_action": (
            "do_not_train" if gate["decision"] == "failed_stop" else "run_baselines"
        ),
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "artifacts": {
            name: _artifact(run / name, run)
            for name in (
                "dataset_contract.json",
                "manifest.jsonl",
                "samples.jsonl",
                "evaluator_labels.jsonl",
                "method_input_manifest.jsonl",
                "stage1_scope.json",
                "relation_diagnostic.json",
            )
        },
    }
    _write_json(run / "gate_report.json", gate_report)
    return gate_report


def _checked_run_artifact(run: Path, ref: Any, field: str) -> Path:
    if not isinstance(ref, dict) or not isinstance(ref.get("path"), str):
        raise VerilogCauseError(f"{field} artifact reference is missing")
    path = (run / ref["path"]).resolve()
    try:
        path.relative_to(run)
    except ValueError as exc:
        raise VerilogCauseError(f"{field} artifact escapes the run") from exc
    if (
        not path.is_file()
        or path.stat().st_size != ref.get("bytes")
        or _sha256(path) != ref.get("sha256")
    ):
        raise VerilogCauseError(f"{field} artifact hash drift")
    return path


def _baseline_scores(
    sample: dict[str, Any],
    failing_count: int,
    passing_count: int,
    mapped_failing_trace_count: int,
) -> dict[str, Any]:
    features = sample.get("features") or {}

    def count(name: str, total: int) -> int:
        rate = features.get(name)
        if not isinstance(rate, (int, float)) or not math.isfinite(rate):
            raise VerilogCauseError(f"baseline {name} is not finite")
        value = round(rate * total)
        if not 0 <= value <= total or not math.isclose(
            rate, value / total, abs_tol=1e-12
        ):
            raise VerilogCauseError(f"baseline {name} is not an exact trace rate")
        return value

    if failing_count < 1 or passing_count < 1:
        raise VerilogCauseError("baselines require failing and passing traces")
    ef = count("fail_execution_rate", failing_count)
    ep = count("pass_execution_rate", passing_count)
    nf = failing_count - ef
    np = passing_count - ep
    scores = {
        "ochiai_fixed_pool": (
            ef / math.sqrt((ef + nf) * (ef + ep)) if ef else 0.0
        ),
        "tarsel_formula_fixed_pool": ef
        * math.sqrt(abs(ep - ef + nf - np)),
        "unweighted_exact_slice": mapped_failing_trace_count / failing_count,
    }
    if tuple(scores) != W3_BASELINES or any(
        not all(isinstance(item, (int, float)) and math.isfinite(item) for item in score)
        if isinstance(score, tuple)
        else not isinstance(score, (int, float)) or not math.isfinite(score)
        for score in scores.values()
    ):
        raise VerilogCauseError("baseline score is incomplete or non-finite")
    return {
        "trace_counts": {
            "failing": failing_count,
            "passing": passing_count,
            "ef": ef,
            "ep": ep,
            "nf": nf,
            "np": np,
            "mapped_failing_trace_count": mapped_failing_trace_count,
        },
        "scores": scores,
    }


def _baseline_macros(
    cases: Sequence[dict[str, Any]], group_field: str
) -> dict[str, Any]:
    grouped: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for case in cases:
        grouped[case[group_field]].append(case)
    return {
        group: {
            method: {
                "case_count": len(rows),
                "mrr": round(fmean(row["metrics"][method]["mrr"] for row in rows), 6),
                "exam_percent": round(
                    fmean(row["metrics"][method]["exam_percent"] for row in rows),
                    6,
                ),
                **{
                    f"top_{limit}_rate": round(
                        fmean(row["metrics"][method][f"top_{limit}"] for row in rows),
                        6,
                    )
                    for limit in (1, 3, 5, 10)
                },
            }
            for method in W3_BASELINES
        }
        for group, rows in sorted(grouped.items())
    }


def _baseline_summary(cases: Sequence[dict[str, Any]]) -> dict[str, Any]:
    return {
        method: {
            "case_count": len(cases),
            "mrr": round(fmean(row["metrics"][method]["mrr"] for row in cases), 6),
            "exam_percent": round(
                fmean(row["metrics"][method]["exam_percent"] for row in cases), 6
            ),
            **{
                f"top_{limit}_rate": round(
                    fmean(row["metrics"][method][f"top_{limit}"] for row in cases),
                    6,
                )
                for limit in (1, 3, 5, 10)
            },
        }
        for method in W3_BASELINES
    }


def generate_candidate_operations(
    source: str, candidate: dict[str, Any]
) -> list[dict[str, Any]]:
    lines = source.splitlines(keepends=True)
    start = candidate["line_start"] - 1
    end = candidate["line_end"]
    if start < 0 or end > len(lines) or end != start + 1:
        return []
    line = lines[start]
    body = line.rstrip("\r\n")
    ending = line[len(body) :]
    proposals: list[tuple[str, str]] = []

    def add(kind: str, replacement: str) -> None:
        if replacement != body and all(replacement != row[1] for row in proposals):
            proposals.append((kind, replacement))

    kind = candidate["statement_kind"]
    if kind == "conditional_guard":
        case_match = re.search(r"(\d+)'([bBdDhH])([0-9a-fA-F_]+)(\s*:)", body)
        if case_match:
            width = int(case_match.group(1))
            radix = case_match.group(2).lower()
            digits = case_match.group(3).replace("_", "")
            base = {"b": 2, "d": 10, "h": 16}[radix]
            value = int(digits, base)
            limit = 1 << width
            for changed in ((value + 1) % limit, (value - 1) % limit, value ^ 1):
                rendered = (
                    format(changed, f"0{width}b")
                    if radix == "b"
                    else format(changed, "x")
                    if radix == "h"
                    else str(changed)
                )
                add(
                    "case_label",
                    body[: case_match.start()]
                    + f"{width}'{radix}{rendered}{case_match.group(4)}"
                    + body[case_match.end() :],
                )
        if_match = re.search(r"\bif\s*\(([^()]*)\)", body)
        if if_match:
            condition = if_match.group(1).strip()
            changed = condition[1:].strip() if condition.startswith("!") else f"!({condition})"
            add(
                "condition_negation",
                body[: if_match.start(1)] + changed + body[if_match.end(1) :],
            )

    if kind in {"assignment", "register_update"}:
        assignment = re.search(r"(?P<lhs>[^;]+?)(?P<op><=|=(?!=))(?P<rhs>[^;]+);", body)
        if assignment:
            lhs = assignment.group("lhs")
            rhs = assignment.group("rhs")
            target = list(re.finditer(r"\b\w+\s*\[\s*(\d+)\s*:\s*(\d+)\s*\]", lhs))
            if target:
                match = target[-1]
                high, low = map(int, match.groups())
                width = abs(high - low) + 1
                if high >= low:
                    ranges = [(low - 1, low - width), (high + width, high + 1)]
                else:
                    ranges = [(low + 1, low + width), (high - width, high - 1)]
                for new_high, new_low in ranges:
                    if min(new_high, new_low) < 0:
                        continue
                    changed_lhs = (
                        lhs[: match.start()]
                        + re.sub(
                            r"\[.*\]$",
                            f"[{new_high}:{new_low}]",
                            match.group(0),
                        )
                        + lhs[match.end() :]
                    )
                    add(
                        "target_slice",
                        body[: assignment.start("lhs")]
                        + changed_lhs
                        + assignment.group("op")
                        + rhs
                        + ";"
                        + body[assignment.end() :],
                    )
            literal = re.search(r"(\d+)'([bBdDhH])([0-9a-fA-F_]+)", rhs)
            if literal:
                width = int(literal.group(1))
                radix = literal.group(2).lower()
                base = {"b": 2, "d": 10, "h": 16}[radix]
                value = int(literal.group(3).replace("_", ""), base)
                limit = 1 << width
                changes = []
                if value >= limit:
                    changes.append(("oversized_literal", str(value)))
                for changed in (0, limit - 1):
                    if changed == value:
                        continue
                    rendered = (
                        format(changed, f"0{width}b")
                        if radix == "b"
                        else format(changed, "x")
                        if radix == "h"
                        else str(changed)
                    )
                    changes.append(("rhs_constant", f"{width}'{radix}{rendered}"))
                for operation_kind, replacement in changes:
                    changed_rhs = (
                        rhs[: literal.start()] + replacement + rhs[literal.end() :]
                    )
                    add(
                        operation_kind,
                        body[: assignment.start("rhs")]
                        + changed_rhs
                        + body[assignment.end("rhs") :],
                    )

    result = []
    for index, (operation_kind, replacement) in enumerate(
        proposals[:MAX_OPERATIONS_PER_CANDIDATE], 1
    ):
        result.append(
            {
                "operation_id": f"{candidate['statement_id']}-{index}",
                "operation_kind": operation_kind,
                "artifact_id": candidate["artifact_id"],
                "statement_id": candidate["statement_id"],
                "line": candidate["line_start"],
                "replacement": replacement + ending,
                "operation_cost": sum(
                    left != right
                    for left, right in zip(body, replacement)
                )
                + abs(len(body) - len(replacement)),
            }
        )
    return result


def _selected_counterfactual_operations(
    candidates: dict[str, Any], samples: Sequence[dict[str, Any]], rtl: dict[str, Any]
) -> list[dict[str, Any]]:
    sources = {
        row["artifact_id"]: Path(row["path"]).read_text(encoding="utf-8")
        for row in rtl["sources"]
    }
    by_key = {
        (row["artifact_id"], row["statement_id"]): row for row in samples
    }
    priority = {
        "oversized_literal": 0,
        "target_slice": 1,
        "case_label": 2,
        "condition_negation": 3,
        "rhs_constant": 4,
    }
    rows = []
    for candidate in candidates["candidates"]:
        operations = generate_candidate_operations(
            sources[candidate["artifact_id"]], candidate
        )
        if not operations:
            continue
        sample = by_key[(candidate["artifact_id"], candidate["statement_id"])]
        features = sample["features"]
        context = sources[candidate["artifact_id"]].splitlines()[
            max(0, candidate["line_start"] - 4) : candidate["line_start"]
        ]
        near_reset = any(
            "reset" in line.lower() or "rst" in line.lower() for line in context
        )
        operation_kinds = {row["operation_kind"] for row in operations}
        selection_priority = (
            0
            if "oversized_literal" in operation_kinds
            else 1
            if near_reset and "rhs_constant" in operation_kinds
            else 2 + min(priority[row] for row in operation_kinds)
        )
        rows.append(
            {
                "candidate": candidate,
                "operations": operations,
                "selection_key": (
                    selection_priority,
                    -features["fail_execution_rate"],
                    -features["execution_specificity"],
                    candidate["line_start"],
                    candidate["statement_id"],
                ),
            }
        )
    rows.sort(key=lambda row: row["selection_key"])
    selected = []
    selected_ids = set()
    quotas = {
        "oversized_literal": 4,
        "target_slice": 8,
        "case_label": 16,
        "condition_negation": 16,
        "rhs_constant": 8,
    }
    for operation_kind, quota in quotas.items():
        for row in (
            row
            for row in rows
            if any(
                operation["operation_kind"] == operation_kind
                for operation in row["operations"]
            )
        ):
            statement_id = row["candidate"]["statement_id"]
            if statement_id in selected_ids:
                continue
            selected.append(row)
            selected_ids.add(statement_id)
            if sum(
                any(
                    operation["operation_kind"] == operation_kind
                    for operation in selected_row["operations"]
                )
                for selected_row in selected
            ) >= quota:
                break
    for row in rows:
        if len(selected) >= MAX_REPLAY_CANDIDATES_PER_CASE:
            break
        statement_id = row["candidate"]["statement_id"]
        if statement_id not in selected_ids:
            selected.append(row)
            selected_ids.add(statement_id)
    return selected


def _endpoint_matches(
    fst: Path, endpoint: dict[str, Any], clock_signal: str
) -> bool:
    from verilog_causal_analysis.cycle_waveform import CycleAlignedWaveform

    with CycleAlignedWaveform(str(fst), clock_signal, exact_clock=True) as waveform:
        signal = _waveform_endpoint_signal(
            waveform, endpoint["signal"], clock_signal.rsplit(".", 1)[0]
        )
        return (
            waveform.get_signal_value(signal, endpoint["cycle"])
            == endpoint["correct"]
        )


def _counterfactual_candidate_score(
    outcomes: Sequence[dict[str, Any]], exact_slice: float, endpoint_distance: float
) -> tuple[float, float, float, float, float]:
    compiled = [row for row in outcomes if row["compile_status"] == "complete"]
    if not compiled:
        return (0.0, 0.0, exact_slice, 0.0, endpoint_distance)
    best = max(
        compiled,
        key=lambda row: (
            row["failure_repair_rate"],
            -row["passing_regression_rate"],
            -row["operation_cost"],
            row["operation_id"],
        ),
    )
    return (
        best["failure_repair_rate"],
        -best["passing_regression_rate"],
        exact_slice,
        -best["operation_cost"],
        endpoint_distance,
    )


def _exact_relation_keys(
    candidates: dict[str, Any], graph: dict[str, Any]
) -> set[tuple[str, str]]:
    return {
        key
        for key, edges in resolve_rtl_evidence(candidates, graph).items()
        if any(
            edge.get("relation")
            in {"active_statement_write", "active_guard"}
            and edge.get("activation_status") == "active_exact"
            for edge in edges
        )
    }


def _wit_hw_mutations(
    trigger: bytes, input_widths: dict[str, int], count: int
) -> list[bytes]:
    rows = [line.split(b",") for line in trigger.split(b"\n")]
    widths = list(input_widths.values())
    rng = random.Random(0)
    result = []
    seen = {trigger}
    attempts = 0
    while len(result) < count and attempts < count * 20:
        attempts += 1
        changed = [list(row) for row in rows]
        cycle_count = max(1, int(len(changed) * 0.2))
        for cycle in rng.sample(range(len(changed)), cycle_count):
            signal_count = rng.randint(1, len(widths))
            for signal in rng.sample(range(len(widths)), signal_count):
                value = rng.randint(0, (1 << widths[signal]) - 1)
                changed[cycle][signal] = f"{value:0{widths[signal]}b}".encode()
        payload = b"\n".join(b",".join(row) for row in changed)
        if payload not in seen:
            seen.add(payload)
            result.append(payload)
    if len(result) != count:
        raise VerilogCauseError("Wit-HW mutation pool is smaller than requested")
    return result


def _discriminative_order(rows: Sequence[dict[str, Any]]) -> list[dict[str, Any]]:
    remaining = list(rows)
    selected = []
    covered: set[str] = set()
    while remaining:
        row = max(
            remaining,
            key=lambda item: (
                len(set(item["signature"]) - covered),
                len(item["signature"]),
                item["workload_id"],
            ),
        )
        selected.append(row)
        covered.update(row["signature"])
        remaining.remove(row)
    return selected


def _replay_operation(
    run: Path,
    case_id: str,
    design_id: str,
    operation: dict[str, Any],
    rtl: dict[str, Any],
    traces: Sequence[dict[str, Any]],
    timeout: float,
) -> dict[str, Any]:
    root = run / "counterfactual" / case_id / operation["operation_id"]
    root.mkdir(parents=True, exist_ok=False)
    sources = {row["artifact_id"]: row for row in rtl["sources"]}
    for source in rtl["sources"]:
        shutil.copyfile(source["path"], root / source["compile_name"])
    source_root = Path(rtl["sources"][0]["path"]).parent
    shutil.copyfile(source_root / "testbench.sv", root / "testbench.sv")
    shutil.copyfile(source_root / "file_list.txt", root / "file_list.txt")
    target = root / sources[operation["artifact_id"]]["compile_name"]
    lines = target.read_text(encoding="utf-8").splitlines(keepends=True)
    lines[operation["line"] - 1] = operation["replacement"]
    target.write_text("".join(lines), encoding="utf-8")
    result = {key: value for key, value in operation.items() if key != "replacement"}
    try:
        compile_result = _compile(root, timeout)
    except PreparationError:
        return {
            **result,
            "compile_status": "compile_error",
            "failure_repair_rate": 0.0,
            "passing_regression_rate": 0.0,
            "repeat_deterministic": None,
        }

    failing = passing = repaired = regressed = 0
    repeat_deterministic = True
    for trace in traces:
        workload = _checked_run_artifact(
            run, trace["workload"], f"{case_id}:{trace['workload_id']}:workload"
        ).read_bytes()
        trace_root = root / "traces" / trace["workload_id"]
        repeat_root = root / "repeat" / trace["workload_id"]
        binary = root / "obj_dir" / "Vtestbench"
        _run_trace(binary, trace_root, workload, timeout)
        _run_trace(binary, repeat_root, workload, timeout)
        output = trace_root / "output-signals.txt"
        repeat_deterministic &= _sha256(output) == _sha256(
            repeat_root / "output-signals.txt"
        )
        reference = (
            run
            / "cases"
            / case_id
            / "evaluator_private"
            / "traces"
            / "correct"
            / trace["workload_id"]
            / "output-signals.txt"
        )
        if trace["outcome"] == "failing":
            failing += 1
            repaired += _endpoint_matches(
                trace_root / "dump.fst",
                trace["failure_endpoint"],
                DESIGN_DUT_CLOCKS[design_id],
            )
        else:
            passing += 1
            regressed += compare_outputs(reference, output)["outcome"] == "failing"
    return {
        **result,
        "compile_status": "complete",
        "compile_runtime_seconds": compile_result["runtime_seconds"],
        "failure_repair_rate": repaired / failing if failing else 0.0,
        "passing_regression_rate": regressed / passing if passing else 0.0,
        "repeat_deterministic": repeat_deterministic,
    }


def counterfactual_pilot(run: Path, timeout: float = 120) -> dict[str, Any]:
    run = run.resolve()
    validate_run_contract(run)
    gate = json.loads((run / "gate_report.json").read_text(encoding="utf-8"))
    if gate.get("decision") != "continue_to_counterfactual_pilot":
        raise VerilogCauseError("stage two does not authorize counterfactual pilot")
    method_rows = _read_jsonl(run / "method_input_manifest.jsonl")
    samples = _read_jsonl(run / "samples.jsonl")
    traces: dict[str, list[dict[str, Any]]] = defaultdict(list)
    design_by_case = {}
    for row in method_rows:
        if row["record_type"] == "trace":
            traces[row["case_id"]].append(row)
        elif row["record_type"] == "case":
            design_by_case[row["case_id"]] = row["design_id"]
    samples_by_case: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for row in samples:
        samples_by_case[row["bug_id"]].append(row)

    jobs = []
    selected_by_case: dict[str, list[dict[str, Any]]] = {}
    for case_id in PILOT_CASES:
        model = run / "cases" / case_id / "model_inputs"
        rtl = json.loads((model / "rtl_manifest.json").read_text(encoding="utf-8"))
        candidates = json.loads((model / "rtl_candidates.json").read_text(encoding="utf-8"))
        selected = _selected_counterfactual_operations(
            candidates, samples_by_case[case_id], rtl
        )
        selected_by_case[case_id] = selected
        for row in selected:
            for operation in row["operations"]:
                jobs.append(
                    (
                        case_id,
                        design_by_case[case_id],
                        operation,
                        rtl,
                        traces[case_id],
                    )
                )

    def run_job(job: tuple[str, str, dict[str, Any], dict[str, Any], Sequence[dict[str, Any]]]):
        case_id, design_id, operation, rtl, case_traces = job
        return {
            "case_id": case_id,
            **_replay_operation(
                run, case_id, design_id, operation, rtl, case_traces, timeout
            ),
        }

    with ThreadPoolExecutor(max_workers=COUNTERFACTUAL_WORKERS) as pool:
        outcomes = list(pool.map(run_job, jobs))
    _write_jsonl(run / "counterfactual_method.jsonl", outcomes)

    baseline = json.loads((run / "baseline_report.json").read_text(encoding="utf-8"))
    baseline_by_case = {row["case_id"]: row for row in baseline["cases"]}
    labels = _read_jsonl(run / "evaluator_labels.jsonl")
    outcomes_by_key: dict[tuple[str, str, str], list[dict[str, Any]]] = defaultdict(list)
    for row in outcomes:
        outcomes_by_key[(row["case_id"], row["artifact_id"], row["statement_id"])].append(row)
    gold_by_case: dict[str, set[tuple[str, str]]] = defaultdict(set)
    for row in labels:
        if row["is_gold"]:
            gold_by_case[row["bug_id"]].add((row["artifact_id"], row["statement_id"]))

    cases = []
    valid_designs = set()
    valid_types = set()
    repaired_gold = 0
    for case_id in PILOT_CASES:
        baseline_case = baseline_by_case[case_id]
        exact = {
            (row["artifact_id"], row["statement_id"]): row["scores"][
                "unweighted_exact_slice"
            ]
            for row in baseline_case["candidates"]
        }
        sample_by_key = {
            (row["artifact_id"], row["statement_id"]): row
            for row in samples_by_case[case_id]
        }
        scores = {
            key: _counterfactual_candidate_score(
                outcomes_by_key[(case_id, *key)],
                score,
                sample_by_key[key]["features"]["near_failure_mean"],
            )
            for key, score in exact.items()
        }
        counterfactual_metrics = set_valued_metrics(
            scores, gold_by_case[case_id], representable=True
        )
        exact_metrics = baseline_case["metrics"]["unweighted_exact_slice"]
        gold_outcomes = [
            row
            for key in gold_by_case[case_id]
            for row in outcomes_by_key[(case_id, *key)]
            if row["compile_status"] == "complete"
        ]
        if gold_outcomes:
            valid_designs.add(baseline_case["design_id"])
            valid_types.update(row["operation_kind"] for row in gold_outcomes)
            repaired_gold += any(row["failure_repair_rate"] > 0 for row in gold_outcomes)
        cases.append(
            {
                "case_id": case_id,
                "design_id": baseline_case["design_id"],
                "selected_candidate_count": len(selected_by_case[case_id]),
                "operation_count": sum(
                    len(row["operations"]) for row in selected_by_case[case_id]
                ),
                "gold_valid_operation_count": len(gold_outcomes),
                "gold_operation_kinds": sorted(
                    {row["operation_kind"] for row in gold_outcomes}
                ),
                "gold_repair": any(
                    row["failure_repair_rate"] > 0 for row in gold_outcomes
                ),
                "exact_slice": exact_metrics,
                "counterfactual_rule": counterfactual_metrics,
                "rank_improved": counterfactual_metrics["gold_rank"]
                < exact_metrics["gold_rank"],
            }
        )

    stage3_failures = []
    if len(valid_designs) < 3:
        stage3_failures.append("fewer_than_three_designs_with_valid_gold_operation")
    if len(valid_types) < 3:
        stage3_failures.append("fewer_than_three_gold_operation_types")
    if repaired_gold < 1:
        stage3_failures.append("no_gold_operation_repairs_failure_endpoint")
    if not any(row["passing_regression_rate"] > 0 for row in outcomes):
        stage3_failures.append("no_passing_regression_counterexample")
    if any(
        len(row["operations"]) > MAX_OPERATIONS_PER_CANDIDATE
        for selected in selected_by_case.values()
        for row in selected
    ):
        stage3_failures.append("operation_budget_not_normalized")
    stage3 = {
        "schema_version": "verilogcause_stage3_operations.v1",
        "pilot_cases": list(PILOT_CASES),
        "valid_design_count": len(valid_designs),
        "valid_operation_types": sorted(valid_types),
        "repaired_gold_case_count": repaired_gold,
        "passing_regression_counterexample_count": sum(
            row["passing_regression_rate"] > 0 for row in outcomes
        ),
        "max_operations_per_candidate": MAX_OPERATIONS_PER_CANDIDATE,
        "max_replay_candidates_per_case": MAX_REPLAY_CANDIDATES_PER_CASE,
        "failures": stage3_failures,
        "decision": "continue_to_stage_four" if not stage3_failures else "failed_stop",
        "cases": cases,
    }
    _write_json(run / "stage3_operations.json", stage3)

    improved = sum(row["rank_improved"] for row in cases)
    stage4_failures = []
    if stage3_failures:
        stage4_failures.append("stage_three_failed")
    if any(row.get("repeat_deterministic") is False for row in outcomes):
        stage4_failures.append("replay_not_deterministic")
    if improved < 3:
        stage4_failures.append("fewer_than_three_pilot_rank_improvements")
    stage4 = {
        "schema_version": "verilogcause_stage4_replay.v1",
        "operation_count": len(outcomes),
        "compile_success_count": sum(
            row["compile_status"] == "complete" for row in outcomes
        ),
        "compile_failure_count": sum(
            row["compile_status"] != "complete" for row in outcomes
        ),
        "deterministic_replay_count": sum(
            row.get("repeat_deterministic") is True for row in outcomes
        ),
        "rank_improved_case_count": improved,
        "failures": stage4_failures,
        "decision": "continue_to_stage_five" if not stage4_failures else "failed_stop",
        "cases": cases,
    }
    _write_json(run / "stage4_replay.json", stage4)
    gate = {
        "schema_version": "verilogcause_gate_report.v1",
        "decision": stage4["decision"],
        "next_action": (
            "run_multi_endpoint_and_witness"
            if stage4["decision"] == "continue_to_stage_five"
            else "stop_after_counterfactual_diagnosis"
        ),
    }
    _write_json(run / "gate_report.json", gate)
    return gate


def _stage5_witness_case(
    run: Path,
    case_id: str,
    design_id: str,
    rtl: dict[str, Any],
    candidates: dict[str, Any],
    workload_pool: dict[str, Any],
    timeout: float,
) -> dict[str, Any]:
    from verilog_causal_analysis import build_causal_graph
    from verilog_causal_analysis.cycle_waveform import CycleAlignedWaveform

    root = run / "stage5" / "witness" / case_id
    inputs = root / "inputs"
    inputs.mkdir(parents=True, exist_ok=False)
    existing = []
    for row in workload_pool["workloads"]:
        existing.append(
            (
                row["workload_id"],
                _checked_run_artifact(
                    run, row["artifact"], f"{case_id}:{row['workload_id']}"
                ).read_bytes(),
            )
        )
    trigger = next(payload for name, payload in existing if name == "trigger")
    existing_payloads = {payload for _, payload in existing}
    mutations = [
        payload
        for payload in _wit_hw_mutations(trigger, workload_pool["input_widths"], 32)
        if payload not in existing_payloads
    ][: WITNESS_POOL_SIZE - len(existing)]
    if len(existing) + len(mutations) != WITNESS_POOL_SIZE:
        raise VerilogCauseError(f"{case_id}: witness pool is incomplete")
    pool = [
        (f"fixed_{name}", payload) for name, payload in existing
    ] + [
        (f"wit_hw_{index:02d}", payload)
        for index, payload in enumerate(mutations, 1)
    ]
    clock_signal = DESIGN_DUT_CLOCKS[design_id]
    allowed_sources = {row["compile_name"] for row in rtl["sources"]}
    universe = _candidate_universe(candidates)
    correct_binary = (
        run
        / "cases"
        / case_id
        / "evaluator_private"
        / "build_correct"
        / "obj_dir"
        / "Vtestbench"
    )
    faulty_binary = (
        run
        / "cases"
        / case_id
        / "model_inputs"
        / "build_sanitized_faulty"
        / "obj_dir"
        / "Vtestbench"
    )
    rows = []
    for workload_id, payload in pool:
        input_path = inputs / f"{workload_id}.in"
        input_path.write_bytes(payload)
        correct_dir = root / "evaluator_private" / workload_id
        faulty_dir = root / "method" / workload_id
        _run_trace(correct_binary, correct_dir, payload, timeout)
        _run_trace(faulty_binary, faulty_dir, payload, timeout)
        public_signals = _output_signals(correct_dir / "output-signals.txt")
        comparison = compare_waveforms(
            correct_dir / "dump.fst",
            faulty_dir / "dump.fst",
            public_signals,
            clock_signal,
        )
        coverage = read_line_coverage(faulty_dir / "coverage.dat", allowed_sources)
        hit_lines = {
            (source["artifact_id"], row["line"])
            for source in rtl["sources"]
            for row in coverage
            if row["source"] == source["compile_name"] and row["count"] > 0
        }
        executed = {
            key
            for key, candidate in universe.items()
            if (candidate["artifact_id"], candidate["line_start"]) in hit_lines
        }
        signature = {f"exec:{key[0]}:{key[1]}" for key in executed}
        exact_keys: set[tuple[str, str]] = set()
        graph_ref = None
        fst_ref = _artifact(faulty_dir / "dump.fst", run)
        if comparison["outcome"] == "failing":
            trace = {
                "trace_id": _canonical_sha256(
                    {
                        "case_id": case_id,
                        "workload_id": workload_id,
                        "fst_sha256": fst_ref["sha256"],
                    }
                ),
                "fst": fst_ref,
                "oracle": {
                    "endpoint_sampling": _ENDPOINT_SAMPLING,
                    "clock": {"signal": clock_signal, "edge": "rising"},
                    "faulty_fst_sha256": fst_ref["sha256"],
                },
                "failure_endpoint": comparison["failure_endpoint"],
            }
            request = _vca_request(run, trace, rtl, design_id)
            graph = build_causal_graph(request)
            graph_path = faulty_dir / "causal_graph.json"
            _write_json(graph_path, graph)
            graph_ref = _artifact(graph_path, run)
            evidence = resolve_rtl_evidence(candidates, graph)
            exact = [
                (key, edge)
                for key, edges in evidence.items()
                for edge in edges
                if edge.get("relation")
                in {"active_statement_write", "active_guard"}
                and edge.get("activation_status") == "active_exact"
            ]
            exact_keys = {key for key, _edge in exact}
            nodes = {row["node_id"]: row for row in graph["signal_nodes"]}
            signature.update(f"path:{key[0]}:{key[1]}" for key in exact_keys)
            signature.update(
                f"branch:{key[0]}:{key[1]}:taken"
                for key, edge in exact
                if edge["relation"] == "active_guard"
            )
            with CycleAlignedWaveform(
                str(faulty_dir / "dump.fst"), clock_signal, exact_clock=True
            ) as waveform:
                for key, edge in exact:
                    if (
                        edge["relation"] != "active_statement_write"
                        or universe[key]["statement_kind"] != "register_update"
                    ):
                        continue
                    node = nodes[edge["target_node_id"]]
                    previous = (
                        waveform.get_signal_value(node["signal"], node["cycle"] - 1)
                        if node["cycle"] > 0
                        else "initial"
                    )
                    signature.add(
                        f"state:{key[0]}:{key[1]}:{previous}->{node['value']}"
                    )
        rows.append(
            {
                "workload_id": workload_id,
                "workload": _artifact(input_path, run),
                "outcome": comparison["outcome"],
                "failure_endpoint": comparison["failure_endpoint"],
                "signature": sorted(signature),
                "exact_keys": [list(key) for key in sorted(exact_keys)],
                "fst": fst_ref,
                "vca_graph": graph_ref,
            }
        )

    by_id = {row["workload_id"]: row for row in rows}
    mutation_rows = [row for row in rows if row["workload_id"].startswith("wit_hw_")]
    fixed_rows = [row for row in rows if row["workload_id"].startswith("fixed_")]
    wit_hw = [by_id["fixed_trigger"], *mutation_rows, *fixed_rows[1:]]
    random_rows = list(rows)
    random.Random(0).shuffle(random_rows)
    orders = {
        "fixed": [row["workload_id"] for row in rows],
        "wit_hw": [row["workload_id"] for row in wit_hw],
        "random": [row["workload_id"] for row in random_rows],
        "discriminative": [
            row["workload_id"] for row in _discriminative_order(rows)
        ],
    }
    return {"rows": rows, "orders": orders}


def stage5_evidence(run: Path, timeout: float = 120) -> dict[str, Any]:
    from verilog_causal_analysis import build_causal_graph

    run = run.resolve()
    validate_run_contract(run)
    gate = json.loads((run / "gate_report.json").read_text(encoding="utf-8"))
    if gate.get("decision") != "continue_to_stage_five":
        raise VerilogCauseError("stage four does not authorize stage five")
    method_rows = _read_jsonl(run / "method_input_manifest.jsonl")
    cases = {
        row["case_id"]: row for row in method_rows if row["record_type"] == "case"
    }
    traces: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for row in method_rows:
        if row["record_type"] == "trace":
            traces[row["case_id"]].append(row)

    method_output = {"schema_version": "verilogcause_stage5_method.v1", "cases": []}
    for case_id in TARGET_CASES:
        case = cases[case_id]
        model = run / "cases" / case_id / "model_inputs"
        rtl = json.loads((model / "rtl_manifest.json").read_text(encoding="utf-8"))
        candidates = json.loads((model / "rtl_candidates.json").read_text(encoding="utf-8"))
        workload_pool = json.loads((model / "workload_pool.json").read_text(encoding="utf-8"))
        multi_rows = []
        graph_dir = run / "stage5" / "multi_endpoint" / case_id
        graph_dir.mkdir(parents=True, exist_ok=False)
        for trace in sorted(traces[case_id], key=lambda row: row["workload_id"]):
            if trace["outcome"] != "failing":
                continue
            primary_graph = json.loads(
                _checked_run_artifact(
                    run, trace["vca_graph"], f"{case_id}:{trace['workload_id']}:graph"
                ).read_text(encoding="utf-8")
            )
            exact_keys = _exact_relation_keys(candidates, primary_graph)
            graph_refs = [trace["vca_graph"]]
            for index, endpoint in enumerate(trace["failure_endpoints"][1:], 1):
                request = _vca_request(run, trace, rtl, case["design_id"], endpoint)
                graph = build_causal_graph(request)
                graph_path = graph_dir / f"{trace['workload_id']}-{index}.json"
                _write_json(graph_path, graph)
                graph_refs.append(_artifact(graph_path, run))
                exact_keys.update(_exact_relation_keys(candidates, graph))
            multi_rows.append(
                {
                    "workload_id": trace["workload_id"],
                    "endpoint_count": len(trace["failure_endpoints"]),
                    "exact_keys": [list(key) for key in sorted(exact_keys)],
                    "graphs": graph_refs,
                }
            )
        witness = _stage5_witness_case(
            run,
            case_id,
            case["design_id"],
            rtl,
            candidates,
            workload_pool,
            timeout,
        )
        method_output["cases"].append(
            {
                "case_id": case_id,
                "design_id": case["design_id"],
                "multi_endpoint": multi_rows,
                "witness": witness,
            }
        )
    method_path = run / "stage5_method.json"
    _write_json(method_path, method_output)

    labels = _read_jsonl(run / "evaluator_labels.jsonl")
    baseline = json.loads((run / "baseline_report.json").read_text(encoding="utf-8"))
    baseline_by_case = {row["case_id"]: row for row in baseline["cases"]}
    gold_by_case: dict[str, set[tuple[str, str]]] = defaultdict(set)
    for row in labels:
        if row["is_gold"]:
            gold_by_case[row["bug_id"]].add((row["artifact_id"], row["statement_id"]))

    evaluated = []
    for case in method_output["cases"]:
        case_id = case["case_id"]
        baseline_case = baseline_by_case[case_id]
        universe = {
            (row["artifact_id"], row["statement_id"])
            for row in baseline_case["candidates"]
        }
        multi_sets = [
            {tuple(key) for key in row["exact_keys"]}
            for row in case["multi_endpoint"]
        ]
        multi_scores = {
            key: sum(key in row for row in multi_sets) / len(multi_sets)
            for key in universe
        }
        multi_metrics = set_valued_metrics(
            multi_scores, gold_by_case[case_id], representable=True
        )
        multi_new = any(
            key in set().union(*multi_sets) for key in gold_by_case[case_id]
        )
        witness_by_id = {
            row["workload_id"]: row for row in case["witness"]["rows"]
        }
        witness_results = {}
        for method, order in case["witness"]["orders"].items():
            witness_results[method] = {}
            for budget in WITNESS_BUDGETS:
                selected = [witness_by_id[item] for item in order[:budget]]
                exact_sets = [
                    {tuple(key) for key in row["exact_keys"]} for row in selected
                ]
                scores = {
                    key: sum(key in row for row in exact_sets) / budget
                    for key in universe
                }
                witness_results[method][str(budget)] = {
                    "metrics": set_valued_metrics(
                        scores, gold_by_case[case_id], representable=True
                    ),
                    "gold_new_evidence": any(
                        key in set().union(*exact_sets)
                        for key in gold_by_case[case_id]
                    ),
                    "signature_count": len(
                        set().union(*(set(row["signature"]) for row in selected))
                    ),
                    "failing_trace_count": sum(
                        row["outcome"] == "failing" for row in selected
                    ),
                }
        evaluated.append(
            {
                "case_id": case_id,
                "design_id": case["design_id"],
                "multi_endpoint": {
                    "metrics": multi_metrics,
                    "gold_new_evidence": multi_new,
                    "extra_endpoint_count": sum(
                        row["endpoint_count"] - 1 for row in case["multi_endpoint"]
                    ),
                },
                "witness": witness_results,
            }
        )

    summary = {}
    for method in ("fixed", "wit_hw", "random", "discriminative"):
        summary[method] = {}
        for budget in WITNESS_BUDGETS:
            rows = [case["witness"][method][str(budget)] for case in evaluated]
            summary[method][str(budget)] = {
                "case_count": len(rows),
                "gold_new_evidence_count": sum(row["gold_new_evidence"] for row in rows),
                "mrr": round(fmean(row["metrics"]["mrr"] for row in rows), 6),
                "exam_percent": round(
                    fmean(row["metrics"]["exam_percent"] for row in rows), 6
                ),
                "top_5_rate": round(
                    fmean(row["metrics"]["top_5"] for row in rows), 6
                ),
            }
    evidence_cases = {
        case["case_id"]
        for case in evaluated
        if case["multi_endpoint"]["gold_new_evidence"]
        or any(
            case["witness"][method]["8"]["gold_new_evidence"]
            for method in ("wit_hw", "random", "discriminative")
        )
    }
    evidence_designs = {
        case["design_id"] for case in evaluated if case["case_id"] in evidence_cases
    }
    multi_count = sum(
        case["multi_endpoint"]["gold_new_evidence"] for case in evaluated
    )
    wit_hw_8 = summary["wit_hw"]["8"]
    discriminative_8 = summary["discriminative"]["8"]
    keep_discriminative = (
        discriminative_8["gold_new_evidence_count"],
        discriminative_8["mrr"],
    ) > (wit_hw_8["gold_new_evidence_count"], wit_hw_8["mrr"])
    failures = []
    if len(evidence_designs) < 3:
        failures.append("new_evidence_in_fewer_than_three_designs")
    if any(len(order) < max(WITNESS_BUDGETS) for case in method_output["cases"] for order in case["witness"]["orders"].values()):
        failures.append("witness_budget_incomplete")
    report = {
        "schema_version": "verilogcause_stage5_evidence.v1",
        "method_input": _artifact(method_path, run),
        "target_case_count": len(evaluated),
        "new_evidence_case_count": len(evidence_cases),
        "new_evidence_design_count": len(evidence_designs),
        "multi_endpoint_gold_new_evidence_count": multi_count,
        "multi_endpoint_decision": "keep" if multi_count else "drop",
        "discriminative_decision": "keep" if keep_discriminative else "drop",
        "witness_summary": summary,
        "failures": failures,
        "decision": "continue_to_stage_six" if not failures else "failed_stop",
        "cases": evaluated,
    }
    _write_json(run / "stage5_evidence.json", report)
    _write_json(
        run / "gate_report.json",
        {
            "schema_version": "verilogcause_gate_report.v1",
            "decision": report["decision"],
            "next_action": (
                "run_target_13_experiment"
                if report["decision"] == "continue_to_stage_six"
                else "stop_after_stage_five_diagnosis"
            ),
        },
    )
    return report


def build_baseline_report(run: Path) -> dict[str, Any]:
    run = run.resolve()
    contract = validate_run_contract(run)
    input_paths = {
        name: run / name
        for name in (
            "dataset_contract.json",
            "manifest.jsonl",
            "samples.jsonl",
            "evaluator_labels.jsonl",
            "method_input_manifest.jsonl",
            "stage1_scope.json",
            "relation_diagnostic.json",
        )
    }
    if any(not path.is_file() for path in input_paths.values()):
        raise VerilogCauseError("baseline input artifact is missing")
    relation = json.loads(input_paths["relation_diagnostic.json"].read_text(encoding="utf-8"))
    if relation.get("decision") != "continue_to_baselines":
        raise VerilogCauseError("relation gate does not authorize baselines")
    stage1 = json.loads(input_paths["stage1_scope.json"].read_text(encoding="utf-8"))
    if stage1.get("decision") != "continue_to_stage_two":
        raise VerilogCauseError("stage one does not authorize baselines")

    manifest = _read_jsonl(input_paths["manifest.jsonl"])
    _validate_manifest_scope(contract, manifest)
    samples = _read_jsonl(input_paths["samples.jsonl"])
    labels = _read_jsonl(input_paths["evaluator_labels.jsonl"])
    case_rows = sorted(
        (row for row in manifest if row.get("record_type") == "case"),
        key=lambda row: row["case_id"],
    )
    traces: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for row in manifest:
        if row.get("record_type") == "trace":
            traces[row["case_id"]].append(row)
    sample_rows: dict[str, list[dict[str, Any]]] = defaultdict(list)
    label_rows: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for row in samples:
        sample_rows[row["bug_id"]].append(row)
    for row in labels:
        label_rows[row["bug_id"]].append(row)

    implementation = contract["implementation_sha256"]
    candidate_hashes = {}
    graph_hashes = {}
    trace_hashes = {}
    cases = []
    failures = []
    for case in case_rows:
        case_id = case["case_id"]
        expected_family = "sequential" if case.get("sequential") else "combinational"
        if case.get("family_id") != expected_family:
            raise VerilogCauseError(f"{case_id}: design/family taxonomy drift")
        if case.get("candidates_implementation_sha256") != implementation:
            raise VerilogCauseError(f"{case_id}: candidate implementation drift")
        if case.get("pilot_implementation_sha256") != implementation:
            raise VerilogCauseError(f"{case_id}: pilot implementation drift")

        candidate_path = _checked_run_artifact(
            run, case.get("candidate_universe"), f"{case_id}:candidate"
        )
        candidates = json.loads(candidate_path.read_text(encoding="utf-8"))
        universe = _candidate_universe(candidates)
        candidate_hashes[case_id] = _sha256(candidate_path)
        by_sample = {
            (row["artifact_id"], row["statement_id"]): row
            for row in sample_rows[case_id]
        }
        by_label = {
            (row["artifact_id"], row["statement_id"]): row["is_gold"]
            for row in label_rows[case_id]
        }
        if len(by_sample) != len(sample_rows[case_id]) or len(by_label) != len(
            label_rows[case_id]
        ):
            raise VerilogCauseError(f"{case_id}: duplicate baseline row")
        if set(by_sample) != set(universe) or set(by_label) != set(universe):
            raise VerilogCauseError(f"{case_id}: baseline candidate join is incomplete")

        case_traces = sorted(
            traces[case_id], key=lambda row: (row["workload_id"], row["trace_id"])
        )
        failing = [row for row in case_traces if row["outcome"] == "failing"]
        passing = [row for row in case_traces if row["outcome"] == "passing"]
        mappings = []
        for trace in case_traces:
            fst_path = _checked_run_artifact(
                run, trace.get("fst"), f"{case_id}:{trace['trace_id']}:fst"
            )
            trace_hashes[trace["trace_id"]] = _sha256(fst_path)
            if trace["outcome"] != "failing":
                continue
            graph_path = _checked_run_artifact(
                run, trace.get("vca_graph"), f"{case_id}:{trace['trace_id']}:graph"
            )
            graph_hashes[trace["trace_id"]] = _sha256(graph_path)
            graph = json.loads(graph_path.read_text(encoding="utf-8"))
            mappings.append(
                {
                    key
                    for key, edges in resolve_rtl_evidence(candidates, graph).items()
                    if any(
                        edge.get("relation")
                        in {"active_statement_write", "active_guard"}
                        and edge.get("activation_status") == "active_exact"
                        for edge in edges
                    )
                }
            )

        method_scores: dict[str, dict[tuple[str, str], Any]] = {
            method: {} for method in W3_BASELINES
        }
        scored_candidates = []
        for key, candidate in sorted(universe.items()):
            result = _baseline_scores(
                by_sample[key],
                len(failing),
                len(passing),
                sum(key in mapping for mapping in mappings),
            )
            for method, score in result["scores"].items():
                method_scores[method][key] = score
            scored_candidates.append(
                {
                    "artifact_id": candidate["artifact_id"],
                    "statement_id": candidate["statement_id"],
                    **result,
                }
            )
        gold = {key for key, is_gold in by_label.items() if is_gold}
        metrics = {
            method: set_valued_metrics(
                scores, gold, representable=case.get("gold_representable") is True
            )
            for method, scores in method_scores.items()
        }
        if case.get("gold_representable") is False and any(
            row["mrr"] != 0.0
            or row["exam_percent"] != 100.0
            or any(row[f"top_{limit}"] for limit in (1, 3, 5, 10))
            for row in metrics.values()
        ):
            failures.append(f"{case_id}:unrepresentable_not_zero")
        cases.append(
            {
                "case_id": case_id,
                "design_id": case["design_id"],
                "family_id": case["family_id"],
                "gold_representable": case.get("gold_representable") is True,
                "gold_reachable": case.get("gold_reachable") is True,
                "gold_sha256": _canonical_sha256(
                    [
                        {"artifact_id": key[0], "statement_id": key[1]}
                        for key in sorted(gold)
                    ]
                ),
                "candidate_count": len(universe),
                "trace_count": len(case_traces),
                "metrics": metrics,
                "candidates": scored_candidates,
            }
        )

    inventory = contract["corpus_inventory"]
    expected_relation = {
        "case_count": inventory["case_count"],
        "existing_candidate_unrepresentable_count": 0,
        "graph_complete_count": inventory["case_count"],
        "zero_pass_count": 0,
        "zero_fail_count": 0,
        "activation_diagnostic_count": 0,
        "unknown_statement_reference_count": 0,
        "fuzzy_mapping_count": 0,
        "chisel_provenance_count": 0,
        "gold_representable_count": 38,
        "gold_reachable_count": 25,
    }
    for field, expected in expected_relation.items():
        if relation.get(field) != expected:
            failures.append(f"relation:{field}")
    if (
        relation.get("experiment_scope") != contract["experiment_scope"]
        or relation.get("inventory_sha256") != inventory["inventory_sha256"]
    ):
        failures.append("relation:inventory_identity")
    if [case["case_id"] for case in cases] != inventory["case_ids"]:
        failures.append("baseline_case_inventory")
    if {
        case["design_id"]: case["family_id"] for case in cases
    } != inventory["design_family_map"]:
        failures.append("baseline_design_taxonomy")
    for case, manifest_case in zip(cases, case_rows):
        simulation = manifest_case.get("simulation") or {}
        compile_ledger = simulation.get("compile_once") or {}
        workload_ledger = simulation.get("workloads") or {}
        case_trace_rows = traces[case["case_id"]]
        if (
            manifest_case.get("status") != "complete"
            or manifest_case.get("gold_review_status") != "approved"
            or simulation.get("sanitizer_output_equivalent") is not True
            or set(compile_ledger)
            != {"correct", "original_faulty", "sanitized_faulty"}
            or any(
                row.get("returncode") != 0 or row.get("timed_out") is not False
                for row in compile_ledger.values()
            )
            or set(workload_ledger)
            != {row["workload_id"] for row in case_trace_rows}
            or any(
                set(workload) != {"correct", "original_faulty", "sanitized_faulty"}
                or
                command.get("simulation", {}).get("returncode") != 0
                or command.get("simulation", {}).get("timed_out") is not False
                for workload in workload_ledger.values()
                for command in workload.values()
            )
            or any(
                row.get("status") != "complete"
                or (row.get("sanitizer_equivalence") or {}).get("equivalent")
                is not True
                for row in case_trace_rows
            )
            or case["trace_count"] != len(case_trace_rows)
        ):
            failures.append(f"{case['case_id']}:preparation_ledger_incomplete")
        _checked_run_artifact(
            run, manifest_case.get("gold_review"), f"{case['case_id']}:gold_review"
        )
    gate = "failed_stop" if failures else "continue_to_counterfactual_pilot"
    return {
        "schema_version": "verilogcause_baseline_report.v1",
        "experiment_scope": contract["experiment_scope"],
        "inventory_sha256": inventory["inventory_sha256"],
        "implementation_sha256": implementation,
        "method_contract": {
            "candidate_pool": "shared_frozen_candidate_universe",
            "trace_pool": "shared_fixed_four_workloads",
            "gold": "reviewed_set_valued",
            "tie_policy": "average_rank",
            "unrepresentable_policy": {"mrr": 0, "exam_percent": 100},
            "methods": {
                "ochiai_fixed_pool": "ef / sqrt((ef + nf) * (ef + ep)); zero if ef=0",
                "tarsel_formula_fixed_pool": "ef * sqrt(abs(ep - ef + nf - np))",
                "unweighted_exact_slice": "mapped_failing_trace_count / F",
            },
        },
        "input_hashes": {
            "top_level": {name: _sha256(path) for name, path in input_paths.items()},
            "candidate_universes": candidate_hashes,
            "causal_graphs": graph_hashes,
            "trace_pool": trace_hashes,
        },
        "case_count": len(cases),
        "cases": cases,
        "macro_metrics": {
            "all_38": _baseline_summary(cases),
            "reachable_25": _baseline_summary(
                [case for case in cases if case["gold_reachable"]]
            ),
            "unreachable_13": _baseline_summary(
                [case for case in cases if not case["gold_reachable"]]
            ),
            "by_design": _baseline_macros(cases, "design_id"),
            "by_family": _baseline_macros(cases, "family_id"),
        },
        "failures": sorted(set(failures)),
        "gate": gate,
        "next_action": (
            "run_counterfactual_pilot"
            if gate == "continue_to_counterfactual_pilot"
            else "fix_stage_two"
        ),
    }


def baselines(run: Path) -> dict[str, Any]:
    first = build_baseline_report(run)
    second = build_baseline_report(run)
    if _canonical_sha256(first) != _canonical_sha256(second):
        first["failures"].append("canonical_recomputation_mismatch")
        first["gate"] = "failed_stop"
        first["next_action"] = "do_not_train"
    report_path = run / "baseline_report.json"
    _write_json(report_path, first)
    gate_report = {
        "schema_version": "verilogcause_gate_report.v1",
        "decision": first["gate"],
        "next_action": first["next_action"],
        "artifacts": {
            name: _artifact(run / name, run)
            for name in (
                "dataset_contract.json",
                "manifest.jsonl",
                "samples.jsonl",
                "evaluator_labels.jsonl",
                "method_input_manifest.jsonl",
                "stage1_scope.json",
                "relation_diagnostic.json",
                "baseline_report.json",
            )
        },
    }
    _write_json(run / "gate_report.json", gate_report)
    return gate_report


def _training_inputs(run: Path) -> dict[str, Any]:
    contract = validate_run_contract(run)
    gate_path = run / "gate_report.json"
    if not gate_path.is_file():
        raise VerilogCauseError("baseline gate report is missing")
    gate = json.loads(gate_path.read_text(encoding="utf-8"))
    if (
        gate.get("decision") != "continue_to_train"
        or gate.get("next_action") != "train_full_41"
    ):
        raise VerilogCauseError("baseline gate does not authorize training")
    for name in (
        "dataset_contract.json",
        "manifest.jsonl",
        "samples.jsonl",
        "evaluator_labels.jsonl",
        "relation_diagnostic.json",
        "baseline_report.json",
    ):
        _checked_run_artifact(run, (gate.get("artifacts") or {}).get(name), name)
    baseline = json.loads((run / "baseline_report.json").read_text(encoding="utf-8"))
    if (
        baseline.get("gate") != "continue_to_train"
        or baseline.get("implementation_sha256") != contract["implementation_sha256"]
        or baseline.get("inventory_sha256")
        != contract["corpus_inventory"]["inventory_sha256"]
    ):
        raise VerilogCauseError("baseline report identity is stale")

    manifest = _read_jsonl(run / "manifest.jsonl")
    cases = _validate_manifest_scope(contract, manifest)
    samples = _read_jsonl(run / "samples.jsonl")
    labels = _read_jsonl(run / "evaluator_labels.jsonl")
    by_case: dict[str, list[dict[str, Any]]] = defaultdict(list)
    label_by_key = {}
    for row in samples:
        features = row.get("features") or {}
        if (
            tuple(features) != FEATURES
            or any(
                not isinstance(value, (int, float)) or not math.isfinite(value)
                for value in features.values()
            )
            or any(word in json.dumps(row).lower() for word in LEAKAGE_WORDS)
        ):
            raise VerilogCauseError("trainer-visible sample is incomplete or leaked")
        by_case[row["bug_id"]].append(row)
    for row in labels:
        key = (row["bug_id"], row["artifact_id"], row["statement_id"])
        if key in label_by_key or row.get("is_gold") not in {True, False}:
            raise VerilogCauseError("evaluator label map is duplicated or incomplete")
        label_by_key[key] = row["is_gold"]

    baseline_by_case = {row["case_id"]: row for row in baseline["cases"]}
    if set(by_case) != set(baseline_by_case) or len(baseline_by_case) != len(cases):
        raise VerilogCauseError("training case join is incomplete")
    for case in cases:
        case_id = case["case_id"]
        sample_keys = {
            (row["artifact_id"], row["statement_id"]) for row in by_case[case_id]
        }
        baseline_keys = {
            (row["artifact_id"], row["statement_id"])
            for row in baseline_by_case[case_id]["candidates"]
        }
        labels_for_case = {
            (artifact_id, statement_id): is_gold
            for (bug_id, artifact_id, statement_id), is_gold in label_by_key.items()
            if bug_id == case_id
        }
        positives = sum(labels_for_case.values())
        if (
            sample_keys != baseline_keys
            or set(labels_for_case) != sample_keys
            or (case.get("gold_representable") is True and not positives)
            or (case.get("gold_representable") is False and positives)
            or positives == len(sample_keys)
        ):
            raise VerilogCauseError(f"{case_id}: training candidate/label join is invalid")
    return {
        "contract": contract,
        "manifest": manifest,
        "cases": cases,
        "samples": samples,
        "by_case": by_case,
        "label_by_key": label_by_key,
        "baseline": baseline,
        "baseline_by_case": baseline_by_case,
    }


def averaged_pairwise_weights(
    samples: Sequence[dict[str, Any]],
    label_by_key: dict[tuple[str, str, str], bool],
    train_bug_ids: set[str],
    method: str,
    epochs: int = W4_EPOCHS,
) -> list[float]:
    selected = {FEATURES.index(name) for name in W4_FEATURES[method]}
    by_bug: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for row in samples:
        if row["bug_id"] in train_bug_ids:
            by_bug[row["bug_id"]].append(row)
    pairs = {}
    for bug_id, rows in sorted(by_bug.items()):
        positive = [
            row
            for row in rows
            if label_by_key[(bug_id, row["artifact_id"], row["statement_id"])]
        ]
        negative = [row for row in rows if row not in positive]
        if not positive:
            continue
        if not negative:
            raise VerilogCauseError(f"{bug_id}: training bug has no negative candidate")
        scale = 1 / (len(positive) * len(negative))
        pairs[bug_id] = [
            (
                [
                    left["features"][name] - right["features"][name]
                    for name in FEATURES
                ],
                scale,
            )
            for left in positive
            for right in negative
        ]
    if not pairs:
        raise VerilogCauseError("training fold has no positive-negative pair")
    weights = [0.0] * len(FEATURES)
    total = [0.0] * len(FEATURES)
    steps = 0
    for _ in range(epochs):
        for bug_pairs in pairs.values():
            update = [0.0] * len(FEATURES)
            for delta, scale in bug_pairs:
                if sum(weights[index] * delta[index] for index in selected) <= 0:
                    for index in selected:
                        update[index] += scale * delta[index]
            weights = [value + delta for value, delta in zip(weights, update)]
            total = [value + weight for value, weight in zip(total, weights)]
            steps += 1
    return [value / steps for value in total]


def _fold_specs(
    cases: Sequence[dict[str, Any]], manifest: Sequence[dict[str, Any]]
) -> list[dict[str, Any]]:
    case_by_id = {row["case_id"]: row for row in cases}
    traces: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for row in manifest:
        if row.get("record_type") == "trace":
            traces[row["case_id"]].append(row)
    specs = []
    for protocol, field in W4_SPLITS.items():
        for held_out in sorted({row[field] for row in cases}):
            test = {row["case_id"] for row in cases if row[field] == held_out}
            train = set(case_by_id) - test
            train_fst = {
                row["fst"]["sha256"] for bug in train for row in traces[bug]
            }
            test_fst = {row["fst"]["sha256"] for bug in test for row in traces[bug]}
            if (
                not train
                or not test
                or train & test
                or train_fst & test_fst
                or {case_by_id[bug]["rtl_set_sha256"] for bug in train}
                & {case_by_id[bug]["rtl_set_sha256"] for bug in test}
            ):
                raise VerilogCauseError(f"{protocol}:{held_out}: train/test isolation failed")
            specs.append(
                {
                    "protocol": protocol,
                    "held_out_id": held_out,
                    "train_bug_ids": sorted(train),
                    "test_bug_ids": sorted(test),
                    "isolation": {
                        "bug_disjoint": True,
                        "trace_fst_disjoint": True,
                        "workload_identity_disjoint": not (
                            {
                                (bug, case_by_id[bug]["workload_pool_sha256"])
                                for bug in train
                            }
                            & {
                                (bug, case_by_id[bug]["workload_pool_sha256"])
                                for bug in test
                            }
                        ),
                        "rtl_set_disjoint": True,
                    },
                }
            )
    counts = {
        protocol: sum(row["protocol"] == protocol for row in specs)
        for protocol in W4_SPLITS
    }
    expected = {
        "lobo": len(cases),
        "lodo": len({row["design_id"] for row in cases}),
        "lofo": len({row["family_id"] for row in cases}),
    }
    lodo_sets = {
        tuple(row["test_bug_ids"]) for row in specs if row["protocol"] == "lodo"
    }
    lofo_sets = {
        tuple(row["test_bug_ids"]) for row in specs if row["protocol"] == "lofo"
    }
    if counts != expected or lodo_sets == lofo_sets:
        raise VerilogCauseError("W4 fold taxonomy is incomplete or isomorphic")
    return specs


def _metric_macro(rows: Sequence[dict[str, Any]]) -> dict[str, Any]:
    methods = (*W3_BASELINES, *W4_LEARNED_METHODS)
    metrics = ("mrr", "exam_percent", "top_1", "top_3", "top_5", "top_10")

    def mean(items: Sequence[dict[str, Any]]) -> dict[str, Any]:
        return {
            method: {
                metric: round(
                    fmean(row["metrics"][method][metric] for row in items), 6
                )
                for metric in metrics
            }
            for method in methods
        }

    def grouped(field: str) -> dict[str, Any]:
        return {
            group: mean([row for row in rows if row[field] == group])
            for group in sorted({row[field] for row in rows})
        }

    def macro(groups: dict[str, Any]) -> dict[str, Any]:
        return {
            method: {
                metric: round(
                    fmean(group[method][metric] for group in groups.values()), 6
                )
                for metric in metrics
            }
            for method in methods
        }

    by_design = grouped("design_id")
    by_family = grouped("family_id")
    return {
        "bug_count": len(rows),
        "bug_macro": mean(rows),
        "by_design": by_design,
        "design_macro": macro(by_design),
        "by_family": by_family,
        "family_macro": macro(by_family),
    }


def _strongest_baseline(lofo: dict[str, Any]) -> str:
    metrics = lofo["family_macro"]
    return min(
        W3_BASELINES,
        key=lambda method: (
            -metrics[method]["mrr"],
            metrics[method]["exam_percent"],
            W3_BASELINES.index(method),
        ),
    )


def _effect_gate(lofo: dict[str, Any], baseline: str) -> dict[str, Any]:
    learned = lofo["family_macro"]["ml_relation"]
    frozen = lofo["family_macro"][baseline]
    checks = {
        "mrr_strictly_higher": learned["mrr"] > frozen["mrr"],
        "exam_strictly_lower": learned["exam_percent"] < frozen["exam_percent"],
        "top_1_not_lower": learned["top_1"] >= frozen["top_1"],
        "top_3_not_lower": learned["top_3"] >= frozen["top_3"],
        "top_5_not_lower": learned["top_5"] >= frozen["top_5"],
        "each_family_mrr_not_lower": all(
            group["ml_relation"]["mrr"] >= group[baseline]["mrr"]
            for group in lofo["by_family"].values()
        ),
    }
    return {
        "protocol": "lofo",
        "aggregation": "family_macro",
        "baseline": baseline,
        "candidate": "ml_relation",
        "checks": checks,
        "passed": all(checks.values()),
    }


def _training_result(run: Path) -> dict[str, Any]:
    inputs = _training_inputs(run)
    contract = inputs["contract"]
    cases = inputs["cases"]
    samples = inputs["samples"]
    labels = inputs["label_by_key"]
    baseline_by_case = inputs["baseline_by_case"]
    input_hashes = {
        name: _sha256(run / name)
        for name in (
            "dataset_contract.json",
            "manifest.jsonl",
            "samples.jsonl",
            "evaluator_labels.jsonl",
            "relation_diagnostic.json",
            "baseline_report.json",
        )
    }
    models = {}
    fold_metrics = []
    specs = _fold_specs(cases, inputs["manifest"])
    for spec in specs:
        train_bug_ids = set(spec["train_bug_ids"])
        weights = {
            method: averaged_pairwise_weights(
                samples, labels, train_bug_ids, method
            )
            for method in W4_LEARNED_METHODS
        }
        model_path = f"models/{spec['protocol']}/{spec['held_out_id']}.json"
        models[model_path] = {
            "protocol": spec["protocol"],
            "held_out_id": spec["held_out_id"],
            "feature_order": list(FEATURES),
            "weights": weights,
            "epochs": W4_EPOCHS,
            "train_bug_ids": spec["train_bug_ids"],
            "test_bug_ids": spec["test_bug_ids"],
            "implementation_sha256": contract["implementation_sha256"],
            "vca_commit": contract["implementation_identity"]["vca_commit"],
            "vca_source_sha256": contract["implementation_identity"][
                "vca_source_sha256"
            ],
            "input_hashes": input_hashes,
        }
        for bug_id in spec["test_bug_ids"]:
            case = next(row for row in cases if row["case_id"] == bug_id)
            case_samples = inputs["by_case"][bug_id]
            gold = {
                (row["artifact_id"], row["statement_id"])
                for row in case_samples
                if labels[(bug_id, row["artifact_id"], row["statement_id"])]
            }
            learned_metrics = {}
            for method in W4_LEARNED_METHODS:
                scores = {
                    (row["artifact_id"], row["statement_id"]): sum(
                        weight * row["features"][name]
                        for weight, name in zip(weights[method], FEATURES)
                    )
                    for row in case_samples
                }
                learned_metrics[method] = set_valued_metrics(
                    scores,
                    gold,
                    representable=case.get("gold_representable") is True,
                )
            fold_metrics.append(
                {
                    "protocol": spec["protocol"],
                    "held_out_id": spec["held_out_id"],
                    "test_bug_id": bug_id,
                    "design_id": case["design_id"],
                    "family_id": case["family_id"],
                    "gold_representable": case.get("gold_representable") is True,
                    "candidate_count": len(case_samples),
                    "metrics": {
                        **baseline_by_case[bug_id]["metrics"],
                        **learned_metrics,
                    },
                }
            )
    protocols = {
        protocol: {
            "fold_count": len(
                {
                    row["held_out_id"]
                    for row in fold_metrics
                    if row["protocol"] == protocol
                }
            ),
            **_metric_macro(
                [row for row in fold_metrics if row["protocol"] == protocol]
            ),
        }
        for protocol in W4_SPLITS
    }
    strongest = _strongest_baseline(protocols["lofo"])
    effect = _effect_gate(protocols["lofo"], strongest)
    summary = {
        "schema_version": "verilogcause_summary.v1",
        "experiment_scope": contract["experiment_scope"],
        "implementation_sha256": contract["implementation_sha256"],
        "inventory_sha256": contract["corpus_inventory"]["inventory_sha256"],
        "input_hashes": input_hashes,
        "training_contract": {
            "epochs": W4_EPOCHS,
            "features": list(FEATURES),
            "method_features": {
                method: list(names) for method, names in W4_FEATURES.items()
            },
            "pair_normalization": "bug_then_positive_negative_pair",
            "splits": list(W4_SPLITS),
        },
        "case_count": len(cases),
        "representable_case_count": sum(
            row.get("gold_representable") is True for row in cases
        ),
        "unrepresentable_case_count": sum(
            row.get("gold_representable") is False for row in cases
        ),
        "fold_isolation": [
            {
                "protocol": row["protocol"],
                "held_out_id": row["held_out_id"],
                **row["isolation"],
            }
            for row in specs
        ],
        "model_payload_hashes": {
            path: _canonical_sha256(payload) for path, payload in models.items()
        },
        "fold_metrics_canonical_sha256": _canonical_sha256(fold_metrics),
        "protocols": protocols,
        "strongest_baseline_selection": {
            "method": strongest,
            "rule": "lofo_family_macro_mrr_desc_exam_asc_fixed_method_order",
        },
        "primary_effect_gate": effect,
        "decision": "continue" if effect["passed"] else "failed_stop",
    }
    return {"models": models, "fold_metrics": fold_metrics, "summary": summary}


def train(run: Path) -> dict[str, Any]:
    run = run.resolve()
    first = _training_result(run)
    second = _training_result(run)
    if _canonical_sha256(first) != _canonical_sha256(second):
        raise VerilogCauseError("training canonical recomputation mismatch")
    models_dir = run / "models"
    if models_dir.exists():
        raise VerilogCauseError("training artifacts already exist")
    for relative, payload in first["models"].items():
        _write_json(run / relative, payload)
    _write_jsonl(run / "fold_metrics.jsonl", first["fold_metrics"])
    _write_json(run / "summary.json", first["summary"])
    gate_report = {
        "schema_version": "verilogcause_gate_report.v1",
        "decision": first["summary"]["decision"],
        "next_action": (
            "report_full_41_cross_family_increment"
            if first["summary"]["decision"] == "continue"
            else "do_not_tune_or_expand"
        ),
        "artifacts": {
            "baseline_report.json": _artifact(run / "baseline_report.json", run),
            "fold_metrics.jsonl": _artifact(run / "fold_metrics.jsonl", run),
            "summary.json": _artifact(run / "summary.json", run),
            "models": {
                relative: _artifact(run / relative, run)
                for relative in first["models"]
            },
        },
    }
    _write_json(run / "gate_report.json", gate_report)
    return gate_report


def main(argv: Sequence[str] | None = None) -> None:
    parser = argparse.ArgumentParser(description="Prepare native VerilogCause data")
    actions = parser.add_subparsers(dest="action", required=True)
    prepare_parser = actions.add_parser("prepare")
    prepare_parser.add_argument("--run", required=True)
    prepare_parser.add_argument("--corpus", required=True)
    prepare_parser.add_argument("--families", required=True)
    prepare_parser.add_argument("--timeout-seconds", type=float, default=120)
    candidates_parser = actions.add_parser("candidates")
    candidates_parser.add_argument("--run", required=True)
    pilot_parser = actions.add_parser("pilot")
    pilot_parser.add_argument("--run", required=True)
    baseline_parser = actions.add_parser("baselines")
    baseline_parser.add_argument("--run", required=True)
    counterfactual_parser = actions.add_parser("counterfactual-pilot")
    counterfactual_parser.add_argument("--run", required=True)
    counterfactual_parser.add_argument("--timeout-seconds", type=float, default=120)
    stage5_parser = actions.add_parser("stage5")
    stage5_parser.add_argument("--run", required=True)
    stage5_parser.add_argument("--timeout-seconds", type=float, default=120)
    train_parser = actions.add_parser("train")
    train_parser.add_argument("--run", required=True)
    args = parser.parse_args(argv)
    if args.action == "candidates":
        run = Path(args.run).resolve()
        build_candidates(run)
        print(json.dumps({"run": str(run), "decision": "stop_for_codex_gold_review"}, sort_keys=True))
        return
    if args.action == "pilot":
        print(json.dumps(pilot(Path(args.run).resolve()), sort_keys=True))
        return
    if args.action == "baselines":
        print(json.dumps(baselines(Path(args.run).resolve()), sort_keys=True))
        return
    if args.action == "counterfactual-pilot":
        print(
            json.dumps(
                counterfactual_pilot(
                    Path(args.run).resolve(), args.timeout_seconds
                ),
                sort_keys=True,
            )
        )
        return
    if args.action == "stage5":
        print(
            json.dumps(
                stage5_evidence(Path(args.run).resolve(), args.timeout_seconds),
                sort_keys=True,
            )
        )
        return
    if args.action == "train":
        print(json.dumps(train(Path(args.run).resolve()), sort_keys=True))
        return
    families = tuple(item.strip() for item in args.families.split(",") if item.strip())
    if not families:
        parser.error("--families must not be empty")
    run = prepare(Path(args.run), Path(args.corpus), families, args.timeout_seconds)
    case_rows = [
        row
        for row in _read_jsonl(run / "manifest.jsonl")
        if row["record_type"] == "case"
    ]
    decision = (
        "stop_for_codex_gold_review"
        if case_rows
        and all(row.get("contrast_status") == "contrast_complete" for row in case_rows)
        else "failed_stop"
    )
    print(json.dumps({"run": str(run), "decision": decision}, sort_keys=True))


if __name__ == "__main__":
    main()
