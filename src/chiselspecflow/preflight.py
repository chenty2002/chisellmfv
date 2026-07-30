"""Deterministic project materialization and semantic indexing."""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any, Dict

from .assets import load_reviewed_assets
from .config import (
    SpecFlowRunConfig,
    load_generator_configuration,
    load_project_contract,
)
from .elaboration import elaborate_baseline
from .semantic_index import merge_semantic_index
from .source_index import build_source_index
from .specification import load_public_spec_package
from .workspace import SpecFlowWorkspace


def prepare_workspace(
    config: SpecFlowRunConfig,
    run_dir: Path,
    suite_ledger: Path,
) -> SpecFlowWorkspace:
    """Materialize and index one run, then stop before authoring."""

    project = load_project_contract(config.project_contract)
    configuration = load_generator_configuration(config.configuration, project)
    public_spec = load_public_spec_package(config.specification, suite_ledger)
    if public_spec["family"] != project.project_id:
        raise ValueError("public spec family does not match project_id")
    workspace = SpecFlowWorkspace(run_dir, config)
    workspace.materialize(project, configuration, public_spec)

    model_manifest = json.loads(
        (workspace.inputs_dir / "model_view_manifest.json").read_text(encoding="utf-8")
    )
    source_path = workspace.indexes_dir / "source_index.json"
    source_index = build_source_index(
        workspace.inputs_dir / "model_sources",
        model_manifest,
        source_path,
        project.repository_root / "tools/chisel-source-indexer",
    )
    baseline_path = workspace.indexes_dir / "baseline_elaboration.json"
    baseline = elaborate_baseline(
        project,
        configuration,
        workspace.project_workspace,
        baseline_path,
    )
    semantic_path = workspace.indexes_dir / "chisel_semantic_index.json"
    matching_adapters = [
        row
        for row in load_reviewed_assets().api_adapters.values()
        if row.get("project_id") == project.project_id
        and row.get("strategy") == "wrapper"
    ]
    if len(matching_adapters) != 1:
        raise ValueError("project requires one exact reviewed wrapper adapter")
    merge_semantic_index(
        source_index,
        baseline,
        project,
        configuration,
        semantic_path,
        matching_adapters[0].get("hierarchical_observers", ()),
    )
    workspace.record_indexes(
        {
            "source_index": source_path,
            "baseline_elaboration": baseline_path,
            "chisel_semantic_index": semantic_path,
        }
    )
    return workspace
