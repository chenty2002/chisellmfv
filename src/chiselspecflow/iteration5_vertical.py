"""V7-3 durable evidence ledger for existing Iteration-5 vertical cases."""

from __future__ import annotations

import hashlib
import json
from pathlib import Path
from typing import Any, Dict, Iterable, Mapping

from .result_contract import write_json


class Iteration5VerticalError(RuntimeError):
    """Raised when a claimed V7-3 case is not backed by exact artifacts."""


MODEL_EVIDENCE_KINDS = {
    "deterministic_fake_model",
    "production_api_model",
}

_STAGE1_REQUIRED = (
    "verification_package.json",
    "review_record.json",
    "frozen_package_provenance.json",
)
_STAGE2_REQUIRED = (
    "verification_package_ref.json",
    "elaboration_certificate.json",
    "verification_operation_plan.json",
    "property_result_map.json",
    "semantic_evidence.json",
    "trace_manifest.json",
)
_STAGE3_REQUIRED = (
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


def write_model_evidence(
    run: Path,
    *,
    evidence_kind: str,
    model_id: str,
) -> Dict[str, Any]:
    """Bind fake/production provenance to one completed diagnosis transcript."""

    if evidence_kind not in MODEL_EVIDENCE_KINDS:
        raise Iteration5VerticalError("unsupported model evidence kind")
    if not isinstance(model_id, str) or not model_id.strip():
        raise Iteration5VerticalError("model_id must be non-empty")
    run = Path(run).resolve()
    _stage1, _stage2, stage3 = _stage_dirs(run)
    transcript_path = stage3 / "diagnosis_transcript_manifest.json"
    calls_path = stage3 / "model_calls.jsonl"
    candidate_path = stage3 / "diagnosis_candidate.json"
    if not all(path.is_file() for path in (transcript_path, calls_path, candidate_path)):
        raise Iteration5VerticalError(
            "model evidence requires transcript, call log, and candidate artifact"
        )
    transcript = _read(transcript_path)
    model_calls = int(transcript.get("counts", {}).get("model_calls", 0))
    if evidence_kind == "production_api_model" and model_calls == 0:
        raise Iteration5VerticalError(
            "production model evidence requires at least one recorded API call"
        )
    record = {
        "schema_version": "iteration5_model_evidence.v1",
        "evidence_kind": evidence_kind,
        "model_id": model_id,
        "authority": "untrusted_candidate_generator",
        "production_model_evidence": evidence_kind == "production_api_model",
        "deterministic_fake_model_evidence": (
            evidence_kind == "deterministic_fake_model"
        ),
        "transcript_status": transcript.get("status"),
        "counts": transcript.get("counts"),
        "artifacts": {
            "diagnosis_transcript_manifest_sha256": _sha256(transcript_path),
            "model_calls_sha256": _sha256(calls_path),
            "diagnosis_candidate_sha256": _sha256(candidate_path),
        },
    }
    write_json(stage3 / "model_evidence.json", record)
    return record


def collect_vertical_case(
    workspace_root: Path,
    run: Path,
    *,
    case_id: str,
    case_kind: str,
) -> Dict[str, Any]:
    """Validate and collect one reviewed V3 diagnosis over a real formal CEX."""

    workspace_root = Path(workspace_root).resolve()
    run = Path(run).resolve()
    stage1, stage2, stage3 = _stage_dirs(run)
    required = [
        *(stage1 / name for name in _STAGE1_REQUIRED),
        *(stage2 / name for name in _STAGE2_REQUIRED),
        *(stage3 / name for name in _STAGE3_REQUIRED),
    ]
    missing = [path.relative_to(run).as_posix() for path in required if not path.is_file()]
    if missing:
        raise Iteration5VerticalError(
            f"{run.name} lacks required V7-3 artifacts: {', '.join(missing)}"
        )

    diagnosis_config = _read(run / "inputs/diagnosis_config.json")
    if diagnosis_config.get("causal_backend") != "verilog_causal_analysis.v3":
        raise Iteration5VerticalError(f"{run.name} is not an explicit V3 run")
    package = _read(stage1 / "verification_package.json")
    asset_review = _read(stage1 / "review_record.json")
    provenance = _read(stage1 / "frozen_package_provenance.json")
    if (
        asset_review.get("schema_version") != "review_record.v1"
        or asset_review.get("reviewer") != "codex"
        or asset_review.get("decision") != "approved"
        or package.get("review", {}).get("review_record_sha256")
        != _sha256(stage1 / "review_record.json")
        or provenance.get("verification_package_sha256")
        != _sha256(stage1 / "verification_package.json")
    ):
        raise Iteration5VerticalError(f"{run.name} asset review identity is invalid")

    result_map = _read(stage2 / "property_result_map.json")
    trace_manifest = _read(stage2 / "trace_manifest.json")
    traces = trace_manifest.get("traces", [])
    exact_cex_rows = [
        row
        for row in result_map.get("operation_results", [])
        if row.get("status") == "cex"
        and row.get("reason") == "tool_reported_cex_with_exact_trace"
    ]
    if (
        result_map.get("formal_outcome") != "cex"
        or result_map.get("operation_set_complete") is not True
        or not traces
        or not exact_cex_rows
    ):
        raise Iteration5VerticalError(f"{run.name} lacks a certified exact CEX")
    for trace in traces:
        trace_path = Path(str(trace.get("path", ""))).resolve()
        if not trace_path.is_file() or _sha256(trace_path) != trace.get("sha256"):
            raise Iteration5VerticalError(f"{run.name} trace identity drifted")

    graph_manifest = _read(stage3 / "causal_graph_manifest.json")
    graph_rows = []
    for ref in graph_manifest.get("graphs", []):
        graph_path = stage3 / str(ref.get("path", ""))
        graph = _read(graph_path)
        if (
            graph.get("schema_version") != "verilog_causal_semantic_graph.v1"
            or graph.get("graph_id") != ref.get("graph_id")
            or _sha256(graph_path) != ref.get("sha256")
        ):
            raise Iteration5VerticalError(f"{run.name} has a non-V3 or drifted graph")
        graph_rows.append(
            {
                "graph_id": graph["graph_id"],
                "status": graph.get("status"),
                "signal_node_count": len(graph.get("signal_nodes", [])),
                "semantic_node_count": len(graph.get("semantic_nodes", [])),
                "edge_count": len(graph.get("edges", [])),
                "sha256": ref["sha256"],
            }
        )
    if graph_manifest.get("status") == "complete" and not graph_rows:
        raise Iteration5VerticalError(f"{run.name} complete manifest has no V3 graph")

    transcript = _read(stage3 / "diagnosis_transcript_manifest.json")
    budget = transcript.get("budget", {})
    counts = transcript.get("counts", {})
    if (
        budget.get("max_model_calls") != 3
        or budget.get("max_evidence_queries") != 2
        or budget.get("parallel_tool_calls") is not False
        or counts.get("model_calls", 0) > 3
        or counts.get("evidence_queries", 0) > 2
    ):
        raise Iteration5VerticalError(f"{run.name} diagnosis budget drifted")
    model_evidence = _read(stage3 / "model_evidence.json")
    if model_evidence.get("evidence_kind") not in MODEL_EVIDENCE_KINDS:
        raise Iteration5VerticalError(f"{run.name} model provenance is invalid")
    if (
        model_evidence.get("artifacts", {}).get(
            "diagnosis_transcript_manifest_sha256"
        )
        != _sha256(stage3 / "diagnosis_transcript_manifest.json")
    ):
        raise Iteration5VerticalError(f"{run.name} model transcript binding drifted")

    diagnosis_review = _read(stage3 / "diagnosis_review.json")
    root_cause = _read(stage3 / "root_cause_result.json")
    final_verdict = _read(stage3 / "final_verdict.json")
    stage_result = _read(stage3 / "stage_result.json")
    if (
        stage_result.get("status") != "completed"
        or diagnosis_review.get("reviewer") != "codex"
        or diagnosis_review.get("decision") not in {"approved", "rejected"}
        or root_cause.get("status")
        not in {"localized", "rtl_only", "inconclusive"}
    ):
        raise Iteration5VerticalError(f"{run.name} external review is incomplete")
    try:
        run_ref = run.relative_to(workspace_root).as_posix()
    except ValueError as exc:
        raise Iteration5VerticalError("vertical run is outside workspace") from exc
    return {
        "case_id": case_id,
        "case_kind": case_kind,
        "status": "completed",
        "run_ref": run_ref,
        "formal": {
            "tool": result_map.get("tool", {}).get("name"),
            "outcome": result_map.get("formal_outcome"),
            "evidence_status": result_map.get("evidence_status"),
            "operation_set_complete": result_map.get("operation_set_complete"),
            "exact_cex_operation_ids": [
                row["operation_id"] for row in exact_cex_rows
            ],
            "trace_sha256": [row["sha256"] for row in traces],
        },
        "causal": {
            "backend": diagnosis_config["causal_backend"],
            "manifest_status": graph_manifest.get("status"),
            "graphs": graph_rows,
        },
        "model": model_evidence,
        "review": {
            "decision": diagnosis_review["decision"],
            "root_cause_status": root_cause["status"],
            "final_verdict": final_verdict.get("verdict"),
        },
        "limits": {
            "semantic_acceptance_from_exit_status": False,
            "semantic_acceptance_from_stage_success": False,
            "vca_provenance_is_source_authority": False,
        },
        "artifact_refs": [
            {
                "path": path.relative_to(run).as_posix(),
                "sha256": _sha256(path),
            }
            for path in required
        ],
    }


def unavailable_vertical_case(
    *,
    case_id: str,
    case_kind: str,
    gate: str,
    reason: str,
    evidence_refs: Iterable[Mapping[str, str]],
) -> Dict[str, Any]:
    """Record an honest unavailable-input gate without synthesizing a case."""

    refs = [dict(row) for row in evidence_refs]
    if not gate.startswith("unavailable_") or not reason or not refs:
        raise Iteration5VerticalError("unavailable case requires a gate and evidence")
    return {
        "case_id": case_id,
        "case_kind": case_kind,
        "status": "unavailable",
        "gate": gate,
        "reason": reason,
        "evidence_refs": refs,
    }


def build_vertical_evidence_ledger(
    cases: Iterable[Mapping[str, Any]],
) -> Dict[str, Any]:
    """Freeze V7-3 category, provenance-separation, and review gates."""

    rows = [dict(row) for row in cases]
    required_kinds = {
        "counter_previous_value",
        "fsm_controller_transition",
        "implication_window_or_bounded_response",
    }
    kinds = {row.get("case_kind") for row in rows}
    completed = [row for row in rows if row.get("status") == "completed"]
    model_kinds = {
        row.get("model", {}).get("evidence_kind") for row in completed
    }
    gates = {
        "three_existing_categories_accounted": required_kinds <= kinds,
        "all_rows_completed_or_unavailable": all(
            row.get("status") in {"completed", "unavailable"} for row in rows
        ),
        "production_and_fake_separated": MODEL_EVIDENCE_KINDS <= model_kinds,
        "localized_case_present": any(
            row.get("review", {}).get("root_cause_status") == "localized"
            for row in completed
        ),
        "hostile_or_incomplete_case_present": any(
            row.get("review", {}).get("root_cause_status")
            in {"rtl_only", "inconclusive"}
            or row.get("causal", {}).get("manifest_status") == "incomplete"
            for row in completed
        ),
        "all_completed_cases_v3": all(
            row.get("causal", {}).get("backend")
            == "verilog_causal_analysis.v3"
            for row in completed
        ),
    }
    return {
        "schema_version": "iteration5_vertical_evidence_ledger.v1",
        "cases": rows,
        "gates": gates,
        "status": "complete" if all(gates.values()) else "incomplete",
    }


def _stage_dirs(run: Path) -> tuple[Path, Path, Path]:
    manifest = _read(run / "manifest.json")
    round_id = int(manifest["current_round"])
    round_dir = run / "rounds" / f"{round_id:04d}"
    return (
        round_dir / "01_asset_authoring",
        round_dir / "02_compile_verify",
        round_dir / "03_diagnose",
    )


def _read(path: Path) -> Dict[str, Any]:
    try:
        value = json.loads(Path(path).read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise Iteration5VerticalError(f"cannot read vertical evidence {path}") from exc
    if not isinstance(value, dict):
        raise Iteration5VerticalError(f"vertical evidence is not an object: {path}")
    return value


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()
