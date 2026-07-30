"""Deterministic package applicability across opaque generator configurations."""

from __future__ import annotations

from typing import Any, Dict, Mapping


class PackageApplicabilityError(ValueError):
    """Raised when applicability inputs are malformed or identity-inconsistent."""


def classify_package_applicability(
    package: Mapping[str, Any],
    source_index: Mapping[str, Any],
    target_index: Mapping[str, Any],
    target_configuration_id: str,
) -> Dict[str, Any]:
    """Classify a reviewed package without reinterpreting its semantics.

    ``reusable`` means the package is used in its authored configuration.
    ``re_instantiated`` means every exact bound object has the same structural
    signature in another configuration.  A missing, ambiguous, or structurally
    changed object is ``not_applicable`` and must return to Stage 1 instead of
    being guessed or suffix-matched.
    """

    source_configuration_id = package.get("configuration_id")
    if not _text(source_configuration_id) or not _text(target_configuration_id):
        raise PackageApplicabilityError("configuration identity is missing")
    if source_index.get("configuration_id") != source_configuration_id:
        raise PackageApplicabilityError("source semantic index configuration mismatch")
    if target_index.get("configuration_id") != target_configuration_id:
        raise PackageApplicabilityError("target semantic index configuration mismatch")
    bindings = package.get("bindings")
    if not isinstance(bindings, list) or not bindings:
        raise PackageApplicabilityError("reviewed package has no bindings")

    source_objects = _objects(source_index)
    target_objects = _objects(target_index)
    rows = []
    seen_bindings = set()
    for binding in bindings:
        if not isinstance(binding, Mapping):
            raise PackageApplicabilityError("binding row is not an object")
        binding_id = binding.get("binding_id")
        object_id = binding.get("object_id")
        if not _text(binding_id) or binding_id in seen_bindings or not _text(object_id):
            raise PackageApplicabilityError("binding identity is missing or duplicated")
        seen_bindings.add(binding_id)
        source = source_objects.get(object_id)
        target = target_objects.get(object_id)
        reasons = []
        if source is None or source.get("fact_status") != "elaboration_confirmed":
            raise PackageApplicabilityError(
                f"source package object is not elaboration-confirmed: {object_id}"
            )
        if target is None:
            reasons.append("object_missing")
        elif target.get("fact_status") != "elaboration_confirmed":
            reasons.append("object_not_elaboration_confirmed")
        else:
            source_signature = _structural_signature(source)
            target_signature = _structural_signature(target)
            for field in source_signature:
                if source_signature[field] != target_signature[field]:
                    reasons.append(field + "_changed")
        rows.append(
            {
                "binding_id": binding_id,
                "object_id": object_id,
                "status": "not_applicable" if reasons else "compatible",
                "reasons": reasons,
            }
        )

    classification = (
        "not_applicable"
        if any(row["status"] == "not_applicable" for row in rows)
        else "reusable"
        if source_configuration_id == target_configuration_id
        else "re_instantiated"
    )
    return {
        "schema_version": "package_applicability",
        "package_id": package.get("package_id"),
        "project_id": package.get("project_id"),
        "source_configuration_id": source_configuration_id,
        "target_configuration_id": target_configuration_id,
        "classification": classification,
        "binding_rows": rows,
    }


def _objects(index: Mapping[str, Any]) -> Dict[str, Mapping[str, Any]]:
    rows = index.get("objects")
    if not isinstance(rows, list):
        raise PackageApplicabilityError("semantic index has no object rows")
    result: Dict[str, Mapping[str, Any]] = {}
    for row in rows:
        if not isinstance(row, Mapping) or not _text(row.get("object_id")):
            raise PackageApplicabilityError("semantic object identity is malformed")
        if row["object_id"] in result:
            raise PackageApplicabilityError("semantic object identity is duplicated")
        result[row["object_id"]] = row
    return result


def _structural_signature(row: Mapping[str, Any]) -> Dict[str, Any]:
    chisel_type = row.get("chisel_type")
    clock_reset = row.get("clock_reset")
    anchor = row.get("source_anchor")
    if not all(isinstance(value, Mapping) for value in (chisel_type, clock_reset, anchor)):
        raise PackageApplicabilityError("semantic object lacks a structural signature")
    return {
        "name": row.get("name"),
        "hardware_kind": row.get("hardware_kind"),
        "chisel_type": dict(chisel_type),
        "direction": row.get("direction"),
        "owner_module": row.get("owner_module"),
        "clock_reset": dict(clock_reset),
        "accessibility": row.get("accessibility"),
        "source_anchor": {
            key: anchor.get(key)
            for key in ("path", "line_start", "line_end", "enclosing_symbol", "source_sha256")
        },
    }


def _text(value: Any) -> bool:
    return isinstance(value, str) and bool(value.strip())
