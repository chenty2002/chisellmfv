import json
import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))


def test_tilelink_grant_probe_profile_loads_with_protocol_traceability():
    from src.coupledl2.property_catalog import load_property_profile, public_catalog

    catalog = load_property_profile("tl_grant_probe_serialization_poc")
    schema = catalog.schemas["TL_GRANT_PROBE_SERIALIZATION"]
    public = public_catalog(catalog)

    assert schema["source"] == {
        "kind": "protocol_requirement",
        "document": "tilelink_spec_1.8.1",
        "locator": "tilelink_spec_1.8.1.md:1173-1175",
        "statement": (
            "After a slave issues a Grant for a block, it should not issue "
            "Probes on that block until the corresponding GrantAck is received."
        ),
    }
    assert set(catalog.templates) == {
        "chisel3_tl_grant_probe_serialization",
        "chisel3_tl_grant_probe_serialization_formal_assert",
    }
    assert all(
        "if (cacheParams.prefetch.isEmpty)" in template["fragments"]["source_block"]
        for template in catalog.templates.values()
    )
    assert all("fragments" not in template for template in public["templates"])
    assert {
        "api_family",
        "api_primitive",
        "semantic_shape",
        "requires_formal_mixin",
    } <= set(public["templates"][0])


def test_tilelink_profile_marker_survives_formal_surface_cleanup(tmp_path):
    from src.coupledl2.preprocess import prepare_profile_surface
    from src.coupledl2.property_catalog import load_property_profile

    verify_top = (
        tmp_path
        / "Chisel"
        / "src"
        / "test"
        / "scala"
        / "coupledL2"
        / "VerifyTop.scala"
    )
    source_b = (
        tmp_path
        / "Chisel"
        / "src"
        / "main"
        / "scala"
        / "coupledL2"
        / "SourceB.scala"
    )
    verify_top.parent.mkdir(parents=True)
    source_b.parent.mkdir(parents=True)
    verify_top.write_text(
        "class VerifyTop extends Module {\n"
        "  val verify_timer = RegInit(0.U(50.W))\n"
        "  verify_timer := verify_timer + 1.U\n"
        "  assume(verify_timer < 100.U)\n"
        "}\n",
        encoding="utf-8",
    )
    source_b.write_text(
        "class SourceB(implicit p: Parameters) extends L2Module {\n"
        "  noReadyEntry := !issueArb.io.out.valid\n"
        "}\n",
        encoding="utf-8",
    )

    result = prepare_profile_surface(
        tmp_path,
        load_property_profile("tl_grant_probe_serialization_poc"),
    )

    assert result.target_path.read_text(encoding="utf-8").count(
        "// CHISELLMFV_PROPERTY_TL_GRANT_PROBE_SERIALIZATION"
    ) == 1


def test_dynamic_property_profile_list_drives_config_and_argparse_choices():
    import main
    from src.coupledl2.config import CoupledL2RunConfig, list_property_profiles

    profiles = list_property_profiles()
    assert "tl_grant_probe_serialization_poc" in profiles
    assert "write_read_poc" in profiles
    assert "mshr_wait_bound_poc" in profiles

    parser_args = main.parse_args([
        "run",
        "--case",
        str(
            ROOT
            / "CoupledL2-Verification"
            / "code"
            / "CaseStudy_1"
            / "XiangShan-CoupledL2-deadlock-v0"
        ),
        "--property-profile",
        "tl_grant_probe_serialization_poc",
        "--preflight-only",
    ])
    assert parser_args.property_profile == "tl_grant_probe_serialization_poc"

    config = CoupledL2RunConfig(
        case_path=(
            ROOT
            / "CoupledL2-Verification"
            / "code"
            / "CaseStudy_1"
            / "XiangShan-CoupledL2-deadlock-v0"
        ),
        property_profile="tl_grant_probe_serialization_poc",
    )
    assert config.property_profile == "tl_grant_probe_serialization_poc"


def test_stage_inputs_persist_bounded_protocol_evidence(tmp_path):
    from src.coupledl2.config import CoupledL2RunConfig
    from src.coupledl2.stages import get_stage_spec
    from src.coupledl2.workspace import CoupledL2Workspace, initialize_stage_context

    workspace = CoupledL2Workspace(
        run_dir=tmp_path,
        workspace_dir=tmp_path / "workspace",
        case_workspace=tmp_path / "workspace" / "case",
        indexes_dir=tmp_path / "indexes",
        logs_dir=tmp_path / "logs",
        results_dir=tmp_path / "results",
        manifest_path=tmp_path / "manifest.json",
        config=CoupledL2RunConfig(
            case_path=(
                ROOT
                / "CoupledL2-Verification"
                / "code"
                / "CaseStudy_1"
                / "XiangShan-CoupledL2-deadlock-v0"
            ),
            property_profile="tl_grant_probe_serialization_poc",
            run_root=tmp_path,
        ),
    )
    workspace.indexes_dir.mkdir(parents=True)
    workspace.logs_dir.mkdir(parents=True)
    preflight_dir = workspace.results_dir / "preflight"
    preflight_dir.mkdir(parents=True)
    (preflight_dir / "preflight_result.json").write_text(
        json.dumps({"gate": {"baseline_build_success": True}}),
        encoding="utf-8",
    )
    for name in ("build_contract", "formal_surface", "tl_signal_index", "observer_index"):
        (workspace.indexes_dir / f"{name}.json").write_text(
            json.dumps({"schema_version": f"{name}"}),
            encoding="utf-8",
        )
    workspace.manifest_path.write_text(
        json.dumps(
            {
                "workspace_hash": "0" * 64,
                "index_hashes": {
                    name: "0" * 64
                    for name in (
                        "build_contract", "formal_surface",
                        "tl_signal_index", "observer_index",
                    )
                },
            }
        ),
        encoding="utf-8",
    )

    ctx = initialize_stage_context(workspace, "bind_properties")
    stage_inputs = json.loads(
        (
            workspace.results_dir
            / "by_stage"
            / get_stage_spec("bind_properties").directory_name
            / "stage_inputs.json"
        ).read_text(encoding="utf-8")
    )

    assert stage_inputs["protocol_evidence"] == ctx.stage_inputs["protocol_evidence"]
    assert stage_inputs["protocol_evidence"]["rules"][0]["rule_id"] == (
        "TL_9_2_GRANT_PROBE_SERIALIZATION"
    )


def test_protocol_evidence_fails_local_prep_when_locator_is_missing():
    from src.coupledl2.property_catalog import PropertyCatalog, load_property_profile
    from src.coupledl2.workspace import build_protocol_evidence

    base = load_property_profile("tl_grant_probe_serialization_poc")
    schemas = dict(base.schemas)
    schema = dict(schemas["TL_GRANT_PROBE_SERIALIZATION"])
    source = dict(schema["source"])
    source["locator"] = "tilelink_spec_1.8.1.md:1-1"
    schema["source"] = source
    schemas["TL_GRANT_PROBE_SERIALIZATION"] = schema
    broken = PropertyCatalog(
        profile=base.profile,
        schemas=schemas,
        templates=base.templates,
        candidates=base.candidates,
    )

    with pytest.raises(ValueError, match="protocol evidence not found"):
        build_protocol_evidence(broken)
