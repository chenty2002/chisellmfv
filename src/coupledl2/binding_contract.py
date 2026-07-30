"""Strict model-facing binding manifest and patch contracts."""

from __future__ import annotations

import copy
import re
from typing import Any, Dict

from .property_catalog import PropertyCatalog
from .binding_candidates import compatible_candidates


BASE_LABEL_RE = re.compile(r"^(?:CL2|TL)_[A-Z0-9_]{1,80}$")
class BindingContractError(ValueError):
    def __init__(
        self,
        field_path: str,
        error_kind: str,
        message: str,
        allowed_values: Any = None,
    ):
        super().__init__(f"{field_path}: {message}")
        self.field_path = field_path
        self.error_kind = error_kind
        self.allowed_values = allowed_values

    def to_dict(self) -> Dict[str, Any]:
        value = {
            "field_path": self.field_path,
            "error_kind": self.error_kind,
            "message": str(self),
        }
        if self.allowed_values is not None:
            value["allowed_values"] = self.allowed_values
        return value


def binding_manifest_tool(catalog: PropertyCatalog) -> Dict[str, Any]:
    """Build the only tool exposed during the first binding request."""
    profile = catalog.profile
    schema_ids = sorted(catalog.schemas)
    template_ids = sorted(catalog.templates)
    candidate_ids = sorted(catalog.candidates)
    target = profile["target"]
    return {
        "name": "submit_binding_manifest",
        "description": (
            "Submit one to eight complete property bindings using repository candidate IDs."
        ),
        "strict": True,
        "parameters": {
            "type": "object",
            "additionalProperties": False,
            "required": ["schema_version", "property_profile_id", "instances"],
            "properties": {
                "schema_version": {"type": "string", "const": "binding_manifest"},
                "property_profile_id": {
                    "type": "string",
                    "const": profile["property_profile_id"],
                },
                "instances": {
                    "type": "array",
                    "minItems": 1,
                    "maxItems": 8,
                    "items": {
                        "type": "object",
                        "additionalProperties": False,
                        "required": [
                            "instance_id", "property_schema_id", "template_id",
                            "target", "bindings", "parameters", "base_label",
                            "evidence",
                        ],
                        "properties": {
                            "instance_id": {
                                "type": "string",
                                "pattern": r"^[a-z0-9_]{1,96}$",
                            },
                            "property_schema_id": {
                                "type": "string",
                                "enum": schema_ids,
                            },
                            "template_id": {"type": "string", "enum": template_ids},
                            "target": {
                                "type": "object",
                                "additionalProperties": False,
                                "required": ["file_id", "marker_id"],
                                "properties": {
                                    "file_id": {
                                        "type": "string",
                                        "const": target["file_id"],
                                    },
                                    "marker_id": {
                                        "type": "string",
                                        "const": target["marker_id"],
                                    },
                                },
                            },
                            "bindings": {
                                "type": "object",
                                "additionalProperties": {
                                    "type": "string",
                                    "enum": candidate_ids,
                                },
                            },
                            "parameters": {"type": "object"},
                            "base_label": {
                                "type": "string",
                                "pattern": r"^(?:CL2|TL)_[A-Z0-9_]{1,80}$",
                            },
                            "evidence": {
                                "type": "array",
                                "maxItems": 4,
                                "description": (
                                    "Provide at most four representative candidate "
                                    "references; do not repeat every binding."
                                ),
                                "items": {
                                    "type": "object",
                                    "additionalProperties": False,
                                    "required": ["candidate_id"],
                                    "properties": {
                                        "candidate_id": {
                                            "type": "string",
                                            "enum": candidate_ids,
                                        }
                                    },
                                },
                            },
                        },
                    },
                },
            },
        },
    }


def validate_binding_manifest(
    payload: Dict[str, Any],
    catalog: PropertyCatalog,
) -> Dict[str, Any]:
    _object(payload, {"schema_version", "property_profile_id", "instances"}, "$")
    _equal(payload["schema_version"], "binding_manifest", "$.schema_version")
    _equal(
        payload["property_profile_id"],
        catalog.profile["property_profile_id"],
        "$.property_profile_id",
    )
    instances = payload["instances"]
    if not isinstance(instances, list) or not 1 <= len(instances) <= 8:
        raise BindingContractError("$.instances", "cardinality", "must contain one to eight instances")
    instance_ids = [item.get("instance_id") for item in instances if isinstance(item, dict)]
    base_labels = [item.get("base_label") for item in instances if isinstance(item, dict)]
    if len(set(instance_ids)) != len(instances):
        raise BindingContractError("$.instances", "duplicate_value", "instance ids must be globally unique")
    if len(set(base_labels)) != len(instances):
        raise BindingContractError("$.instances", "duplicate_value", "base labels must be globally unique")
    normalized = copy.deepcopy(payload)
    for index, instance in enumerate(normalized["instances"]):
        _validate_instance(instance, catalog, index)
    return normalized


def _validate_instance(instance: Dict[str, Any], catalog: PropertyCatalog, index: int) -> None:
    path = f"$.instances[{index}]"
    fields = {
        "instance_id", "property_schema_id", "template_id", "target",
        "bindings", "parameters", "base_label", "evidence",
    }
    _object(instance, fields, path)
    if not re.fullmatch(r"[a-z0-9_]{1,96}", instance["instance_id"]):
        raise BindingContractError(f"{path}.instance_id", "invalid_value", "invalid instance id")
    schema_id = instance["property_schema_id"]
    template_id = instance["template_id"]
    if schema_id not in catalog.schemas:
        raise BindingContractError(
            f"{path}.property_schema_id",
            "unknown_value",
            "unknown property schema",
            sorted(catalog.schemas),
        )
    if template_id not in catalog.templates:
        raise BindingContractError(
            f"{path}.template_id",
            "template_incompatible",
            "template is not allowed by profile",
            sorted(catalog.templates),
        )
    template = catalog.templates[template_id]
    if schema_id not in template["property_schema_ids"]:
        raise BindingContractError(
            f"{path}.template_id",
            "template_incompatible",
            "template does not implement selected schema",
            catalog.schemas[schema_id]["template_ids"],
        )
    target = instance["target"]
    _object(target, {"file_id", "marker_id"}, f"{path}.target")
    _equal(target["file_id"], catalog.profile["target"]["file_id"], f"{path}.target.file_id")
    _equal(target["marker_id"], catalog.profile["target"]["marker_id"], f"{path}.target.marker_id")
    if not isinstance(instance["base_label"], str) or not BASE_LABEL_RE.fullmatch(instance["base_label"]):
        raise BindingContractError(f"{path}.base_label", "invalid_value", "invalid CL2/TL base label")

    bindings = instance["bindings"]
    if not isinstance(bindings, dict) or set(bindings) != set(template["slots"]):
        raise BindingContractError(
            f"{path}.bindings",
            "candidate_incompatible",
            "bindings must cover exactly the template slots",
            sorted(template["slots"]),
        )
    for role, candidate_id in bindings.items():
        candidate = catalog.candidates.get(candidate_id)
        expected_type = template["slots"][role]["type"]
        if (
            candidate is None
            or role not in candidate["roles"]
            or candidate["type"] != expected_type
        ):
            allowed = [
                item["candidate_id"]
                for item in compatible_candidates(catalog, role, expected_type)
            ]
            raise BindingContractError(
                f"{path}.bindings.{role}",
                "candidate_incompatible",
                "candidate role or type is incompatible",
                allowed,
            )
    parameters = instance["parameters"]
    if not isinstance(parameters, dict) or set(parameters) != set(template["parameters"]):
        raise BindingContractError(
            f"{path}.parameters",
            "parameter_out_of_range",
            "parameters must cover exactly the template parameters",
            sorted(template["parameters"]),
        )
    for name, definition in template["parameters"].items():
        value = parameters[name]
        if (
            definition["type"] != "integer"
            or isinstance(value, bool)
            or not isinstance(value, int)
            or value < definition["minimum"]
            or value > definition["maximum"]
        ):
            raise BindingContractError(
                f"{path}.parameters.{name}",
                "parameter_out_of_range",
                "parameter is outside the allowed range",
                [definition["minimum"], definition["maximum"]],
            )
    evidence = instance["evidence"]
    if not isinstance(evidence, list) or len(evidence) > 4:
        raise BindingContractError(f"{path}.evidence", "cardinality", "evidence has at most four items")
    for index, item in enumerate(evidence):
        _object(item, {"candidate_id"}, f"{path}.evidence[{index}]")
        if item["candidate_id"] not in catalog.candidates:
            raise BindingContractError(
                f"{path}.evidence[{index}].candidate_id",
                "unknown_value",
                "unknown candidate",
                sorted(catalog.candidates),
            )


def _object(value: Any, fields: set[str], path: str) -> None:
    if not isinstance(value, dict):
        raise BindingContractError(path, "invalid_type", "must be an object")
    unknown = set(value) - fields
    missing = fields - set(value)
    if unknown:
        raise BindingContractError(path, "unknown_fields", f"unknown fields: {sorted(unknown)}")
    if missing:
        raise BindingContractError(path, "missing_fields", f"missing fields: {sorted(missing)}")


def _equal(value: Any, expected: Any, path: str) -> None:
    if value != expected:
        raise BindingContractError(path, "invalid_value", f"must equal {expected!r}", [expected])
