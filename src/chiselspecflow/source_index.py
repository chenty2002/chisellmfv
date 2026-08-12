"""Adapter and strict validator for the ScalaMeta source indexer."""

from __future__ import annotations

import hashlib
import json
import re
import subprocess
from pathlib import Path
from typing import Any, Dict, Iterable, Mapping


class SourceIndexError(ValueError):
    """Raised when source-index construction or validation fails closed."""


_SCALA_LOCATOR_RE = re.compile(
    r"(?P<path>[A-Za-z0-9_./-]+\.scala):(?P<line>[0-9]+):(?P<column>[0-9]+|\{[0-9,]+\})"
)
_SHORT_LOCATOR_RE = re.compile(
    r"(?:^|,)\s*:(?P<line>[0-9]+):(?P<column>[0-9]+|\{[0-9,]+\})"
)


def build_source_index(
    model_sources_root: Path,
    model_view_manifest: Mapping[str, Any],
    output_path: Path,
    tool_root: Path,
    elaboration: Mapping[str, Any] | None = None,
) -> Dict[str, Any]:
    model_sources_root = Path(model_sources_root).resolve()
    output_path = Path(output_path).resolve()
    tool_root = Path(tool_root).resolve()
    if model_view_manifest.get("schema_version") != "model_view_manifest":
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
    if elaboration is not None:
        _attach_exact_origins(value, elaboration, model_sources_root)
    validate_source_index(value, source_ids, model_sources_root)
    for collection in ("sources", "objects", "guards", "statements"):
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
    if value.get("schema_version") != "scala_source_index":
        raise SourceIndexError("unsupported Scala source index schema")
    for name in ("sources", "objects", "guards", "statements"):
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
    statement_ids = set()
    for row in value["statements"]:
        _validate_fact_row(row, source_ids, "statement", "statement_id")
        statement_id = row["statement_id"]
        if statement_id in statement_ids:
            raise SourceIndexError(f"duplicate statement ID: {statement_id}")
        if row.get("statement_kind") not in {
            "assignment", "register_update", "declaration", "when", "elsewhen", "switch", "case",
            "table_update", "blackbox_parameter", "elaboration_guard",
        }:
            raise SourceIndexError("source statement has unknown kind")
        if row.get("execution_phase") not in {"runtime", "elaboration"}:
            raise SourceIndexError("source entity has unknown execution phase")
        if not isinstance(row.get("exact_origins"), list):
            raise SourceIndexError("source entity exact_origins must be a list")
        if not isinstance(row.get("semantic_object_ids"), list):
            raise SourceIndexError("source statement semantic_object_ids must be a list")
        if row.get("parent_statement_id") is not None and not isinstance(
            row["parent_statement_id"], str
        ):
            raise SourceIndexError("source statement parent_statement_id must be a string or null")
        for field in ("ancestor_statement_ids", "child_statement_ids"):
            if not isinstance(row.get(field), list) or not all(
                isinstance(value, str) for value in row[field]
            ):
                raise SourceIndexError(f"source statement {field} must be a string list")
        statement_ids.add(statement_id)
    for row in value["statements"]:
        related = set(row["ancestor_statement_ids"]) | set(row["child_statement_ids"])
        if row.get("parent_statement_id") is not None:
            related.add(row["parent_statement_id"])
        if not related <= statement_ids or row["statement_id"] in related:
            raise SourceIndexError("source statement hierarchy references an unknown statement")


def _attach_exact_origins(
    value: Dict[str, Any], elaboration: Mapping[str, Any], root: Path
) -> None:
    generated = []
    for row in elaboration.get("generated_files", []):
        if not isinstance(row, Mapping):
            continue
        path = Path(root) / str(row.get("path", ""))
        if path.is_file():
            generated.append((str(row["path"]), path.read_text(encoding="utf-8")))
    for entity in value.get("statements", []):
        if entity.get("execution_phase") != "elaboration":
            continue
        spec = entity.get("exact_origin_spec") or {}
        origins = []
        if entity.get("entity_kind") == "blackbox_parameter":
            parameter = re.escape(str(spec.get("parameter", "")))
            pattern = re.compile(rf"\.{parameter}\s*\(\s*([^\s)]+)\s*\)")
            for path, text in generated:
                for match in pattern.finditer(text):
                    origins.append(_origin(path, text, match, "blackbox_parameter"))
        elif entity.get("entity_kind") == "table_update":
            parameter = spec.get("selection_parameter")
            selection = spec.get("selection_value")
            selected = _selected_parameter(elaboration, parameter) if isinstance(parameter, str) else None
            row_width = spec.get("row_width")
            updated_rows = {
                int(update["row_expression"])
                for update in spec.get("updates", [])
                if isinstance(update, Mapping)
                and re.fullmatch(r"[0-9]+", str(update.get("row_expression", "")))
            }
            if (
                isinstance(selection, int)
                and selected == selection
                and isinstance(row_width, int)
                and row_width > 0
                and updated_rows
            ):
                for path, text in generated:
                    for block in re.finditer(r"\bcasez\b[\s\S]*?\bendcase\b", text):
                        assignments = list(re.finditer(
                            r"=\s*(\d+)'([hdb])([0-9A-Fa-f_]+)\s*;", block.group()
                        ))
                        if len(assignments) < (max(updated_rows) + 1) * row_width:
                            continue
                        for index, item in enumerate(assignments):
                            if index // row_width not in updated_rows:
                                continue
                            origins.append(_origin_at(
                                path, text, block.start() + item.start(), "table_entry"
                            ))
        entity["exact_origins"] = origins


def _selected_parameter(elaboration: Mapping[str, Any], parameter: str) -> int | None:
    command = " ".join(
        str(value)
        for values in (elaboration.get("commands") or {}).values()
        if isinstance(values, list)
        for value in values
    )
    match = re.search(rf"(?:^|\s){re.escape(parameter)}=([0-9]+)(?:\s|$)", command)
    return int(match.group(1)) if match else None


def _origin(path: str, text: str, match: re.Match[str], kind: str) -> Dict[str, Any]:
    return _origin_at(path, text, match.start(), kind)


def _origin_at(path: str, text: str, offset: int, kind: str) -> Dict[str, Any]:
    line_start = text.rfind("\n", 0, offset) + 1
    line_end = text.find("\n", offset)
    source_locators = _scala_locators(text[line_start : None if line_end < 0 else line_end])
    return {
        "authority": "authoritative",
        "kind": kind,
        "path": path,
        "line": text.count("\n", 0, offset) + 1,
        "column": offset - line_start + 1,
        "source_locators": source_locators,
    }


def _scala_locators(text: str) -> list[Dict[str, Any]]:
    matches = list(_SCALA_LOCATOR_RE.finditer(text))
    rows = [
        {
            "path": match.group("path"),
            "line": int(match.group("line")),
            "column": _column(match.group("column")),
        }
        for match in matches
    ]
    if matches:
        last = matches[-1]
        rows.extend(
            {
                "path": last.group("path"),
                "line": int(match.group("line")),
                "column": _column(match.group("column")),
            }
            for match in _SHORT_LOCATOR_RE.finditer(text, last.end())
        )
    return rows


def _column(value: Any) -> int:
    match = re.search(r"[0-9]+", str(value))
    return int(match.group()) if match else 1


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
