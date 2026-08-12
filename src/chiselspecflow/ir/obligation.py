"""Strict verification_obligations validation."""

from __future__ import annotations

import re
from typing import Any, Dict, Mapping

from ..config import OBLIGATION_SCHEMA
from .expression import ExpressionValidationError, validate_expression


SUPPORTED_FAMILIES = frozenset(
    {
        "combinational_mapping",
        "reset_initialization",
        "state_transition",
        "stability",
        "cardinality",
        "bounded_response",
        "algorithmic_reference",
    }
)
SUPPORT_STATUSES = frozenset({"candidate", "supported", "unsupported", "ambiguous"})
_FIELDS = {
    "obligation_id",
    "clause_ref",
    "family",
    "polarity",
    "entities",
    "trigger",
    "guard",
    "expected",
    "temporal",
    "reset_semantics",
    "observation_roles",
    "configuration_domain",
    "support_status",
    "authoring_provenance",
}


class ObligationValidationError(ValueError):
    def __init__(self, code: str, message: str):
        self.code = code
        super().__init__(f"{code}: {message}")


def validate_obligation(
    candidate: Mapping[str, Any], object_types: Mapping[str, Mapping[str, Any]]
) -> Dict[str, Any]:
    if not isinstance(candidate, Mapping) or set(candidate) != _FIELDS:
        actual = set(candidate) if isinstance(candidate, Mapping) else set()
        raise ObligationValidationError(
            "malformed_obligation",
            f"missing={sorted(_FIELDS - actual)}, extra={sorted(actual - _FIELDS)}",
        )
    value = dict(candidate)
    _id(value["obligation_id"], "obligation_id")
    clause = _exact_object(value["clause_ref"], {"spec_sha256", "locator", "text_sha256"}, "clause_ref")
    for field in ("spec_sha256", "text_sha256"):
        if not re.fullmatch(r"[0-9a-f]{64}", str(clause[field])):
            raise ObligationValidationError("invalid_clause_ref", field)
    _id(clause["locator"], "clause_ref.locator")
    family = value["family"]
    status = value["support_status"]
    if status not in SUPPORT_STATUSES:
        raise ObligationValidationError("invalid_support_status", str(status))
    if family not in SUPPORTED_FAMILIES:
        value["support_status"] = "unsupported"
        value["validation_errors"] = ["unsupported_family"]
    if value["polarity"] not in {"guarantee", "assumption"}:
        raise ObligationValidationError("invalid_polarity", str(value["polarity"]))
    entities = value["entities"]
    if not isinstance(entities, list) or not entities or any(item not in object_types for item in entities):
        raise ObligationValidationError("unknown_object", "entities must be known object IDs")
    roles = value["observation_roles"]
    if not isinstance(roles, list) or not roles or any(not isinstance(item, str) or not item for item in roles):
        raise ObligationValidationError("malformed_observation_roles", "at least one role is required")
    temporal = _exact_object(value["temporal"], {"kind", "min_cycles", "max_cycles"}, "temporal")
    if temporal["kind"] not in {
        "same_cycle",
        "next_cycle",
        "bounded",
        "reference_relation",
    }:
        raise ObligationValidationError("unsupported_temporal_kind", str(temporal["kind"]))
    minimum, maximum = temporal["min_cycles"], temporal["max_cycles"]
    if any(not isinstance(item, int) or isinstance(item, bool) or item < 0 for item in (minimum, maximum)) or minimum > maximum:
        raise ObligationValidationError("invalid_temporal_bound", str(temporal))
    if not isinstance(value["reset_semantics"], str) or not value["reset_semantics"]:
        raise ObligationValidationError("invalid_reset_semantics", "non-empty string required")
    domain = value["configuration_domain"]
    if not isinstance(domain, list) or not domain or any(not isinstance(item, str) or not item for item in domain):
        raise ObligationValidationError("invalid_configuration_domain", "non-empty ID list required")
    provenance = _exact_object(value["authoring_provenance"], {"kind", "ref"}, "authoring_provenance")
    if provenance["kind"] not in {"model_call", "reused_asset"}:
        raise ObligationValidationError("invalid_provenance", str(provenance["kind"]))
    _id(provenance["ref"], "authoring_provenance.ref")
    try:
        for field in ("trigger", "guard", "expected"):
            value[field] = validate_expression(value[field], object_types)
    except ExpressionValidationError as exc:
        raise ObligationValidationError(exc.code, str(exc)) from exc
    value["schema_version"] = OBLIGATION_SCHEMA
    return value


def _exact_object(value: Any, fields: set[str], label: str) -> Mapping[str, Any]:
    if not isinstance(value, Mapping) or set(value) != fields:
        raise ObligationValidationError("malformed_obligation", f"{label} fields differ")
    return value


def _id(value: Any, label: str) -> str:
    if not isinstance(value, str) or not value.strip():
        raise ObligationValidationError("malformed_identifier", label)
    return value
