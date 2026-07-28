"""Exact generated-RTL to Chisel-source projection for causal edges."""

from __future__ import annotations

import re
from pathlib import Path
from typing import Any, Dict, Mapping, Sequence

from src.core.artifact_contract import file_sha256

from .causal_contract import stable_source_candidate_id


_LOCATOR_RES = (
    re.compile(r"@\[([^\]]+\.scala)\s+([0-9]+):([0-9]+)\]"),
    re.compile(r"([A-Za-z0-9_./-]+\.scala):([0-9]+):([0-9]+)"),
)


class CausalSourceProjectionError(ValueError):
    """Raised when certified RTL or Chisel source identity drifts."""


def project_causal_sources(
    graphs: Sequence[Mapping[str, Any]],
    *,
    rtl_files: Sequence[Mapping[str, Any]],
    semantic_index: Mapping[str, Any],
    project_root: Path,
    round_id: int,
) -> Dict[str, Any]:
    project_root = Path(project_root).resolve()
    rtl_by_id = _validated_rtl_files(rtl_files)
    semantic_rows = _semantic_anchors(semantic_index, project_root)
    graph_rows = []
    source_candidates: Dict[str, Dict[str, Any]] = {}
    top_errors = []
    for graph in sorted(graphs, key=lambda row: str(row["graph_id"])):
        edge_rows = []
        graph_errors = []
        for edge in graph.get("edges", []):
            projected = _project_edge(
                edge,
                graph_id=str(graph["graph_id"]),
                rtl_by_id=rtl_by_id,
                semantic_rows=semantic_rows,
                project_root=project_root,
            )
            edge_rows.append(projected)
            if projected["projection_status"] == "exact":
                candidate_id = projected["source_candidate_id"]
                candidate = source_candidates.setdefault(
                    candidate_id,
                    {
                        "candidate_id": candidate_id,
                        "projection_status": "exact",
                        "chisel_source_anchor": projected["chisel_source_anchor"],
                        "semantic_object_ids": [],
                        "graph_ids": [],
                        "causal_edge_ids": [],
                        "evidence_refs": [],
                    },
                )
                candidate["semantic_object_ids"] = sorted(
                    set(candidate["semantic_object_ids"])
                    | set(projected["semantic_object_ids"])
                )
                candidate["graph_ids"] = sorted(
                    set(candidate["graph_ids"]) | {str(graph["graph_id"])}
                )
                candidate["causal_edge_ids"] = sorted(
                    set(candidate["causal_edge_ids"]) | {str(edge["edge_id"])}
                )
                candidate["evidence_refs"] = sorted(
                    set(candidate["evidence_refs"])
                    | {f"causal_edge:{edge['edge_id']}"}
                )
            elif projected["projection_status"] in {"ambiguous", "missing"}:
                graph_errors.extend(projected["errors"])
        graph_status = (
            "complete"
            if graph.get("status") == "complete" and not graph_errors
            else "incomplete"
        )
        graph_rows.append(
            {
                "graph_id": graph["graph_id"],
                "status": graph_status,
                "edge_rows": sorted(
                    edge_rows, key=lambda row: str(row["edge_id"])
                ),
                "errors": graph_errors,
            }
        )
        top_errors.extend(graph_errors)
    status = (
        "complete"
        if graph_rows
        and not top_errors
        and all(row["status"] == "complete" for row in graph_rows)
        else "incomplete"
        if graph_rows
        else "not_required"
    )
    return {
        "schema_version": "causal_source_projection.v1",
        "round_id": round_id,
        "status": status,
        "graphs": graph_rows,
        "source_candidates": sorted(
            source_candidates.values(), key=lambda row: row["candidate_id"]
        ),
        "errors": top_errors,
    }


def not_required_source_projection(
    round_id: int, status: str, reason: str
) -> Dict[str, Any]:
    if status not in {"not_required", "unsupported", "incomplete"}:
        raise CausalSourceProjectionError("invalid empty source projection status")
    return {
        "schema_version": "causal_source_projection.v1",
        "round_id": round_id,
        "status": status,
        "graphs": [],
        "source_candidates": [],
        "errors": [{"code": reason, "detail": reason}] if reason else [],
    }


def _validated_rtl_files(
    rows: Sequence[Mapping[str, Any]],
) -> Dict[str, Mapping[str, Any]]:
    result = {}
    for row in rows:
        required = {"artifact_id", "path", "sha256", "bytes"}
        if not isinstance(row, Mapping) or set(row) != required:
            raise CausalSourceProjectionError(
                "certificate RTL closure row has invalid exact fields"
            )
        path = Path(str(row["path"])).resolve()
        if (
            not path.is_file()
            or path.stat().st_size != row["bytes"]
            or file_sha256(path) != row["sha256"]
        ):
            raise CausalSourceProjectionError("certificate RTL closure hash drifted")
        artifact_id = str(row["artifact_id"])
        if artifact_id in result:
            raise CausalSourceProjectionError("duplicate RTL artifact ID")
        result[artifact_id] = {**dict(row), "resolved_path": path}
    return result


def _semantic_anchors(
    semantic_index: Mapping[str, Any], project_root: Path
) -> list[Dict[str, Any]]:
    rows = []
    for obj in semantic_index.get("objects", []):
        anchor = obj.get("source_anchor", {})
        relative = anchor.get("path")
        if not isinstance(relative, str):
            continue
        path = (project_root / relative).resolve()
        try:
            path.relative_to(project_root)
        except ValueError:
            continue
        if (
            path.is_file()
            and file_sha256(path) == anchor.get("source_sha256")
            and isinstance(anchor.get("line_start"), int)
            and isinstance(anchor.get("line_end"), int)
        ):
            rows.append(
                {
                    "object_id": obj.get("object_id"),
                    "path": relative,
                    "line_start": anchor["line_start"],
                    "line_end": anchor["line_end"],
                    "source_sha256": anchor["source_sha256"],
                    "enclosing_symbol": anchor.get("enclosing_symbol"),
                }
            )
    return rows


def _project_edge(
    edge: Mapping[str, Any],
    *,
    graph_id: str,
    rtl_by_id: Mapping[str, Mapping[str, Any]],
    semantic_rows: Sequence[Mapping[str, Any]],
    project_root: Path,
) -> Dict[str, Any]:
    evidence = edge.get("rtl_evidence")
    if not isinstance(evidence, Mapping):
        return {
            "edge_id": edge["edge_id"],
            "rtl_anchor": {
                "artifact_id": None,
                "line_start": None,
                "line_end": None,
                "snippet_sha256": None,
            },
            "projection_status": "rtl_only",
            "source_candidate_id": None,
            "chisel_source_anchor": None,
            "semantic_object_ids": [],
            "evidence_refs": [f"causal_edge:{edge['edge_id']}"],
            "errors": [],
        }
    artifact_id = evidence.get("artifact_id")
    rtl = rtl_by_id.get(str(artifact_id))
    base = {
        "edge_id": edge["edge_id"],
        "rtl_anchor": {
            "artifact_id": artifact_id,
            "line_start": evidence.get("line_start"),
            "line_end": evidence.get("line_end"),
            "snippet_sha256": evidence.get("snippet_sha256"),
        },
        "source_candidate_id": None,
        "chisel_source_anchor": None,
        "semantic_object_ids": [],
        "evidence_refs": [f"causal_edge:{edge['edge_id']}"],
        "errors": [],
    }
    if rtl is None:
        return {
            **base,
            "projection_status": "missing",
            "errors": [
                {
                    "code": "rtl_artifact_identity_missing",
                    "detail": str(artifact_id),
                }
            ],
        }
    start, end = evidence.get("line_start"), evidence.get("line_end")
    lines = Path(rtl["resolved_path"]).read_text(
        encoding="utf-8", errors="replace"
    ).splitlines()
    if (
        isinstance(start, bool)
        or not isinstance(start, int)
        or isinstance(end, bool)
        or not isinstance(end, int)
        or start < 1
        or end < start
        or end > len(lines)
    ):
        return {**base, "projection_status": "rtl_only"}
    # FIRRTL/CIRCT locator comments normally precede the generated statement.
    locators = _locators(
        "\n".join(lines[max(0, start - 4) : end]), project_root
    )
    exact_matches = []
    for relative, line in locators:
        matches = [
            row
            for row in semantic_rows
            if row["path"] == relative
            and row["line_start"] <= line <= row["line_end"]
        ]
        exact_matches.extend(matches)
    if not exact_matches and len(locators) == 1:
        relative, line = next(iter(locators))
        file_hashes = {
            str(row["source_sha256"])
            for row in semantic_rows
            if row["path"] == relative
        }
        source_path = (project_root / relative).resolve()
        line_count = (
            len(source_path.read_text(encoding="utf-8").splitlines())
            if source_path.is_file()
            else 0
        )
        if (
            len(file_hashes) == 1
            and 1 <= line <= line_count
            and file_sha256(source_path) in file_hashes
        ):
            exact_matches.append(
                {
                    "object_id": None,
                    "path": relative,
                    "line_start": line,
                    "line_end": line,
                    "source_sha256": next(iter(file_hashes)),
                    "enclosing_symbol": None,
                }
            )
    anchor_keys = {
        (
            row["path"],
            row["line_start"],
            row["line_end"],
            row["source_sha256"],
            row.get("enclosing_symbol"),
        )
        for row in exact_matches
    }
    if len(anchor_keys) > 1:
        return {
            **base,
            "projection_status": "ambiguous",
            "errors": [
                {
                    "code": "chisel_source_projection_ambiguous",
                    "detail": str(edge["edge_id"]),
                }
            ],
        }
    if not anchor_keys:
        return {**base, "projection_status": "rtl_only"}
    path, line_start, line_end, source_sha256, symbol = next(iter(anchor_keys))
    anchor = {
        "path": path,
        "line_start": line_start,
        "line_end": line_end,
        "source_sha256": source_sha256,
        "enclosing_symbol": symbol,
    }
    candidate_id = stable_source_candidate_id(graph_id, anchor)
    return {
        **base,
        "projection_status": "exact",
        "source_candidate_id": candidate_id,
        "chisel_source_anchor": anchor,
        "semantic_object_ids": sorted(
            {
                str(row["object_id"])
                for row in exact_matches
                if row.get("object_id") is not None
            }
        ),
    }


def _locators(text: str, project_root: Path) -> set[tuple[str, int]]:
    rows = set()
    for pattern in _LOCATOR_RES:
        for match in pattern.finditer(text):
            raw_path, raw_line, _column = match.groups()
            path = Path(raw_path)
            if path.is_absolute():
                resolved = path.resolve()
            else:
                resolved = (project_root / path).resolve()
            try:
                relative = resolved.relative_to(project_root).as_posix()
            except ValueError:
                continue
            rows.add((relative, int(raw_line)))
    return rows
