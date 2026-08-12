from __future__ import annotations

import json
from pathlib import Path

import pytest

import src.experiments.verilogcause as verilogcause
from src.experiments.verilogcause import (
    VerilogCauseError,
    aggregate_features,
    compare_outputs,
    discover_cases,
    join_reviewed_gold,
    read_line_coverage,
    relation_gate,
    resolve_rtl_evidence,
    run_after_gate,
    sanitize_verilog,
    set_valued_metrics,
    trainer_visible_sample,
)
from verilog_causal_analysis.identity import stable_set_sha256


def _candidate_payload() -> dict:
    return {
        "schema_version": "rtl_candidate_universe.v1",
        "rtl_set_sha256": "a" * 64,
        "candidates": [
            {
                "artifact_id": "rtl_0001",
                "statement_id": "stmt_1",
                "line_start": 3,
                "line_end": 3,
                "snippet_sha256": "b" * 64,
            },
            {
                "artifact_id": "rtl_0001",
                "statement_id": "stmt_2",
                "line_start": 4,
                "line_end": 4,
                "snippet_sha256": "c" * 64,
            },
        ],
    }


def _graph() -> dict:
    return {
        "identity": {"rtl_set_sha256": "a" * 64},
        "semantic_nodes": [],
        "signal_nodes": [{"node_id": "dst", "cycle": 2}],
        "edges": [
            {
                "edge_id": "edge_1",
                "dst_node_id": "dst",
                "dependency_type": "sequential",
                "contribution_evidence": {"status": "supported", "score": 0.75},
                "rtl_evidence": {
                    "artifact_id": "rtl_0001",
                    "statement_id": "stmt_1",
                    "line_start": 3,
                    "line_end": 3,
                    "snippet_sha256": "b" * 64,
                },
            }
        ],
    }


def test_metadata_uses_wit_root_and_family_directory(tmp_path: Path) -> None:
    wit = tmp_path / "Wit-HW"
    family = wit / "buggy_designs" / "nested_family" / "implementation"
    family.mkdir(parents=True)
    for name in ("good.v", "bad.v", "tb.sv", "trigger.txt"):
        (family / name).write_text("0", encoding="utf-8")
    (family / "bug-info-1.json").write_text(
        json.dumps(
            {
                "case_name": "odd-separator_1",
                "module_name": "dut",
                "correct_design": "./buggy_designs/nested_family/implementation/good.v",
                "buggy_design": "./buggy_designs/nested_family/implementation/bad.v",
                "testbench": "./buggy_designs/nested_family/implementation/tb.sv",
                "bug_trigger_input": "./buggy_designs/nested_family/implementation/trigger.txt",
                "input_signals": {"a": 1},
                "sequential_flag": False,
            }
        ),
        encoding="utf-8",
    )

    case = discover_cases(wit / "buggy_designs", ["nested_family"])[0]

    assert case["family"] == "nested_family"
    assert case["correct_design"] == (family / "good.v").resolve()


def test_rtl_set_hash_matches_vca_identity(tmp_path: Path) -> None:
    row = {"artifact_id": "rtl_0001", "sha256": "a" * 64, "bytes": 7}
    assert stable_set_sha256([row]) == verilogcause._canonical_sha256([row])


def test_sanitizer_and_output_endpoint_contract(tmp_path: Path) -> None:
    source = 'assign y = "//not-comment"; // BUGGY repair path\n/* modified */assign z = y;\n'
    sanitized = sanitize_verilog(source)

    assert '"//not-comment"' in sanitized
    assert "BUGGY" not in sanitized and "modified" not in sanitized
    assert sanitized.count("\n") == source.count("\n")
    assert [len(line) for line in sanitized.splitlines()] == [
        len(line) for line in source.splitlines()
    ]

    correct = tmp_path / "correct.csv"
    faulty = tmp_path / "faulty.csv"
    correct.write_text("time, a, b\n5,0,1\n15,1,1\n", encoding="utf-8")
    faulty.write_text("time, a, b\n5,0,1\n15,1,0\n", encoding="utf-8")
    failure = compare_outputs(correct, faulty)
    assert failure["failure_endpoint"] == {
        "signal": "b",
        "cycle": 1,
        "time": "15",
        "correct": "1",
        "faulty": "0",
    }
    assert compare_outputs(correct, correct) == {
        "outcome": "passing",
        "failure_endpoint": None,
    }


def test_coverage_and_exact_gold_evaluator_fail_closed(tmp_path: Path) -> None:
    coverage = tmp_path / "coverage.dat"
    coverage.write_text(
        "C '\x01f\x02design.v\x01l\x023' 4\n"
        "C '\x01f\x02testbench.sv\x01l\x029' 7\n",
        encoding="utf-8",
    )
    assert read_line_coverage(coverage, {"design.v"}) == [
        {"source": "design.v", "line": 3, "count": 4}
    ]

    candidates = _candidate_payload()
    review = {
        "review_status": "approved",
        "reviewer": "codex",
        "rtl_set_sha256": "a" * 64,
        "gold_representable": True,
        "gold": [{"artifact_id": "rtl_0001", "statement_id": "stmt_1"}],
    }
    gold, representable = join_reviewed_gold(candidates, review)
    metrics = set_valued_metrics(
        {("rtl_0001", "stmt_1"): 1.0, ("rtl_0001", "stmt_2"): 1.0},
        gold,
        representable=representable,
    )
    assert metrics["gold_rank"] == 1.5 and metrics["tie_size"] == 2
    assert set_valued_metrics({}, set(), representable=False)["exam_percent"] == 100.0
    with pytest.raises(VerilogCauseError, match="RTL hash mismatch"):
        join_reviewed_gold(candidates, review | {"rtl_set_sha256": "d" * 64})


def test_exact_relation_features_and_failed_gate_do_not_train() -> None:
    candidates = _candidate_payload()
    graph = _graph()
    assert list(resolve_rtl_evidence(candidates, graph)) == [("rtl_0001", "stmt_1")]
    drifted = _graph()
    drifted["edges"][0]["rtl_evidence"]["line_start"] = 99
    with pytest.raises(VerilogCauseError, match="line_start drift"):
        resolve_rtl_evidence(candidates, drifted)

    features = aggregate_features(
        candidates,
        [
            {
                "graph": graph,
                "failure_cycle": 2,
                "executed_candidates": [("rtl_0001", "stmt_1")],
            }
        ],
        [],
    )
    first = features[("rtl_0001", "stmt_1")]
    assert first["features"]["causal_trace_coverage"] == 1.0
    assert first["features"]["sequential_ratio"] == 1.0
    assert first["features"]["pass_execution_rate"] is None
    with pytest.raises(VerilogCauseError, match="incomplete"):
        trainer_visible_sample("case", candidates["candidates"][0], first)

    called = False

    def train() -> None:
        nonlocal called
        called = True

    report = relation_gate(
        [
            {
                "case_id": "case",
                "status": "complete",
                "gold_representable": True,
                "gold_reachable": True,
                "has_failing_trace": True,
                "has_passing_trace": False,
                "graph_complete": True,
            }
        ]
    )
    with pytest.raises(VerilogCauseError, match="gate is not open"):
        run_after_gate(report, train)
    assert report["decision"] == "failed_stop" and not called

    pairwise = verilogcause._pairwise_summary(
        [
            {
                "bug_id": "case",
                "family": "alu",
                "sequential": False,
                "score": 1.0,
                "is_gold": True,
            },
            {
                "bug_id": "case",
                "family": "alu",
                "sequential": False,
                "score": 0.0,
                "is_gold": False,
            },
        ]
    )
    assert pairwise["by_family"]["alu"]["win_rate"] == 1.0
