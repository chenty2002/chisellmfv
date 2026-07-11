"""Lightweight CoupledL2 project index generation."""

from __future__ import annotations

import hashlib
import json
import os
import re
from pathlib import Path
from typing import Any, Dict, List, Optional, Set

from .config import CoupledL2RunConfig
from .file_policy import PathIntent, evaluate_workspace_path
from .property_catalog import load_property_profile
from .workspace import CoupledL2Workspace


def generate_indexes(run_dir: Path, case_workspace: Path, config: CoupledL2RunConfig) -> Dict[str, Dict[str, Any]]:
    """Generate deterministic context indexes for the CoupledL2 workflow."""
    indexes_dir = run_dir / "indexes"
    indexes_dir.mkdir(parents=True, exist_ok=True)

    profile = load_property_profile(config.property_profile).profile
    roots = profile["index_roots"]
    indexes = {
        "project_tree": build_project_tree(case_workspace),
        "build_contract": build_build_contract(case_workspace, config),
        "formal_surface": build_formal_surface(case_workspace),
        "tl_signal_index": build_tl_signal_index(case_workspace, roots=roots),
        "observer_index": build_observer_index(case_workspace, roots=roots),
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
    source_suffixes = {".scala", ".sc", ".sbt", ".py", ".sh"}
    for path in _discoverable_files(case_workspace):
        relative_path = path.relative_to(case_workspace)
        if (
            not relative_path.parts
            or relative_path.parts[0] != "Chisel"
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
    filtered: List[Dict[str, Any]] = []
    for directory, dir_names, file_names in os.walk(case_workspace, followlinks=False):
        directory_path = Path(directory)
        allowed_dirs = []
        for name in sorted(dir_names):
            path = directory_path / name
            relative = path.relative_to(case_workspace)
            decision = evaluate_workspace_path(relative, intent=PathIntent.DISCOVER)
            if decision.allowed and not path.is_symlink():
                allowed_dirs.append(name)
            else:
                filtered.append({
                    "path": _rel(path, case_workspace),
                    "rule": decision.rule,
                    "reason": decision.reason,
                    "explicit_access": True,
                })
        dir_names[:] = allowed_dirs
        for name in sorted(file_names):
            path = directory_path / name
            relative = path.relative_to(case_workspace)
            decision = evaluate_workspace_path(relative, intent=PathIntent.DISCOVER)
            if decision.allowed:
                files.append({
                    "path": _rel(path, case_workspace),
                    "size": path.stat().st_size,
                })
    return {
        "case_root": "workspace/case",
        "file_count": len(files),
        "files": files,
        "filtered_paths": filtered,
    }


def build_build_contract(case_workspace: Path, config: CoupledL2RunConfig) -> Dict[str, Any]:
    chisel_dir = case_workspace / "Chisel"
    verilog_dir = case_workspace / "Verilog"
    makefile = chisel_dir / "Makefile"
    setup_script = verilog_dir / "setup.sh"
    verify_top_files = sorted(
        path
        for path in _discoverable_files(chisel_dir, policy_root=case_workspace)
        if path.match("VerifyTop*.scala")
    )

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
            "workspace/case/Chisel/Verilog/**/VerifyTop*.sv",
        ],
    }


def build_formal_surface(case_workspace: Path) -> Dict[str, Any]:
    chisel_dir = case_workspace / "Chisel"
    assertion_records: List[Dict[str, Any]] = []
    uses_chiselfv = False
    uses_boring_utils = False
    uses_ltl = False

    for path in _discoverable_files(chisel_dir, policy_root=case_workspace):
        if path.suffix != ".scala":
            continue
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


TL_FIELDS = {
    "opcode",
    "param",
    "size",
    "source",
    "address",
    "mask",
    "data",
    "corrupt",
    "sink",
    "denied",
}

OBSERVER_MODULES = {
    "SinkA",
    "SinkC",
    "SourceB",
    "SourceC",
    "RefillUnit",
    "GrantBuffer",
    "RequestBuffer",
    "MSHRCtl",
    "MSHR",
    "Directory",
    "MainPipe",
}

_TL_BUNDLE_RE = re.compile(r"\bTLBundle([A-E])?\b")
_IO_CHANNEL_RE = re.compile(r"\bio\.(in|out)\.([a-e])\b")
_TL_FIELD_RE = re.compile(
    r"\b((?:io\.(?:in|out)\.)?[a-e]|[A-Za-z_][A-Za-z0-9_]*(?:\.io)?\.[a-e]|io\.[A-Za-z_][A-Za-z0-9_]*)"
    r"\.bits\.(" + "|".join(sorted(TL_FIELDS)) + r")\b"
)
_HANDSHAKE_RE = re.compile(
    r"\b((?:io\.(?:in|out)\.)?[a-e]|[A-Za-z_][A-Za-z0-9_]*(?:\.io)?\.[a-e]|io\.[A-Za-z_][A-Za-z0-9_]*)"
    r"\.(valid|ready)\b"
)
_SCALA_SCOPE_RE = re.compile(r"\b(?:class|object|trait)\s+([A-Za-z_][A-Za-z0-9_]*)\b")


def build_tl_signal_index(
    case_workspace: Path, *, roots: Optional[List[str]] = None
) -> Dict[str, Any]:
    """Index discoverable Scala TileLink signals without reading generated output."""
    candidates: List[Dict[str, Any]] = []
    module_counts: Dict[str, int] = {}
    channels: Set[str] = set()
    files_scanned = 0

    for path in _discoverable_scala_sources(case_workspace, roots=roots):
        files_scanned += 1
        text = path.read_text(encoding="utf-8", errors="ignore")
        current_scope = path.stem
        for line_no, line in enumerate(text.splitlines(), start=1):
            scope_match = _SCALA_SCOPE_RE.search(line)
            if scope_match:
                current_scope = scope_match.group(1)

            for match in _TL_BUNDLE_RE.finditer(line):
                channel = match.group(1)
                roles = ["tl_bundle"]
                if channel:
                    roles.append(f"channel_{channel.lower()}")
                    channels.add(channel.lower())
                candidates.append(
                    _candidate(
                        candidate_type="tl_bundle",
                        roles=roles,
                        path=path,
                        case_workspace=case_workspace,
                        line_no=line_no,
                        expression=match.group(0),
                        module=current_scope,
                        description="TileLink bundle declaration or construction.",
                    )
                )

            for match in _IO_CHANNEL_RE.finditer(line):
                direction, channel = match.groups()
                channels.add(channel)
                candidates.append(
                    _candidate(
                        candidate_type="tl_channel_endpoint",
                        roles=[f"channel_{channel}", f"direction_{direction}"],
                        path=path,
                        case_workspace=case_workspace,
                        line_no=line_no,
                        expression=match.group(0),
                        module=current_scope,
                        description=f"TileLink {channel.upper()} channel endpoint on io.{direction}.",
                    )
                )

            for match in _HANDSHAKE_RE.finditer(line):
                signal, handshake = match.groups()
                channel = _infer_channel(signal)
                roles = [f"handshake_{handshake}"]
                if channel:
                    roles.append(f"channel_{channel}")
                    channels.add(channel)
                candidates.append(
                    _candidate(
                        candidate_type="tl_handshake",
                        roles=roles,
                        path=path,
                        case_workspace=case_workspace,
                        line_no=line_no,
                        expression=match.group(0),
                        module=current_scope,
                        description=f"TileLink handshake {handshake} signal.",
                    )
                )

            for match in _TL_FIELD_RE.finditer(line):
                signal, field = match.groups()
                channel = _infer_channel(signal)
                roles = [f"field_{field}"]
                if channel:
                    roles.append(f"channel_{channel}")
                    channels.add(channel)
                candidates.append(
                    _candidate(
                        candidate_type="tl_field",
                        roles=roles,
                        path=path,
                        case_workspace=case_workspace,
                        line_no=line_no,
                        expression=match.group(0),
                        module=current_scope,
                        description=f"TileLink bits.{field} field reference.",
                    )
                )

    candidates = _assign_candidate_ids(candidates, prefix="tl_sig")
    for item in candidates:
        module = item["provenance"]["module"]
        module_counts[module] = module_counts.get(module, 0) + 1

    return {
        "schema_version": "tl_signal_index.v2",
        "case_root": "workspace/case",
        "roots": _normalized_roots(case_workspace, roots),
        "files_scanned": files_scanned,
        "candidate_count": len(candidates),
        "channels": sorted(channels),
        "module_counts": dict(sorted(module_counts.items())),
        "candidates": candidates,
    }


def build_observer_index(
    case_workspace: Path, *, roots: Optional[List[str]] = None
) -> Dict[str, Any]:
    """Index high-value CoupledL2 modules that can host or explain TL properties."""
    candidates: List[Dict[str, Any]] = []
    module_counts: Dict[str, int] = {}
    files_scanned = 0

    module_pattern = re.compile(r"\b(" + "|".join(sorted(OBSERVER_MODULES, key=len, reverse=True)) + r")\b")
    for path in _discoverable_scala_sources(case_workspace, roots=roots):
        files_scanned += 1
        text = path.read_text(encoding="utf-8", errors="ignore")
        current_scope = path.stem
        for line_no, line in enumerate(text.splitlines(), start=1):
            scope_match = _SCALA_SCOPE_RE.search(line)
            if scope_match:
                current_scope = scope_match.group(1)
            for match in module_pattern.finditer(line):
                module_name = match.group(1)
                module_counts[module_name] = module_counts.get(module_name, 0) + 1
                candidates.append(
                    _candidate(
                        candidate_type="observer_module",
                        roles=[f"module_{module_name.lower()}"],
                        path=path,
                        case_workspace=case_workspace,
                        line_no=line_no,
                        expression=module_name,
                        module=current_scope,
                        description=f"CoupledL2 observer or protocol component: {module_name}.",
                    )
                )

    candidates = _assign_candidate_ids(candidates, prefix="obs")
    return {
        "schema_version": "observer_index.v2",
        "case_root": "workspace/case",
        "roots": _normalized_roots(case_workspace, roots),
        "files_scanned": files_scanned,
        "candidate_count": len(candidates),
        "module_counts": dict(sorted(module_counts.items())),
        "candidates": candidates,
    }


def _discoverable_files(root: Path, *, policy_root: Optional[Path] = None) -> List[Path]:
    """Walk without following links and prune paths denied for default discovery."""
    policy_root = policy_root or root
    files: List[Path] = []
    for directory, dir_names, file_names in os.walk(root, followlinks=False):
        directory_path = Path(directory)
        allowed_dirs = []
        for name in sorted(dir_names):
            path = directory_path / name
            decision = evaluate_workspace_path(
                path.relative_to(policy_root),
                intent=PathIntent.DISCOVER,
            )
            if decision.allowed and not path.is_symlink():
                allowed_dirs.append(name)
        dir_names[:] = allowed_dirs
        for name in sorted(file_names):
            path = directory_path / name
            decision = evaluate_workspace_path(
                path.relative_to(policy_root),
                intent=PathIntent.DISCOVER,
            )
            if decision.allowed:
                files.append(path)
    return sorted(files)


def _discoverable_scala_sources(
    case_workspace: Path, *, roots: Optional[List[str]] = None
) -> List[Path]:
    paths = []
    for root in _normalized_roots(case_workspace, roots):
        root_path = case_workspace / root
        if not root_path.is_dir():
            continue
        paths.extend(
            path
            for path in _discoverable_files(root_path, policy_root=case_workspace)
            if path.suffix == ".scala"
        )
    return sorted(set(paths))


def _normalized_roots(case_workspace: Path, roots: Optional[List[str]]) -> List[str]:
    default = ["Chisel/src/main/scala/coupledL2", "Chisel/src/test/scala"]
    selected = roots if roots is not None else default
    if not selected or not all(isinstance(root, str) and root for root in selected):
        raise ValueError("index roots must be a non-empty string list")
    normalized = []
    for root in selected:
        path = Path(root)
        if path.is_absolute() or ".." in path.parts:
            raise ValueError("index root must be workspace-relative")
        normalized.append(path.as_posix())
    return sorted(set(normalized))


def _candidate(
    *,
    candidate_type: str,
    roles: List[str],
    path: Path,
    case_workspace: Path,
    line_no: int,
    expression: str,
    module: str,
    description: str,
) -> Dict[str, Any]:
    channel = next((role[len("channel_"):] for role in roles if role.startswith("channel_")), None)
    phase = next((role[len("handshake_"):] for role in roles if role.startswith("handshake_")), None)
    return {
        "type": candidate_type,
        "roles": sorted(set(roles)),
        "description": description,
        "chisel_type": "unknown",
        "width_source": "not_inferred",
        "clock_domain": "implicit_module_clock",
        "channel": channel,
        "handshake_phase": phase or "observation",
        "observable": True,
        "provenance": {
            "path": _rel(path, case_workspace),
            "line": line_no,
            "module": module,
            "expression": expression,
        },
    }


def _assign_candidate_ids(candidates: List[Dict[str, Any]], *, prefix: str) -> List[Dict[str, Any]]:
    keyed = sorted(
        candidates,
        key=lambda item: (
            item["provenance"]["path"],
            item["type"],
            item["provenance"]["expression"],
            ",".join(item["roles"]),
        ),
    )
    result = []
    seen = set()
    for item in keyed:
        dedupe_key = (
            item["type"],
            tuple(item["roles"]),
            item["provenance"]["path"],
            item["provenance"]["expression"],
        )
        if dedupe_key in seen:
            continue
        seen.add(dedupe_key)
        numbered = dict(item)
        identity = {
            "path": item["provenance"]["path"],
            "enclosing_symbol": item["provenance"]["module"],
            "expression": item["provenance"]["expression"],
            "roles": item["roles"],
            "type": item["type"],
        }
        digest = hashlib.sha256(
            json.dumps(identity, sort_keys=True, separators=(",", ":")).encode("utf-8")
        ).hexdigest()
        numbered["source_identity"] = identity
        numbered["candidate_id"] = f"{prefix}_{digest[:16]}"
        result.append(numbered)
    return result


def _infer_channel(signal: str) -> Optional[str]:
    match = re.search(r"(?:^|\.)([a-e])(?:$|\.)", signal)
    return match.group(1) if match else None


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
        "forbidden_apis": forbidden_apis,
    }


def _select_make_target(config: CoupledL2RunConfig) -> str:
    profile = load_property_profile(config.property_profile).profile
    return str(profile["build"]["recommended_make_target"])


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
