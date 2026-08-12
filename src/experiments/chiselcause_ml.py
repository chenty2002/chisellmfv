"""Run-local data preparation and ranking for ChiselCause ML."""

from __future__ import annotations

import argparse
import csv
import json
import re
from collections import defaultdict
from pathlib import Path
from statistics import fmean
from typing import Any, Callable, Sequence

from src.experiments.chiselcause_exp import (
    ChiselCauseExperimentError,
    _read_vcd,
    _sha256,
    _unique_vcd_code,
    _write_json,
    _write_jsonl,
)
from src.experiments.paper import BUG_COUNTS, DEVELOPMENT_FAMILIES, _variant_parameters


ML_DESIGNS = (*DEVELOPMENT_FAMILIES, "alu", "decoder_3_to_8")
DESIGN_FAMILIES = {
    "counter": "sequential_control",
    "fsm_16": "sequential_control",
    "i2c": "sequential_control",
    "alu": "combinational",
    "decoder_3_to_8": "combinational",
}
FAILING_TRACE_BUDGET = 2
FEATURES = (
    "positive_authority",
    "distance_score",
    "max_contribution",
    "evidence_count",
    "near_failure",
    "sequential_ratio",
    "is_runtime",
    "is_update",
    "is_declaration",
)
METRICS = ("mrr", "exam_percent", "top_1", "top_3", "top_5")
ABLATIONS = {
    "ml_dynamic_only": tuple(range(6)),
    "ml_static_only": tuple(range(6, 9)),
    "ml_drop_positive_authority": tuple(range(1, 9)),
}
METHODS = ("d1", "deterministic_multi_trace", "ml", *ABLATIONS)
PERCEPTRON_EPOCHS = 20
PAIR_LOSS_NORMALIZATION = "bug_then_trace_then_negative"
SPLITS = {
    "lobo": "bug_id",
    "lodo": "design_id",
    "lofo": "family_id",
}
GOLD_TASKS = Path("runs/specflow-paper/20260808-c4-r-complete-formula-v1/tasks.json")
CASE_ID = re.compile(
    rf"^((?:{'|'.join(map(re.escape, ML_DESIGNS))})-\d+)-(?:base|w\d{{2}})$"
)


def write_configs(run: Path, repo: Path) -> list[Path]:
    output = run / "input-configs"
    paths = []
    for family in ML_DESIGNS:
        clean = json.loads(
            (repo / f"benchmark/synth/{family}/specflow/configs/cfg_000.json").read_text(
                encoding="utf-8"
            )
        )
        family_dir = output / family
        family_dir.mkdir(parents=True, exist_ok=True)
        clean["configuration_id"] = f"ml_{family}_0"
        clean_path = family_dir / "clean.json"
        _write_json(clean_path, clean)
        paths.append(clean_path)
        for index in range(1, BUG_COUNTS[family] + 1):
            path = family_dir / f"{family}-{index}.json"
            _write_json(
                path,
                {
                    "schema_version": clean["schema_version"],
                    "configuration_id": f"ml_{family}_{index}",
                    "parameters": _variant_parameters(family, index),
                },
            )
            paths.append(path)
    return paths


def write_witnesses(cases_path: Path, output: Path) -> list[Path]:
    payload = json.loads(cases_path.read_text(encoding="utf-8"))
    cases = payload.get("cases", [])
    if len(cases) != 1 or cases[0].get("status") != "complete":
        raise ChiselCauseExperimentError("one complete baseline case is required")
    case = cases[0]
    if case.get("formal", {}).get("outcome") != "cex":
        raise ChiselCauseExperimentError("baseline case must contain a formal CEX")

    clock = case["endpoint_projection"]["clock_signal"].rsplit(".", 1)[-1]
    functional_inputs = sorted(
        (
            {"name": row["name"], "width": row["width"]}
            for row in case["interface"]["inputs"]
            if row["name"] not in {clock, "reset"}
        ),
        key=lambda row: row["name"],
    )
    if not functional_inputs:
        raise ChiselCauseExperimentError("baseline case has no functional inputs")

    vcd = cases_path.parent / case["artifacts"]["vcd"]["path"]
    baseline = _sample_inputs(
        vcd,
        functional_inputs,
        clock,
        case["cex"]["failure_cycle"],
    )
    output.mkdir(parents=True, exist_ok=True)
    _write_json(
        output / "witness.json",
        {
            "signals": [row["name"] for row in functional_inputs],
            "widths": [row["width"] for row in functional_inputs],
        },
    )

    paths = []
    zeros = [0] * len(functional_inputs)
    for delay in range(8):
        path = output / f"w{delay:02d}.csv"
        _write_csv(path, [zeros] * delay + baseline)
        paths.append(path)

    tail = baseline[-4:]
    cells = [
        (cycle, signal)
        for cycle in range(len(tail) - 1)
        for signal in range(len(functional_inputs))
    ]
    if not cells:
        raise ChiselCauseExperimentError("baseline needs a pre-failure input cycle")
    for offset in range(8):
        rows: list[list[int | str]] = [
            ["*"] * len(functional_inputs) for _ in tail
        ]
        cycle, signal = cells[offset % len(cells)]
        rows[cycle][signal] = (tail[cycle][signal] + 1) % (
            2 ** functional_inputs[signal]["width"]
        )
        path = output / f"w{offset + 8:02d}.csv"
        _write_csv(path, rows)
        paths.append(path)
    return paths


def _sample_inputs(
    vcd: Path,
    inputs: Sequence[dict[str, Any]],
    clock: str,
    failure_cycle: int,
) -> list[list[int]]:
    definitions, samples = _read_vcd(vcd)
    clock_code = _unique_vcd_code(definitions, f"ChiselCauseMiter.{clock}")
    codes = [
        _unique_vcd_code(definitions, f"ChiselCauseMiter.{row['name']}")
        for row in inputs
    ]
    values: dict[str, str] = {}
    previous_clock = "x"
    rows = []
    for _, changes in samples:
        values.update(changes)
        current_clock = values.get(clock_code, "x")
        if previous_clock != "1" and current_clock == "1":
            row = [values.get(code, "x") for code in codes]
            if any(set(value) - {"0", "1"} for value in row):
                raise ChiselCauseExperimentError("baseline input contains X/Z at a sampled cycle")
            rows.append([int(value, 2) for value in row])
            if len(rows) == failure_cycle + 1:
                return rows
        previous_clock = current_clock
    raise ChiselCauseExperimentError("VCD ends before the failure cycle")


def _write_csv(path: Path, rows: Sequence[Sequence[int | str]]) -> None:
    with path.open("w", encoding="utf-8", newline="") as stream:
        csv.writer(stream, lineterminator="\n").writerows(rows)


def candidate_features(
    candidate: dict[str, Any], graph: dict[str, Any], failure_cycle: int
) -> list[float]:
    edges = {row["edge_id"]: row for row in graph["edges"]}
    cycles = {row["node_id"]: row["cycle"] for row in graph["signal_nodes"]}
    mapped = [
        edges[edge_id]
        for edge_id in candidate.get("authoritative_evidence_ids", [])
        if edge_id in edges
    ]
    near = any(
        cycles.get(edge[node]) in {failure_cycle - 1, failure_cycle}
        for edge in mapped
        for node in ("src_node_id", "dst_node_id")
    )
    distance = candidate.get("shortest_endpoint_distance")
    kind = candidate.get("statement_kind")
    return [
        float(candidate.get("positive_authoritative_evidence") is True),
        0.0 if distance is None else 1.0 / (1.0 + float(distance)),
        float(candidate.get("max_contribution_score", 0.0)),
        min(len(candidate.get("authoritative_evidence_ids", [])), 4) / 4.0,
        float(near),
        sum(edge.get("dependency_type") == "sequential" for edge in mapped)
        / len(mapped)
        if mapped
        else 0.0,
        float(candidate.get("execution_phase") == "runtime"),
        float(kind in {"assignment", "register_update", "table_update", "blackbox_parameter"}),
        float(kind == "declaration"),
    ]


def build_dataset(
    run: Path,
    case_root: Path,
    tasks_path: Path = GOLD_TASKS,
    replay_run: Path | None = None,
    designs: Sequence[str] = ML_DESIGNS,
) -> None:
    designs = tuple(designs)
    if not designs or len(designs) != len(set(designs)) or set(designs) - set(ML_DESIGNS):
        raise ChiselCauseExperimentError("dataset designs must be unique supported designs")
    tasks = json.loads(tasks_path.read_text(encoding="utf-8"))["tasks"]
    selected_tasks = {
        row["bug_id"]: row
        for row in tasks
        if row["family"] in designs
    }
    gold = {
        bug_id: row["hidden_evaluation"]["gold_source_location"]
        for bug_id, row in selected_tasks.items()
    }
    replay_source = replay_run or run
    replay = {
        row["case_id"]: row
        for row in _read_jsonl_if_present(replay_source / "replay_ledger.jsonl")
        if row.get("complete") is True
    }
    admitted: dict[str, int] = defaultdict(int)
    seen: set[tuple[str, str]] = set()
    manifest = []
    samples = []

    for cases_path in sorted(case_root.glob("*/cases.json")):
        for case in json.loads(cases_path.read_text(encoding="utf-8")).get("cases", []):
            match = CASE_ID.fullmatch(case.get("case_id", ""))
            if not match or match.group(1) not in gold:
                continue
            bug_id = match.group(1)
            if admitted[bug_id] == FAILING_TRACE_BUDGET:
                continue
            if case.get("status") != "complete" or case.get("formal", {}).get("outcome") != "cex":
                continue
            source_run = cases_path.parent
            case_id = case["case_id"]
            analysis = replay_source if case_id in replay else source_run
            analysis_dir = analysis / "raw" / case_id / "d1" / "d2_backward_v1"
            graph_path = analysis_dir / "causal_graph.json"
            ranking_path = analysis_dir / "source_ranking.json"
            fst_path = source_run / case["artifacts"]["fst"]["path"]
            faulty_path = source_run / case["artifacts"]["faulty_formal_rtl"]["path"]
            contrast_path = source_run / case["artifacts"]["trace_contrast"]["path"]
            if not all(
                path.is_file()
                for path in (graph_path, ranking_path, fst_path, faulty_path, contrast_path)
            ):
                continue
            graph = json.loads(graph_path.read_text(encoding="utf-8"))
            ranking = json.loads(ranking_path.read_text(encoding="utf-8"))
            contrast = json.loads(contrast_path.read_text(encoding="utf-8"))
            if (
                contrast.get("status") != "complete"
                or contrast.get("budget") != {"failing": 1, "passing": 1}
                or [row.get("outcome") for row in contrast.get("traces", [])]
                != ["failing", "passing"]
                or _sha256(contrast_path)
                != case["artifacts"]["trace_contrast"]["sha256"]
            ):
                raise ChiselCauseExperimentError("trace contrast contract is invalid")
            failing_trace, passing_trace = contrast["traces"]
            if (
                graph.get("status") != "complete"
                or ranking.get("status") != "complete"
                or ranking.get("complete_graph") is not True
                or ranking.get("complete_source_projection") is not True
            ):
                continue
            trace_sha = _sha256(fst_path)
            identity = (_sha256(faulty_path), trace_sha)
            if identity in seen:
                continue
            seen.add(identity)
            admitted[bug_id] += 1
            target = gold[bug_id]
            candidates = ranking["candidates"]
            matches = [
                row
                for row in candidates
                if row["file"] == target["path"]
                and row["line"] == target["line"]
            ]
            positive_matches = [
                row
                for row in matches
                if row.get("positive_authoritative_evidence") is True
            ]
            gold_row = positive_matches[0] if len(positive_matches) == 1 else None
            reachable = gold_row is not None
            manifest.append(
                {
                    "bug_id": bug_id,
                    "design_id": selected_tasks[bug_id]["family"],
                    "family_id": DESIGN_FAMILIES[selected_tasks[bug_id]["family"]],
                    "case_id": case_id,
                    "case_path": str(cases_path),
                    "faulty_rtl_sha256": identity[0],
                    "fst_path": str(fst_path),
                    "trace_sha256": trace_sha,
                    "failure_cycle": case["cex"]["failure_cycle"],
                    "analysis_path": str(analysis_dir),
                    "causal_graph_sha256": _sha256(graph_path),
                    "source_ranking_sha256": _sha256(ranking_path),
                    "trace_contrast_sha256": _sha256(contrast_path),
                    "first_mismatch_cycle": failing_trace["first_mismatch_cycle"],
                    "failing_slice_signature": failing_trace["slice_signature"],
                    "passing_slice_signature": passing_trace["slice_signature"],
                    "status": "complete",
                    "candidate_reachable": bool(matches),
                    "gold_reachable": reachable,
                    "gold_statement_id": gold_row["statement_id"] if gold_row else None,
                    "baseline": _metrics(gold_row["rank"], len(candidates))
                    if reachable
                    else None,
                }
            )
            for candidate in candidates:
                samples.append(
                    {
                        "bug_id": bug_id,
                        "trace_sha256": trace_sha,
                        "statement_id": candidate["statement_id"],
                        "d1_rank": candidate["rank"],
                        "features": candidate_features(
                            candidate, graph, case["cex"]["failure_cycle"]
                        ),
                        "is_gold": bool(
                            gold_row
                            and candidate["statement_id"] == gold_row["statement_id"]
                        ),
                    }
                )
    run.mkdir(parents=True, exist_ok=True)
    _write_json(
        run / "dataset_contract.json",
        {"designs": list(designs), "failing_trace_budget_per_bug": FAILING_TRACE_BUDGET},
    )
    _write_jsonl(run / "manifest.jsonl", manifest)
    _write_jsonl(run / "samples.jsonl", samples)


def averaged_perceptron(
    rows: Sequence[dict[str, Any]],
    epochs: int = PERCEPTRON_EPOCHS,
    feature_indices: Sequence[int] | None = None,
) -> list[float]:
    traces: dict[tuple[str, str], list[dict[str, Any]]] = defaultdict(list)
    for row in rows:
        traces[(row["bug_id"], row["trace_sha256"])].append(row)
    pairs_by_bug: dict[str, list[tuple[list[float], float]]] = defaultdict(list)
    valid_trace_count: dict[str, int] = defaultdict(int)
    for key in sorted(traces):
        candidates = traces[key]
        gold = [row for row in candidates if row["is_gold"]]
        negatives = sorted(
            (row for row in candidates if not row["is_gold"]),
            key=lambda row: row["statement_id"],
        )
        if len(gold) != 1 or not negatives:
            continue
        valid_trace_count[key[0]] += 1
        for negative in negatives:
            pairs_by_bug[key[0]].append(
                (
                    [g - n for g, n in zip(gold[0]["features"], negative["features"])],
                    1.0 / len(negatives),
                )
            )
    selected = set(feature_indices if feature_indices is not None else range(len(FEATURES)))
    weights = [0.0] * len(FEATURES)
    total = [0.0] * len(FEATURES)
    steps = 0
    for _ in range(epochs):
        for bug_id in sorted(pairs_by_bug):
            update = [0.0] * len(FEATURES)
            trace_scale = 1.0 / valid_trace_count[bug_id]
            for delta, negative_scale in pairs_by_bug[bug_id]:
                if sum(weights[index] * delta[index] for index in selected) <= 0:
                    for index in selected:
                        update[index] += trace_scale * negative_scale * delta[index]
            weights = [weight + value for weight, value in zip(weights, update)]
            total = [value + weight for value, weight in zip(total, weights)]
            steps += 1
    return [value / steps for value in total] if steps else weights


def train(run: Path) -> dict[str, Any]:
    manifest = _read_jsonl_if_present(run / "manifest.jsonl")
    samples = _read_jsonl_if_present(run / "samples.jsonl")
    by_trace: dict[tuple[str, str], list[dict[str, Any]]] = defaultdict(list)
    for row in samples:
        by_trace[(row["bug_id"], row["trace_sha256"])].append(row)
    models = run / "models"
    models.mkdir(parents=True, exist_ok=True)
    fold_metrics = []
    for protocol, split_field in SPLITS.items():
        protocol_models = models / protocol
        protocol_models.mkdir(exist_ok=True)
        for held_out in sorted({row[split_field] for row in manifest}):
            test_traces = [row for row in manifest if row[split_field] == held_out]
            test_bugs = {row["bug_id"] for row in test_traces}
            train_manifest = [row for row in manifest if row[split_field] != held_out]
            train_bugs = {row["bug_id"] for row in train_manifest}
            if train_bugs.intersection(test_bugs):
                raise ChiselCauseExperimentError("train/test folds share a bug")
            train_hashes = {row["trace_sha256"] for row in train_manifest}
            test_hashes = {row["trace_sha256"] for row in test_traces}
            if train_hashes.intersection(test_hashes):
                raise ChiselCauseExperimentError("train/test folds share an FST hash")
            train_keys = {
                (row["bug_id"], row["trace_sha256"])
                for row in train_manifest
                if row["status"] == "complete" and row["gold_reachable"] is True
            }
            train_rows = [
                row
                for row in samples
                if (row["bug_id"], row["trace_sha256"]) in train_keys
            ]
            weights_by_method = {"ml": averaged_perceptron(train_rows)}
            weights_by_method.update(
                {
                    method: averaged_perceptron(
                        train_rows, feature_indices=feature_indices
                    )
                    for method, feature_indices in ABLATIONS.items()
                }
            )
            _write_json(
                protocol_models / f"{held_out}.json",
                {
                    "protocol": protocol,
                    "held_out_id": held_out,
                    "features": list(FEATURES),
                    "epochs": PERCEPTRON_EPOCHS,
                    "pair_loss_normalization": PAIR_LOSS_NORMALIZATION,
                    "weights": weights_by_method["ml"],
                    "ablations": {
                        method: {
                            "feature_indices": list(ABLATIONS[method]),
                            "weights": weights,
                        }
                        for method, weights in weights_by_method.items()
                        if method in ABLATIONS
                    },
                    "train_bug_ids": sorted({row["bug_id"] for row in train_rows}),
                    "test_bug_ids": sorted(test_bugs),
                },
            )
            for test_bug in sorted(test_bugs):
                bug_traces = [row for row in test_traces if row["bug_id"] == test_bug]
                d1_scores = _mean_statement_scores(
                    bug_traces, by_trace, lambda row: -row["d1_rank"]
                )
                learned_scores = {
                    method: _mean_statement_scores(
                        bug_traces,
                        by_trace,
                        lambda row, weights=weights: sum(
                            weight * value
                            for weight, value in zip(weights, row["features"])
                        ),
                    )
                    for method, weights in weights_by_method.items()
                }
                for trace in bug_traces:
                    candidates = by_trace[(test_bug, trace["trace_sha256"])]
                    reachable = trace["gold_reachable"] is True
                    fold_metrics.append(
                        {
                            "protocol": protocol,
                            "held_out_id": held_out,
                            "test_bug_id": test_bug,
                            "design_id": trace["design_id"],
                            "family_id": trace["family_id"],
                            "case_id": trace["case_id"],
                            "trace_sha256": trace["trace_sha256"],
                            "candidate_reachable": trace["candidate_reachable"],
                            "authority_reachable": reachable,
                            "d1": _rank_metrics(
                                candidates,
                                {
                                    row["statement_id"]: -row["d1_rank"]
                                    for row in candidates
                                },
                                reachable,
                            ),
                            "deterministic_multi_trace": _rank_metrics(
                                candidates, d1_scores, reachable
                            ),
                            **{
                                method: _rank_metrics(
                                    candidates, scores, reachable
                                )
                                for method, scores in learned_scores.items()
                            },
                        }
                    )
    _write_jsonl(run / "fold_metrics.jsonl", fold_metrics)
    contract_path = run / "dataset_contract.json"
    designs = (
        json.loads(contract_path.read_text(encoding="utf-8"))["designs"]
        if contract_path.is_file()
        else ML_DESIGNS
    )
    summary = _summary(manifest, fold_metrics, designs)
    _write_json(run / "summary.json", summary)
    return summary


def _mean_statement_scores(
    traces: Sequence[dict[str, Any]],
    by_trace: dict[tuple[str, str], list[dict[str, Any]]],
    score: Callable[[dict[str, Any]], float],
) -> dict[str, float]:
    values: dict[str, list[float]] = defaultdict(list)
    for trace in traces:
        for candidate in by_trace[(trace["bug_id"], trace["trace_sha256"])]:
            values[candidate["statement_id"]].append(score(candidate))
    return {statement_id: fmean(scores) for statement_id, scores in values.items()}


def _rank_metrics(
    candidates: Sequence[dict[str, Any]],
    scores: dict[str, float],
    reachable: bool,
) -> dict[str, Any]:
    if not reachable:
        return {
            "gold_rank": None,
            "tie_size": None,
            "mrr": 0.0,
            "exam_percent": 100.0,
            "top_1": False,
            "top_3": False,
            "top_5": False,
        }
    gold_scores = [scores[row["statement_id"]] for row in candidates if row["is_gold"]]
    if not gold_scores:
        raise ChiselCauseExperimentError("reachable trace has no gold candidate")
    gold_score = max(gold_scores)
    tied = sum(scores[row["statement_id"]] == gold_score for row in candidates)
    rank = 1 + sum(scores[row["statement_id"]] > gold_score for row in candidates)
    rank += (tied - 1) / 2
    return {"tie_size": tied, **_metrics(rank, len(candidates))}


def _metrics(rank: float, count: int) -> dict[str, Any]:
    return {
        "gold_rank": rank,
        "mrr": round(1.0 / rank, 6),
        "exam_percent": round(100.0 * rank / count, 6),
        "top_1": rank <= 1,
        "top_3": rank <= 3,
        "top_5": rank <= 5,
    }


def _summary(
    manifest: Sequence[dict[str, Any]],
    folds: Sequence[dict[str, Any]],
    designs: Sequence[str] = ML_DESIGNS,
) -> dict[str, Any]:
    expected_bugs = {
        f"{family}-{index}"
        for family in designs
        for index in range(1, BUG_COUNTS[family] + 1)
    }
    observed_bugs = {row["bug_id"] for row in manifest}
    candidate = _recall(manifest, "candidate_reachable")
    authority = _recall(manifest, "gold_reachable")
    data_gate = bool(
        manifest
        and observed_bugs == expected_bugs
        and all(row["status"] == "complete" for row in manifest)
        and all(
            sum(row["bug_id"] == bug for row in manifest) == FAILING_TRACE_BUDGET
            for bug in observed_bugs
        )
        and candidate["bug"]["recalled"] == len(observed_bugs)
        and authority["bug"]["recalled"] == len(observed_bugs)
    )
    summary = {
        "implementation_sha256": _sha256(Path(__file__)),
        "training_contract": {
            "epochs": PERCEPTRON_EPOCHS,
            "failing_trace_budget_per_bug": FAILING_TRACE_BUDGET,
            "design_families": {
                design: DESIGN_FAMILIES[design] for design in designs
            },
            "pair_loss_normalization": PAIR_LOSS_NORMALIZATION,
            "ablations": {
                method: list(indices) for method, indices in ABLATIONS.items()
            },
        },
        "unique_trace_count": len(manifest),
        "candidate_recall": candidate,
        "positive_authority_recall": authority,
        "trace_count_by_bug": {
            bug_id: sum(row["bug_id"] == bug_id for row in manifest)
            for bug_id in sorted(observed_bugs)
        },
        "gold_reachable_by_bug": {
            bug_id: sum(
                row["bug_id"] == bug_id and row["gold_reachable"] for row in manifest
            )
            for bug_id in sorted(observed_bugs)
        },
        "failing_slice_signature_count_by_bug": {
            bug_id: len(
                {
                    row["failing_slice_signature"]
                    for row in manifest
                    if row["bug_id"] == bug_id
                }
            )
            for bug_id in sorted(observed_bugs)
        },
        "all_expected_bugs": observed_bugs == expected_bugs,
        "lodo_lofo_fold_identity_equivalent": all(
            row["design_id"] == row["family_id"] for row in manifest
        ),
        "protocols": {
            protocol: {
                "fold_count": len(
                    {
                        row["held_out_id"]
                        for row in folds
                        if row["protocol"] == protocol
                    }
                ),
                "train_test_bug_disjoint": True,
                "train_test_fst_disjoint": True,
                "end_to_end": _aggregate(
                    [row for row in folds if row["protocol"] == protocol]
                ),
                "reachable_only": _aggregate(
                    [
                        row
                        for row in folds
                        if row["protocol"] == protocol and row["authority_reachable"]
                    ]
                ),
            }
            for protocol in SPLITS
        },
        "data_gate_decision": "continue" if data_gate else "failed_stop",
    }
    family_macro = summary["protocols"]["lofo"]["end_to_end"]["family_macro"]
    baseline = family_macro.get("deterministic_multi_trace", {})
    learned = family_macro.get("ml", {})
    improves = bool(
        data_gate
        and learned.get("mrr", 0.0) > baseline.get("mrr", 0.0)
        and learned.get("exam_percent", 100.0)
        < baseline.get("exam_percent", 100.0)
        and all(
            learned.get(metric, 0.0) >= baseline.get(metric, 0.0)
            for metric in ("top_1", "top_3", "top_5")
        )
    )
    summary["p4_effectiveness"] = {
        "comparison_protocol": "lofo",
        "baseline": "deterministic_multi_trace",
        "candidate": "ml",
        "incremental_value": improves,
        "lodo_lofo_count_as_independent_evidence": not summary[
            "lodo_lofo_fold_identity_equivalent"
        ],
    }
    summary["decision"] = "continue" if improves else "failed_stop"
    return summary


def _recall(rows: Sequence[dict[str, Any]], field: str) -> dict[str, Any]:
    bugs = sorted({row["bug_id"] for row in rows})
    trace_hits = sum(row[field] is True for row in rows)
    bug_hits = sum(
        any(row["bug_id"] == bug and row[field] is True for row in rows)
        for bug in bugs
    )
    return {
        "trace": {
            "recalled": trace_hits,
            "total": len(rows),
            "rate": round(trace_hits / len(rows), 6) if rows else 0.0,
        },
        "bug": {
            "recalled": bug_hits,
            "total": len(bugs),
            "rate": round(bug_hits / len(bugs), 6) if bugs else 0.0,
        },
    }


def _aggregate(rows: Sequence[dict[str, Any]]) -> dict[str, Any]:
    methods = METHODS

    def mean(items: Sequence[dict[str, Any]]) -> dict[str, Any]:
        return {
            method: {
                metric: round(fmean(row[method][metric] for row in items), 6)
                for metric in METRICS
            }
            for method in methods
        }

    by_bug = {
        bug: mean([row for row in rows if row["test_bug_id"] == bug])
        for bug in sorted({row["test_bug_id"] for row in rows})
    }

    def grouped(field: str) -> dict[str, Any]:
        result = {}
        for group in sorted({row[field] for row in rows}):
            bugs = {row["test_bug_id"] for row in rows if row[field] == group}
            result[group] = {
                method: {
                    metric: round(
                        fmean(by_bug[bug][method][metric] for bug in bugs), 6
                    )
                    for metric in METRICS
                }
                for method in methods
            }
        return result

    design_metrics = grouped("design_id")
    family_metrics = grouped("family_id")

    def macro(groups: dict[str, Any]) -> dict[str, Any]:
        if not groups:
            return {method: {} for method in methods}
        return {
            method: {
                metric: round(
                    fmean(group[method][metric] for group in groups.values()), 6
                )
                for metric in METRICS
            }
            for method in methods
        }

    return {
        "trace_count": len(rows),
        "bug_count": len(by_bug),
        "bug_metrics": by_bug,
        "bug_macro": macro(by_bug),
        "design_metrics": design_metrics,
        "design_macro": macro(design_metrics),
        "family_metrics": family_metrics,
        "family_macro": macro(family_metrics),
    }


def _read_jsonl_if_present(path: Path) -> list[dict[str, Any]]:
    if not path.is_file():
        return []
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line]


def main(argv: Sequence[str] | None = None) -> None:
    parser = argparse.ArgumentParser(description="ChiselCause ML data preparation")
    actions = parser.add_subparsers(dest="action", required=True)
    configs_parser = actions.add_parser("configs")
    configs_parser.add_argument("--run", required=True)
    witnesses_parser = actions.add_parser("witnesses")
    witnesses_parser.add_argument("--case", required=True)
    witnesses_parser.add_argument("--out", required=True)
    dataset_parser = actions.add_parser("dataset")
    dataset_parser.add_argument("--run", required=True)
    dataset_parser.add_argument("--case-root", required=True)
    dataset_parser.add_argument("--replay-run")
    dataset_parser.add_argument("--design", action="append", choices=ML_DESIGNS)
    train_parser = actions.add_parser("train")
    train_parser.add_argument("--run", required=True)
    args = parser.parse_args(argv)

    if args.action == "configs":
        paths = write_configs(Path(args.run).resolve(), Path.cwd().resolve())
    elif args.action == "witnesses":
        paths = write_witnesses(Path(args.case).resolve(), Path(args.out).resolve())
    elif args.action == "dataset":
        run = Path(args.run).resolve()
        build_dataset(
            run,
            Path(args.case_root).resolve(),
            replay_run=Path(args.replay_run).resolve() if args.replay_run else None,
            designs=args.design or ML_DESIGNS,
        )
        paths = [run / "manifest.jsonl", run / "samples.jsonl"]
    else:
        summary = train(Path(args.run).resolve())
        print(json.dumps(summary, sort_keys=True))
        return
    print(json.dumps({"files": [str(path) for path in paths]}, sort_keys=True))


if __name__ == "__main__":
    main()
