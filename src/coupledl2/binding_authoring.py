"""Offline, run-local drafting support for reviewed CoupledL2 property bindings."""

from __future__ import annotations

import json
import copy
from pathlib import Path
from typing import Any, Dict, Iterable, List

from .property_catalog import PropertyCatalog


def build_gold_binding_manifest(
    catalog: PropertyCatalog, property_schema_id: str
) -> Dict[str, Any]:
    """Convert one reviewed gold binding into the production manifest contract."""
    if catalog.review is None or catalog.review["review_status"] != "approved":
        raise ValueError("gold binding requires an approved property package")
    if catalog.gold_bindings is None:
        raise ValueError("property profile has no reviewed gold binding list")
    try:
        binding = catalog.gold_bindings["bindings"][property_schema_id]
    except KeyError as exc:
        raise ValueError(f"unknown gold property schema: {property_schema_id}") from exc
    return {
        "schema_version": "binding_manifest.v1",
        "property_profile_id": catalog.profile["property_profile_id"],
        "instances": [
            {
                "instance_id": property_schema_id.lower(),
                "property_schema_id": property_schema_id,
                "template_id": binding["template_id"],
                "target": {
                    "file_id": catalog.profile["target"]["file_id"],
                    "marker_id": catalog.profile["target"]["marker_id"],
                },
                "bindings": copy.deepcopy(binding["bindings"]),
                "parameters": copy.deepcopy(binding["parameters"]),
                "base_label": binding["base_label"],
                "evidence": copy.deepcopy(binding["evidence"]),
            }
        ],
    }


def write_binding_draft(
    draft_root: Path,
    *,
    rule_id: str,
    property_schema_id: str,
    candidates: Iterable[Dict[str, Any]],
) -> Path:
    """Persist an unreviewed candidate draft without touching repository assets."""
    selected = [
        _public_candidate(candidate)
        for candidate in candidates
        if isinstance(candidate, dict) and candidate.get("observable") is True
    ]
    selected.sort(key=lambda item: item["candidate_id"])
    draft = {
        "schema_version": "binding_draft.v1",
        "rule_id": rule_id,
        "property_schema_id": property_schema_id,
        "review_status": "not_reviewed",
        "authoring_scope": "run_local",
        "candidate_count": len(selected),
        "candidates": selected[:64],
    }
    draft_root.mkdir(parents=True, exist_ok=True)
    path = draft_root / f"{property_schema_id.lower()}_binding_draft.json"
    path.write_text(
        json.dumps(draft, indent=2, sort_keys=True) + "\n", encoding="utf-8"
    )
    return path


def model_choice_eligible_candidates(
    candidates: Iterable[Dict[str, Any]], *, role: str, chisel_type: str
) -> List[str]:
    """Return candidates only when a real compatible choice exists for one slot."""
    compatible = sorted(
        candidate["candidate_id"]
        for candidate in candidates
        if role in candidate.get("roles", [])
        and candidate.get("type") == chisel_type
        and candidate.get("approved") is True
    )
    return compatible if len(compatible) >= 2 else []


def evaluate_gold_binding_recall(
    catalog: PropertyCatalog, gold_bindings: Dict[str, Any]
) -> Dict[str, Any]:
    """Calculate bounded ranking evidence from reviewed authoring trials."""
    trials = gold_bindings["selection_trials"]
    selectable = {
        role
        for template in catalog.templates.values()
        for role, definition in template["slots"].items()
        if sum(
            role in candidate["roles"] and candidate["type"] == definition["type"]
            for candidate in catalog.candidates.values()
        ) >= 2
    }
    evaluated = [trial for trial in trials if trial["slot"] in selectable]
    count = len(evaluated)
    if count == 0:
        return {
            "evaluated_slot_count": 0,
            "top_1_recall": 0.0,
            "top_3_recall": 0.0,
            "manual_correction_count": 0,
        }
    ranks = [
        trial["ranked_candidate_ids"].index(trial["gold_candidate_id"]) + 1
        for trial in evaluated
    ]
    return {
        "evaluated_slot_count": count,
        "top_1_recall": sum(rank <= 1 for rank in ranks) / count,
        "top_3_recall": sum(rank <= 3 for rank in ranks) / count,
        "manual_correction_count": sum(
            trial["manual_corrected"] for trial in evaluated
        ),
    }


def _public_candidate(candidate: Dict[str, Any]) -> Dict[str, Any]:
    keys = (
        "candidate_id", "type", "roles", "description", "source_identity",
        "chisel_type", "width_source", "clock_domain", "channel",
        "handshake_phase", "observable", "provenance",
    )
    return {key: candidate[key] for key in keys if key in candidate}
