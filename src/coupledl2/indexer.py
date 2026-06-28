"""Lightweight CoupledL2 project index generation."""

from __future__ import annotations

import hashlib
import json
import re
from pathlib import Path
from typing import Any, Dict, List, Optional

from .config import CoupledL2RunConfig
from .workspace import CoupledL2Workspace


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


def refresh_indexes(workspace: CoupledL2Workspace) -> Dict[str, Any]:
    """Regenerate indexes and bind their hashes to the current source workspace."""
    indexes = generate_indexes(
        workspace.run_dir,
        workspace.case_workspace,
        workspace.config,
    )
    index_hashes = {
        name: _sha256_json(value)
        for name, value in indexes.items()
    }
    state = {
        "workspace_hash": compute_workspace_hash(workspace.case_workspace),
        "index_hashes": index_hashes,
    }
    manifest = json.loads(workspace.manifest_path.read_text(encoding="utf-8"))
    manifest.update(state)
    _write_json(workspace.manifest_path, manifest)
    return state


def compute_index_hashes(indexes_dir: Path) -> Dict[str, str]:
    """Hash the canonical JSON value of every persisted project index."""
    return {
        path.stem: _sha256_json(
            json.loads(path.read_text(encoding="utf-8"))
        )
        for path in sorted(indexes_dir.glob("*.json"))
    }


def compute_workspace_hash(case_workspace: Path) -> str:
    """Hash source/configuration files while excluding generated build products."""
    digest = hashlib.sha256()
    ignored_parts = {
        "generated",
        "out",
        "target",
        ".mill",
        ".bsp",
        ".metals",
        "repair_loop",
    }
    source_suffixes = {".scala", ".sc", ".sbt", ".py", ".sh"}
    for path in sorted(case_workspace.rglob("*")):
        relative_path = path.relative_to(case_workspace)
        if (
            not path.is_file()
            or not relative_path.parts
            or relative_path.parts[0] != "Chisel"
            or ignored_parts.intersection(relative_path.parts)
            or (
                path.name != "Makefile"
                and path.suffix.lower() not in source_suffixes
            )
        ):
            continue
        relative = relative_path.as_posix()
        digest.update(relative.encode("utf-8"))
        digest.update(b"\0")
        digest.update(path.read_bytes())
        digest.update(b"\0")
    return digest.hexdigest()


def _sha256_json(value: Dict[str, Any]) -> str:
    encoded = json.dumps(
        value,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    )
    return hashlib.sha256(encoded.encode("utf-8")).hexdigest()


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
        "chisel": detect_chisel_compatibility(chisel_dir, case_workspace),
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
    uses_ltl = False

    for path in sorted(chisel_dir.rglob("*.scala")):
        text = path.read_text(encoding="utf-8", errors="ignore")
        uses_chiselfv = uses_chiselfv or "chiselFv" in text or "Formal." in text
        uses_boring_utils = uses_boring_utils or "BoringUtils" in text
        uses_ltl = uses_ltl or "chisel3.ltl" in text or "AssertProperty" in text
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
        "uses_ltl": uses_ltl,
    }


def detect_chisel_compatibility(chisel_dir: Path, case_workspace: Path) -> Dict[str, Any]:
    """Infer the Chisel compatibility family from local build files."""
    candidates = [
        chisel_dir / "build.sc",
        chisel_dir / "common.sc",
        chisel_dir / "build.sbt",
    ]
    detected_from: List[str] = []
    deps: List[Dict[str, Optional[str]]] = []
    family = "unknown"
    version: Optional[str] = None
    scala_version: Optional[str] = None

    for path in candidates:
        if not path.is_file():
            continue
        text = path.read_text(encoding="utf-8", errors="ignore")
        rel = _rel(path, case_workspace)
        file_matched = False

        chisel6 = re.search(r'ivy"org\.chipsalliance::chisel:([^"]+)"', text)
        if chisel6:
            family = "chisel6"
            version = chisel6.group(1)
            deps.append({"organization": "org.chipsalliance", "artifact": "chisel", "version": version})
            file_matched = True

        chisel3 = re.search(r'ivy"edu\.berkeley\.cs::chisel3:([^"]+)"', text)
        if chisel3:
            family = "chisel3"
            version = chisel3.group(1)
            deps.append({"organization": "edu.berkeley.cs", "artifact": "chisel3", "version": version})
            file_matched = True

        default_chisel3 = re.search(r'"chisel3"\s*->\s*"([^"]+)"', text)
        if default_chisel3 and family == "unknown":
            family = "chisel3"
            version = default_chisel3.group(1)
            deps.append({"organization": "edu.berkeley.cs", "artifact": "chisel3", "version": version})
            file_matched = True

        scala_literal = re.search(r'override\s+def\s+scalaVersion\s*=\s*"([^"]+)"', text)
        scala_default = re.search(r'"scala"\s*->\s*"([^"]+)"', text)
        if scala_literal:
            scala_version = scala_literal.group(1)
            file_matched = True
        elif scala_default:
            scala_version = scala_default.group(1)
            file_matched = True

        if file_matched and rel not in detected_from:
            detected_from.append(rel)

    major_version = _major_version(version)
    if family == "unknown" and major_version == 3:
        family = "chisel3"
    elif family == "unknown" and major_version and major_version >= 6:
        family = "chisel6"

    assertion_skill = (
        "skills/chiselfv_chisel3_assertions.md"
        if family == "chisel3"
        else "skills/chiselfv_assertions.md"
    )
    forbidden_apis = []
    if family == "chisel3":
        forbidden_apis = [
            "chisel3.ltl",
            "AssertProperty",
            "Sequence",
            "fvAssert",
            "one-argument BoringUtils.bore(source)",
        ]

    return {
        "family": family,
        "version": version,
        "major_version": major_version,
        "scala_version": scala_version,
        "detected_from": detected_from,
        "dependencies": deps,
        "assertion_skill": assertion_skill,
        "forbidden_apis": forbidden_apis,
        "version_check": "read build.sc/common.sc/build.sbt to choose the versioned assertion skill before choosing assertion APIs",
    }


def _select_make_target(config: CoupledL2RunConfig) -> str:
    name = config.case_name.lower()
    category = config.property_category
    if category in {"deadlock", "peer_l2"} or "deadlock" in name or "peer-l2" in name:
        return "auto-l2l3l2"
    return "auto"


def _looks_like_assertion(line: str) -> bool:
    return bool(re.search(r"\b(assert|assume)\b|Formal\.(assert|assume)", line))


def _major_version(version: Optional[str]) -> Optional[int]:
    if not version:
        return None
    match = re.match(r"(\d+)", version)
    return int(match.group(1)) if match else None


def _rel(path: Path, case_workspace: Path) -> str:
    return "workspace/case/" + path.relative_to(case_workspace).as_posix()


def _write_json(path: Path, value: Dict[str, Any]) -> None:
    path.write_text(json.dumps(value, indent=2, ensure_ascii=False, sort_keys=True) + "\n", encoding="utf-8")
