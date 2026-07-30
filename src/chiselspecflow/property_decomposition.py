"""Reviewed public-property to source-component identity contracts."""

from __future__ import annotations

import json
import re
from pathlib import Path
from typing import Any, Dict, Mapping


SCHEMA_VERSION = "specflow_property_decomposition"
_ROW_FIELDS = {
    "expected_property_id",
    "decomposition_kind",
    "component_ids",
    "clause_refs",
    "rationale",
}
_FIELDS = {
    "schema_version",
    "specification_id",
    "spec_sha256",
    "reviewer",
    "reviewed_at",
    "decision",
    "evidence_refs",
    "component_groups",
    "role_hints",
    "rows",
}
_ROLES = {
    "primary_assertion",
    "activation_cover",
    "observer_cover",
    "state_cover",
    "assumption_sat",
}
_ID_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_.:-]*$")


class PropertyDecompositionError(ValueError):
    """Raised when component identity is incomplete, ambiguous, or unreviewed."""


def load_property_decomposition(
    path: Path, public_spec: Mapping[str, Any]
) -> Dict[str, Any]:
    try:
        value = json.loads(Path(path).read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as exc:
        raise PropertyDecompositionError(f"cannot read property decomposition: {path}") from exc
    if not isinstance(value, dict):
        raise PropertyDecompositionError("property decomposition must be an object")
    return validate_property_decomposition(value, public_spec)


def build_identity_decomposition(public_spec: Mapping[str, Any]) -> Dict[str, Any]:
    """Build the deterministic one-property/one-component default contract."""

    rows = [
        {
            "expected_property_id": property_id,
            "decomposition_kind": "identity",
            "component_ids": [property_id],
            "clause_refs": [],
            "rationale": "The reviewed public property is lowered as one source component.",
        }
        for property_id in public_spec["expected_property_ids"]
    ]
    value = {
        "schema_version": SCHEMA_VERSION,
        "specification_id": public_spec["specification_id"],
        "spec_sha256": public_spec["spec_sha256"],
        "reviewer": public_spec["review"]["reviewer"],
        "reviewed_at": public_spec["review"]["reviewed_at"],
        "decision": "approved",
        "evidence_refs": list(public_spec["authority_refs"]),
        "component_groups": {},
        "role_hints": {},
        "rows": rows,
    }
    return validate_property_decomposition(value, public_spec)


def validate_property_decomposition(
    value: Mapping[str, Any], public_spec: Mapping[str, Any]
) -> Dict[str, Any]:
    if not isinstance(value, Mapping) or set(value) != _FIELDS:
        raise PropertyDecompositionError("property decomposition has an invalid exact schema")
    if (
        value.get("schema_version") != SCHEMA_VERSION
        or value.get("specification_id") != public_spec.get("specification_id")
        or value.get("spec_sha256") != public_spec.get("spec_sha256")
    ):
        raise PropertyDecompositionError("property decomposition identity mismatch")
    if value.get("reviewer") != "codex" or value.get("decision") != "approved":
        raise PropertyDecompositionError("property decomposition is not Codex-approved")
    if not re.fullmatch(r"[0-9]{4}-[0-9]{2}-[0-9]{2}", str(value.get("reviewed_at"))):
        raise PropertyDecompositionError("property decomposition review date is invalid")
    evidence = value.get("evidence_refs")
    if not isinstance(evidence, list) or not evidence or any(not _text(row) for row in evidence):
        raise PropertyDecompositionError("property decomposition requires review evidence")

    expected = list(public_spec.get("expected_property_ids", []))
    clauses = set(public_spec.get("normative_clause_ids", []))
    rows = value.get("rows")
    if not isinstance(rows, list) or not rows:
        raise PropertyDecompositionError("property decomposition rows are empty")
    by_expected: Dict[str, Mapping[str, Any]] = {}
    component_owner: Dict[str, str] = {}
    normalized_rows = []
    for row in rows:
        if not isinstance(row, Mapping) or set(row) != _ROW_FIELDS:
            raise PropertyDecompositionError("property decomposition row fields differ")
        property_id = row.get("expected_property_id")
        if property_id not in expected or property_id in by_expected:
            raise PropertyDecompositionError("expected property is unknown or duplicated")
        kind = row.get("decomposition_kind")
        if kind not in {"identity", "conjunctive", "coverage_partition"}:
            raise PropertyDecompositionError("property decomposition kind is invalid")
        components = row.get("component_ids")
        if (
            not isinstance(components, list)
            or not components
            or any(not _text(component) or not _ID_RE.fullmatch(component) for component in components)
            or len(set(components)) != len(components)
        ):
            raise PropertyDecompositionError("component IDs are malformed or duplicated")
        if kind == "identity" and components != [property_id]:
            raise PropertyDecompositionError("identity decomposition must preserve the public ID")
        for component in components:
            owner = component_owner.setdefault(component, property_id)
            if owner != property_id:
                raise PropertyDecompositionError("component ID belongs to multiple public properties")
        clause_refs = row.get("clause_refs")
        if (
            not isinstance(clause_refs, list)
            or any(clause not in clauses for clause in clause_refs)
            or len(set(clause_refs)) != len(clause_refs)
        ):
            raise PropertyDecompositionError("component clause references are invalid")
        if not _text(row.get("rationale")):
            raise PropertyDecompositionError("component decomposition rationale is required")
        by_expected[property_id] = row
        normalized_rows.append(dict(row))
    if set(by_expected) != set(expected):
        raise PropertyDecompositionError("not every public expected property is accounted")
    all_components = set(component_owner)
    role_hints = value.get("role_hints")
    if (
        not isinstance(role_hints, Mapping)
        or any(component not in all_components or role not in _ROLES for component, role in role_hints.items())
    ):
        raise PropertyDecompositionError("component role hints are invalid")
    groups = value.get("component_groups")
    if not isinstance(groups, Mapping):
        raise PropertyDecompositionError("component groups must be an object")
    grouped = set()
    for primary, members in groups.items():
        if (
            primary not in all_components
            or role_hints.get(primary) != "primary_assertion"
            or not isinstance(members, list)
            or primary not in members
            or len(set(members)) != len(members)
            or any(member not in all_components for member in members)
            or grouped & set(members)
        ):
            raise PropertyDecompositionError("component group is malformed or overlapping")
        grouped.update(members)
    normalized = dict(value)
    normalized["rows"] = sorted(
        normalized_rows, key=lambda row: expected.index(row["expected_property_id"])
    )
    return normalized


def component_ids(value: Mapping[str, Any]) -> tuple[str, ...]:
    return tuple(
        component
        for row in value.get("rows", [])
        for component in row.get("component_ids", [])
    )


def build_authoring_scope(
    value: Mapping[str, Any],
    public_spec: Mapping[str, Any],
    selected_property_ids: tuple[str, ...] = (),
    selected_component_ids: tuple[str, ...] = (),
) -> Dict[str, Any]:
    """Freeze one bounded public task without dropping unselected suite rows."""

    all_ids = tuple(public_spec["expected_property_ids"])
    selected = selected_property_ids or all_ids
    if not selected or len(set(selected)) != len(selected) or any(row not in all_ids for row in selected):
        raise PropertyDecompositionError("authoring scope contains an unknown or duplicate public property")
    by_expected = {row["expected_property_id"]: row for row in value["rows"]}
    rows = [by_expected[property_id] for property_id in selected]
    clauses = sorted(
        {clause for row in rows for clause in row["clause_refs"]},
        key=lambda clause: public_spec["normative_clause_ids"].index(clause),
    )
    if not clauses:
        clauses = list(public_spec["normative_clause_ids"])
    available_components = [component for row in rows for component in row["component_ids"]]
    if selected_component_ids:
        if (
            len(set(selected_component_ids)) != len(selected_component_ids)
            or any(component not in available_components for component in selected_component_ids)
        ):
            raise PropertyDecompositionError("authoring component scope is unknown or duplicated")
        groups = value["component_groups"]
        expanded = []
        for component in selected_component_ids:
            for member in groups.get(component, [component]):
                if member not in expanded:
                    expanded.append(member)
        selected_components = expanded
        primary_components = list(selected_component_ids)
        require_complete = True
    else:
        selected_components = available_components
        primary_components = [
            component
            for component in selected_components
            if value["role_hints"].get(component) == "primary_assertion"
        ]
        require_complete = False
    return {
        "schema_version": "specflow_authoring_scope",
        "specification_id": public_spec["specification_id"],
        "spec_sha256": public_spec["spec_sha256"],
        "expected_property_ids": list(selected),
        "component_ids": selected_components,
        "primary_component_ids": primary_components,
        "component_role_hints": {
            component: value["role_hints"][component]
            for component in selected_components
            if component in value["role_hints"]
        },
        "require_complete_primary_set": require_complete,
        "clause_ids": clauses,
        "decomposition_rows": [dict(row) for row in rows],
    }


def _text(value: Any) -> bool:
    return isinstance(value, str) and bool(value.strip())
