from pathlib import Path
import sys


ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))


from src.core.artifact_contract import file_sha256  # noqa: E402
from src.core.formal_operations import canonical_sha256  # noqa: E402
from src.chiselspecflow.causal_source_projection import (  # noqa: E402
    project_causal_sources,
)


def _edge(snippet, artifact_id="rtl_0001"):
    return {
        "edge_id": "vce_exact",
        "src_node_id": "vcn_src",
        "dst_node_id": "vcn_dst",
        "dependency_type": "combinational",
        "identity_strength": "exact",
        "evidence_strength": "toggle_supported",
        "contribution_score": 0.8,
        "reason_code": "source_and_target_toggled",
        "rtl_evidence": {
            "artifact_id": artifact_id,
            "line_start": 1,
            "line_end": 1,
            "snippet": snippet,
            "snippet_sha256": canonical_sha256(snippet),
            "expression": "y = x",
            "condition": "",
        },
        "change_examples": [],
    }


def test_rtl_locator_exactly_projects_to_hash_bound_chisel_anchor(tmp_path):
    project = tmp_path / "project"
    source = project / "src/main/scala/Foo.scala"
    source.parent.mkdir(parents=True)
    source.write_text("line1\nline2\nval result = Wire(Bool())\n")
    rtl = tmp_path / "Top.sv"
    snippet = "assign y = x; // src/main/scala/Foo.scala:3:1"
    rtl.write_text(snippet + "\n")
    graph = {
        "graph_id": "vcg_exact",
        "status": "complete",
        "edges": [_edge(snippet)],
    }
    semantic = {
        "objects": [
            {
                "object_id": "obj_result",
                "source_anchor": {
                    "path": "src/main/scala/Foo.scala",
                    "line_start": 3,
                    "line_end": 3,
                    "source_sha256": file_sha256(source),
                    "enclosing_symbol": "Foo",
                },
            }
        ]
    }

    result = project_causal_sources(
        [graph],
        rtl_files=[
            {
                "artifact_id": "rtl_0001",
                "path": str(rtl),
                "sha256": file_sha256(rtl),
                "bytes": rtl.stat().st_size,
            }
        ],
        semantic_index=semantic,
        project_root=project,
        round_id=1,
    )

    row = result["graphs"][0]["edge_rows"][0]
    assert row["projection_status"] == "exact"
    assert row["chisel_source_anchor"]["path"] == "src/main/scala/Foo.scala"
    assert row["semantic_object_ids"] == ["obj_result"]
    assert result["source_candidates"][0]["candidate_id"].startswith("sc_")


def test_missing_locator_remains_rtl_only(tmp_path):
    project = tmp_path / "project"
    project.mkdir()
    rtl = tmp_path / "Top.sv"
    snippet = "assign y = x;"
    rtl.write_text(snippet + "\n")
    result = project_causal_sources(
        [
            {
                "graph_id": "vcg_rtl_only",
                "status": "complete",
                "edges": [_edge(snippet)],
            }
        ],
        rtl_files=[
            {
                "artifact_id": "rtl_0001",
                "path": str(rtl),
                "sha256": file_sha256(rtl),
                "bytes": rtl.stat().st_size,
            }
        ],
        semantic_index={"objects": []},
        project_root=project,
        round_id=1,
    )
    assert (
        result["graphs"][0]["edge_rows"][0]["projection_status"]
        == "rtl_only"
    )
    assert result["source_candidates"] == []


def test_locator_without_semantic_object_overlap_remains_rtl_only(
    tmp_path,
):
    project = tmp_path / "project"
    source = project / "src/main/scala/Foo.scala"
    source.parent.mkdir(parents=True)
    source.write_text("class Foo\nval state = RegInit(0.U)\nstate := next\n")
    rtl = tmp_path / "Top.sv"
    snippet = "stateReg <= next; // src/main/scala/Foo.scala:3:1"
    rtl.write_text(snippet + "\n")
    semantic = {
        "objects": [
            {
                "object_id": "obj_state",
                "source_anchor": {
                    "path": "src/main/scala/Foo.scala",
                    "line_start": 2,
                    "line_end": 2,
                    "source_sha256": file_sha256(source),
                    "enclosing_symbol": "Foo",
                },
            }
        ]
    }
    result = project_causal_sources(
        [{"graph_id": "vcg_file", "status": "complete", "edges": [_edge(snippet)]}],
        rtl_files=[
            {
                "artifact_id": "rtl_0001",
                "path": str(rtl),
                "sha256": file_sha256(rtl),
                "bytes": rtl.stat().st_size,
            }
        ],
        semantic_index=semantic,
        project_root=project,
        round_id=1,
    )
    row = result["graphs"][0]["edge_rows"][0]
    assert row["projection_status"] == "rtl_only"
    assert row["chisel_source_anchor"] is None


def test_source_hash_drift_rejects_semantic_anchor(tmp_path):
    project = tmp_path / "project"
    source = project / "src/main/scala/Foo.scala"
    source.parent.mkdir(parents=True)
    source.write_text("class Foo\nval state = RegInit(0.U)\nstate := next\n")
    frozen_hash = file_sha256(source)
    source.write_text("class Foo\nval state = RegInit(1.U)\nstate := other\n")
    rtl = tmp_path / "Top.sv"
    snippet = "stateReg <= next; // src/main/scala/Foo.scala:3:1"
    rtl.write_text(snippet + "\n")
    result = project_causal_sources(
        [{"graph_id": "vcg_drift", "status": "complete", "edges": [_edge(snippet)]}],
        rtl_files=[
            {
                "artifact_id": "rtl_0001",
                "path": str(rtl),
                "sha256": file_sha256(rtl),
                "bytes": rtl.stat().st_size,
            }
        ],
        semantic_index={
            "objects": [
                {
                    "object_id": "obj_state",
                    "source_anchor": {
                        "path": "src/main/scala/Foo.scala",
                        "line_start": 3,
                        "line_end": 3,
                        "source_sha256": frozen_hash,
                        "enclosing_symbol": "Foo",
                    },
                }
            ]
        },
        project_root=project,
        round_id=1,
    )
    assert result["graphs"][0]["edge_rows"][0]["projection_status"] == "rtl_only"
    assert result["source_candidates"] == []
