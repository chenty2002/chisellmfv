"""Strict repository-owned property schema, template, and profile catalog."""

from __future__ import annotations

import json
import re
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Dict, List

from .property_review import (
    PropertyReviewError,
    load_property_review,
    verify_review_assets,
)


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
    review: Dict[str, Any] | None = None
    gold_bindings: Dict[str, Any] | None = None


def load_property_profile(profile_id: str, *, require_approved: bool = True) -> PropertyCatalog:
    """Load one profile and all referenced assets, rejecting loose contracts."""
    if not re.fullmatch(r"[a-z0-9_]+", profile_id):
        raise PropertyCatalogError("invalid property profile id")
    profile = _read_json(ASSET_ROOT / "profiles" / f"{profile_id}.json")
    _validate_profile(profile, profile_id)

    schemas: Dict[str, Dict[str, Any]] = {}
    schema_paths: Dict[str, Path] = {}
    for path in sorted((ASSET_ROOT / "schemas").glob("*.json")):
        payload = _read_json(path)
        if payload.get("property_schema_id") in profile["property_schema_ids"]:
            _validate_schema(payload)
            schemas[payload["property_schema_id"]] = payload
            schema_paths[payload["property_schema_id"]] = path
    templates: Dict[str, Dict[str, Any]] = {}
    template_paths: Dict[str, Path] = {}
    for path in sorted((ASSET_ROOT / "templates").glob("*.json")):
        payload = _read_json(path)
        if payload.get("template_id") in profile["template_ids"]:
            _validate_template(payload)
            templates[payload["template_id"]] = payload
            template_paths[payload["template_id"]] = path

    if set(schemas) != set(profile["property_schema_ids"]):
        raise PropertyCatalogError("profile references missing property schemas")
    if set(templates) != set(profile["template_ids"]):
        raise PropertyCatalogError("profile references missing assertion templates")
    if any(template["chisel_family"] != profile["chisel_family"] for template in templates.values()):
        raise PropertyCatalogError("template chisel family does not match profile")

    gold_binding_id = profile.get("gold_binding_id")
    gold_binding_path = None
    gold_bindings = None
    if gold_binding_id:
        gold_binding_path = ASSET_ROOT / "gold_bindings" / f"{gold_binding_id}.json"
        gold_bindings = _read_json(gold_binding_path)
        _validate_gold_bindings(
            gold_bindings, gold_binding_id, profile, schemas, templates
        )

    candidates = {
        item["candidate_id"]: item for item in profile["binding_candidates"]
    }
    if len(candidates) != len(profile["binding_candidates"]):
        raise PropertyCatalogError("duplicate binding candidate id")
    _validate_cross_references(profile, schemas, templates, candidates)
    assets = {
        f"profiles/{profile_id}.json": ASSET_ROOT / "profiles" / f"{profile_id}.json",
        **{
            f"schemas/{schema_paths[schema_id].name}": schema_paths[schema_id]
            for schema_id in schemas
        },
        **{
            f"templates/{template_paths[template_id].name}": template_paths[template_id]
            for template_id in templates
        },
        f"formal_contracts/{profile.get('formal_contract_id', profile_id)}.json": (
            ASSET_ROOT / "formal_contracts" / f"{profile.get('formal_contract_id', profile_id)}.json"
        ),
    }
    if gold_binding_path is not None:
        assets[f"gold_bindings/{gold_binding_path.name}"] = gold_binding_path
    if any(
        schema["source"]["kind"] == "protocol_requirement"
        for schema in schemas.values()
    ):
        assets["../protocol_assets/tilelink/rules.json"] = (
            ASSET_ROOT.parent / "protocol_assets" / "tilelink" / "rules.json"
        )
    try:
        review = load_property_review(profile_id)
        verify_review_assets(review, assets)
    except PropertyReviewError as exc:
        if require_approved:
            raise PropertyCatalogError(str(exc)) from exc
        review = None
    if require_approved and review["review_status"] != "approved":
        raise PropertyCatalogError("property profile is not Codex-approved")
    return PropertyCatalog(
        profile=profile,
        schemas=schemas,
        templates=templates,
        candidates=candidates,
        formal_contract_id=profile.get(
            "formal_contract_id", profile["property_profile_id"]
        ),
        review=review,
        gold_bindings=gold_bindings,
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
        "binding_policy": _binding_policy(catalog),
        "target": {
            key: catalog.profile["target"][key]
            for key in ("file_id", "marker_id")
        },
        "source_targets": [
            {key: item[key] for key in ("file_id", "marker_id")}
            for item in catalog.profile.get("source_targets", [])
        ],
    }


def _binding_policy(catalog: PropertyCatalog) -> Dict[str, Dict[str, Any]]:
    policy = {}
    for template in catalog.templates.values():
        for role, slot in template["slots"].items():
            candidates = sorted(
                candidate["candidate_id"]
                for candidate in catalog.candidates.values()
                if role in candidate["roles"] and candidate["type"] == slot["type"]
            )
            policy[role] = {
                "mode": "model_select" if len(candidates) >= 2 else "deterministic",
                "candidate_ids": candidates,
            }
    return dict(sorted(policy.items()))


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


def validate_property_schema(value: Dict[str, Any]) -> None:
    fields = {
        "schema_version", "property_schema_id", "category", "layer", "title",
        "source", "rule_id", "channel_scope", "trigger_event", "response_event",
        "matching_key", "temporal_shape", "bound", "preconditions",
        "optional_behavior_policy", "environment_assumptions",
        "required_observations", "template_ids", "review_required",
    }
    _exact_fields(value, fields, fields, "property_schema")
    if value["schema_version"] != "property_schema.v2":
        raise PropertyCatalogError("unsupported property schema version")
    _exact_fields(
        value["source"],
        {"kind", "document", "locator", "statement"},
        {"kind", "document", "locator", "statement"},
        "property_schema.source",
    )
    if value["source"]["kind"] not in SOURCE_KINDS:
        raise PropertyCatalogError("invalid property source kind")
    is_protocol = value["source"]["kind"] == "protocol_requirement"
    if is_protocol != isinstance(value["rule_id"], str):
        raise PropertyCatalogError("protocol schema must have rule_id and non-protocol schema must not")
    _exact_fields(value["channel_scope"], {"channels", "message_classes", "scope"}, {"channels", "message_classes", "scope"}, "property_schema.channel_scope")
    if not isinstance(value["channel_scope"]["channels"], list) or not all(
        channel in {"A", "B", "C", "D", "E"} for channel in value["channel_scope"]["channels"]
    ):
        raise PropertyCatalogError("property schema channel scope is invalid")
    for field in ("trigger_event", "response_event"):
        event = value[field]
        if event is None:
            continue
        _exact_fields(event, {"kind", "description", "channel", "message_classes"}, {"kind", "description", "channel", "message_classes"}, f"property_schema.{field}")
        if event["channel"] is not None and event["channel"] not in {"A", "B", "C", "D", "E"}:
            raise PropertyCatalogError("property schema event channel is invalid")
    _exact_fields(value["matching_key"], {"fields", "semantics"}, {"fields", "semantics"}, "property_schema.matching_key")
    _exact_fields(value["bound"], {"kind", "minimum", "maximum"}, {"kind", "minimum", "maximum"}, "property_schema.bound")
    if value["bound"]["kind"] not in {"none", "cycles"}:
        raise PropertyCatalogError("property schema bound kind is invalid")
    if value["temporal_shape"] not in {"invariant", "stable_while_stalled", "forbid_while_pending", "response_eventually", "bounded_liveness"}:
        raise PropertyCatalogError("property schema temporal shape is invalid")
    if value["optional_behavior_policy"] not in {"required", "implementation_defined", "environment_constrained"}:
        raise PropertyCatalogError("property schema optional behavior policy is invalid")
    observations = value["required_observations"]
    if not isinstance(observations, list) or not observations:
        raise PropertyCatalogError("property schema requires observations")
    roles = set()
    for observation in observations:
        _exact_fields(observation, {"id", "role", "type", "description"}, {"id", "role", "type", "description"}, "property_schema.required_observations[]")
        if observation["role"] in roles:
            raise PropertyCatalogError("property schema observation roles must be unique")
        roles.add(observation["role"])


_validate_schema = validate_property_schema


def _schema_slots(schema: Dict[str, Any]) -> Dict[str, str]:
    return {
        observation["role"]: observation["type"]
        for observation in schema["required_observations"]
    }


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
        "source_targets", "binding_candidates", "formal_contract_id", "index_roots",
        "gold_binding_id",
    }
    required = fields - {"source_targets", "formal_contract_id", "gold_binding_id"}
    _exact_fields(value, fields, required, "property_profile")
    if value["schema_version"] != "property_profile.v1":
        raise PropertyCatalogError("unsupported property profile version")
    if value["property_profile_id"] != requested_id:
        raise PropertyCatalogError("property profile id does not match filename")
    if "formal_contract_id" in value and not re.fullmatch(
        r"[a-z0-9_]+", value["formal_contract_id"]
    ):
        raise PropertyCatalogError("invalid formal contract id")
    if "gold_binding_id" in value and not re.fullmatch(
        r"[a-z0-9_]+", value["gold_binding_id"]
    ):
        raise PropertyCatalogError("invalid gold binding id")
    if (
        not isinstance(value["index_roots"], list)
        or not value["index_roots"]
        or not all(
            isinstance(root, str)
            and root.startswith("Chisel/")
            and ".." not in Path(root).parts
            for root in value["index_roots"]
        )
    ):
        raise PropertyCatalogError("property profile requires workspace-relative index roots")
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
            expected = _schema_slots(schema)
            actual = {
                name: definition["type"]
                for name, definition in template["slots"].items()
            }
            if expected != actual:
                raise PropertyCatalogError("schema/template slot contract mismatch")
    required_roles = {
        role
        for schema in schemas.values()
        for role in _schema_slots(schema)
    }
    for role in required_roles:
        if not any(
            role in candidate["roles"]
            and any(
                _schema_slots(schema).get(role) == candidate["type"]
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


def _validate_gold_bindings(
    value: Dict[str, Any],
    requested_id: str,
    profile: Dict[str, Any],
    schemas: Dict[str, Dict[str, Any]],
    templates: Dict[str, Dict[str, Any]],
) -> None:
    fields = {
        "schema_version", "gold_binding_id", "property_profile_id", "bindings",
        "selection_trials",
    }
    _exact_fields(value, fields, fields, "gold_binding_list")
    if value["schema_version"] != "gold_binding_list.v1":
        raise PropertyCatalogError("unsupported gold binding list version")
    if value["gold_binding_id"] != requested_id:
        raise PropertyCatalogError("gold binding id does not match filename")
    if value["property_profile_id"] != profile["property_profile_id"]:
        raise PropertyCatalogError("gold binding profile mismatch")
    bindings = value["bindings"]
    if not isinstance(bindings, dict) or set(bindings) != set(schemas):
        raise PropertyCatalogError("gold binding list must cover every profile schema")
    candidates = {
        candidate["candidate_id"]: candidate
        for candidate in profile["binding_candidates"]
    }
    for schema_id, binding in bindings.items():
        _exact_fields(
            binding,
            {"template_id", "bindings", "parameters", "base_label", "evidence"},
            {"template_id", "bindings", "parameters", "base_label", "evidence"},
            f"gold_binding_list.bindings.{schema_id}",
        )
        template_id = binding["template_id"]
        if template_id not in templates or schema_id not in templates[template_id]["property_schema_ids"]:
            raise PropertyCatalogError("gold binding template does not implement schema")
        template = templates[template_id]
        if set(binding["bindings"]) != set(template["slots"]):
            raise PropertyCatalogError("gold binding must cover template slots")
        for role, candidate_id in binding["bindings"].items():
            candidate = candidates.get(candidate_id)
            if (
                candidate is None
                or role not in candidate["roles"]
                or candidate["type"] != template["slots"][role]["type"]
            ):
                raise PropertyCatalogError("gold binding candidate is incompatible")
        if set(binding["parameters"]) != set(template["parameters"]):
            raise PropertyCatalogError("gold binding parameters do not match template")
    trials = value["selection_trials"]
    if not isinstance(trials, list):
        raise PropertyCatalogError("gold binding selection trials must be a list")
    for trial in trials:
        _exact_fields(
            trial,
            {
                "slot", "ranked_candidate_ids", "gold_candidate_id",
                "manual_corrected", "model", "evidence_ref", "reason",
            },
            {
                "slot", "ranked_candidate_ids", "gold_candidate_id",
                "manual_corrected", "model", "evidence_ref", "reason",
            },
            "gold_binding_list.selection_trials[]",
        )
        ranked = trial["ranked_candidate_ids"]
        if (
            not isinstance(ranked, list)
            or len(ranked) < 2
            or len(set(ranked)) != len(ranked)
            or trial["gold_candidate_id"] not in ranked
            or not isinstance(trial["manual_corrected"], bool)
            or not all(
                isinstance(trial[field], str) and trial[field]
                for field in ("model", "evidence_ref", "reason")
            )
        ):
            raise PropertyCatalogError("invalid gold binding selection trial")
