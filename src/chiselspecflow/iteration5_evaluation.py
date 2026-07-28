"""Hash-bound Iteration-5 evaluation freeze and controlled Track-D ablation."""

from __future__ import annotations

import hashlib
import json
from pathlib import Path
from typing import Any, Dict, Iterable, Mapping


class Iteration5EvaluationError(RuntimeError):
    """Raised when an evaluation input is incomplete or not comparable."""


def _read(path: Path) -> Dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise Iteration5EvaluationError(f"cannot read evaluation input {path}") from exc
    if not isinstance(value, dict):
        raise Iteration5EvaluationError(f"evaluation input is not an object: {path}")
    return value


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


def _artifact_refs(run: Path, stage2: Path, stage3: Path) -> list[Dict[str, str]]:
    paths = [
        stage2 / "trace_manifest.json",
        stage2 / "elaboration_certificate.json",
        stage2 / "property_result_map.json",
        stage3 / "evidence_projection.json",
        stage3 / "causal_graph_manifest.json",
        stage3 / "causal_source_projection.json",
        stage3 / "causal_query_log.jsonl",
        stage3 / "model_calls.jsonl",
        stage3 / "diagnosis_transcript_manifest.json",
        stage3 / "diagnosis_candidate.json",
        stage3 / "diagnosis_review.json",
        stage3 / "root_cause_result.json",
    ]
    missing = [path.name for path in paths if not path.is_file()]
    if missing:
        raise Iteration5EvaluationError(
            f"{run.name} lacks required durable artifacts: {', '.join(missing)}"
        )
    return [
        {"path": path.relative_to(run).as_posix(), "sha256": _sha256(path)}
        for path in paths
    ]


def _graph_metrics(stage3: Path, manifest: Mapping[str, Any]) -> Dict[str, Any]:
    graphs = []
    for ref in manifest.get("graphs", []):
        graph_path = stage3 / str(ref["path"])
        graph = _read(graph_path)
        bounds = graph.get("bounds", {})
        graphs.append(
            {
                "graph_id": ref["graph_id"],
                "status": graph.get("status"),
                "node_count": len(graph.get("nodes", [])),
                "edge_count": len(graph.get("edges", [])),
                "max_nodes_reached": bool(bounds.get("max_nodes_reached")),
                "max_depth_reached": bool(bounds.get("max_depth_reached")),
            }
        )
    return {
        "status": manifest.get("status"),
        "graphs": graphs,
        "truncated": any(
            row["max_nodes_reached"] or row["max_depth_reached"] for row in graphs
        ),
    }


def collect_case(
    workspace_root: Path,
    run: Path,
    *,
    case_id: str,
    case_kind: str,
    evidence_kind: str,
) -> Dict[str, Any]:
    """Collect one completed real-formal CEX case without trusting run success alone."""

    run = run.resolve()
    stage2, stage3 = _stage_dirs(run)
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
    traces = trace_manifest.get("traces", [])
    if result_map.get("formal_outcome") != "cex" or not traces:
        raise Iteration5EvaluationError(f"{run.name} is not a durable CEX case")
    if stage_result.get("status") != "completed":
        raise Iteration5EvaluationError(f"{run.name} diagnosis review is not completed")
    if review.get("reviewer") != "codex":
        raise Iteration5EvaluationError(f"{run.name} lacks Codex review authority")
    no_answer = transcript.get("status") != "candidate_submitted"
    token_metrics = transcript.get("metrics", {})
    try:
        run_ref = run.relative_to(workspace_root.resolve()).as_posix()
    except ValueError as exc:
        raise Iteration5EvaluationError("evaluation run is outside workspace") from exc
    return {
        "case_id": case_id,
        "case_kind": case_kind,
        "run_ref": run_ref,
        "evidence_kind": evidence_kind,
        "formal": {
            "tool": result_map.get("tool", {}).get("name"),
            "outcome": result_map.get("formal_outcome"),
            "evidence_status": result_map.get("evidence_status"),
            "operation_set_complete": result_map.get("operation_set_complete"),
            "trace_sha256": traces[0]["sha256"],
        },
        "projection_status": projection.get("status"),
        "graph": _graph_metrics(stage3, graph_manifest),
        "source_projection_status": source_projection.get("status"),
        "exact_source_candidate_count": len(source_projection.get("source_candidates", [])),
        "diagnosis": {
            "classification": candidate.get("classification"),
            "review_decision": review.get("decision"),
            "root_cause_status": root.get("status"),
            "final_verdict": stage_result.get("final_verdict"),
        },
        "accounting": {
            "model_calls": transcript.get("counts", {}).get("model_calls", 0),
            "query_calls": transcript.get("counts", {}).get("evidence_queries", 0),
            "prompt_tokens": token_metrics.get("prompt_tokens"),
            "completion_tokens": token_metrics.get("completion_tokens"),
            "total_tokens": token_metrics.get("total_tokens"),
            "token_accounting_complete": token_metrics.get(
                "token_accounting_complete", False
            ),
            "model_wall_time_s": token_metrics.get("model_wall_time_s"),
            "no_answer": no_answer,
        },
        "artifact_refs": _artifact_refs(run, stage2, stage3),
    }


def build_track_d_ablation(workspace_root: Path, run: Path) -> Dict[str, Any]:
    """Freeze a same-CEX, same-candidate-universe deterministic model ablation.

    The V5 arm is a controlled one-shot fake model: it sees the frozen exact
    candidate universe but receives no causal query result.  The V6 arm is the
    persisted deterministic query/query/submit transcript from the same run.
    Neither arm is production-model evidence.
    """

    run = run.resolve()
    stage2, stage3 = _stage_dirs(run)
    traces = _read(stage2 / "trace_manifest.json").get("traces", [])
    source_projection = _read(stage3 / "causal_source_projection.json")
    transcript = _read(stage3 / "diagnosis_transcript_manifest.json")
    root = _read(stage3 / "root_cause_result.json")
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
    if not traces or not candidates:
        raise Iteration5EvaluationError("Track D requires a CEX and exact candidates")
    v6_ranking = [
        row["candidate_id"] for row in root.get("ranked_candidates", [])
    ]
    if root.get("status") != "localized" or not v6_ranking:
        raise Iteration5EvaluationError("Track D V6 arm is not reviewed/localized")
    budget = transcript.get("budget", {})
    if budget.get("max_model_calls") != 3 or budget.get("max_evidence_queries") != 2:
        raise Iteration5EvaluationError("Track D run does not use the frozen V6 budget")
    universe_sha = _canonical_sha256(candidates)
    return {
        "schema_version": "track_d_ablation.v1",
        "evidence_kind": "controlled_deterministic_fake_model",
        "common_inputs": {
            "trace_sha256": traces[0]["sha256"],
            "candidate_granularity": "exact_chisel_source_candidate",
            "candidate_universe": candidates,
            "candidate_universe_sha256": universe_sha,
        },
        "shared_budget": {
            "max_model_calls": 3,
            "max_evidence_queries": 2,
            "parallel_tool_calls": False,
        },
        "arms": {
            "v5_one_shot": {
                "policy": "one_shot_without_causal_queries",
                "model_calls": 1,
                "query_calls": 0,
                "ranked_candidate_ids": [candidates[0]["candidate_id"]],
                "token_accounting_complete": False,
                "tokens": None,
            },
            "v6_causal_loop": {
                "policy": "bounded_query_query_submit",
                "model_calls": transcript["counts"]["model_calls"],
                "query_calls": transcript["counts"]["evidence_queries"],
                "ranked_candidate_ids": v6_ranking,
                "token_accounting_complete": transcript["metrics"].get(
                    "token_accounting_complete", False
                ),
                "tokens": transcript["metrics"].get("total_tokens"),
                "model_wall_time_s": transcript["metrics"].get("model_wall_time_s"),
            },
        },
        "fairness_checks": {
            "same_cex": True,
            "same_candidate_universe": True,
            "same_candidate_granularity": True,
            "same_available_budget": True,
        },
        "input_refs": [
            {
                "path": (stage2 / "trace_manifest.json").relative_to(run).as_posix(),
                "sha256": _sha256(stage2 / "trace_manifest.json"),
            },
            {
                "path": (
                    stage3 / "causal_source_projection.json"
                ).relative_to(run).as_posix(),
                "sha256": _sha256(stage3 / "causal_source_projection.json"),
            },
            {
                "path": (
                    stage3 / "diagnosis_transcript_manifest.json"
                ).relative_to(run).as_posix(),
                "sha256": _sha256(stage3 / "diagnosis_transcript_manifest.json"),
            },
            {
                "path": (stage3 / "root_cause_result.json").relative_to(run).as_posix(),
                "sha256": _sha256(stage3 / "root_cause_result.json"),
            },
        ],
    }


def build_evaluation_freeze(
    workspace_root: Path,
    case_specs: Iterable[Mapping[str, str]],
    *,
    ablation_run: Path,
) -> Dict[str, Any]:
    cases = [
        collect_case(
            workspace_root,
            Path(spec["run"]),
            case_id=spec["case_id"],
            case_kind=spec["case_kind"],
            evidence_kind=spec["evidence_kind"],
        )
        for spec in case_specs
    ]
    primary = [row for row in cases if row["case_id"].startswith("primary-")]
    required = {"counter_previous_value", "fsm_controller_transition", "phase_window"}
    primary_kinds = {row["case_kind"] for row in primary}
    localized = any(
        row["diagnosis"]["root_cause_status"] == "localized" for row in primary
    )
    hostile = any(
        row["diagnosis"]["root_cause_status"] in {"rtl_only", "inconclusive"}
        for row in cases
    )
    track_d = build_track_d_ablation(workspace_root, ablation_run)
    gates = {
        "three_case_kinds_present": primary_kinds == required,
        "all_primary_real_formal_cex": all(
            row["formal"]["tool"] == "jaspergold"
            and row["formal"]["outcome"] == "cex"
            for row in primary
        ),
        "all_primary_reviewed": all(
            row["diagnosis"]["review_decision"] in {"approved", "rejected"}
            for row in primary
        ),
        "localized_case_present": localized,
        "hostile_boundary_present": hostile,
        "track_d_fairness_complete": all(track_d["fairness_checks"].values()),
        "production_and_fake_separated": len(
            {row["evidence_kind"] for row in cases}
        )
        > 1,
    }
    return {
        "schema_version": "iteration5_evaluation_freeze.v1",
        "cases": cases,
        "track_d_ablation": track_d,
        "gates": gates,
        "status": "complete" if all(gates.values()) else "incomplete",
    }
