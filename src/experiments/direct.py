"""One-call direct property-package baseline for the paper experiment.

The baseline shares SpecFlow's deterministic typed IR validators and compiler,
but deliberately does not use the staged candidate authoring or external
review gate.  One model call must submit the complete package.
"""

from __future__ import annotations

import json
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Mapping

from src.chiselspecflow.assets import load_reviewed_assets, load_run_local_package
from src.chiselspecflow.authoring import (
    AuthoringError,
    _clause_slices,
    _validate_bindings,
    _validate_monitors,
    _validate_obligations,
)
from src.chiselspecflow.authoring_tools import (
    binding_tools,
    monitor_tools,
    obligation_tools,
)
from src.chiselspecflow.config import (
    AUTHORING_CANDIDATES_SCHEMA,
    CANDIDATE_ASSET_DELTA_SCHEMA,
    STAGE_INPUTS_SCHEMA,
    VERIFICATION_PACKAGE_SCHEMA,
)
from src.chiselspecflow.ir.semantic import validate_semantic_index
from src.chiselspecflow.property_decomposition import validate_property_decomposition
from src.chiselspecflow.stages import get_stage_spec
from src.core.artifact_contract import file_sha256, write_stage_outcome
from src.core.formal_operations import canonical_sha256


DIRECT_TOOL_NAME = "submit_direct_property_package"


def run_direct_one_shot(workspace: Any, model: Any, *, max_tokens: int) -> dict[str, Any]:
    """Submit and validate one complete direct package in exactly one call."""

    assets = load_reviewed_assets()
    manifest = _read_json(workspace.manifest_path)
    if manifest.get("preflight_status") != "index_ready":
        raise AuthoringError("preflight_incomplete", "semantic index is not frozen")
    stage_dir = workspace.stage_dir("asset_authoring")
    if any(stage_dir.iterdir()):
        raise FileExistsError("direct one-shot stage directory is immutable once written")

    semantic = validate_semantic_index(
        _read_json(workspace.indexes_dir / "chisel_semantic_index.json")
    )
    public_spec = _read_json(workspace.inputs_dir / "public_spec_package.json")
    decomposition = validate_property_decomposition(
        _read_json(workspace.inputs_dir / "property_decomposition.json"), public_spec
    )
    authoring_scope = _read_json(workspace.inputs_dir / "authoring_scope.json")
    project = _read_json(workspace.inputs_dir / "project_contract.json")
    configuration = _read_json(workspace.inputs_dir / "configuration.json")
    confirmed = [
        row for row in semantic["objects"]
        if row.get("fact_status") == "elaboration_confirmed"
    ]
    object_types = {
        row["object_id"]: {
            "kind": row["chisel_type"]["kind"],
            "width": row["chisel_type"]["width"],
            "signed": row["chisel_type"]["signed"],
        }
        for row in confirmed
    }
    clauses = _clause_slices(
        workspace.inputs_dir / "specification.md",
        authoring_scope["clause_ids"],
    )
    stage_inputs = {
        "schema_version": STAGE_INPUTS_SCHEMA,
        "round_id": 1,
        "method": "direct_one_shot",
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
            "full_text": (
                workspace.inputs_dir / "specification.md"
            ).read_text(encoding="utf-8"),
        },
        "authoring_scope": authoring_scope,
        "semantic_objects": confirmed,
        "asset_library": assets.snapshot(),
        "input_hashes": manifest["input_hashes"],
    }
    _write_json(stage_dir / "stage_inputs.json", stage_inputs)

    tool = _direct_tool(
        clauses=clauses,
        semantic_objects=confirmed,
        configuration_id=configuration["configuration_id"],
        adapter_ids=assets.api_adapters,
        archetypes=assets.monitor_archetypes,
        source_property_ids=authoring_scope["component_ids"],
        primary_component_ids=authoring_scope["primary_component_ids"],
        role_hints=authoring_scope["component_role_hints"],
    )
    prompt_path = Path(__file__).with_name("assets") / "direct_one_shot_prompt.md"
    prompt_text = prompt_path.read_text(encoding="utf-8")
    selected_archetype_id = next(
        row["parameters"]["properties"]["monitors"]["items"]["properties"][
            "archetype_id"
        ]["const"]
        for row in [tool]
    )
    asset_snapshot = stage_inputs["asset_library"]
    model_asset_library = {
        "schema_version": asset_snapshot["schema_version"],
        "obligation_schemas": asset_snapshot["obligation_schemas"],
        "api_adapters": asset_snapshot["api_adapters"],
        "monitor_archetypes": [
            row
            for row in asset_snapshot["monitor_archetypes"]
            if row["asset_id"] == selected_archetype_id
        ],
    }
    response = model.chat_with_tools(
        messages=[
            {
                "role": "system",
                "content": (
                    prompt_text
                    + "\nSubmit obligations, bindings, and monitors in the one required "
                    "typed package tool. There is no revision or reviewer. "
                    "An obligation must not reference an undeclared state_id. "
                    "Define every historical value in monitor state, or express it "
                    "as a next_cycle trigger/expected relation. Binding refs and "
                    "required observations must be exact binding_id values submitted "
                    "in this package. The implicit module reset is applied by the "
                    "monitor reset policy; do not invent or bind a reset object when "
                    "it is absent from semantic_objects. For a complete public lookup table, use one "
                    "lookup_table expression instead of nested mux duplication."
                ),
            },
            {
                "role": "user",
                "content": json.dumps(
                    {
                        key: stage_inputs[key]
                        for key in (
                            "schema_version",
                            "round_id",
                            "method",
                            "project",
                            "configuration",
                            "specification",
                            "authoring_scope",
                            "semantic_objects",
                            "input_hashes",
                        )
                    }
                    | {"asset_library": model_asset_library},
                    sort_keys=True,
                ),
            },
        ],
        tools=[tool],
        max_tokens=max_tokens,
        temperature=0.0,
        tool_choice={
            "type": "function",
            "function": {"name": DIRECT_TOOL_NAME},
        },
        enable_thinking=False,
        parallel_tool_calls=False,
        usage_metadata={"stage": "direct_one_shot", "task_type": "property_authoring"},
    )
    _write_json(
        stage_dir / "direct_model_response.json",
        {"response": response, "usage": model.get_token_usage()},
    )
    calls = response.get("function_calls") if response.get("type") == "function_calls" else None
    if not isinstance(calls, list) or len(calls) != 1:
        return _failed(stage_dir, manifest, "invalid_submission", "one tool call required")
    call = calls[0]
    arguments = call.get("arguments")
    if call.get("name") != DIRECT_TOOL_NAME or not isinstance(arguments, Mapping):
        return _failed(stage_dir, manifest, "invalid_submission", "wrong tool or arguments")
    if set(arguments) != {"obligations", "bindings", "monitors"}:
        return _failed(stage_dir, manifest, "invalid_submission", "package fields differ")

    try:
        obligations = _validate_obligations(
            arguments["obligations"],
            object_types,
            clauses,
            public_spec["spec_sha256"],
            configuration["configuration_id"],
            set(authoring_scope["primary_component_ids"]),
            authoring_scope["require_complete_primary_set"],
        )
        bindings = _validate_bindings(
            arguments["bindings"],
            semantic,
            {row["obligation_id"] for row in obligations},
            configuration["configuration_id"],
            set(assets.api_adapters),
        )
        monitors = _validate_monitors(
            arguments["monitors"],
            object_types,
            {row["obligation_id"] for row in obligations},
            {row["binding_id"] for row in bindings},
            assets,
            configuration["configuration_id"],
            set(authoring_scope["component_ids"]),
            authoring_scope["component_role_hints"],
            authoring_scope["require_complete_primary_set"],
        )
    except (AuthoringError, ValueError) as exc:
        return _failed(stage_dir, manifest, "invalid_submission", str(exc))

    candidates = {
        "schema_version": AUTHORING_CANDIDATES_SCHEMA,
        "status": "direct_submission",
        "obligations": obligations,
        "bindings": bindings,
        "monitors": monitors,
        "model_call_refs": [str(call.get("id") or DIRECT_TOOL_NAME)],
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
    direct_record = {
        "schema_version": "direct_one_shot_submission",
        "authority": "model_submission",
        "model_call_id": str(call.get("id") or DIRECT_TOOL_NAME),
        "submitted_at": datetime.now(timezone.utc).isoformat(),
        "candidate_sha256": file_sha256(stage_dir / "authoring_candidates.json"),
        "review_performed": False,
    }
    _write_json(stage_dir / "review_record.json", direct_record)
    package_body = {
        "schema_version": VERIFICATION_PACKAGE_SCHEMA,
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
            "reviewer": "none_direct_one_shot",
            "reviewed_at": direct_record["submitted_at"],
            "semantic_intent_decisions": [],
        },
    }
    package = dict(package_body)
    package["package_id"] = "p0pkg_" + canonical_sha256(package_body)[:24]
    _write_json(stage_dir / "verification_package.json", package)
    load_run_local_package(stage_dir / "verification_package.json")
    result = write_stage_outcome(
        stage_dir,
        get_stage_spec("asset_authoring"),
        {
            "success": True,
            "status": "completed",
            "method": "direct_one_shot",
            "model_calls": 1,
            "package_id": package["package_id"],
            "verification_package_sha256": file_sha256(
                stage_dir / "verification_package.json"
            ),
        },
        source_state=manifest,
    )
    manifest["review_state"] = "direct_submission"
    _write_json(workspace.manifest_path, manifest)
    return result


def _failed(stage_dir: Path, manifest: dict[str, Any], code: str, reason: str) -> dict[str, Any]:
    if not (stage_dir / "authoring_candidates.json").exists():
        _write_json(
            stage_dir / "authoring_candidates.json",
            {
                "schema_version": AUTHORING_CANDIDATES_SCHEMA,
                "status": "invalid",
                "obligations": [],
                "bindings": [],
                "monitors": [],
                "model_call_refs": [],
                "error": {"code": code, "reason": reason},
            },
        )
    return write_stage_outcome(
        stage_dir,
        get_stage_spec("asset_authoring"),
        {
            "success": False,
            "status": "invalid_submission",
            "error_kind": code,
            "error": reason,
            "method": "direct_one_shot",
            "model_calls": 1,
        },
        source_state=manifest,
    )


def _direct_tool(
    *,
    clauses: list[Mapping[str, Any]],
    semantic_objects: list[Mapping[str, Any]],
    configuration_id: str,
    adapter_ids: Any,
    archetypes: Mapping[str, Mapping[str, Any]],
    source_property_ids: list[str],
    primary_component_ids: list[str],
    role_hints: Mapping[str, str],
) -> dict[str, Any]:
    placeholder_obligation = "__submitted_obligation__"
    placeholder_binding = "__submitted_binding__"
    object_types = {
        row["object_id"]: row["chisel_type"] for row in semantic_objects
    }
    obligation_parameters = obligation_tools(
        [row["locator"] for row in clauses],
        object_types,
        configuration_id,
        primary_component_ids,
    )[0]["parameters"]
    binding_parameters = binding_tools(
        [placeholder_obligation],
        semantic_objects,
        configuration_id,
        adapter_ids,
    )[0]["parameters"]
    from src.chiselspecflow.authoring import _selected_monitor_archetype

    selected_archetype_id = _selected_monitor_archetype(role_hints)
    monitor_parameters = monitor_tools(
        [placeholder_obligation],
        [placeholder_binding],
        object_types,
        configuration_id,
        archetypes,
        source_property_ids,
        role_hints,
        selected_archetype_id,
    )[0]["parameters"]
    obligation_item = _rename_expression_ref(
        obligation_parameters["properties"]["candidates"]["items"],
        "obligation_expression",
    )
    binding_item = _replace_placeholder(
        binding_parameters["properties"]["candidates"]["items"],
        {placeholder_obligation},
    )
    # P0 has no review phase, so reviewer-facing binding prose only increases
    # malformed-output risk without contributing to the baseline result.
    binding_item["properties"]["rationale"] = {
        "type": "string",
        "const": "direct one-shot binding",
    }
    binding_item["properties"]["rejected_alternatives"] = {
        "type": "array",
        "maxItems": 0,
        "items": {"type": "string"},
    }
    monitor_item = _rename_expression_ref(
        _replace_placeholder(
            monitor_parameters["properties"]["candidates"]["items"],
            {placeholder_obligation, placeholder_binding},
        ),
        "monitor_expression",
    )
    parameters = {
        "type": "object",
        "properties": {
            "obligations": {
                "type": "array",
                "minItems": len(primary_component_ids),
                "maxItems": len(primary_component_ids),
                "items": obligation_item,
            },
            "bindings": {"type": "array", "minItems": 1, "items": binding_item},
            "monitors": {
                "type": "array",
                "minItems": 1,
                "maxItems": 1,
                "items": monitor_item,
            },
        },
        "required": ["obligations", "bindings", "monitors"],
        "additionalProperties": False,
        "$defs": {
            "obligation_expression": _rename_expression_ref(
                obligation_parameters["$defs"]["expression"],
                "obligation_expression",
            ),
            "monitor_expression": _rename_expression_ref(
                monitor_parameters["$defs"]["expression"],
                "monitor_expression",
            ),
        },
    }
    return {
        "name": DIRECT_TOOL_NAME,
        "description": "Submit the complete direct typed property package in one call.",
        "strict": True,
        "parameters": parameters,
    }


def _replace_placeholder(value: Any, placeholders: set[str]) -> Any:
    if isinstance(value, dict):
        enum = value.get("enum")
        if (
            value.get("type") == "string"
            and isinstance(enum, list)
            and enum
            and set(enum) <= placeholders
        ):
            return {"type": "string", "minLength": 1}
        return {key: _replace_placeholder(item, placeholders) for key, item in value.items()}
    if isinstance(value, list):
        return [_replace_placeholder(item, placeholders) for item in value]
    return value


def _rename_expression_ref(value: Any, name: str) -> Any:
    if isinstance(value, dict):
        if value == {"$ref": "#/$defs/expression"}:
            return {"$ref": f"#/$defs/{name}"}
        return {
            key: _rename_expression_ref(item, name)
            for key, item in value.items()
        }
    if isinstance(value, list):
        return [_rename_expression_ref(item, name) for item in value]
    return value


def _read_json(path: Path) -> dict[str, Any]:
    value = json.loads(Path(path).read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ValueError(f"JSON object required: {path}")
    return value


def _write_json(path: Path, value: Mapping[str, Any]) -> None:
    path.write_text(
        json.dumps(value, indent=2, ensure_ascii=False, sort_keys=True) + "\n",
        encoding="utf-8",
    )
