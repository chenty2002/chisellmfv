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

from .stages import get_stage_spec
from .causal_backend import materialize_causal_evidence
from .causal_loop import (
    run_causal_evidence_loop,
    write_not_required_loop_artifacts,
)
from .trace_projection import _load_certified_package, project_stage2_evidence
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
    causal_manifest, causal_source, causal_graphs = materialize_causal_evidence(
        workspace,
        round_id,
        projection,
        config=manifest.get("diagnosis"),
    )
    _write_json(stage3 / "causal_graph_manifest.json", causal_manifest)
    _write_json(stage3 / "causal_source_projection.json", causal_source)
    result_map = _read_json(stage2 / "property_result_map.json")
    semantic_evidence = _read_json(stage2 / "semantic_evidence.json")

    if _is_accepted_without_diagnosis(result_map, semantic_evidence):
        write_not_required_loop_artifacts(
            stage3,
            reason="all_primary_proven",
            graph_manifest=causal_manifest,
            source_projection=causal_source,
        )
        candidate = _not_required("diagnosis_candidate.v2", "all_primary_proven")
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
        write_not_required_loop_artifacts(
            stage3,
            reason="tool_or_identity_failure_is_deterministic",
            graph_manifest=causal_manifest,
            source_projection=causal_source,
        )
        candidate = _not_required(
            "diagnosis_candidate.v2", "tool_or_identity_failure_is_deterministic"
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
    package, _package_path = _load_certified_package(stage2)
    candidate, loop_counts = run_causal_evidence_loop(
        model,
        projection=projection,
        graph_manifest=causal_manifest,
        source_projection=causal_source,
        graphs=causal_graphs,
        reviewed_package=package,
        frozen_clauses=_frozen_clause_context(workspace, projection),
        project_root=workspace.project_workspace,
        stage3=stage3,
        track_d=track_d,
    )
    _write_json(stage3 / "diagnosis_candidate.json", candidate)
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
        model_calls=loop_counts["model_calls"],
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
            "model_calls": loop_counts["model_calls"],
            "evidence_queries": loop_counts["evidence_queries"],
            "protocol_repairs": loop_counts["protocol_repairs"],
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


def _frozen_clause_context(
    workspace: Any, projection: Mapping[str, Any]
) -> list[Dict[str, str]]:
    """Recover only the hash-bound clause slices referenced by CEX evidence."""

    clauses = {
        str(trace["spec_clause"]["locator"]): str(
            trace["spec_clause"]["text_sha256"]
        )
        for trace in projection.get("traces", [])
        if isinstance(trace.get("spec_clause"), Mapping)
        and trace["spec_clause"].get("locator")
        and trace["spec_clause"].get("text_sha256")
    }
    lines = (workspace.inputs_dir / "specification.md").read_text(
        encoding="utf-8"
    ).splitlines()
    rows = []
    for locator, expected_sha256 in sorted(clauses.items()):
        matches = [
            index
            for index, line in enumerate(lines)
            if re.search(rf"\b{re.escape(locator)}\b", line)
        ]
        if not matches:
            raise DiagnosisError(f"frozen spec clause is missing: {locator}")
        index = matches[0]
        parts = [lines[index].strip()]
        index += 1
        while (
            index < len(lines)
            and lines[index].strip()
            and not lines[index].lstrip().startswith(("- **", "| `"))
        ):
            parts.append(lines[index].strip())
            index += 1
        text = " ".join(parts)
        actual = hashlib.sha256(text.encode("utf-8")).hexdigest()
        if actual != expected_sha256:
            raise DiagnosisError(f"frozen spec clause hash drifted: {locator}")
        rows.append(
            {
                "locator": locator,
                "text": text,
                "text_sha256": actual,
            }
        )
    return rows


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
