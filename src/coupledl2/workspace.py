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
from .property_catalog import PropertyCatalog, load_property_profile, public_catalog
from .prompt_context import build_prompt_bundle
from .skills import install_context_assets, stage_rule_paths, stage_skill_paths
from .stages import COUPLEDL2_STAGES, STAGE_SPECS, get_stage_spec


STAGE_INPUTS_SCHEMA_VERSION = "stage_inputs.v1"
MANIFEST_SCHEMA_VERSION = "coupledl2_run_manifest.v2"
PROTOCOL_ASSET_ROOT = Path(__file__).with_name("protocol_assets") / "tilelink"


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
    prompt_bundle: List[Dict[str, Any]]
    prompt_asset_total_chars: int
    stage_inputs: Dict[str, Any]

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
            "prompt_bundle": self.prompt_bundle,
            "prompt_asset_total_chars": self.prompt_asset_total_chars,
            "stage_inputs": self.stage_inputs,
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
    if manifest.get("schema_version") != MANIFEST_SCHEMA_VERSION:
        raise ValueError("unsupported CoupledL2 manifest schema version")
    required = {
        "schema_version", "case_name", "original_case_path",
        "workspace_case_path", "run_dir", "workspace_dir", "copy_strategy",
        "verify_mode", "input_mode", "property_profile", "stages",
        "entry_stage", "skipped_stages", "preflight_result",
        "preflight_status",
    }
    missing = required - set(manifest)
    if missing:
        raise ValueError(f"CoupledL2 manifest missing fields: {sorted(missing)}")
    config = CoupledL2RunConfig(
        case_path=Path(manifest["original_case_path"]),
        property_profile=manifest["property_profile"],
        verify_mode=manifest["verify_mode"],
        input_mode=manifest["input_mode"],
        run_root=run_dir.parent,
        copy_strategy=manifest["copy_strategy"],
    )
    workspace_dir = run_dir / "workspace"
    case_workspace = workspace_dir / "case"
    expected_case = Path(manifest["workspace_case_path"]).resolve()
    if case_workspace.resolve() != expected_case or not case_workspace.is_dir():
        raise ValueError("manifest workspace path does not match the resume run directory")
    install_context_assets(workspace_dir)
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
    if stage == "bind_properties":
        skills = []
        rules = []
        prompt_bundle = []
    else:
        skills = stage_skill_paths(workspace.workspace_dir, stage, context_indexes)
        rules = stage_rule_paths(workspace.workspace_dir, stage)
        prompt_bundle = build_prompt_bundle(
            workspace.workspace_dir,
            rules=rules,
            skills=skills,
        )
    stage_inputs = _build_stage_inputs(
        workspace,
        stage=stage,
        stage_dir=stage_dir,
        snapshot_dir=snapshot_dir,
        skills=skills,
        rules=rules,
        context_indexes=context_indexes,
        prompt_bundle=prompt_bundle,
    )
    ctx = StageContext(
        stage=stage,
        stage_dir=stage_dir,
        snapshot_dir=snapshot_dir,
        skills=skills,
        rules=rules,
        context_indexes=context_indexes,
        prompt_bundle=prompt_bundle,
        prompt_asset_total_chars=sum(asset["chars"] for asset in prompt_bundle),
        stage_inputs=stage_inputs,
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
        "schema_version": MANIFEST_SCHEMA_VERSION,
        "case_name": config.case_name,
        "original_case_path": str(config.case_path),
        "workspace_case_path": str(case_workspace),
        "run_dir": str(run_dir),
        "workspace_dir": str(workspace_dir),
        "copy_strategy": config.copy_strategy,
        "verify_mode": config.verify_mode,
        "input_mode": config.input_mode,
        "property_profile": config.property_profile,
        "stages": COUPLEDL2_STAGES,
        "entry_stage": "bind_properties",
        "skipped_stages": ["build_top_module"],
        "preflight_result": "results/preflight/preflight_result.json",
        "preflight_status": "pending",
    }


def _load_stage_indexes(indexes_dir: Path, stage: str) -> Dict[str, Dict[str, Any]]:
    required = {
        "bind_properties": [
            "build_contract",
            "formal_surface",
            "tl_signal_index",
            "observer_index",
        ],
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
    _write_json(ctx.stage_dir / "stage_inputs.json", ctx.stage_inputs)


def _build_stage_inputs(
    workspace: CoupledL2Workspace,
    *,
    stage: str,
    stage_dir: Path,
    snapshot_dir: Path,
    skills: List[Path],
    rules: List[Path],
    context_indexes: Dict[str, Dict[str, Any]],
    prompt_bundle: List[Dict[str, Any]],
) -> Dict[str, Any]:
    """Build the complete in-memory stage input contract."""
    payload = {
        "schema_version": STAGE_INPUTS_SCHEMA_VERSION,
        "stage": stage,
        "stage_dir": str(stage_dir),
        "snapshot_dir": str(snapshot_dir),
        "skills": [_rel_to_workspace(path, workspace.workspace_dir) for path in skills],
        "rules": [_rel_to_workspace(path, workspace.workspace_dir) for path in rules],
        "prompt_assets": [
            {key: asset[key] for key in ("path", "sha256", "chars")}
            for asset in prompt_bundle
        ],
        "prompt_asset_total_chars": sum(asset["chars"] for asset in prompt_bundle),
        "context_indexes": sorted(context_indexes.keys()),
        "previous_stage_handoffs": _load_previous_handoffs(workspace, stage),
        "case_name": workspace.config.case_name,
        "verify_mode": workspace.config.verify_mode,
        "input_mode": workspace.config.input_mode,
        "property_profile": workspace.config.property_profile,
        "chisel_compatibility": context_indexes.get("build_contract", {}).get("chisel"),
    }
    if stage == "bind_properties":
        catalog = load_property_profile(workspace.config.property_profile)
        build_contract = context_indexes.get("build_contract", {})
        formal_surface = context_indexes.get("formal_surface", {})
        preflight_result = json.loads(
            (workspace.results_dir / "preflight" / "preflight_result.json").read_text(
                encoding="utf-8"
            )
        )
        payload["build_contract_summary"] = {
            "recommended_make_target": build_contract.get("recommended_make_target"),
            "verify_top_files": [
                _tool_visible_path(path)
                for path in build_contract.get("verify_top_files", [])
            ],
            "generated_verilog_globs": [
                _tool_visible_path(path)
                for path in build_contract.get("generated_verilog_globs", [])
            ],
        }
        payload["formal_surface_summary"] = _formal_surface_summary(formal_surface)
        payload["tilelink_index_summary"] = _tilelink_index_summary(context_indexes)
        payload["protocol_evidence"] = build_protocol_evidence(catalog)
        payload["preflight_gate"] = {
            key: preflight_result.get("gate", {}).get(key)
            for key in (
                "source_assertion_count",
                "source_boringutils_count",
                "baseline_build_success",
                "generated_assertion_count",
            )
        }
        payload["preflight"] = {
            "result": "results/preflight/preflight_result.json",
            "baseline_build": "results/preflight/baseline_build_result.json",
            "generated_assertion_scan": "results/preflight/generated_assertion_scan.json",
        }
        payload["property_catalog"] = public_catalog(catalog)
    elif stage == "waveform_explanation":
        stage2_dir = (
            workspace.results_dir
            / "by_stage"
            / get_stage_spec("bind_properties").directory_name
        )
        stage3_dir = (
            workspace.results_dir
            / "by_stage"
            / get_stage_spec("invoke_verification").directory_name
        )
        result_map_path = stage3_dir / "property_result_map.json"
        manifest_path = stage2_dir / "binding_manifest.json"
        if result_map_path.is_file() and manifest_path.is_file():
            result_map = json.loads(result_map_path.read_text(encoding="utf-8"))
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
            instance = manifest["instances"][0]
            failed = []
            for prop in result_map.get("properties", []):
                for result in prop.get("jaspergold_properties", []):
                    if result.get("status") == "cex":
                        failed.append(
                            {
                                "instance_id": prop.get("instance_id"),
                                "property_schema_id": prop.get(
                                    "property_schema_id"
                                ),
                                "template_id": prop.get("template_id"),
                                "base_label": prop.get("base_label"),
                                "source": prop.get("source"),
                                "protocol_rule": prop.get("protocol_rule"),
                                "binding_manifest_path": prop.get(
                                    "binding_manifest_path"
                                ),
                                "bindings": instance.get("bindings"),
                                "parameters": instance.get("parameters"),
                                "rtl_label": result.get("rtl_label"),
                                "jaspergold_property_id": result.get(
                                    "jaspergold_property_id"
                                ),
                                "counterexample_path": result.get(
                                    "counterexample_path"
                                ),
                            }
                        )
            payload["failed_property_traces"] = failed
    return payload


def build_protocol_evidence(catalog: PropertyCatalog) -> Dict[str, Any]:
    """Return bounded protocol evidence for protocol-sourced schemas."""
    protocol_sources = [
        (schema_id, schema["source"])
        for schema_id, schema in sorted(catalog.schemas.items())
        if schema.get("source", {}).get("kind") == "protocol_requirement"
    ]
    if not protocol_sources:
        return {
            "schema_version": "protocol_evidence.v1",
            "document": None,
            "source_sha256": None,
            "rules": [],
        }

    rules_path = PROTOCOL_ASSET_ROOT / "rules.json"
    if not rules_path.is_file():
        raise ValueError(f"protocol evidence rules not found: {rules_path}")
    rules_index = json.loads(rules_path.read_text(encoding="utf-8"))
    if rules_index.get("schema_version") != "tilelink_rule_index.v1":
        raise ValueError("unsupported protocol rule index schema")
    source_sha256 = rules_index.get("source_sha256")
    evidence: List[Dict[str, Any]] = []
    for schema_id, source in protocol_sources:
        matches = [
            rule
            for rule in rules_index.get("rules", [])
            if rule.get("document") == source["document"]
            and rule.get("locator") == source["locator"]
            and schema_id in rule.get("candidate_schema_ids", [])
        ]
        if len(matches) != 1:
            raise ValueError(
                "protocol evidence not found for "
                f"{schema_id} at {source['document']} {source['locator']}"
            )
        rule = matches[0]
        statement = str(rule.get("statement", ""))
        evidence.append(
            {
                "rule_id": rule["rule_id"],
                "locator": rule["locator"],
                "statement": statement[:220],
                "source_sha256": source_sha256,
            }
        )
    return {
        "schema_version": "protocol_evidence.v1",
        "document": rules_index.get("document_id"),
        "source_sha256": source_sha256,
        "rules": evidence[:3],
    }


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


def _tilelink_index_summary(context_indexes: Dict[str, Dict[str, Any]]) -> Dict[str, Any]:
    tl_index = context_indexes.get("tl_signal_index", {})
    observer_index = context_indexes.get("observer_index", {})
    tl_candidates = tl_index.get("candidates", [])
    observer_candidates = observer_index.get("candidates", [])
    top_candidate_ids = [
        item["candidate_id"]
        for item in observer_candidates[:4] + tl_candidates[:8]
        if "candidate_id" in item
    ]
    module_counts: Dict[str, int] = {}
    for source in (tl_index.get("module_counts", {}), observer_index.get("module_counts", {})):
        for module, count in source.items():
            module_counts[module] = module_counts.get(module, 0) + int(count)
    return {
        "tl_signal_candidate_count": int(tl_index.get("candidate_count", 0)),
        "observer_candidate_count": int(observer_index.get("candidate_count", 0)),
        "channels": tl_index.get("channels", []),
        "module_counts": dict(sorted(module_counts.items())),
        "top_candidate_ids": top_candidate_ids[:12],
    }


def _tool_visible_path(path: str) -> str:
    """Remove the persisted workspace/ prefix used by deterministic indexes."""
    prefix = "workspace/"
    return path[len(prefix):] if path.startswith(prefix) else path


def _write_json(path: Path, value: Dict[str, Any]) -> None:
    path.write_text(json.dumps(value, indent=2, ensure_ascii=False, sort_keys=True) + "\n", encoding="utf-8")


def _append_jsonl(path: Path, value: Dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("a", encoding="utf-8") as handle:
        handle.write(json.dumps(value, ensure_ascii=False, sort_keys=True) + "\n")
