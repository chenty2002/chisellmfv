"""Adapter and strict validator for the ScalaMeta source indexer."""

from __future__ import annotations

import hashlib
import json
import subprocess
from pathlib import Path
from typing import Any, Dict, Iterable, Mapping


class SourceIndexError(ValueError):
    """Raised when source-index construction or validation fails closed."""


def build_source_index(
    model_sources_root: Path,
    model_view_manifest: Mapping[str, Any],
    output_path: Path,
    tool_root: Path,
) -> Dict[str, Any]:
    model_sources_root = Path(model_sources_root).resolve()
    output_path = Path(output_path).resolve()
    tool_root = Path(tool_root).resolve()
    if model_view_manifest.get("schema_version") != "model_view_manifest.v1":
        raise SourceIndexError("unsupported model view manifest")
    paths = []
    source_ids = {}
    for row in model_view_manifest.get("files", []):
        if not isinstance(row, dict):
            raise SourceIndexError("model view file row must be an object")
        relative = Path(row.get("path", ""))
        path = (model_sources_root / relative).resolve()
        try:
            path.relative_to(model_sources_root)
        except ValueError as exc:
            raise SourceIndexError("model source escapes allowlisted root") from exc
        if not path.is_file() or path.suffix != ".scala":
            raise SourceIndexError(f"invalid model source: {relative}")
        if _file_sha256(path) != row.get("sha256"):
            raise SourceIndexError(f"model source hash mismatch: {relative}")
        paths.append(path)
        source_ids[relative.as_posix()] = row.get("source_id")
    if not paths:
        raise SourceIndexError("model view contains no Scala source")

    command = [
        "sbt",
        "--error",
        "runMain chisellmfv.indexer.Main --root "
        + _sbt_quote(model_sources_root)
        + " --output "
        + _sbt_quote(output_path)
        + " "
        + " ".join(_sbt_quote(path) for path in paths),
    ]
    completed = subprocess.run(
        command,
        cwd=tool_root,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        check=False,
    )
    if completed.returncode != 0:
        raise SourceIndexError(
            "ScalaMeta source indexer failed:\n" + completed.stdout[-4000:]
        )
    value = _read_json(output_path)
    validate_source_index(value, source_ids, model_sources_root)
    for collection in ("sources", "objects", "guards"):
        for row in value[collection]:
            path = row.get("path") or row.get("source_anchor", {}).get("path")
            row["source_id"] = source_ids[path]
    output_path.write_text(
        json.dumps(value, indent=2, sort_keys=True) + "\n", encoding="utf-8"
    )
    return value


def validate_source_index(
    value: Mapping[str, Any], source_ids: Mapping[str, str], root: Path
) -> None:
    if value.get("schema_version") != "scala_source_index.v1":
        raise SourceIndexError("unsupported Scala source index schema")
    for name in ("sources", "objects", "guards"):
        if not isinstance(value.get(name), list):
            raise SourceIndexError(f"source index {name} must be a list")
    indexed_paths = set()
    for row in value["sources"]:
        if not isinstance(row, dict) or set(row) != {"path", "sha256"}:
            raise SourceIndexError("invalid source index file row")
        path = row["path"]
        if path not in source_ids or path in indexed_paths:
            raise SourceIndexError("source index contains an unknown or duplicate path")
        indexed_paths.add(path)
        if _file_sha256(Path(root) / path) != row["sha256"]:
            raise SourceIndexError("source index file hash mismatch")
    if indexed_paths != set(source_ids):
        raise SourceIndexError("source index did not account for every visible source")
    object_ids = set()
    for row in value["objects"]:
        _validate_fact_row(row, source_ids, "object", "object_id")
        object_id = row["object_id"]
        if object_id in object_ids:
            raise SourceIndexError(f"duplicate object ID: {object_id}")
        object_ids.add(object_id)
    guard_ids = set()
    for row in value["guards"]:
        _validate_fact_row(row, source_ids, "guard", "guard_id")
        if row.get("domain") not in {"elaboration", "hardware"}:
            raise SourceIndexError("source guard has unknown domain")
        guard_id = row["guard_id"]
        if guard_id in guard_ids:
            raise SourceIndexError(f"duplicate guard ID: {guard_id}")
        guard_ids.add(guard_id)


def _validate_fact_row(
    row: Any, source_ids: Mapping[str, str], label: str, identity: str
) -> None:
    if not isinstance(row, dict) or not isinstance(row.get(identity), str):
        raise SourceIndexError(f"invalid source {label} row")
    anchor = row.get("source_anchor")
    if not isinstance(anchor, dict) or anchor.get("path") not in source_ids:
        raise SourceIndexError(f"source {label} has an invalid anchor")
    if not isinstance(row.get("owner_module"), str) or not row["owner_module"]:
        raise SourceIndexError(f"source {label} has no owner")


def _read_json(path: Path) -> Dict[str, Any]:
    try:
        value = json.loads(Path(path).read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as exc:
        raise SourceIndexError(f"cannot read source index: {path}") from exc
    if not isinstance(value, dict):
        raise SourceIndexError("source index must be an object")
    return value


def _file_sha256(path: Path) -> str:
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def _sbt_quote(value: Path) -> str:
    return '"' + str(value).replace("\\", "\\\\").replace('"', '\\"') + '"'
