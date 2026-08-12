from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any

import pytest

from src.chiselspecflow.authoring import run_asset_authoring
from src.chiselspecflow.authoring_tools import monitor_tools, obligation_tools
from src.chiselspecflow.config import SpecFlowRunConfig
from src.chiselspecflow.ir.expression import ExpressionType, validate_expression
from src.chiselspecflow.monitor_compiler import ChiselExpr, lower_expression
from src.chiselspecflow.preflight import prepare_workspace
from src.chiselspecflow.property_decomposition import (
    PropertyDecompositionError,
    build_authoring_scope,
    load_property_decomposition,
)
from src.chiselspecflow.specification import load_public_spec_package
from src.core.llm_client import LLMClient
from src.experiments.direct import run_direct_one_shot
from src.experiments.paper import (
    DEVELOPMENT_FAMILIES,
    EVALUATION_FAMILIES,
    FAMILY_GROUPS,
    SELECTED_AUTHORING_CLAUSES,
    SELECTED_AUTHORING_SCOPE,
    ExperimentContractError,
    _assert_track_p_task_order,
    _experiment_config,
    _family_entry,
    _scheduled_track_p_tasks,
    _sha256,
    _write_development_decision,
    _terminal_authoring_state,
    prepare,
)


REPO = Path(__file__).resolve().parents[1]
LEDGER = REPO / "benchmark/synth/SPECIFICATIONS.sha256"


def _literal(value: bool) -> dict[str, Any]:
    return {
        "op": "literal",
        "value": value,
        "type": {"kind": "Bool", "width": 1, "signed": False},
    }


def _bool_expression(object_id: str) -> dict[str, Any]:
    return {"op": "object_ref", "object_id": object_id}


class FixedPackageModel:
    def __init__(self) -> None:
        self.stage_inputs: dict[str, Any] | None = None
        self.calls: list[dict[str, Any]] = []

    def chat_with_tools(self, **kwargs: Any) -> dict[str, Any]:
        self.calls.append(kwargs)
        assert kwargs["enable_thinking"] is False
        assert kwargs["parallel_tool_calls"] is False
        assert len(kwargs["tools"]) == 1
        tool_name = kwargs["tools"][0]["name"]
        assert kwargs["tool_choice"] == {
            "type": "function",
            "function": {"name": tool_name},
        }
        context = json.loads(kwargs["messages"][-1]["content"])
        if "stage_inputs" in context:
            self.stage_inputs = context["stage_inputs"]
        elif "schema_version" in context:
            self.stage_inputs = context
        assert self.stage_inputs is not None

        stage = self.stage_inputs
        objects = stage["semantic_objects"]
        selected = next(
            row for row in objects if row["chisel_type"]["kind"] == "Bool"
        )
        object_id = selected["object_id"]
        scope = stage["authoring_scope"]
        clause = stage["specification"]["clauses"][0]
        obligation_id = scope["primary_component_ids"][0]
        obligation = {
            "obligation_id": obligation_id,
            "clause_ref": {
                "spec_sha256": stage["specification"]["spec_sha256"],
                "locator": clause["locator"],
                "text_sha256": clause["text_sha256"],
            },
            "family": "stability",
            "polarity": "guarantee",
            "entities": [object_id],
            "trigger": _literal(True),
            "guard": _literal(True),
            "expected": _bool_expression(object_id),
            "temporal": {"kind": "same_cycle", "min_cycles": 0, "max_cycles": 0},
            "reset_semantics": "disabled while reset",
            "observation_roles": ["value"],
            "configuration_domain": [stage["configuration"]["configuration_id"]],
            "support_status": "candidate",
            "authoring_provenance": {"kind": "model_call", "ref": "fixed"},
        }
        binding = {
            "binding_id": "binding.fixed",
            "obligation_id": obligation_id,
            "semantic_role": "value",
            "object_id": object_id,
            "instance_selector": "dut",
            "configuration_domain": [stage["configuration"]["configuration_id"]],
            "compatibility": {
                "type": selected["chisel_type"]["kind"],
                "width": selected["chisel_type"]["width"],
                "ownership": selected["owner_module"],
                "clock": selected["clock_reset"]["clock_domain"],
                "reset": selected["clock_reset"]["reset_domain"],
                "configuration": stage["configuration"]["configuration_id"],
            },
            "acquisition": {
                "strategy": "wrapper",
                "host_scope": "SpecFlowOverlay",
                "adapter_id": stage["asset_library"]["api_adapters"][0]["asset_id"],
            },
            "rationale": "fixed schema-valid binding",
            "rejected_alternatives": [],
            "review_state": "candidate",
        }
        archetype_id = (
            "previous_value"
            if "state_cover" in scope["component_role_hints"].values()
            else "direct_relation"
        )
        archetype = next(
            row
            for row in stage["asset_library"]["monitor_archetypes"]
            if row["asset_id"] == archetype_id
        )
        state = (
            [
                {
                    "state_id": "past_valid",
                    "type": {"kind": "Bool", "width": 1, "signed": False},
                    "init": _literal(False),
                    "update": _bool_expression(object_id),
                    "clear": _literal(False),
                }
            ]
            if archetype_id == "previous_value"
            else []
        )
        monitor = {
            "monitor_id": "monitor.fixed",
            "obligation_id": obligation_id,
            "archetype_id": archetype_id,
            "archetype_sha256": archetype["sha256"],
            "binding_refs": ["binding.fixed"],
            "state": state,
            "properties": [
                {
                    "source_property_id": component,
                    "role": scope["component_role_hints"][component],
                    "expression_ir": _bool_expression(object_id),
                    "guard_ir": _literal(True),
                }
                for component in scope["component_ids"]
            ],
            "reset_policy": "disable_while_reset",
            "overlay": {
                "strategy": "wrapper",
                "wrapper_top": "SpecFlowOverlay",
                "host_scope": "SpecFlowOverlay",
            },
            "required_observations": ["binding.fixed"],
            "configuration_domain": [stage["configuration"]["configuration_id"]],
        }
        candidates = {
            "submit_obligation_candidates": [obligation],
            "submit_binding_candidates": [binding],
            "submit_monitor_candidates": [monitor],
        }
        arguments = (
            {
                "obligations": [obligation],
                "bindings": [binding],
                "monitors": [monitor],
            }
            if tool_name == "submit_direct_property_package"
            else {"candidates": candidates[tool_name]}
        )
        return {
            "type": "function_calls",
            "function_calls": [
                {"id": f"fixed-{len(self.calls)}", "name": tool_name, "arguments": arguments}
            ],
            "finish_reason": "tool_calls",
        }

    def get_token_usage(self) -> dict[str, int]:
        return {"llm_calls": len(self.calls)}


@pytest.mark.parametrize("family", FAMILY_GROUPS)
def test_selected_scope_has_one_complete_primary_group(family: str) -> None:
    specflow = REPO / "benchmark/synth" / family / "specflow"
    public = load_public_spec_package(specflow / "spec.md", LEDGER)
    decomposition = load_property_decomposition(
        specflow / "property_decomposition.json", public
    )
    expected_property, primary = SELECTED_AUTHORING_SCOPE[family]
    scope = build_authoring_scope(
        decomposition,
        public,
        (expected_property,),
        (primary,),
        SELECTED_AUTHORING_CLAUSES.get(family, ()),
    )

    assert scope["primary_component_ids"] == [primary]
    assert scope["component_ids"] == decomposition["component_groups"][primary]
    assert scope["require_complete_primary_set"] is True


def test_selected_scope_rejects_clause_outside_property() -> None:
    specflow = REPO / "benchmark/synth/led_controller/specflow"
    public = load_public_spec_package(specflow / "spec.md", LEDGER)
    decomposition = load_property_decomposition(
        specflow / "property_decomposition.json", public
    )

    with pytest.raises(PropertyDecompositionError):
        build_authoring_scope(
            decomposition,
            public,
            ("LED-P003",),
            ("LED-P003",),
            ("LED-001",),
        )


@pytest.mark.parametrize("family", ("fsm_16", "i2c"))
def test_fixed_model_builds_p0_and_p1_selected_packages_for_known_failures(
    tmp_path: Path,
    family: str,
) -> None:
    specflow = REPO / "benchmark/synth" / family / "specflow"
    expected_property, primary = SELECTED_AUTHORING_SCOPE[family]

    def workspace(name: str):
        return prepare_workspace(
            SpecFlowRunConfig(
                project_contract=specflow / "project.json",
                specification=specflow / "spec.md",
                configuration=specflow / "configs/cfg_000.json",
                run_root=tmp_path,
                opaque_task_id=name,
                expected_property_ids=(expected_property,),
                component_ids=(primary,),
            ),
            tmp_path / name,
            LEDGER,
        )

    p0_model = FixedPackageModel()
    p0 = run_direct_one_shot(workspace("p0"), p0_model, max_tokens=32768)
    assert p0["status"] == "completed"
    assert p0_model.calls[0]["max_tokens"] == 32768
    direct_definitions = p0_model.calls[0]["tools"][0]["parameters"]["$defs"]
    obligation_schema = json.dumps(direct_definitions["obligation_expression"])
    monitor_schema = json.dumps(direct_definitions["monitor_expression"])
    assert "#/$defs/expression" not in obligation_schema + monitor_schema
    assert "#/$defs/obligation_expression" in obligation_schema
    assert "previous_value" not in obligation_schema
    assert "#/$defs/monitor_expression" in monitor_schema
    assert "previous_value" in monitor_schema

    p1_model = FixedPackageModel()
    p1 = run_asset_authoring(workspace("p1"), p1_model)
    assert p1.status == "awaiting_review"
    assert [row["max_tokens"] for row in p1_model.calls] == [8192, 8192, 16384]
    if family == "fsm_16":
        assert "## 5. Terms, events, and abstract golden model" in (
            p1_model.stage_inputs["specification"]["full_text"]
        )
    candidates = json.loads(
        (p1.stage_dir / "authoring_candidates.json").read_text(encoding="utf-8")
    )
    assert candidates["status"] == "candidate"
    assert candidates["obligations"] and candidates["bindings"] and candidates["monitors"]
    submitted = candidates["monitors"][0]
    assert len(submitted["properties"]) == len(
        p1_model.stage_inputs["authoring_scope"]["component_ids"]
    )
    assert all(row["clear"]["root"]["result_type"]["kind"] == "Bool" for row in submitted["state"])


def test_tool_schema_fixes_primary_id_and_exact_component_group() -> None:
    obligation_parameters = obligation_tools(
        ("CLAUSE",),
        ("object",),
        "cfg_000",
        ("PRIMARY",),
    )[0]["parameters"]
    obligation = obligation_parameters["properties"]["candidates"]
    assert obligation["minItems"] == obligation["maxItems"] == 1
    assert obligation["items"]["properties"]["obligation_id"]["enum"] == ["PRIMARY"]

    archetypes = {
        "direct_relation": {
            "sha256": "0" * 64,
            "state_contract": {
                "minimum_count": 0,
                "maximum_count": 0,
                "required_type_kinds": [],
            },
        }
    }
    components = ("PRIMARY", "PRIMARY.activation", "PRIMARY.observer")
    roles = {
        "PRIMARY": "primary_assertion",
        "PRIMARY.activation": "activation_cover",
        "PRIMARY.observer": "observer_cover",
    }
    monitors = monitor_tools(
        ("PRIMARY",),
        ("binding",),
        ("object",),
        "cfg_000",
        archetypes,
        components,
        roles,
        "direct_relation",
    )[0]["parameters"]["properties"]["candidates"]
    assert monitors["minItems"] == monitors["maxItems"] == 1
    properties = monitors["items"]["properties"]["properties"]
    assert properties["minItems"] == properties["maxItems"] == len(components)
    assert {
        clause["contains"]["properties"]["source_property_id"]["const"]
        for clause in properties["allOf"]
    } == set(components)

    stateful = monitor_tools(
        ("PRIMARY",),
        ("binding",),
        ("object",),
        "cfg_000",
        {
            "previous_value": {
                "sha256": "1" * 64,
                "state_contract": {
                    "minimum_count": 1,
                    "maximum_count": 4,
                    "required_type_kinds": ["Bool"],
                },
            }
        },
        components,
        roles,
        "previous_value",
    )[0]["parameters"]["properties"]["candidates"]["items"]
    state_contract = stateful["properties"]["state"]
    assert state_contract["allOf"][0]["contains"]["properties"]["type"][
        "properties"
    ]["kind"] == {"type": "string", "const": "Bool"}

    expression_variants = obligation_parameters["$defs"]["expression"]["anyOf"]
    literal = next(item for item in expression_variants if "anyOf" in item)
    bool_literal, integer_literal = literal["anyOf"]
    assert bool_literal["properties"]["value"] == {"type": "boolean"}
    assert integer_literal["properties"]["value"] == {"type": "integer"}


def test_fsm_lookup_table_is_typed_and_lowers_without_raw_source() -> None:
    bool_type = {"kind": "Bool", "width": 1, "signed": False}
    uint2_type = {"kind": "UInt", "width": 2, "signed": False}
    expression = validate_expression(
        {
            "op": "lookup_table",
            "selectors": [
                {"op": "object_ref", "object_id": "s0"},
                {"op": "object_ref", "object_id": "s1"},
            ],
            "values": [0, 2, 3, 1],
            "type": uint2_type,
        },
        {"s0": bool_type, "s1": bool_type},
    )
    lowered = lower_expression(
        expression,
        {
            "s0": ChiselExpr("dut.s0", ExpressionType("Bool", 1, False)),
            "s1": ChiselExpr("dut.s1", ExpressionType("Bool", 1, False)),
        },
    )
    assert lowered.source == (
        "VecInit(Seq(0.U(2.W), 2.U(2.W), 3.U(2.W), 1.U(2.W)))"
        "(Cat(dut.s0, dut.s1))"
    )


def test_track_p_schedule_has_fixed_evaluation_order_and_14_rows(
    tmp_path: Path,
) -> None:
    assert DEVELOPMENT_FAMILIES == ("counter", "fsm_16", "i2c")
    assert EVALUATION_FAMILIES == (
        "alu",
        "decoder_3_to_8",
        "arbiter",
        "led_controller",
        "sdram_controller",
        "reed_solomon_decoder",
        "sha3",
    )
    schedule = _scheduled_track_p_tasks("Evaluation")
    assert len(schedule) == 14
    assert schedule == tuple(
        f"{family}-{method}"
        for family in EVALUATION_FAMILIES
        for method in ("p0", "p1")
    )
    (tmp_path / "track_p.jsonl").write_text(
        json.dumps(
            {
                "task": "alu-p0",
                "family": "alu",
                "method": "p0",
                "group": "Evaluation",
            }
        )
        + "\n",
        encoding="utf-8",
    )
    _assert_track_p_task_order(tmp_path, "Evaluation", "alu", "p1")
    with pytest.raises(ExperimentContractError, match="order mismatch"):
        _assert_track_p_task_order(tmp_path, "Evaluation", "arbiter", "p0")


def test_evaluation_gate_requires_frozen_immutable_decision_and_inputs(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setattr(
        "src.experiments.paper.validate_prepared", lambda _run_dir: None
    )
    config = {
        "status": "development",
        "frozen_inputs": {"input_set_sha256": "frozen-input-set"},
    }
    config_path = tmp_path / "config.development.json"
    config_path.write_text(json.dumps(config) + "\n", encoding="utf-8")

    with pytest.raises(ExperimentContractError, match="decision.json"):
        _experiment_config(tmp_path, tmp_path, "Evaluation")

    names = (
        "corpus",
        "prompt",
        "scoring_script",
        "experiment_runner",
        "direct_baseline",
        "specflow_tool_schema",
        "specflow_authoring",
        "llm_client",
        "suite_ledger",
    )
    references = {}
    for name in names:
        path = tmp_path / f"{name}.txt"
        path.write_text(name + "\n", encoding="utf-8")
        references[name] = {
            "path": path.name,
            "sha256": _sha256(path),
            "size_bytes": path.stat().st_size,
        }
    decision = {
        "decision": "pilot_only",
        "evaluation_authorized": False,
        "config_development_sha256": _sha256(config_path),
        "frozen_inputs": references
        | {"input_set_sha256": "frozen-input-set"},
    }
    decision_path = tmp_path / "decision.json"
    decision_path.write_text(json.dumps(decision) + "\n", encoding="utf-8")
    (tmp_path / "decision.sha256").write_text(
        _sha256(decision_path) + "  decision.json\n",
        encoding="utf-8",
    )
    with pytest.raises(ExperimentContractError, match="not authorized"):
        _experiment_config(tmp_path, tmp_path, "Evaluation")

    decision["decision"] = "frozen"
    decision["evaluation_authorized"] = False
    decision_path.write_text(json.dumps(decision) + "\n", encoding="utf-8")
    (tmp_path / "decision.sha256").write_text(
        _sha256(decision_path) + "  decision.json\n",
        encoding="utf-8",
    )
    with pytest.raises(ExperimentContractError, match="not authorized"):
        _experiment_config(tmp_path, tmp_path, "Evaluation")

    decision["evaluation_authorized"] = True
    decision_path.write_text(json.dumps(decision) + "\n", encoding="utf-8")
    (tmp_path / "decision.sha256").write_text(
        _sha256(decision_path) + "  decision.json\n",
        encoding="utf-8",
    )
    assert _experiment_config(tmp_path, tmp_path, "Evaluation") == config

    (tmp_path / "prompt.txt").write_text("drift\n", encoding="utf-8")
    with pytest.raises(ExperimentContractError, match="frozen input drift"):
        _experiment_config(tmp_path, tmp_path, "Evaluation")


def test_truncated_tool_arguments_are_preserved_before_parsing(tmp_path: Path) -> None:
    class Response:
        ok = True

        def json(self) -> dict[str, Any]:
            return {
                "id": "response-fixed",
                "usage": {
                    "prompt_tokens": 10,
                    "completion_tokens": 4,
                    "total_tokens": 14,
                },
                "choices": [
                    {
                        "finish_reason": "length",
                        "message": {
                            "content": None,
                            "tool_calls": [
                                {
                                    "id": "call-fixed",
                                    "function": {
                                        "name": "submit",
                                        "arguments": "{\"value\":",
                                    },
                                }
                            ],
                        },
                    }
                ],
            }

    class Session:
        payload: dict[str, Any] | None = None

        def post(self, *_args: Any, **kwargs: Any) -> Response:
            self.payload = kwargs["json"]
            return Response()

    client = LLMClient(
        model="fixed",
        api_key="not-recorded",
        llm_url="https://example.invalid/v1",
        raw_response_dir=tmp_path,
    )
    session = Session()
    client.session = session
    result = client.chat_with_tools(
        messages=[{"role": "user", "content": "test"}],
        tools=[
            {
                "name": "submit",
                "description": "submit",
                "parameters": {"type": "object"},
            }
        ],
        tool_choice={
            "type": "function",
            "function": {"name": "submit"},
        },
        enable_thinking=False,
    )

    assert result["type"] == "invalid_tool_arguments"
    raw_text = (tmp_path / "response_01.json").read_text(encoding="utf-8")
    raw = json.loads(raw_text)
    assert raw["raw_tool_calls"][0]["arguments"] == '{"value":'
    assert "not-recorded" not in raw_text
    assert session.payload["thinking"] == {"type": "disabled"}
    assert "enable_thinking" not in session.payload


def test_tool_arguments_accept_literal_newline_inside_string(tmp_path: Path) -> None:
    class Response:
        ok = True

        def json(self) -> dict[str, Any]:
            return {
                "id": "response-newline",
                "usage": {},
                "choices": [
                    {
                        "finish_reason": "tool_calls",
                        "message": {
                            "content": None,
                            "tool_calls": [
                                {
                                    "id": "call-newline",
                                    "function": {
                                        "name": "submit",
                                        "arguments": '{"reason":"line one\nline two"}',
                                    },
                                }
                            ],
                        },
                    }
                ],
            }

    class Session:
        def post(self, *_args: Any, **_kwargs: Any) -> Response:
            return Response()

    client = LLMClient(
        model="fixed",
        api_key="not-recorded",
        llm_url="https://example.invalid/v1",
        raw_response_dir=tmp_path,
    )
    client.session = Session()
    result = client.chat_with_tools(
        messages=[{"role": "user", "content": "test"}],
        tools=[{"name": "submit", "description": "submit", "parameters": {}}],
    )
    assert result["function_calls"][0]["arguments"] == {
        "reason": "line one\nline two"
    }


def test_prepare_gates_missing_decomposition_budget_and_duplicate_decision(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    original_is_file = Path.is_file
    missing = (
        REPO / "benchmark/synth/counter/specflow/property_decomposition.json"
    ).resolve()

    def is_file(path: Path) -> bool:
        return False if path.resolve() == missing else original_is_file(path)

    monkeypatch.setattr(Path, "is_file", is_file)
    with pytest.raises(ExperimentContractError, match="missing property decomposition"):
        _family_entry(REPO, tmp_path, "counter")
    monkeypatch.setattr(Path, "is_file", original_is_file)

    args = argparse.Namespace(
        repo=str(REPO),
        url="https://example.invalid/v1",
        model="fixed",
        max_output_tokens=16384,
        recorded_argv=(),
    )
    with pytest.raises(ExperimentContractError, match="output budget"):
        prepare(args)

    (tmp_path / "decision.json").write_text("{}\n", encoding="utf-8")
    with pytest.raises(ExperimentContractError, match="decision already exists"):
        _write_development_decision(tmp_path, {}, [], "pilot_only")


def test_terminal_authoring_state_records_installed_review_rejection(
    tmp_path: Path,
) -> None:
    method_root = tmp_path / "p1"
    source_run = method_root / "source_run"
    stage = source_run / "stages/01_asset_authoring"
    stage.mkdir(parents=True)
    state = {
        "schema_version": "track_p_task_state",
        "task": "i2c-p1",
        "family": "i2c",
        "method": "p1",
        "status": "awaiting_review",
        "source_run": str(source_run),
        "model_usage": {},
        "model_request_attempts": 3,
        "wall_time_seconds": 1.0,
    }
    (method_root / "task_state.json").write_text(
        json.dumps(state) + "\n", encoding="utf-8"
    )
    (source_run / "manifest.json").write_text(
        json.dumps({"review_state": "rejected"}) + "\n", encoding="utf-8"
    )
    (stage / "stage_result.json").write_text(
        json.dumps(
            {
                "status": "rejected",
                "error_kind": "review_rejected",
                "reason": "semantic mismatch",
            }
        )
        + "\n",
        encoding="utf-8",
    )

    terminal = _terminal_authoring_state(method_root)

    assert terminal["status"] == "rejected"
    assert terminal["error"] == "semantic mismatch"
    assert json.loads((method_root / "task_state.json").read_text())["status"] == "rejected"
