"""Independent chisel_semantic_index.v1 fail-closed validator."""

from __future__ import annotations

import re
from typing import Any, Dict, Mapping

from ..config import SEMANTIC_INDEX_SCHEMA_VERSION


class SemanticIRValidationError(ValueError):
    def __init__(self, code: str, message: str):
        self.code = code
        super().__init__(f"{code}: {message}")


_INDEX_FIELDS = {
    "schema_version",
    "project_id",
    "configuration_id",
    "top",
    "objects",
    "guards",
    "source_index_sha256",
    "baseline_elaboration_sha256",
}
_ROW_FIELDS = {
    "object_id",
    "name",
    "source_anchor",
    "hardware_kind",
    "scala_hardware_domain",
    "chisel_type",
    "direction",
    "owner_module",
    "guard_context",
    "clock_reset",
    "configuration_condition",
    "accessibility",
    "fact_status",
    "validation_errors",
    "evidence_refs",
    "source_id",
}


def validate_semantic_index(value: Mapping[str, Any]) -> Dict[str, Any]:
    if not isinstance(value, Mapping) or set(value) != _INDEX_FIELDS:
        raise SemanticIRValidationError("malformed_semantic_index", "index fields differ")
    if value.get("schema_version") != SEMANTIC_INDEX_SCHEMA_VERSION:
        raise SemanticIRValidationError("unsupported_schema", str(value.get("schema_version")))
    for field in ("project_id", "configuration_id", "top"):
        if not isinstance(value.get(field), str) or not value[field]:
            raise SemanticIRValidationError("malformed_identifier", field)
    for field in ("source_index_sha256", "baseline_elaboration_sha256"):
        if not re.fullmatch(r"[0-9a-f]{64}", str(value.get(field))):
            raise SemanticIRValidationError("invalid_hash", field)
    rows = value.get("objects")
    if not isinstance(rows, list) or not rows:
        raise SemanticIRValidationError("missing_objects", "objects must be non-empty")
    normalized = []
    identities = set()
    for row in rows:
        normalized_row = validate_semantic_object(row, value["configuration_id"])
        if normalized_row["object_id"] in identities:
            raise SemanticIRValidationError("duplicate_object", normalized_row["object_id"])
        identities.add(normalized_row["object_id"])
        normalized.append(normalized_row)
    guards = value.get("guards")
    if not isinstance(guards, list):
        raise SemanticIRValidationError("malformed_guards", "guards must be a list")
    result = dict(value)
    result["objects"] = normalized
    return result


def validate_semantic_object(row: Mapping[str, Any], configuration_id: str) -> Dict[str, Any]:
    if not isinstance(row, Mapping) or set(row) != _ROW_FIELDS:
        raise SemanticIRValidationError("malformed_semantic_object", "object fields differ")
    for field in ("object_id", "name", "owner_module", "source_id"):
        if not isinstance(row.get(field), str) or not row[field]:
            raise SemanticIRValidationError("unknown_owner" if field == "owner_module" else "malformed_identifier", field)
    anchor = row.get("source_anchor")
    if not isinstance(anchor, Mapping) or set(anchor) != {"path", "line_start", "line_end", "enclosing_symbol", "source_sha256"}:
        raise SemanticIRValidationError("invalid_source_anchor", row["object_id"])
    if not re.fullmatch(r"[0-9a-f]{64}", str(anchor.get("source_sha256"))):
        raise SemanticIRValidationError("invalid_source_anchor", "source_sha256")
    if row.get("hardware_kind") not in {"port", "reg", "wire", "enum", "aggregate", "instance", "derived_event"}:
        raise SemanticIRValidationError("unsupported_hardware_kind", str(row.get("hardware_kind")))
    if row.get("scala_hardware_domain") not in {"elaboration", "hardware", "mixed", "unknown"}:
        raise SemanticIRValidationError("invalid_hardware_domain", str(row.get("scala_hardware_domain")))
    chisel_type = row.get("chisel_type")
    if not isinstance(chisel_type, Mapping) or set(chisel_type) != {"kind", "width", "signed", "fields", "index_domain"}:
        raise SemanticIRValidationError("malformed_type", row["object_id"])
    if chisel_type["kind"] not in {"Bool", "UInt", "SInt", "Bundle", "Vec", "Enum"}:
        raise SemanticIRValidationError("malformed_type", str(chisel_type["kind"]))
    if row.get("fact_status") == "elaboration_confirmed" and (
        not isinstance(chisel_type.get("width"), int)
        or isinstance(chisel_type.get("width"), bool)
        or chisel_type["width"] < 1
    ):
        raise SemanticIRValidationError("unknown_width", row["object_id"])
    if row.get("direction") not in {"input", "output", "internal", "none"}:
        raise SemanticIRValidationError("invalid_direction", str(row.get("direction")))
    if row.get("accessibility") not in {"direct", "wrapper", "layer", "probe", "boring", "unavailable"}:
        raise SemanticIRValidationError("invalid_accessibility", str(row.get("accessibility")))
    if row.get("fact_status") not in {"source_candidate", "elaboration_confirmed", "ambiguous"}:
        raise SemanticIRValidationError("invalid_fact_status", str(row.get("fact_status")))
    if row.get("configuration_condition") != configuration_id:
        raise SemanticIRValidationError("configuration_not_applicable", row["object_id"])
    for field in ("guard_context", "clock_reset"):
        if not isinstance(row.get(field), Mapping):
            raise SemanticIRValidationError("malformed_semantic_object", field)
    if not isinstance(row.get("validation_errors"), list) or not isinstance(row.get("evidence_refs"), list):
        raise SemanticIRValidationError("malformed_semantic_object", "evidence/error rows")
    return dict(row)
