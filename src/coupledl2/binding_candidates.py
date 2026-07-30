"""Merge reviewed anchors with fail-closed, source-indexed binding candidates."""

from __future__ import annotations

import re
from dataclasses import replace
from pathlib import Path
from typing import Any, Dict, Iterable

from .property_catalog import PropertyCatalog


def build_binding_catalog(
    catalog: PropertyCatalog,
    context_indexes: Dict[str, Dict[str, Any]],
) -> PropertyCatalog:
    """Return a run-local catalog containing only renderable compatible candidates."""
    candidates = dict(catalog.candidates)
    allowed_paths = {
        "workspace/case/" + catalog.profile["target"]["relative_path"],
        *(
            "workspace/case/" + item["relative_path"]
            for item in catalog.profile.get("source_targets", [])
        ),
    }
    slots = {
        role: definition
        for template in catalog.templates.values()
        for role, definition in template["slots"].items()
    }
    for index_name in ("tl_signal_index", "observer_index"):
        for raw in context_indexes.get(index_name, {}).get("candidates", []):
            candidate = _adapt_index_candidate(raw, slots, allowed_paths, index_name)
            if candidate is not None:
                candidates.setdefault(candidate["candidate_id"], candidate)
    return replace(catalog, candidates=candidates)


def compatible_candidates(
    catalog: PropertyCatalog,
    role: str,
    expected_type: str,
) -> list[Dict[str, Any]]:
    """Return a stable list satisfying type, semantic role, scope and domain gates."""
    return sorted(
        (
            candidate
            for candidate in catalog.candidates.values()
            if role in candidate.get("roles", [])
            and candidate.get("type") == expected_type
            and candidate.get("clock_domain", "implicit_module_clock")
            == "implicit_module_clock"
            and candidate.get("reset_domain", "implicit_module_reset")
            == "implicit_module_reset"
        ),
        key=lambda item: item["candidate_id"],
    )


def _adapt_index_candidate(
    raw: Dict[str, Any],
    slots: Dict[str, Dict[str, Any]],
    allowed_paths: set[str],
    index_name: str,
) -> Dict[str, Any] | None:
    provenance = raw.get("provenance") or {}
    path = provenance.get("path")
    expression = provenance.get("expression")
    candidate_type = raw.get("chisel_type") or raw.get("type")
    if (
        path not in allowed_paths
        or candidate_type not in {"Bool", "UInt"}
        or not isinstance(expression, str)
        or not expression
        or raw.get("observable") is not True
    ):
        return None
    roles = sorted(
        role
        for role, definition in slots.items()
        if definition["type"] == candidate_type
        and _role_matches(role, expression, raw.get("roles", []))
    )
    if not roles:
        return None
    return {
        "candidate_id": raw["candidate_id"],
        "expression": expression,
        "type": candidate_type,
        "roles": roles,
        "description": raw.get("description", "Indexed source candidate."),
        "width": raw.get("width"),
        "width_source": raw.get("width_source", "not_inferred"),
        "clock_domain": raw.get("clock_domain", "implicit_module_clock"),
        "reset_domain": raw.get("reset_domain", "implicit_module_reset"),
        "scope": raw.get("scope", provenance.get("module")),
        "provenance": {
            "kind": "source_index",
            "index": index_name,
            "path": (
                path[len("workspace/case/"):]
                if path.startswith("workspace/case/")
                else path
            ),
            "line": provenance.get("line"),
            "enclosing_symbol": provenance.get("module"),
            "source_identity": raw.get("source_identity"),
        },
    }


def _role_matches(role: str, expression: str, raw_roles: Iterable[str]) -> bool:
    """Conservative semantic match; ambiguous source references are excluded."""
    normalized = _snake(expression)
    tokens = set(normalized.split("_")) | {
        str(item).lower() for item in raw_roles
    }
    role_tokens = [token for token in role.lower().split("_") if token]
    if role.lower() == normalized or all(token in tokens for token in role_tokens):
        return True
    if role == "trigger":
        return normalized.endswith(("_valid", "_fire"))
    if role.endswith("_fire"):
        subject = role[:-len("_fire")]
        return normalized.endswith("_fire") and subject in tokens
    if role == "observed_data":
        return "data" in tokens and ("d" in tokens or "response" in tokens)
    return False


def _snake(value: str) -> str:
    value = re.sub(r"([a-z0-9])([A-Z])", r"\1_\2", value)
    return re.sub(r"[^A-Za-z0-9]+", "_", value).strip("_").lower()
