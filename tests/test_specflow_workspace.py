import json
from pathlib import Path
import sys

import pytest


ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))


from src.chiselspecflow.config import (  # noqa: E402
    SpecFlowRunConfig,
    load_generator_configuration,
    load_project_contract,
)
from src.chiselspecflow.specification import load_public_spec_package  # noqa: E402
from src.chiselspecflow.workspace import SpecFlowWorkspace  # noqa: E402


PROJECT_PATH = ROOT / "benchmark/synth/counter/specflow/project.json"
SPEC_PATH = ROOT / "benchmark/synth/counter/specflow/spec.md"
CONFIG_PATH = ROOT / "benchmark/synth/counter/specflow/configs/cfg_000.json"
LEDGER_PATH = ROOT / "benchmark/synth/SPECIFICATIONS.sha256"


def _materialize(tmp_path):
    config = SpecFlowRunConfig(
        project_contract=PROJECT_PATH,
        specification=SPEC_PATH,
        configuration=CONFIG_PATH,
        run_root=tmp_path,
    )
    project = load_project_contract(PROJECT_PATH)
    configuration = load_generator_configuration(CONFIG_PATH, project)
    spec = load_public_spec_package(SPEC_PATH, LEDGER_PATH)
    workspace = SpecFlowWorkspace(tmp_path / "run", config)
    workspace.materialize(project, configuration, spec)
    return workspace


def test_isolated_workspace_contains_only_allowlisted_model_sources(tmp_path):
    workspace = _materialize(tmp_path)
    run_manifest = json.loads(workspace.manifest_path.read_text(encoding="utf-8"))
    manifest = json.loads(
        (workspace.inputs_dir / "model_view_manifest.json").read_text(encoding="utf-8")
    )
    visible = list((workspace.inputs_dir / "model_sources").rglob("*.scala"))

    assert [row["path"] for row in manifest["files"]] == [
        "src/main/scala/FirstCounter.scala"
    ]
    assert len(visible) == 1
    visible_text = visible[0].read_text(encoding="utf-8").lower()
    assert "buggy" not in visible_text
    assert "mutation" not in visible_text
    assert "countervariants" not in visible_text
    assert (workspace.project_workspace / "src/main/scala/CounterVariants.scala").is_file()
    assert not (workspace.project_workspace / "target").exists()
    assert (workspace.inputs_dir / "property_decomposition.json").is_file()
    assert "property_decomposition_sha256" in run_manifest["input_hashes"]
    assert (workspace.inputs_dir / "authoring_scope.json").is_file()
    assert "authoring_scope_sha256" in run_manifest["input_hashes"]
    assert "diagnosis_config_sha256" in run_manifest["input_hashes"]
    assert run_manifest["diagnosis"]["max_depth"] == 12


def test_workspace_fails_on_overwrite(tmp_path):
    workspace = _materialize(tmp_path)
    project = load_project_contract(PROJECT_PATH)
    configuration = load_generator_configuration(CONFIG_PATH, project)
    spec = load_public_spec_package(SPEC_PATH, LEDGER_PATH)
    with pytest.raises(FileExistsError):
        workspace.materialize(project, configuration, spec)
