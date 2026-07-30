"""Deterministic Stage-3 counterexample projection and source ranking."""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any, Dict, Mapping, Optional, Sequence

from src.core.artifact_contract import (
    file_sha256,
    validate_completed_stage,
    write_stage_outcome,
)

from .causal_backend import materialize_causal_evidence
from .stages import get_stage_spec
from .trace_projection import project_stage2_evidence


class DiagnosisError(ValueError):
    """Raised when exact Stage-2 evidence cannot be consumed."""


def run_diagnose(
    run_dir: Path,
    *,
    fst2vcd_argv: Optional[list[str]] = None,
) -> Dict[str, Any]:
    """Project exact CEX evidence and rank source candidates without a model."""

    from .runner import _validate_run_integrity, load_existing_workspace

    workspace = load_existing_workspace(run_dir)
    manifest = _read_json(workspace.manifest_path)
    _validate_run_integrity(workspace, manifest)
    round_id = 1
    stage2 = workspace.stage_dir("compile_verify")
    if validate_completed_stage(stage2, get_stage_spec("compile_verify")) is None:
        raise DiagnosisError("compile_verify completion or hashes are invalid")

    stage3 = workspace.stage_dir("diagnose")
    completed = validate_completed_stage(stage3, get_stage_spec("diagnose"))
    if completed is not None:
        return completed
    if any(stage3.iterdir()):
        raise DiagnosisError("diagnose stage directory is immutable once written")

    projection = project_stage2_evidence(
        workspace.run_dir,
        round_id,
        fst2vcd_argv=fst2vcd_argv,
    )
    _write_json(stage3 / "evidence_projection.json", projection)
    graph_manifest, source_projection, graphs = materialize_causal_evidence(
        workspace,
        round_id,
        projection,
        config=manifest.get("diagnosis"),
    )
    _write_json(stage3 / "causal_graph_manifest.json", graph_manifest)
    _write_json(stage3 / "causal_source_projection.json", source_projection)

    result_map = _read_json(stage2 / "property_result_map.json")
    semantic_evidence = _read_json(stage2 / "semantic_evidence.json")
    root_cause, ranking = _rank_source_candidates(
        graph_manifest,
        source_projection,
        graphs,
        round_id=round_id,
    )
    verdict = _reduce_verdict(
        result_map,
        semantic_evidence,
        projection,
        round_id=round_id,
        stage2=stage2,
        stage3=stage3,
    )
    return _complete_diagnosis(
        workspace,
        manifest,
        round_id,
        projection,
        root_cause,
        ranking,
        verdict,
    )


def _rank_source_candidates(
    graph_manifest: Mapping[str, Any],
    source_projection: Mapping[str, Any],
    graphs: Mapping[str, Mapping[str, Any]] | Sequence[Mapping[str, Any]],
    *,
    round_id: int,
) -> tuple[Dict[str, Any], Dict[str, Any]]:
    """Rank exact source projections by deterministic causal-edge evidence."""

    graph_by_id = (
        {str(key): value for key, value in graphs.items()}
        if isinstance(graphs, Mapping)
        else {str(row["graph_id"]): row for row in graphs}
    )
    manifest_graphs = {
        str(row["graph_id"]): row
        for row in graph_manifest.get("graphs", [])
        if isinstance(row, Mapping) and row.get("graph_id")
    }
    complete_graph_ids = {
        graph_id
        for graph_id, row in manifest_graphs.items()
        if row.get("status") == "complete"
        and graph_by_id.get(graph_id, {}).get("status") == "complete"
    }
    edge_by_id: Dict[str, Mapping[str, Any]] = {}
    for graph_id in sorted(complete_graph_ids):
        for edge in graph_by_id[graph_id].get("edges", []):
            if isinstance(edge, Mapping) and edge.get("edge_id"):
                edge_by_id[str(edge["edge_id"])] = edge

    rows = []
    for source in source_projection.get("source_candidates", []):
        if (
            not isinstance(source, Mapping)
            or source.get("projection_status") != "exact"
            or not set(source.get("graph_ids", [])) & complete_graph_ids
        ):
            continue
        edge_ids = sorted(
            edge_id
            for edge_id in set(source.get("causal_edge_ids", []))
            if edge_id in edge_by_id
        )
        if not edge_ids:
            continue
        scores = [
            float(edge_by_id[edge_id].get("contribution_score", 0.0) or 0.0)
            for edge_id in edge_ids
        ]
        rows.append(
            {
                "candidate_id": source["candidate_id"],
                "projection_status": "exact",
                "chisel_source_anchor": dict(source["chisel_source_anchor"]),
                "causal_edge_ids": edge_ids,
                "evidence_strengths": sorted(
                    {
                        str(edge_by_id[edge_id].get("evidence_strength", "unresolved"))
                        for edge_id in edge_ids
                    }
                ),
                "score": max(scores),
                "supporting_edge_count": len(edge_ids),
            }
        )
    rows.sort(
        key=lambda row: (
            -row["score"],
            -row["supporting_edge_count"],
            row["candidate_id"],
        )
    )
    previous_key = None
    rank = 0
    for index, row in enumerate(rows, start=1):
        key = (row["score"], row["supporting_edge_count"])
        if key != previous_key:
            rank = index
            previous_key = key
        row["rank"] = rank

    status = (
        "localized"
        if rows
        and graph_manifest.get("status") == "complete"
        and source_projection.get("status") == "complete"
        else "inconclusive"
    )
    limitations = []
    if graph_manifest.get("status") != "complete":
        limitations.append("causal graph is incomplete")
    if source_projection.get("status") != "complete":
        limitations.append("source projection is incomplete")
    if not rows:
        limitations.append("no exact source candidate has causal-edge support")
    root_cause = {
        "schema_version": "root_cause_result",
        "status": status,
        "round_id": round_id,
        "reviewed": False,
        "formal_verdict": "not_established",
        "causal_graph_ids": sorted(complete_graph_ids),
        "ranked_candidates": rows,
        "limitations": limitations,
    }
    ranking = {
        "schema_version": "source_ranking",
        "status": "candidate_ranking" if rows else "not_available",
        "ordering": rows,
        "tie_rule": "score_then_supporting_edge_count_then_candidate_id",
    }
    return root_cause, ranking


def _reduce_verdict(
    result_map: Mapping[str, Any],
    semantic: Mapping[str, Any],
    projection: Mapping[str, Any],
    *,
    round_id: int,
    stage2: Path,
    stage3: Path,
) -> Dict[str, Any]:
    if _is_accepted(result_map, semantic):
        verdict = "accepted"
        reason = "all primary operations are proven with complete semantic evidence"
    elif (
        result_map.get("execution_status") == "completed"
        and result_map.get("formal_outcome") == "cex"
        and projection.get("status") == "complete"
    ):
        verdict = "violated"
        reason = "formal verification produced an exact counterexample"
    else:
        verdict = "inconclusive"
        reason = "formal or projection evidence is incomplete"
    return {
        "schema_version": "final_verdict",
        "verdict": verdict,
        "reason": reason,
        "round_id": round_id,
        "model_calls": 0,
        "evidence_refs": {
            "compile_verify_stage_result_sha256": file_sha256(
                stage2 / "stage_result.json"
            ),
            "evidence_projection_sha256": file_sha256(
                stage3 / "evidence_projection.json"
            ),
        },
    }


def _complete_diagnosis(
    workspace: Any,
    manifest: Mapping[str, Any],
    round_id: int,
    projection: Mapping[str, Any],
    root_cause: Mapping[str, Any],
    ranking: Mapping[str, Any],
    verdict: Mapping[str, Any],
) -> Dict[str, Any]:
    stage3 = workspace.stage_dir("diagnose")
    _write_json(stage3 / "root_cause_result.json", root_cause)
    _write_json(stage3 / "source_ranking.json", ranking)
    _write_json(stage3 / "final_verdict.json", verdict)
    _write_analysis(stage3, projection, root_cause, verdict)
    result = write_stage_outcome(
        stage3,
        get_stage_spec("diagnose"),
        {
            "success": True,
            "status": "completed",
            "round_id": round_id,
            "model_calls": 0,
            "projection_status": projection.get("status"),
            "final_verdict": verdict["verdict"],
            "root_cause_status": root_cause["status"],
        },
        source_state=dict(manifest),
    )
    _write_json(
        workspace.final_result_path,
        {
            "schema_version": "specflow_final_result",
            "project_id": manifest["project_id"],
            "configuration_id": manifest["configuration_id"],
            "round_id": round_id,
            "verdict": verdict["verdict"],
            "final_verdict_ref": {
                "path": str(
                    (stage3 / "final_verdict.json").relative_to(workspace.run_dir)
                ),
                "sha256": file_sha256(stage3 / "final_verdict.json"),
            },
            "root_cause_result_ref": {
                "path": str(
                    (stage3 / "root_cause_result.json").relative_to(workspace.run_dir)
                ),
                "sha256": file_sha256(stage3 / "root_cause_result.json"),
            },
            "diagnose_stage_result_sha256": file_sha256(
                stage3 / "stage_result.json"
            ),
        },
    )
    _set_run_completed(workspace)
    return result


def _write_analysis(
    stage3: Path,
    projection: Mapping[str, Any],
    root_cause: Mapping[str, Any],
    verdict: Mapping[str, Any],
) -> None:
    lines = [
        "# SpecFlow Counterexample Analysis",
        "",
        f"- Projection status: `{projection.get('status')}`",
        f"- Final verdict: `{verdict.get('verdict')}`",
        f"- Root-cause status: `{root_cause.get('status')}`",
        f"- Model calls: `0`",
    ]
    for row in root_cause.get("ranked_candidates", []):
        anchor = row["chisel_source_anchor"]
        lines.append(
            f"- Rank {row['rank']}: `{anchor['path']}:{anchor['line_start']}` "
            f"(score={row['score']:.3f}, edges={row['supporting_edge_count']})"
        )
    for limitation in root_cause.get("limitations", []):
        lines.append(f"- Limitation: {limitation}")
    (stage3 / "counterexample_analysis.md").write_text(
        "\n".join(lines) + "\n", encoding="utf-8"
    )


def _is_accepted(
    result_map: Mapping[str, Any], semantic: Mapping[str, Any]
) -> bool:
    return (
        result_map.get("execution_status") == "completed"
        and result_map.get("formal_outcome") == "all_proven"
        and result_map.get("evidence_status") == "complete"
        and result_map.get("semantic_candidate") == "supported"
        and result_map.get("operation_set_complete") is True
        and semantic.get("evidence_status") == "complete"
        and semantic.get("semantic_candidate") == "supported"
    )


def _set_run_completed(workspace: Any) -> None:
    manifest = _read_json(workspace.manifest_path)
    manifest["review_state"] = "completed"
    _write_json(workspace.manifest_path, manifest)


def _read_json(path: Path) -> Dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise DiagnosisError(f"invalid JSON artifact: {path}") from exc
    if not isinstance(value, dict):
        raise DiagnosisError(f"JSON artifact must be an object: {path}")
    return value


def _write_json(path: Path, value: Mapping[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(dict(value), indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
