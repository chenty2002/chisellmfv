from __future__ import annotations

import json
from pathlib import Path

import pytest

from src.chiselspecflow.config import (
    load_generator_configuration,
    load_project_contract,
)
from src.experiments.paper import (
    BUG_COUNTS,
    FAMILY_GROUPS,
    LEDGERS,
    _candidate_universe,
    _gold_locations,
    append_row,
    convert_vcd,
)


REPO = Path(__file__).resolve().parents[1]


@pytest.mark.parametrize("family", FAMILY_GROUPS)
def test_paper_family_has_exact_cfg000_contract_and_gold(family: str) -> None:
    root = REPO / "benchmark/synth" / family / "specflow"
    project = load_project_contract(root / "project.json")
    configuration = load_generator_configuration(
        root / "configs/cfg_000.json", project
    )

    assert configuration.configuration_id == "cfg_000"
    assert configuration.parameters.get("variantIndex", 0) == 0
    assert len(_gold_locations(REPO, family)["locations"]) == BUG_COUNTS[family]
    assert _candidate_universe(REPO, family)["candidate_count"] > 0


def test_result_ledger_rejects_duplicate_task_method(tmp_path: Path) -> None:
    for name in LEDGERS:
        (tmp_path / name).touch()
    row = {
        "task": "counter-p0",
        "method": "p0",
        "status": "tool_error",
        "cost": {},
        "input_hashes": {},
        "artifacts": {},
    }
    row_path = tmp_path / "row.json"
    row_path.write_text(json.dumps(row), encoding="utf-8")

    append_row(tmp_path, "track_p.jsonl", row_path)
    with pytest.raises(ValueError, match="duplicate result row"):
        append_row(tmp_path, "track_p.jsonl", row_path)


def test_vcd_to_fst_is_hash_bound(tmp_path: Path) -> None:
    vcd = tmp_path / "exact.vcd"
    vcd.write_text(
        "\n".join(
            (
                "$date today $end",
                "$version test $end",
                "$timescale 1ns $end",
                "$scope module top $end",
                "$var wire 1 ! clock $end",
                "$upscope $end",
                "$enddefinitions $end",
                "#0",
                "0!",
                "#1",
                "1!",
                "",
            )
        ),
        encoding="utf-8",
    )
    fst = tmp_path / "exact.fst"

    result = convert_vcd(tmp_path, vcd, fst, "case", "property")

    assert result["status"] == "complete"
    assert fst.stat().st_size > 0
    assert len(result["input_vcd"]["sha256"]) == 64
    assert len(result["output_fst"]["sha256"]) == 64
    assert fst.with_suffix(".fst.conversion.json").is_file()
