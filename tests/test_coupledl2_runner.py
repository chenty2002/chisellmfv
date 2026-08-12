import json
import logging
import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from main import parse_args
from src.core.artifact_contract import file_sha256, write_stage_outcome
from src.coupledl2.backend import CoupledL2BuildOperations
from src.coupledl2.config import CoupledL2RunConfig
from src.coupledl2.indexer import refresh_indexes
from src.coupledl2.result_contract import (
    bind_operation_plan_to_package,
    build_primary_operation_plan,
    build_semantic_evidence,
    build_unmaterialized_observation_map,
    canonical_sha256,
    reduce_property_result_map,
)
from src.coupledl2.runner import CoupledL2Runner
from src.coupledl2.stages import get_stage_spec
from src.coupledl2.workspace import (
    create_coupledl2_workspace,
    load_coupledl2_workspace,
)
from src.core.records import build_run_cost_summary, merge_run_cost_summaries


PROFILE_ID = "mshr_wait_bound_poc"
CASE_NAME = "XiangShan-CoupledL2-deadlock-v0"


def _make_case(root: Path) -> Path:
    case = root / CASE_NAME
    scala = case / "Chisel" / "src" / "test" / "scala" / "coupledl2" / "VerifyTop.scala"
    scala.parent.mkdir(parents=True)
    scala.write_text("class VerifyTop\n", encoding="utf-8")
    (case / "Chisel" / "Makefile").write_text("auto:\n\t@true\n", encoding="utf-8")
    (case / "Verilog").mkdir()
    (case / "Verilog" / "setup.sh").write_text("#!/bin/sh\n", encoding="utf-8")
    return case


def _workspace(tmp_path: Path):
    workspace = create_coupledl2_workspace(
        CoupledL2RunConfig(
            case_path=_make_case(tmp_path / "cases"),
            property_profile=PROFILE_ID,
            run_root=tmp_path / "runs",
        )
    )
    manifest = json.loads(workspace.manifest_path.read_text(encoding="utf-8"))
    manifest["preflight_status"] = "success"
    workspace.manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
    preflight_dir = workspace.results_dir / "preflight"
    preflight_dir.mkdir(parents=True, exist_ok=True)
    (preflight_dir / "preflight_result.json").write_text(
        json.dumps({"schema_version": "preflight_result", "gate": {}}),
        encoding="utf-8",
    )
    refresh_indexes(workspace)
    return workspace


def _traceability():
    return {
        "schema_version": "assertion_traceability",
        "top_module": "VerifyTop",
        "properties": [
            {
                "instance_id": "mshr_wait_0",
                "property_schema_id": "CL2_MSHR_WAIT_BOUND",
                "template_id": "chisel3_mshr_wait_bound",
                "base_label": "CL2_MSHR_WAIT_BOUND_0",
                "rtl_properties": [
                    {
                        "rtl_label": "CL2_MSHR_WAIT_BOUND_0",
                        "expected_property_id": "VerifyTop.CL2_MSHR_WAIT_BOUND_0",
                    }
                ],
            }
        ],
    }


def _write_stage2(workspace):
    stage_dir = workspace.results_dir / "by_stage" / get_stage_spec("bind_properties").directory_name
    traceability = _traceability()
    plan = build_primary_operation_plan(traceability, package_sha256="0" * 64)
    package = bind_operation_plan_to_package(
        {
            "schema_version": "property_package",
            "property_profile_id": PROFILE_ID,
            "traceability": traceability,
            "operation_plan": plan,
            "package_semantics_sha256": "",
            "observation_map": build_unmaterialized_observation_map(
                top_module="VerifyTop",
                package_sha256="0" * 64,
                reason="test fixture does not elaborate an observation map",
            ),
        }
    )
    delta = {
        "schema_version": "assertion_delta",
        "top_module": "VerifyTop",
        "operation_plan_sha256": canonical_sha256(package["operation_plan"]),
        "operation_ids": [item["operation_id"] for item in plan["operations"]],
        "rtl_properties": [
            {
                "rtl_label": "CL2_MSHR_WAIT_BOUND_0",
                "expected_property_id": "VerifyTop.CL2_MSHR_WAIT_BOUND_0",
            }
        ],
    }
    stage_dir.mkdir(parents=True, exist_ok=True)
    for name, value in (
        ("stage_inputs.json", {"schema_version": "stage_inputs"}),
        ("binding_manifest.json", {"schema_version": "binding_manifest"}),
        ("property_package.json", package),
        ("assertion_delta.json", delta),
        ("render_result.json", {"success": True}),
        ("build_result.json", {"success": True}),
    ):
        (stage_dir / name).write_text(
            json.dumps(value, ensure_ascii=False, sort_keys=True),
            encoding="utf-8",
        )
    (stage_dir / "assertion_diff.patch").write_text("# test fixture\n", encoding="utf-8")
    result = write_stage_outcome(
        stage_dir,
        get_stage_spec("bind_properties"),
        {
            "schema_version": "stage_result",
            "success": True,
            "summary": "test binding completed",
        },
    )
    return package, result


def _result_map(workspace, *, status="proven", trace_decode_contract=None):
    stage_dir = workspace.results_dir / "by_stage" / get_stage_spec("bind_properties").directory_name
    package = json.loads((stage_dir / "property_package.json").read_text(encoding="utf-8"))
    operation = package["operation_plan"]["operations"][0]
    row = {
        "operation_id": operation["operation_id"],
        "status": status,
        "reason": f"test_{status}",
    }
    if status == "cex":
        row["trace_path"] = "traces/cex.fst"
        if trace_decode_contract is not None:
            row["trace_decode_contract"] = trace_decode_contract
    return reduce_property_result_map(
        operation_plan=package["operation_plan"],
        operation_results=[row],
        property_profile_id=PROFILE_ID,
        property_package_sha256=file_sha256(stage_dir / "property_package.json"),
        assertion_delta_sha256=file_sha256(stage_dir / "assertion_delta.json"),
        execution_status_hint="completed" if status != "tool_error" else "tool_error",
    )


def _write_stage3(workspace, property_map):
    stage_dir = workspace.results_dir / "by_stage" / get_stage_spec("invoke_verification").directory_name
    stage_dir.mkdir(parents=True, exist_ok=True)
    map_path = stage_dir / "property_result_map.json"
    map_path.write_text(json.dumps(property_map, sort_keys=True), encoding="utf-8")
    (stage_dir / "semantic_evidence.json").write_text(
        json.dumps(
            build_semantic_evidence(property_map, property_result_map_sha256=file_sha256(map_path)),
            sort_keys=True,
        ),
        encoding="utf-8",
    )
    operations = [
        operation
        for instance in property_map["instances"]
        for operation in instance["operations"]
    ]
    (stage_dir / "proof_events.jsonl").write_text(
        "".join(
            json.dumps(
                {"schema_version": "proof_event", "event": "property_finalized", "sequence": index, **operation},
                sort_keys=True,
            )
            + "\n"
            for index, operation in enumerate(operations)
        ),
        encoding="utf-8",
    )
    (stage_dir / "jaspergold.log").write_text("test deterministic result\n", encoding="utf-8")
    return write_stage_outcome(
        stage_dir,
        get_stage_spec("invoke_verification"),
        {
            "schema_version": "stage_result",
            "success": True,
            "summary": "test deterministic result",
            "execution_status": property_map["execution_status"],
            "formal_outcome": property_map["formal_outcome"],
            "semantic_status": property_map["semantic_status"],
            "experiment_status": property_map["experiment_status"],
            "exclusion_reasons": property_map["exclusion_reasons"],
            "cex_work_items": property_map["cex_work_items"],
        },
    )


def _runner(workspace, *, resumed=False):
    return CoupledL2Runner(
        workspace=workspace,
        logger=logging.getLogger("runner-test"),
        llm_client=None,
        resumed=resumed,
    )


def test_fresh_run_rejects_skipping_the_bind_properties_gate(tmp_path):
    workspace = _workspace(tmp_path)

    with pytest.raises(ValueError, match="fresh run must start at bind_properties"):
        _runner(workspace).run(stage="invoke_verification")


def test_resume_requires_a_successful_bind_properties_handoff(tmp_path, monkeypatch):
    workspace = _workspace(tmp_path)
    runner = _runner(load_coupledl2_workspace(workspace.run_dir), resumed=True)

    with pytest.raises(ValueError, match="successful bind_properties handoff"):
        runner.run(stage="invoke_verification")

    _write_stage2(workspace)

    def fake_verification(_backend):
        return {
            "success": True,
            "summary": "all primary assertions proven",
            "property_result_map": _result_map(workspace),
        }

    monkeypatch.setattr(CoupledL2BuildOperations, "run_full_verification_flow", fake_verification)
    result = _runner(load_coupledl2_workspace(workspace.run_dir), resumed=True).run(
        stage="invoke_verification"
    )

    assert result["success"] is True
    assert result["formal_outcome"] == "all_proven"
    assert result["semantic_status"] == "inconclusive"
    assert result["experiment_status"] == "excluded"


def test_resume_rejects_tampered_indexes_before_running_a_stage(tmp_path):
    workspace = _workspace(tmp_path)
    (workspace.indexes_dir / "formal_surface.json").write_text(
        '{"assertion_count": 999}\n',
        encoding="utf-8",
    )

    with pytest.raises(ValueError, match="index hashes do not match"):
        _runner(load_coupledl2_workspace(workspace.run_dir), resumed=True).run(
            stage="bind_properties"
        )


def test_full_flow_stops_after_formal_completion_without_semantic_pseudo_pass(
    tmp_path, monkeypatch
):
    workspace = _workspace(tmp_path)

    class FakeBindingStage:
        def __init__(self, workspace, *_args, **_kwargs):
            self.workspace = workspace

        def run(self):
            _write_stage2(self.workspace)
            stage_dir = self.workspace.results_dir / "by_stage" / get_stage_spec("bind_properties").directory_name
            result = json.loads((stage_dir / "stage_result.json").read_text(encoding="utf-8"))
            return {"success": True, "stage_result": result}

    def fake_verification(_backend):
        return {
            "success": True,
            "summary": "all primary assertions proven",
            "property_result_map": _result_map(workspace),
        }

    monkeypatch.setattr("src.coupledl2.runner.BindingStage", FakeBindingStage)
    monkeypatch.setattr(CoupledL2BuildOperations, "run_full_verification_flow", fake_verification)

    result = _runner(workspace).run(full=True)

    assert result["success"] is True
    assert result["completed_stage"] == "invoke_verification"
    assert result["formal_outcome"] == "all_proven"
    assert result["semantic_status"] == "inconclusive"
    assert result["experiment_status"] == "excluded"
    assert not (workspace.results_dir / "by_stage" / "02_bind_properties" / "semantic_evidence.json").exists()


def test_refresh_indexes_updates_manifest_and_returns_hashes(tmp_path):
    workspace = _workspace(tmp_path)
    first = refresh_indexes(workspace)
    source = workspace.case_workspace / "Chisel" / "src" / "test" / "scala" / "coupledl2" / "VerifyTop.scala"
    source.write_text("class VerifyTop { assert(true) }\n", encoding="utf-8")

    second = refresh_indexes(workspace)
    manifest = json.loads(workspace.manifest_path.read_text(encoding="utf-8"))

    assert second["workspace_hash"] != first["workspace_hash"]
    assert second["index_hashes"]["formal_surface"] != first["index_hashes"]["formal_surface"]
    assert manifest["workspace_hash"] == second["workspace_hash"]
    assert manifest["index_hashes"] == second["index_hashes"]


def test_cost_summary_records_model_usage_and_stage_termination():
    summary = build_run_cost_summary(
        {
            "llm_calls": 2,
            "llm_total_tokens": 30,
            "llm_prompt_tokens": 20,
            "llm_completion_tokens": 10,
        },
        stage_results=[
            {
                "stage": "bind_properties",
                "termination_reason": "stage_completed",
            }
        ],
    )

    assert summary["llm"]["calls"] == 2
    assert summary["llm"]["total_tokens"] == 30
    assert summary["termination_reasons"] == {"bind_properties": "stage_completed"}


def test_cost_summary_merge_preserves_usage_from_before_resume():
    previous = build_run_cost_summary(
        {"llm_calls": 1, "llm_total_tokens": 20},
        stage_results=[
            {"stage": "bind_properties", "termination_reason": "stage_completed"}
        ],
    )
    current = build_run_cost_summary(
        {"llm_calls": 1, "llm_total_tokens": 10},
        stage_results=[
            {
                "stage": "invoke_verification",
                "termination_reason": "stage_completed",
            }
        ],
    )

    merged = merge_run_cost_summaries(previous, current)

    assert merged["llm"]["calls"] == 2
    assert merged["llm"]["total_tokens"] == 30
    assert merged["termination_reasons"] == {
        "bind_properties": "stage_completed",
        "invoke_verification": "stage_completed",
    }


def test_cli_accepts_resume_run_without_requiring_case(monkeypatch, tmp_path):
    monkeypatch.setattr(
        sys,
        "argv",
        [
            "main.py",
            "run",
            "--resume-run",
            str(tmp_path / "existing-run"),
            "--stage",
            "invoke_verification",
        ],
    )

    args = parse_args()

    assert args.case is None
    assert args.resume_run == str(tmp_path / "existing-run")
