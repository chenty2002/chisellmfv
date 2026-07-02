"""Deterministic CoupledL2 cleanup and baseline-build gate."""

from __future__ import annotations

import hashlib
import json
import re
from pathlib import Path
from typing import Any, Dict, Iterable

from .indexer import generate_indexes
from .preprocess import (
    patch_autoverify_outputs,
    prepare_profile_surface,
    scan_formal_surface,
)
from .property_catalog import load_property_profile
from .workspace import CoupledL2Workspace


class CoupledL2Preflight:
    def __init__(self, workspace: CoupledL2Workspace, backend: Any):
        self.workspace = workspace
        self.backend = backend
        self.results_dir = workspace.results_dir / "preflight"

    def run(self) -> Dict[str, Any]:
        self.results_dir.mkdir(parents=True, exist_ok=True)
        before_manifest = _source_manifest(self.workspace.case_workspace)
        before_surface = scan_formal_surface(self.workspace.case_workspace)
        _write_json(self.results_dir / "source_manifest_before.json", before_manifest)
        _write_json(self.results_dir / "formal_surface_before.json", before_surface)

        catalog = load_property_profile(self.workspace.config.property_profile)
        patched_autoverify = patch_autoverify_outputs(self.workspace.case_workspace)
        prepared = prepare_profile_surface(self.workspace.case_workspace, catalog)
        preprocess = {
            "schema_version": "coupledl2_preprocess.v3",
            "success": True,
            "patched_autoverify": patched_autoverify,
            "property_profile": self.workspace.config.property_profile,
            "prepared_surface": {
                "target_path": "workspace/case/"
                + prepared.target_path.relative_to(
                    self.workspace.case_workspace
                ).as_posix(),
                "marker_text": prepared.marker_text,
                "sha256_before": prepared.sha256_before,
                "sha256_after": prepared.sha256_after,
            },
            "generated_output_dir": "workspace/case/Chisel/generated",
        }
        preprocess["removed_generated_rtl"] = _remove_copied_generated_rtl(
            self.workspace.case_workspace
        )
        after_manifest = _source_manifest(self.workspace.case_workspace)
        after_surface = scan_formal_surface(self.workspace.case_workspace)
        _write_json(self.results_dir / "preprocess_report.json", preprocess)
        _write_json(self.results_dir / "source_manifest_after.json", after_manifest)
        _write_json(self.results_dir / "formal_surface_after.json", after_surface)

        cleanup_ok = bool(preprocess.get("success"))
        if cleanup_ok:
            generate_indexes(
                self.workspace.run_dir,
                self.workspace.case_workspace,
                self.workspace.config,
            )
            baseline = self.backend.run_baseline_build()
        else:
            baseline = {
                "success": False,
                "skipped": True,
                "error": "baseline build skipped because formal-surface cleanup failed",
                "generated_files": [],
            }
        _write_json(self.results_dir / "baseline_build_result.json", baseline)

        generated_scan = _scan_generated_assertions(baseline.get("generated_files", []))
        _write_json(self.results_dir / "generated_assertion_scan.json", generated_scan)
        gate = {
            "cleanup_completed": cleanup_ok,
            "source_assertion_count": after_surface["assertion_count"],
            "source_boringutils_count": after_surface["boringutils_count"],
            "baseline_build_success": bool(baseline.get("success")),
            "generated_assertion_count": generated_scan["assertion_count"],
            "baseline_cl2_label_count": generated_scan["cl2_label_count"],
        }
        success = (
            gate["cleanup_completed"]
            and gate["source_assertion_count"] == 0
            and gate["source_boringutils_count"] == 0
            and gate["baseline_build_success"]
            and gate["generated_assertion_count"] == 0
            and gate["baseline_cl2_label_count"] == 0
        )
        result = {
            "schema_version": "coupledl2_preflight.v1",
            "success": success,
            "termination_reason": _termination_reason(gate),
            "gate": gate,
            "artifacts": {
                "preprocess_report": "results/preflight/preprocess_report.json",
                "formal_surface_before": "results/preflight/formal_surface_before.json",
                "formal_surface_after": "results/preflight/formal_surface_after.json",
                "source_manifest_before": "results/preflight/source_manifest_before.json",
                "source_manifest_after": "results/preflight/source_manifest_after.json",
                "baseline_build": "results/preflight/baseline_build_result.json",
                "generated_assertion_scan": "results/preflight/generated_assertion_scan.json",
            },
        }
        _write_json(self.results_dir / "preflight_result.json", result)
        _update_manifest(self.workspace, result)
        return result


def _termination_reason(gate: Dict[str, Any]) -> str:
    if not gate["cleanup_completed"] or gate["source_assertion_count"] or gate["source_boringutils_count"]:
        return "preflight_cleanup_failed"
    if not gate["baseline_build_success"]:
        return "preflight_build_failed"
    if gate["generated_assertion_count"] or gate["baseline_cl2_label_count"]:
        return "preflight_generated_assertions_found"
    return "preflight_completed"


def _source_manifest(case_workspace: Path) -> Dict[str, Any]:
    files = []
    for path in sorted(path for path in case_workspace.rglob("*") if path.is_file()):
        data = path.read_bytes()
        files.append({
            "path": "workspace/case/" + path.relative_to(case_workspace).as_posix(),
            "size": len(data),
            "sha256": hashlib.sha256(data).hexdigest(),
        })
    return {"schema_version": "source_manifest.v1", "files": files}


def _scan_generated_assertions(paths: Iterable[str]) -> Dict[str, Any]:
    records = []
    cl2_labels = []
    pattern = re.compile(
        r"(?:\bassert\s*(?:property)?\s*\(|\$assert\b|"
        r"\$(?:error|fatal)\s*\(\s*\"Assertion failed)"
    )
    for value in paths:
        path = Path(value)
        if not path.is_file():
            continue
        for line_no, line in enumerate(path.read_text(encoding="utf-8", errors="ignore").splitlines(), 1):
            if pattern.search(line.split("//", 1)[0]):
                records.append({"path": str(path), "line": line_no, "text": line.strip()})
            for label in re.findall(r"\bCL2_[A-Z0-9_]+\b", line):
                cl2_labels.append(
                    {"path": str(path), "line": line_no, "label": label}
                )
    return {
        "schema_version": "generated_assertion_scan.v1",
        "assertion_count": len(records),
        "assertions": records,
        "cl2_label_count": len(cl2_labels),
        "cl2_labels": cl2_labels,
    }


def _remove_copied_generated_rtl(case_workspace: Path) -> list:
    removed = []
    roots = [
        case_workspace / "Chisel" / "generated",
        case_workspace / "Verilog",
    ]
    for root in roots:
        if not root.is_dir():
            continue
        for path in sorted(root.rglob("*")):
            if path.is_file() and path.suffix.lower() in {".v", ".sv"}:
                removed.append("workspace/case/" + path.relative_to(case_workspace).as_posix())
                path.unlink()
    return removed


def _update_manifest(workspace: CoupledL2Workspace, result: Dict[str, Any]) -> None:
    manifest = json.loads(workspace.manifest_path.read_text(encoding="utf-8"))
    manifest["preflight_status"] = "success" if result["success"] else "failed"
    _write_json(workspace.manifest_path, manifest)


def _write_json(path: Path, value: Dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(value, indent=2, ensure_ascii=False, sort_keys=True) + "\n",
        encoding="utf-8",
    )
