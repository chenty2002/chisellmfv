"""Strict repository-owned property schema, template, and profile catalog."""

from __future__ import annotations

import json
import re
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Dict, List


ASSET_ROOT = Path(__file__).with_name("property_assets")
SOURCE_KINDS = {
    "protocol_requirement",
    "implementation_requirement",
    "historical_counterexample",
}
PLACEHOLDER_RE = re.compile(r"\{\{([A-Za-z_][A-Za-z0-9_]*)\}\}")


class PropertyCatalogError(ValueError):
    """Raised when repository property assets violate their fixed contract."""


@dataclass(frozen=True)
class PropertyCatalog:
    profile: Dict[str, Any]
    schemas: Dict[str, Dict[str, Any]]
    templates: Dict[str, Dict[str, Any]]
    candidates: Dict[str, Dict[str, Any]]
    formal_contract_id: str = ""


def load_property_profile(profile_id: str) -> PropertyCatalog:
    """Load one profile and all referenced assets, rejecting loose contracts."""
    if not re.fullmatch(r"[a-z0-9_]+", profile_id):
        raise PropertyCatalogError("invalid property profile id")
    profile = _read_json(ASSET_ROOT / "profiles" / f"{profile_id}.json")
    _validate_profile(profile, profile_id)

    schemas: Dict[str, Dict[str, Any]] = {}
    for path in sorted((ASSET_ROOT / "schemas").glob("*.json")):
        payload = _read_json(path)
        if payload.get("property_schema_id") in profile["property_schema_ids"]:
            _validate_schema(payload)
            schemas[payload["property_schema_id"]] = payload
    templates: Dict[str, Dict[str, Any]] = {}
    for path in sorted((ASSET_ROOT / "templates").glob("*.json")):
        payload = _read_json(path)
        if payload.get("template_id") in profile["template_ids"]:
            _validate_template(payload)
            templates[payload["template_id"]] = payload

    if set(schemas) != set(profile["property_schema_ids"]):
        raise PropertyCatalogError("profile references missing property schemas")
    if set(templates) != set(profile["template_ids"]):
        raise PropertyCatalogError("profile references missing assertion templates")
    if any(template["chisel_family"] != profile["chisel_family"] for template in templates.values()):
        raise PropertyCatalogError("template chisel family does not match profile")

    candidates = {
        item["candidate_id"]: item for item in profile["binding_candidates"]
    }
    if len(candidates) != len(profile["binding_candidates"]):
        raise PropertyCatalogError("duplicate binding candidate id")
    _validate_cross_references(profile, schemas, templates, candidates)
    return PropertyCatalog(
        profile=profile,
        schemas=schemas,
        templates=templates,
        candidates=candidates,
        formal_contract_id=profile.get(
            "formal_contract_id", profile["property_profile_id"]
        ),
    )


def list_property_profiles() -> List[str]:
    """Return repository-owned property profile IDs discovered from assets."""
    return sorted(path.stem for path in (ASSET_ROOT / "profiles").glob("*.json"))


def public_catalog(catalog: PropertyCatalog) -> Dict[str, Any]:
    """Return the model-visible catalog without template bodies or expressions."""
    return {
        "schema_version": "property_catalog_view.v1",
        "property_profile_id": catalog.profile["property_profile_id"],
        "formal_contract_id": catalog.formal_contract_id,
        "schemas": list(catalog.schemas.values()),
        "templates": [_public_template(template) for template in catalog.templates.values()],
        "candidates": [
            {
                key: candidate[key]
                for key in ("candidate_id", "type", "roles", "description")
            }
            for candidate in catalog.candidates.values()
        ],
        "target": {
            key: catalog.profile["target"][key]
            for key in ("file_id", "marker_id")
        },
        "source_targets": [
            {key: item[key] for key in ("file_id", "marker_id")}
            for item in catalog.profile.get("source_targets", [])
        ],
    }


def _public_template(template: Dict[str, Any]) -> Dict[str, Any]:
    keys = (
        "template_id",
        "chisel_family",
        "property_schema_ids",
        "slots",
        "parameters",
        "api_family",
        "api_primitive",
        "semantic_shape",
        "requires_formal_mixin",
    )
    return {key: template[key] for key in keys if key in template}


def _read_json(path: Path) -> Dict[str, Any]:
    if not path.is_file():
        raise PropertyCatalogError(f"property asset not found: {path.name}")
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise PropertyCatalogError(f"invalid property asset: {path.name}") from exc
    if not isinstance(value, dict):
        raise PropertyCatalogError(f"property asset must be an object: {path.name}")
    return value


def _exact_fields(value: Dict[str, Any], allowed: set[str], required: set[str], path: str) -> None:
    unknown = set(value) - allowed
    missing = required - set(value)
    if unknown:
        raise PropertyCatalogError(f"{path} unknown fields: {sorted(unknown)}")
    if missing:
        raise PropertyCatalogError(f"{path} missing fields: {sorted(missing)}")


def _validate_schema(value: Dict[str, Any]) -> None:
    fields = {
        "schema_version", "property_schema_id", "category", "layer", "title",
        "source", "scope", "trigger", "expectation", "preconditions",
        "matching_fields", "time_bound", "observation_points",
        "binding_requirements", "environment_assumptions", "required_slots",
        "template_ids", "review_required",
    }
    _exact_fields(value, fields, fields, "property_schema")
    if value["schema_version"] != "property_schema.v1":
        raise PropertyCatalogError("unsupported property schema version")
    _exact_fields(
        value["source"],
        {"kind", "document", "locator", "statement"},
        {"kind", "document", "locator", "statement"},
        "property_schema.source",
    )
    if value["source"]["kind"] not in SOURCE_KINDS:
        raise PropertyCatalogError("invalid property source kind")
    _exact_fields(
        value["time_bound"],
        {"required", "minimum", "maximum"},
        {"required", "minimum", "maximum"},
        "property_schema.time_bound",
    )
    if not value["required_slots"]:
        raise PropertyCatalogError("property schema requires at least one slot")


def _validate_template(value: Dict[str, Any]) -> None:
    fields = {
        "schema_version", "template_id", "chisel_family",
        "property_schema_ids", "slots", "parameters", "fragments", "rtl_match",
        "api_family", "api_primitive", "semantic_shape", "requires_formal_mixin",
        "allowed_profile_ids",
    }
    required = fields - {
        "api_family", "api_primitive", "semantic_shape", "requires_formal_mixin",
        "allowed_profile_ids",
    }
    _exact_fields(value, fields, required, "assertion_template")
    if value["schema_version"] != "assertion_template.v1":
        raise PropertyCatalogError("unsupported assertion template version")
    if "requires_formal_mixin" in value and not isinstance(value["requires_formal_mixin"], bool):
        raise PropertyCatalogError("requires_formal_mixin must be boolean")
    for key in ("api_family", "api_primitive", "semantic_shape"):
        if key in value and (not isinstance(value[key], str) or not value[key]):
            raise PropertyCatalogError(f"{key} must be a non-empty string")
    if "allowed_profile_ids" in value:
        if (
            not isinstance(value["allowed_profile_ids"], list)
            or not value["allowed_profile_ids"]
            or not all(isinstance(item, str) and item for item in value["allowed_profile_ids"])
        ):
            raise PropertyCatalogError("allowed_profile_ids must be a non-empty string list")
    _exact_fields(
        value["fragments"],
        {"support_block", "assertion_block", "source_block"},
        {"support_block", "assertion_block"},
        "assertion_template.fragments",
    )
    _exact_fields(
        value["rtl_match"],
        {"source_annotation_suffix", "minimum_occurrences", "allow_multiple_occurrences"},
        {"source_annotation_suffix", "minimum_occurrences", "allow_multiple_occurrences"},
        "assertion_template.rtl_match",
    )
    for name, slot in value["slots"].items():
        _exact_fields(slot, {"type"}, {"type"}, f"assertion_template.slots.{name}")
    for name, parameter in value["parameters"].items():
        _exact_fields(
            parameter,
            {"type", "minimum", "maximum"},
            {"type", "minimum", "maximum"},
            f"assertion_template.parameters.{name}",
        )
    allowed = set(value["slots"]) | set(value["parameters"]) | {"base_label"}
    assertion_placeholders = set(PLACEHOLDER_RE.findall(value["fragments"]["assertion_block"]))
    if assertion_placeholders - allowed:
        raise PropertyCatalogError("assertion template has undeclared placeholders")
    if "{{ASSERTION_BLOCK}}" in value["fragments"]["assertion_block"]:
        raise PropertyCatalogError("assertion block cannot contain fragment placeholder")
    support = value["fragments"]["support_block"]
    if support and support.count("{{ASSERTION_BLOCK}}") != 1:
        raise PropertyCatalogError("support block must contain one ASSERTION_BLOCK placeholder")
    source = value["fragments"].get("source_block", "")
    source_placeholders = set(PLACEHOLDER_RE.findall(source))
    allowed_source = allowed | {"source_label"}
    if source_placeholders - allowed_source:
        raise PropertyCatalogError("source block has undeclared placeholders")


def _validate_profile(value: Dict[str, Any], requested_id: str) -> None:
    fields = {
        "schema_version", "property_profile_id", "case_name", "chisel_family",
        "property_schema_ids", "template_ids", "build", "target",
        "source_targets", "binding_candidates", "formal_contract_id",
    }
    required = fields - {"source_targets", "formal_contract_id"}
    _exact_fields(value, fields, required, "property_profile")
    if value["schema_version"] != "property_profile.v1":
        raise PropertyCatalogError("unsupported property profile version")
    if value["property_profile_id"] != requested_id:
        raise PropertyCatalogError("property profile id does not match filename")
    if "formal_contract_id" in value and not re.fullmatch(
        r"[a-z0-9_]+", value["formal_contract_id"]
    ):
        raise PropertyCatalogError("invalid formal contract id")
    _exact_fields(
        value["build"],
        {"recommended_make_target"},
        {"recommended_make_target"},
        "property_profile.build",
    )
    _exact_fields(
        value["target"],
        {
            "file_id", "relative_path", "cleanup_region", "marker_id",
            "marker_text", "marker_after",
        },
        {
            "file_id", "relative_path", "cleanup_region", "marker_id",
            "marker_text", "marker_after",
        },
        "property_profile.target",
    )
    cleanup = value["target"]["cleanup_region"]
    if cleanup is not None:
        _exact_fields(
            cleanup,
            {
                "start_text", "block_start_text",
                "remove_through_balanced_block", "preserve_start_text",
            },
            {
                "start_text", "block_start_text",
                "remove_through_balanced_block", "preserve_start_text",
            },
            "property_profile.target.cleanup_region",
        )
    for index, source_target in enumerate(value.get("source_targets", [])):
        _exact_fields(
            source_target,
            {
                "file_id", "relative_path", "cleanup_region", "marker_id",
                "marker_text", "marker_after",
            },
            {
                "file_id", "relative_path", "cleanup_region", "marker_id",
                "marker_text", "marker_after",
            },
            f"property_profile.source_targets[{index}]",
        )
        if source_target["cleanup_region"] is not None:
            raise PropertyCatalogError("source target cleanup regions are not supported")
    for item in value["binding_candidates"]:
        _exact_fields(
            item,
            {
                "candidate_id", "expression", "type", "roles", "description",
                "provenance",
            },
            {
                "candidate_id", "expression", "type", "roles", "description",
                "provenance",
            },
            "property_profile.binding_candidates",
        )
        provenance = item["provenance"]
        if provenance.get("kind") == "source_scope":
            _exact_fields(
                provenance,
                {"kind", "path", "scope_anchor"},
                {"kind", "path", "scope_anchor"},
                "candidate.provenance",
            )
        elif provenance.get("kind") == "template_fragment":
            _exact_fields(
                provenance,
                {"kind", "template_id"},
                {"kind", "template_id"},
                "candidate.provenance",
            )
        else:
            raise PropertyCatalogError("invalid candidate provenance kind")


def _validate_cross_references(
    profile: Dict[str, Any],
    schemas: Dict[str, Dict[str, Any]],
    templates: Dict[str, Dict[str, Any]],
    candidates: Dict[str, Dict[str, Any]],
) -> None:
    for schema in schemas.values():
        if not set(schema["template_ids"]) <= set(templates):
            raise PropertyCatalogError("schema references template outside profile")
        for template_id in schema["template_ids"]:
            template = templates[template_id]
            if schema["property_schema_id"] not in template["property_schema_ids"]:
                raise PropertyCatalogError("schema/template cross reference mismatch")
            allowed_profile_ids = template.get("allowed_profile_ids")
            if (
                allowed_profile_ids is not None
                and profile["property_profile_id"] not in allowed_profile_ids
            ):
                raise PropertyCatalogError("template is not allowed for this profile")
            expected = schema["required_slots"]
            actual = {
                name: definition["type"]
                for name, definition in template["slots"].items()
            }
            if expected != actual:
                raise PropertyCatalogError("schema/template slot contract mismatch")
    required_roles = {
        role
        for schema in schemas.values()
        for role in schema["required_slots"]
    }
    for role in required_roles:
        if not any(
            role in candidate["roles"]
            and any(
                schema["required_slots"].get(role) == candidate["type"]
                for schema in schemas.values()
            )
            for candidate in candidates.values()
        ):
            raise PropertyCatalogError(f"no type-compatible candidate for slot {role}")
    for candidate in candidates.values():
        provenance = candidate["provenance"]
        if (
            provenance["kind"] == "template_fragment"
            and provenance["template_id"] not in templates
        ):
            raise PropertyCatalogError("candidate references template outside profile")
