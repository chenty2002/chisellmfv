"""Deterministic evidence-carrying lowering of V3 obligations."""

from __future__ import annotations

import hashlib
import json
from typing import Any, Dict, Iterable, Mapping, Optional

from .property_catalog import PropertyCatalog
from .property_ir import LOWERING_FAMILIES, validate_property_schema_v3


FAMILY_CAPABILITIES = {
    "stability": {"obligation_kinds": {"safety"}, "state": "previous_value", "checks": {"channel_polarity", "optional_paths"}},
    "response_scoreboard": {"obligation_kinds": {"response"}, "state": "outstanding_scoreboard", "checks": {"channel_polarity", "identifier_lifetime", "optional_paths"}},
    "serialization": {"obligation_kinds": {"serialization", "safety"}, "state": "lifetime_scoreboard", "checks": {"identifier_lifetime", "optional_paths"}},
    "multibeat": {"obligation_kinds": {"safety"}, "state": "beat_lifecycle", "checks": {"beat_completion", "channel_polarity"}},
    "permission_state": {"obligation_kinds": {"permission"}, "state": "permission_ghost_state", "checks": {"permission_transition", "cardinality"}},
    "data_relation": {"obligation_kinds": {"data"}, "state": "data_ghost_state", "checks": {"identifier_lifetime", "optional_paths"}},
    "bounded_liveness": {"obligation_kinds": {"bounded_liveness"}, "state": "wait_counter", "checks": {"bound", "environment_boundary"}},
}


class PropertyCompilationError(ValueError):
    """Raised when an unsupported semantic shape would otherwise be guessed."""


def compile_manifest(
    manifest: Dict[str, Any],
    catalog: PropertyCatalog,
    *,
    rtl_properties: Optional[Iterable[Mapping[str, Any]]] = None,
) -> Dict[str, Any]:
    records = list(rtl_properties or [])
    reviewed_hashes = {
        item["asset_id"]: item["sha256"]
        for item in (catalog.review or {}).get("assets", [])
        if item.get("kind") in {"schema", "template"}
    }
    certificates = []
    for instance in manifest["instances"]:
        schema = catalog.schemas[instance["property_schema_id"]]
        template = catalog.templates[instance["template_id"]]
        certificates.append(
            _compile_instance(instance, schema, template, records, reviewed_hashes)
        )
    material = json.dumps(certificates, sort_keys=True, separators=(",", ":")).encode()
    return {
        "schema_version": "compilation_certificate.v1",
        "property_profile_id": catalog.profile["property_profile_id"],
        "compiler": {"name": "coupledl2_property_compiler", "mode": "deterministic", "version": 1},
        "lowering_families": sorted(LOWERING_FAMILIES),
        "instances": certificates,
        "certificate_sha256": hashlib.sha256(material).hexdigest(),
    }


def build_witness_plan(manifest: Dict[str, Any], catalog: PropertyCatalog) -> Dict[str, Any]:
    instances = []
    for instance in manifest["instances"]:
        schema = catalog.schemas[instance["property_schema_id"]]
        validate_property_schema_v3(schema)
        oracle = schema["oracle_plan"]
        instances.append({
            "instance_id": instance["instance_id"],
            "property_schema_id": instance["property_schema_id"],
            "trigger_event_ids": oracle["non_vacuity"]["trigger_event_ids"],
            "cover_goals": oracle["positive_traces"],
            "negative_oracles": oracle["negative_traces"],
            "mutation_classes": oracle["mutation_classes"],
            "observer_requirements": oracle["non_vacuity"]["observer_requirements"],
            "state_requirements": schema["observable_contract"]["ghost_state"],
            "assumption_satisfiable_required": oracle["non_vacuity"]["assumption_satisfiable"],
            "parent_evidence_reusable": False,
        })
    return {"schema_version": "witness_plan.v1", "instances": instances}


def initial_semantic_evidence(
    catalog: PropertyCatalog, certificate: Dict[str, Any]
) -> Dict[str, Any]:
    return {
        "schema_version": "semantic_evidence.v1",
        "approval": {
            "approved_by_codex": bool(catalog.review and catalog.review["review_status"] == "approved"),
            "review_id": catalog.review["review_id"] if catalog.review else None,
        },
        "independent_gold_label": {"status": "not_adjudicated", "adjudicator": None, "oracle_ref": None},
        "compilation_certificate_sha256": certificate["certificate_sha256"],
        "instances": [{
            "instance_id": item["instance_id"],
            "ir_validation": "passed",
            "normative_rule": item["normative_rule"],
            "asset_hashes": item["asset_hashes"],
            "compile_status": "passed",
            "rtl_identity": "passed" if item["rtl_properties"] else "not_checked",
            "non_vacuity": "not_checked",
            "non_vacuity_reason": "formal witness evidence has not been supplied",
            "positive_witness": None,
            "observer_reachability": "not_checked",
            "state_reachability": "not_checked",
            "assumption_satisfiability": "not_checked",
            "negative_oracle": "not_checked",
            "experiment_eligible": False,
        } for item in certificate["instances"]],
        "experiment_eligible": False,
    }


def _compile_instance(
    instance: Dict[str, Any], schema: Dict[str, Any], template: Dict[str, Any],
    records: list[Mapping[str, Any]], reviewed_hashes: Mapping[str, str],
) -> Dict[str, Any]:
    validate_property_schema_v3(schema)
    family = schema["lowering_family"]
    capability = FAMILY_CAPABILITIES.get(family)
    if capability is None or schema["obligation_kind"] not in capability["obligation_kinds"]:
        raise PropertyCompilationError(
            f"unsupported semantic shape: {schema['obligation_kind']} via {family}"
        )
    if instance["template_id"] not in schema["template_ids"]:
        raise PropertyCompilationError("template is not compatible with obligation")
    fragments = template["fragments"]
    assertion_hash = hashlib.sha256(fragments["assertion_block"].encode()).hexdigest()
    schema_payload_hash = hashlib.sha256(
        json.dumps(schema, sort_keys=True, separators=(",", ":")).encode()
    ).hexdigest()
    labels = sorted(
        str(item["rtl_label"])
        for item in records
        if str(item.get("rtl_label", "")).startswith(instance["base_label"] + "__E")
    )
    automaton = schema["event_automaton"]
    return {
        "instance_id": instance["instance_id"],
        "property_schema_id": instance["property_schema_id"],
        "asset_hashes": {
            "schema_payload_sha256": schema_payload_hash,
            "reviewed_schema_sha256": reviewed_hashes.get(schema["property_schema_id"]),
            "reviewed_template_sha256": reviewed_hashes.get(template["template_id"]),
        },
        "normative_rule": {
            "source_kind": schema["source"]["kind"],
            "rule_id": schema["rule_id"],
            "document": schema["source"]["document"],
            "locator": schema["source"]["locator"],
        },
        "obligation_kind": schema["obligation_kind"],
        "lowering_family": family,
        "template_id": instance["template_id"],
        "selection_mode": "model_select" if len(schema["template_ids"]) > 1 else "deterministic",
        "state_representation": capability["state"],
        "static_checks": sorted(capability["checks"]),
        "static_gate": "passed",
        "ir_node_map": {
            "events": [item["event_id"] for item in automaton["events"]],
            "transitions": [f"{item['from']}--{item['event']}-->{item['to']}" for item in automaton["transitions"]],
            "correlation_keys": schema["correlation"]["keys"],
            "observer_roles": [item["role"] for item in schema["observable_contract"]["observations"]],
            "environment_layers": ["protocol_premises", "fairness", "harness_restrictions", "proof_simplifications"],
        },
        "outputs": {
            "assert": {"template_fragment_sha256": assertion_hash, "base_label": instance["base_label"]},
            "assume": schema["environment_boundary"],
            "cover": schema["oracle_plan"]["positive_traces"],
            "observer_requirements": schema["observable_contract"],
        },
        "rtl_properties": labels,
    }
