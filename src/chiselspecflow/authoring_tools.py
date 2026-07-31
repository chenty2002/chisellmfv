"""The only model-callable Stage-1 tools: three typed candidate submissions."""

from __future__ import annotations

from typing import Any, Dict, Iterable, Mapping


AUTHORING_TOOL_NAMES = (
    "submit_obligation_candidates",
    "submit_binding_candidates",
    "submit_monitor_candidates",
)
AMBIGUITY_TOOL_NAME = "report_spec_ambiguity"


def obligation_tools(
    clause_ids: Iterable[str],
    object_ids: Iterable[str],
    configuration_id: str,
    obligation_ids: Iterable[str] | None = None,
) -> list[Dict[str, Any]]:
    obligation_ids = tuple(obligation_ids) if obligation_ids is not None else None
    expression = _expression_schema(tuple(object_ids))
    candidate = _strict_object(
        {
            "obligation_id": (
                _enum(obligation_ids) if obligation_ids is not None else _string()
            ),
            "clause_ref": _strict_object(
                {"spec_sha256": _sha256(), "locator": _enum(clause_ids), "text_sha256": _sha256()}
            ),
            "family": _enum(
                (
                    "combinational_mapping",
                    "reset_initialization",
                    "state_transition",
                    "stability",
                    "cardinality",
                    "bounded_response",
                )
            ),
            "polarity": _enum(("guarantee", "assumption")),
            "entities": {"type": "array", "minItems": 1, "items": _enum(object_ids)},
            "trigger": expression,
            "guard": expression,
            "expected": expression,
            "temporal": _strict_object(
                {
                    "kind": _enum(("same_cycle", "next_cycle", "bounded")),
                    "min_cycles": {"type": "integer", "minimum": 0},
                    "max_cycles": {"type": "integer", "minimum": 0},
                }
            ),
            "reset_semantics": _string(),
            "observation_roles": {"type": "array", "minItems": 1, "items": _string()},
            "configuration_domain": {"type": "array", "minItems": 1, "maxItems": 1, "items": {"type": "string", "const": configuration_id}},
            "support_status": _enum(("candidate", "unsupported", "ambiguous")),
            "authoring_provenance": _strict_object(
                {"kind": _enum(("model_call", "reused_asset")), "ref": _string()}
            ),
        }
    )
    tool = _tool("submit_obligation_candidates", candidate)
    if obligation_ids is not None:
        count = len(obligation_ids)
        candidates = tool["parameters"]["properties"]["candidates"]
        candidates["minItems"] = count
        candidates["maxItems"] = count
    return [tool, _ambiguity_tool(clause_ids)]


def binding_tools(
    obligation_ids: Iterable[str],
    semantic_objects: Iterable[Mapping[str, Any]],
    configuration_id: str,
    adapter_ids: Iterable[str],
) -> list[Dict[str, Any]]:
    object_ids = tuple(row["object_id"] for row in semantic_objects)
    owners = tuple(sorted({row["owner_module"] for row in semantic_objects}))
    clocks = tuple(
        sorted({row["clock_reset"]["clock_domain"] for row in semantic_objects})
    )
    resets = tuple(
        sorted({row["clock_reset"]["reset_domain"] for row in semantic_objects})
    )
    candidate = _strict_object(
        {
            "binding_id": _string(),
            "obligation_id": _enum(obligation_ids),
            "semantic_role": _string(),
            "object_id": _enum(object_ids),
            "instance_selector": {"type": "string", "const": "dut"},
            "configuration_domain": {"type": "array", "minItems": 1, "maxItems": 1, "items": {"type": "string", "const": configuration_id}},
            "compatibility": _strict_object(
                {
                    "type": _enum(("Bool", "UInt", "SInt")),
                    "width": {"type": "integer", "minimum": 1},
                    "ownership": _enum(owners),
                    "clock": _enum(clocks),
                    "reset": _enum(resets),
                    "configuration": {"type": "string", "const": configuration_id},
                }
            ),
            "acquisition": _strict_object(
                {
                    "strategy": {"type": "string", "const": "wrapper"},
                    "host_scope": {"type": "string", "const": "SpecFlowOverlay"},
                    "adapter_id": _enum(adapter_ids),
                }
            ),
            "rationale": _string(),
            "rejected_alternatives": {"type": "array", "items": _string()},
            "review_state": {"type": "string", "const": "candidate"},
        }
    )
    return [_tool("submit_binding_candidates", candidate), _ambiguity_tool(obligation_ids)]


def monitor_tools(
    obligation_ids: Iterable[str],
    binding_ids: Iterable[str],
    object_ids: Iterable[str],
    configuration_id: str,
    archetypes: Mapping[str, Mapping[str, Any]],
    source_property_ids: Iterable[str] | None = None,
    role_hints: Mapping[str, str] | None = None,
    archetype_id: str | None = None,
) -> list[Dict[str, Any]]:
    expression = _expression_schema(tuple(object_ids), allow_state=True)
    state = _strict_object(
        {
            "state_id": _string(),
            "type": _type_schema(),
            "init": expression,
            "update": expression,
            "clear": expression,
        }
    )
    prop = _strict_object(
        {
            "source_property_id": (
                _enum(source_property_ids) if source_property_ids is not None else _string()
            ),
            "role": _enum(("primary_assertion", "activation_cover", "observer_cover", "state_cover", "assumption_sat")),
            "expression_ir": expression,
            "guard_ir": expression,
        }
    )
    variants = []
    selected_archetypes = (
        {archetype_id: archetypes[archetype_id]}
        if archetype_id is not None
        else archetypes
    )
    for selected_id, archetype in sorted(selected_archetypes.items()):
        contract = archetype["state_contract"]
        state_rows = {
            "type": "array",
            "minItems": contract["minimum_count"],
            "maxItems": contract["maximum_count"],
            "items": state,
        }
        if contract["required_type_kinds"]:
            state_rows["allOf"] = [
                {
                    "contains": _strict_object(
                        {
                            "state_id": _string(),
                            "type": _strict_object(
                                {
                                    "kind": {"type": "string", "const": kind},
                                    "width": {"type": "integer", "minimum": 1},
                                    "signed": {"type": "boolean"},
                                }
                            ),
                            "init": expression,
                            "update": expression,
                            "clear": expression,
                        }
                    ),
                    "minContains": 1,
                }
                for kind in contract["required_type_kinds"]
            ]
        properties = {"type": "array", "minItems": 1, "items": prop}
        if source_property_ids is not None:
            component_ids = tuple(source_property_ids)
            properties["minItems"] = len(component_ids)
            properties["maxItems"] = len(component_ids)
            properties["allOf"] = [
                {
                    "contains": _strict_object(
                        {
                            "source_property_id": {
                                "type": "string",
                                "const": component_id,
                            },
                            "role": (
                                {
                                    "type": "string",
                                    "const": role_hints[component_id],
                                }
                                if role_hints is not None
                                else _enum(
                                    (
                                        "primary_assertion",
                                        "activation_cover",
                                        "observer_cover",
                                        "state_cover",
                                        "assumption_sat",
                                    )
                                )
                            ),
                            "expression_ir": expression,
                            "guard_ir": expression,
                        }
                    ),
                    "minContains": 1,
                    "maxContains": 1,
                }
                for component_id in component_ids
            ]
        variants.append(
            _strict_object(
                {
                    "monitor_id": _string(),
                    "obligation_id": _enum(obligation_ids),
                    "archetype_id": {"type": "string", "const": selected_id},
                    "archetype_sha256": {
                        "type": "string",
                        "const": archetype["sha256"],
                    },
                    "binding_refs": {"type": "array", "minItems": 1, "items": _enum(binding_ids)},
                    "state": state_rows,
                    "properties": properties,
                    "reset_policy": {"type": "string", "const": "disable_while_reset"},
                    "overlay": _strict_object(
                        {
                            "strategy": {"type": "string", "const": "wrapper"},
                            "wrapper_top": {"type": "string", "const": "SpecFlowOverlay"},
                            "host_scope": {"type": "string", "const": "SpecFlowOverlay"},
                        }
                    ),
                    "required_observations": {"type": "array", "minItems": 1, "items": _enum(binding_ids)},
                    "configuration_domain": {"type": "array", "minItems": 1, "maxItems": 1, "items": {"type": "string", "const": configuration_id}},
                }
            )
        )
    candidate = variants[0] if len(variants) == 1 else {"anyOf": variants}
    tool = _tool("submit_monitor_candidates", candidate)
    candidates = tool["parameters"]["properties"]["candidates"]
    candidates["minItems"] = 1
    candidates["maxItems"] = 1
    return [tool, _ambiguity_tool(obligation_ids)]


def _tool(name: str, candidate: Mapping[str, Any]) -> Dict[str, Any]:
    definitions: list[Mapping[str, Any]] = []
    candidate = _hoist_expression_definitions(candidate, definitions)
    if definitions and any(item != definitions[0] for item in definitions[1:]):
        raise ValueError("one authoring tool cannot mix incompatible expression domains")
    parameters = _strict_object(
        {"candidates": {"type": "array", "minItems": 1, "items": dict(candidate)}}
    )
    if definitions:
        parameters["$defs"] = {"expression": dict(definitions[0])}
    return {
        "name": name,
        "description": "Submit only typed run-local candidates. Raw Scala, approval, and file writes are forbidden.",
        "strict": True,
        "parameters": parameters,
    }


def _ambiguity_tool(ids: Iterable[str]) -> Dict[str, Any]:
    return {
        "name": AMBIGUITY_TOOL_NAME,
        "description": "Stop authoring when the public specification is ambiguous or unsupported.",
        "strict": True,
        "parameters": _strict_object(
            {
                "clause_ids": {"type": "array", "minItems": 1, "items": _enum(ids)},
                "reason": _string(),
            }
        ),
    }


def _expression_schema(object_ids: tuple[str, ...], allow_state: bool = False) -> Dict[str, Any]:
    # JSON Schema recursion keeps the tool surface bounded without raw-code fields.
    ref = {"$ref": "#/$defs/expression"}
    variants = [
        _literal_schema(),
        _strict_object({"op": {"type": "string", "const": "object_ref"}, "object_id": _enum(object_ids)}),
        _strict_object({"op": {"type": "string", "const": "not"}, "arg": ref}),
        _strict_object({"op": _enum(("and", "or")), "args": {"type": "array", "minItems": 2, "items": ref}}),
        _strict_object({"op": _enum(("eq", "neq", "ult", "ule", "ugt", "uge", "slt", "sle", "sgt", "sge", "add", "sub")), "lhs": ref, "rhs": ref}),
        _strict_object({"op": {"type": "string", "const": "mux"}, "condition": ref, "when_true": ref, "when_false": ref}),
        _strict_object({"op": _enum(("onehot", "popcount")), "arg": ref}),
        _strict_object({"op": {"type": "string", "const": "bit_select"}, "arg": ref, "index": {"type": "integer", "minimum": 0}}),
        _strict_object({"op": {"type": "string", "const": "slice"}, "arg": ref, "high": {"type": "integer", "minimum": 0}, "low": {"type": "integer", "minimum": 0}}),
        _strict_object(
            {
                "op": {"type": "string", "const": "lookup_table"},
                "selectors": {
                    "type": "array",
                    "minItems": 1,
                    "maxItems": 8,
                    "items": ref,
                },
                "values": {
                    "type": "array",
                    "minItems": 2,
                    "maxItems": 256,
                    "items": {"type": "integer"},
                },
                "type": _type_schema(),
            }
        ),
    ]
    if allow_state:
        variants.extend(
            [
                _strict_object({"op": _enum(("past_valid", "previous_value")), "state_id": _string()}),
                _strict_object({"op": {"type": "string", "const": "bounded_counter_relation"}, "counter_state_id": _string(), "relation": _enum(("lt", "le", "eq", "ge", "gt")), "bound": {"type": "integer", "minimum": 0}}),
            ]
        )
    return {"$defs": {"expression": {"anyOf": variants}}, "$ref": "#/$defs/expression"}


def _hoist_expression_definitions(value: Any, definitions: list[Mapping[str, Any]]) -> Any:
    if isinstance(value, Mapping):
        if set(value) == {"$defs", "$ref"} and value["$ref"] == "#/$defs/expression":
            definitions.append(value["$defs"]["expression"])
            return {"$ref": "#/$defs/expression"}
        return {
            key: _hoist_expression_definitions(item, definitions)
            for key, item in value.items()
        }
    if isinstance(value, list):
        return [_hoist_expression_definitions(item, definitions) for item in value]
    return value


def _strict_object(properties: Mapping[str, Any]) -> Dict[str, Any]:
    return {
        "type": "object",
        "properties": dict(properties),
        "required": list(properties),
        "additionalProperties": False,
    }


def _type_schema() -> Dict[str, Any]:
    return _strict_object(
        {
            "kind": _enum(("Bool", "UInt", "SInt")),
            "width": {"type": "integer", "minimum": 1},
            "signed": {"type": "boolean"},
        }
    )


def _literal_schema() -> Dict[str, Any]:
    return {
        "anyOf": [
            _strict_object(
                {
                    "op": {"type": "string", "const": "literal"},
                    "value": {"type": "boolean"},
                    "type": _strict_object(
                        {
                            "kind": {"type": "string", "const": "Bool"},
                            "width": {"type": "integer", "const": 1},
                            "signed": {"type": "boolean", "const": False},
                        }
                    ),
                }
            ),
            _strict_object(
                {
                    "op": {"type": "string", "const": "literal"},
                    "value": {"type": "integer"},
                    "type": _strict_object(
                        {
                            "kind": _enum(("UInt", "SInt")),
                            "width": {"type": "integer", "minimum": 1},
                            "signed": {"type": "boolean"},
                        }
                    ),
                }
            ),
        ]
    }


def _string() -> Dict[str, Any]:
    return {"type": "string", "minLength": 1, "maxLength": 512}


def _sha256() -> Dict[str, Any]:
    return {"type": "string", "pattern": "^[0-9a-f]{64}$"}


def _enum(values: Iterable[str]) -> Dict[str, Any]:
    values = list(values)
    return {"type": "string", "enum": values}
