"""Merge source candidates with elaboration facts into the binding authority."""

from __future__ import annotations

import hashlib
import json
import re
from pathlib import Path
from typing import Any, Dict, Mapping, Optional, Sequence, Tuple

from .config import SEMANTIC_INDEX_SCHEMA_VERSION, GeneratorConfiguration, ProjectContract


class SemanticIndexError(ValueError):
    """Raised when semantic facts are missing, contradictory, or ambiguous."""


def merge_semantic_index(
    source_index: Mapping[str, Any],
    baseline: Mapping[str, Any],
    project: ProjectContract,
    configuration: GeneratorConfiguration,
    output_path: Optional[Path] = None,
    hierarchical_observers: Sequence[Mapping[str, Any]] = (),
) -> Dict[str, Any]:
    if source_index.get("schema_version") != "scala_source_index.v1":
        raise SemanticIndexError("unsupported source index")
    if baseline.get("schema_version") != "baseline_elaboration.v1":
        raise SemanticIndexError("unsupported baseline elaboration index")
    if baseline.get("configuration_id") != configuration.configuration_id:
        raise SemanticIndexError("baseline configuration identity mismatch")
    top = project.generator["top_name"]
    elaborated = {
        (row.get("owner_module"), row.get("name")): row
        for row in baseline.get("objects", [])
        if isinstance(row, dict)
    }
    allowed_hierarchical = _validate_hierarchical_observers(
        hierarchical_observers
    )
    elaborated_by_locator: Dict[Tuple[str, int, str], list[Mapping[str, Any]]] = {}
    for row in baseline.get("objects", []):
        if not isinstance(row, Mapping):
            continue
        locator = row.get("source_locator")
        if not isinstance(locator, Mapping):
            continue
        path = locator.get("path")
        line = locator.get("line")
        name = row.get("name")
        if isinstance(path, str) and isinstance(line, int) and isinstance(name, str):
            elaborated_by_locator.setdefault((path, line, name), []).append(row)
    rows = []
    for candidate in source_index.get("objects", []):
        name = candidate.get("name")
        source_type = candidate.get("chisel_type", {})
        fact = elaborated.get((top, name))
        nested = False
        candidate_anchor = candidate.get("source_anchor", {})
        observer_key = (
            candidate.get("owner_module"),
            name,
            candidate_anchor.get("path"),
            candidate_anchor.get("line_start"),
        )
        if fact is None and observer_key in allowed_hierarchical:
            matches = []
            for (path, line, fact_name), located in elaborated_by_locator.items():
                if (
                    fact_name == name
                    and line == candidate_anchor.get("line_start")
                    and str(candidate_anchor.get("path", "")).endswith(path)
                ):
                    matches.extend(located)
            if len(matches) == 1:
                fact = matches[0]
                nested = True
        errors = []
        if not candidate.get("owner_module"):
            errors.append("unknown_owner")
        if source_type.get("width") is None:
            errors.append("unknown_width")
        if fact is None:
            errors.append("not_present_in_baseline_elaboration")
        else:
            if source_type.get("width") != fact.get("width"):
                errors.append("width_mismatch")
            expected_direction = candidate.get("direction")
            if expected_direction != "internal" and expected_direction != fact.get("direction"):
                errors.append("direction_mismatch")
            if not fact.get("source_locator_available"):
                errors.append("source_locator_missing")
            else:
                locator = fact.get("source_locator") or {}
                anchor = candidate.get("source_anchor", {})
                if not str(anchor.get("path", "")).endswith(
                    str(locator.get("path", ""))
                ) or anchor.get("line_start") != locator.get("line"):
                    errors.append("source_anchor_mismatch")
        confirmed = not errors
        row = dict(candidate)
        anchor = dict(row.get("source_anchor", {}))
        source_relative = anchor.get("path")
        if isinstance(source_relative, str):
            source_path = (project.project_root / source_relative).resolve()
            try:
                source_path.relative_to(project.project_root)
            except ValueError as exc:
                raise SemanticIndexError("source anchor escapes the project root") from exc
            if not source_path.is_file():
                raise SemanticIndexError("source anchor does not name a project file")
            anchor["source_sha256"] = hashlib.sha256(source_path.read_bytes()).hexdigest()
            row["source_anchor"] = anchor
        row.update(
            {
                "owner_module": (
                    candidate.get("owner_module")
                    if nested
                    else top
                    if fact is not None
                    else candidate.get("owner_module")
                ),
                "accessibility": (
                    "wrapper" if nested else candidate.get("accessibility")
                ),
                "clock_reset": {
                    "clock_domain": project.formal["clock"],
                    "reset_domain": project.formal["reset"],
                    "reset_kind": (
                        "synchronous_active_high"
                        if project.formal["reset_active_high"]
                        else "synchronous_active_low"
                    ),
                },
                "configuration_condition": configuration.configuration_id,
                "fact_status": (
                    "elaboration_confirmed" if confirmed else "ambiguous"
                ),
                "validation_errors": errors,
                "evidence_refs": list(candidate.get("evidence_refs", []))
                + (
                    ["baseline_elaboration.json#objects/" + name]
                    if fact is not None
                    else []
                ),
            }
        )
        rows.append(row)
    value = {
        "schema_version": SEMANTIC_INDEX_SCHEMA_VERSION,
        "project_id": project.project_id,
        "configuration_id": configuration.configuration_id,
        "top": top,
        "objects": sorted(rows, key=lambda row: row["object_id"]),
        "guards": list(source_index.get("guards", [])),
        "source_index_sha256": _canonical_sha256(source_index),
        "baseline_elaboration_sha256": _canonical_sha256(baseline),
    }
    if output_path is not None:
        output_path = Path(output_path)
        output_path.parent.mkdir(parents=True, exist_ok=True)
        output_path.write_text(
            json.dumps(value, indent=2, sort_keys=True) + "\n", encoding="utf-8"
        )
    return value


def _validate_hierarchical_observers(
    rows: Sequence[Mapping[str, Any]],
) -> set[Tuple[Any, Any, Any, Any]]:
    required = {
        "owner_module",
        "name",
        "source_path",
        "source_line",
        "access_path",
        "trace_path",
    }
    result = set()
    for row in rows:
        if not isinstance(row, Mapping) or set(row) != required:
            raise SemanticIndexError("hierarchical observer has invalid fields")
        if not all(
            isinstance(row[field], str) and row[field]
            for field in (
                "owner_module",
                "name",
                "source_path",
                "access_path",
                "trace_path",
            )
        ):
            raise SemanticIndexError("hierarchical observer has invalid text")
        if not isinstance(row["source_line"], int) or row["source_line"] < 1:
            raise SemanticIndexError("hierarchical observer has invalid source line")
        for field in ("access_path", "trace_path"):
            if any(
                re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", part) is None
                for part in row[field].split(".")
            ):
                raise SemanticIndexError(
                    f"hierarchical observer has unsafe {field}"
                )
        key = (
            row["owner_module"],
            row["name"],
            row["source_path"],
            row["source_line"],
        )
        if key in result:
            raise SemanticIndexError("duplicate hierarchical observer")
        result.add(key)
    return result


def require_confirmed_object(index: Mapping[str, Any], name: str) -> Mapping[str, Any]:
    matches = [row for row in index.get("objects", []) if row.get("name") == name]
    if len(matches) != 1:
        raise SemanticIndexError(f"semantic object is missing or non-unique: {name}")
    row = matches[0]
    if row.get("fact_status") != "elaboration_confirmed":
        reasons = row.get("validation_errors", [])
        raise SemanticIndexError(f"semantic object is not confirmed: {name}: {reasons}")
    chisel_type = row.get("chisel_type", {})
    if chisel_type.get("width") is None or not row.get("owner_module"):
        raise SemanticIndexError(f"semantic object lacks width or owner: {name}")
    return row


def _canonical_sha256(value: Mapping[str, Any]) -> str:
    encoded = json.dumps(
        value, sort_keys=True, separators=(",", ":"), ensure_ascii=False
    ).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()
