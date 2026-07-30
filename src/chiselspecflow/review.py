"""External Stage-1 review gate and canonical verification-package creation."""

from __future__ import annotations

import json
import re
from pathlib import Path
from typing import Any, Dict, Mapping

from src.core.artifact_contract import file_sha256, write_stage_outcome
from src.core.formal_operations import canonical_sha256

from .assets import load_run_local_package
from .config import REVIEW_RECORD_SCHEMA, VERIFICATION_PACKAGE_SCHEMA
from .stages import get_stage_spec


class ReviewError(ValueError):
    """Raised when an external review does not bind the full run-local intent."""


_REVIEW_FIELDS = {
    "schema_version",
    "reviewer",
    "decision",
    "reviewed_hashes",
    "evidence_refs",
    "semantic_intent_decisions",
    "reviewed_at",
    "reason",
}


def install_review(run_dir: Path, review_record_path: Path) -> Dict[str, Any]:
    """Validate one human/Codex review and complete Stage 1 if approved."""

    run_dir = Path(run_dir).resolve()
    manifest_path = run_dir / "manifest.json"
    manifest = _read_json(manifest_path)
    if manifest.get("review_state") != "awaiting_review":
        raise ReviewError("run is not awaiting review")
    round_id = 1
    stage_dir = run_dir / "stages" / "01_asset_authoring"
    request = _read_json(stage_dir / "review_request.json")
    candidates = _read_json(stage_dir / "authoring_candidates.json")
    stage_inputs = _read_json(stage_dir / "stage_inputs.json")
    delta = _read_json(stage_dir / "candidate_asset_delta.json")
    record = _read_json(Path(review_record_path))
    _validate_review_record(record, request, stage_dir)
    _write_json(stage_dir / "review_record.json", record)

    if record["decision"] == "rejected":
        result = write_stage_outcome(
            stage_dir,
            get_stage_spec("asset_authoring"),
            {
                "success": False,
                "status": "rejected",
                "error_kind": "review_rejected",
                "round_id": round_id,
                "model_calls": _model_call_count(candidates),
                "reason": record["reason"],
            },
            source_state=manifest,
        )
        _update_run_state(run_dir, "rejected")
        return result

    package_body = {
        "schema_version": VERIFICATION_PACKAGE_SCHEMA,
        "project_id": stage_inputs["project"]["project_id"],
        "configuration_id": stage_inputs["configuration"]["configuration_id"],
        "round_id": round_id,
        "input_hashes": stage_inputs["input_hashes"],
        "asset_library": stage_inputs["asset_library"],
        "obligations": candidates["obligations"],
        "bindings": candidates["bindings"],
        "monitors": candidates["monitors"],
        "review": {
            "review_record_sha256": file_sha256(stage_dir / "review_record.json"),
            "reviewer": record["reviewer"],
            "reviewed_at": record["reviewed_at"],
            "semantic_intent_decisions": record["semantic_intent_decisions"],
        },
    }
    package = dict(package_body)
    package["package_id"] = "vpkg_" + canonical_sha256(package_body)[:24]
    # Keep the exact schema order independent: package_id is part of the frozen shape.
    package = {
        "schema_version": package.pop("schema_version"),
        "package_id": package.pop("package_id"),
        **package,
    }
    _write_json(stage_dir / "verification_package.json", package)
    load_run_local_package(stage_dir / "verification_package.json")
    result = write_stage_outcome(
        stage_dir,
        get_stage_spec("asset_authoring"),
        {
            "success": True,
            "status": "completed",
            "round_id": round_id,
            "model_calls": _model_call_count(candidates),
            "package_id": package["package_id"],
            "verification_package_sha256": file_sha256(stage_dir / "verification_package.json"),
        },
        source_state=manifest,
    )
    _update_run_state(run_dir, "approved")
    return result


def build_review_record_template(run_dir: Path, reviewer: str = "codex") -> Dict[str, Any]:
    """Build an unapproved template; a reviewer must supply decisions/evidence."""

    run_dir = Path(run_dir).resolve()
    stage_dir = run_dir / "stages" / "01_asset_authoring"
    request = _read_json(stage_dir / "review_request.json")
    reviewed_hashes = list(request["reviewed_hashes_required"])
    reviewed_hashes.append(
        {"artifact": "review_request.json", "sha256": file_sha256(stage_dir / "review_request.json")}
    )
    return {
        "schema_version": REVIEW_RECORD_SCHEMA,
        "reviewer": reviewer,
        "decision": "rejected",
        "reviewed_hashes": reviewed_hashes,
        "evidence_refs": [],
        "semantic_intent_decisions": [
            {"asset_id": asset_id, "decision": "rejected", "reason": "review required"}
            for asset_id in request["semantic_intent_ids"]
        ],
        "reviewed_at": "",
        "reason": "complete this template through an external Codex/human review",
    }


def _validate_review_record(record: Mapping[str, Any], request: Mapping[str, Any], stage_dir: Path) -> None:
    if set(record) != _REVIEW_FIELDS or record.get("schema_version") != REVIEW_RECORD_SCHEMA:
        raise ReviewError("review record has an invalid exact schema")
    reviewer = record.get("reviewer")
    if reviewer != "codex" and not (
        isinstance(reviewer, str)
        and re.fullmatch(r"human:[A-Za-z0-9_.@-]+", reviewer)
    ):
        raise ReviewError("reviewer must be codex or human:<id>; API-model reviewers are forbidden")
    if record.get("decision") not in {"approved", "rejected"}:
        raise ReviewError("unknown review decision")
    if not isinstance(record.get("reviewed_at"), str) or not re.fullmatch(
        r"[0-9]{4}-[0-9]{2}-[0-9]{2}(?:T[0-9:.+-]+Z?)?",
        record["reviewed_at"],
    ):
        raise ReviewError("reviewed_at must be an ISO date or timestamp")
    if not isinstance(record.get("reason"), str) or not record["reason"].strip():
        raise ReviewError("review reason is required")
    evidence = record.get("evidence_refs")
    if not isinstance(evidence, list) or not evidence or any(not isinstance(item, str) or not item.strip() for item in evidence):
        raise ReviewError("review evidence_refs must be non-empty")
    expected_hashes = {
        row["artifact"]: row["sha256"]
        for row in request.get("reviewed_hashes_required", [])
    }
    expected_hashes["review_request.json"] = file_sha256(stage_dir / "review_request.json")
    actual_hashes: Dict[str, str] = {}
    reviewed_hashes = record.get("reviewed_hashes")
    if not isinstance(reviewed_hashes, list):
        raise ReviewError("reviewed_hashes must be a list")
    for row in reviewed_hashes:
        if not isinstance(row, Mapping) or set(row) != {"artifact", "sha256"}:
            raise ReviewError("reviewed hash row is malformed")
        if row["artifact"] in actual_hashes:
            raise ReviewError("duplicate reviewed artifact hash")
        actual_hashes[row["artifact"]] = row["sha256"]
    if actual_hashes != expected_hashes:
        raise ReviewError("reviewed hashes do not exactly bind the review request")
    for artifact, digest in actual_hashes.items():
        if file_sha256(stage_dir / artifact) != digest:
            raise ReviewError(f"reviewed artifact hash drifted: {artifact}")
    decisions = record.get("semantic_intent_decisions")
    if not isinstance(decisions, list):
        raise ReviewError("semantic_intent_decisions must be a list")
    by_id = {}
    for row in decisions:
        if not isinstance(row, Mapping) or set(row) != {"asset_id", "decision", "reason"}:
            raise ReviewError("semantic intent decision is malformed")
        if row["asset_id"] in by_id or row["decision"] not in {"approved", "rejected"} or not str(row["reason"]).strip():
            raise ReviewError("semantic intent decision is duplicate or invalid")
        by_id[row["asset_id"]] = row["decision"]
    expected_ids = set(request.get("semantic_intent_ids", []))
    if set(by_id) != expected_ids:
        raise ReviewError("review does not cover every run-local semantic intent")
    if record["decision"] == "approved" and set(by_id.values()) != {"approved"}:
        raise ReviewError("approved review contains a rejected semantic intent")


def _model_call_count(candidates: Mapping[str, Any]) -> int:
    return len(candidates.get("model_call_refs", []))


def _update_run_state(run_dir: Path, review_state: str) -> None:
    manifest_path = run_dir / "manifest.json"
    manifest = _read_json(manifest_path)
    manifest["review_state"] = review_state
    _write_json(manifest_path, manifest)


def _read_json(path: Path) -> Dict[str, Any]:
    try:
        value = json.loads(Path(path).read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as exc:
        raise ReviewError(f"cannot read JSON object: {path}") from exc
    if not isinstance(value, dict):
        raise ReviewError(f"JSON object required: {path}")
    return value


def _write_json(path: Path, value: Mapping[str, Any]) -> None:
    temporary = Path(path).with_name(Path(path).name + ".tmp")
    temporary.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    temporary.replace(path)
