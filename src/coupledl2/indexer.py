"""Lightweight CoupledL2 project index generation."""

from __future__ import annotations

import json
import re
from pathlib import Path
from typing import Any, Dict, List

from .config import CoupledL2RunConfig


def generate_indexes(run_dir: Path, case_workspace: Path, config: CoupledL2RunConfig) -> Dict[str, Dict[str, Any]]:
    """Generate the minimal indexes required by commit 1."""
    indexes_dir = run_dir / "indexes"
    indexes_dir.mkdir(parents=True, exist_ok=True)

    indexes = {
        "project_tree": build_project_tree(case_workspace),
        "build_contract": build_build_contract(case_workspace, config),
        "formal_surface": build_formal_surface(case_workspace),
    }
    for name, value in indexes.items():
        _write_json(indexes_dir / f"{name}.json", value)
    return indexes


def build_project_tree(case_workspace: Path) -> Dict[str, Any]:
    files: List[Dict[str, Any]] = []
    for path in sorted(case_workspace.rglob("*")):
        if path.is_file():
            files.append({
                "path": _rel(path, case_workspace),
                "size": path.stat().st_size,
            })
    return {
        "case_root": "workspace/case",
        "file_count": len(files),
        "files": files,
    }


def build_build_contract(case_workspace: Path, config: CoupledL2RunConfig) -> Dict[str, Any]:
    chisel_dir = case_workspace / "Chisel"
    verilog_dir = case_workspace / "Verilog"
    makefile = chisel_dir / "Makefile"
    setup_script = verilog_dir / "setup.sh"
    verify_top_files = sorted(chisel_dir.rglob("VerifyTop*.scala"))

    return {
        "case_name": config.case_name,
        "makefile": _rel(makefile, case_workspace) if makefile.is_file() else None,
        "setup_script": _rel(setup_script, case_workspace) if setup_script.is_file() else None,
        "verify_top_files": [_rel(path, case_workspace) for path in verify_top_files],
        "recommended_make_target": _select_make_target(config),
        "env": {
            "VERIFY_MODE": config.verify_mode,
            "VERIFY_INPUT_MODE": config.input_mode,
        },
        "generated_verilog_globs": [
            "workspace/case/Chisel/generated/**/*.sv",
            "workspace/case/Chisel/generated/**/*.v",
            "workspace/case/Verilog/VerifyTop*.sv",
        ],
    }


def build_formal_surface(case_workspace: Path) -> Dict[str, Any]:
    chisel_dir = case_workspace / "Chisel"
    assertion_records: List[Dict[str, Any]] = []
    uses_chiselfv = False
    uses_boring_utils = False

    for path in sorted(chisel_dir.rglob("*.scala")):
        text = path.read_text(encoding="utf-8", errors="ignore")
        uses_chiselfv = uses_chiselfv or "chiselFv" in text or "Formal." in text
        uses_boring_utils = uses_boring_utils or "BoringUtils" in text
        for line_no, line in enumerate(text.splitlines(), start=1):
            if _looks_like_assertion(line):
                assertion_records.append({
                    "path": _rel(path, case_workspace),
                    "line": line_no,
                    "text": line.strip(),
                })

    return {
        "case_root": "workspace/case",
        "assertion_count": len(assertion_records),
        "assertions": assertion_records,
        "uses_chiselfv": uses_chiselfv,
        "uses_boring_utils": uses_boring_utils,
    }


def _select_make_target(config: CoupledL2RunConfig) -> str:
    name = config.case_name.lower()
    category = config.property_category
    if category in {"deadlock", "peer_l2"} or "deadlock" in name or "peer-l2" in name:
        return "auto-l2l3l2"
    return "auto"


def _looks_like_assertion(line: str) -> bool:
    return bool(re.search(r"\b(assert|assume)\b|Formal\.(assert|assume)", line))


def _rel(path: Path, case_workspace: Path) -> str:
    return "workspace/case/" + path.relative_to(case_workspace).as_posix()


def _write_json(path: Path, value: Dict[str, Any]) -> None:
    path.write_text(json.dumps(value, indent=2, ensure_ascii=False, sort_keys=True) + "\n", encoding="utf-8")
