"""Strict chisel_bindings compatibility validation."""

from __future__ import annotations

from typing import Any, Dict, Mapping

from ..config import BINDING_SCHEMA


_FIELDS = {
    "binding_id",
    "obligation_id",
    "semantic_role",
    "object_id",
    "instance_selector",
    "configuration_domain",
    "compatibility",
    "acquisition",
    "rationale",
    "rejected_alternatives",
    "review_state",
}
_COMPATIBILITY_FIELDS = {"type", "width", "ownership", "clock", "reset", "configuration"}
_ERROR_CODES = frozenset(
    {
        "object_not_elaboration_confirmed",
        "type_mismatch",
        "width_mismatch",
        "owner_unreachable",
        "clock_domain_mismatch",
        "reset_semantics_mismatch",
        "configuration_not_applicable",
        "observer_strategy_unsupported",
    }
)


class BindingValidationError(ValueError):
    """Expose every deterministic compatibility failure in ``errors``."""

    def __init__(self, errors: list[Dict[str, str]]):
        self.errors = errors
        self.codes = tuple(row["code"] for row in errors)
        super().__init__("; ".join(f"{row['code']}: {row['detail']}" for row in errors))


def validate_binding(
    candidate: Mapping[str, Any],
    semantic_index: Mapping[str, Any],
    obligation_ids: set[str] | frozenset[str],
    configuration_id: str,
    adapter_ids: set[str] | frozenset[str] | None = None,
) -> Dict[str, Any]:
    errors = binding_validation_errors(
        candidate, semantic_index, obligation_ids, configuration_id, adapter_ids
    )
    if errors:
        raise BindingValidationError(errors)
    value = dict(candidate)
    value["schema_version"] = BINDING_SCHEMA
    value["validation_errors"] = []
    return value


def binding_validation_errors(
    candidate: Mapping[str, Any],
    semantic_index: Mapping[str, Any],
    obligation_ids: set[str] | frozenset[str],
    configuration_id: str,
    adapter_ids: set[str] | frozenset[str] | None = None,
) -> list[Dict[str, str]]:
    if not isinstance(candidate, Mapping) or set(candidate) != _FIELDS:
        return [_error("object_not_elaboration_confirmed", "binding fields are malformed")]
    errors: list[Dict[str, str]] = []
    if not _text(candidate.get("binding_id")) or candidate.get("obligation_id") not in obligation_ids:
        errors.append(_error("configuration_not_applicable", "unknown obligation or binding ID"))
    object_id = candidate.get("object_id")
    objects = {
        row.get("object_id"): row
        for row in semantic_index.get("objects", [])
        if isinstance(row, Mapping)
    }
    row = objects.get(object_id)
    if row is None or row.get("fact_status") != "elaboration_confirmed":
        errors.append(_error("object_not_elaboration_confirmed", str(object_id)))
        return _deduplicate(errors)

    compatibility = candidate.get("compatibility")
    if not isinstance(compatibility, Mapping) or set(compatibility) != _COMPATIBILITY_FIELDS:
        return _deduplicate(errors + [_error("type_mismatch", "compatibility fields are malformed")])
    actual_type = row.get("chisel_type", {})
    expected_kind = compatibility.get("type")
    expected_width = compatibility.get("width")
    if expected_kind != actual_type.get("kind"):
        errors.append(_error("type_mismatch", f"expected {expected_kind}, object is {actual_type.get('kind')}"))
    if expected_width != actual_type.get("width"):
        errors.append(_error("width_mismatch", f"expected {expected_width}, object is {actual_type.get('width')}"))
    if compatibility.get("ownership") != row.get("owner_module"):
        errors.append(
            _error(
                "owner_unreachable",
                f"submitted {compatibility.get('ownership')}, expected {row.get('owner_module')}",
            )
        )
    clock_reset = row.get("clock_reset", {})
    if compatibility.get("clock") != clock_reset.get("clock_domain"):
        errors.append(
            _error(
                "clock_domain_mismatch",
                f"submitted {compatibility.get('clock')}, expected {clock_reset.get('clock_domain')}",
            )
        )
    if compatibility.get("reset") != clock_reset.get("reset_domain"):
        errors.append(
            _error(
                "reset_semantics_mismatch",
                f"submitted {compatibility.get('reset')}, expected {clock_reset.get('reset_domain')}",
            )
        )
    domain = candidate.get("configuration_domain")
    if (
        compatibility.get("configuration") != configuration_id
        or not isinstance(domain, list)
        or configuration_id not in domain
        or row.get("configuration_condition") != configuration_id
    ):
        errors.append(_error("configuration_not_applicable", configuration_id))
    acquisition = candidate.get("acquisition")
    if not isinstance(acquisition, Mapping) or set(acquisition) != {"strategy", "host_scope", "adapter_id"}:
        errors.append(_error("observer_strategy_unsupported", "malformed acquisition"))
    elif (
        acquisition.get("strategy") != "wrapper"
        or acquisition.get("host_scope") != "SpecFlowOverlay"
        or row.get("accessibility") not in {"direct", "wrapper"}
        or (adapter_ids is not None and acquisition.get("adapter_id") not in adapter_ids)
    ):
        errors.append(_error("observer_strategy_unsupported", str(acquisition.get("strategy"))))
    if candidate.get("review_state") != "candidate":
        errors.append(_error("configuration_not_applicable", "model candidates must remain candidate"))
    if not _text(candidate.get("semantic_role")) or candidate.get("instance_selector") != "dut":
        errors.append(_error("owner_unreachable", "role and instance selector are required"))
    if not _text(candidate.get("rationale")) or not isinstance(candidate.get("rejected_alternatives"), list):
        errors.append(_error("observer_strategy_unsupported", "rationale fields are malformed"))
    return _deduplicate(errors)


def _error(code: str, detail: str) -> Dict[str, str]:
    assert code in _ERROR_CODES
    return {"code": code, "detail": detail}


def _deduplicate(errors: list[Dict[str, str]]) -> list[Dict[str, str]]:
    seen = set()
    rows = []
    for row in errors:
        key = (row["code"], row["detail"])
        if key not in seen:
            seen.add(key)
            rows.append(row)
    return rows


def _text(value: Any) -> bool:
    return isinstance(value, str) and bool(value.strip())
