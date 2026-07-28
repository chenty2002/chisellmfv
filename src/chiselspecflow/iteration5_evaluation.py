"""Hash-bound V7-4 evaluation freeze over completed V7-3 evidence."""

from __future__ import annotations

import hashlib
import json
from pathlib import Path
from typing import Any, Dict, Iterable, Mapping, Sequence


class Iteration5EvaluationError(RuntimeError):
    """Raised when an evaluation input is incomplete or not comparable."""


_REQUIRED_CASE_ARTIFACTS = (
    "trace_manifest.json",
    "elaboration_certificate.json",
    "property_result_map.json",
    "semantic_evidence.json",
    "evidence_projection.json",
    "causal_graph_manifest.json",
    "causal_source_projection.json",
    "causal_query_log.jsonl",
    "model_calls.jsonl",
    "candidate_attempts.jsonl",
    "diagnosis_transcript_manifest.json",
    "diagnosis_candidate.json",
    "diagnosis_review_request.json",
    "diagnosis_review.json",
    "root_cause_result.json",
    "source_ranking.json",
    "final_verdict.json",
    "stage_result.json",
    "model_evidence.json",
)
_MODEL_EVIDENCE_KINDS = {
    "deterministic_fake_model",
    "production_api_model",
}
_FROZEN_BUDGET = {
    "max_model_calls": 3,
    "max_evidence_queries": 2,
    "parallel_tool_calls": False,
}


def _read(path: Path) -> Dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise Iteration5EvaluationError(f"cannot read evaluation input {path}") from exc
    if not isinstance(value, dict):
        raise Iteration5EvaluationError(f"evaluation input is not an object: {path}")
    return value


def _read_jsonl(path: Path) -> list[Dict[str, Any]]:
    rows: list[Dict[str, Any]] = []
    try:
        for line_number, line in enumerate(
            path.read_text(encoding="utf-8").splitlines(), start=1
        ):
            if not line.strip():
                continue
            value = json.loads(line)
            if not isinstance(value, dict):
                raise ValueError("row is not an object")
            rows.append(value)
    except (OSError, json.JSONDecodeError, ValueError) as exc:
        raise Iteration5EvaluationError(
            f"cannot read evaluation JSONL {path}:{line_number if 'line_number' in locals() else 0}"
        ) from exc
    return rows


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _canonical_sha256(value: Any) -> str:
    payload = json.dumps(
        value, sort_keys=True, separators=(",", ":"), ensure_ascii=False
    ).encode("utf-8")
    return hashlib.sha256(payload).hexdigest()


def _stage_dirs(run: Path) -> tuple[Path, Path]:
    manifest = _read(run / "manifest.json")
    round_id = int(manifest["current_round"])
    round_dir = run / "rounds" / f"{round_id:04d}"
    return round_dir / "02_compile_verify", round_dir / "03_diagnose"


def _relative_to_workspace(workspace_root: Path, path: Path) -> str:
    try:
        return path.resolve().relative_to(workspace_root.resolve()).as_posix()
    except ValueError as exc:
        raise Iteration5EvaluationError(
            f"evaluation input is outside workspace: {path}"
        ) from exc


def _artifact_ref(run: Path, path: Path) -> Dict[str, str]:
    if not path.is_file():
        raise Iteration5EvaluationError(
            f"{run.name} lacks required durable artifact: {path.name}"
        )
    try:
        relative = path.relative_to(run).as_posix()
    except ValueError as exc:
        raise Iteration5EvaluationError(
            f"artifact is outside its run root: {path}"
        ) from exc
    return {"path": relative, "sha256": _sha256(path)}


def _validate_artifact_refs(run: Path, refs: Sequence[Mapping[str, Any]]) -> None:
    if not refs:
        raise Iteration5EvaluationError(f"{run.name} has no hash-bound artifact refs")
    for ref in refs:
        relative = ref.get("path")
        expected = ref.get("sha256")
        if (
            not isinstance(relative, str)
            or Path(relative).is_absolute()
            or ".." in Path(relative).parts
            or not isinstance(expected, str)
        ):
            raise Iteration5EvaluationError(f"{run.name} has an invalid artifact ref")
        path = run / relative
        if not path.is_file() or _sha256(path) != expected:
            raise Iteration5EvaluationError(
                f"{run.name} artifact identity drifted: {relative}"
            )


def _bound_reached(value: Any) -> bool:
    if isinstance(value, Mapping):
        return any(
            (isinstance(item, bool) and item and str(key).endswith("_reached"))
            or _bound_reached(item)
            for key, item in value.items()
        )
    if isinstance(value, list):
        return any(_bound_reached(item) for item in value)
    return False


def _performance_value(
    performance: Mapping[str, Any], *keys: str
) -> tuple[Any, bool]:
    for key in keys:
        value = performance.get(key)
        if isinstance(value, (int, float)) and not isinstance(value, bool):
            return value, True
    return None, False


def _graph_metrics(
    run: Path, stage3: Path, manifest: Mapping[str, Any]
) -> tuple[Dict[str, Any], list[Dict[str, str]]]:
    graphs = []
    graph_refs: list[Dict[str, str]] = []
    parse_values: list[float] = []
    build_values: list[float] = []
    parse_complete = True
    build_complete = True
    for ref in manifest.get("graphs", []):
        graph_path = stage3 / str(ref.get("path", ""))
        graph = _read(graph_path)
        if (
            graph.get("graph_id") != ref.get("graph_id")
            or _sha256(graph_path) != ref.get("sha256")
        ):
            raise Iteration5EvaluationError(
                f"{run.name} causal graph identity drifted"
            )
        schema = graph.get("schema_version")
        if schema == "verilog_causal_semantic_graph.v1":
            signal_count = len(graph.get("signal_nodes", []))
            semantic_count = len(graph.get("semantic_nodes", []))
            node_count = signal_count + semantic_count
        elif schema == "verilog_causal_graph.v2":
            signal_count = None
            semantic_count = None
            node_count = len(graph.get("nodes", []))
        else:
            raise Iteration5EvaluationError(
                f"{run.name} has unsupported graph schema {schema!r}"
            )
        performance = graph.get("performance")
        if not isinstance(performance, Mapping):
            performance = {}
        parse_time, has_parse = _performance_value(
            performance, "parse_time_s", "parse_seconds"
        )
        build_time, has_build = _performance_value(
            performance, "build_time_s", "build_seconds", "elapsed_seconds"
        )
        parse_complete = parse_complete and has_parse
        build_complete = build_complete and has_build
        if has_parse:
            parse_values.append(float(parse_time))
        if has_build:
            build_values.append(float(build_time))
        bounds = graph.get("bounds", {})
        row = {
            "graph_id": ref["graph_id"],
            "schema_version": schema,
            "status": graph.get("status"),
            "node_count": node_count,
            "signal_node_count": signal_count,
            "semantic_node_count": semantic_count,
            "edge_count": len(graph.get("edges", [])),
            "bound_reached": _bound_reached(bounds),
            "frontier_diagnostic_count": sum(
                1
                for diagnostic in graph.get("diagnostics", [])
                if diagnostic.get("frontier") is True
            ),
        }
        graphs.append(row)
        graph_refs.append(_artifact_ref(run, graph_path))
    return (
        {
            "status": manifest.get("status"),
            "graphs": graphs,
            "truncated": any(row["bound_reached"] for row in graphs),
            "timing": {
                "parse_time_s": (
                    round(sum(parse_values), 6) if parse_complete and graphs else None
                ),
                "parse_time_available": bool(graphs) and parse_complete,
                "build_time_s": (
                    round(sum(build_values), 6) if build_complete and graphs else None
                ),
                "build_time_available": bool(graphs) and build_complete,
            },
        },
        graph_refs,
    )


def _query_metrics(stage3: Path) -> Dict[str, Any]:
    rows = _read_jsonl(stage3 / "causal_query_log.jsonl")
    values = [
        float(row["wall_time_s"])
        for row in rows
        if isinstance(row.get("wall_time_s"), (int, float))
        and not isinstance(row.get("wall_time_s"), bool)
    ]
    timing_complete = len(values) == len(rows)
    return {
        "calls": len(rows),
        "outcomes": [row.get("outcome") for row in rows],
        "wall_time_s": round(sum(values), 6) if timing_complete else None,
        "wall_time_available": timing_complete,
    }


def _case_artifact_refs(
    run: Path, stage2: Path, stage3: Path, graph_refs: Sequence[Mapping[str, str]]
) -> list[Dict[str, str]]:
    paths = [
        *(stage2 / name for name in _REQUIRED_CASE_ARTIFACTS[:4]),
        *(stage3 / name for name in _REQUIRED_CASE_ARTIFACTS[4:]),
    ]
    refs = [_artifact_ref(run, path) for path in paths]
    refs.extend(dict(ref) for ref in graph_refs)
    transcript = _read(stage3 / "diagnosis_transcript_manifest.json")
    for ref in transcript.get("query_results", []):
        path = stage3 / str(ref.get("path", ""))
        if _sha256(path) != ref.get("sha256"):
            raise Iteration5EvaluationError(
                f"{run.name} query result identity drifted"
            )
        refs.append(_artifact_ref(run, path))
    return refs


def _trace_universe(
    run: Path,
    result_map: Mapping[str, Any],
    trace_manifest: Mapping[str, Any],
    projection: Mapping[str, Any],
) -> list[str]:
    traces = trace_manifest.get("traces", [])
    trace_hashes = sorted(
        str(row["sha256"]) for row in traces if isinstance(row.get("sha256"), str)
    )
    projected_hashes = sorted(
        str(row.get("trace", {}).get("sha256"))
        for row in projection.get("traces", [])
        if isinstance(row.get("trace", {}).get("sha256"), str)
    )
    if not trace_hashes or projected_hashes != trace_hashes:
        raise Iteration5EvaluationError(
            f"{run.name} exact CEX projection/trace universe drifted"
        )
    exact_cex = [
        row
        for row in result_map.get("operation_results", [])
        if row.get("status") == "cex"
        and row.get("reason") == "tool_reported_cex_with_exact_trace"
    ]
    if not exact_cex:
        raise Iteration5EvaluationError(f"{run.name} lacks an exact CEX operation")
    return trace_hashes


def collect_case(
    workspace_root: Path,
    run: Path,
    *,
    case_id: str,
    case_kind: str,
    evidence_kind: str | None = None,
    expected_artifact_refs: Sequence[Mapping[str, Any]] | None = None,
) -> Dict[str, Any]:
    """Collect one reviewed real-formal case and revalidate every live artifact."""

    run = run.resolve()
    stage2, stage3 = _stage_dirs(run)
    if expected_artifact_refs is not None:
        _validate_artifact_refs(run, expected_artifact_refs)
    result_map = _read(stage2 / "property_result_map.json")
    trace_manifest = _read(stage2 / "trace_manifest.json")
    projection = _read(stage3 / "evidence_projection.json")
    graph_manifest = _read(stage3 / "causal_graph_manifest.json")
    source_projection = _read(stage3 / "causal_source_projection.json")
    transcript = _read(stage3 / "diagnosis_transcript_manifest.json")
    candidate = _read(stage3 / "diagnosis_candidate.json")
    review = _read(stage3 / "diagnosis_review.json")
    root = _read(stage3 / "root_cause_result.json")
    stage_result = _read(stage3 / "stage_result.json")
    model_evidence = _read(stage3 / "model_evidence.json")
    trace_hashes = _trace_universe(run, result_map, trace_manifest, projection)
    if (
        result_map.get("formal_outcome") != "cex"
        or result_map.get("tool", {}).get("name") != "jaspergold"
    ):
        raise Iteration5EvaluationError(f"{run.name} is not a real JasperGold CEX")
    if stage_result.get("status") != "completed":
        raise Iteration5EvaluationError(f"{run.name} diagnosis review is not completed")
    if (
        review.get("reviewer") != "codex"
        or review.get("decision") not in {"approved", "rejected"}
    ):
        raise Iteration5EvaluationError(f"{run.name} lacks Codex review authority")
    actual_evidence_kind = model_evidence.get("evidence_kind")
    if actual_evidence_kind not in _MODEL_EVIDENCE_KINDS:
        raise Iteration5EvaluationError(f"{run.name} model evidence is unclassified")
    if evidence_kind is not None and actual_evidence_kind not in evidence_kind:
        raise Iteration5EvaluationError(
            f"{run.name} evidence kind does not match the durable model record"
        )
    budget = transcript.get("budget", {})
    if any(budget.get(key) != value for key, value in _FROZEN_BUDGET.items()):
        raise Iteration5EvaluationError(f"{run.name} diagnosis budget drifted")
    graph, graph_refs = _graph_metrics(run, stage3, graph_manifest)
    query = _query_metrics(stage3)
    token_metrics = transcript.get("metrics", {})
    no_answer = transcript.get("status") != "candidate_submitted"
    return {
        "case_id": case_id,
        "case_kind": case_kind,
        "run_ref": _relative_to_workspace(workspace_root, run),
        "evidence": {
            "formal": "real_jaspergold",
            "model": actual_evidence_kind,
            "review": "external_codex_review",
            "fixture": False,
        },
        "formal": {
            "tool": "jaspergold",
            "outcome": result_map.get("formal_outcome"),
            "evidence_status": result_map.get("evidence_status"),
            "operation_set_complete": result_map.get("operation_set_complete"),
            "trace_universe_sha256": trace_hashes,
        },
        "projection": {
            "status": projection.get("status"),
            "exact_cex_projection": True,
            "source_status": source_projection.get("status"),
            "exact_source_candidate_count": sum(
                1
                for row in source_projection.get("source_candidates", [])
                if row.get("projection_status") == "exact"
            ),
        },
        "graph": graph,
        "query": query,
        "diagnosis": {
            "classification": candidate.get("classification"),
            "review_decision": review.get("decision"),
            "root_cause_status": root.get("status"),
            "final_verdict": stage_result.get("final_verdict"),
        },
        "accounting": {
            "model_calls": transcript.get("counts", {}).get("model_calls", 0),
            "query_calls": transcript.get("counts", {}).get(
                "evidence_queries", 0
            ),
            "prompt_tokens": token_metrics.get("prompt_tokens"),
            "completion_tokens": token_metrics.get("completion_tokens"),
            "total_tokens": token_metrics.get("total_tokens"),
            "token_accounting_complete": token_metrics.get(
                "token_accounting_complete", False
            ),
            "model_wall_time_s": token_metrics.get("model_wall_time_s"),
            "no_answer": no_answer,
        },
        "artifact_refs": _case_artifact_refs(
            run, stage2, stage3, graph_refs
        ),
    }


def collect_proof_comparator(
    workspace_root: Path,
    run: Path,
    *,
    comparator_id: str,
    case_kind: str,
) -> Dict[str, Any]:
    """Collect a real JasperGold proof accepted by the existing V5 verdict path."""

    run = run.resolve()
    stage2, stage3 = _stage_dirs(run)
    result_map_path = stage2 / "property_result_map.json"
    semantic_path = stage2 / "semantic_evidence.json"
    verdict_path = stage3 / "final_verdict.json"
    stage_result_path = stage3 / "stage_result.json"
    result_map = _read(result_map_path)
    verdict = _read(verdict_path)
    if (
        result_map.get("tool", {}).get("name") != "jaspergold"
        or result_map.get("formal_outcome") != "all_proven"
        or result_map.get("operation_set_complete") is not True
        or verdict.get("verdict") != "accepted"
    ):
        raise Iteration5EvaluationError(
            f"{run.name} is not an accepted real JasperGold proof comparator"
        )
    return {
        "comparator_id": comparator_id,
        "case_kind": case_kind,
        "run_ref": _relative_to_workspace(workspace_root, run),
        "formal": {
            "tool": "jaspergold",
            "outcome": "all_proven",
            "evidence_status": result_map.get("evidence_status"),
            "operation_set_complete": True,
        },
        "verdict": "accepted",
        "artifact_refs": [
            _artifact_ref(run, result_map_path),
            _artifact_ref(run, semantic_path),
            _artifact_ref(run, verdict_path),
            _artifact_ref(run, stage_result_path),
        ],
    }


def build_track_d_ablation(
    workspace_root: Path,
    run: Path,
    *,
    v5_fixture_path: Path | None = None,
) -> Dict[str, Any]:
    """Freeze the existing controlled V5 one-shot/V6 causal-loop comparison.

    Both arms consume the exact CEX and exact source-candidate universe from one
    durable real-formal run.  The V5 arm remains explicitly labelled as a
    deterministic evaluation fixture; it is not production-model evidence.
    """

    run = run.resolve()
    stage2, stage3 = _stage_dirs(run)
    result_map = _read(stage2 / "property_result_map.json")
    trace_manifest = _read(stage2 / "trace_manifest.json")
    projection = _read(stage3 / "evidence_projection.json")
    source_projection = _read(stage3 / "causal_source_projection.json")
    transcript = _read(stage3 / "diagnosis_transcript_manifest.json")
    root = _read(stage3 / "root_cause_result.json")
    model_evidence = _read(stage3 / "model_evidence.json")
    trace_hashes = _trace_universe(run, result_map, trace_manifest, projection)
    candidates = sorted(
        (
            {
                "candidate_id": row["candidate_id"],
                "projection_status": row["projection_status"],
                "chisel_source_anchor": row["chisel_source_anchor"],
            }
            for row in source_projection.get("source_candidates", [])
            if row.get("projection_status") == "exact"
        ),
        key=lambda row: row["candidate_id"],
    )
    if not candidates:
        raise Iteration5EvaluationError("Track D requires exact source candidates")
    candidate_ids = {row["candidate_id"] for row in candidates}
    v6_ranking = [row["candidate_id"] for row in root.get("ranked_candidates", [])]
    if (
        root.get("status") != "localized"
        or not v6_ranking
        or not set(v6_ranking) <= candidate_ids
    ):
        raise Iteration5EvaluationError(
            "Track D V6 arm is not reviewed/localized in the candidate universe"
        )
    budget = transcript.get("budget", {})
    if any(budget.get(key) != value for key, value in _FROZEN_BUDGET.items()):
        raise Iteration5EvaluationError("Track D run does not use the frozen V6 budget")
    counts = transcript.get("counts", {})
    if (
        counts.get("model_calls", 0) > _FROZEN_BUDGET["max_model_calls"]
        or counts.get("evidence_queries", 0)
        > _FROZEN_BUDGET["max_evidence_queries"]
    ):
        raise Iteration5EvaluationError("Track D V6 consumption exceeds its budget")
    universe_sha = _canonical_sha256(candidates)
    common_inputs = {
        "trace_universe_sha256": trace_hashes,
        "trace_universe_identity": _canonical_sha256(trace_hashes),
        "candidate_granularity": "exact_chisel_source_candidate",
        "candidate_universe": candidates,
        "candidate_universe_sha256": universe_sha,
    }
    v5_inputs = {
        "trace_universe_identity": common_inputs["trace_universe_identity"],
        "candidate_universe_sha256": universe_sha,
        "available_budget": dict(_FROZEN_BUDGET),
    }
    v6_inputs = dict(v5_inputs)
    fairness = {
        "same_cex": (
            v5_inputs["trace_universe_identity"]
            == v6_inputs["trace_universe_identity"]
        ),
        "same_candidate_universe": (
            v5_inputs["candidate_universe_sha256"]
            == v6_inputs["candidate_universe_sha256"]
        ),
        "same_candidate_granularity": True,
        "same_available_budget": (
            v5_inputs["available_budget"] == v6_inputs["available_budget"]
        ),
    }
    if not all(fairness.values()):
        raise Iteration5EvaluationError("Track D fairness identity drifted")
    input_paths = (
        stage2 / "property_result_map.json",
        stage2 / "trace_manifest.json",
        stage3 / "evidence_projection.json",
        stage3 / "causal_source_projection.json",
        stage3 / "diagnosis_transcript_manifest.json",
        stage3 / "root_cause_result.json",
        stage3 / "model_evidence.json",
    )
    v5_arm = {
        "policy": "one_shot_without_causal_queries",
        "evidence_kind": "deterministic_fixture",
        "production_model_evidence": False,
        "model_calls": 1,
        "query_calls": 0,
        "ranked_candidate_ids": [candidates[0]["candidate_id"]],
        "token_accounting_complete": False,
        "tokens": None,
        "no_answer": False,
        "input_identity": v5_inputs,
    }
    fixture_ref = None
    if v5_fixture_path is not None:
        fixture_path = v5_fixture_path.resolve()
        fixture = _read(fixture_path)
        if (
            fixture.get("schema_version") != "track_d_v5_one_shot_fixture.v1"
            or fixture.get("run_ref") != _relative_to_workspace(workspace_root, run)
            or fixture.get("common_inputs") != common_inputs
            or fixture.get("shared_budget") != _FROZEN_BUDGET
            or fixture.get("arm") != v5_arm
        ):
            raise Iteration5EvaluationError(
                "Track D V5 one-shot fixture identity drifted"
            )
        _validate_artifact_refs(run, fixture.get("input_refs", []))
        fixture_ref = {
            "path": _relative_to_workspace(workspace_root, fixture_path),
            "sha256": _sha256(fixture_path),
        }
        v5_arm["fixture_ref"] = fixture_ref
    result = {
        "schema_version": "track_d_ablation.v2",
        "evidence_kind": "controlled_deterministic_fixture_over_real_jaspergold_cex",
        "run_ref": _relative_to_workspace(workspace_root, run),
        "common_inputs": common_inputs,
        "shared_budget": dict(_FROZEN_BUDGET),
        "arms": {
            "v5_one_shot": v5_arm,
            "v6_causal_loop": {
                "policy": "bounded_query_query_submit",
                "evidence_kind": model_evidence.get("evidence_kind"),
                "production_model_evidence": model_evidence.get(
                    "evidence_kind"
                )
                == "production_api_model",
                "model_calls": counts.get("model_calls", 0),
                "query_calls": counts.get("evidence_queries", 0),
                "ranked_candidate_ids": v6_ranking,
                "token_accounting_complete": transcript.get("metrics", {}).get(
                    "token_accounting_complete", False
                ),
                "tokens": transcript.get("metrics", {}).get("total_tokens"),
                "model_wall_time_s": transcript.get("metrics", {}).get(
                    "model_wall_time_s"
                ),
                "no_answer": transcript.get("status") != "candidate_submitted",
                "input_identity": v6_inputs,
            },
        },
        "fairness_checks": fairness,
        "input_refs": [_artifact_ref(run, path) for path in input_paths],
    }
    if fixture_ref is not None:
        result["fixture_ref"] = fixture_ref
    return result


def build_evaluation_freeze(
    workspace_root: Path,
    case_specs: Iterable[Mapping[str, Any]],
    *,
    ablation_run: Path,
    proof_specs: Iterable[Mapping[str, str]] = (),
    vertical_ledger_path: Path | None = None,
    v5_fixture_path: Path | None = None,
) -> Dict[str, Any]:
    """Build V7-4 from live runs, optionally anchored to the completed V7-3 ledger."""

    workspace_root = workspace_root.resolve()
    ledger_ref = None
    ledger_rows: dict[str, Mapping[str, Any]] = {}
    if vertical_ledger_path is not None:
        ledger_path = vertical_ledger_path.resolve()
        ledger = _read(ledger_path)
        if (
            ledger.get("schema_version")
            != "iteration5_vertical_evidence_ledger.v1"
            or ledger.get("status") != "complete"
            or not all(ledger.get("gates", {}).values())
        ):
            raise Iteration5EvaluationError("V7-3 vertical ledger is not complete")
        ledger_ref = {
            "path": _relative_to_workspace(workspace_root, ledger_path),
            "sha256": _sha256(ledger_path),
        }
        ledger_rows = {
            str(row.get("case_id")): row for row in ledger.get("cases", [])
        }

    cases = []
    for spec in case_specs:
        case_id = str(spec["case_id"])
        ledger_row = ledger_rows.get(case_id)
        if vertical_ledger_path is not None and ledger_row is None:
            raise Iteration5EvaluationError(
                f"case {case_id} is absent from the V7-3 ledger"
            )
        run = (
            workspace_root / str(ledger_row["run_ref"])
            if ledger_row is not None
            else Path(str(spec["run"]))
        )
        if ledger_row is not None:
            if (
                ledger_row.get("status") != "completed"
                or ledger_row.get("case_kind") != spec["case_kind"]
            ):
                raise Iteration5EvaluationError(
                    f"case {case_id} does not match its V7-3 ledger row"
                )
        cases.append(
            collect_case(
                workspace_root,
                run,
                case_id=case_id,
                case_kind=str(spec["case_kind"]),
                evidence_kind=spec.get("evidence_kind"),
                expected_artifact_refs=(
                    ledger_row.get("artifact_refs", [])
                    if ledger_row is not None
                    else None
                ),
            )
        )

    comparators = [
        collect_proof_comparator(
            workspace_root,
            Path(spec["run"]),
            comparator_id=spec["comparator_id"],
            case_kind=spec["case_kind"],
        )
        for spec in proof_specs
    ]
    required = {
        "counter_previous_value",
        "fsm_controller_transition",
        "implication_window_or_bounded_response",
    }
    primary_kinds = {row["case_kind"] for row in cases}
    localized = any(
        row["diagnosis"]["root_cause_status"] == "localized" for row in cases
    )
    hostile = any(
        row["diagnosis"]["root_cause_status"] in {"rtl_only", "inconclusive"}
        for row in cases
    )
    track_d = build_track_d_ablation(
        workspace_root,
        ablation_run,
        v5_fixture_path=v5_fixture_path,
    )
    evidence_kinds = {row["evidence"]["model"] for row in cases}
    gates = {
        "v7_3_ledger_hash_bound": ledger_ref is not None,
        "three_case_kinds_present": primary_kinds == required,
        "all_cases_real_formal_cex": all(
            row["formal"]["tool"] == "jaspergold"
            and row["formal"]["outcome"] == "cex"
            and row["projection"]["exact_cex_projection"]
            for row in cases
        ),
        "all_cases_reviewed": all(
            row["diagnosis"]["review_decision"] in {"approved", "rejected"}
            for row in cases
        ),
        "localized_case_present": localized,
        "hostile_boundary_present": hostile,
        "track_d_fairness_complete": all(track_d["fairness_checks"].values()),
        "track_d_arms_hash_bound": track_d.get("fixture_ref") is not None,
        "production_and_fake_separated": _MODEL_EVIDENCE_KINDS <= evidence_kinds,
        "v5_proof_regression_preserved": bool(comparators)
        and all(row["verdict"] == "accepted" for row in comparators),
        "all_report_rows_hash_bound": all(row["artifact_refs"] for row in cases)
        and all(row["artifact_refs"] for row in comparators)
        and bool(track_d["input_refs"]),
    }
    return {
        "schema_version": "iteration5_evaluation_freeze.v2",
        "source_vertical_ledger": ledger_ref,
        "cases": cases,
        "regression_comparators": comparators,
        "track_d_ablation": track_d,
        "evidence_separation": {
            "deterministic_fixture": "Track-D V5 one-shot arm only",
            "deterministic_fake_model": [
                row["case_id"]
                for row in cases
                if row["evidence"]["model"] == "deterministic_fake_model"
            ],
            "production_api_model": [
                row["case_id"]
                for row in cases
                if row["evidence"]["model"] == "production_api_model"
            ],
            "real_jaspergold": [row["case_id"] for row in cases],
            "external_review": [row["case_id"] for row in cases],
        },
        "gates": gates,
        "status": "complete" if all(gates.values()) else "incomplete",
    }
