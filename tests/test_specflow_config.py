import json
from pathlib import Path
import sys

import pytest


ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))


from src.chiselspecflow.config import (  # noqa: E402
    SpecFlowConfigError,
    load_generator_configuration,
    load_project_contract,
)
from src.chiselspecflow.specification import (  # noqa: E402
    PublicSpecificationError,
    load_public_spec_package,
    validate_suite_ledger,
)
import src.chiselspecflow.specification as specification_module  # noqa: E402


PROJECT = ROOT / "benchmark/synth/counter/specflow/project.json"
CONFIG = ROOT / "benchmark/synth/counter/specflow/configs/cfg_000.json"
LEDGER = ROOT / "benchmark/synth/SPECIFICATIONS.sha256"


def test_counter_project_and_configuration_are_strict_and_debug_preserving(tmp_path):
    project = load_project_contract(PROJECT)
    configuration = load_generator_configuration(CONFIG, project)

    assert project.project_id == "counter"
    assert project.model_visible_files == (Path("src/main/scala/FirstCounter.scala"),)
    assert all("strip-debug-info" not in item for item in project.build["firtool_options"])
    assert project.diagnosis["causal_policy"] == "best_effort"
    assert project.diagnosis["max_depth"] == 12
    assert configuration.configuration_id == "cfg_000"

    invalid = dict(configuration.raw)
    invalid["parameters"] = dict(invalid["parameters"])
    invalid["parameters"].pop("increment")
    invalid_path = tmp_path / "invalid.json"
    invalid_path.write_text(json.dumps(invalid), encoding="utf-8")
    with pytest.raises(SpecFlowConfigError, match="fields mismatch"):
        load_generator_configuration(invalid_path, project)


def test_only_existing_fsm_vertical_slice_explicitly_opts_into_semantic():
    projects = {
        family: load_project_contract(
            ROOT / f"benchmark/synth/{family}/specflow/project.json"
        )
        for family in ("counter", "fsm_16", "led_controller", "i2c")
    }

    assert projects["fsm_16"].diagnosis["causal_backend"] == (
        "verilog_causal_analysis"
    )
    assert {
        projects[family].diagnosis["causal_backend"]
        for family in ("counter", "led_controller", "i2c")
    } == {"verilog_causal_analysis"}


def test_checksum_suite_and_all_public_specs_validate():
    ledger = validate_suite_ledger(LEDGER)
    spec_rows = [
        row for row in ledger["entries"] if row["path"].endswith("/specflow/spec.md")
    ]
    packages = [load_public_spec_package(ROOT / row["path"], LEDGER) for row in spec_rows]

    assert len(packages) == 11
    assert {item["difficulty"] for item in packages} == {"S", "M", "L"}
    assert all(item["review"]["decision"] == "approved" for item in packages)
    assert all(item["normative_clause_ids"] for item in packages)
    assert all(item["expected_property_ids"] for item in packages)


def test_public_spec_rejects_a_ledger_hash_drift(tmp_path, monkeypatch):
    copied = tmp_path / "SPECIFICATIONS.sha256"
    copied.write_text(LEDGER.read_text(encoding="utf-8").replace("f91e", "0000", 1))
    monkeypatch.setattr(specification_module, "_repository_root", lambda _path: ROOT)
    with pytest.raises(PublicSpecificationError, match="hash mismatch"):
        validate_suite_ledger(copied)
