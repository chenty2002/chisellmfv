"""Workspace and stage initialization for CoupledL2 workflows."""

from __future__ import annotations

import json
import re
import shutil
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from typing import Any, Dict, List
from uuid import uuid4

from .config import CoupledL2RunConfig
from .file_policy import ignored_copy_entry
from .skills import install_context_assets, stage_rule_paths, stage_skill_paths
from .stages import COUPLEDL2_STAGES, STAGE_SPECS, get_stage_spec


STAGE_INPUTS_SCHEMA_VERSION = "stage_inputs.v1"


@dataclass(frozen=True)
class CoupledL2Workspace:
    run_dir: Path
    workspace_dir: Path
    case_workspace: Path
    indexes_dir: Path
    logs_dir: Path
    results_dir: Path
    manifest_path: Path
    config: CoupledL2RunConfig


@dataclass(frozen=True)
class StageContext:
    stage: str
    stage_dir: Path
    snapshot_dir: Path
    skills: List[Path]
    rules: List[Path]
    context_indexes: Dict[str, Dict[str, Any]]

    def to_dict(self, tool_root: Path) -> Dict[str, Any]:
        payload = {
            "stage": self.stage,
            "stage_dir": _rel_to_tool_root(self.stage_dir, tool_root),
            "stage_inputs_path": _rel_to_tool_root(self.stage_dir / "stage_inputs.json", tool_root),
            "snapshot_dir": _rel_to_tool_root(self.snapshot_dir, tool_root),
            "skills": [_rel_to_tool_root(path, tool_root) for path in self.skills],
            "rules": [_rel_to_tool_root(path, tool_root) for path in self.rules],
            "context_indexes": sorted(self.context_indexes.keys()),
            "context_index_paths": {
                name: f"indexes/{name}.json" for name in sorted(self.context_indexes.keys())
            },
        }
        chisel = self.context_indexes.get("build_contract", {}).get("chisel")
        if chisel:
            payload["chisel_compatibility"] = chisel
        formal_surface = self.context_indexes.get("formal_surface")
        if formal_surface:
            payload["formal_surface_summary"] = _formal_surface_summary(formal_surface)
        return payload


def create_coupledl2_workspace(config: CoupledL2RunConfig) -> CoupledL2Workspace:
    """Create one isolated run workspace; preflight populates indexes afterwards."""
    run_dir = _allocate_run_dir(config.run_root, config.case_name)
    workspace_dir = run_dir / "workspace"
    case_workspace = workspace_dir / "case"
    indexes_dir = run_dir / "indexes"
    logs_dir = run_dir / "logs"
    results_dir = run_dir / "results"

    for directory in [workspace_dir, indexes_dir, logs_dir, results_dir]:
        directory.mkdir(parents=True, exist_ok=True)

    shutil.copytree(config.case_path, case_workspace, ignore=_ignore_copy_entries)
    install_context_assets(workspace_dir)
    _create_stage_dirs(results_dir)

    manifest_path = run_dir / "manifest.json"
    manifest = _build_manifest(config, run_dir, workspace_dir, case_workspace)
    _write_json(manifest_path, manifest)

    _append_jsonl(logs_dir / "events.jsonl", {
        "event": "workflow_initialized",
        "case_name": config.case_name,
        "run_dir": str(run_dir),
    })

    return CoupledL2Workspace(
        run_dir=run_dir,
        workspace_dir=workspace_dir,
        case_workspace=case_workspace,
        indexes_dir=indexes_dir,
        logs_dir=logs_dir,
        results_dir=results_dir,
        manifest_path=manifest_path,
        config=config,
    )


def load_coupledl2_workspace(run_dir: Path) -> CoupledL2Workspace:
    """Load an existing run without copying the original case again."""
    run_dir = Path(run_dir).resolve()
    manifest_path = run_dir / "manifest.json"
    if not manifest_path.is_file():
        raise FileNotFoundError(f"CoupledL2 manifest not found: {manifest_path}")
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    config = CoupledL2RunConfig(
        case_path=Path(manifest["original_case_path"]),
        verify_mode=manifest["verify_mode"],
        input_mode=manifest["input_mode"],
        property_category=manifest["property_category"],
        run_root=run_dir.parent,
        copy_strategy=manifest["copy_strategy"],
    )
    workspace_dir = run_dir / "workspace"
    case_workspace = workspace_dir / "case"
    expected_case = Path(manifest["workspace_case_path"]).resolve()
    if case_workspace.resolve() != expected_case or not case_workspace.is_dir():
        raise ValueError("manifest workspace path does not match the resume run directory")
    return CoupledL2Workspace(
        run_dir=run_dir,
        workspace_dir=workspace_dir,
        case_workspace=case_workspace,
        indexes_dir=run_dir / "indexes",
        logs_dir=run_dir / "logs",
        results_dir=run_dir / "results",
        manifest_path=manifest_path,
        config=config,
    )


def initialize_stage_context(workspace: CoupledL2Workspace, stage: str) -> StageContext:
    """Load the stage-specific context slice for the active workflow."""
    spec = get_stage_spec(stage)
    stage_dir = workspace.results_dir / "by_stage" / spec.directory_name
    stage_dir.mkdir(parents=True, exist_ok=True)
    snapshot_dir = stage_dir / "source_snapshot"
    snapshot_dir.mkdir(parents=True, exist_ok=True)

    context_indexes = _load_stage_indexes(workspace.indexes_dir, stage)
    ctx = StageContext(
        stage=stage,
        stage_dir=stage_dir,
        snapshot_dir=snapshot_dir,
        skills=stage_skill_paths(workspace.workspace_dir, stage, context_indexes),
        rules=stage_rule_paths(workspace.workspace_dir, stage),
        context_indexes=context_indexes,
    )
    _write_stage_inputs(workspace, ctx)
    _append_jsonl(workspace.logs_dir / "events.jsonl", {
        "event": "stage_initialized",
        "stage": stage,
        "stage_dir": str(stage_dir),
    })
    return ctx


def _allocate_run_dir(run_root: Path, case_name: str) -> Path:
    run_root.mkdir(parents=True, exist_ok=True)
    timestamp = datetime.now().strftime("%Y%m%d-%H%M%S")
    slug = re.sub(r"[^a-zA-Z0-9_.-]+", "-", case_name.lower())[:64].strip("-") or "coupledl2"
    return run_root / f"{timestamp}-{slug}-{uuid4().hex[:8]}"


def _ignore_copy_entries(_directory: str, names: List[str]) -> set:
    return {name for name in names if ignored_copy_entry(name)}


def _create_stage_dirs(results_dir: Path) -> None:
    for spec in STAGE_SPECS:
        (results_dir / "by_stage" / spec.directory_name).mkdir(parents=True, exist_ok=True)


def _build_manifest(
    config: CoupledL2RunConfig,
    run_dir: Path,
    workspace_dir: Path,
    case_workspace: Path,
) -> Dict[str, Any]:
    return {
        "case_name": config.case_name,
        "original_case_path": str(config.case_path),
        "workspace_case_path": str(case_workspace),
        "run_dir": str(run_dir),
        "workspace_dir": str(workspace_dir),
        "copy_strategy": config.copy_strategy,
        "verify_mode": config.verify_mode,
        "input_mode": config.input_mode,
        "property_category": config.property_category,
        "stages": COUPLEDL2_STAGES,
        "entry_stage": "write_assertions",
        "skipped_stages": ["build_top_module"],
        "preflight_result": "results/preflight/preflight_result.json",
        "preflight_status": "pending",
    }


def _load_stage_indexes(indexes_dir: Path, stage: str) -> Dict[str, Dict[str, Any]]:
    required = {
        "write_assertions": ["build_contract", "formal_surface"],
        "invoke_verification": ["build_contract", "formal_surface"],
        "waveform_explanation": ["build_contract", "formal_surface"],
        "propose_bugfix": ["build_contract", "formal_surface"],
    }[stage]
    return {
        name: json.loads((indexes_dir / f"{name}.json").read_text(encoding="utf-8"))
        for name in required
    }


def _write_stage_inputs(workspace: CoupledL2Workspace, ctx: StageContext) -> None:
    """Persist the stable input contract for one stage."""
    payload = {
        "schema_version": STAGE_INPUTS_SCHEMA_VERSION,
        "stage": ctx.stage,
        "stage_dir": str(ctx.stage_dir),
        "snapshot_dir": str(ctx.snapshot_dir),
        "skills": [_rel_to_workspace(path, workspace.workspace_dir) for path in ctx.skills],
        "rules": [_rel_to_workspace(path, workspace.workspace_dir) for path in ctx.rules],
        "context_indexes": sorted(ctx.context_indexes.keys()),
        "previous_stage_handoffs": _load_previous_handoffs(workspace, ctx.stage),
        "case_name": workspace.config.case_name,
        "verify_mode": workspace.config.verify_mode,
        "input_mode": workspace.config.input_mode,
        "property_category": workspace.config.property_category,
        "chisel_compatibility": ctx.context_indexes.get("build_contract", {}).get("chisel"),
    }
    if ctx.stage == "write_assertions":
        payload["preflight"] = {
            "result": "results/preflight/preflight_result.json",
            "baseline_build": "results/preflight/baseline_build_result.json",
            "generated_assertion_scan": "results/preflight/generated_assertion_scan.json",
        }
    _write_json(ctx.stage_dir / "stage_inputs.json", payload)


def _load_previous_handoffs(workspace: CoupledL2Workspace, stage: str) -> List[Dict[str, Any]]:
    """Load completed handoff records from stages before the current stage."""
    current_index = COUPLEDL2_STAGES.index(stage)
    handoffs: List[Dict[str, Any]] = []
    for previous_stage in COUPLEDL2_STAGES[:current_index]:
        path = (
            workspace.results_dir
            / "by_stage"
            / get_stage_spec(previous_stage).directory_name
            / "handoff.json"
        )
        if not path.is_file():
            continue
        handoffs.append(json.loads(path.read_text(encoding="utf-8")))
    return handoffs


def _rel_to_workspace(path: Path, workspace_dir: Path) -> str:
    return path.relative_to(workspace_dir).as_posix()


def _rel_to_tool_root(path: Path, tool_root: Path) -> str:
    root = tool_root.resolve()
    resolved = path.resolve()
    workspace = root / "workspace"
    if workspace.is_dir() and (resolved == workspace or workspace in resolved.parents):
        return resolved.relative_to(workspace).as_posix()
    return resolved.relative_to(root).as_posix()


def _formal_surface_summary(formal_surface: Dict[str, Any]) -> Dict[str, Any]:
    return {
        "uses_chiselfv": formal_surface.get("uses_chiselfv"),
        "uses_boring_utils": formal_surface.get("uses_boring_utils"),
        "uses_ltl": formal_surface.get("uses_ltl"),
    }


def _write_json(path: Path, value: Dict[str, Any]) -> None:
    path.write_text(json.dumps(value, indent=2, ensure_ascii=False, sort_keys=True) + "\n", encoding="utf-8")


def _append_jsonl(path: Path, value: Dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("a", encoding="utf-8") as handle:
        handle.write(json.dumps(value, ensure_ascii=False, sort_keys=True) + "\n")
