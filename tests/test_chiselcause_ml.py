import json
from pathlib import Path

import pytest

import src.experiments.chiselcause_ml as chiselcause_ml
from src.experiments.chiselcause_exp import _load_witness, _render_witness
from src.experiments.chiselcause_ml import (
    train,
    write_configs,
    write_witnesses,
)


def test_ml_a_configs_and_wildcard_witnesses(tmp_path: Path) -> None:
    config_paths = write_configs(tmp_path / "ml", Path.cwd())
    assert len(config_paths) == 30
    assert json.loads(
        (tmp_path / "ml/input-configs/counter/counter-2.json").read_text()
    )["parameters"] == {
        "increment": 1,
        "overflowAtMax": False,
        "resetCounter": True,
    }
    assert json.loads(
        (tmp_path / "ml/input-configs/alu/alu-6.json").read_text()
    )["parameters"] == {"variantIndex": 6}
    assert chiselcause_ml.CASE_ID.fullmatch("decoder_3_to_8-6-w01")

    wildcard = tmp_path / "wildcard.csv"
    wildcard.write_text("*,0\n1,*\n*,*\n", encoding="utf-8")
    rows = _load_witness(
        wildcard,
        ("enable", "data"),
        ({"name": "enable", "width": 1}, {"name": "data", "width": 2}),
    )
    assert rows == [{"data": 0}, {"enable": 1}, {}]
    rendered = _render_witness(rows, "clock", "reset", True)
    assert rendered.count("assume property") == 2
    assert "|-> ();" not in rendered

    vcd = tmp_path / "raw/base/formal/counterexample.vcd"
    vcd.parent.mkdir(parents=True)
    vcd.write_text(
        """$scope module ChiselCauseMiter $end
$var wire 1 ! clock $end
$var wire 1 \" reset $end
$var wire 1 # enable $end
$upscope $end
$enddefinitions $end
#0
1!
1\"
0#
#5
0!
#10
1!
0\"
1#
""",
        encoding="utf-8",
    )
    cases = tmp_path / "cases.json"
    cases.write_text(
        json.dumps(
            {
                "cases": [
                    {
                        "status": "complete",
                        "formal": {"outcome": "cex"},
                        "interface": {
                            "inputs": [
                                {"name": "clock", "width": 1},
                                {"name": "reset", "width": 1},
                                {"name": "enable", "width": 1},
                            ]
                        },
                        "artifacts": {
                            "vcd": {"path": "raw/base/formal/counterexample.vcd"}
                        },
                        "cex": {"failure_cycle": 1},
                        "endpoint_projection": {
                            "clock_signal": "ChiselCauseMiter.clock"
                        },
                    }
                ]
            }
        ),
        encoding="utf-8",
    )
    witness_paths = write_witnesses(cases, tmp_path / "witnesses")
    assert len(witness_paths) == 16
    assert witness_paths[0].read_text() == "0\n1\n"
    assert witness_paths[7].read_text() == "0\n0\n0\n0\n0\n0\n0\n0\n1\n"
    assert witness_paths[8].read_text() == "1\n*\n"
    assert json.loads((tmp_path / "witnesses/witness.json").read_text()) == {
        "signals": ["enable"],
        "widths": [1],
    }


def test_p3_ties_recall_macros_and_pretraining_isolation(
    tmp_path: Path, monkeypatch
) -> None:
    labels = {
        "bug-a": ("design-a", "family-1", True, True),
        "bug-b": ("design-b", "family-1", True, True),
        "bug-c": ("design-c", "family-2", True, False),
        "bug-d": ("design-c", "family-2", False, False),
    }
    manifest = []
    samples = []
    for bug_id, (design, family, candidate, authority) in labels.items():
        manifest.append(
            {
                "bug_id": bug_id,
                "design_id": design,
                "family_id": family,
                "case_id": bug_id,
                "trace_sha256": f"fst-{bug_id}",
                "status": "complete",
                "candidate_reachable": candidate,
                "gold_reachable": authority,
                "failing_slice_signature": f"slice-{bug_id}",
            }
        )
        for statement in ("gold", "other"):
            d1_rank = 1.0 if statement == "gold" else 2.0
            samples.append(
                {
                    "bug_id": bug_id,
                    "trace_sha256": f"fst-{bug_id}",
                    "statement_id": statement,
                    "d1_rank": d1_rank if bug_id == "bug-a" else 1.5,
                    "features": [0.0, 0.0, 0.5, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0],
                    "is_gold": statement == "gold" and candidate,
                }
            )
    (tmp_path / "manifest.jsonl").write_text(
        "".join(json.dumps(row) + "\n" for row in manifest), encoding="utf-8"
    )
    (tmp_path / "samples.jsonl").write_text(
        "".join(json.dumps(row) + "\n" for row in samples), encoding="utf-8"
    )
    gold = {
        "bug_id": "normalized",
        "trace_sha256": "trace",
        "statement_id": "gold",
        "features": [1.0] + [0.0] * 8,
        "is_gold": True,
    }
    negative = {
        **gold,
        "statement_id": "negative-1",
        "features": [0.0] * 9,
        "is_gold": False,
    }
    assert chiselcause_ml.averaged_perceptron([gold, negative], epochs=1) == (
        chiselcause_ml.averaged_perceptron(
            [gold, negative, {**negative, "statement_id": "negative-2"}],
            epochs=1,
        )
    )
    visible = []

    def capture(rows, **_):
        visible.append({row["bug_id"] for row in rows})
        return [0.0] * 9

    monkeypatch.setattr(chiselcause_ml, "averaged_perceptron", capture)
    summary = train(tmp_path)
    folds = [
        json.loads(row)
        for row in (tmp_path / "fold_metrics.jsonl").read_text().splitlines()
    ]

    assert summary["candidate_recall"]["bug"] == {
        "recalled": 3,
        "total": 4,
        "rate": 0.75,
    }
    assert summary["positive_authority_recall"]["bug"] == {
        "recalled": 2,
        "total": 4,
        "rate": 0.5,
    }
    assert {name: row["fold_count"] for name, row in summary["protocols"].items()} == {
        "lobo": 4,
        "lodo": 3,
        "lofo": 2,
    }
    ordered = next(
        row
        for row in folds
        if row["protocol"] == "lobo" and row["test_bug_id"] == "bug-a"
    )
    assert {
        row["features"][2] for row in samples if row["bug_id"] == "bug-a"
    } == {0.5}
    assert ordered["d1"] == {
        "exam_percent": 50.0,
        "gold_rank": 1.0,
        "mrr": 1.0,
        "tie_size": 1,
        "top_1": True,
        "top_3": True,
        "top_5": True,
    }
    tied = next(
        row
        for row in folds
        if row["protocol"] == "lobo" and row["test_bug_id"] == "bug-b"
    )
    assert tied["d1"] == {
        "exam_percent": 75.0,
        "gold_rank": 1.5,
        "mrr": 0.666667,
        "tie_size": 2,
        "top_1": False,
        "top_3": True,
        "top_5": True,
    }
    unreachable = next(
        row
        for row in folds
        if row["protocol"] == "lobo" and row["test_bug_id"] == "bug-d"
    )
    assert unreachable["ml"]["mrr"] == 0.0
    assert unreachable["ml"]["exam_percent"] == 100.0
    assert unreachable["ml"]["top_5"] is False
    assert (
        summary["protocols"]["lofo"]["end_to_end"]["family_macro"]["ml"]["mrr"]
        == 0.333334
    )
    assert summary["protocols"]["lofo"]["reachable_only"]["bug_count"] == 2
    assert visible[-8:-4] == [set()] * 4
    assert visible[-4:] == [{"bug-a", "bug-b"}] * 4

    leaked = tmp_path / "leaked"
    leaked.mkdir()
    manifest[1]["trace_sha256"] = manifest[0]["trace_sha256"]
    (leaked / "manifest.jsonl").write_text(
        "".join(json.dumps(row) + "\n" for row in manifest), encoding="utf-8"
    )
    (leaked / "samples.jsonl").write_text("", encoding="utf-8")
    with pytest.raises(chiselcause_ml.ChiselCauseExperimentError, match="FST hash"):
        train(leaked)
