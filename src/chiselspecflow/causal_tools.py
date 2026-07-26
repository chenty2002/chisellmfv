"""Strict read-only tool schemas over already materialized causal evidence."""

from __future__ import annotations

from pathlib import Path
from typing import Any, Dict, Mapping, MutableMapping, Sequence

from src.core.artifact_contract import file_sha256


READ_TOOL_NAMES = frozenset(
    {
        "expand_causal_predecessors",
        "get_causal_paths",
        "get_causal_evidence",
        "get_source_context",
    }
)
TERMINATION_TOOL_NAMES = frozenset(
    {"submit_diagnosis_candidate", "report_diagnosis_inconclusive"}
)


class CausalToolError(ValueError):
    """Raised for unknown IDs, over-budget requests, or schema injection."""


def new_visibility(
    graphs: Mapping[str, Mapping[str, Any]],
    overviews: Mapping[str, Mapping[str, Any]],
) -> Dict[str, set[str]]:
    graph_ids = set(graphs)
    node_ids = {
        str(row["node_id"])
        for overview in overviews.values()
        for row in overview.get("top_nodes", [])
    }
    return {
        "graph_ids": graph_ids,
        "node_ids": node_ids,
        "edge_ids": set(),
        "source_candidate_ids": set(),
    }


def build_causal_tool_schemas(
    visibility: Mapping[str, set[str]],
    *,
    operation_ids: Sequence[str],
    failure_cycles: Sequence[int],
    object_ids: Sequence[str],
    monitor_state_ids: Sequence[str],
    clause_locators: Sequence[str],
    evidence_refs: Sequence[str],
) -> list[Dict[str, Any]]:
    graph_ids = sorted(visibility["graph_ids"])
    node_ids = sorted(visibility["node_ids"])
    edge_ids = sorted(visibility["edge_ids"])
    source_ids = sorted(visibility["source_candidate_ids"])
    read_tools = [
        {
            "name": "expand_causal_predecessors",
            "description": "Read a bounded predecessor neighborhood by stable IDs.",
            "strict": True,
            "parameters": _strict_object(
                {
                    "graph_id": _enum(graph_ids),
                    "node_ids": {
                        "type": "array",
                        "minItems": 1,
                        "maxItems": 20,
                        "items": _enum(node_ids),
                    },
                    "max_hops": _enum([1, 2]),
                }
            ),
        },
        {
            "name": "get_causal_paths",
            "description": "Read at most three bounded predecessor paths.",
            "strict": True,
            "parameters": _strict_object(
                {
                    "graph_id": _enum(graph_ids),
                    "target_node_id": _enum(node_ids),
                    "max_paths": {"type": "integer", "minimum": 1, "maximum": 3},
                    "minimum_evidence_strength": _enum(
                        [
                            "expression_counterfactual",
                            "branch_observed",
                            "toggle_supported",
                            "structural_only",
                            "unresolved",
                        ]
                    ),
                }
            ),
        },
        {
            "name": "get_causal_evidence",
            "description": "Read bounded RTL evidence for disclosed causal edges.",
            "strict": True,
            "parameters": _strict_object(
                {
                    "graph_id": _enum(graph_ids),
                    "edge_ids": {
                        "type": "array",
                        "minItems": 1,
                        "maxItems": 8,
                        "items": _enum(edge_ids),
                    },
                }
            ),
        },
        {
            "name": "get_source_context",
            "description": "Read five lines around exact hash-bound Chisel candidates.",
            "strict": True,
            "parameters": _strict_object(
                {
                    "source_candidate_ids": {
                        "type": "array",
                        "minItems": 1,
                        "maxItems": 5,
                        "items": _enum(source_ids),
                    }
                }
            ),
        },
    ]
    ranking_item = _strict_object(
        {
            "candidate_id": _enum(source_ids),
            "rank_group": {"type": "integer", "minimum": 1},
            "evidence_refs": {
                "type": "array",
                "minItems": 1,
                "items": _enum(list(evidence_refs)),
            },
        }
    )
    termination_tools = [
        {
            "name": "submit_diagnosis_candidate",
            "description": (
                "Submit a bounded evidence classification only; do not approve, "
                "set a verdict, request paths, or propose a patch."
            ),
            "strict": True,
            "parameters": _strict_object(
                {
                    "classification": _enum(
                        [
                            "design_violation",
                            "obligation_error",
                            "binding_error",
                            "monitor_error",
                            "assumption_error",
                            "tool_or_identity_error",
                            "inconclusive",
                        ]
                    ),
                    "operation_id": _enum(list(operation_ids)),
                    "failure_cycle": _enum(list(failure_cycles)),
                    "object_ids": {
                        "type": "array",
                        "minItems": 1,
                        "items": _enum(list(object_ids)),
                    },
                    "monitor_state_ids": {
                        "type": "array",
                        "items": _enum(
                            list(monitor_state_ids) or ["not_required"]
                        ),
                    },
                    "spec_clause_locator": _enum(list(clause_locators)),
                    "evidence_refs": {
                        "type": "array",
                        "minItems": 1,
                        "items": _enum(list(evidence_refs)),
                    },
                    "causal_graph_ids": {
                        "type": "array",
                        "items": _enum(graph_ids),
                    },
                    "causal_node_ids": {
                        "type": "array",
                        "items": _enum(node_ids),
                    },
                    "causal_edge_ids": {
                        "type": "array",
                        "items": _enum(edge_ids),
                    },
                    "ranked_source_candidates": {
                        "type": "array",
                        "items": ranking_item,
                    },
                    "summary": {"type": "string", "minLength": 1},
                }
            ),
        },
        {
            "name": "report_diagnosis_inconclusive",
            "description": "Terminate explicitly when bounded evidence is insufficient.",
            "strict": True,
            "parameters": _strict_object(
                {
                    "reason_code": _enum(
                        [
                            "causal_evidence_incomplete",
                            "causal_identity_ambiguous",
                            "causal_query_budget_exhausted",
                            "exact_source_projection_missing",
                            "formal_evidence_incomplete",
                        ]
                    ),
                    "evidence_refs": {
                        "type": "array",
                        "minItems": 1,
                        "items": _enum(list(evidence_refs)),
                    },
                    "missing_evidence": {
                        "type": "array",
                        "minItems": 1,
                        "items": {"type": "string", "minLength": 1},
                    },
                }
            ),
        },
    ]
    return read_tools + termination_tools


def execute_causal_read_tool(
    name: str,
    arguments: Any,
    *,
    graphs: Mapping[str, Mapping[str, Any]],
    source_projection: Mapping[str, Any],
    visibility: MutableMapping[str, set[str]],
    project_root: Path,
) -> Dict[str, Any]:
    if name not in READ_TOOL_NAMES:
        raise CausalToolError("tool is not a causal read operation")
    if not isinstance(arguments, Mapping):
        raise CausalToolError("tool arguments must be an object")
    if name == "get_source_context":
        _exact_fields(arguments, {"source_candidate_ids"})
        ids = _id_list(
            arguments["source_candidate_ids"],
            visibility["source_candidate_ids"],
            maximum=5,
            label="source candidate IDs",
        )
        return _source_context(
            ids, source_projection=source_projection, project_root=project_root
        )

    _exact_graph(arguments, visibility)
    graph_id = str(arguments["graph_id"])
    graph = graphs[graph_id]
    try:
        from verilog_causal_analysis import (
            expand_predecessors,
            get_edge_evidence,
            get_ranked_paths,
        )
    except Exception as exc:
        raise CausalToolError("causal query backend is unavailable") from exc

    if name == "expand_causal_predecessors":
        _exact_fields(arguments, {"graph_id", "node_ids", "max_hops"})
        node_ids = _id_list(
            arguments["node_ids"],
            visibility["node_ids"],
            maximum=20,
            label="node IDs",
        )
        if arguments["max_hops"] not in {1, 2}:
            raise CausalToolError("max_hops must be 1 or 2")
        result = expand_predecessors(
            graph,
            node_ids,
            max_hops=int(arguments["max_hops"]),
            max_nodes=20,
        )
        visibility["node_ids"].update(
            str(row["node_id"]) for row in result.get("nodes", [])
        )
        visibility["edge_ids"].update(
            str(row["edge_id"]) for row in result.get("edges", [])
        )
        return result

    if name == "get_causal_paths":
        _exact_fields(
            arguments,
            {
                "graph_id",
                "target_node_id",
                "max_paths",
                "minimum_evidence_strength",
            },
        )
        target = str(arguments["target_node_id"])
        if target not in visibility["node_ids"]:
            raise CausalToolError("unknown or undisclosed target node ID")
        maximum = arguments["max_paths"]
        if (
            isinstance(maximum, bool)
            or not isinstance(maximum, int)
            or not 1 <= maximum <= 3
        ):
            raise CausalToolError("max_paths must be in [1, 3]")
        result = get_ranked_paths(
            graph,
            target,
            max_paths=maximum,
            max_path_length=8,
            minimum_evidence_strength=str(
                arguments["minimum_evidence_strength"]
            ),
        )
        for path in result.get("paths", []):
            visibility["node_ids"].update(str(row) for row in path["node_ids"])
            visibility["edge_ids"].update(str(row) for row in path["edge_ids"])
        return result

    _exact_fields(arguments, {"graph_id", "edge_ids"})
    edge_ids = _id_list(
        arguments["edge_ids"],
        visibility["edge_ids"],
        maximum=8,
        label="edge IDs",
    )
    result = get_edge_evidence(graph, edge_ids)
    projections = {
        str(row["edge_id"]): row
        for graph_row in source_projection.get("graphs", [])
        if graph_row.get("graph_id") == graph_id
        for row in graph_row.get("edge_rows", [])
    }
    selected = [
        projections[edge_id]
        for edge_id in edge_ids
        if edge_id in projections
    ]
    result["source_projection"] = selected
    disclosed = {
        str(row["source_candidate_id"])
        for row in selected
        if row.get("projection_status") == "exact"
        and row.get("source_candidate_id")
    }
    visibility["source_candidate_ids"].update(disclosed)
    if _contains_absolute_path(result):
        raise CausalToolError("query result contains an absolute path")
    return result


def _source_context(
    candidate_ids: Sequence[str],
    *,
    source_projection: Mapping[str, Any],
    project_root: Path,
) -> Dict[str, Any]:
    candidates = {
        str(row["candidate_id"]): row
        for row in source_projection.get("source_candidates", [])
    }
    project_root = Path(project_root).resolve()
    rows = []
    for candidate_id in candidate_ids:
        row = candidates.get(candidate_id)
        if row is None or row.get("projection_status") != "exact":
            raise CausalToolError("source candidate is not exact")
        anchor = row["chisel_source_anchor"]
        relative = Path(str(anchor["path"]))
        if relative.is_absolute() or ".." in relative.parts:
            raise CausalToolError("source candidate path is unsafe")
        path = (project_root / relative).resolve()
        try:
            path.relative_to(project_root)
        except ValueError as exc:
            raise CausalToolError("source candidate escapes project root") from exc
        if not path.is_file() or file_sha256(path) != anchor["source_sha256"]:
            raise CausalToolError("source candidate hash drifted")
        lines = path.read_text(encoding="utf-8").splitlines()
        center = int(anchor["line_start"])
        start = max(1, center - 2)
        end = min(len(lines), start + 4)
        start = max(1, end - 4)
        rows.append(
            {
                "candidate_id": candidate_id,
                "source_anchor": dict(anchor),
                "context_start_line": start,
                "context_end_line": end,
                "lines": [
                    {"line": index, "text": lines[index - 1]}
                    for index in range(start, end + 1)
                ],
            }
        )
    result = {"source_candidates": rows}
    if _contains_absolute_path(result):
        raise CausalToolError("source context contains an absolute path")
    return result


def _exact_graph(
    arguments: Mapping[str, Any], visibility: Mapping[str, set[str]]
) -> None:
    graph_id = arguments.get("graph_id")
    if not isinstance(graph_id, str) or graph_id not in visibility["graph_ids"]:
        raise CausalToolError("unknown graph ID")


def _id_list(
    value: Any,
    allowed: set[str],
    *,
    maximum: int,
    label: str,
) -> list[str]:
    if (
        not isinstance(value, list)
        or not value
        or len(value) > maximum
        or any(not isinstance(row, str) for row in value)
        or len(value) != len(set(value))
        or not set(value) <= allowed
    ):
        raise CausalToolError(f"{label} are unknown, duplicated, or over budget")
    return list(value)


def _exact_fields(value: Mapping[str, Any], fields: set[str]) -> None:
    if set(value) != fields:
        raise CausalToolError("tool arguments have invalid exact fields")


def _strict_object(properties: Mapping[str, Any]) -> Dict[str, Any]:
    return {
        "type": "object",
        "properties": dict(properties),
        "required": list(properties),
        "additionalProperties": False,
    }


def _enum(values: Sequence[Any]) -> Dict[str, Any]:
    kind = "string" if all(isinstance(row, str) for row in values) else "integer"
    return {"type": kind, "enum": list(values)}


def _contains_absolute_path(value: Any) -> bool:
    if isinstance(value, str):
        return value.startswith("/")
    if isinstance(value, Mapping):
        return any(_contains_absolute_path(row) for row in value.values())
    if isinstance(value, list):
        return any(_contains_absolute_path(row) for row in value)
    return False
