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
        intents, _used, intent_refs = _request_candidates(
            model,
            expected_tool="extract_obligations",
            tools=extract_obligation_tools(
                [row["locator"] for row in clauses], count=len(primary_ids)
            ),
            context={
                "task": "select one typed obligation intent for each primary component",
                "stage_inputs": stage_inputs,
            },
            validator=lambda rows: _validate_extracted_intents(
                rows, clauses, len(primary_ids)
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
        instances, _used, instance_refs = _request_candidates(
            model,
            expected_tool="bind_and_instantiate",
            tools=bind_and_instantiate_tools(
                [row["obligation_ref"] for row in indexed],
                confirmed,
                assets.monitor_archetypes,
            ),
            context={
                "task": (
                    "select elaboration-confirmed objects and fill archetype slots; "
                    "use the literal role name none for an unused optional role"
                ),
                "obligations": indexed,
                "semantic_objects": confirmed,
                "component_role_hints": scope["component_role_hints"],
                "archetypes": [
                    dict(assets.monitor_archetypes[asset_id])
                    for asset_id in sorted(assets.monitor_archetypes)
                ],
            },
            validator=lambda rows: _validate_instances(
                rows, indexed, confirmed, assets.monitor_archetypes
            ),
            audit_log=audits,
            candidate_attempt_log=attempts,
            max_tokens=max_tokens[1],
        )
        refs.extend(instance_refs)
        obligations, bindings, monitors = _materialize_two_stage_candidates(
            indexed=indexed,
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
    clauses: list[Mapping[str, Any]],
    count: int,
) -> list[Dict[str, Any]]:
    allowed_fields = {
        "clause_locator",
        "family",
        "temporal_kind",
        "min_cycles",
        "max_cycles",
        "relation",
        "archetype_hint",
        "support_status",
    }
    locators = {row["locator"] for row in clauses}
    if len(rows) != count:
        raise AuthoringError("incomplete_obligation_set", f"expected {count} intents")
    normalized = []
    for row in rows:
        if set(row) != allowed_fields or row.get("clause_locator") not in locators:
            raise AuthoringError("invalid_obligation_intent", "intent fields or clause differ")
        if row.get("support_status") != "supported":
            raise AuthoringError("unsupported_obligation", str(row.get("support_status")))
        minimum, maximum = row.get("min_cycles"), row.get("max_cycles")
        if (
            not isinstance(minimum, int)
            or isinstance(minimum, bool)
            or not isinstance(maximum, int)
            or isinstance(maximum, bool)
            or minimum < 0
            or maximum < minimum
        ):
            raise AuthoringError("invalid_temporal_bound", str((minimum, maximum)))
        normalized.append(dict(row))
    return normalized


def _validate_instances(
    rows: list[Mapping[str, Any]],
    indexed: list[Mapping[str, Any]],
    semantic_objects: list[Mapping[str, Any]],
    archetypes: Mapping[str, Mapping[str, Any]],
) -> list[Dict[str, Any]]:
    refs = {row["obligation_ref"] for row in indexed}
    intents = {row["obligation_ref"]: row for row in indexed}
    objects = {row["object_id"] for row in semantic_objects}
    if len(rows) != len(refs):
        raise AuthoringError("incomplete_instantiation_set", "one instance per obligation")
    normalized = []
    seen = set()
    for row in rows:
        if set(row) != {"obligation_ref", "archetype_id", "bindings", "slots"}:
            raise AuthoringError("invalid_instantiation", "instance fields differ")
        ref = row.get("obligation_ref")
        if ref not in refs or ref in seen or row.get("archetype_id") not in archetypes:
            raise AuthoringError("invalid_instantiation", str(ref))
        if row["archetype_id"] != intents[ref]["archetype_hint"]:
            raise AuthoringError(
                "invalid_instantiation", "archetype differs from extracted intent"
            )
        bindings = row.get("bindings")
        if not isinstance(bindings, list) or not bindings:
            raise AuthoringError("invalid_instantiation", "bindings are empty")
        roles = set()
        for binding in bindings:
            if (
                not isinstance(binding, Mapping)
                or set(binding) != {"role", "object_id"}
                or not isinstance(binding["role"], str)
                or not binding["role"].strip()
                or binding["role"] in roles
                or binding["object_id"] not in objects
            ):
                raise AuthoringError("invalid_instantiation", "binding selection is invalid")
            roles.add(binding["role"])
        slots = row.get("slots")
        expected_slots = {
            "lhs_role",
            "rhs_role",
            "guard_role",
            "trigger_role",
            "response_role",
            "use_expected_literal",
            "expected_literal",
            "use_lookup_table",
            "selector_roles",
            "lookup_values",
            "bound",
        }
        if not isinstance(slots, Mapping) or set(slots) != expected_slots:
            raise AuthoringError("invalid_instantiation", "slot fields differ")
        required_roles = {slots["lhs_role"]}
        if not slots["use_expected_literal"] and not slots["use_lookup_table"]:
            required_roles.add(slots["rhs_role"])
        if slots["use_lookup_table"]:
            required_roles.update(slots["selector_roles"])
            if len(slots["lookup_values"]) < 2:
                raise AuthoringError("invalid_instantiation", "lookup table is incomplete")
        for name in ("guard_role", "trigger_role", "response_role"):
            if slots[name] != "none":
                required_roles.add(slots[name])
        if not required_roles <= roles:
            raise AuthoringError(
                "invalid_instantiation", str(sorted(required_roles - roles))
            )
        seen.add(ref)
        normalized.append(dict(row))
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
    by_ref = {row["obligation_ref"]: row for row in instances}
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
    obligations: list[Dict[str, Any]] = []
    all_bindings: list[Dict[str, Any]] = []
    monitors: list[Dict[str, Any]] = []
    bool_type = {"kind": "Bool", "width": 1, "signed": False}
    for primary_id, intent in zip(primary_ids, indexed):
        instance = by_ref[intent["obligation_ref"]]
        archetype_id = instance["archetype_id"]
        expected_archetype = _selected_monitor_archetype(scope["component_role_hints"])
        if archetype_id != expected_archetype:
            raise AuthoringError(
                "archetype_role_mismatch",
                f"{archetype_id} cannot provide {sorted(scope['component_role_hints'].values())}",
            )
        bindings = materialize_generic_bindings(
            instance["bindings"],
            semantic,
            obligation_id=primary_id,
            configuration_id=configuration["configuration_id"],
            adapter_id=adapter_id,
        )
        role_objects = {
            row["semantic_role"]: row["object_id"] for row in bindings
        }
        slots = instance["slots"]
        lhs_id = role_objects[slots["lhs_role"]]
        lhs = _raw_object(lhs_id)
        lhs_type = object_types[lhs_id]
        if slots["use_lookup_table"]:
            rhs = {
                "op": "lookup_table",
                "selectors": [
                    _raw_object(role_objects[role])
                    for role in slots["selector_roles"]
                ],
                "values": list(slots["lookup_values"]),
                "type": lhs_type,
            }
        elif slots["use_expected_literal"]:
            literal: Any = slots["expected_literal"]
            if lhs_type["kind"] == "Bool":
                if literal not in (0, 1):
                    raise AuthoringError("type_mismatch", "Bool literal must be 0 or 1")
                literal = bool(literal)
            rhs = _raw_literal(literal, lhs_type)
        else:
            rhs = _raw_object(role_objects[slots["rhs_role"]])
        guard = (
            _raw_literal(True, bool_type)
            if slots["guard_role"] == "none"
            else _raw_object(role_objects[slots["guard_role"]])
        )
        trigger = (
            guard
            if slots["trigger_role"] == "none"
            else _raw_object(role_objects[slots["trigger_role"]])
        )
        relation = _raw_compare(intent["relation"], lhs, rhs)
        clause = clause_by_id[intent["clause_locator"]]
        obligation = {
            "obligation_id": primary_id,
            "clause_ref": {
                "spec_sha256": public_spec["spec_sha256"],
                "locator": clause["locator"],
                "text_sha256": clause["text_sha256"],
            },
            "family": intent["family"],
            "polarity": "guarantee",
            "entities": sorted(set(role_objects.values())),
            "trigger": trigger,
            "guard": guard,
            "expected": relation,
            "temporal": {
                "kind": intent["temporal_kind"],
                "min_cycles": intent["min_cycles"],
                "max_cycles": intent["max_cycles"],
            },
            "reset_semantics": "disabled while reset",
            "observation_roles": sorted(role_objects),
            "configuration_domain": [configuration["configuration_id"]],
            "support_status": "candidate",
            "authoring_provenance": {
                "kind": "model_call",
                "ref": intent["obligation_ref"],
            },
        }
        state: list[Dict[str, Any]] = []
        property_expression = relation
        if archetype_id == "previous_value":
            if lhs_type != bool_type:
                raise AuthoringError("type_mismatch", "previous_value requires Bool lhs")
            state_id = f"{primary_id}.previous"
            state = [
                {
                    "state_id": state_id,
                    "type": lhs_type,
                    "init": _raw_literal(False, bool_type),
                    "update": lhs,
                    "clear": _raw_literal(False, bool_type),
                }
            ]
            property_expression = _raw_compare(
                intent["relation"], lhs, {"op": "previous_value", "state_id": state_id}
            )
        elif archetype_id in {"bounded_counter", "lifecycle"}:
            response = (
                relation
                if slots["response_role"] == "none"
                else _raw_object(role_objects[slots["response_role"]])
            )
            width = max(1, int(slots["bound"] + 1).bit_length())
            uint_type = {"kind": "UInt", "width": width, "signed": False}
            active_id = f"{primary_id}.active"
            age_id = f"{primary_id}.age"
            state = [
                {
                    "state_id": active_id,
                    "type": bool_type,
                    "init": _raw_literal(False, bool_type),
                    "update": trigger,
                    "clear": response,
                },
                {
                    "state_id": age_id,
                    "type": uint_type,
                    "init": _raw_literal(0, uint_type),
                    "update": {
                        "op": "add",
                        "lhs": {"op": "previous_value", "state_id": age_id},
                        "rhs": _raw_literal(1, uint_type),
                    },
                    "clear": response,
                },
            ]
            property_expression = {
                "op": "or",
                "args": [
                    response,
                    {
                        "op": "bounded_counter_relation",
                        "counter_state_id": age_id,
                        "relation": "le",
                        "bound": slots["bound"],
                    },
                ],
            }
        properties = []
        for component_id in scope["component_ids"]:
            role = scope["component_role_hints"][component_id]
            expression = property_expression
            if role == "activation_cover":
                expression = trigger
            elif role == "observer_cover":
                expression = relation
            elif role == "state_cover":
                expression = (
                    {"op": "previous_value", "state_id": state[0]["state_id"]}
                    if state
                    else relation
                )
            elif role == "assumption_sat":
                expression = guard
            properties.append(
                {
                    "source_property_id": component_id,
                    "role": role,
                    "expression_ir": expression,
                    "guard_ir": guard,
                }
            )
        monitor = {
            "monitor_id": f"{primary_id}.monitor",
            "obligation_id": primary_id,
            "archetype_id": archetype_id,
            "archetype_sha256": assets.monitor_archetypes[archetype_id]["sha256"],
            "binding_refs": [row["binding_id"] for row in bindings],
            "state": state,
            "properties": properties,
            "reset_policy": "disable_while_reset",
            "overlay": {
                "strategy": "wrapper",
                "wrapper_top": "SpecFlowOverlay",
                "host_scope": "SpecFlowOverlay",
            },
            "required_observations": [row["binding_id"] for row in bindings],
            "configuration_domain": [configuration["configuration_id"]],
        }
        obligations.extend(
            _validate_obligations(
                [obligation],
                object_types,
                clauses,
                public_spec["spec_sha256"],
                configuration["configuration_id"],
                {primary_id},
                True,
            )
        )
        all_bindings.extend(
            _validate_bindings(
                bindings,
                semantic,
                {primary_id},
                configuration["configuration_id"],
                {adapter_id},
            )
        )
        monitors.extend(
            _validate_monitors(
                [monitor],
                object_types,
                {primary_id},
                {row["binding_id"] for row in bindings},
                assets,
                configuration["configuration_id"],
                set(scope["component_ids"]),
                scope["component_role_hints"],
                True,
            )
        )
    return obligations, all_bindings, monitors


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
