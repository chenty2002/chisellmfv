import json
import sys
from pathlib import Path
from types import SimpleNamespace

import pytest

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))


def test_assets_encode_source_and_one_to_many_policy():
    from src.coupledl2.property_catalog import load_property_profile

    catalog = load_property_profile("mshr_wait_bound_poc")
    schema = catalog.schemas["CL2_MSHR_WAIT_BOUND"]
    template = catalog.templates["chisel3_mshr_wait_bound"]

    assert schema["source"]["kind"] == "implementation_requirement"
    assert template["rtl_match"]["allow_multiple_occurrences"] is True
    assert "WireDefault(0.U(64.W))" in template["fragments"]["support_block"]
    assert catalog.profile["target"]["relative_path"].endswith("VerifyTop.scala")
    assert catalog.profile["source_targets"][0]["relative_path"].endswith("MSHRCtl.scala")


def test_write_read_preflight_replaces_old_monitor_with_marker(tmp_path):
    from src.coupledl2.preprocess import prepare_profile_surface
    from src.coupledl2.property_catalog import load_property_profile

    target = (
        tmp_path
        / "Chisel"
        / "src"
        / "test"
        / "scala"
        / "coupledL2Verification"
        / "VerifyTop.scala"
    )
    target.parent.mkdir(parents=True)
    target.write_text(
        """
class VerifyTop {
  // Keep only write_read consistency assertions in this version.
  val data_p1 = RegInit(0.U(256.W))
  val data_p2 = RegInit(0.U(256.W))
  val valid = RegInit(false.B)
  coupledL2.foreach { l2 =>
    val d_GrantData_valid = BoringUtils.bore(l2.io.valid)
    fvAssert(!d_GrantData_valid)
  }
}
""".lstrip(),
        encoding="utf-8",
    )

    result = prepare_profile_surface(
        tmp_path,
        load_property_profile("write_read_poc"),
    )
    text = result.target_path.read_text(encoding="utf-8")

    assert "BoringUtils.bore" not in text
    assert text.count("// CHISELLMFV_PROPERTY_WRITE_READ") == 1
    assert "d_GrantData_valid" not in text
    assert "data_p1 = RegInit(0.U(256.W))" not in text
    assert "data_p2 = RegInit(0.U(256.W))" not in text
    assert "valid = RegInit(false.B)" not in text


def test_formal_library_definition_survives_instance_cleanup(tmp_path):
    from src.coupledl2.preprocess import clean_formal_surface

    library = (
        tmp_path
        / "Chisel"
        / "src"
        / "main"
        / "scala"
        / "chiselFv"
        / "Formal.scala"
    )
    library.parent.mkdir(parents=True)
    library.write_text(
        "trait Formal {\n"
        "  def fvAssert(cond: Bool, msg: String = \"\") = {\n"
        "    when(notChaos) { AssertProperty(cond, msg) }\n"
        "  }\n"
        "}\n",
        encoding="utf-8",
    )
    harness = (
        tmp_path
        / "Chisel"
        / "src"
        / "test"
        / "scala"
        / "coupledL2Verification"
        / "VerifyTop.scala"
    )
    harness.parent.mkdir(parents=True)
    harness.write_text(
        "class VerifyTop extends Module with Formal {\n"
        "  fvAssert(io.valid, \"old\")\n"
        "}\n",
        encoding="utf-8",
    )

    result = clean_formal_surface(tmp_path)

    assert result["success"] is True
    assert "AssertProperty(cond, msg)" in library.read_text(encoding="utf-8")
    assert "fvAssert(io.valid" in harness.read_text(encoding="utf-8")
    assert result["policy"] == "profile_owned_generated_regions_only"


def test_build_contract_uses_raw_chisel_rtl_as_property_fact_source():
    from src.coupledl2.config import CoupledL2RunConfig
    from src.coupledl2.indexer import build_build_contract

    case = (
        ROOT
        / "CoupledL2-Verification"
        / "code"
        / "CaseStudy_1"
        / "XiangShan-CoupledL2-write_read"
    )
    config = CoupledL2RunConfig(
        case_path=case,
        property_profile="write_read_poc",
    )

    contract = build_build_contract(case, config)

    assert contract["generated_verilog_globs"] == [
        "workspace/case/Chisel/Verilog/**/VerifyTop*.sv"
    ]


def test_backend_discovers_verifytop_sidecar_verilog_inputs(tmp_path):
    from src.coupledl2.backend import CoupledL2BuildOperations

    output_dir = tmp_path / "Chisel" / "Verilog" / "L2L3L2"
    output_dir.mkdir(parents=True)
    for name in ("VerifyTop.sv", "LogPerfHelper.v", "TLLogWriter.v", "STD_CLKGT_func.v"):
        (output_dir / name).write_text(f"module {Path(name).stem}; endmodule\n", encoding="utf-8")

    backend = object.__new__(CoupledL2BuildOperations)
    backend.case_dir = tmp_path
    backend._build_contract = {
        "generated_verilog_globs": ["workspace/case/Chisel/Verilog/**/VerifyTop*.sv"]
    }

    discovered = [path.name for path in backend.discover_generated_verilog_files()]

    assert discovered == [
        "LogPerfHelper.v",
        "STD_CLKGT_func.v",
        "TLLogWriter.v",
        "VerifyTop.sv",
    ]


def _valid_mshr_manifest():
    return {
        "schema_version": "binding_manifest",
        "property_profile_id": "mshr_wait_bound_poc",
        "instances": [
            {
                "instance_id": "cl2_mshr_wait_bound_0",
                "property_schema_id": "CL2_MSHR_WAIT_BOUND",
                "template_id": "chisel3_mshr_wait_bound",
                "target": {
                    "file_id": "verify_top",
                    "marker_id": "mshr_wait_bound_assertion_region",
                },
                "bindings": {"timer": "sig_mshr_wait_timer"},
                "parameters": {"bound": 64},
                "base_label": "CL2_MSHR_WAIT_BOUND_0",
                "evidence": [{"candidate_id": "sig_mshr_wait_timer"}],
            }
        ],
    }


def _valid_write_read_manifest():
    return {
        "schema_version": "binding_manifest",
        "property_profile_id": "write_read_poc",
        "instances": [
            {
                "instance_id": "cl2_write_read_regression_0",
                "property_schema_id": "CL2_WRITE_READ_REGRESSION",
                "template_id": "chisel6_write_read_regression",
                "target": {
                    "file_id": "verify_top",
                    "marker_id": "write_read_monitor_region",
                },
                "bindings": {
                    "trigger": "sig_wr_trigger",
                    "selector": "sig_wr_selector",
                    "observed_data": "sig_wr_observed_data",
                    "expected_data_true": "sig_wr_expected_true",
                    "expected_data_false": "sig_wr_expected_false",
                    "tracking_valid": "sig_wr_tracking_valid",
                },
                "parameters": {},
                "base_label": "CL2_WRITE_READ_REGRESSION_0",
                "evidence": [{"candidate_id": "sig_wr_trigger"}],
            }
        ],
    }


def _mshr_generated_rtl(base_label="CL2_MSHR_WAIT_BOUND_0"):
    annotation = "// @[src/test/scala/coupledL2/VerifyTop.scala 1:1]\n"
    covers = (
        ("trigger_cover", "mshr_wait"),
        ("observer_cover", "timer__nonzero"),
        ("state_cover", "wait_counter__nonzero"),
        ("assumption_sat", "environment"),
    )
    return (
        "assert (timer <= 64); " + annotation
        + "".join(
            f"{base_label}__NV__{role}__{target}: cover (timer != 0); {annotation}"
            for role, target in covers
        )
    )


def test_manifest_cannot_contain_generated_code():
    from src.coupledl2.binding_contract import (
        BindingContractError,
        validate_binding_manifest,
    )
    from src.coupledl2.property_catalog import load_property_profile

    payload = _valid_mshr_manifest()
    payload["instances"][0]["source_code"] = "assert(false.B)"

    with pytest.raises(BindingContractError, match="unknown fields"):
        validate_binding_manifest(
            payload,
            load_property_profile("mshr_wait_bound_poc"),
        )


def test_manifest_tool_constrains_instance_id_to_validator_pattern():
    from src.coupledl2.binding_contract import binding_manifest_tool
    from src.coupledl2.property_catalog import load_property_profile

    tool = binding_manifest_tool(load_property_profile("write_read_poc"))
    instance = tool["parameters"]["properties"]["instances"]["items"]

    assert instance["properties"]["instance_id"]["pattern"] == r"^[a-z0-9_]{1,96}$"
    assert "at most four" in instance["properties"]["evidence"]["description"]


def test_rtl_labeler_expands_one_source_property_to_unique_labels(tmp_path):
    from src.coupledl2.property_catalog import load_property_profile
    from src.coupledl2.rtl_property_labeler import label_rtl_properties

    rtl = tmp_path / "VerifyTop.sv"
    rtl.write_text(
        "".join(
            "assert (timer <= 64); "
            f"// @[src/test/scala/coupledL2/VerifyTop.scala 223:{index}]\n"
            for index in range(4)
        ),
        encoding="utf-8",
    )

    result = label_rtl_properties(
        [rtl],
        _valid_mshr_manifest(),
        load_property_profile("mshr_wait_bound_poc"),
    )

    assert [item.rtl_label for item in result] == [
        "CL2_MSHR_WAIT_BOUND_0__E0",
        "CL2_MSHR_WAIT_BOUND_0__E1",
        "CL2_MSHR_WAIT_BOUND_0__E2",
        "CL2_MSHR_WAIT_BOUND_0__E3",
    ]


def test_rtl_labeler_replaces_multiline_chisel6_generated_labels(tmp_path):
    from src.coupledl2.property_catalog import load_property_profile
    from src.coupledl2.rtl_property_labeler import label_rtl_properties

    rtl = tmp_path / "VerifyTop_write_read.sv"
    rtl.write_text(
        "".join(
            f"  _GEN_{index}:\n"
            "    assert property (@(posedge clock) disable iff (reset)\n"
            "                     observed == expected);"
            " // src/test/scala/coupledL2Verification/VerifyTop.scala:302:13\n"
            for index in range(2)
        ),
        encoding="utf-8",
    )

    result = label_rtl_properties(
        [rtl],
        _valid_write_read_manifest(),
        load_property_profile("write_read_poc"),
    )

    assert [item.rtl_label for item in result] == [
        "CL2_WRITE_READ_REGRESSION_0__E0",
        "CL2_WRITE_READ_REGRESSION_0__E1",
    ]
    text = rtl.read_text(encoding="utf-8")
    assert "_GEN_0:" not in text
    assert "_GEN_1:" not in text


def test_rtl_labeler_replaces_chisel6_base_label_expansions(tmp_path):
    from src.coupledl2.property_catalog import load_property_profile
    from src.coupledl2.rtl_property_labeler import label_rtl_properties

    rtl = tmp_path / "VerifyTop_write_read.sv"
    rtl.write_text(
        "  CL2_WRITE_READ_REGRESSION_0:\n"
        "    assert property (@(posedge clock) observed); "
        "// src/test/scala/coupledL2Verification/VerifyTop.scala:302:13\n"
        "  CL2_WRITE_READ_REGRESSION_0_1:\n"
        "    assert property (@(posedge clock) observed_1); "
        "// src/test/scala/coupledL2Verification/VerifyTop.scala:302:13\n",
        encoding="utf-8",
    )

    result = label_rtl_properties(
        [rtl],
        _valid_write_read_manifest(),
        load_property_profile("write_read_poc"),
    )

    assert len(result) == 2
    text = rtl.read_text(encoding="utf-8")
    assert "CL2_WRITE_READ_REGRESSION_0__E0:" in text
    assert "CL2_WRITE_READ_REGRESSION_0__E1:" in text


def test_renderer_rejects_undeclared_placeholder_without_editing_source(tmp_path):
    from src.coupledl2.assertion_renderer import (
        AssertionRenderError,
        render_property_source,
    )
    from src.coupledl2.property_catalog import (
        PropertyCatalog,
        load_property_profile,
    )

    case = tmp_path / "workspace" / "case"
    target = case / "Chisel" / "src" / "test" / "scala" / "coupledL2" / "VerifyTop.scala"
    source = case / "Chisel" / "src" / "main" / "scala" / "coupledL2" / "MSHRCtl.scala"
    target.parent.mkdir(parents=True)
    source.parent.mkdir(parents=True)
    original = (
        "lazy val module = new LazyModuleImp(this) {\n"
        "  // CHISELLMFV_PROPERTY_MSHR_WAIT_BOUND_ASSERT\n"
        "}\n"
    )
    target.write_text(original, encoding="utf-8")
    source.write_text(
        "when(m.io.status.bits.channel === 1.U) {\n"
        "  // CHISELLMFV_PROPERTY_MSHR_WAIT_BOUND_SOURCE\n"
        "}\n",
        encoding="utf-8",
    )
    base = load_property_profile("mshr_wait_bound_poc")
    templates = dict(base.templates)
    template = dict(templates["chisel3_mshr_wait_bound"])
    fragments = dict(template["fragments"])
    fragments["assertion_block"] += "\n{{invented_signal}}"
    template["fragments"] = fragments
    templates[template["template_id"]] = template
    broken = PropertyCatalog(
        profile=base.profile,
        schemas=base.schemas,
        templates=templates,
        candidates=base.candidates,
    )

    with pytest.raises(AssertionRenderError, match="undeclared placeholder"):
        render_property_source(target, _valid_mshr_manifest(), broken)

    assert target.read_text(encoding="utf-8") == original


def test_write_read_renderer_restores_repository_owned_formal_mixin(tmp_path):
    from src.coupledl2.assertion_renderer import render_property_source
    from src.coupledl2.property_catalog import load_property_profile

    target = tmp_path / "VerifyTop.scala"
    target.write_text(
        "lazy val module = new LazyModuleImp(this) {\n"
        "  // CHISELLMFV_PROPERTY_WRITE_READ\n"
        "}\n",
        encoding="utf-8",
    )

    render_property_source(
        target,
        _valid_write_read_manifest(),
        load_property_profile("write_read_poc"),
    )
    text = target.read_text(encoding="utf-8")

    assert text.count("new LazyModuleImp(this) with Formal {") == 1


def test_renderer_uses_template_metadata_for_formal_mixin(tmp_path):
    from src.coupledl2.assertion_renderer import render_property_source
    from src.coupledl2.property_catalog import load_property_profile

    target = (
        tmp_path
        / "Chisel"
        / "src"
        / "test"
        / "scala"
        / "coupledL2"
        / "VerifyTop.scala"
    )
    source = tmp_path / "Chisel" / "src" / "main" / "scala" / "coupledL2" / "SourceB.scala"
    target.parent.mkdir(parents=True)
    source.parent.mkdir(parents=True)
    target.write_text(
        "lazy val module = new LazyModuleImp(this) {\n"
        "  // CHISELLMFV_PROPERTY_TL_GRANT_PROBE_SERIALIZATION\n"
        "}\n",
        encoding="utf-8",
    )
    source.write_text(
        "class SourceB(implicit p: Parameters) extends L2Module {\n"
        "  val conflict = WireDefault(false.B)\n"
        "  val issueArb = Module(new FastArbiter(new SourceBReq, 4))\n"
        "  noReadyEntry := !issueArb.io.out.valid\n"
        "  // CHISELLMFV_PROPERTY_TL_GRANT_PROBE_SERIALIZATION_SOURCE\n"
        "}\n",
        encoding="utf-8",
    )
    manifest = {
        "schema_version": "binding_manifest",
        "property_profile_id": "tl_grant_probe_serialization_poc",
        "instances": [
            {
                "instance_id": "tl_grant_probe_serialization_0",
                "property_schema_id": "TL_GRANT_PROBE_SERIALIZATION",
                "template_id": "chisel3_tl_grant_probe_serialization",
                "target": {
                    "file_id": "verify_top",
                    "marker_id": "tl_grant_probe_serialization_region",
                },
                "bindings": {
                    "grant_pending": "sig_tl_grant_pending",
                    "probe_fire": "sig_tl_probe_fire",
                },
                "parameters": {},
                "base_label": "TL_GRANT_PROBE_SERIALIZATION_0",
                "evidence": [{"candidate_id": "sig_tl_grant_pending"}],
            }
        ],
    }

    render_property_source(
        target,
        manifest,
        load_property_profile("tl_grant_probe_serialization_poc"),
    )

    text = target.read_text(encoding="utf-8")
    assert text.count("new LazyModuleImp(this) with Formal {") == 1
    assert "assert(!(" in text


def test_binding_messages_include_bounded_protocol_evidence():
    from src.coupledl2.binding_stage import BindingStage

    stage = object.__new__(BindingStage)
    stage.catalog = __import__(
        "src.coupledl2.property_catalog",
        fromlist=["load_property_profile"],
    ).load_property_profile("tl_grant_probe_serialization_poc")
    from src.coupledl2.property_catalog import public_catalog
    from src.coupledl2.workspace import build_protocol_evidence

    stage.stage_context = SimpleNamespace(
        stage_inputs={
            "schema_version": "stage_inputs",
            "property_catalog": public_catalog(stage.catalog),
            "protocol_evidence": build_protocol_evidence(stage.catalog),
        }
    )

    messages = stage._binding_messages()
    payload = json.loads(messages[1]["content"])

    assert "at most one template instance for each property schema" in messages[0]["content"]
    assert "Every instance_id and base_label must be globally unique" in messages[0]["content"]
    assert "protocol_evidence" in payload
    evidence = payload["protocol_evidence"]["rules"][0]
    assert evidence == {
        "rule_id": "TL_9_2_GRANT_PROBE_SERIALIZATION",
        "locator": "tilelink_spec_1.8.1.md:1173-1175",
        "statement": (
            "After a slave issues a Grant for a block, it should not issue "
            "Probes on that block until the corresponding GrantAck is received."
        ),
        "source_sha256": "7a87c21b115a52e90bc079a20a8cc8c2f8345a61d082320a36828de96df24d9f",
    }
    assert "fragments" not in messages[1]["content"]
    assert len(evidence["statement"]) < 220


def _binding_workspace(tmp_path):
    mshr = (
        tmp_path
        / "workspace"
        / "case"
        / "Chisel"
        / "src"
        / "main"
        / "scala"
        / "coupledL2"
        / "MSHRCtl.scala"
    )
    verify_top = (
        tmp_path
        / "workspace"
        / "case"
        / "Chisel"
        / "src"
        / "test"
        / "scala"
        / "coupledL2"
        / "VerifyTop.scala"
    )
    mshr.parent.mkdir(parents=True)
    verify_top.parent.mkdir(parents=True)
    mshr.write_text(
        "for (((timer, m), i) <- timers.zip(mshrs).zipWithIndex) {\n"
        "  when(m.io.status.bits.channel === 1.U) {\n"
        "    // CHISELLMFV_PROPERTY_MSHR_WAIT_BOUND_SOURCE\n"
        "  }\n"
        "}\n",
        encoding="utf-8",
    )
    verify_top.write_text(
        "lazy val module = new LazyModuleImp(this) {\n"
        "  val verify_timer = RegInit(0.U(50.W))\n"
        "  verify_timer := verify_timer + 1.U\n"
        "  // CHISELLMFV_PROPERTY_MSHR_WAIT_BOUND_ASSERT\n"
        "}\n",
        encoding="utf-8",
    )
    return (
        SimpleNamespace(
            run_dir=tmp_path,
            case_workspace=tmp_path / "workspace" / "case",
            results_dir=tmp_path / "results",
            config=SimpleNamespace(property_profile="mshr_wait_bound_poc"),
        ),
        verify_top,
    )


def _binding_context(workspace):
    from src.coupledl2.property_catalog import load_property_profile, public_catalog
    from src.coupledl2.workspace import StageContext, build_protocol_evidence

    stage_dir = workspace.results_dir / "by_stage" / "02_bind_properties"
    stage_dir.mkdir(parents=True, exist_ok=True)
    catalog = load_property_profile("mshr_wait_bound_poc")
    stage_inputs = {
        "schema_version": "stage_inputs",
        "stage": "bind_properties",
        "property_catalog": public_catalog(catalog),
        "protocol_evidence": build_protocol_evidence(catalog),
    }
    (stage_dir / "stage_inputs.json").write_text(
        json.dumps(stage_inputs), encoding="utf-8"
    )
    return StageContext(
        stage="bind_properties",
        stage_dir=stage_dir,
        snapshot_dir=stage_dir / "source_snapshot",
        context_indexes={},
        stage_inputs=stage_inputs,
        binding_catalog=catalog,
    )


def test_binding_stage_rolls_back_source_after_failed_build(tmp_path):
    from src.coupledl2.binding_stage import BindingStage

    workspace, target = _binding_workspace(tmp_path)
    original = target.read_text(encoding="utf-8")
    source = (
        workspace.case_workspace
        / "Chisel"
        / "src"
        / "main"
        / "scala"
        / "coupledL2"
        / "MSHRCtl.scala"
    )
    original_source = source.read_text(encoding="utf-8")

    class LLM:
        def chat_with_tools(self, **_kwargs):
            return {
                "type": "function_calls",
                "function_calls": [
                    {
                        "id": "manifest",
                        "name": "submit_binding_manifest",
                        "arguments": _valid_mshr_manifest(),
                    }
                ],
            }

    class Backend:
        def discover_generated_verilog_files(self):
            return []

        def verify_compilation(self, require_assertions=False):
            return {"success": False, "error": "compile failed"}

    result = BindingStage(
        workspace, Backend(), LLM(), logger=None,
        stage_context=_binding_context(workspace),
    ).run()

    assert result["success"] is False
    assert target.read_text(encoding="utf-8") == original
    assert source.read_text(encoding="utf-8") == original_source


def test_property_result_map_preserves_one_to_many_jg_ids():
    from src.coupledl2.backend import join_property_results
    from src.coupledl2.result_contract import build_primary_operation_plan

    traceability = {
        "schema_version": "assertion_traceability",
        "properties": [
            {
                "instance_id": "cl2_mshr_wait_bound_0",
                "property_schema_id": "CL2_MSHR_WAIT_BOUND",
                "template_id": "chisel3_mshr_wait_bound",
                "base_label": "CL2_MSHR_WAIT_BOUND_0",
                "binding_manifest_path": "binding_manifest.json",
                "source": {
                    "kind": "protocol_requirement",
                    "document": "tilelink_spec_1.8.1",
                    "locator": "tilelink_spec_1.8.1.md:1173-1175",
                    "statement": "Grant and Probe are serialized.",
                },
                "protocol_rule": {
                    "rule_id": "TL_9_2_GRANT_PROBE_SERIALIZATION",
                    "locator": "tilelink_spec_1.8.1.md:1173-1175",
                },
                "rtl_properties": [
                    {
                        "rtl_label": f"CL2_MSHR_WAIT_BOUND_0__E{index}",
                        "expected_property_id": f"VerifyTop.CL2_MSHR_WAIT_BOUND_0__E{index}",
                    }
                    for index in range(4)
                ],
            }
        ],
    }
    plan = build_primary_operation_plan(traceability, package_sha256="0" * 64)
    operation_results = [
        {
            "operation_id": operation["operation_id"],
            "observed_property_id": f"VerifyTop.CL2_MSHR_WAIT_BOUND_0__E{index}",
            "status": "cex",
            "reason": "tool_reported_cex",
            "trace_path": "traces/trace.fst",
        }
        for index, operation in enumerate(plan["operations"])
    ]
    report = {
        "operation_results": operation_results,
        "auxiliary_results": [],
        "execution_status": "completed",
        "formal_outcome": "cex",
    }

    result = join_property_results(
        traceability,
        report,
        operation_plan=plan,
        property_profile_id="mshr_wait_bound_poc",
        property_package_sha256="1" * 64,
        assertion_delta_sha256="2" * 64,
    )

    assert result["schema_version"] == "property_result_map"
    assert len(result["instances"][0]["operations"]) == 4
    assert all(
        item["rtl_property_id"].startswith("VerifyTop.CL2_MSHR_WAIT_BOUND_0__E")
        for item in result["instances"][0]["operations"]
    )
    assert result["instances"][0]["refs"]["binding_manifest_path"] == "binding_manifest.json"
    assert result["instances"][0]["refs"]["protocol_rule"]["rule_id"] == (
        "TL_9_2_GRANT_PROBE_SERIALIZATION"
    )


def test_property_result_map_preserves_chiselfv_auxiliary_properties():
    from src.coupledl2.backend import join_property_results
    from src.coupledl2.result_contract import build_primary_operation_plan

    label = "TL_GRANT_PROBE_SERIALIZATION__E0"
    traceability = {
        "properties": [
            {
                "instance_id": "tl_grant_probe_serialization",
                "property_schema_id": "TL_GRANT_PROBE_SERIALIZATION",
                "template_id": "chisel3_tl_grant_probe_serialization_formal_assert",
                "base_label": "TL_GRANT_PROBE_SERIALIZATION",
                "rtl_properties": [{"rtl_label": label, "expected_property_id": f"VerifyTop.{label}"}],
            }
        ]
    }
    plan = build_primary_operation_plan(traceability, package_sha256="0" * 64)
    report = {
        "operation_results": [
            {
                "operation_id": plan["operations"][0]["operation_id"],
                "observed_property_id": f"VerifyTop.{label}",
                "status": "proven",
                "reason": "tool_reported_proven",
            }
        ],
        "auxiliary_results": [
            {
                "observed_property_id": f"VerifyTop.{label}:precondition1",
                "status": "proven",
            }
        ],
        "execution_status": "completed",
        "formal_outcome": "all_proven",
    }

    result = join_property_results(
        traceability,
        report,
        operation_plan=plan,
        property_profile_id="tl_grant_probe_serialization_poc",
        property_package_sha256="1" * 64,
        assertion_delta_sha256="2" * 64,
    )

    assert len(result["instances"][0]["operations"]) == 1
    assert result["unmatched_tool_results"][0]["observed_property_id"].endswith(
        ":precondition1"
    )


def test_stage3_accounts_tool_failure_and_still_writes_result_map(tmp_path):
    from src.coupledl2.backend import CoupledL2BuildOperations
    from src.coupledl2.result_contract import (
        bind_operation_plan_to_package,
        build_primary_operation_plan,
        build_unmaterialized_observation_map,
    )

    workspace = SimpleNamespace(
        results_dir=tmp_path / "results",
        config=SimpleNamespace(property_profile="mshr_wait_bound_poc"),
    )
    stage2_dir = workspace.results_dir / "by_stage" / "02_bind_properties"
    stage2_dir.mkdir(parents=True)
    manifest = _valid_mshr_manifest()
    (stage2_dir / "binding_manifest.json").write_text(
        json.dumps(manifest),
        encoding="utf-8",
    )
    traceability = {
        "schema_version": "assertion_traceability",
        "properties": [
            {
                "instance_id": "cl2_mshr_wait_bound_0",
                "property_schema_id": "CL2_MSHR_WAIT_BOUND",
                "template_id": "chisel3_mshr_wait_bound",
                "base_label": "CL2_MSHR_WAIT_BOUND_0",
                "rtl_properties": [
                    {
                        "rtl_label": "CL2_MSHR_WAIT_BOUND_0__E0",
                        "expected_property_id": "VerifyTop.CL2_MSHR_WAIT_BOUND_0__E0",
                    }
                ],
            }
        ],
    }
    operation_plan = build_primary_operation_plan(traceability, package_sha256="0" * 64)
    (stage2_dir / "property_package.json").write_text(
        json.dumps(
            bind_operation_plan_to_package({
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
            })
        ),
        encoding="utf-8",
    )
    (stage2_dir / "assertion_delta.json").write_text(
        json.dumps(
            {
                "schema_version": "assertion_delta",
                "top_module": "VerifyTop",
                "rtl_properties": [
                    {
                        "rtl_label": "CL2_MSHR_WAIT_BOUND_0__E0",
                        "expected_property_id": (
                            "VerifyTop.CL2_MSHR_WAIT_BOUND_0__E0"
                        ),
                    }
                ],
            }
        ),
        encoding="utf-8",
    )
    rtl = tmp_path / "VerifyTop.sv"
    rtl.write_text(
        "module VerifyTop;\n"
        "  assert (timer <= 64); // @[src/test/scala/coupledL2/VerifyTop.scala 1:1]\n"
        "endmodule\n",
        encoding="utf-8",
    )

    backend = object.__new__(CoupledL2BuildOperations)
    backend.workspace = workspace
    backend._run_build = lambda _stage_dir: {
        "success": True,
        "generated_files": [str(rtl)],
        "top_module": "VerifyTop",
    }
    backend.prepare_verification_inputs = lambda top_module=None: {"success": True}
    backend.run_jaspergold = lambda: {
        "success": False,
        "summary": "JasperGold failed before producing property statuses",
        "output": "killed",
        "execution_status": "tool_error",
        "formal_outcome": "inconclusive",
        "accounted_count": 1,
        "expected_count": 1,
        "jaspergold_result": {
            "property_statuses": {},
            "operation_results": [
                {
                    "operation_id": operation_plan["operations"][0]["operation_id"],
                    "observed_property_id": None,
                    "status": "tool_error",
                    "reason": "jaspergold_exit_-9",
                }
            ],
            "auxiliary_results": [],
            "execution_status": "tool_error",
            "formal_outcome": "inconclusive",
            "expected_count": 1,
            "accounted_count": 1,
            "returncode": -9,
        },
    }

    result = backend.run_full_verification_flow()

    assert result["success"] is False
    assert result["summary"] == "JasperGold failed before producing property statuses"
    assert result["property_result_map"]["schema_version"] == "property_result_map"
    assert result["property_result_map"]["accounted_operation_count"] == 1
    assert result["property_result_map"]["execution_status"] == "tool_error"
    assert (
        workspace.results_dir
        / "by_stage"
        / "03_invoke_verification"
        / "property_result_map.json"
    ).is_file()


def test_jaspergold_input_truncates_circt_resource_file_list(tmp_path):
    from src.coupledl2.backend import materialize_jaspergold_input

    source = tmp_path / "VerifyTop.sv"
    destination = tmp_path / "prepared" / "VerifyTop.sv"
    source.write_text(
        "module VerifyTop;\n"
        "  CL2_TEST__E0: assert (1'b1);\n"
        "endmodule\n"
        '// ----- 8< ----- FILE "firrtl_black_box_resource_files.f" ----- 8< -----\n'
        "ClockGate.v\nResetCounter.sv\n",
        encoding="utf-8",
    )

    materialize_jaspergold_input(source, destination)

    text = destination.read_text(encoding="utf-8")
    assert "CL2_TEST__E0" in text
    assert "firrtl_black_box_resource_files.f" not in text
    assert "ClockGate.v" not in text


def test_jaspergold_input_defaults_uninitialized_string_parameters(tmp_path):
    from src.coupledl2.backend import materialize_jaspergold_input

    source = tmp_path / "TLLogWriter.v"
    destination = tmp_path / "prepared" / "TLLogWriter.v"
    source.write_text(
        "module TLLogWriter;\n"
        "  parameter string site;\n"
        "endmodule\n",
        encoding="utf-8",
    )

    materialize_jaspergold_input(source, destination)

    assert 'parameter string site = "";' in destination.read_text(encoding="utf-8")


def test_non_design_diagnosis_does_not_allow_stage5():
    from src.coupledl2.runner import diagnoses_allow_bugfix

    assert diagnoses_allow_bugfix(
        [{"classification": "design_bug"}]
    )
    assert not diagnoses_allow_bugfix(
        [
            {"classification": "design_bug"},
            {"classification": "binding_error"},
        ]
    )
    assert not diagnoses_allow_bugfix([])
