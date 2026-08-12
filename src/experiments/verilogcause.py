"""Run-local native-Verilog corpus preparation for VerilogCause."""

from __future__ import annotations

import argparse
import csv
import difflib
import hashlib
import json
import math
import re
import shutil
import subprocess
import time
from collections import defaultdict
from datetime import datetime, timezone
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
_DUT_CLOCK = "testbench.DUT.clk"
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
        or any(len(cell) != width or set(cell) - {48, 49} for cell, width in zip(cells, widths))
        for row in rows
    ):
        raise VerilogCauseError("trigger row does not match metadata input widths")
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


def _waveform_endpoint_signal(waveform: Any, public_signal: str) -> str:
    public = [
        row
        for name, row in waveform.signals.by_name.items()
        if re.sub(r"\s*\[\d+:\d+\]$", "", name) == f"testbench.{public_signal}"
    ]
    if len(public) != 1:
        raise VerilogCauseError(f"public endpoint is not exact: {public_signal}")
    aliases = [
        name
        for name, row in waveform.signals.by_name.items()
        if row.handle == public[0].handle and name.startswith("testbench.DUT.")
    ]
    if len(aliases) != 1:
        raise VerilogCauseError(f"DUT endpoint alias is not exact: {public_signal}")
    return aliases[0]


def compare_waveforms(
    correct_fst: Path, faulty_fst: Path, public_signals: Sequence[str]
) -> dict[str, Any]:
    from verilog_causal_analysis.cycle_waveform import CycleAlignedWaveform

    with CycleAlignedWaveform(
        str(correct_fst), _DUT_CLOCK, exact_clock=True
    ) as correct, CycleAlignedWaveform(
        str(faulty_fst), _DUT_CLOCK, exact_clock=True
    ) as faulty:
        if correct.get_cycle_count() != faulty.get_cycle_count():
            raise VerilogCauseError("waveforms have different DUT clock cycle counts")
        signal_pairs = [
            (
                signal,
                _waveform_endpoint_signal(correct, signal),
                _waveform_endpoint_signal(faulty, signal),
            )
            for signal in public_signals
        ]
        for cycle in range(correct.get_cycle_count()):
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
                    return {
                        "outcome": "failing",
                        "failure_endpoint": endpoint,
                        "first_divergence": endpoint,
                    }
    return {
        "outcome": "passing",
        "failure_endpoint": None,
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
) -> tuple[set[tuple[str, str]], bool]:
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
    representable = review.get("gold_representable")
    gold = {
        (row.get("artifact_id"), row.get("statement_id")) for row in review.get("gold", [])
    }
    if representable is True and (not gold or not gold <= universe.keys()):
        raise VerilogCauseError("representable gold does not exactly join candidates")
    if representable is False and gold:
        raise VerilogCauseError("unrepresentable gold must not name a candidate")
    if representable not in {True, False}:
        raise VerilogCauseError("gold representability is undecided")
    return gold, representable


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
        if row.get("gold_representable") is True and not row.get("gold_reachable"):
            failures.append(f"{case_id}:gold_unreachable")
        if not row.get("has_failing_trace") or not row.get("has_passing_trace"):
            failures.append(f"{case_id}:contrast_incomplete")
        if not row.get("graph_complete"):
            failures.append(f"{case_id}:graph_incomplete")
        if row.get("activation_diagnostic_count"):
            failures.append(f"{case_id}:activation_not_exact")
    return {
        "decision": "failed_stop" if failures else "continue_to_train",
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
                    f"family:{positive['family']}",
                    "design:sequential"
                    if positive["sequential"]
                    else "design:combinational",
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

    families = sorted(
        key.removeprefix("family:")
        for key in counts
        if key.startswith("family:")
    )
    by_family = {family: summarize(f"family:{family}") for family in families}
    return {
        "overall": summarize("overall"),
        "by_family": by_family,
        "by_design": {
            name: summarize(f"design:{name}")
            for name in ("combinational", "sequential")
        },
        "family_macro_win_rate": (
            fmean(row["win_rate"] for row in by_family.values())
            if by_family
            and all(row["win_rate"] is not None for row in by_family.values())
            else None
        ),
    }


def run_after_gate(report: dict[str, Any], action: Callable[[], Any]) -> Any:
    if report.get("decision") != "continue_to_train":
        raise VerilogCauseError("relation gate is not open")
    return action()


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
            fsts["original_faulty"], fsts["sanitized_faulty"], public_signals
        )
        if equivalent["outcome"] != "passing":
            raise PreparationError(
                "sanitizer_mismatch", "sanitizer changed cycle-end external output"
            )
        comparison = compare_waveforms(
            fsts["correct"], fsts["sanitized_faulty"], public_signals
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
            "clock": {"signal": _DUT_CLOCK, "edge": "rising"},
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
                    "clock": {"signal": _DUT_CLOCK, "edge": "rising"},
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
        "family_id": case["family"],
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
    cases = discover_cases(corpus, families)
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
            "implementation_identity": {
                "parent_commit": _git_head(Path.cwd()),
                "vca_commit": _git_head(Path("VerilogCausalAnalysis")),
            },
            "simulator": {"name": "verilator", "version": version, "flags": list(VERILATOR_FLAGS)},
            "trace_generation": {
                "seed": 0,
                "budget_per_case": 4,
                "source": "gold_blind_trigger_first_zero_one",
                "deduplication": "sha256",
                "endpoint_sampling": _ENDPOINT_SAMPLING,
                "clock": {"signal": _DUT_CLOCK, "edge": "rising"},
            },
            "sanitized_rtl_policy": "strip_all_comments_preserve_noncomment_bytes_and_newlines",
            "gold_review_policy": "proposal_then_codex_review",
            "candidate_schema": "rtl_candidate_universe.v2",
            "feature_schema": list(FEATURES),
            "split_policy": ["lobo", "lodo", "lofo"],
            "tie_policy": "average_rank",
            "unreachable_policy": {"mrr": 0, "exam_percent": 100},
            "methods": [
                "wit_hw_ochiai",
                "tarsel",
                "static_backward",
                "causal_consistency_rule",
                "coverage_only",
                "causal_only",
                "ml_relation",
            ],
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
                    "family_id": case["family"],
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


def _vca_request(run: Path, trace: dict[str, Any], rtl: dict[str, Any]):
    from verilog_causal_analysis import make_request, policy_identity
    from verilog_causal_analysis.cycle_waveform import CycleAlignedWaveform

    fst = run / trace["fst"]["path"]
    oracle = trace.get("oracle") or {}
    if (
        _sha256(fst) != trace["fst"]["sha256"]
        or
        oracle.get("endpoint_sampling") != _ENDPOINT_SAMPLING
        or oracle.get("clock") != {"signal": _DUT_CLOCK, "edge": "rising"}
        or oracle.get("faulty_fst_sha256") != trace["fst"]["sha256"]
    ):
        raise VerilogCauseError("trace endpoint sampling contract is incomplete")
    with CycleAlignedWaveform(str(fst), _DUT_CLOCK, exact_clock=True) as waveform:
        endpoint_signal = _waveform_endpoint_signal(
            waveform, trace["failure_endpoint"]["signal"]
        )
        if waveform.get_signal_value(
            endpoint_signal, trace["failure_endpoint"]["cycle"]
        ) != trace["failure_endpoint"]["faulty"]:
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
        clock={"signal": _DUT_CLOCK, "edge": "rising"},
        endpoint={
            "signal": endpoint_signal,
            "cycle": trace["failure_endpoint"]["cycle"],
            "projection": None,
        },
        semantic_inputs=[],
        search_policy=policy_identity().to_dict(),
        bounds=VCA_BOUNDS,
        random_seed=0,
        strict=True,
    )


def build_candidates(run: Path) -> None:
    from verilog_causal_analysis import build_rtl_candidates

    rows = _read_jsonl(run / "manifest.jsonl")
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
        request = _vca_request(run, failing[0], rtl)
        candidates = build_rtl_candidates(request)
        if candidates.get("schema_version") != "rtl_candidate_universe.v2":
            raise VerilogCauseError("VCA did not produce rtl_candidate_universe.v2")
        path = model / "rtl_candidates.json"
        _write_json(path, candidates)
        row["candidate_universe"] = _artifact(path, run)
        row["vca_request_sha256"] = request.request_sha256
        row["candidate_trace_id"] = failing[0]["trace_id"]
        failing[0]["vca_endpoint_signal"] = request.endpoint.signal
    _write_jsonl(run / "manifest.jsonl", rows)


def pilot(run: Path) -> dict[str, Any]:
    from verilog_causal_analysis import build_causal_graph

    rows = _read_jsonl(run / "manifest.jsonl")
    traces: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for trace in (row for row in rows if row["record_type"] == "trace"):
        traces[trace["case_id"]].append(trace)
    samples = []
    labels = []
    diagnostic_rows = []
    for case in (row for row in rows if row["record_type"] == "case"):
        case_id = case["case_id"]
        case_traces = traces[case_id]
        if case.get("contrast_status") != "contrast_complete":
            diagnostic_rows.append(
                {
                    "case_id": case_id,
                    "family": case["family_id"],
                    "status": "contrast_incomplete",
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
        gold, representable = join_reviewed_gold(
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
            request = _vca_request(run, trace, rtl)
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
                "gold_representable": representable,
                "gold_reachable": reachable,
                "gold_review": _artifact(review_path, run),
                "candidate_universe": _artifact(candidates_path, run),
            }
        )
        diagnostic_rows.append(
            {
                "case_id": case_id,
                "family": case["family_id"],
                "status": "complete",
                "gold_representable": representable,
                "gold_reachable": reachable,
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
                "family": case_by_id[row["bug_id"]]["family_id"],
                "sequential": case_by_id[row["bug_id"]]["sequential"],
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
    if len(diagnostic_rows) != 13:
        gate["failures"].append(f"pilot_case_count:{len(diagnostic_rows)}")
    if sum(leakage.values()):
        gate["failures"].append("trainer_visible_label_leak")
    for family in ("alu", "counter", "fsm_16"):
        rate = (pairwise["by_family"].get(family) or {}).get("win_rate")
        if rate is None or rate <= 0.5:
            gate["failures"].append(
                f"{family}:pairwise_win_rate_not_above_half"
            )
    gate["failures"] = sorted(set(gate["failures"]))
    gate["decision"] = "failed_stop" if gate["failures"] else "continue_to_train"
    diagnostic = {
        "schema_version": "verilogcause_relation_diagnostic.v1",
        "case_count": len(diagnostic_rows),
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
                "relation_diagnostic.json",
            )
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
    args = parser.parse_args(argv)
    if args.action == "candidates":
        run = Path(args.run).resolve()
        build_candidates(run)
        print(json.dumps({"run": str(run), "decision": "stop_for_codex_gold_review"}, sort_keys=True))
        return
    if args.action == "pilot":
        print(json.dumps(pilot(Path(args.run).resolve()), sort_keys=True))
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
