import json
from pathlib import Path
import sys
from types import SimpleNamespace

import pytest


REPO_ROOT = Path(__file__).resolve().parents[1]
if str(REPO_ROOT) not in sys.path:
    sys.path.insert(0, str(REPO_ROOT))


def _mshr_manifest(bindings=None):
    return {
        "schema_version": "binding_manifest",
        "property_profile_id": "mshr_wait_bound_poc",
        "instances": [
            {
                "instance_id": "mshr_wait_0",
                "property_schema_id": "CL2_MSHR_WAIT_BOUND",
                "template_id": "chisel3_mshr_wait_bound",
                "target": {
                    "file_id": "verify_top",
                    "marker_id": "mshr_wait_bound_assertion_region",
                },
                "bindings": bindings if bindings is not None else {},
                "parameters": {"bound": 64},
                "base_label": "CL2_MSHR_WAIT_BOUND_0",
                "evidence": [],
            }
        ],
    }


def test_dynamic_tl_candidate_pool_is_filtered_and_expression_hidden(tmp_path):
    from src.coupledl2.binding_candidates import build_binding_catalog
    from src.coupledl2.indexer import build_observer_index, build_tl_signal_index
    from src.coupledl2.property_catalog import load_property_profile, public_catalog

    case = tmp_path / "case"
    target = case / "Chisel/src/test/scala/coupledL2Verification/VerifyTop.scala"
    target.parent.mkdir(parents=True)
    target.write_text(
        "class VerifyTop {\n  val observed = io.out.d.valid\n}\n",
        encoding="utf-8",
    )
    roots = ["Chisel/src/test/scala/coupledL2Verification"]
    indexes = {
        "tl_signal_index": build_tl_signal_index(case, roots=roots),
        "observer_index": build_observer_index(case, roots=roots),
    }
    catalog = build_binding_catalog(
        load_property_profile("write_read_poc"), indexes
    )
    dynamic = [
        item for item in catalog.candidates.values()
        if item["candidate_id"].startswith("tl_sig_")
        and "trigger" in item["roles"]
    ]
    assert dynamic
    assert dynamic[0]["type"] == "Bool"
    assert dynamic[0]["provenance"]["kind"] == "source_index"
    visible = public_catalog(catalog)["candidates"]
    assert all("expression" not in item for item in visible)
    assert any(item.get("source_location", {}).get("line") == 2 for item in visible)


def test_binding_manifest_requires_every_slot_from_the_model():
    from src.coupledl2.binding_contract import (
        BindingContractError,
        validate_binding_manifest,
    )
    from src.coupledl2.property_catalog import load_property_profile

    with pytest.raises(BindingContractError, match="cover exactly"):
        validate_binding_manifest(
            _mshr_manifest(), load_property_profile("mshr_wait_bound_poc")
        )


def test_stage_artifact_contract_is_shared_by_completion_and_resume(tmp_path):
    from src.core.artifact_contract import (
        validate_completed_stage,
        write_stage_outcome,
    )
    from src.coupledl2.stages import StageSpec

    spec = StageSpec(
        name="unit",
        ordinal=9,
        execution_kind="deterministic",
        required_predecessor=None,
        completion_gate="unit",
        artifact_contract=("canonical.json",),
    )
    (tmp_path / "canonical.json").write_text('{"value": 1}\n', encoding="utf-8")
    write_stage_outcome(tmp_path, spec, {"success": True})
    assert validate_completed_stage(tmp_path, spec)["success"] is True
    (tmp_path / "canonical.json").write_text('{"value": 2}\n', encoding="utf-8")
    assert validate_completed_stage(tmp_path, spec) is None


def test_proven_result_does_not_become_non_vacuous():
    from src.coupledl2.result_contract import (
        build_primary_operation_plan,
        reduce_property_result_map,
    )

    traceability = {
        "properties": [
            {
                "instance_id": "mshr_wait_0",
                "rtl_properties": [
                    {
                        "rtl_label": "CL2_MSHR_WAIT_BOUND__E0",
                        "expected_property_id": "VerifyTop.CL2_MSHR_WAIT_BOUND__E0",
                    }
                ],
            }
        ]
    }
    plan = build_primary_operation_plan(traceability, package_sha256="0" * 64)
    result = reduce_property_result_map(
        operation_plan=plan,
        operation_results=[
            {
                "operation_id": plan["operations"][0]["operation_id"],
                "status": "proven",
                "reason": "fixture",
            }
        ],
        property_profile_id="mshr_wait_bound_poc",
        property_package_sha256="1" * 64,
        assertion_delta_sha256="2" * 64,
    )

    assert result["semantic_status"] == "inconclusive"
    assert result["experiment_status"] == "excluded"
    assert "negative_oracle_not_run" in result["exclusion_reasons"]


def test_backend_primary_accounting_requires_exact_property_id():
    from src.coupledl2.backend import account_expected_operations

    result = account_expected_operations(
        [
            {
                "operation_id": "exact__primary_assertion__CL2_EXACT_E0",
                "role": "primary_assertion",
                "target": "CL2_EXACT__E0",
                "rtl_property_id": "VerifyTop.CL2_EXACT__E0",
            }
        ],
        {
            "property_statuses": {
                "OtherTop.CL2_EXACT__E0": {
                    "status": "proven",
                    "engine": "N",
                    "bound": "10",
                    "time": "1.0 s",
                }
            }
        },
        log_text="",
        returncode=0,
        outer_timed_out=False,
        trace_dir=None,
    )
    assert result["primary_results"][0]["status"] == "tool_error"
    assert result["primary_results"][0]["observed_property_id"] is None
    assert result["auxiliary_results"][0]["observed_property_id"].startswith(
        "OtherTop."
    )


def test_stage_contracts_remove_duplicate_artifacts_and_model_from_stage3():
    from src.coupledl2.stages import get_stage_spec

    stage2 = get_stage_spec("bind_properties")
    stage3 = get_stage_spec("invoke_verification")
    assert stage3.execution_kind == "deterministic"
    assert stage3.artifact_contract == (
        "property_result_map.json",
        "semantic_evidence.json",
        "proof_events.jsonl",
        "jaspergold.log",
    )
    assert "property_package.json" in stage2.artifact_contract
    assert "semantic_evidence.json" not in stage2.artifact_contract
    assert "assertion_traceability.json" not in stage2.artifact_contract


def test_binding_stage_emits_one_property_package_without_overwriting_inputs(tmp_path):
    from src.coupledl2.binding_stage import BindingStage
    from src.coupledl2.property_catalog import load_property_profile
    from src.coupledl2.workspace import StageContext

    case = tmp_path / "workspace/case"
    target = case / "Chisel/src/test/scala/coupledL2/VerifyTop.scala"
    source = case / "Chisel/src/main/scala/coupledL2/MSHRCtl.scala"
    target.parent.mkdir(parents=True)
    source.parent.mkdir(parents=True)
    target.write_text(
        "lazy val module = new LazyModuleImp(this) {\n"
        "  val verify_timer = RegInit(0.U(50.W))\n"
        "  verify_timer := verify_timer + 1.U\n"
        "  // CHISELLMFV_PROPERTY_MSHR_WAIT_BOUND_ASSERT\n"
        "}\n",
        encoding="utf-8",
    )
    source.write_text(
        "for (((timer, m), i) <- timers.zip(mshrs).zipWithIndex) {\n"
        "  when(m.io.status.bits.channel === 1.U) {\n"
        "    // CHISELLMFV_PROPERTY_MSHR_WAIT_BOUND_SOURCE\n"
        "  }\n"
        "}\n",
        encoding="utf-8",
    )
    results = tmp_path / "results"
    stage_dir = results / "by_stage/02_bind_properties"
    preflight = results / "preflight"
    stage_dir.mkdir(parents=True)
    preflight.mkdir(parents=True)
    (preflight / "baseline_assertion_inventory.json").write_text(
        '{"entries": []}\n', encoding="utf-8"
    )
    (preflight / "generated_assertion_scan.json").write_text(
        '{"cl2_labels": []}\n', encoding="utf-8"
    )
    stage_inputs = {
        "schema_version": "stage_inputs",
        "stage": "bind_properties",
        "sentinel": "must-not-change",
        "property_catalog": {},
    }
    (stage_dir / "stage_inputs.json").write_text(
        json.dumps(stage_inputs), encoding="utf-8"
    )
    catalog = load_property_profile("mshr_wait_bound_poc")
    context = StageContext(
        stage="bind_properties",
        stage_dir=stage_dir,
        snapshot_dir=stage_dir / "source_snapshot",
        context_indexes={},
        stage_inputs=stage_inputs,
        binding_catalog=catalog,
    )
    workspace = SimpleNamespace(
        run_dir=tmp_path,
        case_workspace=case,
        results_dir=results,
        config=SimpleNamespace(property_profile="mshr_wait_bound_poc"),
    )
    rtl = case / "Chisel/generated/VerifyTop.sv"

    class LLM:
        def chat_with_tools(self, **_kwargs):
            return {
                "type": "function_calls",
                "function_calls": [
                    {
                        "name": "submit_binding_manifest",
                        "arguments": _mshr_manifest(
                            {"timer": "sig_mshr_wait_timer"}
                        ),
                    }
                ],
            }

    class Backend:
        def discover_generated_verilog_files(self):
            return [rtl] if rtl.is_file() else []

        def verify_compilation(self, require_assertions=False):
            rtl.parent.mkdir(parents=True, exist_ok=True)
            annotation = "// @[src/test/scala/coupledL2/VerifyTop.scala 1:1]\n"
            base = "CL2_MSHR_WAIT_BOUND_0"
            rtl.write_text(
                "assert (timer <= 64); " + annotation
                + f"{base}__NV__trigger_cover__mshr_wait: cover (timer != 0); " + annotation
                + f"{base}__NV__observer_cover__timer__nonzero: cover (timer != 0); " + annotation
                + f"{base}__NV__state_cover__wait_counter__nonzero: cover (timer != 0); " + annotation
                + f"{base}__NV__assumption_sat__environment: cover (1); " + annotation,
                encoding="utf-8",
            )
            return {
                "success": True,
                "generated_files": [str(rtl)],
                "top_module": "VerifyTop",
            }

    result = BindingStage(
        workspace, Backend(), LLM(), logger=None, stage_context=context
    ).run()
    assert result["success"] is True
    assert json.loads((stage_dir / "stage_inputs.json").read_text()) == stage_inputs
    package = json.loads((stage_dir / "property_package.json").read_text())
    assert package["compilation_certificate"]["instances"][0]["static_gate"] == "passed"
    assert package["witness_plan"]["instances"][0]["parent_evidence_reusable"] is False
    for removed in (
        "assertion_traceability.json",
        "rtl_label_result.json",
        "compilation_certificate.json",
        "witness_plan.json",
        "verification_campaign.json",
    ):
        assert not (stage_dir / removed).exists()


def test_runner_stage3_is_direct_and_keeps_proof_separate_from_semantics(
    tmp_path, monkeypatch
):
    from src.core.artifact_contract import file_sha256, write_stage_outcome
    from src.coupledl2.backend import CoupledL2BuildOperations
    from src.coupledl2.result_contract import (
        bind_operation_plan_to_package,
        build_primary_operation_plan,
        build_unmaterialized_observation_map,
        reduce_property_result_map,
    )
    from src.coupledl2.runner import CoupledL2Runner
    from src.coupledl2.stages import get_stage_spec

    results = tmp_path / "results"
    stage2 = results / "by_stage/02_bind_properties"
    stage3 = results / "by_stage/03_invoke_verification"
    stage2.mkdir(parents=True)
    stage3.mkdir(parents=True)
    manifest = _mshr_manifest({"timer": "sig_mshr_wait_timer"})
    rtl_record = {
        "rtl_label": "CL2_MSHR_WAIT_BOUND_0__E0",
        "rtl_file": "workspace/case/VerifyTop.sv",
        "rtl_line": 1,
        "elaboration_index": 0,
    }
    traceability = {
        "schema_version": "assertion_traceability",
        "properties": [
            {
                "instance_id": "mshr_wait_0",
                "property_schema_id": "CL2_MSHR_WAIT_BOUND",
                "template_id": "chisel3_mshr_wait_bound",
                "base_label": "CL2_MSHR_WAIT_BOUND_0",
                "rtl_properties": [
                    {
                        **rtl_record,
                        "expected_property_id": "VerifyTop.CL2_MSHR_WAIT_BOUND_0__E0",
                    }
                ],
            }
        ],
    }
    operation_plan = build_primary_operation_plan(
        traceability, package_sha256="0" * 64
    )
    package = bind_operation_plan_to_package(
        {
            "schema_version": "property_package",
            "property_profile_id": "mshr_wait_bound_poc",
            "package_semantics_sha256": "",
            "operation_plan": operation_plan,
            "observation_map": build_unmaterialized_observation_map(
                top_module="VerifyTop",
                package_sha256="0" * 64,
                reason="fixture",
            ),
            "traceability": traceability,
        }
    )
    stage2_values = {
        "stage_inputs.json": {"schema_version": "stage_inputs"},
        "binding_manifest.json": manifest,
        "property_package.json": package,
        "assertion_delta.json": {
            "schema_version": "assertion_delta",
            "property_package_sha256": "pending",
            "operation_plan_sha256": "0" * 64,
            "rtl_properties": [rtl_record],
        },
        "render_result.json": {"schema_version": "render_result"},
        "build_result.json": {"success": True},
    }
    for name, value in stage2_values.items():
        (stage2 / name).write_text(json.dumps(value) + "\n", encoding="utf-8")
    (stage2 / "assertion_diff.patch").write_text("diff\n", encoding="utf-8")
    delta = json.loads((stage2 / "assertion_delta.json").read_text())
    delta["property_package_sha256"] = file_sha256(stage2 / "property_package.json")
    (stage2 / "assertion_delta.json").write_text(json.dumps(delta) + "\n")
    write_stage_outcome(
        stage2,
        get_stage_spec("bind_properties"),
        {"success": True, "rtl_property_count": 1},
    )
    result_map = reduce_property_result_map(
        operation_plan=operation_plan,
        operation_results=[
            {
                "operation_id": operation_plan["operations"][0]["operation_id"],
                "status": "proven",
                "reason": "tool_reported_proven",
            }
        ],
        property_profile_id="mshr_wait_bound_poc",
        property_package_sha256=file_sha256(stage2 / "property_package.json"),
        assertion_delta_sha256=file_sha256(stage2 / "assertion_delta.json"),
        instance_metadata={
            "mshr_wait_0": {
                "property_schema_id": "CL2_MSHR_WAIT_BOUND",
                "template_id": "chisel3_mshr_wait_bound",
                "base_label": "CL2_MSHR_WAIT_BOUND_0",
            }
        },
    )

    def fake_verification(self):
        (stage3 / "proof_events.jsonl").write_text(
            json.dumps({"event": "property_finalized"}) + "\n", encoding="utf-8"
        )
        (stage3 / "jaspergold.log").write_text("proven\n", encoding="utf-8")
        return {
            "success": True,
            "summary": "1 proven",
            "property_result_map": json.loads(json.dumps(result_map)),
        }

    monkeypatch.setattr(
        CoupledL2BuildOperations, "run_full_verification_flow", fake_verification
    )
    workspace = SimpleNamespace(
        run_dir=tmp_path,
        results_dir=results,
        case_workspace=tmp_path / "workspace/case",
        workspace_dir=tmp_path / "workspace",
        config=SimpleNamespace(property_profile="mshr_wait_bound_poc"),
    )
    result = CoupledL2Runner(
        workspace=workspace,
        logger=None,
        llm_client=None,
    )._run_deterministic_verification()

    assert result["success"] is True
    assert result["stage_result"]["model_calls"] == 0
    updated_map = json.loads((stage3 / "property_result_map.json").read_text())
    assert updated_map["schema_version"] == "property_result_map"
    assert updated_map["formal_outcome"] == "all_proven"
    assert updated_map["semantic_status"] == "inconclusive"
    assert updated_map["experiment_status"] == "excluded"
    evidence = json.loads((stage3 / "semantic_evidence.json").read_text())
    assert evidence["semantic_status"] == "inconclusive"
    assert not (stage2 / "semantic_evidence.json").exists()
