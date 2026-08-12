"""Bounded Stage-1 controller with typed candidate-only model calls."""

from __future__ import annotations

import hashlib
import json
import re
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Callable, Dict, Mapping, Optional, Protocol

from src.core.artifact_contract import file_sha256, write_stage_outcome
from src.core.formal_operations import canonical_sha256

from .assets import AssetLibrary, load_reviewed_assets
from .authoring_tools import (
    AMBIGUITY_TOOL_NAME,
    bind_and_instantiate_tools,
    binding_tools,
    extract_obligation_tools,
    monitor_tools,
    obligation_tools,
)
from .config import (
    AUTHORING_CANDIDATES_SCHEMA,
    CANDIDATE_ASSET_DELTA_SCHEMA,
    REVIEW_REQUEST_SCHEMA,
    STAGE_INPUTS_SCHEMA,
)
from .ir.binding import BindingValidationError, validate_binding
from .ir.monitor import MonitorValidationError, validate_monitor
from .ir.obligation import ObligationValidationError, validate_obligation
from .ir.semantic import validate_semantic_index
from .monitor_compiler import materialize_generic_bindings
from .stages import get_stage_spec
from .property_decomposition import validate_property_decomposition
from .workspace import SpecFlowWorkspace


class AuthoringModel(Protocol):
    def chat_with_tools(self, **kwargs: Any) -> Dict[str, Any]: ...


class AuthoringError(ValueError):
    """Raised when the single authoring submission is invalid."""

    def __init__(self, code: str, message: str):
        self.code = code
        super().__init__(f"{code}: {message}")


@dataclass(frozen=True)
class AuthoringResult:
    status: str
    stage_dir: Path
    review_request: Optional[Path]
    model_calls: int


def run_asset_authoring(
    workspace: SpecFlowWorkspace,
    model: AuthoringModel,
    asset_library: Optional[AssetLibrary] = None,
) -> AuthoringResult:
    """Author typed candidates and stop at the mandatory external review gate."""

    assets = asset_library or load_reviewed_assets()
    manifest = _read_json(workspace.manifest_path)
    if manifest.get("preflight_status") != "index_ready":
        raise AuthoringError("preflight_incomplete", "semantic index is not frozen")
    round_id = 1
    stage_dir = workspace.stage_dir("asset_authoring")
    if (stage_dir / "stage_result.json").exists():
        raise FileExistsError("asset_authoring already has a stage outcome")
    semantic = validate_semantic_index(
        _read_json(workspace.indexes_dir / "chisel_semantic_index.json")
    )
    public_spec = _read_json(workspace.inputs_dir / "public_spec_package.json")
    decomposition = validate_property_decomposition(
        _read_json(workspace.inputs_dir / "property_decomposition.json"), public_spec
    )
    authoring_scope = _read_json(workspace.inputs_dir / "authoring_scope.json")
    if (
        authoring_scope.get("schema_version") != "specflow_authoring_scope"
        or authoring_scope.get("specification_id") != public_spec["specification_id"]
        or authoring_scope.get("spec_sha256") != public_spec["spec_sha256"]
    ):
        raise AuthoringError("authoring_scope_invalid", "public task scope identity mismatch")
    project = _read_json(workspace.inputs_dir / "project_contract.json")
    configuration = _read_json(workspace.inputs_dir / "configuration.json")
    confirmed = [
        row for row in semantic.get("objects", [])
        if row.get("fact_status") == "elaboration_confirmed"
    ]
    if not confirmed:
        raise AuthoringError("no_confirmed_objects", "Stage 1 has no binding authority")
    object_types = {
        row["object_id"]: {
            "kind": row["chisel_type"]["kind"],
            "width": row["chisel_type"]["width"],
            "signed": row["chisel_type"]["signed"],
        }
        for row in confirmed
    }
    clause_slices = _clause_slices(
        workspace.inputs_dir / "specification.md",
        authoring_scope["clause_ids"],
    )
    stage_inputs = {
        "schema_version": STAGE_INPUTS_SCHEMA,
        "round_id": round_id,
        "project": {
            "project_id": project["project_id"],
            "generator": {
                "top_name": project["generator"]["top_name"],
                "configuration_schema": project["generator"]["configuration_schema"],
            },
            "formal": project["formal"],
        },
        "configuration": {
            "configuration_id": configuration["configuration_id"],
            "parameters": configuration["parameters"],
        },
        "specification": {
            "specification_id": public_spec["specification_id"],
            "spec_sha256": public_spec["spec_sha256"],
            "clauses": clause_slices,
            "full_text": (
                workspace.inputs_dir / "specification.md"
            ).read_text(encoding="utf-8"),
        },
        "authoring_scope": authoring_scope,
        "semantic_objects": confirmed,
        "asset_library": assets.snapshot(),
        "input_hashes": manifest["input_hashes"],
        "index_hashes": manifest["index_hashes"],
    }
    _write_json(stage_dir / "stage_inputs.json", stage_inputs)
    calls = 0
    call_refs: list[str] = []
    call_audit: list[Dict[str, Any]] = []
    candidate_attempts: list[Dict[str, Any]] = []

    try:
        selected_archetype_id = _selected_monitor_archetype(
            authoring_scope["component_role_hints"]
        )
        model_stage_inputs = {
            key: stage_inputs[key]
            for key in (
                "schema_version",
                "round_id",
                "project",
                "configuration",
                "specification",
                "authoring_scope",
                "semantic_objects",
                "input_hashes",
                "index_hashes",
            )
        }
        asset_snapshot = stage_inputs["asset_library"]
        model_stage_inputs["asset_library"] = {
            "schema_version": asset_snapshot["schema_version"],
            "obligation_schemas": asset_snapshot["obligation_schemas"],
            "api_adapters": asset_snapshot["api_adapters"],
            "monitor_archetypes": [
                row
                for row in asset_snapshot["monitor_archetypes"]
                if row["asset_id"] == selected_archetype_id
            ],
        }
        raw_obligations, used, refs = _request_candidates(
            model,
            expected_tool="submit_obligation_candidates",
            tools=obligation_tools(
                [row["locator"] for row in clause_slices],
                object_types,
                configuration["configuration_id"],
                authoring_scope["primary_component_ids"],
            ),
            context={
                "stage_inputs": model_stage_inputs,
                "task": (
                    "author exactly one obligation for each authoring_scope.primary_component_ids; "
                    "component IDs with cover/state/assumption role hints are monitor evidence, not obligations; "
                    "obligations cannot reference monitor state; use lookup_table for a complete public table"
                ),
            },
            validator=lambda rows: _validate_obligations(
                rows,
                object_types,
                clause_slices,
                public_spec["spec_sha256"],
                configuration["configuration_id"],
                set(authoring_scope["primary_component_ids"]),
                authoring_scope["require_complete_primary_set"],
            ),
            audit_log=call_audit,
            candidate_attempt_log=candidate_attempts,
            max_tokens=8192,
        )
        calls += used
        call_refs += refs
        if any(row["support_status"] in {"unsupported", "ambiguous"} for row in raw_obligations):
            raise AuthoringError("unsupported_obligation", "candidate obligation is unsupported or ambiguous")
        obligation_ids = {row["obligation_id"] for row in raw_obligations}

        raw_bindings, used, refs = _request_candidates(
            model,
            expected_tool="submit_binding_candidates",
            tools=binding_tools(
                obligation_ids,
                confirmed,
                configuration["configuration_id"],
                assets.api_adapters,
            ),
            context={
                "task": "bind obligations to confirmed object IDs",
                "obligations": raw_obligations,
                "semantic_objects": confirmed,
                "api_adapters": assets.snapshot()["api_adapters"],
            },
            validator=lambda rows: _validate_bindings(
                rows,
                semantic,
                obligation_ids,
                configuration["configuration_id"],
                set(assets.api_adapters),
            ),
            audit_log=call_audit,
            candidate_attempt_log=candidate_attempts,
            max_tokens=8192,
        )
        calls += used
        call_refs += refs
        binding_ids = {row["binding_id"] for row in raw_bindings}

        raw_monitors, used, refs = _request_candidates(
            model,
            expected_tool="submit_monitor_candidates",
            tools=monitor_tools(
                obligation_ids,
                binding_ids,
                object_types,
                configuration["configuration_id"],
                assets.monitor_archetypes,
                authoring_scope["component_ids"],
                authoring_scope["component_role_hints"],
                selected_archetype_id,
            ),
            context={
                "task": "compose typed monitor IR from reviewed archetype IDs",
                "obligations": raw_obligations,
                "bindings": raw_bindings,
                "archetype": dict(
                    assets.monitor_archetypes[selected_archetype_id]
                ),
                "component_role_hints": authoring_scope["component_role_hints"],
                "typed_state_contract": {
                    "init": "must have exactly the declared state type",
                    "update": "must have exactly the declared state type",
                    "clear": "must be Bool regardless of the declared state type",
                    "property_expression_and_guard": "must both be Bool",
                    "historical_state": (
                        "previous_value and past_valid may reference only state_id "
                        "values declared in this monitor state array"
                    ),
                },
                "submission_contract": (
                    "submit exactly one monitor; every selected component ID must "
                    "appear exactly once with its declared role"
                ),
            },
            validator=lambda rows: _validate_monitors(
                rows,
                object_types,
                obligation_ids,
                binding_ids,
                assets,
                configuration["configuration_id"],
                set(authoring_scope["component_ids"]),
                authoring_scope["component_role_hints"],
                authoring_scope["require_complete_primary_set"],
            ),
            audit_log=call_audit,
            candidate_attempt_log=candidate_attempts,
            max_tokens=16384,
        )
        calls += used
        call_refs += refs
    except AuthoringError as exc:
        calls = len(call_audit)
        _write_jsonl(_model_log_path(workspace, round_id), call_audit)
        _write_jsonl(
            _candidate_attempt_log_path(workspace, round_id), candidate_attempts
        )
        failure_status = (
            "ambiguous"
            if exc.code == "spec_ambiguity"
            else "invalid_submission"
            if exc.code == "invalid_model_submission"
            else "unsupported"
        )
        _write_json(
            stage_dir / "authoring_candidates.json",
            {
                "schema_version": AUTHORING_CANDIDATES_SCHEMA,
                "status": failure_status,
                "obligations": locals().get("raw_obligations", []),
                "bindings": locals().get("raw_bindings", []),
                "monitors": locals().get("raw_monitors", []),
                "model_call_refs": call_refs,
                "error": {"code": exc.code, "message": str(exc)},
            },
        )
        write_stage_outcome(
            stage_dir,
            get_stage_spec("asset_authoring"),
            {
                "success": False,
                "status": failure_status,
                "error_kind": exc.code,
                "error": str(exc),
                "round_id": round_id,
                "model_calls": calls,
            },
            source_state=manifest,
        )
        _set_review_state(workspace, "not_applicable")
        return AuthoringResult(
            failure_status,
            stage_dir,
            None,
            calls,
        )

    candidates = {
        "schema_version": AUTHORING_CANDIDATES_SCHEMA,
        "status": "candidate",
        "obligations": raw_obligations,
        "bindings": raw_bindings,
        "monitors": raw_monitors,
        "model_call_refs": call_refs,
    }
    calls = len(call_audit)
    _write_jsonl(_model_log_path(workspace, round_id), call_audit)
    _write_jsonl(_candidate_attempt_log_path(workspace, round_id), candidate_attempts)
    _write_json(stage_dir / "authoring_candidates.json", candidates)
    delta = {
        "schema_version": CANDIDATE_ASSET_DELTA_SCHEMA,
        "base_asset_library_sha256": canonical_sha256(stage_inputs["asset_library"]),
        "new_run_local_ids": sorted(
            obligation_ids
            | binding_ids
            | {row["monitor_id"] for row in raw_monitors}
        ),
        "modified_reviewed_asset_ids": [],
    }
    _write_json(stage_dir / "candidate_asset_delta.json", delta)
    review_request = _build_review_request(stage_dir, candidates, delta, round_id)
    _write_json(stage_dir / "review_request.json", review_request)
    write_stage_outcome(
        stage_dir,
        get_stage_spec("asset_authoring"),
        {
            "success": False,
            "status": "awaiting_review",
            "error_kind": "review_required",
            "round_id": round_id,
            "model_calls": calls,
        },
        source_state=manifest,
    )
    _set_review_state(workspace, "awaiting_review")
    return AuthoringResult("awaiting_review", stage_dir, stage_dir / "review_request.json", calls)


def run_two_stage_authoring(
    workspace: SpecFlowWorkspace,
    model: AuthoringModel,
    *,
    max_tokens: tuple[int, int],
    asset_library: Optional[AssetLibrary] = None,
) -> Dict[str, Any]:
    """Create one run-local S2 package with exactly two compact model calls."""

    if len(max_tokens) != 2 or any(value < 1 for value in max_tokens):
        raise ValueError("two positive output budgets are required")
    assets = asset_library or load_reviewed_assets()
    manifest = _read_json(workspace.manifest_path)
    if manifest.get("preflight_status") != "index_ready":
        raise AuthoringError("preflight_incomplete", "semantic index is not frozen")
    stage_dir = workspace.stage_dir("asset_authoring")
    if any(stage_dir.iterdir()):
        raise FileExistsError("two-stage authoring directory is immutable once written")

    semantic = validate_semantic_index(
        _read_json(workspace.indexes_dir / "chisel_semantic_index.json")
    )
    confirmed = [
        row
        for row in semantic["objects"]
        if row.get("fact_status") == "elaboration_confirmed"
        and row.get("accessibility") in {"direct", "wrapper"}
    ]
    public_spec = _read_json(workspace.inputs_dir / "public_spec_package.json")
    scope = _read_json(workspace.inputs_dir / "authoring_scope.json")
    project = _read_json(workspace.inputs_dir / "project_contract.json")
    configuration = _read_json(workspace.inputs_dir / "configuration.json")
    clauses = _clause_slices(
        workspace.inputs_dir / "specification.md", scope["clause_ids"]
    )
    adapters = [
        (asset_id, row)
        for asset_id, row in assets.api_adapters.items()
        if row.get("project_id") == project["project_id"]
        and row.get("strategy") == "wrapper"
    ]
    if len(adapters) != 1:
        raise AuthoringError("adapter_unavailable", "one project wrapper is required")
    adapter_id = adapters[0][0]
    primary_ids = list(scope["primary_component_ids"])
    intent_candidates = list(scope.get("intent_candidates", []))
    stage_inputs = {
        "schema_version": STAGE_INPUTS_SCHEMA,
        "round_id": 1,
        "method": "two_stage_specflow",
        "project": {
            "project_id": project["project_id"],
            "generator": project["generator"],
            "formal": project["formal"],
        },
        "configuration": configuration,
        "specification": {
            "specification_id": public_spec["specification_id"],
            "spec_sha256": public_spec["spec_sha256"],
            "clauses": clauses,
            "full_text": (workspace.inputs_dir / "specification.md").read_text(
                encoding="utf-8"
            ),
        },
        "authoring_scope": scope,
        "semantic_objects": confirmed,
        "asset_library": assets.snapshot(),
        "input_hashes": manifest["input_hashes"],
        "index_hashes": manifest["index_hashes"],
    }
    _write_json(stage_dir / "stage_inputs.json", stage_inputs)
    audits: list[Dict[str, Any]] = []
    attempts: list[Dict[str, Any]] = []
    refs: list[str] = []
    try:
        if {row["clause_locator"] for row in intent_candidates} != {
            row["locator"] for row in clauses
        }:
            raise AuthoringError(
                "intent_contract_missing", "every required clause needs a reviewed intent"
            )
        intents, _used, intent_refs = _request_candidates(
            model,
            expected_tool="extract_obligations",
            tools=extract_obligation_tools(intent_candidates),
            context={
                "task": "select one reviewed complete intent ID for every required clause",
                "clauses": clauses,
                "intent_candidates": intent_candidates,
            },
            validator=lambda rows: _validate_extracted_intents(
                rows, intent_candidates
            ),
            audit_log=audits,
            candidate_attempt_log=attempts,
            max_tokens=max_tokens[0],
        )
        refs.extend(intent_refs)
        indexed = [
            {"obligation_ref": f"obligation_{index:02d}", **row}
            for index, row in enumerate(intents, 1)
        ]
        grammar_candidates = _enumerate_two_stage_candidates(
            indexed,
            confirmed,
            assets.monitor_archetypes,
            reference_relations=assets.reference_relations,
            clauses=clauses,
            full_text=stage_inputs["specification"]["full_text"],
            scope=scope,
        )
        stage_inputs["candidate_grammar"] = grammar_candidates
        _write_json(stage_dir / "stage_inputs.json", stage_inputs)
        instances, _used, instance_refs = _request_candidates(
            model,
            expected_tool="bind_and_instantiate",
            tools=bind_and_instantiate_tools(
                grammar_candidates,
            ),
            context={
                "task": (
                    "select exactly one complete enumerated formula per required clause; "
                    "match the clause text and named semantic objects because candidate "
                    "order is not a ranking"
                ),
                "obligations": indexed,
                "candidates": grammar_candidates,
                "clauses": clauses,
                "semantic_objects": [
                    row
                    for row in confirmed
                    if row["object_id"]
                    in {
                        object_id
                        for candidate in grammar_candidates
                        for object_id in candidate["object_ids"]
                    }
                ],
                "component_role_hints": scope["component_role_hints"],
            },
            validator=lambda rows: _validate_instances(
                rows, grammar_candidates
            ),
            audit_log=audits,
            candidate_attempt_log=attempts,
            max_tokens=max_tokens[1],
        )
        refs.extend(instance_refs)
        obligations, bindings, monitors = _materialize_two_stage_candidates(
            indexed=indexed,
            grammar_candidates=grammar_candidates,
            instances=instances,
            primary_ids=primary_ids,
            scope=scope,
            clauses=clauses,
            public_spec=public_spec,
            configuration=configuration,
            semantic=semantic,
            assets=assets,
            adapter_id=adapter_id,
        )
    except (AuthoringError, ValueError) as exc:
        _write_jsonl(_model_log_path(workspace, 1), audits)
        _write_jsonl(_candidate_attempt_log_path(workspace, 1), attempts)
        _write_json(
            stage_dir / "authoring_candidates.json",
            {
                "schema_version": AUTHORING_CANDIDATES_SCHEMA,
                "status": "invalid",
                "obligations": [],
                "bindings": [],
                "monitors": [],
                "model_call_refs": refs,
                "error": str(exc),
            },
        )
        return write_stage_outcome(
            stage_dir,
            get_stage_spec("asset_authoring"),
            {
                "success": False,
                "status": "invalid_submission",
                "error_kind": getattr(exc, "code", "invalid_submission"),
                "error": str(exc),
                "method": "two_stage_specflow",
                "model_calls": len(audits),
            },
            source_state=manifest,
        )

    _write_jsonl(_model_log_path(workspace, 1), audits)
    _write_jsonl(_candidate_attempt_log_path(workspace, 1), attempts)
    candidates = {
        "schema_version": AUTHORING_CANDIDATES_SCHEMA,
        "status": "run_local_evaluation",
        "obligations": obligations,
        "bindings": bindings,
        "monitors": monitors,
        "model_call_refs": refs,
    }
    _write_json(stage_dir / "authoring_candidates.json", candidates)
    delta = {
        "schema_version": CANDIDATE_ASSET_DELTA_SCHEMA,
        "base_asset_library_sha256": canonical_sha256(stage_inputs["asset_library"]),
        "new_run_local_ids": sorted(
            {row["obligation_id"] for row in obligations}
            | {row["binding_id"] for row in bindings}
            | {row["monitor_id"] for row in monitors}
        ),
        "modified_reviewed_asset_ids": [],
    }
    _write_json(stage_dir / "candidate_asset_delta.json", delta)
    authored_at = datetime.now(timezone.utc).isoformat()
    review = {
        "schema_version": "specflow_experiment_authoring_record",
        "authority": "run_local_evaluation_only",
        "reviewer": "none",
        "reviewed_at": authored_at,
        "candidate_sha256": file_sha256(stage_dir / "authoring_candidates.json"),
        "promotion_allowed": False,
    }
    _write_json(stage_dir / "review_record.json", review)
    package_body = {
        "schema_version": "verification_package",
        "project_id": project["project_id"],
        "configuration_id": configuration["configuration_id"],
        "round_id": 1,
        "input_hashes": manifest["input_hashes"],
        "asset_library": assets.snapshot(),
        "obligations": obligations,
        "bindings": bindings,
        "monitors": monitors,
        "review": {
            "review_record_sha256": file_sha256(stage_dir / "review_record.json"),
            "reviewer": "none_specflow_experiment",
            "reviewed_at": authored_at,
            "semantic_intent_decisions": [],
        },
    }
    package = dict(package_body)
    package["package_id"] = "s2pkg_" + canonical_sha256(package_body)[:24]
    _write_json(stage_dir / "verification_package.json", package)
    manifest["review_state"] = "direct_submission"
    _write_json(workspace.manifest_path, manifest)
    return write_stage_outcome(
        stage_dir,
        get_stage_spec("asset_authoring"),
        {
            "success": True,
            "status": "completed",
            "method": "two_stage_specflow",
            "model_calls": 2,
            "package_id": package["package_id"],
            "verification_package_sha256": file_sha256(
                stage_dir / "verification_package.json"
            ),
        },
        source_state=manifest,
    )


def _validate_extracted_intents(
    rows: list[Mapping[str, Any]],
    candidates: list[Mapping[str, Any]],
) -> list[Dict[str, Any]]:
    by_id = {row["intent_id"]: row for row in candidates}
    locators = {row["clause_locator"] for row in candidates}
    if len(rows) != len(locators):
        raise AuthoringError(
            "incomplete_obligation_set", f"expected {len(locators)} intents"
        )
    normalized = []
    seen = set()
    for row in rows:
        if set(row) != {"intent_id"} or row.get("intent_id") not in by_id:
            raise AuthoringError("invalid_obligation_intent", "unknown intent ID")
        candidate = by_id[row["intent_id"]]
        clause = candidate["clause_locator"]
        if clause in seen:
            raise AuthoringError("incomplete_obligation_set", "required clause is duplicated")
        normalized.append({**candidate, "support_status": "supported"})
        seen.add(clause)
    if seen != locators:
        raise AuthoringError("incomplete_obligation_set", str(sorted(locators - seen)))
    return normalized


def _enumerate_two_stage_candidates(
    indexed: list[Mapping[str, Any]],
    semantic_objects: list[Mapping[str, Any]],
    archetypes: Mapping[str, Mapping[str, Any]],
    *,
    reference_relations: Optional[Mapping[str, Mapping[str, Any]]] = None,
    clauses: list[Mapping[str, Any]],
    full_text: str,
    scope: Mapping[str, Any],
) -> list[Dict[str, Any]]:
    """Enumerate at most four complete, exact-object formulas per clause."""

    archetype_by_temporal = {
        "same_cycle": "direct_relation",
        "reset_initialization": "direct_relation",
        "next_cycle": "previous_value",
        "previous_value": "previous_value",
        "bounded_response": "bounded_counter",
    }
    candidates: list[Dict[str, Any]] = []
    clause_rows = {row["locator"]: row for row in clauses}
    for row in indexed:
        temporal_kind = row["temporal_kind"]
        if temporal_kind == "reference_relation":
            matches = [
                relation
                for relation in (reference_relations or {}).values()
                if relation.get("source_property_id") == row["component_id"]
            ]
            if len(matches) != 1:
                raise AuthoringError(
                    "specification_or_environment_incomplete",
                    f"one reviewed reference relation is required for {row['component_id']}",
                )
            relation = matches[0]
            object_ids = []
            for name in relation["required_objects"]:
                objects = [item for item in semantic_objects if item.get("name") == name]
                if len(objects) != 1:
                    raise AuthoringError(
                        "specification_or_environment_incomplete",
                        f"reference object {name} is not uniquely confirmed",
                    )
                object_ids.append(objects[0]["object_id"])
            candidates.append({
                "candidate_id": f"{row['obligation_ref']}.reference_relation",
                "obligation_ref": row["obligation_ref"],
                "clause_locator": row["clause_locator"],
                "formula_kind": "reference_relation",
                "archetype_id": "algorithmic_reference",
                "reference_relation_id": relation["asset_id"],
                "object_ids": object_ids,
                "bound": 0,
            })
            continue
        archetype_id = archetype_by_temporal[temporal_kind]
        if archetype_id not in archetypes:
            raise AuthoringError("unsupported_archetype", archetype_id)
        clause = row["clause_locator"]
        body = _clause_body(clause_rows[clause]["text"])
        if "uses" in body.lower() and ("pre-edge" in body.lower() or "previous" in body.lower()):
            candidates.append({
                "candidate_id": f"{row['obligation_ref']}.temporal_modifier",
                "obligation_ref": row["obligation_ref"],
                "clause_locator": clause,
                "formula_kind": "temporal_modifier",
                "archetype_id": archetype_id,
                "object_ids": [],
                "bound": row["bound"],
            })
            continue
        local = _rank_semantic_objects(body, semantic_objects)
        broad = _rank_semantic_objects(
            full_text + " " + " ".join(str(item) for item in scope.get("primary_component_ids", [])),
            semantic_objects,
        )
        local_ids = {item["object_id"] for item in local}
        ranked = local + [
            item for item in broad if item["object_id"] not in local_ids
        ]
        lookup = _lookup_formula(row, body, full_text, ranked)
        if lookup is not None:
            candidates.append({
                "candidate_id": f"{row['obligation_ref']}.formula_1",
                "obligation_ref": row["obligation_ref"],
                "clause_locator": clause,
                "formula_kind": "assertion",
                "archetype_id": archetype_id,
                "bound": row["bound"],
                **lookup,
            })
            continue
        compatible = [item for item in ranked if _compatible_observation(item, row)]
        wrapper_outputs = [
            item
            for item in compatible
            if item.get("accessibility") == "wrapper"
            and item.get("direction") == "output"
        ]
        observations = sorted(
            wrapper_outputs or compatible,
            key=lambda item: item.get("direction") != "output",
        )[:2]
        if not observations:
            raise AuthoringError(
                "specification_or_environment_incomplete",
                f"no unique semantic observation for {clause}",
            )
        for observation in observations:
            guards = _guard_variants(body, row, observation, ranked, full_text)
            expected_rows = _expected_variants(
                row, observation, ranked, body, full_text
            )
            if len(guards) == len(expected_rows) > 1:
                formulas = []
                object_ids = set()
                for guard_atoms, expected in zip(guards, expected_rows):
                    formula_object_ids = {observation["object_id"]}
                    formula_object_ids.update(
                        atom["object_id"]
                        for atom in guard_atoms
                        if "object_id" in atom
                    )
                    if expected.get("object_id"):
                        formula_object_ids.add(expected["object_id"])
                    object_ids.update(formula_object_ids)
                    formulas.append({
                        "formula_kind": "assertion",
                        "archetype_id": archetype_id,
                        "object_ids": sorted(formula_object_ids),
                        "observation_id": observation["object_id"],
                        "guard_atoms": guard_atoms,
                        "expected": expected,
                        "relation": row["relation"],
                        "temporal_kind": temporal_kind,
                        "bound": row["bound"],
                    })
                index = 1 + sum(
                    item["obligation_ref"] == row["obligation_ref"]
                    for item in candidates
                )
                candidates.append({
                    "candidate_id": f"{row['obligation_ref']}.formula_{index}",
                    "obligation_ref": row["obligation_ref"],
                    "clause_locator": clause,
                    "formula_kind": "conjunction",
                    "archetype_id": archetype_id,
                    "object_ids": sorted(object_ids),
                    "formulas": formulas,
                    "bound": row["bound"],
                })
                continue
            for guard_atoms in guards:
                for expected in expected_rows:
                    object_ids = {observation["object_id"]}
                    object_ids.update(
                        atom["object_id"] for atom in guard_atoms if "object_id" in atom
                    )
                    if expected.get("object_id"):
                        object_ids.add(expected["object_id"])
                    index = 1 + sum(item["obligation_ref"] == row["obligation_ref"] for item in candidates)
                    if index > 4:
                        break
                    candidates.append({
                        "candidate_id": f"{row['obligation_ref']}.formula_{index}",
                        "obligation_ref": row["obligation_ref"],
                        "clause_locator": clause,
                        "formula_kind": "assertion",
                        "archetype_id": archetype_id,
                        "object_ids": sorted(object_ids),
                        "observation_id": observation["object_id"],
                        "guard_atoms": guard_atoms,
                        "expected": expected,
                        "relation": row["relation"],
                        "temporal_kind": temporal_kind,
                        "bound": row["bound"],
                    })
                if sum(item["obligation_ref"] == row["obligation_ref"] for item in candidates) >= 4:
                    break
            if sum(item["obligation_ref"] == row["obligation_ref"] for item in candidates) >= 4:
                break
        if not any(item["obligation_ref"] == row["obligation_ref"] for item in candidates):
            raise AuthoringError("specification_or_environment_incomplete", clause)
    return candidates


def _clause_body(text: str) -> str:
    parts = text.split("**", 2)
    return parts[-1] if len(parts) == 3 else text


def _name_terms(row: Mapping[str, Any]) -> set[str]:
    values = [str(row.get("name", "")), *map(str, row.get("aliases", []) or [])]
    ignored = {"i", "o", "in", "out", "pad", "wb", "reg", "wire"}
    return {
        token
        for value in values
        for token in re.findall(r"[a-z0-9]+", re.sub(r"([A-Za-z])([0-9])", r"\1_\2", value).lower())
        if len(token) > 1 and token not in ignored
    }


def _rank_semantic_objects(
    text: str, semantic_objects: list[Mapping[str, Any]]
) -> list[Mapping[str, Any]]:
    lowered = text.lower().replace("acknowledge", "ack")
    scored = []
    for item in semantic_objects:
        terms = _name_terms(item)
        score = sum(lowered.count(term) for term in terms)
        if score:
            score = 10 * score + (2 if item.get("direction") == "output" else 0)
            scored.append((score, str(item["object_id"]), item))
    return [item for _score, _identity, item in sorted(scored, key=lambda row: (-row[0], row[1]))]


def _compatible_observation(item: Mapping[str, Any], intent: Mapping[str, Any]) -> bool:
    kind = item["chisel_type"]["kind"]
    return not (
        kind == "Bool"
        and (intent["expected_role"] == "incremented_observation" or intent["relation"] not in {"eq", "neq"})
    )


def _guard_variants(
    text: str,
    intent: Mapping[str, Any],
    observation: Mapping[str, Any],
    ranked: list[Mapping[str, Any]],
    full_text: str,
) -> list[list[Dict[str, Any]]]:
    lowered = text.lower().replace("acknowledge", "ack")
    base: list[Dict[str, Any]] = []
    if "reset" in lowered and "regardless of reset" not in lowered:
        low = (
            "reset low" in lowered
            or "reset=0" in lowered
            or ("both reset and" in lowered and " low" in lowered)
        )
        base.append({"kind": "reset", "value": not low})
    bool_added = False
    for item in ranked:
        if item["object_id"] == observation["object_id"] or item["chisel_type"]["kind"] != "Bool":
            continue
        terms = _name_terms(item)
        high = any(f"{term}=1" in lowered or f"{term} is high" in lowered for term in terms)
        low = any(f"{term}=0" in lowered or f"{term} is low" in lowered for term in terms)
        if high or low:
            base.append({"kind": "bool", "object_id": item["object_id"], "value": high})
            bool_added = True
            if len(base) == 3:
                break
    named = _named_constants(full_text)
    state = next(
        (
            item
            for item in ranked
            if "state" in _name_terms(item)
            and item["object_id"] != observation["object_id"]
        ),
        None,
    )
    if state is not None and len(base) < 3:
        state_name = next(
            (
                name
                for name, _value, width in named
                if width is None and re.search(rf"\b{name}\b", text)
            ),
            None,
        )
        if state_name is not None:
            state_value = next(value for name, value, _width in named if name == state_name)
            base.append({
                "kind": "compare",
                "object_id": state["object_id"],
                "relation": "eq",
                "value": state_value,
            })
    numeric_text = re.sub(r"\bmodulo\s+[0-9]+\b", "", lowered)
    guarded_ids = {atom["object_id"] for atom in base if "object_id" in atom}
    numeric = [
        item for item in ranked
        if item["object_id"] != observation["object_id"]
        and item["object_id"] not in guarded_ids
        and item["chisel_type"]["kind"] != "Bool"
    ]
    variants = []
    comparisons = [
        (relation, int(value))
        for pattern, relation in (
            (r"(?:less than|below)\s+([0-9]+)", "ult"),
            (r"at least\s+([0-9]+)", "uge"),
            (r"not\s+([0-9]+)", "neq"),
        )
        for value in re.findall(pattern, numeric_text)
    ]
    if not comparisons:
        numbers = re.findall(r"(?<![A-Za-z0-9_-])([0-9]+)", numeric_text)
        comparisons = [("eq", int(numbers[0]))] if numbers else []
    if comparisons and numeric and len(base) < 3:
        for item in numeric[:2]:
            for relation, number in dict.fromkeys(comparisons):
                if item["chisel_type"]["kind"] == "SInt":
                    relation = {"ult": "slt", "ule": "sle", "ugt": "sgt", "uge": "sge"}.get(relation, relation)
                variants.append(base + [{"kind": "compare", "object_id": item["object_id"], "relation": relation, "value": number}])
    if not variants:
        variants = [base]
    if intent["trigger_role"] != "none" and not bool_added and len(base) < 3:
        bools = [
            item
            for item in ranked
            if item["chisel_type"]["kind"] == "Bool"
            and item["object_id"] != observation["object_id"]
        ]
        if (
            observation["chisel_type"]["kind"] == "Bool"
            and intent["temporal_kind"] in {"next_cycle", "previous_value"}
        ):
            bools = [observation]
        if bools:
            value = intent["trigger_role"] != "low"
            variants = [atoms + [{"kind": "bool", "object_id": item["object_id"], "value": value}] for atoms in variants for item in bools[:2]]
    return [atoms[:3] for atoms in variants[:2]]


def _expected_variants(
    intent: Mapping[str, Any],
    observation: Mapping[str, Any],
    ranked: list[Mapping[str, Any]],
    text: str,
    full_text: str,
) -> list[Dict[str, Any]]:
    source = intent["expected_role"]
    if source in {"zero", "one"}:
        return [{"kind": "literal", "value": int(source == "one")}]
    if source in {"same_observation", "incremented_observation"}:
        return [{"kind": source}]
    width = observation["chisel_type"]["width"]
    literals = sorted(
        (
            match.start(),
            {"kind": "literal", "value": value},
        )
        for name, value, width_hint in _named_constants(full_text)
        if width_hint == width
        and (match := re.search(rf"\b{name}\b", text)) is not None
    )
    if literals:
        return [literal for _position, literal in literals[:2]]
    same_type = [
        item for item in ranked
        if item["object_id"] != observation["object_id"]
        and item["chisel_type"] == observation["chisel_type"]
    ]
    return [{"kind": "object", "object_id": item["object_id"]} for item in same_type[:2]]


def _lookup_formula(
    intent: Mapping[str, Any],
    text: str,
    full_text: str,
    ranked: list[Mapping[str, Any]],
) -> Dict[str, Any] | None:
    if intent["temporal_kind"] not in {"next_cycle", "previous_value"}:
        return None
    state = next((item for item in ranked if "state" in _name_terms(item) and item["chisel_type"]["kind"] != "Bool"), None)
    selectors = [item for item in ranked if item["chisel_type"]["kind"] == "Bool" and "input" in _name_terms(item)][:2]
    rows = []
    for line in full_text.splitlines():
        match = re.match(r"\|\s*`?S([0-9]+)`?\s*\|(.+)\|", line)
        if match:
            values = [int(value) for value in re.findall(r"`?S([0-9]+)`?", match.group(2))]
            if len(values) == 4:
                rows.extend(values)
    if state is None or len(selectors) != 2 or len(rows) != 64:
        return None
    selectors = sorted(selectors, key=lambda item: str(item.get("name", "")))
    object_ids = [state["object_id"], *(item["object_id"] for item in selectors)]
    return {
        "object_ids": object_ids,
        "observation_id": state["object_id"],
        "guard_atoms": [{"kind": "reset", "value": False}],
        "expected": {"kind": "lookup_table", "selector_object_ids": object_ids, "values": rows},
        "relation": intent["relation"],
        "temporal_kind": intent["temporal_kind"],
    }


def _named_constants(text: str) -> list[tuple[str, int, int | None]]:
    return [
        (
            name,
            int(raw, 2 if len(raw) > 1 and set(raw) <= {"0", "1"} else 10),
            len(raw) if len(raw) > 1 and set(raw) <= {"0", "1"} else None,
        )
        for name, raw in re.findall(r"\b([A-Z][A-Z0-9_]*)=([0-9]+)\b", text)
    ]


def _validate_instances(
    rows: list[Mapping[str, Any]],
    candidates: list[Mapping[str, Any]],
) -> list[Dict[str, Any]]:
    by_id = {row["candidate_id"]: row for row in candidates}
    refs = {row["obligation_ref"] for row in candidates}
    if len(rows) != len(refs):
        raise AuthoringError("incomplete_instantiation_set", "one instance per obligation")
    normalized = []
    seen = set()
    for row in rows:
        if set(row) != {"candidate_id"}:
            raise AuthoringError("invalid_instantiation", "instance fields differ")
        candidate = by_id.get(row.get("candidate_id"))
        if candidate is None:
            raise AuthoringError("invalid_instantiation", str(row.get("candidate_id")))
        ref = candidate["obligation_ref"]
        if ref in seen:
            raise AuthoringError("invalid_instantiation", f"duplicate {ref}")
        seen.add(ref)
        normalized.append(dict(row))
    if seen != refs:
        raise AuthoringError("incomplete_instantiation_set", str(sorted(refs - seen)))
    return normalized


def _raw_literal(value: Any, value_type: Mapping[str, Any]) -> Dict[str, Any]:
    return {"op": "literal", "value": value, "type": dict(value_type)}


def _raw_object(object_id: str) -> Dict[str, Any]:
    return {"op": "object_ref", "object_id": object_id}


def _raw_compare(
    relation: str, lhs: Mapping[str, Any], rhs: Mapping[str, Any]
) -> Dict[str, Any]:
    return {"op": relation, "lhs": dict(lhs), "rhs": dict(rhs)}


def _materialize_two_stage_candidates(
    *,
    indexed: list[Mapping[str, Any]],
    grammar_candidates: list[Mapping[str, Any]],
    instances: list[Mapping[str, Any]],
    primary_ids: list[str],
    scope: Mapping[str, Any],
    clauses: list[Mapping[str, Any]],
    public_spec: Mapping[str, Any],
    configuration: Mapping[str, Any],
    semantic: Mapping[str, Any],
    assets: AssetLibrary,
    adapter_id: str,
) -> tuple[list[Dict[str, Any]], list[Dict[str, Any]], list[Dict[str, Any]]]:
    candidate_by_id = {row["candidate_id"]: row for row in grammar_candidates}
    selected = [candidate_by_id[row["candidate_id"]] for row in instances]
    selected_by_ref = {row["obligation_ref"]: row for row in selected}
    clause_by_id = {row["locator"]: row for row in clauses}
    object_rows = {row["object_id"]: row for row in semantic["objects"]}
    object_types = {
        object_id: {
            "kind": row["chisel_type"]["kind"],
            "width": row["chisel_type"]["width"],
            "signed": row["chisel_type"]["signed"],
        }
        for object_id, row in object_rows.items()
        if row.get("fact_status") == "elaboration_confirmed"
    }
    if len(primary_ids) != 1:
        raise AuthoringError("specification_or_environment_incomplete", "R requires one complete primary component group")
    if set(selected_by_ref) != {row["obligation_ref"] for row in indexed}:
        raise AuthoringError("incomplete_instantiation_set", "required clause candidate is missing")
    reference_rows = [row for row in selected if row["formula_kind"] == "reference_relation"]
    if reference_rows:
        if len(reference_rows) != len(selected):
            raise AuthoringError("invalid_instantiation", "reference and scalar formulas cannot be mixed")
        return _materialize_reference_relation(
            selected=reference_rows,
            indexed=indexed,
            primary_ids=primary_ids,
            scope=scope,
            clauses=clauses,
            public_spec=public_spec,
            configuration=configuration,
            semantic=semantic,
            assets=assets,
            adapter_id=adapter_id,
        )
    assertions = [
        formula
        for row in selected
        for formula in (
            row["formulas"]
            if row["formula_kind"] == "conjunction"
            else [row]
        )
        if formula["formula_kind"] == "assertion"
    ]
    if not assertions:
        raise AuthoringError("specification_or_environment_incomplete", "scope has no executable assertion")
    bool_type = {"kind": "Bool", "width": 1, "signed": False}
    primary_id = primary_ids[0]
    object_ids = sorted({object_id for row in assertions for object_id in row["object_ids"]})
    bindings = materialize_generic_bindings(
        [{"role": f"entity_{index:02d}", "object_id": object_id} for index, object_id in enumerate(object_ids, 1)],
        semantic,
        obligation_id=primary_id,
        configuration_id=configuration["configuration_id"],
        adapter_id=adapter_id,
    )
    previous_ids = sorted({
        object_id
        for row in assertions
        if row["temporal_kind"] in {"next_cycle", "previous_value", "bounded_response"}
        for object_id in row["object_ids"]
    })
    uses_previous_reset = any(
        row["temporal_kind"] in {"next_cycle", "previous_value", "bounded_response"}
        and any(atom["kind"] == "reset" for atom in row["guard_atoms"])
        for row in assertions
    )
    uses_previous = bool(previous_ids) or uses_previous_reset
    valid_id = f"{primary_id}.past_valid"
    state: list[Dict[str, Any]] = []
    if uses_previous:
        state.append({
            "state_id": valid_id,
            "type": bool_type,
            "init": _raw_literal(False, bool_type),
            "update": _raw_literal(True, bool_type),
            "clear": _raw_literal(False, bool_type),
        })
    previous_state_ids = {}
    if uses_previous_reset:
        reset_state_id = f"{primary_id}.previous_reset"
        previous_state_ids["__formal_reset__"] = reset_state_id
        state.append({
            "state_id": reset_state_id,
            "type": bool_type,
            "init": _raw_literal(False, bool_type),
            "update": _raw_object("__formal_reset__"),
            "clear": _raw_literal(False, bool_type),
        })
    for index, object_id in enumerate(previous_ids, 1):
        state_id = f"{primary_id}.previous_{index:02d}"
        previous_state_ids[object_id] = state_id
        value_type = object_types[object_id]
        state.append({
            "state_id": state_id,
            "type": value_type,
            "init": _raw_literal(False if value_type["kind"] == "Bool" else 0, value_type),
            "update": _raw_object(object_id),
            "clear": _raw_literal(False, bool_type),
        })
    formula_rows = [
        _materialize_complete_formula(row, object_types, previous_state_ids, bool_type)
        for row in assertions
    ]
    assertion_expression = _combine("and", [row["assertion"] for row in formula_rows], bool_type)
    activation_expression = _combine("or", [row["activation"] for row in formula_rows], bool_type)
    observer_expression = _combine(
        "or",
        [
            _combine("and", [row["activation"], row["relation"]], bool_type)
            for row in formula_rows
        ],
        bool_type,
    )
    monitor_guard = {"op": "past_valid", "state_id": valid_id} if uses_previous else _raw_literal(True, bool_type)
    properties = []
    for component_id in scope["component_ids"]:
        role = scope["component_role_hints"][component_id]
        expression = assertion_expression
        if role == "activation_cover":
            expression = activation_expression
        elif role == "observer_cover":
            expression = observer_expression
        elif role == "state_cover":
            expression = monitor_guard
        elif role == "assumption_sat":
            expression = activation_expression
        properties.append({
            "source_property_id": component_id,
            "role": role,
            "expression_ir": expression,
            "guard_ir": monitor_guard,
        })
    archetype_id = "previous_value" if uses_previous else assertions[0]["archetype_id"]
    first_intent = indexed[0]
    first_clause = clause_by_id[first_intent["clause_locator"]]
    obligation = {
        "obligation_id": primary_id,
        "clause_ref": {
            "spec_sha256": public_spec["spec_sha256"],
            "locator": first_clause["locator"],
            "text_sha256": first_clause["text_sha256"],
        },
        "family": first_intent["family"],
        "polarity": "guarantee",
        "entities": object_ids,
        "trigger": _without_history(activation_expression, previous_state_ids, bool_type),
        "guard": _raw_literal(True, bool_type),
        "expected": _without_history(assertion_expression, previous_state_ids, bool_type),
        "temporal": {"kind": "next_cycle" if uses_previous else "same_cycle", "min_cycles": int(uses_previous), "max_cycles": int(uses_previous)},
        "reset_semantics": "explicit declared reset atoms",
        "observation_roles": [f"clause:{row['clause_locator']}" for row in selected],
        "configuration_domain": [configuration["configuration_id"]],
        "support_status": "candidate",
        "authoring_provenance": {"kind": "model_call", "ref": first_intent["obligation_ref"]},
    }
    monitor = {
        "monitor_id": f"{primary_id}.monitor",
        "obligation_id": primary_id,
        "archetype_id": archetype_id,
        "archetype_sha256": assets.monitor_archetypes[archetype_id]["sha256"],
        "binding_refs": [row["binding_id"] for row in bindings],
        "state": state,
        "properties": properties,
        "reset_policy": "explicit_reset",
        "overlay": {"strategy": "wrapper", "wrapper_top": "SpecFlowOverlay", "host_scope": "SpecFlowOverlay"},
        "required_observations": [row["binding_id"] for row in bindings],
        "configuration_domain": [configuration["configuration_id"]],
    }
    obligations = _validate_obligations([obligation], object_types, clauses, public_spec["spec_sha256"], configuration["configuration_id"], {primary_id}, True)
    checked_bindings = _validate_bindings(bindings, semantic, {primary_id}, configuration["configuration_id"], {adapter_id})
    monitors = _validate_monitors([monitor], object_types, {primary_id}, {row["binding_id"] for row in bindings}, assets, configuration["configuration_id"], set(scope["component_ids"]), scope["component_role_hints"], True)
    return obligations, checked_bindings, monitors


def _materialize_reference_relation(
    *,
    selected: list[Mapping[str, Any]],
    indexed: list[Mapping[str, Any]],
    primary_ids: list[str],
    scope: Mapping[str, Any],
    clauses: list[Mapping[str, Any]],
    public_spec: Mapping[str, Any],
    configuration: Mapping[str, Any],
    semantic: Mapping[str, Any],
    assets: AssetLibrary,
    adapter_id: str,
) -> tuple[list[Dict[str, Any]], list[Dict[str, Any]], list[Dict[str, Any]]]:
    relation_ids = {row["reference_relation_id"] for row in selected}
    if len(primary_ids) != 1 or len(relation_ids) != 1:
        raise AuthoringError(
            "specification_or_environment_incomplete",
            "one complete primary reference relation is required",
        )
    primary_id = primary_ids[0]
    relation_id = next(iter(relation_ids))
    relation = assets.reference_relations[relation_id]
    if (
        relation["source_property_id"] != primary_id
        or set(relation["component_ids"]) != set(scope["component_ids"])
    ):
        raise AuthoringError("reference_relation_mismatch", relation_id)
    object_ids = selected[0]["object_ids"]
    if any(row["object_ids"] != object_ids for row in selected):
        raise AuthoringError("reference_relation_mismatch", "object bindings differ")
    object_rows = {row["object_id"]: row for row in semantic["objects"]}
    object_types = {
        object_id: {
            "kind": row["chisel_type"]["kind"],
            "width": row["chisel_type"]["width"],
            "signed": row["chisel_type"]["signed"],
        }
        for object_id, row in object_rows.items()
        if row.get("fact_status") == "elaboration_confirmed"
    }
    bindings = materialize_generic_bindings(
        [
            {"role": name, "object_id": object_id}
            for name, object_id in zip(relation["required_objects"], object_ids)
        ],
        semantic,
        obligation_id=primary_id,
        configuration_id=configuration["configuration_id"],
        adapter_id=adapter_id,
    )
    bool_type = {"kind": "Bool", "width": 1, "signed": False}
    literal_true = _raw_literal(True, bool_type)
    first_intent = indexed[0]
    first_clause = {row["locator"]: row for row in clauses}[
        first_intent["clause_locator"]
    ]
    obligation = {
        "obligation_id": primary_id,
        "clause_ref": {
            "spec_sha256": public_spec["spec_sha256"],
            "locator": first_clause["locator"],
            "text_sha256": first_clause["text_sha256"],
        },
        "family": "algorithmic_reference",
        "polarity": "guarantee",
        "entities": object_ids,
        "trigger": literal_true,
        "guard": literal_true,
        "expected": literal_true,
        "temporal": {"kind": "reference_relation", "min_cycles": 0, "max_cycles": 0},
        "reset_semantics": "implemented by the reviewed reference relation",
        "observation_roles": [f"clause:{row['clause_locator']}" for row in selected],
        "configuration_domain": [configuration["configuration_id"]],
        "support_status": "candidate",
        "authoring_provenance": {"kind": "reused_asset", "ref": relation_id},
    }
    monitor = {
        "monitor_id": f"{primary_id}.monitor",
        "obligation_id": primary_id,
        "archetype_id": "algorithmic_reference",
        "archetype_sha256": assets.monitor_archetypes["algorithmic_reference"]["sha256"],
        "binding_refs": [row["binding_id"] for row in bindings],
        "state": [],
        "properties": [
            {
                "source_property_id": component_id,
                "role": scope["component_role_hints"][component_id],
                "expression_ir": literal_true,
                "guard_ir": literal_true,
            }
            for component_id in scope["component_ids"]
        ],
        "reset_policy": "explicit_reset",
        "overlay": {
            "strategy": "wrapper",
            "wrapper_top": "SpecFlowOverlay",
            "host_scope": "SpecFlowOverlay",
        },
        "required_observations": [row["binding_id"] for row in bindings],
        "configuration_domain": [configuration["configuration_id"]],
    }
    obligations = _validate_obligations(
        [obligation], object_types, clauses, public_spec["spec_sha256"],
        configuration["configuration_id"], {primary_id}, True,
    )
    checked_bindings = _validate_bindings(
        bindings, semantic, {primary_id}, configuration["configuration_id"], {adapter_id}
    )
    monitors = _validate_monitors(
        [monitor], object_types, {primary_id},
        {row["binding_id"] for row in bindings}, assets,
        configuration["configuration_id"], set(scope["component_ids"]),
        scope["component_role_hints"], True,
    )
    return obligations, checked_bindings, monitors


def _materialize_complete_formula(
    candidate: Mapping[str, Any],
    object_types: Mapping[str, Mapping[str, Any]],
    previous_state_ids: Mapping[str, str],
    bool_type: Mapping[str, Any],
) -> Dict[str, Any]:
    previous = candidate["temporal_kind"] in {"next_cycle", "previous_value", "bounded_response"}

    def value(object_id: str) -> Dict[str, Any]:
        if previous:
            return {"op": "previous_value", "state_id": previous_state_ids[object_id]}
        return _raw_object(object_id)

    atoms = []
    for atom in candidate["guard_atoms"]:
        if atom["kind"] == "reset":
            reset = value("__formal_reset__") if previous else _raw_object("__formal_reset__")
            atoms.append(reset if atom["value"] else {"op": "not", "arg": reset})
        elif atom["kind"] == "bool":
            item = value(atom["object_id"])
            atoms.append(item if atom["value"] else {"op": "not", "arg": item})
        else:
            item_type = object_types[atom["object_id"]]
            atoms.append(_raw_compare(atom["relation"], value(atom["object_id"]), _raw_literal(atom["value"], item_type)))
    activation = _combine("and", atoms, bool_type)
    observation_id = candidate["observation_id"]
    observation_type = object_types[observation_id]
    expected = candidate["expected"]
    if expected["kind"] == "literal":
        literal: Any = expected["value"]
        if observation_type["kind"] == "Bool":
            literal = bool(literal)
        rhs = _raw_literal(literal, observation_type)
    elif expected["kind"] == "same_observation":
        rhs = value(observation_id)
    elif expected["kind"] == "incremented_observation":
        rhs = {"op": "add", "lhs": value(observation_id), "rhs": _raw_literal(1, observation_type)}
    elif expected["kind"] == "object":
        rhs = value(expected["object_id"])
    else:
        rhs = {
            "op": "lookup_table",
            "selectors": [value(object_id) for object_id in expected["selector_object_ids"]],
            "values": expected["values"],
            "type": observation_type,
        }
    relation = _raw_compare(candidate["relation"], _raw_object(observation_id), rhs)
    assertion = relation if not atoms else {"op": "or", "args": [{"op": "not", "arg": activation}, relation]}
    return {"assertion": assertion, "activation": activation, "relation": relation}


def _combine(op: str, rows: list[Mapping[str, Any]], bool_type: Mapping[str, Any]) -> Dict[str, Any]:
    if not rows:
        return _raw_literal(op == "and", bool_type)
    if len(rows) == 1:
        return dict(rows[0])
    return {"op": op, "args": [dict(row) for row in rows]}


def _without_history(
    value: Any,
    previous_state_ids: Mapping[str, str],
    bool_type: Mapping[str, Any],
) -> Any:
    inverse = {state_id: object_id for object_id, state_id in previous_state_ids.items()}
    if isinstance(value, list):
        return [_without_history(item, previous_state_ids, bool_type) for item in value]
    if not isinstance(value, Mapping):
        return value
    if value.get("op") == "past_valid":
        return _raw_literal(True, bool_type)
    if value.get("op") == "previous_value":
        return _raw_object(inverse[value["state_id"]])
    return {
        key: _without_history(item, previous_state_ids, bool_type)
        for key, item in value.items()
    }


def _request_candidates(
    model: AuthoringModel,
    *,
    expected_tool: str,
    tools: list[Dict[str, Any]],
    context: Mapping[str, Any],
    validator: Callable[[list[Mapping[str, Any]]], list[Dict[str, Any]]],
    audit_log: Optional[list[Dict[str, Any]]] = None,
    candidate_attempt_log: Optional[list[Dict[str, Any]]] = None,
    max_tokens: int = 4096,
) -> tuple[list[Dict[str, Any]], int, list[str]]:
    response = model.chat_with_tools(
        messages=[
        {
            "role": "system",
            "content": (
                "You are a one-shot candidate generator. Use exactly the required named tool. "
                "Submit only IDs and typed IR from the supplied context. Never emit Scala, file edits, review, or approval."
            ),
        },
        {"role": "user", "content": json.dumps(context, sort_keys=True)},
        ],
        tools=[tool for tool in tools if tool["name"] == expected_tool],
        max_tokens=max_tokens,
        temperature=0.0,
        tool_choice={
            "type": "function",
            "function": {"name": expected_tool},
        },
        enable_thinking=False,
        parallel_tool_calls=False,
        usage_metadata={"stage": "asset_authoring", "task_type": "candidate_authoring"},
    )
    audit = {
        "schema_version": "specflow_model_call",
        "sequence": len(audit_log or []) + 1,
        "stage": "asset_authoring",
        "expected_tool": expected_tool,
        "parallel_tool_calls": False,
        "thinking_enabled": False,
        "response_type": response.get("type"),
        "finish_reason": response.get("finish_reason"),
    }
    if audit_log is not None:
        audit_log.append(audit)
    calls = response.get("function_calls") if response.get("type") == "function_calls" else None
    if not isinstance(calls, list) or len(calls) != 1:
        audit["outcome"] = "invalid_submission"
        raise AuthoringError("invalid_model_submission", "exactly one function call is required")
    call = calls[0]
    call_ref = str(call.get("id") or expected_tool)
    audit["call_id"] = call_ref
    audit["returned_tool"] = call.get("name")
    arguments = call.get("arguments")
    if call.get("name") == AMBIGUITY_TOOL_NAME:
        if not isinstance(arguments, Mapping) or set(arguments) != {"clause_ids", "reason"}:
            audit["outcome"] = "invalid_submission"
            raise AuthoringError("invalid_model_submission", "malformed ambiguity report")
        audit["outcome"] = "spec_ambiguity"
        raise AuthoringError("spec_ambiguity", str(arguments["reason"]))
    if call.get("name") != expected_tool:
        audit["outcome"] = "invalid_submission"
        raise AuthoringError("invalid_model_submission", f"unexpected tool {call.get('name')!r}")
    if not isinstance(arguments, Mapping) or set(arguments) != {"candidates"} or not isinstance(arguments["candidates"], list):
        audit["outcome"] = "invalid_submission"
        raise AuthoringError("invalid_model_submission", "tool arguments must contain only candidates[]")
    if _contains_forbidden_authoring_content(arguments):
        audit["outcome"] = "invalid_submission"
        raise AuthoringError("invalid_model_submission", "forbidden authoring content")
    audit["submitted_candidate_count"] = len(arguments["candidates"])
    audit["submitted_candidates_sha256"] = canonical_sha256(
        {"candidates": arguments["candidates"]}
    )
    if candidate_attempt_log is not None:
        candidate_attempt_log.append(
            {
                "schema_version": "specflow_candidate_submission",
                "expected_tool": expected_tool,
                "call_id": call_ref,
                "candidates_sha256": audit["submitted_candidates_sha256"],
                "candidates": arguments["candidates"],
            }
        )
    try:
        validated = validator(arguments["candidates"])
    except (ObligationValidationError, BindingValidationError, MonitorValidationError) as exc:
        audit["outcome"] = "invalid_submission"
        audit["error"] = str(exc)
        raise AuthoringError("invalid_model_submission", str(exc)) from exc
    audit["outcome"] = "accepted_candidates"
    audit["candidate_count"] = len(validated)
    return validated, 1, [call_ref]


def _validate_obligations(
    rows: list[Mapping[str, Any]],
    object_types: Mapping[str, Mapping[str, Any]],
    clauses: list[Mapping[str, Any]],
    spec_sha256: str,
    configuration_id: str,
    primary_component_ids: set[str],
    require_complete_primary_set: bool,
) -> list[Dict[str, Any]]:
    if not rows:
        raise AuthoringError("empty_candidates", "at least one obligation is required")
    by_locator = {row["locator"]: row for row in clauses}
    normalized = []
    ids = set()
    for row in rows:
        value = validate_obligation(row, object_types)
        identity = value["obligation_id"]
        if identity in ids:
            raise AuthoringError("duplicate_candidate_id", identity)
        ids.add(identity)
        if require_complete_primary_set and identity not in primary_component_ids:
            raise AuthoringError("unknown_primary_component", identity)
        clause = by_locator.get(value["clause_ref"]["locator"])
        if clause is None or value["clause_ref"]["spec_sha256"] != spec_sha256 or value["clause_ref"]["text_sha256"] != clause["text_sha256"]:
            raise AuthoringError("clause_hash_mismatch", identity)
        if value["configuration_domain"] != [configuration_id]:
            raise AuthoringError("configuration_not_applicable", identity)
        normalized.append(value)
    if require_complete_primary_set and ids != primary_component_ids:
        raise AuthoringError(
            "incomplete_primary_component_set",
            str(sorted(primary_component_ids - ids)),
        )
    return normalized


def _validate_bindings(
    rows: list[Mapping[str, Any]],
    semantic: Mapping[str, Any],
    obligation_ids: set[str],
    configuration_id: str,
    adapter_ids: set[str],
) -> list[Dict[str, Any]]:
    if not rows:
        raise AuthoringError("empty_candidates", "at least one binding is required")
    normalized = []
    ids = set()
    for row in rows:
        value = validate_binding(
            row, semantic, obligation_ids, configuration_id, adapter_ids
        )
        identity = value["binding_id"]
        if identity in ids:
            raise AuthoringError("duplicate_candidate_id", identity)
        ids.add(identity)
        normalized.append(value)
    return normalized


def _validate_monitors(
    rows: list[Mapping[str, Any]],
    object_types: Mapping[str, Mapping[str, Any]],
    obligation_ids: set[str],
    binding_ids: set[str],
    assets: AssetLibrary,
    configuration_id: str,
    allowed_property_ids: set[str],
    role_hints: Mapping[str, str],
    require_complete_property_set: bool,
) -> list[Dict[str, Any]]:
    if not rows:
        raise AuthoringError("empty_candidates", "at least one monitor is required")
    normalized = []
    ids = set()
    source_property_ids = set()
    for row in rows:
        value = validate_monitor(
            row,
            object_types=object_types,
            obligation_ids=obligation_ids,
            binding_ids=binding_ids,
            archetypes=assets.monitor_archetypes,
            configuration_id=configuration_id,
        )
        identity = value["monitor_id"]
        if identity in ids:
            raise AuthoringError("duplicate_candidate_id", identity)
        ids.add(identity)
        monitor_property_ids = {
            prop["source_property_id"] for prop in value["properties"]
        }
        for prop in value["properties"]:
            hinted = role_hints.get(prop["source_property_id"])
            if hinted is not None and prop["role"] != hinted:
                raise AuthoringError(
                    "component_role_mismatch",
                    f"{prop['source_property_id']} requires {hinted}",
                )
        if not monitor_property_ids <= allowed_property_ids:
            raise AuthoringError(
                "unknown_source_property_id",
                str(sorted(monitor_property_ids - allowed_property_ids)),
            )
        duplicate_properties = source_property_ids & monitor_property_ids
        if duplicate_properties:
            raise AuthoringError(
                "duplicate_source_property", str(sorted(duplicate_properties))
            )
        source_property_ids |= monitor_property_ids
        normalized.append(value)
    if require_complete_property_set and source_property_ids != allowed_property_ids:
        raise AuthoringError(
            "incomplete_component_set",
            str(sorted(allowed_property_ids - source_property_ids)),
        )
    return normalized


def _selected_monitor_archetype(role_hints: Mapping[str, str]) -> str:
    roles = set(role_hints.values())
    if "assumption_sat" in roles:
        return "bounded_counter"
    if "state_cover" in roles:
        return "previous_value"
    return "direct_relation"


def _build_review_request(
    stage_dir: Path,
    candidates: Mapping[str, Any],
    delta: Mapping[str, Any],
    round_id: int,
) -> Dict[str, Any]:
    artifacts = []
    for name in ("stage_inputs.json", "authoring_candidates.json", "candidate_asset_delta.json"):
        artifacts.append({"artifact": name, "sha256": file_sha256(stage_dir / name)})
    intent_ids = sorted(
        [row["obligation_id"] for row in candidates["obligations"]]
        + [row["binding_id"] for row in candidates["bindings"]]
        + [row["monitor_id"] for row in candidates["monitors"]]
    )
    return {
        "schema_version": REVIEW_REQUEST_SCHEMA,
        "round_id": round_id,
        "reviewed_hashes_required": artifacts,
        "semantic_intent_ids": intent_ids,
        "evidence_refs_required": True,
        "allowed_reviewers": ["codex", "human:<id>"],
        "candidate_delta_sha256": canonical_sha256(delta),
    }


def _clause_slices(path: Path, clause_ids: list[str]) -> list[Dict[str, str]]:
    lines = Path(path).read_text(encoding="utf-8").splitlines()
    rows = []
    for clause_id in clause_ids:
        matches = [index for index, line in enumerate(lines) if re.search(rf"\b{re.escape(clause_id)}\b", line)]
        if not matches:
            raise AuthoringError("missing_clause", clause_id)
        start = matches[0]
        text_lines = [lines[start].strip()]
        index = start + 1
        while index < len(lines) and lines[index].strip() and not lines[index].lstrip().startswith(("- **", "| `")):
            text_lines.append(lines[index].strip())
            index += 1
        text = " ".join(text_lines)
        rows.append(
            {
                "locator": clause_id,
                "text": text,
                "text_sha256": hashlib.sha256(text.encode("utf-8")).hexdigest(),
            }
        )
    return rows


def _contains_forbidden_authoring_content(value: Any) -> bool:
    forbidden_keys = {"scala", "raw_scala", "source_code", "approval", "approve", "reviewer", "file_path", "patch"}
    if isinstance(value, Mapping):
        if any(str(key).lower() in forbidden_keys for key in value):
            return True
        return any(_contains_forbidden_authoring_content(item) for item in value.values())
    if isinstance(value, list):
        return any(_contains_forbidden_authoring_content(item) for item in value)
    return False


def _set_review_state(workspace: SpecFlowWorkspace, review_state: str) -> None:
    manifest = _read_json(workspace.manifest_path)
    manifest["review_state"] = review_state
    _write_json(workspace.manifest_path, manifest)


def _read_json(path: Path) -> Dict[str, Any]:
    value = json.loads(Path(path).read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise AuthoringError("invalid_json_artifact", str(path))
    return value


def _write_json(path: Path, value: Mapping[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + ".tmp")
    temporary.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    temporary.replace(path)


def _write_jsonl(path: Path, rows: list[Mapping[str, Any]]) -> None:
    if path.exists():
        raise FileExistsError(f"model call log is immutable once written: {path}")
    path.write_text(
        "".join(json.dumps(row, sort_keys=True) + "\n" for row in rows),
        encoding="utf-8",
    )


def _model_log_path(workspace: SpecFlowWorkspace, round_id: int) -> Path:
    return workspace.logs_dir / "model_calls.jsonl"


def _candidate_attempt_log_path(
    workspace: SpecFlowWorkspace, round_id: int
) -> Path:
    return workspace.logs_dir / "candidate_submission.jsonl"
