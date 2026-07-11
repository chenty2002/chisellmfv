"""Offline, run-local drafting support for reviewed CoupledL2 property bindings."""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any, Dict, Iterable, List


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


def _public_candidate(candidate: Dict[str, Any]) -> Dict[str, Any]:
    keys = (
        "candidate_id", "type", "roles", "description", "source_identity",
        "chisel_type", "width_source", "clock_domain", "channel",
        "handshake_phase", "observable", "provenance",
    )
    return {key: candidate[key] for key in keys if key in candidate}
