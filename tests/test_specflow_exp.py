from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any

from src.chiselspecflow.authoring_tools import (
    bind_and_instantiate_tools,
    extract_obligation_tools,
)
from src.chiselspecflow.authoring import (
    _enumerate_two_stage_candidates,
    _expected_variants,
    _guard_variants,
    _lookup_formula,
    run_two_stage_authoring,
)
from src.chiselspecflow.assets import load_reviewed_assets
from src.chiselspecflow.config import SpecFlowRunConfig
from src.chiselspecflow.preflight import prepare_workspace
from src.chiselspecflow.elaboration import _remove_circt_resource_file_list
from src.chiselspecflow.monitor_compiler import OverlayUnit, render_overlay
from src.experiments.direct_sva import (
    classify_direct_sva_execution,
    generate_direct_sva,
    render_direct_harness,
)
from src.experiments.scoring import (
    family_bootstrap_ci,
    paired_family_differences,
    specflow_rates,
)
from src.experiments.specflow_exp import (
    DIAGNOSTIC_PROPERTIES,
    FAMILY_GROUPS,
    _failure_status,
    _score_s0,
    prepare,
)


class DirectModel:
    def chat_with_tools(self, **kwargs: Any) -> dict[str, Any]:
        assert kwargs["tools"][0]["name"] == "submit_direct_sva"
        return {
            "type": "function_calls",
            "function_calls": [
                {
                    "id": "direct",
                    "name": "submit_direct_sva",
                    "arguments": {
                        "properties": [
                            {
                                "property_id": "P0",
                                "sva": (
                                    "P0__assert: assert property (@(posedge clock) dut.ok);\n"
                                    "P0__activation: cover property (@(posedge clock) dut.enable);"
                                ),
                            }
                        ]
                    },
                }
            ],
        }

    def get_token_usage(self) -> dict[str, int]:
        return {"llm_calls": 1}


class TwoStageModel:
    def __init__(self) -> None:
        self.calls = 0

    def chat_with_tools(self, **kwargs: Any) -> dict[str, Any]:
        self.calls += 1
        tool = kwargs["tools"][0]["name"]
        context = json.loads(kwargs["messages"][-1]["content"])
        if tool == "extract_obligations":
            candidates = [
                {"intent_id": next(
                    row["intent_id"]
                    for row in context["intent_candidates"]
                    if row["clause_locator"] == clause["locator"]
                )}
                for clause in context["clauses"]
            ]
        else:
            candidates = [
                {"candidate_id": next(
                    row["candidate_id"]
                    for row in context["candidates"]
                    if row["obligation_ref"] == obligation["obligation_ref"]
                )}
                for obligation in context["obligations"]
            ]
        return {
            "type": "function_calls",
            "function_calls": [
                {
                    "id": f"call-{self.calls}",
                    "name": tool,
                    "arguments": {"candidates": candidates},
                }
            ],
        }


def test_two_stage_grammar_covers_diagnosed_shared_semantics() -> None:
    def obj(name: str, kind: str, width: int, direction: str = "internal") -> dict[str, Any]:
        return {
            "object_id": "obj_" + name,
            "name": name,
            "aliases": [],
            "direction": direction,
            "chisel_type": {"kind": kind, "width": width, "signed": kind == "SInt"},
        }

    lights = obj("lights", "UInt", 3, "output")
    state = obj("state", "UInt", 2)
    count = obj("count", "SInt", 32)
    led_text = "In GO, count below 6 selects GREEN; count at least 6 selects YELLOW."
    intent = {"trigger_role": "none", "expected_role": "semantic_object", "temporal_kind": "same_cycle"}
    guards = _guard_variants(
        led_text, intent, lights, [lights, state, count],
        "WAIT=0 GO=1 WARN=2 STOP=3 RED=001 YELLOW=010 GREEN=100",
    )
    assert [row[-1]["relation"] for row in guards] == ["slt", "sge"]
    assert all(row[0]["object_id"] == "obj_state" and row[0]["value"] == 1 for row in guards)
    assert _expected_variants(
        intent, lights, [lights, state, count], led_text,
        "lights match the current branch. RED=001 YELLOW=010 GREEN=100",
    ) == [{"kind": "literal", "value": 4}, {"kind": "literal", "value": 2}]
    candidates = _enumerate_two_stage_candidates(
        [{
            "obligation_ref": "obligation_01",
            "clause_locator": "LED-004",
            "trigger_role": "none",
            "expected_role": "semantic_object",
            "temporal_kind": "same_cycle",
            "bound": 0,
            "relation": "eq",
        }],
        [lights, state, count],
        {"direct_relation": {}},
        clauses=[{"locator": "LED-004", "text": led_text}],
        full_text=(
            led_text
            + " lights match the current branch. "
            + "WAIT=0 GO=1 WARN=2 STOP=3 RED=001 YELLOW=010 GREEN=100"
        ),
        scope={"primary_component_ids": ["LED-P003"]},
    )
    assert len(candidates) == 1
    assert candidates[0]["formula_kind"] == "conjunction"
    assert [row["expected"]["value"] for row in candidates[0]["formulas"]] == [4, 2]

    cmd_ack = obj("cmd_ack", "Bool", 1, "output")
    cmd_ack["accessibility"] = "wrapper"
    wb_ack = obj("wb_ack_o", "Bool", 1, "output")
    wb_ack["accessibility"] = "direct"
    pulse_intent = {"trigger_role": "high", "temporal_kind": "next_cycle"}
    assert _guard_variants("completion", pulse_intent, cmd_ack, [cmd_ack], "completion") == [
        [{"kind": "bool", "object_id": "obj_cmd_ack", "value": True}]
    ]
    pulse_candidates = _enumerate_two_stage_candidates(
        [{
            "obligation_ref": "obligation_01",
            "clause_locator": "I2C-010",
            "trigger_role": "high",
            "expected_role": "zero",
            "temporal_kind": "next_cycle",
            "bound": 1,
            "relation": "eq",
        }],
        [wb_ack, cmd_ack],
        {"previous_value": {}},
        clauses=[{"locator": "I2C-010", "text": "ack completion pulse"}],
        full_text="bit phase completion ack",
        scope={"primary_component_ids": ["I2C-P004.bit-phase-completion"]},
    )
    assert len(pulse_candidates) == 1
    assert pulse_candidates[0]["observation_id"] == "obj_cmd_ack"

    counter = obj("counter_out", "UInt", 4, "output")
    enable = obj("enable", "Bool", 1, "input")
    hold_intent = {"trigger_role": "low", "temporal_kind": "next_cycle"}
    assert _guard_variants(
        "Both reset and enable are low; hold modulo 16.",
        hold_intent,
        counter,
        [counter, enable],
        "",
    ) == [[
        {"kind": "reset", "value": False},
        {"kind": "bool", "object_id": "obj_enable", "value": False},
    ]]

    fsm_text = (Path(__file__).resolve().parents[1] / "benchmark/synth/fsm_16/specflow/spec.md").read_text()
    lookup = _lookup_formula(
        {"temporal_kind": "next_cycle", "relation": "eq"},
        "complete next-state relation",
        fsm_text,
        [obj("state", "UInt", 4, "output"), obj("input1", "Bool", 1, "input"), obj("input2", "Bool", 1, "input")],
    )
    assert lookup is not None and len(lookup["expected"]["values"]) == 64


def test_direct_sva_is_raw_and_has_no_typed_contract(tmp_path: Path) -> None:
    rows = generate_direct_sva(
        DirectModel(), {"faulty_chisel_sources": []}, tmp_path / "authoring", max_tokens=10
    )
    assert rows == [
        {
            "property_id": "P0",
            "sva": (
                "P0__assert: assert property (@(posedge clock) dut.ok);\n"
                "P0__activation: cover property (@(posedge clock) dut.enable);"
            ),
        }
    ]
    harness = render_direct_harness(
        top="dut_top",
        formal={"clock": "clock", "reset": "reset"},
        baseline={
            "objects": [
                {"owner_module": "dut_top", "direction": "input", "name": "enable"},
                {"owner_module": "dut_top", "direction": "output", "name": "ok"},
            ]
        },
        semantic_index={
            "objects": [
                {
                    "owner_module": "dut_top",
                    "direction": "input",
                    "name": "enable",
                    "chisel_type": {"kind": "Bool", "width": 1, "signed": False},
                },
                {
                    "owner_module": "dut_top",
                    "direction": "output",
                    "name": "ok",
                    "chisel_type": {"kind": "Bool", "width": 1, "signed": False},
                },
                {
                    "owner_module": "dut_top",
                    "direction": "output",
                    "name": "spurious_internal",
                    "chisel_type": {"kind": "Bool", "width": 1, "signed": False},
                },
            ]
        },
        properties=rows,
    )
    assert "dut_top dut (.enable(enable), .ok(ok), .clock(clock), .reset(reset));" in harness
    assert "spurious_internal" not in harness
    assert "P0__assert: assert property" in harness


def test_direct_sva_classifies_hdl_errors_as_compile_failures() -> None:
    result = {"operation_results": [{"status": "tool_error"}]}
    log = "[ERROR (VERI-1128)] 'valid_sample' is not declared"
    assert classify_direct_sva_execution(result, log) == "compile_error"
    assert classify_direct_sva_execution(result, "Waiting for license") == "tool_error"


def test_missing_direct_tool_call_is_an_authoring_failure() -> None:
    error = ValueError("Direct SVA requires exactly one tool call")
    assert _failure_status(error) == "authoring_error"


def test_baseline_sv_truncates_embedded_circt_resource_file_list(
    tmp_path: Path,
) -> None:
    path = tmp_path / "Design.sv"
    path.write_text(
        "module Design;\nendmodule\n"
        '// ----- 8< ----- FILE "firrtl_black_box_resource_files.f" ----- 8< -----\n'
        "BlackBox.sv\n",
        encoding="utf-8",
    )
    _remove_circt_resource_file_list(path)
    assert path.read_text(encoding="utf-8") == "module Design;\nendmodule\n"


def test_two_stage_tool_surface_has_only_reviewed_ids_and_no_expression_tree() -> None:
    extract = extract_obligation_tools((
        {
            "intent_id": "P.intent-1",
            "clause_locator": "C1",
        },
    ))[0]
    bind = bind_and_instantiate_tools(
        (
            {
                "candidate_id": "obligation_01.same_cycle",
                "obligation_ref": "obligation_01",
                "archetype_id": "direct_relation",
                "binding_roles": ["observation"],
                "object_options": {"observation": ["obj"]},
            },
        ),
    )[0]
    serialized = json.dumps([extract, bind], sort_keys=True)
    assert "expression_ir" not in serialized
    assert "slots" not in serialized
    assert "expected_literal" not in serialized
    assert "monitor_id" not in serialized
    assert "binding_id" not in serialized
    assert "object_id" not in json.dumps(bind, sort_keys=True)
    assert "expected_role" not in json.dumps(extract, sort_keys=True)
    assert extract["name"] == "extract_obligations"
    assert bind["name"] == "bind_and_instantiate"


def test_two_stage_authoring_assigns_ids_and_writes_run_local_package(
    tmp_path: Path,
) -> None:
    repo = Path(__file__).resolve().parents[1]
    specflow = repo / "benchmark/synth/i2c/specflow"
    workspace = prepare_workspace(
        SpecFlowRunConfig(
            project_contract=specflow / "project.json",
            specification=specflow / "spec.md",
            configuration=specflow / "configs/cfg_000.json",
            run_root=tmp_path,
            opaque_task_id="i2c-s2",
            expected_property_ids=("I2C-P004",),
            component_ids=("I2C-P004.bit-phase-completion",),
            clause_ids=("I2C-010",),
        ),
        tmp_path / "i2c-s2",
        repo / "benchmark/synth/SPECIFICATIONS.sha256",
    )
    model = TwoStageModel()
    outcome = run_two_stage_authoring(
        workspace, model, max_tokens=(100, 100)
    )
    assert outcome["status"] == "completed"
    assert model.calls == 2
    package = json.loads(
        (workspace.stage_dir("asset_authoring") / "verification_package.json").read_text()
    )
    assert package["obligations"][0]["obligation_id"] == "I2C-P004.bit-phase-completion"
    assert package["bindings"][0]["binding_id"].startswith(
        "I2C-P004.bit-phase-completion.binding."
    )
    assert package["monitors"][0]["monitor_id"] == "I2C-P004.bit-phase-completion.monitor"
    assert package["review"]["reviewer"] == "none_specflow_experiment"


def test_two_stage_authoring_fails_without_reviewed_intent(
    tmp_path: Path,
) -> None:
    repo = Path(__file__).resolve().parents[1]
    specflow = repo / "benchmark/synth/counter/specflow"
    workspace = prepare_workspace(
        SpecFlowRunConfig(
            project_contract=specflow / "project.json",
            specification=specflow / "spec.md",
            configuration=specflow / "configs/cfg_000.json",
            run_root=tmp_path,
            opaque_task_id="counter-direct-relation",
            expected_property_ids=("CTR-P-TIM-002",),
            component_ids=("CTR-P-TIM-002",),
        ),
        tmp_path / "counter-direct-relation",
        repo / "benchmark/synth/SPECIFICATIONS.sha256",
    )

    model = TwoStageModel()
    outcome = run_two_stage_authoring(workspace, model, max_tokens=(100, 100))

    assert outcome["status"] == "invalid_submission"
    assert outcome["error_kind"] == "intent_contract_missing"
    assert model.calls == 0


def test_rendered_property_identity_keeps_predicate_name_distinct() -> None:
    label = "CSF_0123456789ABCDEF"
    rendered = render_overlay(
        [
            OverlayUnit(
                monitor_id="P.monitor",
                state_lines=(),
                property_rows=(
                    {
                        "source_property_id": "P",
                        "expected_label": label,
                        "role": "primary_assertion",
                        "guard": "true.B",
                        "expression": "(dut.out === dut.out)",
                    },
                ),
            )
        ],
        {"generator": {}},
        {"parameters": {}},
        {
            "top": "Dut",
            "objects": [
                {
                    "name": "out",
                    "owner_module": "Dut",
                    "direction": "output",
                    "fact_status": "elaboration_confirmed",
                    "chisel_type": {"kind": "Bool", "width": 1, "signed": False},
                }
            ],
        },
        {
            "project_id": None,
            "strategy": "wrapper",
            "constructor_template": "new Dut()",
            "imports": [],
        },
    )

    predicate_name = f"{label}_predicate"
    assert f"val {predicate_name} = WireInit(" in rendered.source
    assert f"dontTouch({predicate_name})" in rendered.source
    assert f'assert({predicate_name}, "{label}")' in rendered.source
    property_row = rendered.properties[0]
    source_line = rendered.source.splitlines()[
        property_row["overlay_source_anchor"]["line"] - 1
    ]
    assert source_line.strip() == f'assert({predicate_name}, "{label}")'


def test_reviewed_reference_relation_keeps_original_property_identity() -> None:
    assets = load_reviewed_assets()
    relation = assets.reference_relations["reed_solomon_decoder.rs204_reference"]
    semantic_objects = [
        {
            "object_id": "obj_" + name,
            "name": name,
            "owner_module": "RS_dec",
            "direction": "input" if name in {"CE", "input_byte"} else "output",
            "accessibility": "direct",
            "fact_status": "elaboration_confirmed",
            "chisel_type": {
                "kind": "UInt" if name in {"input_byte", "Out_byte"} else "Bool",
                "width": 8 if name in {"input_byte", "Out_byte"} else 1,
                "signed": False,
            },
        }
        for name in relation["required_objects"]
    ]
    candidates = _enumerate_two_stage_candidates(
        [{
            "obligation_ref": "obligation_01",
            "component_id": "RS204-P-REL-001",
            "clause_locator": "RS204-N-001",
            "temporal_kind": "reference_relation",
        }],
        semantic_objects,
        assets.monitor_archetypes,
        reference_relations=assets.reference_relations,
        clauses=[{"locator": "RS204-N-001", "text": "field relation"}],
        full_text="field relation",
        scope={"primary_component_ids": ["RS204-P-REL-001"]},
    )
    assert [row["reference_relation_id"] for row in candidates] == [
        "reed_solomon_decoder.rs204_reference"
    ]

    rendered = render_overlay(
        [OverlayUnit(
            monitor_id="RS204-P-REL-001.monitor",
            state_lines=(),
            property_rows=tuple(
                {
                    "source_property_id": source_id,
                    "expected_label": f"CSF_REFERENCE_{index}",
                    "role": role,
                    "guard": "true.B",
                    "expression": "true.B",
                }
                for index, (source_id, role) in enumerate((
                    ("RS204-P-REL-001", "primary_assertion"),
                    ("RS204-P-REL-001.activation", "activation_cover"),
                    ("RS204-P-REL-001.observer", "observer_cover"),
                ))
            ),
        )],
        {"project_id": "reed_solomon_decoder"},
        {"parameters": {"variantIndex": 0}},
        {"top": "RS_dec", "objects": semantic_objects},
        assets.api_adapters["reed_solomon_decoder.wrapper"],
        relation,
    )
    assert "val rs_reference_data = IO(Input(UInt(8.W)))" in rendered.source
    assert "assume((!(csf_reference.premise_valid)) || (csf_reference.premise))" in rendered.source
    assert [row["source_property_id"] for row in rendered.properties] == [
        "RS204-P-REL-001",
        "RS204-P-REL-001.activation",
        "RS204-P-REL-001.observer",
    ]


def test_prepare_lists_all_41_bug_tasks(tmp_path: Path, monkeypatch: Any) -> None:
    def family_entry(_repo: Path, _run_dir: Path, family: str) -> dict[str, Any]:
        (_run_dir / "raw" / "frozen_inputs" / family).mkdir(
            parents=True, exist_ok=True
        )
        count = {
            "counter": 3,
            "fsm_16": 4,
            "i2c": 6,
            "alu": 6,
            "decoder_3_to_8": 6,
            "arbiter": 3,
            "led_controller": 4,
            "sdram_controller": 3,
            "reed_solomon_decoder": 3,
            "sha3": 3,
        }[family]
        ref = {"path": "x", "sha256": "0" * 64, "size_bytes": 1}
        return {
            "project": ref,
            "specification": ref,
            "configuration": ref,
            "selected_authoring_scope": {},
            "clean": {},
            "bugs": [
                {
                    "bug_id": f"{family}-{index}",
                    "variant_index": index,
                    "chisel": {},
                    "rtl": [],
                    "bug_metadata": ref,
                    "bug_diff": ref,
                    "gold_source_location": {},
                }
                for index in range(1, count + 1)
            ],
        }

    monkeypatch.setattr("src.experiments.specflow_exp._family_entry", family_entry)
    repo = tmp_path / "repo"
    repo.mkdir()
    run_dir = prepare(
        argparse.Namespace(
            repo=str(repo),
            experiment_id="fixed",
            model="model",
            url="https://example.invalid/v1",
            max_output_tokens=100,
            timeout_seconds=10,
            per_property_seconds=2,
        )
    )
    tasks = json.loads((run_dir / "tasks.json").read_text())["tasks"]
    assert len(tasks) == 41
    assert {task["family"] for task in tasks} == set(FAMILY_GROUPS)
    assert sum(task["development_exposed"] for task in tasks) == 17
    assert (run_dir / "results.jsonl").read_text() == ""


def _row(family: str, method: str, valid: int) -> dict[str, Any]:
    return {
        "bug_id": family + "-1",
        "family": family,
        "difficulty": "easy",
        "development_exposed": False,
        "method": method,
        "status": "completed",
        "generated_property_count": 1,
        "compiled_on_clean": 1,
        "compiled_on_faulty": 1,
        "clean_proven_count": valid,
        "clean_cex_count": 0,
        "faulty_cex_count": valid,
        "non_vacuous_count": valid,
        "valid_bug_detecting_property_count": valid,
        "property_funnel": [
            {
                "property_id": "P",
                "authoring_success": True,
                "executable_on_clean": True,
                "executable_on_faulty": True,
                "clean_proven": bool(valid),
                "clean_non_vacuous": bool(valid),
                "faulty_exact_cex": bool(valid),
                "valid_detection": bool(valid),
                "clean_false_alarm": False,
                "classification": (
                    "valid_detection" if valid else "incomplete_evidence"
                ),
            }
        ],
        "funnel_failure": None,
        "model_calls": 1,
        "input_tokens": 1,
        "output_tokens": 1,
        "authoring_seconds": 1.0,
        "formal_seconds": 1.0,
        "obligation_families": [],
        "monitor_archetypes": [],
        "failure_class": None,
        "artifact_paths": {},
    }


def test_scoring_uses_conjunctive_validity_and_family_pairing() -> None:
    assert specflow_rates(_row("alu", "s0", 1))["valid_property_rate"] == 1.0
    rows = [
        _row("alu", "s0", 0),
        _row("alu", "s2", 1),
        _row("counter", "s0", 1),
        _row("counter", "s2", 1),
    ]
    paired = {row["comparison"]: row for row in paired_family_differences(rows)}
    assert paired["s2-s0"]["paired_family_count"] == 2
    assert paired["s2-s0"]["mean_bug_detection_difference"] == 0.5
    assert family_bootstrap_ci([0.0, 1.0], samples=100, seed=0)[0] >= 0.0


def test_property_funnel_exact_join_and_failure_classes() -> None:
    row = _row("counter", "s0", 0)
    row["generated_property_count"] = 3
    clean = {
        "P1": {
            "primary_assertion": {"status": "proven"},
            "activation_cover": {"status": "covered"},
        },
        "P2": {
            "primary_assertion": {"status": "cex"},
            "activation_cover": {"status": "covered"},
        },
        "P3": {
            "primary_assertion": {"status": "proven"},
            "activation_cover": {"status": "uncovered"},
        },
    }
    faulty = {
        "P1": {"primary_assertion": {"status": "proven"}},
        "P2": {"primary_assertion": {"status": "cex", "trace_path": "p2.fst"}},
        "P3": {"primary_assertion": {"status": "cex", "trace_path": "p3.fst"}},
    }
    _score_s0(
        row,
        clean,
        faulty,
        ["P1", "P2", "P3"],
        clean_executable=True,
        faulty_executable=True,
    )
    classes = {item["property_id"]: item["classification"] for item in row["property_funnel"]}
    assert classes == {
        "P1": "clean_valid_not_fault_sensitive",
        "P2": "faulty_sensitive_clean_false_alarm",
        "P3": "non_vacuity_failure",
    }
    assert row["valid_bug_detecting_property_count"] == 0


def test_a3_properties_are_one_run_local_check_per_development_family() -> None:
    assert set(DIAGNOSTIC_PROPERTIES) == {
        "counter",
        "fsm_16",
        "i2c",
        "led_controller",
    }
    for item in DIAGNOSTIC_PROPERTIES.values():
        property_id = item["property_id"]
        assert item["sva"].count(f"{property_id}__assert:") == 1
        assert item["sva"].count(f"{property_id}__activation:") == 1
