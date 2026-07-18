"""Strict chisel_monitors.v1 validation for bounded repository archetypes."""

from __future__ import annotations

import hashlib
from typing import Any, Dict, Mapping

from ..config import MONITOR_SCHEMA_VERSION
from .expression import ExpressionValidationError, ExpressionType, infer_expression_type, validate_expression


_FIELDS = {
    "monitor_id",
    "obligation_id",
    "archetype_id",
    "archetype_sha256",
    "binding_refs",
    "state",
    "properties",
    "reset_policy",
    "overlay",
    "required_observations",
    "configuration_domain",
}
_PROPERTY_FIELDS = {"source_property_id", "role", "expression_ir", "guard_ir"}
_STATE_FIELDS = {"state_id", "type", "init", "update", "clear"}
_ROLES = {"primary_assertion", "activation_cover", "observer_cover", "state_cover", "assumption_sat"}


class MonitorValidationError(ValueError):
    def __init__(self, code: str, message: str):
        self.code = code
        super().__init__(f"{code}: {message}")


def validate_monitor(
    candidate: Mapping[str, Any],
    *,
    object_types: Mapping[str, Mapping[str, Any]],
    obligation_ids: set[str] | frozenset[str],
    binding_ids: set[str] | frozenset[str],
    archetypes: Mapping[str, Mapping[str, Any]],
    configuration_id: str,
) -> Dict[str, Any]:
    if not isinstance(candidate, Mapping) or set(candidate) != _FIELDS:
        actual = set(candidate) if isinstance(candidate, Mapping) else set()
        raise MonitorValidationError(
            "malformed_monitor",
            f"missing={sorted(_FIELDS - actual)}, extra={sorted(actual - _FIELDS)}",
        )
    value = dict(candidate)
    _text(value["monitor_id"], "monitor_id")
    if value["obligation_id"] not in obligation_ids:
        raise MonitorValidationError("unknown_obligation", str(value["obligation_id"]))
    archetype_id = value["archetype_id"]
    archetype = archetypes.get(archetype_id)
    if archetype is None or archetype_id not in {"direct_relation.v1", "previous_value.v1"}:
        raise MonitorValidationError("unsupported_archetype", str(archetype_id))
    if value["archetype_sha256"] != archetype.get("sha256"):
        raise MonitorValidationError("archetype_hash_mismatch", str(archetype_id))
    refs = value["binding_refs"]
    if not isinstance(refs, list) or not refs or any(ref not in binding_ids for ref in refs):
        raise MonitorValidationError("unknown_binding", "binding_refs must be known")
    if value["configuration_domain"] != [configuration_id]:
        raise MonitorValidationError("configuration_not_applicable", configuration_id)
    overlay = value["overlay"]
    if not isinstance(overlay, Mapping) or set(overlay) != {"strategy", "wrapper_top", "host_scope"}:
        raise MonitorValidationError("malformed_overlay", "overlay fields differ")
    if (
        overlay["strategy"] != "wrapper"
        or overlay.get("wrapper_top") != "SpecFlowOverlay"
        or overlay.get("host_scope") != "SpecFlowOverlay"
    ):
        raise MonitorValidationError("observer_strategy_unsupported", str(overlay.get("strategy")))
    if value["reset_policy"] != "disable_while_reset":
        raise MonitorValidationError(
            "invalid_reset_policy", "Iteration 2 requires disable_while_reset"
        )
    observations = value["required_observations"]
    if not isinstance(observations, list) or not observations or any(ref not in binding_ids for ref in observations):
        raise MonitorValidationError("unknown_binding", "required_observations must be binding IDs")

    states = value["state"]
    if not isinstance(states, list):
        raise MonitorValidationError("malformed_state", "state must be a list")
    if archetype_id == "direct_relation.v1" and states:
        raise MonitorValidationError("archetype_state_mismatch", "direct_relation has no state")
    if archetype_id == "previous_value.v1" and not states:
        raise MonitorValidationError("archetype_state_mismatch", "previous_value requires state")
    state_types: Dict[str, Mapping[str, Any]] = {}
    for index, state in enumerate(states):
        if not isinstance(state, Mapping) or set(state) != _STATE_FIELDS:
            raise MonitorValidationError("malformed_state", f"state[{index}] fields differ")
        state_id = _text(state["state_id"], f"state[{index}].state_id")
        if state_id in state_types:
            raise MonitorValidationError("duplicate_state", state_id)
        state_types[state_id] = _type_dict(state["type"], f"state[{index}].type")
    normalized_states = []
    for index, state in enumerate(states):
        state_type = ExpressionType(**state_types[state["state_id"]])
        normalized = dict(state)
        for field in ("init", "update", "clear"):
            try:
                normalized[field] = validate_expression(state[field], object_types, state_types)
                actual = infer_expression_type(normalized[field], object_types, state_types)
            except ExpressionValidationError as exc:
                raise MonitorValidationError(exc.code, str(exc)) from exc
            expected = ExpressionType("Bool", 1, False) if field == "clear" else state_type
            if actual != expected:
                raise MonitorValidationError("type_mismatch", f"state[{index}].{field}")
        normalized_states.append(normalized)

    properties = value["properties"]
    if not isinstance(properties, list) or not properties:
        raise MonitorValidationError("malformed_properties", "at least one property required")
    normalized_properties = []
    property_ids = set()
    roles = set()
    for index, prop in enumerate(properties):
        if not isinstance(prop, Mapping) or set(prop) != _PROPERTY_FIELDS:
            raise MonitorValidationError("malformed_property", f"properties[{index}] fields differ")
        source_id = _text(prop["source_property_id"], f"properties[{index}].source_property_id")
        role = prop["role"]
        if role not in _ROLES:
            raise MonitorValidationError("unsupported_property_role", str(role))
        if source_id in property_ids:
            raise MonitorValidationError("duplicate_source_property", source_id)
        property_ids.add(source_id)
        roles.add(role)
        normalized = dict(prop)
        try:
            for field in ("expression_ir", "guard_ir"):
                normalized[field] = validate_expression(prop[field], object_types, state_types)
                result = infer_expression_type(normalized[field], object_types, state_types)
                if result != ExpressionType("Bool", 1, False):
                    raise MonitorValidationError("type_mismatch", f"properties[{index}].{field} must be Bool")
        except ExpressionValidationError as exc:
            raise MonitorValidationError(exc.code, str(exc)) from exc
        normalized["expected_label"] = expected_property_label(source_id, role)
        normalized_properties.append(normalized)
    required_roles = set(archetype.get("required_roles", []))
    if not required_roles <= roles:
        raise MonitorValidationError("missing_property_role", str(sorted(required_roles - roles)))
    value["state"] = normalized_states
    value["properties"] = normalized_properties
    value["schema_version"] = MONITOR_SCHEMA_VERSION
    return value


def expected_property_label(source_property_id: str, role: str) -> str:
    digest = hashlib.sha256(f"{source_property_id}\0{role}".encode("utf-8")).hexdigest()[:16]
    return "CSF_" + digest.upper()


def _type_dict(value: Any, label: str) -> Dict[str, Any]:
    if not isinstance(value, Mapping) or set(value) != {"kind", "width", "signed"}:
        raise MonitorValidationError("malformed_type", label)
    kind, width, signed = value["kind"], value["width"], value["signed"]
    if kind not in {"Bool", "UInt", "SInt"} or not isinstance(width, int) or isinstance(width, bool) or width < 1:
        raise MonitorValidationError("malformed_type", label)
    if not isinstance(signed, bool) or signed != (kind == "SInt") or (kind == "Bool" and width != 1):
        raise MonitorValidationError("malformed_type", label)
    return {"kind": kind, "width": width, "signed": signed}


def _text(value: Any, label: str) -> str:
    if not _is_text(value):
        raise MonitorValidationError("malformed_identifier", label)
    return value


def _is_text(value: Any) -> bool:
    return isinstance(value, str) and bool(value.strip())
