"""Deterministic CoupledL2 cleanup and baseline-build gate."""

from __future__ import annotations

import hashlib
import json
import re
from pathlib import Path
from typing import Any, Dict, Iterable

from .indexer import generate_indexes
from .formal_contract import load_formal_contract
from .preprocess import (
    build_baseline_assertion_inventory,
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
        formal_contract = load_formal_contract(
            catalog.formal_contract_id,
            profile_id=catalog.profile["property_profile_id"],
            case_name=catalog.profile["case_name"],
            case_workspace=self.workspace.case_workspace,
        )
        formal_contract_artifact = formal_contract.artifact()
        _write_json(self.results_dir / "formal_contract.json", formal_contract_artifact)
        baseline_inventory_before = build_baseline_assertion_inventory(
            self.workspace.case_workspace,
            disabled_labels=formal_contract.payload["disabled_baseline_properties"],
        )
        _write_json(
            self.results_dir / "baseline_assertion_inventory_before.json",
            baseline_inventory_before,
        )
        prepared = prepare_profile_surface(self.workspace.case_workspace, catalog)
        preprocess = {
            "schema_version": "coupledl2_preprocess",
            "success": True,
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
        preserved_inventory = build_baseline_assertion_inventory(
            self.workspace.case_workspace,
            disabled_labels=formal_contract.payload["disabled_baseline_properties"],
        )
        baseline_inventory = _merge_baseline_inventory(
            baseline_inventory_before,
            preserved_inventory,
        )
        _write_json(self.results_dir / "preprocess_report.json", preprocess)
        _write_json(self.results_dir / "source_manifest_after.json", after_manifest)
        _write_json(self.results_dir / "formal_surface_after.json", after_surface)
        _write_json(
            self.results_dir / "baseline_assertion_inventory.json",
            baseline_inventory,
        )

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
            "inventory_complete": baseline_inventory["entry_count"]
            == baseline_inventory["preserved_count"] + baseline_inventory["disabled_count"],
            "source_assertion_count": after_surface["assertion_count"],
            "source_boringutils_count": after_surface["boringutils_count"],
            "baseline_build_success": bool(baseline.get("success")),
            "generated_assertion_count": generated_scan["assertion_count"],
            "baseline_cl2_label_count": generated_scan["cl2_label_count"],
            "formal_contract_sha256": formal_contract.sha256,
            "top_policy_satisfied": bool(baseline.get("top_module"))
            and str(baseline.get("top_module")).startswith(
                formal_contract.payload["top"]["name_prefix"]
            ),
        }
        success = (
            gate["cleanup_completed"]
            and gate["inventory_complete"]
            and gate["baseline_build_success"]
            and gate["top_policy_satisfied"]
        )
        result = {
            "schema_version": "coupledl2_preflight",
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
                "baseline_assertion_inventory": "results/preflight/baseline_assertion_inventory.json",
                "baseline_assertion_inventory_before": "results/preflight/baseline_assertion_inventory_before.json",
                "formal_contract": "results/preflight/formal_contract.json",
            },
        }
        _write_json(self.results_dir / "preflight_result.json", result)
        _update_manifest(self.workspace, result)
        return result


def _termination_reason(gate: Dict[str, Any]) -> str:
    if not gate["cleanup_completed"] or not gate["inventory_complete"]:
        return "preflight_cleanup_failed"
    if not gate["baseline_build_success"]:
        return "preflight_build_failed"
    if not gate["top_policy_satisfied"]:
        return "preflight_formal_top_mismatch"
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
    return {"schema_version": "source_manifest", "files": files}


def _merge_baseline_inventory(
    before: Dict[str, Any],
    after: Dict[str, Any],
) -> Dict[str, Any]:
    """Retain explicit records for profile-owned baseline removals."""
    after_keys = {
        (item["source_path"], item["kind"], item["sha256"])
        for item in after.get("entries", [])
    }
    disabled = []
    for original in before.get("entries", []):
        key = (original["source_path"], original["kind"], original["sha256"])
        if key in after_keys:
            continue
        item = dict(original)
        item["policy"] = "disabled"
        item["reason"] = "profile_owned_cleanup_or_generated_region"
        disabled.append(item)
    entries = [*after.get("entries", []), *disabled]
    entries.sort(key=lambda item: (item["source_path"], item["line"], item["kind"]))
    return {
        "schema_version": "baseline_assertion_inventory",
        "entries": entries,
        "entry_count": len(entries),
        "preserved_count": sum(item["policy"] == "preserved" for item in entries),
        "disabled_count": sum(item["policy"] == "disabled" for item in entries),
    }


def _scan_generated_assertions(paths: Iterable[str]) -> Dict[str, Any]:
    records = []
    property_labels = []
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
            for label in re.findall(r"\b(?:CL2|TL)_[A-Z0-9_]+\b", line):
                property_labels.append(
                    {"path": str(path), "line": line_no, "label": label}
                )
    return {
        "schema_version": "generated_assertion_scan",
        "assertion_count": len(records),
        "assertions": records,
        "cl2_label_count": len(property_labels),
        "cl2_labels": property_labels,
    }


def _remove_copied_generated_rtl(case_workspace: Path) -> list:
    removed = []
    roots = [
        case_workspace / "Chisel" / "generated",
        case_workspace / "Chisel" / "Verilog",
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
