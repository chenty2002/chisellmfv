from pathlib import Path
import json
import sys

import pytest


ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))


from src.core.artifact_contract import file_sha256  # noqa: E402
from src.chiselspecflow.causal_backend import (  # noqa: E402
    CausalBackendError,
    _resolve_fst_display_name,
    materialize_causal_evidence,
)
import src.chiselspecflow.causal_backend as causal_backend  # noqa: E402


class FakeWorkspace:
    def __init__(self, root):
        self.run_dir = root / "run"
        self.indexes_dir = self.run_dir / "indexes"
        self.project_workspace = self.run_dir / "project"
        self.indexes_dir.mkdir(parents=True)
        self.project_workspace.mkdir(parents=True)
        self.stage_dir("compile_verify").mkdir(parents=True)
        self.stage_dir("diagnose").mkdir(parents=True)

    def stage_dir(self, stage):
        name = {
            "compile_verify": "02_compile_verify",
            "diagnose": "03_diagnose",
        }[stage]
        return self.run_dir / "stages" / name


def _write_json(path, value):
    path.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n")


def _workspace(tmp_path, *, with_trace):
    workspace = FakeWorkspace(tmp_path)
    stage2 = workspace.stage_dir("compile_verify")
    rtl = workspace.project_workspace / "Top.sv"
    rtl.write_text("module Top(input clock, input result); endmodule\n")
    certificate = {
        "generated_files": [
            {
                "artifact_id": "rtl_0001",
                "path": str(rtl),
                "sha256": file_sha256(rtl),
                "bytes": rtl.stat().st_size,
            }
        ]
    }
    certificate_path = stage2 / "elaboration_certificate.json"
    _write_json(certificate_path, certificate)
    package_run = workspace.run_dir / "reviewed"
    package_path = (
        package_run
        / "stages/01_asset_authoring/verification_package.json"
    )
    package_path.parent.mkdir(parents=True, exist_ok=True)
    _write_json(
        package_path,
        {
            "schema_version": "verification_package",
            "package_id": "vpkg_backend_test",
            "monitors": [],
        },
    )
    _write_json(
        stage2 / "verification_package_ref.json",
        {
            "schema_version": "verification_package_ref",
            "source_run": str(package_run),
            "path": "stages/01_asset_authoring/verification_package.json",
            "sha256": file_sha256(package_path),
            "package_id": "vpkg_backend_test",
        },
    )
    plan_path = stage2 / "verification_operation_plan.json"
    result_path = stage2 / "property_result_map.json"
    _write_json(plan_path, {"operations": []})
    _write_json(result_path, {"operation_results": []})
    traces = []
    if with_trace:
        trace = stage2 / "exact.fst"
        trace.write_bytes(b"synthetic-fst")
        traces.append(
            {
                "operation_id": "op_primary",
                "emitted_property_id": "Top.P",
                "path": str(trace),
                "format": "fst",
                "sha256": file_sha256(trace),
                "bytes": trace.stat().st_size,
            }
        )
    trace_manifest_path = stage2 / "trace_manifest.json"
    _write_json(
        trace_manifest_path,
        {
            "schema_version": "trace_manifest",
            "operation_plan_sha256": "0" * 64,
            "traces": traces,
        },
    )
    semantic_path = workspace.indexes_dir / "chisel_semantic_index.json"
    _write_json(semantic_path, {"objects": []})
    projection = {
        "schema_version": "evidence_projection",
        "round_id": 1,
        "status": "complete",
        "identity": {
            "verification_package_sha256": file_sha256(package_path),
            "elaboration_certificate_sha256": file_sha256(certificate_path),
            "operation_plan_sha256": file_sha256(plan_path),
            "property_result_map_sha256": file_sha256(result_path),
            "trace_manifest_sha256": file_sha256(trace_manifest_path),
        },
        "observation_map": {
            "semantic_index_sha256": file_sha256(semantic_path),
        },
        "traces": (
            [
                {
                    "operation_id": "op_primary",
                    "emitted_property_id": "Top.P",
                    "status": "complete",
                    "failure_cycle": 1,
                    "source_objects": [
                        {
                            "object_id": "obj_result",
                            "binding_id": "bind_result",
                            "emitted_signal": "Top.result",
                        }
                    ],
                    "causal_seed": {
                        "status": "ready",
                        "operation_id": "op_primary",
                        "failure_cycle": 1,
                        "endpoint_candidates": [
                            {
                                "object_id": "obj_result",
                                "binding_id": "bind_result",
                                "emitted_signal": "Top.result",
                                "selection_reason": "failed_expression_observer",
                            }
                        ],
                        "clock_signal": "Top.clock",
                        "errors": [],
                    },
                }
            ]
            if with_trace
            else []
        ),
    }
    return workspace, projection, rtl, traces


def _complete_graph(request):
    return {
        "schema_version": "verilog_causal_graph",
        "graph_id": "vcg_test",
        "status": "complete",
        "identity": {
            "request_sha256": request.request_sha256,
            "trace_sha256": request.trace.sha256,
            "rtl_set_sha256": "1" * 64,
            "analyzer_revision": "test",
            "hdlconvertor_revision": "test",
            "random_seed": request.random_seed,
        },
        "bounds": {
            "max_depth": request.max_depth,
            "max_nodes": request.max_nodes,
            "max_depth_reached": False,
            "max_nodes_reached": False,
        },
        "nodes": [
            {
                "node_id": "vcn_endpoint",
                "signal_id": "sig_endpoint",
                "signal": request.endpoint_signal,
                "cycle": request.endpoint_cycle,
                "value": "0",
                "depth": 0,
                "is_endpoint": True,
                "is_slice_leaf": False,
                "rtl_context_status": "exact",
                "identity_strength": "exact",
                "suspect_score": 1.0,
            }
        ],
        "edges": [],
        "diagnostics": [],
    }


def _complete_semantic_graph(request):
    from verilog_causal_analysis import make_search_summary

    return {
        "schema_version": "verilog_causal_semantic_graph",
        "graph_id": "vcsg_test",
        "status": "complete",
        "identity": {
            "request_sha256": request.request_sha256,
            "trace_sha256": request.trace.sha256,
            "rtl_set_sha256": "1" * 64,
            "analyzer_revision": "test+c6",
            "profile_version": "chisel-semantic-profile",
        },
        "endpoint": {
            "signal": request.endpoint.signal,
            "cycle": request.endpoint.cycle,
            "projection_id": None,
        },
        "signal_nodes": [],
        "semantic_nodes": [],
        "edges": [],
        "root_candidates": [],
        "search_summary": make_search_summary(
            request.search_policy,
            termination_reason="frontier_exhausted",
            seed_count=1,
        ),
        "bounds": dict(request.bounds),
        "diagnostics": [],
    }


def _install_projection_contract(workspace, projection):
    stage2 = workspace.stage_dir("compile_verify")
    package_run = workspace.run_dir / "reviewed"
    package_path = package_run / "stages/01_asset_authoring/verification_package.json"
    package_path.parent.mkdir(parents=True, exist_ok=True)
    package = {
        "schema_version": "verification_package",
        "package_id": "vpkg_projection_test",
        "monitors": [
            {
                "obligation_id": "obl_primary",
                "properties": [
                    {
                        "source_property_id": "P",
                        "role": "primary_assertion",
                        "expression_ir": {
                            "schema_version": "expression_ir",
                            "root": {
                                "op": "object_ref",
                                "object_id": "obj_result",
                            },
                        },
                        "guard_ir": {
                            "schema_version": "expression_ir",
                            "root": {"op": "literal", "value": True},
                        },
                    }
                ],
            }
        ],
    }
    _write_json(package_path, package)
    _write_json(
        stage2 / "verification_package_ref.json",
        {
            "schema_version": "verification_package_ref",
            "source_run": str(package_run),
            "path": "stages/01_asset_authoring/verification_package.json",
            "sha256": file_sha256(package_path),
            "package_id": package["package_id"],
        },
    )
    _write_json(
        stage2 / "verification_operation_plan.json",
        {
            "operations": [
                {
                    "operation_id": "op_primary",
                    "emitted_property_id": "Top.P",
                    "source_property_id": "P",
                    "obligation_id": "obl_primary",
                    "role": "primary_assertion",
                }
            ]
        },
    )
    projection["traces"][0]["source_property_id"] = "P"
    projection["traces"][0]["monitor_states"] = []
    projection["identity"]["verification_package_sha256"] = file_sha256(
        package_path
    )
    projection["identity"]["operation_plan_sha256"] = file_sha256(
        stage2 / "verification_operation_plan.json"
    )


def test_proof_path_writes_not_required_without_graph_builder(tmp_path):
    workspace, projection, _rtl, _traces = _workspace(
        tmp_path, with_trace=False
    )
    manifest, source, graphs = materialize_causal_evidence(
        workspace, 1, projection
    )
    assert manifest["status"] == "not_required"
    assert source["status"] == "not_required"
    assert graphs == {}


def test_adapter_uses_only_certificate_rtl_set_and_path_free_graph(tmp_path):
    workspace, projection, rtl, _traces = _workspace(
        tmp_path, with_trace=True
    )
    rogue = workspace.project_workspace / "golden_hidden.sv"
    rogue.write_text("module golden_hidden; endmodule\n")
    captured = []

    def builder(request):
        captured.append(request)
        return _complete_semantic_graph(request)

    manifest, _source, graphs = materialize_causal_evidence(
        workspace, 1, projection, graph_builder=builder
    )
    assert manifest["status"] == "complete"
    assert len(captured) == 1
    assert [row.path for row in captured[0].rtl_files] == [str(rtl.resolve())]
    graph = graphs["vcsg_test"]
    assert str(tmp_path) not in json.dumps(graph, sort_keys=True)


def test_adapter_explicit_semantic_profile_reaches_c6_without_oracle_seed(tmp_path):
    workspace, projection, rtl, _traces = _workspace(
        tmp_path, with_trace=True
    )
    captured = []

    def builder(request):
        captured.append(request)
        return _complete_semantic_graph(request)

    config = {
        "causal_backend": "verilog_causal_analysis",
        "causal_policy": "best_effort",
        "clock_domain": "formal_primary",
        "max_depth": 12,
        "max_nodes": 120,
        "random_seed": 0,
    }
    manifest, source, graphs = materialize_causal_evidence(
        workspace,
        1,
        projection,
        config=config,
        graph_builder=builder,
    )
    assert manifest["status"] == "complete"
    assert source["status"] == "complete"
    assert list(graphs) == ["vcsg_test"]
    request = captured[0]
    assert "source_provenance" in request.semantic_profile.features
    assert request.endpoint.projection_mode == "none"
    assert [row.path for row in request.rtl_files] == [str(rtl.resolve())]


def test_adapter_materializes_hash_bound_projection_only_for_missing_rtl_context(
    tmp_path, monkeypatch
):
    workspace, projection, _rtl, _traces = _workspace(
        tmp_path, with_trace=True
    )
    _install_projection_contract(workspace, projection)
    captured = []

    class FakeInstanceGraph:
        def get_rtl_context(self, _signal):
            return {"found": False}

    class FakePrepared:
        instance_graph = FakeInstanceGraph()

        def __enter__(self):
            return self

        def __exit__(self, *_args):
            return None

        def build(self, request):
            captured.append(request)
            return _complete_semantic_graph(request)

    monkeypatch.setattr(
        causal_backend,
        "_resolve_fst_endpoint",
        lambda _path, emitted_signal, _semantic: emitted_signal,
    )
    monkeypatch.setattr(
        "verilog_causal_analysis.prepare_causal_session",
        lambda _request: FakePrepared(),
    )
    config = {
        "causal_backend": "verilog_causal_analysis",
        "causal_policy": "best_effort",
        "clock_domain": "formal_primary",
        "max_depth": 12,
        "max_nodes": 120,
        "random_seed": 0,
    }
    manifest, _source, graphs = materialize_causal_evidence(
        workspace, 1, projection, config=config
    )

    assert manifest["status"] == "complete"
    assert "semantic_request_set_sha256" in manifest["inputs"]
    assert "semantic_input_set_sha256" in manifest["inputs"]
    assert list(graphs) == ["vcsg_test"]
    request = captured[0]
    assert request.endpoint.projection_mode == "controller_supplied_exact"
    assert request.endpoint.predicate_members == ("Top.result",)
    assert "endpoint_projection" in request.semantic_profile.features
    assert len(request.semantic_inputs) == 1
    semantic_input = request.semantic_inputs[0]
    assert semantic_input.kind == "assertion_endpoint_projection"
    artifact = json.loads(Path(semantic_input.path).read_text())
    assert artifact["endpoint_signal"] == "Top.result"
    assert artifact["endpoint_cycle"] == 1
    assert artifact["predicate_members"] == ["Top.result"]
    assert artifact["trace_sha256"] == request.trace.sha256


def test_adapter_keeps_projection_null_when_typed_endpoint_is_sliceable(
    tmp_path, monkeypatch
):
    workspace, projection, _rtl, _traces = _workspace(
        tmp_path, with_trace=True
    )
    captured = []

    class FakeInstanceGraph:
        def get_rtl_context(self, _signal):
            return {"found": True}

    class FakePrepared:
        instance_graph = FakeInstanceGraph()

        def __enter__(self):
            return self

        def __exit__(self, *_args):
            return None

        def build(self, request):
            captured.append(request)
            return _complete_semantic_graph(request)

    monkeypatch.setattr(
        causal_backend,
        "_resolve_fst_endpoint",
        lambda _path, emitted_signal, _semantic: emitted_signal,
    )
    monkeypatch.setattr(
        "verilog_causal_analysis.prepare_causal_session",
        lambda _request: FakePrepared(),
    )
    config = {
        "causal_backend": "verilog_causal_analysis",
        "causal_policy": "best_effort",
        "clock_domain": "formal_primary",
        "max_depth": 12,
        "max_nodes": 120,
        "random_seed": 0,
    }
    manifest, _source, _graphs = materialize_causal_evidence(
        workspace, 1, projection, config=config
    )

    assert manifest["status"] == "complete"
    assert captured[0].endpoint.projection_mode == "none"
    assert captured[0].semantic_inputs == ()
    assert "endpoint_projection" not in captured[0].semantic_profile.features
    assert not (workspace.stage_dir("diagnose") / "causal_inputs").exists()


def test_semantic_non_primary_clock_fails_closed_before_graph_build(tmp_path):
    workspace, projection, _rtl, _traces = _workspace(
        tmp_path, with_trace=True
    )
    projection["observation_map"]["clock_signal"] = "Top.primary_clock"
    config = {
        "causal_backend": "verilog_causal_analysis",
        "causal_policy": "best_effort",
        "clock_domain": "formal_primary",
        "max_depth": 12,
        "max_nodes": 120,
        "random_seed": 0,
    }
    manifest, source, graphs = materialize_causal_evidence(
        workspace,
        1,
        projection,
        config=config,
        graph_builder=_complete_semantic_graph,
    )

    assert manifest["status"] == "incomplete"
    assert graphs == {}
    assert source["status"] == "incomplete"
    assert manifest["errors"][0]["code"] == "causal_graph_build_failed"
    assert "primary formal clock" in manifest["errors"][0]["detail"]
    assert str(tmp_path) not in json.dumps(manifest)


@pytest.mark.parametrize(
    "artifact",
    ["verification_package", "operation_plan", "property_result_map"],
)
def test_semantic_stage2_projection_input_hash_drift_fails_closed(
    tmp_path, artifact
):
    workspace, projection, _rtl, _traces = _workspace(
        tmp_path, with_trace=True
    )
    stage2 = workspace.stage_dir("compile_verify")
    paths = {
        "verification_package": (
            workspace.run_dir
            / "reviewed/stages/01_asset_authoring/verification_package.json"
        ),
        "operation_plan": stage2 / "verification_operation_plan.json",
        "property_result_map": stage2 / "property_result_map.json",
    }
    paths[artifact].write_text(paths[artifact].read_text() + " ")
    config = {
        "causal_backend": "verilog_causal_analysis",
        "causal_policy": "best_effort",
        "clock_domain": "formal_primary",
        "max_depth": 12,
        "max_nodes": 120,
        "random_seed": 0,
    }

    with pytest.raises(CausalBackendError, match="identity drifted"):
        materialize_causal_evidence(
            workspace,
            1,
            projection,
            config=config,
            graph_builder=_complete_semantic_graph,
        )


def test_certificate_or_trace_hash_drift_fails_closed(tmp_path):
    workspace, projection, rtl, traces = _workspace(
        tmp_path, with_trace=True
    )
    rtl.write_text(rtl.read_text() + "// drift\n")
    with pytest.raises(CausalBackendError, match="RTL hash drifted"):
        materialize_causal_evidence(
            workspace, 1, projection, graph_builder=_complete_graph
        )

    workspace2, projection2, _rtl2, traces2 = _workspace(
        tmp_path / "second", with_trace=True
    )
    Path(traces2[0]["path"]).write_bytes(b"drifted")
    manifest, _source, graphs = materialize_causal_evidence(
        workspace2, 1, projection2, graph_builder=_complete_graph
    )
    assert manifest["status"] == "incomplete"
    assert graphs == {}
    assert manifest["errors"][0]["code"] == "causal_graph_build_failed"

    workspace3, projection3, _rtl3, _traces3 = _workspace(
        tmp_path / "third", with_trace=True
    )
    certificate = workspace3.stage_dir("compile_verify") / "elaboration_certificate.json"
    certificate.write_text(certificate.read_text() + " ")
    with pytest.raises(CausalBackendError, match="input identity drifted"):
        materialize_causal_evidence(
            workspace3, 1, projection3, graph_builder=_complete_graph
        )


@pytest.mark.parametrize(
    ("mutation", "error_code"),
    [
        (
            lambda projection: projection["traces"][0]["causal_seed"].update(
                {"operation_id": "op_other"}
            ),
            "causal_seed_trace_identity_mismatch",
        ),
        (
            lambda projection: projection["traces"][0].update(
                {"emitted_property_id": "Top.OTHER"}
            ),
            "causal_trace_property_identity_mismatch",
        ),
        (
            lambda projection: projection["traces"][0]["causal_seed"][
                "endpoint_candidates"
            ][0].update({"emitted_signal": "Top.guessed"}),
            "causal_endpoint_trace_join_mismatch",
        ),
    ],
)
def test_adapter_rejects_cross_identity_joins(tmp_path, mutation, error_code):
    workspace, projection, _rtl, _traces = _workspace(
        tmp_path, with_trace=True
    )
    mutation(projection)
    manifest, _source, graphs = materialize_causal_evidence(
        workspace, 1, projection, graph_builder=_complete_graph
    )
    assert manifest["status"] == "incomplete"
    assert graphs == {}
    assert manifest["errors"][0]["code"] == error_code


def test_semantic_index_hash_drift_fails_closed(tmp_path):
    workspace, projection, _rtl, _traces = _workspace(
        tmp_path, with_trace=True
    )
    semantic = workspace.indexes_dir / "chisel_semantic_index.json"
    semantic.write_text(semantic.read_text() + " ")
    with pytest.raises(CausalBackendError, match="input identity drifted"):
        materialize_causal_evidence(
            workspace, 1, projection, graph_builder=_complete_graph
        )


def test_typed_endpoint_resolves_only_unique_fst_packed_display_suffix():
    assert (
        _resolve_fst_display_name(
            "SpecFlowOverlay.state",
            [
                ("SpecFlowOverlay.other [3:0]", 4),
                ("SpecFlowOverlay.state [3:0]", 4),
            ],
            4,
        )
        == "SpecFlowOverlay.state [3:0]"
    )
    with pytest.raises(CausalBackendError, match="one exact FST"):
        _resolve_fst_display_name(
            "SpecFlowOverlay.state",
            [
                ("SpecFlowOverlay.state [3:0]", 4),
                ("SpecFlowOverlay.state [7:4]", 4),
            ],
            4,
        )
