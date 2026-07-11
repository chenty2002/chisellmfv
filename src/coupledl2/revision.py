"""Verification-asset revision lineage and Stage-5 approval gates."""

from __future__ import annotations

from typing import Any, Dict
import re


REVISION_TARGETS = {"property_schema", "template", "binding", "formal_contract", "environment", "design"}


def validate_revision_request(payload: Dict[str, Any]) -> Dict[str, Any]:
    if payload.get("schema_version") != "revision_request.v2" or not isinstance(payload.get("requests"), list):
        raise ValueError("invalid revision_request.v2")
    for item in payload["requests"]:
        if item.get("revision_target") not in REVISION_TARGETS:
            raise ValueError("invalid revision target")
        for key in ("parent_run_id", "property", "old_asset_sha256", "reason", "evidence_refs"):
            if key not in item:
                raise ValueError(f"revision request missing {key}")
        if not re.fullmatch(r"[0-9a-f]{64}", str(item["old_asset_sha256"])):
            raise ValueError("revision request requires a concrete old asset hash")
        if not isinstance(item["evidence_refs"], list) or not item["evidence_refs"]:
            raise ValueError("revision request requires evidence refs")
    return payload


def design_bug_is_eligible(diagnosis: Dict[str, Any], *, package_approved: bool, formal_contract_approved: bool, reconstruction_available: bool) -> bool:
    return diagnosis.get("classification") == "design_bug" and diagnosis.get("revision_target") == "design_source" and package_approved and formal_contract_approved and reconstruction_available


def revision_outcome(parent_run_id: str, rerun_id: str, property_label: str, old_hash: str, new_hash: str, status: str) -> Dict[str, Any]:
    allowed = {"cex_disappeared", "cex_persists", "inconclusive", "excluded_by_environment"}
    if status not in allowed or old_hash == new_hash:
        raise ValueError("invalid revision outcome")
    return {"schema_version": "revision_outcome.v1", "parent_run_id": parent_run_id, "rerun_id": rerun_id, "property": property_label, "old_asset_sha256": old_hash, "new_asset_sha256": new_hash, "outcome": status}


def validate_revision_outcome_set(payload: Dict[str, Any]) -> Dict[str, Any]:
    required = {
        "schema_version", "parent_run_id", "rerun_id", "revision_target",
        "asset_id", "old_asset_sha256", "new_asset_sha256", "review", "properties",
    }
    if not isinstance(payload, dict) or set(payload) != required:
        raise ValueError("invalid revision outcome set fields")
    if payload["schema_version"] != "revision_outcome_set.v1":
        raise ValueError("unsupported revision outcome set")
    for key in ("old_asset_sha256", "new_asset_sha256"):
        if not re.fullmatch(r"[0-9a-f]{64}", str(payload[key])):
            raise ValueError("revision outcome requires concrete asset hashes")
    if payload["old_asset_sha256"] == payload["new_asset_sha256"]:
        raise ValueError("revision outcome requires an asset change")
    review = payload["review"]
    if review.get("reviewer") != "codex" or review.get("approved") is not True:
        raise ValueError("revision outcome requires Codex approval")
    properties = payload["properties"]
    if not isinstance(properties, list) or not properties:
        raise ValueError("revision outcome requires property results")
    allowed = {"cex_disappeared", "cex_persists", "inconclusive", "excluded_by_environment"}
    labels = []
    for item in properties:
        if set(item) != {"property", "parent_status", "rerun_status", "outcome", "reason"}:
            raise ValueError("invalid property revision outcome")
        if item["outcome"] not in allowed:
            raise ValueError("invalid revision outcome")
        labels.append(item["property"])
    if len(labels) != len(set(labels)):
        raise ValueError("duplicate property revision outcome")
    return payload
