import json
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))


def _make_case(tmp_path):
    case = tmp_path / "XiangShan-CoupledL2-deadlock-v0"
    source = case / "Chisel" / "src" / "main" / "scala" / "coupledL2" / "Slice.scala"
    source.parent.mkdir(parents=True)
    source.write_text(
        """
package coupledL2

class Slice {
  val in = Flipped(TLBundle(edgeIn.bundle))
  val out = TLBundle(edgeOut.bundle)
  val sinkA = Module(new SinkA)
  val grantBuffer = Module(new GrantBuffer)
  sinkA.io.a <> io.in.a
  io.out.d <> grantBuffer.io.d
  val a_fire = io.in.a.valid && io.in.a.ready
  val a_opcode = io.in.a.bits.opcode
  val a_address = io.in.a.bits.address
  val d_fire = io.out.d.valid && io.out.d.ready
  val d_source = io.out.d.bits.source
  val d_sink = io.out.d.bits.sink
}
""".lstrip(),
        encoding="utf-8",
    )
    generated = case / "Chisel" / "generated" / "Generated.scala"
    generated.parent.mkdir(parents=True)
    generated.write_text(
        "class Generated { val bad = io.in.a.bits.address }\n",
        encoding="utf-8",
    )
    return case


def test_tilelink_index_finds_channels_and_excludes_generated_paths(tmp_path):
    from src.coupledl2.config import CoupledL2RunConfig
    from src.coupledl2.indexer import generate_indexes

    case = _make_case(tmp_path)
    indexes = generate_indexes(
        tmp_path / "run",
        case,
        CoupledL2RunConfig(case_path=case, property_profile="mshr_wait_bound_poc"),
    )

    tl_index = indexes["tl_signal_index"]
    observer_index = indexes["observer_index"]
    candidates = tl_index["candidates"]
    paths = json.dumps(candidates)
    roles = {role for candidate in candidates for role in candidate["roles"]}

    assert tl_index["schema_version"] == "tl_signal_index"
    assert observer_index["schema_version"] == "observer_index"
    assert "channel_a" in roles
    assert "channel_d" in roles
    assert "handshake_valid" in roles
    assert "handshake_ready" in roles
    assert "field_opcode" in roles
    assert "field_source" in roles
    assert "field_sink" in roles
    assert "workspace/case/Chisel/generated" not in paths
    assert (tmp_path / "run" / "indexes" / "tl_signal_index.json").is_file()
    assert (tmp_path / "run" / "indexes" / "observer_index.json").is_file()


def test_bind_properties_stage_input_exposes_compact_tilelink_summary(tmp_path):
    from src.coupledl2.config import CoupledL2RunConfig
    from src.coupledl2.indexer import generate_indexes
    from src.coupledl2.workspace import CoupledL2Workspace, initialize_stage_context

    case = _make_case(tmp_path)
    run_dir = tmp_path / "run"
    results_dir = run_dir / "results"
    (results_dir / "preflight").mkdir(parents=True)
    (results_dir / "by_stage" / "02_bind_properties").mkdir(parents=True)
    (results_dir / "preflight" / "preflight_result.json").write_text(
        json.dumps(
            {
                "gate": {
                    "source_assertion_count": 0,
                    "source_boringutils_count": 0,
                    "baseline_build_success": True,
                    "generated_assertion_count": 0,
                }
            }
        ),
        encoding="utf-8",
    )
    config = CoupledL2RunConfig(
        case_path=case,
        property_profile="mshr_wait_bound_poc",
        run_root=tmp_path,
    )
    generate_indexes(run_dir, case, config)
    (run_dir / "manifest.json").write_text(
        json.dumps(
            {
                "workspace_hash": "0" * 64,
                "index_hashes": {
                    path.stem: "0" * 64
                    for path in (run_dir / "indexes").glob("*.json")
                },
            }
        ),
        encoding="utf-8",
    )
    workspace = CoupledL2Workspace(
        run_dir=run_dir,
        workspace_dir=run_dir / "workspace",
        case_workspace=case,
        indexes_dir=run_dir / "indexes",
        logs_dir=run_dir / "logs",
        results_dir=results_dir,
        manifest_path=run_dir / "manifest.json",
        config=config,
    )

    ctx = initialize_stage_context(workspace, "bind_properties")
    summary = ctx.stage_inputs["tilelink_index_summary"]

    assert ctx.stage_inputs["context_indexes"] == [
        "build_contract",
        "formal_surface",
        "observer_index",
        "tl_signal_index",
    ]
    assert summary["tl_signal_candidate_count"] >= 8
    assert summary["observer_candidate_count"] >= 2
    assert summary["module_counts"]["Slice"] >= 8
    assert 1 <= len(summary["top_candidate_ids"]) <= 12
    assert "candidates" not in summary
