"""Lifecycle entrypoint for deterministic SpecFlow Stage 2."""

from __future__ import annotations

import json
from pathlib import Path
import shutil
from typing import Any, Dict, Optional

from src.core.artifact_contract import (
    file_sha256,
    validate_completed_stage,
    write_stage_outcome,
)

from .applicability import classify_package_applicability
from .assets import load_run_local_package
from .backend import JasperGoldBackend
from .config import SpecFlowRunConfig
from .elaboration import elaborate_verification_overlay
from .monitor_compiler import compile_reviewed_package
from .result_contract import build_operation_plan, reduce_property_results, write_json
from .stages import get_stage_spec
from .workspace import SpecFlowWorkspace


class SpecFlowRunnerError(RuntimeError):
    """Raised when a run cannot legally enter deterministic Stage 2."""


class CompileVerifyStage:
    """Stage-2 executor intentionally constructed without an LLM client."""

    def __init__(self, backend: Optional[Any] = None):
        self.backend = backend or JasperGoldBackend()

    def run(
        self, run_dir: Path, *, frozen_package_run: Optional[Path] = None
    ) -> Dict[str, Any]:
        workspace = load_existing_workspace(run_dir)
        manifest = _read_json(workspace.manifest_path)
        _validate_run_integrity(workspace, manifest)
        round_id = manifest.get("current_round")
        replay = frozen_package_run is not None
        package_workspace = (
            load_existing_workspace(frozen_package_run) if replay else workspace
        )
        package_manifest = _read_json(package_workspace.manifest_path)
        package_round = package_manifest.get("current_round")
        if package_manifest.get("review_state") != "approved":
            raise SpecFlowRunnerError("compile_verify requires an approved review")
        stage1 = package_workspace.stage_dir(package_round, "asset_authoring")
        if validate_completed_stage(stage1, get_stage_spec("asset_authoring")) is None:
            raise SpecFlowRunnerError("asset_authoring completion or hashes are invalid")
        if replay:
            _preserve_frozen_package_evidence(
                workspace=workspace,
                round_id=round_id,
                package_workspace=package_workspace,
                package_round=package_round,
            )
        stage2 = workspace.stage_dir(round_id, "compile_verify")
        if any(stage2.iterdir()):
            raise SpecFlowRunnerError("compile_verify stage directory is immutable once written")

        package_path = stage1 / "verification_package.json"
        package = load_run_local_package(package_path)
        if package["project_id"] != manifest.get("project_id"):
            raise SpecFlowRunnerError("verification package project does not match target run")
        for key in (
            "project_contract_sha256",
            "specification_sha256",
            "public_spec_package_sha256",
            "property_decomposition_sha256",
            "authoring_scope_sha256",
            "model_view_manifest_sha256",
        ):
            if package["input_hashes"].get(key) != manifest["input_hashes"].get(key):
                raise SpecFlowRunnerError(
                    f"frozen package input identity does not match target: {key}"
                )
        if not replay and package["configuration_id"] != manifest["configuration_id"]:
            raise SpecFlowRunnerError("verification package configuration does not match run")
        applicability = classify_package_applicability(
            package,
            _read_json(package_workspace.indexes_dir / "chisel_semantic_index.json"),
            _read_json(workspace.indexes_dir / "chisel_semantic_index.json"),
            manifest["configuration_id"],
        )
        if applicability["classification"] == "not_applicable":
            reasons = sorted(
                {
                    reason
                    for row in applicability["binding_rows"]
                    for reason in row["reasons"]
                }
            )
            raise SpecFlowRunnerError(
                "verification package is not applicable to target configuration: "
                + ",".join(reasons)
            )
        applicability_path = stage2 / "package_applicability.json"
        write_json(applicability_path, applicability)
        verification_package_ref = {
            "schema_version": "verification_package_ref.v1",
            "mode": "frozen_replay" if replay else "authored_run",
            "package_id": package["package_id"],
            "source_run": str(package_workspace.run_dir),
            "path": str(package_path.relative_to(package_workspace.run_dir)),
            "sha256": file_sha256(package_path),
            "review_record_sha256": package["review"]["review_record_sha256"],
            "source_configuration_id": package["configuration_id"],
            "target_configuration_id": manifest["configuration_id"],
            "applicability": applicability["classification"],
            "applicability_sha256": file_sha256(applicability_path),
        }
        write_json(stage2 / "verification_package_ref.json", verification_package_ref)

        compiled = compile_reviewed_package(
            workspace,
            output_dir=stage2,
            package_path=package_path,
            frozen_replay=replay,
        )
        certificate = elaborate_verification_overlay(
            workspace.project_workspace,
            stage2,
            configuration_id=manifest["configuration_id"],
            verification_package_path=package_path,
            overlay_manifest_path=compiled.overlay_manifest_path,
            source_assertion_delta_path=compiled.assertion_delta_path,
        )
        certificate_path = stage2 / "elaboration_certificate.json"
        operation_plan = build_operation_plan(
            certificate,
            certificate_path=certificate_path,
            verification_package_sha256=file_sha256(package_path),
        )
        operation_plan_path = stage2 / "verification_operation_plan.json"
        write_json(operation_plan_path, operation_plan)
        project = _read_json(workspace.inputs_dir / "project_contract.json")
        backend_result = self.backend.run(
            stage2, certificate, operation_plan, project["formal"]
        )
        trace_manifest_path = stage2 / "trace_manifest.json"
        if not trace_manifest_path.is_file():
            raise SpecFlowRunnerError("formal backend did not write trace_manifest.json")
        _validate_trace_manifest(
            _read_json(trace_manifest_path),
            operation_plan_path,
            backend_result["operation_results"],
        )
        result_map, semantic_evidence = reduce_property_results(
            operation_plan,
            backend_result["operation_results"],
            operation_plan_path=operation_plan_path,
            trace_manifest_sha256=file_sha256(trace_manifest_path),
            tool=backend_result["tool"],
        )
        write_json(stage2 / "property_result_map.json", result_map)
        write_json(stage2 / "semantic_evidence.json", semantic_evidence)
        result = write_stage_outcome(
            stage2,
            get_stage_spec("compile_verify"),
            {
                "success": True,
                "status": "completed",
                "round_id": round_id,
                "model_calls": 0,
                "execution_status": result_map["execution_status"],
                "formal_outcome": result_map["formal_outcome"],
                "evidence_status": result_map["evidence_status"],
                "semantic_candidate": result_map["semantic_candidate"],
                "expected_operation_count": result_map["expected_operation_count"],
                "accounted_operation_count": result_map["accounted_operation_count"],
            },
            source_state=manifest,
        )
        _update_round_state(workspace, round_id, "compile_verify_complete")
        return result


def run_compile_verify(
    run_dir: Path,
    *,
    timeout_seconds: int = 300,
    per_property_seconds: int = 60,
) -> Dict[str, Any]:
    return CompileVerifyStage(
        JasperGoldBackend(timeout_seconds, per_property_seconds)
    ).run(run_dir)


def run_frozen_package_replay(
    target_run_dir: Path,
    frozen_package_run: Path,
    *,
    timeout_seconds: int = 300,
    per_property_seconds: int = 60,
) -> Dict[str, Any]:
    """Run Stage 2 on another opaque configuration without Stage-1 authoring."""

    return CompileVerifyStage(
        JasperGoldBackend(timeout_seconds, per_property_seconds)
    ).run(target_run_dir, frozen_package_run=frozen_package_run)


def load_existing_workspace(run_dir: Path) -> SpecFlowWorkspace:
    run_dir = Path(run_dir).resolve()
    inputs = run_dir / "inputs"
    if not (run_dir / "manifest.json").is_file():
        raise SpecFlowRunnerError("SpecFlow manifest is missing")
    config = SpecFlowRunConfig(
        project_contract=inputs / "project_contract.json",
        specification=inputs / "specification.md",
        configuration=inputs / "configuration.json",
        run_root=run_dir.parent,
    )
    return SpecFlowWorkspace(run_dir, config)


def _preserve_frozen_package_evidence(
    *,
    workspace: SpecFlowWorkspace,
    round_id: int,
    package_workspace: SpecFlowWorkspace,
    package_round: int,
) -> None:
    """Preserve the reviewed package authority inside a replay run.

    A frozen replay intentionally makes no Stage-1 model calls and does not
    claim a second authoring completion.  It still needs a durable local copy
    of the exact reviewed package and review record so a later evidence audit
    does not depend only on an external source-run path.
    """

    source_stage1 = package_workspace.stage_dir(package_round, "asset_authoring")
    target_stage1 = workspace.stage_dir(round_id, "asset_authoring")
    if any(target_stage1.iterdir()):
        raise SpecFlowRunnerError(
            "frozen replay asset_authoring evidence directory is not empty"
        )
    source_package = source_stage1 / "verification_package.json"
    source_review = source_stage1 / "review_record.json"
    if not source_package.is_file() or not source_review.is_file():
        raise SpecFlowRunnerError(
            "frozen replay source lacks package or review evidence"
        )
    package_sha256 = file_sha256(source_package)
    review_sha256 = file_sha256(source_review)
    package = _read_json(source_package)
    if package.get("review", {}).get("review_record_sha256") != review_sha256:
        raise SpecFlowRunnerError("frozen replay review/package identity mismatch")

    target_package = target_stage1 / "verification_package.json"
    target_review = target_stage1 / "review_record.json"
    shutil.copyfile(source_package, target_package)
    shutil.copyfile(source_review, target_review)
    write_json(
        target_stage1 / "frozen_package_provenance.json",
        {
            "schema_version": "frozen_package_provenance.v1",
            "status": "preserved",
            "authority": "source_run_external_review",
            "model_calls": 0,
            "source_run": str(package_workspace.run_dir),
            "source_round": package_round,
            "package_id": package["package_id"],
            "verification_package_sha256": package_sha256,
            "review_record_sha256": review_sha256,
        },
    )


def _update_round_state(
    workspace: SpecFlowWorkspace, round_id: int, state: str
) -> None:
    round_path = workspace.round_dir(round_id) / "round.json"
    value = _read_json(round_path)
    value["state"] = state
    write_json(round_path, value)


def _read_json(path: Path) -> Dict[str, Any]:
    value = json.loads(Path(path).read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise SpecFlowRunnerError(f"JSON object required: {path}")
    return value


def _validate_trace_manifest(
    manifest: Dict[str, Any],
    operation_plan_path: Path,
    operation_results: list[Dict[str, Any]],
) -> None:
    plan = _read_json(operation_plan_path)
    expected = {
        row["operation_id"]: row["emitted_property_id"]
        for row in plan.get("operations", [])
    }
    if set(manifest) != {"schema_version", "operation_plan_sha256", "traces"}:
        raise SpecFlowRunnerError("trace manifest has an invalid exact schema")
    if (
        manifest.get("schema_version") != "trace_manifest.v1"
        or manifest.get("operation_plan_sha256") != file_sha256(operation_plan_path)
        or not isinstance(manifest.get("traces"), list)
    ):
        raise SpecFlowRunnerError("trace manifest identity mismatch")
    by_operation: Dict[str, str] = {}
    for row in manifest["traces"]:
        required = {
            "operation_id",
            "emitted_property_id",
            "path",
            "format",
            "sha256",
            "bytes",
        }
        if not isinstance(row, dict) or set(row) != required:
            raise SpecFlowRunnerError("trace manifest row has an invalid exact schema")
        operation_id = row["operation_id"]
        if operation_id in by_operation:
            raise SpecFlowRunnerError("trace manifest contains a duplicate operation")
        if expected.get(operation_id) != row["emitted_property_id"]:
            raise SpecFlowRunnerError("trace manifest property identity mismatch")
        path = Path(row["path"])
        if (
            not path.is_file()
            or path.stat().st_size != row["bytes"]
            or file_sha256(path) != row["sha256"]
        ):
            raise SpecFlowRunnerError("trace artifact hash or size mismatch")
        by_operation[operation_id] = str(path)
    for result in operation_results:
        trace_path = result.get("trace_path")
        manifest_path = by_operation.get(result.get("operation_id"))
        if (trace_path is None) != (manifest_path is None):
            raise SpecFlowRunnerError("result/trace manifest membership mismatch")
        if trace_path is not None and str(Path(trace_path)) != manifest_path:
            raise SpecFlowRunnerError("result/trace manifest path mismatch")


def _validate_run_integrity(
    workspace: SpecFlowWorkspace, manifest: Dict[str, Any]
) -> None:
    input_paths = {
        "project_contract_sha256": workspace.inputs_dir / "project_contract.json",
        "configuration_sha256": workspace.inputs_dir / "configuration.json",
        "specification_sha256": workspace.inputs_dir / "specification.md",
        "public_spec_package_sha256": workspace.inputs_dir / "public_spec_package.json",
        "property_decomposition_sha256": workspace.inputs_dir / "property_decomposition.json",
        "authoring_scope_sha256": workspace.inputs_dir / "authoring_scope.json",
        "model_view_manifest_sha256": workspace.inputs_dir / "model_view_manifest.json",
        "diagnosis_config_sha256": workspace.inputs_dir / "diagnosis_config.json",
    }
    for key, path in input_paths.items():
        if not path.is_file() or file_sha256(path) != manifest.get("input_hashes", {}).get(key):
            raise SpecFlowRunnerError(f"run input hash drifted: {key}")
    for name, digest in manifest.get("index_hashes", {}).items():
        path = workspace.indexes_dir / (name + ".json")
        if not path.is_file() or file_sha256(path) != digest:
            raise SpecFlowRunnerError(f"run index hash drifted: {name}")
    semantic = _read_json(workspace.indexes_dir / "chisel_semantic_index.json")
    source_hashes: Dict[str, str] = {}
    for row in semantic.get("objects", []):
        anchor = row.get("source_anchor", {})
        relative = anchor.get("path")
        digest = anchor.get("source_sha256")
        if not isinstance(relative, str) or not isinstance(digest, str):
            raise SpecFlowRunnerError("semantic index contains an invalid source anchor")
        previous = source_hashes.setdefault(relative, digest)
        if previous != digest:
            raise SpecFlowRunnerError("semantic index contains conflicting source hashes")
    for relative, digest in source_hashes.items():
        path = (workspace.project_workspace / relative).resolve()
        try:
            path.relative_to(workspace.project_workspace)
        except ValueError as exc:
            raise SpecFlowRunnerError("semantic source path escapes the project") from exc
        if not path.is_file() or file_sha256(path) != digest:
            raise SpecFlowRunnerError(f"indexed Chisel source drifted: {relative}")
