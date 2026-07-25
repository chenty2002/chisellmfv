"""Conditional Stage-3 diagnosis, review, verdict, and revision-round gates."""

from __future__ import annotations

import hashlib
import json
import re
from pathlib import Path
from typing import Any, Dict, Mapping, Optional, Protocol

from src.core.artifact_contract import (
    file_sha256,
    validate_completed_stage,
    write_stage_outcome,
)
from src.core.formal_operations import canonical_sha256

from .stages import DIAGNOSIS_CLASSIFICATIONS, get_stage_spec
from .trace_projection import project_stage2_evidence
from .workspace import SpecFlowRound


class DiagnosisModel(Protocol):
    def chat_with_tools(self, **kwargs: Any) -> Dict[str, Any]: ...


class DiagnosisError(ValueError):
    """Raised when Stage 3 cannot preserve its bounded evidence contract."""


_REVIEW_FIELDS = {
    "schema_version",
    "reviewer",
    "decision",
    "reviewed_hashes",
    "evidence_refs",
    "reviewed_at",
    "reason",
}
_REVISION_LAYERS = {
    "obligation_error": "obligation",
    "binding_error": "binding",
    "monitor_error": "monitor",
    "assumption_error": "assumption",
}


def run_diagnose(
    run_dir: Path,
    model: Optional[DiagnosisModel] = None,
    *,
    track_d: bool = False,
    fst2vcd_argv: Optional[list[str]] = None,
) -> Dict[str, Any]:
    """Run deterministic projection and invoke a model only for conditional cases."""

    from .runner import _validate_run_integrity, load_existing_workspace

    workspace = load_existing_workspace(run_dir)
    manifest = _read_json(workspace.manifest_path)
    _validate_run_integrity(workspace, manifest)
    round_id = manifest["current_round"]
    stage2 = workspace.stage_dir(round_id, "compile_verify")
    stage2_result = validate_completed_stage(stage2, get_stage_spec("compile_verify"))
    if stage2_result is None:
        raise DiagnosisError("compile_verify completion or hashes are invalid")
    stage3 = workspace.stage_dir(round_id, "diagnose")
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
    result_map = _read_json(stage2 / "property_result_map.json")
    semantic_evidence = _read_json(stage2 / "semantic_evidence.json")

    if _is_accepted_without_diagnosis(result_map, semantic_evidence):
        candidate = _not_required("diagnosis_candidate.v1", "all_primary_proven")
        review = _not_required("diagnosis_review.v1", "diagnosis_not_invoked")
        ranking = _not_required("source_ranking.v1", "track_d_not_required")
        revision = _not_required("revision_request.v1", "accepted")
        verdict = _final_verdict(
            "accepted",
            "complete reviewed proof and evidence gates",
            round_id,
            stage2,
            stage3,
            model_calls=0,
        )
        return _complete_diagnosis(
            workspace,
            manifest,
            round_id,
            candidate,
            review,
            ranking,
            revision,
            verdict,
            model_calls=0,
        )

    if not _requires_model(result_map):
        candidate = _not_required(
            "diagnosis_candidate.v1", "tool_or_identity_failure_is_deterministic"
        )
        review = _not_required("diagnosis_review.v1", "diagnosis_not_invoked")
        ranking = _not_required("source_ranking.v1", "track_d_not_required")
        revision = _not_required("revision_request.v1", "no_typed_revision")
        verdict = _final_verdict(
            "inconclusive",
            "Stage 2 did not produce complete proof or exact CEX evidence",
            round_id,
            stage2,
            stage3,
            model_calls=0,
        )
        return _complete_diagnosis(
            workspace,
            manifest,
            round_id,
            candidate,
            review,
            ranking,
            revision,
            verdict,
            model_calls=0,
        )

    if model is None:
        raise DiagnosisError("a diagnosis model is required for CEX evidence")
    candidate, audit = _request_diagnosis_candidate(model, projection, track_d=track_d)
    _write_json(stage3 / "diagnosis_candidate.json", candidate)
    _write_jsonl(stage3 / "model_calls.jsonl", audit)
    request = {
        "schema_version": "diagnosis_review_request.v1",
        "round_id": round_id,
        "reviewed_hashes_required": [
            {
                "artifact": "evidence_projection.json",
                "sha256": file_sha256(stage3 / "evidence_projection.json"),
            },
            {
                "artifact": "diagnosis_candidate.json",
                "sha256": file_sha256(stage3 / "diagnosis_candidate.json"),
            },
        ],
        "allowed_reviewers": ["codex", "human:<id>"],
        "candidate_classification": candidate["classification"],
        "evidence_refs_required": True,
    }
    _write_json(stage3 / "diagnosis_review_request.json", request)
    _write_json(
        stage3 / "diagnosis_review.json",
        _not_required("diagnosis_review.v1", "awaiting_external_review"),
    )
    _write_json(
        stage3 / "source_ranking.json",
        _not_required("source_ranking.v1", "awaiting_external_review"),
    )
    _write_json(
        stage3 / "revision_request.json",
        _not_required("revision_request.v1", "diagnosis_unreviewed"),
    )
    interim = _final_verdict(
        "inconclusive",
        "diagnosis candidate is not externally reviewed",
        round_id,
        stage2,
        stage3,
        model_calls=len(audit),
        reviewed=False,
    )
    _write_json(stage3 / "final_verdict.json", interim)
    _write_analysis(stage3, projection, candidate, None, interim)
    outcome = write_stage_outcome(
        stage3,
        get_stage_spec("diagnose"),
        {
            "success": False,
            "status": "awaiting_review",
            "error_kind": "diagnosis_review_required",
            "round_id": round_id,
            "model_calls": len(audit),
            "final_verdict": "inconclusive",
        },
        source_state=manifest,
    )
    _set_round_state(workspace, round_id, "awaiting_diagnosis_review")
    return outcome


def build_diagnosis_review_template(
    run_dir: Path, reviewer: str = "codex"
) -> Dict[str, Any]:
    """Return a rejected-by-default external review template."""

    from .runner import load_existing_workspace

    workspace = load_existing_workspace(run_dir)
    manifest = _read_json(workspace.manifest_path)
    stage3 = workspace.stage_dir(manifest["current_round"], "diagnose")
    request = _read_json(stage3 / "diagnosis_review_request.json")
    return {
        "schema_version": "diagnosis_review.v1",
        "reviewer": reviewer,
        "decision": "rejected",
        "reviewed_hashes": list(request["reviewed_hashes_required"]),
        "evidence_refs": [],
        "reviewed_at": "",
        "reason": "review the exact projected evidence and diagnosis classification",
    }


def install_diagnosis_review(run_dir: Path, review_record: Path) -> Dict[str, Any]:
    """Install an external review and reduce the only authoritative verdict."""

    from .runner import _validate_run_integrity, load_existing_workspace

    workspace = load_existing_workspace(run_dir)
    manifest = _read_json(workspace.manifest_path)
    _validate_run_integrity(workspace, manifest)
    round_id = manifest["current_round"]
    stage2 = workspace.stage_dir(round_id, "compile_verify")
    if validate_completed_stage(stage2, get_stage_spec("compile_verify")) is None:
        raise DiagnosisError("compile_verify completion or hashes are invalid")
    stage3 = workspace.stage_dir(round_id, "diagnose")
    pending = _read_json(stage3 / "stage_result.json")
    if pending.get("status") != "awaiting_review":
        raise DiagnosisError("diagnose is not awaiting external review")
    request = _read_json(stage3 / "diagnosis_review_request.json")
    record = _read_json(review_record)
    _validate_diagnosis_review(record, request, stage3)
    _write_json(stage3 / "diagnosis_review.json", record)
    candidate = _read_json(stage3 / "diagnosis_candidate.json")
    projection = _read_json(stage3 / "evidence_projection.json")
    result_map = _read_json(stage2 / "property_result_map.json")
    approved = record["decision"] == "approved"
    classification = candidate["classification"] if approved else "inconclusive"
    if (
        approved
        and classification == "design_violation"
        and result_map.get("formal_outcome") == "cex"
        and projection.get("status") == "complete"
        and any(
            row.get("failure_cycle") is not None and row.get("status") == "complete"
            for row in projection.get("traces", [])
        )
    ):
        verdict_name = "violated"
        reason = "exact CEX projection and reviewed design-violation diagnosis"
    else:
        verdict_name = "inconclusive"
        reason = (
            "diagnosis review rejected the candidate"
            if not approved
            else "reviewed evidence does not satisfy the violated verdict gate"
        )
    revision = _build_revision_request(
        candidate,
        projection,
        stage2,
        round_id,
        approved=approved,
    )
    ranking = _build_source_ranking(candidate, projection, approved=approved)
    verdict = _final_verdict(
        verdict_name,
        reason,
        round_id,
        stage2,
        stage3,
        model_calls=int(pending.get("model_calls", 0)),
        reviewed=approved,
        classification=classification,
    )
    return _complete_diagnosis(
        workspace,
        manifest,
        round_id,
        candidate,
        record,
        ranking,
        revision,
        verdict,
        model_calls=int(pending.get("model_calls", 0)),
    )


def create_revision_round(run_dir: Path) -> Path:
    """Create the next immutable round from a reviewed typed revision request."""

    from .runner import _validate_run_integrity, load_existing_workspace

    workspace = load_existing_workspace(run_dir)
    manifest = _read_json(workspace.manifest_path)
    _validate_run_integrity(workspace, manifest)
    parent = manifest["current_round"]
    stage3 = workspace.stage_dir(parent, "diagnose")
    if validate_completed_stage(stage3, get_stage_spec("diagnose")) is None:
        raise DiagnosisError("diagnose completion or hashes are invalid")
    revision_path = stage3 / "revision_request.json"
    revision = _read_json(revision_path)
    if (
        revision.get("schema_version") != "revision_request.v1"
        or revision.get("status") != "required"
        or revision.get("parent_round") != parent
    ):
        raise DiagnosisError("no reviewed typed revision request is available")
    before = _tree_hash(workspace.round_dir(parent))
    child = SpecFlowRound(
        parent + 1,
        parent_round=parent,
        revision_request_sha256=file_sha256(revision_path),
    )
    child_path = workspace.create_round(child)
    if _tree_hash(workspace.round_dir(parent)) != before:
        raise DiagnosisError("parent round changed while creating revision round")
    round_manifest = _read_json(child_path / "round.json")
    round_manifest["state"] = "index_ready"
    _write_json(child_path / "round.json", round_manifest)
    updated_manifest = _read_json(workspace.manifest_path)
    updated_manifest["review_state"] = "not_started"
    _write_json(workspace.manifest_path, updated_manifest)
    return child_path


def _request_diagnosis_candidate(
    model: DiagnosisModel,
    projection: Mapping[str, Any],
    *,
    track_d: bool,
) -> tuple[Dict[str, Any], list[Dict[str, Any]]]:
    traces = [row for row in projection.get("traces", []) if row.get("operation_id")]
    operation_ids = sorted({row["operation_id"] for row in traces})
    cycles = sorted(
        {row["failure_cycle"] for row in traces if row.get("failure_cycle") is not None}
    )
    object_ids = sorted(
        {
            obj["object_id"]
            for row in traces
            for obj in row.get("source_objects", [])
        }
    )
    state_ids = sorted(
        {
            state["state_id"]
            for row in traces
            for state in row.get("monitor_states", [])
        }
    )
    clauses = sorted(
        {
            row.get("spec_clause", {}).get("locator")
            for row in traces
            if row.get("spec_clause", {}).get("locator")
        }
    )
    evidence_refs = _allowed_evidence_refs(traces)
    if not operation_ids or not cycles or not object_ids or not clauses:
        # The model may still classify incomplete evidence, but it must use stable
        # placeholders that are already present in the projection.
        operation_ids = operation_ids or ["projection:missing_operation"]
        cycles = cycles or [-1]
        object_ids = object_ids or ["projection:missing_object"]
        clauses = clauses or ["projection:missing_clause"]
        evidence_refs = evidence_refs or ["projection:incomplete"]
    ranking_item = _strict_object(
        {
            "candidate_id": _string(),
            "object_id": _enum(object_ids),
            "rank_group": {"type": "integer", "minimum": 1},
            "evidence_refs": _nonempty_enum_array(evidence_refs),
        }
    )
    parameters = _strict_object(
        {
            "classification": _enum(sorted(DIAGNOSIS_CLASSIFICATIONS)),
            "operation_id": _enum(operation_ids),
            "failure_cycle": _enum(cycles),
            "object_ids": _nonempty_enum_array(object_ids),
            "monitor_state_ids": {
                "type": "array",
                "items": _enum(state_ids or ["not_required"]),
            },
            "spec_clause_locator": _enum(clauses),
            "evidence_refs": _nonempty_enum_array(evidence_refs),
            "summary": _string(),
            "ranked_source_candidates": {
                "type": "array",
                "minItems": 1 if track_d else 0,
                **({} if track_d else {"maxItems": 0}),
                "items": ranking_item,
            },
        }
    )
    tools = [
        {
            "name": "submit_diagnosis_candidate",
            "description": (
                "Classify only the supplied deterministic evidence. Do not emit a final "
                "verdict, patch, approval, or unlisted source location."
            ),
            "strict": True,
            "parameters": parameters,
        }
    ]
    context = {
        "task": (
            "classify exact projected formal evidence; "
            + (
                "submit at least one ranked source candidate"
                if track_d
                else "ranked_source_candidates must be an empty array outside Track D"
            )
        ),
        "projection": projection,
        "allowed_evidence_refs": evidence_refs,
        "track_d": track_d,
    }
    messages = [
        {
            "role": "system",
            "content": (
                "You are a bounded evidence classifier. Use exactly the required named "
                "tool. The final verdict and review are outside your authority."
            ),
        },
        {"role": "user", "content": json.dumps(context, sort_keys=True)},
    ]
    audit = []
    last_error = ""
    for attempt in range(2):
        if attempt:
            messages.append(
                {
                    "role": "user",
                    "content": "One bounded protocol repair is allowed: " + last_error,
                }
            )
        response = model.chat_with_tools(
            messages=messages,
            tools=tools,
            max_tokens=4096,
            temperature=0.0,
            tool_choice="required",
            parallel_tool_calls=False,
            usage_metadata={"stage": "diagnose", "task_type": "evidence_diagnosis"},
        )
        row = {
            "schema_version": "specflow_model_call.v1",
            "sequence": attempt + 1,
            "stage": "diagnose",
            "expected_tool": "submit_diagnosis_candidate",
            "attempt": attempt + 1,
            "parallel_tool_calls": False,
            "response_type": response.get("type"),
        }
        audit.append(row)
        calls = response.get("function_calls") if response.get("type") == "function_calls" else None
        if not isinstance(calls, list) or len(calls) != 1:
            last_error = "exactly one diagnosis function call is required"
            row.update({"outcome": "protocol_repair", "error": last_error})
            continue
        call = calls[0]
        if call.get("name") != "submit_diagnosis_candidate":
            last_error = "unexpected diagnosis tool"
            row.update({"outcome": "protocol_repair", "error": last_error})
            continue
        arguments = call.get("arguments")
        try:
            candidate = _validate_candidate(
                arguments,
                operation_ids,
                cycles,
                object_ids,
                state_ids,
                clauses,
                evidence_refs,
                track_d=track_d,
            )
        except DiagnosisError as exc:
            last_error = str(exc)
            row.update({"outcome": "validation_repair", "error": last_error})
            continue
        candidate["model_call_ref"] = str(
            call.get("id") or f"submit_diagnosis_candidate:{attempt + 1}"
        )
        body = dict(candidate)
        candidate["candidate_id"] = "diag_" + canonical_sha256(body)[:24]
        row.update(
            {
                "outcome": "accepted_candidate",
                "call_id": candidate["model_call_ref"],
                "classification": candidate["classification"],
            }
        )
        return candidate, audit
    raise DiagnosisError("diagnosis candidate repair exhausted: " + last_error)


def _validate_candidate(
    value: Any,
    operation_ids: list[str],
    cycles: list[int],
    object_ids: list[str],
    state_ids: list[str],
    clauses: list[str],
    evidence_refs: list[str],
    *,
    track_d: bool,
) -> Dict[str, Any]:
    fields = {
        "classification",
        "operation_id",
        "failure_cycle",
        "object_ids",
        "monitor_state_ids",
        "spec_clause_locator",
        "evidence_refs",
        "summary",
        "ranked_source_candidates",
    }
    if not isinstance(value, Mapping) or set(value) != fields:
        raise DiagnosisError("diagnosis candidate has an invalid exact schema")
    candidate = dict(value)
    if candidate["classification"] not in DIAGNOSIS_CLASSIFICATIONS:
        raise DiagnosisError("unknown diagnosis classification")
    if candidate["operation_id"] not in operation_ids:
        raise DiagnosisError("diagnosis references an unknown operation")
    if candidate["failure_cycle"] not in cycles:
        raise DiagnosisError("diagnosis references an unknown failure cycle")
    _validate_subset(candidate["object_ids"], object_ids, "object IDs", nonempty=True)
    _validate_subset(candidate["monitor_state_ids"], state_ids, "monitor states")
    if candidate["spec_clause_locator"] not in clauses:
        raise DiagnosisError("diagnosis references an unknown spec clause")
    _validate_subset(candidate["evidence_refs"], evidence_refs, "evidence refs", nonempty=True)
    if not isinstance(candidate["summary"], str) or not candidate["summary"].strip():
        raise DiagnosisError("diagnosis summary is required")
    ranking = candidate["ranked_source_candidates"]
    if not isinstance(ranking, list) or (track_d and not ranking) or (not track_d and ranking):
        raise DiagnosisError("ranked source candidates do not match Track D mode")
    seen = set()
    for row in ranking:
        if not isinstance(row, Mapping) or set(row) != {
            "candidate_id", "object_id", "rank_group", "evidence_refs"
        }:
            raise DiagnosisError("ranked source candidate is malformed")
        if row["candidate_id"] in seen or row["object_id"] not in object_ids:
            raise DiagnosisError("ranked source candidate identity is invalid")
        seen.add(row["candidate_id"])
        if not isinstance(row["rank_group"], int) or isinstance(row["rank_group"], bool) or row["rank_group"] < 1:
            raise DiagnosisError("rank group must be a positive integer")
        _validate_subset(row["evidence_refs"], evidence_refs, "ranking evidence", nonempty=True)
    candidate["schema_version"] = "diagnosis_candidate.v1"
    return candidate


def _validate_diagnosis_review(
    record: Mapping[str, Any],
    request: Mapping[str, Any],
    stage3: Path,
) -> None:
    if set(record) != _REVIEW_FIELDS or record.get("schema_version") != "diagnosis_review.v1":
        raise DiagnosisError("diagnosis review has an invalid exact schema")
    reviewer = record.get("reviewer")
    if reviewer != "codex" and not (
        isinstance(reviewer, str) and re.fullmatch(r"human:[A-Za-z0-9_.@-]+", reviewer)
    ):
        raise DiagnosisError("diagnosis reviewer must be codex or human:<id>")
    if record.get("decision") not in {"approved", "rejected"}:
        raise DiagnosisError("unknown diagnosis review decision")
    if not isinstance(record.get("reviewed_at"), str) or not re.fullmatch(
        r"[0-9]{4}-[0-9]{2}-[0-9]{2}(?:T[0-9:.+-]+Z?)?",
        record["reviewed_at"],
    ):
        raise DiagnosisError("diagnosis reviewed_at must be an ISO date or timestamp")
    if not isinstance(record.get("reason"), str) or not record["reason"].strip():
        raise DiagnosisError("diagnosis review reason is required")
    if not isinstance(record.get("evidence_refs"), list) or not record["evidence_refs"]:
        raise DiagnosisError("diagnosis review evidence refs are required")
    expected = {
        row["artifact"]: row["sha256"]
        for row in request.get("reviewed_hashes_required", [])
    }
    actual = {}
    for row in record.get("reviewed_hashes", []):
        if not isinstance(row, Mapping) or set(row) != {"artifact", "sha256"}:
            raise DiagnosisError("diagnosis reviewed hash row is malformed")
        if row["artifact"] in actual:
            raise DiagnosisError("duplicate diagnosis reviewed artifact")
        actual[row["artifact"]] = row["sha256"]
    if actual != expected:
        raise DiagnosisError("diagnosis review does not bind exact requested hashes")
    for artifact, digest in actual.items():
        if file_sha256(stage3 / artifact) != digest:
            raise DiagnosisError(f"diagnosis reviewed artifact drifted: {artifact}")


def _complete_diagnosis(
    workspace: Any,
    manifest: Mapping[str, Any],
    round_id: int,
    candidate: Mapping[str, Any],
    review: Mapping[str, Any],
    ranking: Mapping[str, Any],
    revision: Mapping[str, Any],
    verdict: Mapping[str, Any],
    *,
    model_calls: int,
) -> Dict[str, Any]:
    stage3 = workspace.stage_dir(round_id, "diagnose")
    projection = _read_json(stage3 / "evidence_projection.json")
    _write_json(stage3 / "diagnosis_candidate.json", candidate)
    _write_json(stage3 / "diagnosis_review.json", review)
    _write_json(stage3 / "source_ranking.json", ranking)
    _write_json(stage3 / "revision_request.json", revision)
    _write_json(stage3 / "final_verdict.json", verdict)
    _write_analysis(stage3, projection, candidate, review, verdict)
    result = write_stage_outcome(
        stage3,
        get_stage_spec("diagnose"),
        {
            "success": True,
            "status": "completed",
            "round_id": round_id,
            "model_calls": model_calls,
            "projection_status": projection["status"],
            "final_verdict": verdict["verdict"],
            "revision_status": revision["status"],
        },
        source_state=dict(manifest),
    )
    final_result = {
        "schema_version": "specflow_final_result.v1",
        "project_id": manifest["project_id"],
        "configuration_id": manifest["configuration_id"],
        "round_id": round_id,
        "verdict": verdict["verdict"],
        "final_verdict_ref": {
            "path": str((stage3 / "final_verdict.json").relative_to(workspace.run_dir)),
            "sha256": file_sha256(stage3 / "final_verdict.json"),
        },
        "diagnose_stage_result_sha256": file_sha256(stage3 / "stage_result.json"),
    }
    _write_json(workspace.final_result_path, final_result)
    _set_round_state(workspace, round_id, "completed")
    return result


def _build_revision_request(
    candidate: Mapping[str, Any],
    projection: Mapping[str, Any],
    stage2: Path,
    round_id: int,
    *,
    approved: bool,
) -> Dict[str, Any]:
    classification = candidate.get("classification")
    layer = _REVISION_LAYERS.get(classification) if approved else None
    if layer is None:
        return _not_required("revision_request.v1", "no_reviewed_asset_error")
    package_ref = _read_json(stage2 / "verification_package_ref.json")
    return {
        "schema_version": "revision_request.v1",
        "status": "required",
        "revision_layer": layer,
        "old_asset_sha256": package_ref["sha256"],
        "evidence_refs": list(candidate["evidence_refs"]),
        "allowed_change_scope": [
            f"round_local:{layer}",
            "round_local:dependent_monitor_ir",
        ],
        "parent_round": round_id,
        "forbidden_change_scope": [
            "design_source",
            "repository_property_assets",
            "prior_round",
        ],
        "evidence_projection_sha256": canonical_sha256(projection),
    }


def _build_source_ranking(
    candidate: Mapping[str, Any],
    projection: Mapping[str, Any],
    *,
    approved: bool,
) -> Dict[str, Any]:
    rows = candidate.get("ranked_source_candidates", [])
    if not approved or not rows:
        return _not_required("source_ranking.v1", "track_d_not_reviewed_or_disabled")
    anchors = {
        obj["object_id"]: obj["source_anchor"]
        for trace in projection.get("traces", [])
        for obj in trace.get("source_objects", [])
    }
    rtl_locations = {
        row["object_id"]: row["emitted_signal"]
        for row in projection.get("observation_map", {}).get("bindings", [])
    }
    normalized = []
    for row in rows:
        normalized.append(
            {
                **row,
                "source_anchor": anchors[row["object_id"]],
                "rtl_location": rtl_locations[row["object_id"]],
                "tie_rule": "shared_rank_group_then_candidate_id",
            }
        )
    normalized.sort(key=lambda row: (row["rank_group"], row["candidate_id"]))
    return {
        "schema_version": "source_ranking.v1",
        "status": "reviewed",
        "ordering": normalized,
        "tie_rule": "shared_rank_group_then_candidate_id",
    }


def _final_verdict(
    verdict: str,
    reason: str,
    round_id: int,
    stage2: Path,
    stage3: Path,
    *,
    model_calls: int,
    reviewed: bool = True,
    classification: Optional[str] = None,
) -> Dict[str, Any]:
    return {
        "schema_version": "final_verdict.v1",
        "verdict": verdict,
        "reason": reason,
        "round_id": round_id,
        "reviewed_diagnosis": reviewed,
        "diagnosis_classification": classification,
        "model_calls": model_calls,
        "evidence_refs": {
            "compile_verify_stage_result_sha256": file_sha256(
                stage2 / "stage_result.json"
            ),
            "evidence_projection_sha256": file_sha256(
                stage3 / "evidence_projection.json"
            ),
        },
    }


def _write_analysis(
    stage3: Path,
    projection: Mapping[str, Any],
    candidate: Mapping[str, Any],
    review: Optional[Mapping[str, Any]],
    verdict: Mapping[str, Any],
) -> None:
    lines = [
        "# SpecFlow Counterexample Analysis",
        "",
        f"- Projection status: `{projection.get('status')}`",
        f"- Diagnosis status: `{candidate.get('status', 'candidate')}`",
        f"- Classification: `{candidate.get('classification', 'not_required')}`",
        f"- Review decision: `{(review or {}).get('decision', 'not_required')}`",
        f"- Final verdict: `{verdict.get('verdict')}`",
    ]
    for trace in projection.get("traces", []):
        lines.extend(
            [
                "",
                f"## Operation `{trace.get('operation_id')}`",
                "",
                f"- Spec clause: `{trace.get('spec_clause', {}).get('locator')}`",
                f"- Failure cycle: `{trace.get('failure_cycle')}`",
                f"- Failure time: `{trace.get('failure_time')}`",
            ]
        )
        for obj in trace.get("source_objects", []):
            anchor = obj["source_anchor"]
            lines.append(
                f"- Object `{obj['name']}` (`{obj['object_id']}`): "
                f"`{anchor['path']}:{anchor['line_start']}`"
            )
        for state in trace.get("monitor_states", []):
            lines.append(
                f"- Monitor state `{state['state_id']}` at failure: "
                f"`{state['failure_value']}`"
            )
    (stage3 / "counterexample_analysis.md").write_text(
        "\n".join(lines) + "\n", encoding="utf-8"
    )


def _is_accepted_without_diagnosis(
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


def _requires_model(result_map: Mapping[str, Any]) -> bool:
    return (
        result_map.get("execution_status") == "completed"
        and result_map.get("formal_outcome") == "cex"
        and any(
            row.get("status") == "cex"
            and row.get("observed_property_id")
            and row.get("trace_path")
            for row in result_map.get("operation_results", [])
        )
    )


def _allowed_evidence_refs(traces: list[Mapping[str, Any]]) -> list[str]:
    refs = set()
    for trace in traces:
        operation = trace["operation_id"]
        refs.add("operation:" + operation)
        if trace.get("failure_cycle") is not None:
            refs.add(f"trace_cycle:{operation}:{trace['failure_cycle']}")
        locator = trace.get("spec_clause", {}).get("locator")
        if locator:
            refs.add("spec:" + locator)
        for obj in trace.get("source_objects", []):
            refs.add("object:" + obj["object_id"])
            anchor = obj["source_anchor"]
            refs.add(f"source:{anchor['path']}:{anchor['line_start']}")
        for state in trace.get("monitor_states", []):
            refs.add("state:" + state["state_id"])
    return sorted(refs)


def _validate_subset(
    value: Any,
    allowed: list[str],
    label: str,
    *,
    nonempty: bool = False,
) -> None:
    if not isinstance(value, list) or (nonempty and not value):
        raise DiagnosisError(f"{label} must be a {'non-empty ' if nonempty else ''}list")
    if len(value) != len(set(value)) or not set(value) <= set(allowed):
        raise DiagnosisError(f"{label} contain duplicates or unknown identities")


def _not_required(schema_version: str, reason: str) -> Dict[str, Any]:
    return {"schema_version": schema_version, "status": "not_required", "reason": reason}


def _set_round_state(workspace: Any, round_id: int, state: str) -> None:
    path = workspace.round_dir(round_id) / "round.json"
    value = _read_json(path)
    value["state"] = state
    _write_json(path, value)


def _tree_hash(root: Path) -> str:
    digest = hashlib.sha256()
    for path in sorted(item for item in Path(root).rglob("*") if item.is_file()):
        digest.update(path.relative_to(root).as_posix().encode("utf-8"))
        digest.update(b"\0")
        digest.update(bytes.fromhex(file_sha256(path)))
    return digest.hexdigest()


def _strict_object(properties: Mapping[str, Any]) -> Dict[str, Any]:
    return {
        "type": "object",
        "properties": dict(properties),
        "required": list(properties),
        "additionalProperties": False,
    }


def _string() -> Dict[str, Any]:
    return {"type": "string", "minLength": 1}


def _enum(values: list[Any]) -> Dict[str, Any]:
    return {"type": "string" if all(isinstance(row, str) for row in values) else "integer", "enum": values}


def _nonempty_enum_array(values: list[str]) -> Dict[str, Any]:
    return {"type": "array", "minItems": 1, "items": _enum(values)}


def _read_json(path: Path) -> Dict[str, Any]:
    try:
        value = json.loads(Path(path).read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as exc:
        raise DiagnosisError(f"cannot read JSON object: {path}") from exc
    if not isinstance(value, dict):
        raise DiagnosisError(f"JSON object required: {path}")
    return value


def _write_json(path: Path, value: Mapping[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + ".tmp")
    temporary.write_text(
        json.dumps(value, indent=2, sort_keys=True) + "\n", encoding="utf-8"
    )
    temporary.replace(path)


def _write_jsonl(path: Path, rows: list[Mapping[str, Any]]) -> None:
    if path.exists():
        raise DiagnosisError(f"model call log already exists: {path}")
    path.write_text(
        "".join(json.dumps(row, sort_keys=True) + "\n" for row in rows),
        encoding="utf-8",
    )
