from pathlib import Path

from src.experiments.chiselcause_exp import (
    _build_trace_contrast,
    _budget_checkpoint_metrics,
    _evaluate_source_ranking,
    _first_violated_output,
    _first_gold_work,
    _renamed_design,
    _render_witness,
    validate_result,
)
from verilog_causal_analysis import build_source_ranking


def test_witness_cycle_and_result_contract(tmp_path: Path) -> None:
    design = tmp_path / "design.sv"
    design.write_text("module Top; Inline child(); endmodule\nmodule Inline; endmodule\n")
    renamed = _renamed_design(design, tmp_path / "renamed.sv", ["Top"], "Clean_")
    assert renamed.read_text() == (
        "module Clean_Top; Clean_Inline child(); endmodule\n"
        "module Clean_Inline; endmodule\n"
    )

    witness = _render_witness(
        [{"reset": 1, "enable": 0}, {"reset": 0, "enable": 1}],
        "clock",
        "reset",
        True,
    )
    assert "if (reset)" in witness
    assert "witness_cycle <= 2'd0" in witness
    assert "!reset" not in witness
    assert "!(reset)" in witness

    vcd = tmp_path / "cex.vcd"
    vcd.write_text(
        """$scope module ChiselCauseMiter $end
$var wire 1 ! clock $end
$var wire 4 \" clean_counter_out [3:0] $end
$var wire 4 # faulty_counter_out [3:0] $end
$var reg 1 $ chiselcause_outputs_match $end
$var wire 1 & enable $end
$upscope $end
$enddefinitions $end
#0
1!
b0000 \"
b0000 #
1$
0&
#5
0!
#10
1!
b0001 \"
b0010 #
0$
1&
""",
        encoding="utf-8",
    )
    failure = _first_violated_output(
        vcd,
        [{"name": "counter_out", "direction": "output", "width": 4}],
        "clock",
    )
    assert failure == {"output": "counter_out", "cycle": 1, "time": 10}
    fst = tmp_path / "cex.fst"
    fst.write_bytes(b"paired fst")
    contrast = _build_trace_contrast(
        run_dir=tmp_path,
        case_id="counter-bug-01",
        vcd_path=vcd,
        fst_path=fst,
        interface={
            "inputs": [
                {"name": "clock", "direction": "input", "width": 1},
                {"name": "reset", "direction": "input", "width": 1},
                {"name": "enable", "direction": "input", "width": 1},
            ],
            "outputs": [
                {"name": "counter_out", "direction": "output", "width": 4}
            ],
        },
        clock="clock",
        failure=failure,
    )
    assert [
        (row["outcome"], row["first_mismatch_cycle"])
        for row in contrast["traces"]
    ] == [("failing", 1), ("passing", None)]
    assert contrast["budget"] == {"failing": 1, "passing": 1}
    assert contrast["traces"][0]["guard_summary"]["inputs"] == {"enable": "1"}
    assert (
        contrast["traces"][0]["artifact"]["sha256"]
        == contrast["traces"][1]["artifact"]["sha256"]
    )
    assert (
        contrast["traces"][0]["slice_signature"]
        != contrast["traces"][1]["slice_signature"]
    )

    validate_result(
        {
            "schema_version": "chiselcause_result.v2",
            "case_id": "counter-bug-01",
            "method": "d2",
            "status": "incomplete",
            "input_identity": {},
            "source_ranking": None,
            "search_trace": None,
            "work": None,
            "metrics": None,
            "runtime_seconds": None,
            "peak_rss_bytes": None,
            "termination_reason": None,
            "failure_reason": "C3_not_run",
        }
    )


def test_anytime_metrics_use_observed_trace_and_absolute_checkpoints(tmp_path: Path) -> None:
    gold = {
        "file": "src/main/scala/Counter.scala",
        "line": 2,
        "evidence_node_ids": ["node_gold"],
    }
    trace = [
        {
            "node_id": "node_other",
            "cumulative": {"expanded_nodes": 1, "intervention_evaluations": 2},
        },
        {
            "parent_node_id": "node_gold",
            "cumulative": {"expanded_nodes": 3, "intervention_evaluations": 7},
        },
    ]
    assert _first_gold_work(gold, trace) == {
        "expanded_nodes": 3,
        "intervention_evaluations": 7,
    }
    checkpoints = _budget_checkpoint_metrics(
        run_dir=tmp_path,
        result={"work": {"expanded_nodes": 3}},
        ranking={
            "ordering": [
                {
                    "file": "src/main/scala/Counter.scala",
                    "line": 1,
                    "evidence_node_ids": ["node_other"],
                },
                gold,
            ]
        },
        target={"path": "src/main/scala/Counter.scala", "line": 2},
        trace=trace,
        checkpoints=(1, 3, 5),
        enabled=True,
    )
    assert checkpoints == [
        {"expanded_nodes": 1, "status": "complete", "gold_rank": None, "mrr": 0.0},
        {"expanded_nodes": 3, "status": "complete", "gold_rank": 2, "mrr": 0.5},
        {"expanded_nodes": 5, "status": "not_reached", "gold_rank": None, "mrr": None},
    ]


def test_source_ranking_merges_compiler_temporaries_by_statement(tmp_path: Path) -> None:
    source = tmp_path / "src/main/scala/Counter.scala"
    source.parent.mkdir(parents=True)
    source.write_text("val counter = Reg(UInt(4.W))\ncounter := counter + 2.U\n")
    graph = {
        "graph_id": "vcsg_test",
        "status": "complete",
        "signal_nodes": [
            {
                "node_id": "node_counter",
                "signal": "Top.dut.counter [3:0]",
                "suspect_score": 0.9,
            }
        ],
        "semantic_nodes": [],
        "edges": [
            {
                "edge_id": "edge_a",
                "src_node_id": "node_counter",
                "contribution_score": 1.0,
                "rtl_evidence": {
                    "snippet": "counter <= next; // src/main/scala/Counter.scala:1:5, :2:1"
                },
            },
            {
                "edge_id": "edge_b",
                "src_node_id": "node_counter",
                "contribution_score": 0.5,
                "rtl_evidence": {
                    "snippet": "tmp = counter; // src/main/scala/Counter.scala:2:1"
                },
            },
        ],
    }
    elaboration = {
        "objects": [
            {
                "name": "counter",
                "source_locator": {
                    "path": "src/main/scala/Counter.scala",
                    "line": 1,
                    "column": 5,
                },
            }
        ],
        "source_locators": [
            {"path": "src/main/scala/Counter.scala", "line": 1, "column": 5},
            {"path": "src/main/scala/Counter.scala", "line": 2, "column": 1},
        ],
    }
    ranking = build_source_ranking(
        graph,
        elaboration,
        source_index={
            "objects": [{"object_id": "obj_counter", "name": "counter"}],
            "statements": [
                {
                    "statement_id": "stmt_counter_decl",
                    "statement_kind": "declaration",
                    "source_anchor": {"path": "src/main/scala/Counter.scala", "line_start": 1, "line_end": 1},
                    "column_start": 5,
                    "syntax": "val counter = Reg(UInt(4.W))",
                    "semantic_object_ids": ["obj_counter"],
                },
                {
                    "statement_id": "stmt_counter_update",
                    "statement_kind": "register_update",
                    "source_anchor": {"path": "src/main/scala/Counter.scala", "line_start": 2, "line_end": 2},
                    "column_start": 1,
                    "syntax": "counter := counter + 2.U",
                    "semantic_object_ids": ["obj_counter"],
                },
            ],
        },
        case_id="counter-1",
        method="d2",
        source_root=tmp_path,
    )
    line_two = next(row for row in ranking["ordering"] if row["line"] == 2)
    assert line_two["score"] == 1.0
    assert line_two["evidence_node_ids"] == ["edge_a", "edge_b", "node_counter"]
    assert line_two["positive_authoritative_evidence"] is True
    assert ranking["statement_candidate_count"] == 2


def test_source_ranking_binds_one_selected_table_update_to_rtl_delta(tmp_path: Path) -> None:
    clean = tmp_path / "clean.sv"
    faulty = tmp_path / "faulty.sv"
    clean.write_text("assign y = 1'b0;\n")
    faulty.write_text("assign y = 1'b1;\n")
    ranking = build_source_ranking(
        {
            "graph_id": "vcsg_table",
            "status": "complete",
            "search_summary": {},
            "signal_nodes": [
                {
                    "node_id": "endpoint",
                    "signal": "Top.chiselcause_mismatch_any",
                    "is_endpoint": True,
                }
            ],
            "semantic_nodes": [],
            "edges": [],
        },
        {"commands": {"elaborate_argv": ["tool", "variantIndex=1"]}},
        source_index={
            "objects": [],
            "statements": [
                {
                    "statement_id": "table_bug_1",
                    "statement_kind": "table_update",
                    "entity_kind": "table_update",
                    "execution_phase": "elaboration",
                    "source_anchor": {
                        "path": "Table.scala",
                        "line_start": 4,
                        "line_end": 4,
                    },
                    "exact_origin_spec": {
                        "selection_parameter": "variantIndex",
                        "selection_value": 1,
                        "row_width": 1,
                        "updates": [{"row_expression": "0"}],
                    },
                    "exact_origins": [],
                    "semantic_object_ids": [],
                }
            ],
        },
        case_id="table-1",
        method="d1",
        source_root=tmp_path,
        clean_rtl=clean,
        faulty_rtl=faulty,
    )
    candidate = ranking["candidates"][0]
    assert candidate["in_rtl_delta"] is True
    assert candidate["positive_authoritative_evidence"] is True
    assert candidate["exact_origins"][0]["kind"] == "differential_table_update"


def test_evaluator_rejects_zero_evidence_gold_and_uses_average_tie_rank() -> None:
    ranking = {
        "complete_graph": True,
        "complete_source_projection": True,
        "statement_candidate_count": 3,
        "authoritative_candidate_count": 3,
        "positive_authoritative_candidate_count": 2,
        "ordering": [
            {
                "statement_id": "s1",
                "file": "Foo.scala",
                "line": 1,
                "rank": 1.5,
                "position": 1,
                "tie_size": 2,
                "positive_authoritative_evidence": True,
            },
            {
                "statement_id": "s2",
                "file": "Foo.scala",
                "line": 2,
                "rank": 1.5,
                "position": 2,
                "tie_size": 2,
                "positive_authoritative_evidence": True,
            },
            {
                "statement_id": "s3",
                "file": "Foo.scala",
                "line": 3,
                "rank": 3.0,
                "position": 3,
                "tie_size": 1,
                "positive_authoritative_evidence": False,
            },
        ],
    }
    tied = _evaluate_source_ranking(ranking, {"path": "Foo.scala", "line": 2})
    assert tied["gold_rank"] == 1.5
    assert tied["gold_position"] == 2
    assert tied["tie_size"] == 2
    assert tied["exam_percent"] == 66.666667

    unreachable = _evaluate_source_ranking(
        ranking, {"path": "Foo.scala", "line": 3}
    )
    assert unreachable["evaluation_status"] == "gold_unreachable"
    assert unreachable["evaluation_reason"] == "gold_without_positive_authoritative_evidence"
    assert unreachable["gold_rank"] is None
    assert unreachable["mrr"] is None
