"""Strict read-only tool schemas over already materialized causal evidence."""

from __future__ import annotations

from pathlib import Path
from typing import Any, Dict, Mapping, MutableMapping, Sequence

from src.core.artifact_contract import file_sha256
from src.core.formal_operations import canonical_sha256

from .causal_contract import (
    CAUSAL_GRAPH_V2_SCHEMA,
    CAUSAL_GRAPH_V3_SCHEMA,
    CausalContractError,
    causal_graph_schema,
    causal_graph_stable_ids,
)


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
) -> Dict[str, Any]:
    graph_ids = set(graphs)
    node_ids_by_graph: Dict[str, set[str]] = {}
    edge_ids_by_graph: Dict[str, set[str]] = {}
    for graph_id, graph in graphs.items():
        overview = overviews.get(graph_id, {})
        schema = graph.get("schema_version")
        if schema == CAUSAL_GRAPH_V2_SCHEMA:
            disclosed = {
                str(row["node_id"])
                for row in overview.get("top_nodes", [])
                if isinstance(row, Mapping) and row.get("node_id")
            }
        elif schema == CAUSAL_GRAPH_V3_SCHEMA:
            disclosed = {
                str(row["semantic_id"])
                for row in overview.get("root_candidates", [])
                if isinstance(row, Mapping) and row.get("semantic_id")
            }
            disclosed.update(
                str(row["node_id"])
                for row in graph.get("signal_nodes", [])
                if isinstance(row, Mapping)
                and row.get("node_id")
                and row.get("is_endpoint") is True
            )
        else:
            disclosed = set()
        node_ids_by_graph[graph_id] = disclosed
        edge_ids_by_graph[graph_id] = set()
    node_ids = set().union(*node_ids_by_graph.values()) if graphs else set()
    return {
        "graph_ids": graph_ids,
        "node_ids": node_ids,
        "edge_ids": set(),
        "source_candidate_ids": set(),
        "node_ids_by_graph": node_ids_by_graph,
        "edge_ids_by_graph": edge_ids_by_graph,
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
    if name == "expand_causal_predecessors":
        _exact_fields(arguments, {"graph_id", "node_ids", "max_hops"})
        _id_list(
            arguments["node_ids"],
            _visible_graph_ids(visibility, "node_ids", graph_id),
            maximum=20,
            label="node IDs",
        )
        if arguments["max_hops"] not in {1, 2}:
            raise CausalToolError("max_hops must be 1 or 2")
    elif name == "get_causal_paths":
        _exact_fields(
            arguments,
            {
                "graph_id",
                "target_node_id",
                "max_paths",
                "minimum_evidence_strength",
            },
        )
        if str(arguments["target_node_id"]) not in _visible_graph_ids(
            visibility, "node_ids", graph_id
        ):
            raise CausalToolError("unknown or undisclosed target node ID")
        maximum = arguments["max_paths"]
        if (
            isinstance(maximum, bool)
            or not isinstance(maximum, int)
            or not 1 <= maximum <= 3
        ):
            raise CausalToolError("max_paths must be in [1, 3]")
    else:
        _exact_fields(arguments, {"graph_id", "edge_ids"})
        _id_list(
            arguments["edge_ids"],
            _visible_graph_ids(visibility, "edge_ids", graph_id),
            maximum=8,
            label="edge IDs",
        )
    try:
        schema = causal_graph_schema(graph)
        causal_graph_stable_ids(graph)
    except CausalContractError as exc:
        raise CausalToolError(str(exc)) from exc
    if schema == CAUSAL_GRAPH_V3_SCHEMA:
        return _execute_v3_read_tool(
            name,
            arguments,
            graph=graph,
            graph_id=graph_id,
            source_projection=source_projection,
            visibility=visibility,
        )
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
            _visible_graph_ids(visibility, "node_ids", graph_id),
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
        _visible_graph_ids(visibility, "node_ids", graph_id).update(
            str(row["node_id"]) for row in result.get("nodes", [])
        )
        _visible_graph_ids(visibility, "edge_ids", graph_id).update(
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
        if target not in _visible_graph_ids(
            visibility, "node_ids", graph_id
        ):
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
            _visible_graph_ids(visibility, "node_ids", graph_id).update(
                str(row) for row in path["node_ids"]
            )
            _visible_graph_ids(visibility, "edge_ids", graph_id).update(
                str(row) for row in path["edge_ids"]
            )
        return result

    _exact_fields(arguments, {"graph_id", "edge_ids"})
    edge_ids = _id_list(
        arguments["edge_ids"],
        _visible_graph_ids(visibility, "edge_ids", graph_id),
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


def _execute_v3_read_tool(
    name: str,
    arguments: Mapping[str, Any],
    *,
    graph: Mapping[str, Any],
    graph_id: str,
    source_projection: Mapping[str, Any],
    visibility: MutableMapping[str, Any],
) -> Dict[str, Any]:
    try:
        from verilog_causal_analysis import (
            get_handshake_timeline,
            get_interval_evidence,
            get_pipeline_occupancy,
            get_semantic_paths,
            get_waitfor_component,
        )
    except Exception as exc:
        raise CausalToolError("V3 causal query support is unavailable") from exc

    visible_nodes = _visible_graph_ids(visibility, "node_ids", graph_id)
    visible_edges = _visible_graph_ids(visibility, "edge_ids", graph_id)
    signal_by_id = {
        str(row["node_id"]): row for row in graph.get("signal_nodes", [])
    }
    semantic_by_id = {
        str(row["semantic_id"]): row
        for row in graph.get("semantic_nodes", [])
    }
    edge_by_id = {
        str(row["edge_id"]): row for row in graph.get("edges", [])
    }

    if name == "expand_causal_predecessors":
        _exact_fields(arguments, {"graph_id", "node_ids", "max_hops"})
        node_ids = _id_list(
            arguments["node_ids"],
            visible_nodes,
            maximum=20,
            label="node IDs",
        )
        hops = arguments["max_hops"]
        if hops not in {1, 2}:
            raise CausalToolError("max_hops must be 1 or 2")
        results = []
        disclosed_nodes: set[str] = set()
        disclosed_edges: set[str] = set()
        for node_id in node_ids:
            if node_id in signal_by_id:
                result = _v3_raw_predecessors(
                    graph, [node_id], max_hops=int(hops), max_nodes=20
                )
            elif node_id in semantic_by_id:
                node = semantic_by_id[node_id]
                kind = str(node.get("type"))
                if kind in {"waitfor_component", "waitfor_scc"}:
                    result = get_waitfor_component(graph, node_id)
                elif kind in {
                    "persistent_interval",
                    "stall_interval",
                    "pipeline_occupancy",
                }:
                    result = get_interval_evidence(graph, [node_id])
                elif kind == "handshake":
                    result = get_handshake_timeline(
                        graph, node_id, max_events=20
                    )
                elif kind == "pipeline":
                    start, end = _v3_pipeline_range(graph, node_id)
                    result = get_pipeline_occupancy(
                        graph,
                        node_id,
                        start_cycle=start,
                        end_cycle=end,
                    )
                else:
                    result = get_semantic_paths(
                        graph, node_id, max_paths=3, max_length=8
                    )
            else:
                raise CausalToolError("unknown V3 stable ID class")
            results.append(result)
            nodes, edges = _v3_result_ids(result)
            disclosed_nodes.update(nodes)
            disclosed_edges.update(edges)
            for edge_id, edge in edge_by_id.items():
                endpoints = {
                    str(edge[field])
                    for field in (
                        "src_node_id",
                        "dst_node_id",
                        "src_semantic_id",
                        "dst_semantic_id",
                    )
                    if field in edge
                }
                if endpoints and endpoints <= disclosed_nodes:
                    disclosed_edges.add(edge_id)
        disclosed_nodes &= set(signal_by_id) | set(semantic_by_id)
        disclosed_edges &= set(edge_by_id)
        _disclose(
            visibility,
            graph_id,
            node_ids=disclosed_nodes,
            edge_ids=disclosed_edges,
        )
        row = {
            "schema_version": "specflow_v3_causal_expansion.v1",
            "graph_id": graph_id,
            "results": results,
            "nodes": [
                dict(signal_by_id.get(item) or semantic_by_id[item])
                for item in sorted(disclosed_nodes)
            ],
            "edges": [
                dict(edge_by_id[item]) for item in sorted(disclosed_edges)
            ],
        }
        row["result_sha256"] = canonical_sha256(row)
        return row

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
        if target not in visible_nodes:
            raise CausalToolError("unknown or undisclosed target node ID")
        maximum = arguments["max_paths"]
        if (
            isinstance(maximum, bool)
            or not isinstance(maximum, int)
            or not 1 <= maximum <= 3
        ):
            raise CausalToolError("max_paths must be in [1, 3]")
        if target in signal_by_id:
            result = _v3_raw_paths(
                graph, target, max_paths=maximum, max_length=8
            )
        elif target in semantic_by_id:
            result = get_semantic_paths(
                graph, target, max_paths=maximum, max_length=8
            )
        else:
            raise CausalToolError("unknown V3 stable ID class")
        nodes, edges = _v3_result_ids(result)
        _disclose(
            visibility,
            graph_id,
            node_ids=nodes,
            edge_ids=edges,
        )
        return result

    _exact_fields(arguments, {"graph_id", "edge_ids"})
    edge_ids = _id_list(
        arguments["edge_ids"],
        visible_edges,
        maximum=8,
        label="edge IDs",
    )
    selected_edges = [dict(edge_by_id[item]) for item in edge_ids]
    projections = {
        str(row["edge_id"]): row
        for graph_row in source_projection.get("graphs", [])
        if graph_row.get("graph_id") == graph_id
        for row in graph_row.get("edge_rows", [])
    }
    selected_projection = [
        projections[item] for item in edge_ids if item in projections
    ]
    disclosed = {
        str(row["source_candidate_id"])
        for row in selected_projection
        if row.get("projection_status") == "exact"
        and row.get("source_candidate_id")
    }
    visibility["source_candidate_ids"].update(disclosed)
    result = {
        "schema_version": "chisel_causal_edge_evidence_query.v1",
        "graph_id": graph_id,
        "edges": selected_edges,
        "source_projection": selected_projection,
    }
    result["result_sha256"] = canonical_sha256(result)
    if _contains_absolute_path(result):
        raise CausalToolError("query result contains an absolute path")
    return result


def _visible_graph_ids(
    visibility: MutableMapping[str, Any],
    kind: str,
    graph_id: str,
) -> set[str]:
    by_graph = visibility.get(kind + "_by_graph")
    if isinstance(by_graph, dict):
        return by_graph.setdefault(graph_id, set())
    return visibility[kind]


def _disclose(
    visibility: MutableMapping[str, Any],
    graph_id: str,
    *,
    node_ids: Sequence[str] | set[str],
    edge_ids: Sequence[str] | set[str],
) -> None:
    nodes = set(node_ids)
    edges = set(edge_ids)
    visibility["node_ids"].update(nodes)
    visibility["edge_ids"].update(edges)
    _visible_graph_ids(visibility, "node_ids", graph_id).update(nodes)
    _visible_graph_ids(visibility, "edge_ids", graph_id).update(edges)


def _v3_result_ids(value: Mapping[str, Any]) -> tuple[set[str], set[str]]:
    nodes: set[str] = set()
    edges: set[str] = set()

    def visit(row: Any) -> None:
        if isinstance(row, Mapping):
            for key, item in row.items():
                if key in {"node_id", "semantic_id"} and isinstance(item, str):
                    nodes.add(item)
                elif key in {
                    "node_ids",
                    "semantic_path",
                    "members",
                } and isinstance(item, list):
                    nodes.update(str(member) for member in item)
                elif key == "edge_id" and isinstance(item, str):
                    edges.add(item)
                elif key in {"edge_ids", "edges"} and isinstance(item, list):
                    for edge in item:
                        if isinstance(edge, str):
                            edges.add(edge)
                        else:
                            visit(edge)
                else:
                    visit(item)
        elif isinstance(row, list):
            for item in row:
                visit(item)

    visit(value)
    return nodes, edges


def _v3_raw_predecessors(
    graph: Mapping[str, Any],
    node_ids: Sequence[str],
    *,
    max_hops: int,
    max_nodes: int,
) -> Dict[str, Any]:
    node_by_id = {
        str(row["node_id"]): row for row in graph.get("signal_nodes", [])
    }
    incoming: Dict[str, list[Mapping[str, Any]]] = {}
    for edge in graph.get("edges", []):
        if "src_node_id" in edge and "dst_node_id" in edge:
            incoming.setdefault(str(edge["dst_node_id"]), []).append(edge)
    selected_nodes = set(node_ids)
    selected_edges: Dict[str, Mapping[str, Any]] = {}
    frontier = sorted(node_ids)
    for _hop in range(max_hops):
        next_frontier = []
        for target in frontier:
            for edge in sorted(
                incoming.get(target, []), key=lambda row: str(row["edge_id"])
            ):
                source = str(edge["src_node_id"])
                if source not in node_by_id or len(selected_nodes) >= max_nodes:
                    continue
                selected_nodes.add(source)
                selected_edges[str(edge["edge_id"])] = edge
                next_frontier.append(source)
        frontier = sorted(set(next_frontier))
    result = {
        "schema_version": "chisel_raw_predecessor_query.v1",
        "graph_id": graph["graph_id"],
        "nodes": [dict(node_by_id[item]) for item in sorted(selected_nodes)],
        "edges": [
            dict(selected_edges[item]) for item in sorted(selected_edges)
        ],
        "max_hops": max_hops,
        "max_nodes": max_nodes,
    }
    result["result_sha256"] = canonical_sha256(result)
    return result


def _v3_raw_paths(
    graph: Mapping[str, Any],
    target: str,
    *,
    max_paths: int,
    max_length: int,
) -> Dict[str, Any]:
    expansion = _v3_raw_predecessors(
        graph, [target], max_hops=min(2, max_length), max_nodes=20
    )
    paths = [
        {
            "node_ids": [
                str(edge["src_node_id"]),
                str(edge["dst_node_id"]),
            ],
            "edge_ids": [str(edge["edge_id"])],
        }
        for edge in expansion["edges"][:max_paths]
    ]
    result = {
        "schema_version": "chisel_raw_paths_query.v1",
        "graph_id": graph["graph_id"],
        "target_node_id": target,
        "paths": paths,
    }
    result["result_sha256"] = canonical_sha256(result)
    return result


def _v3_pipeline_range(
    graph: Mapping[str, Any], pipeline_id: str
) -> tuple[int, int]:
    intervals = [
        row
        for row in graph.get("semantic_nodes", [])
        if row.get("type") == "pipeline_occupancy"
        and row.get("pipeline_id") == pipeline_id
        and isinstance(row.get("start_cycle"), int)
        and isinstance(row.get("end_cycle"), int)
    ]
    if intervals:
        return (
            min(int(row["start_cycle"]) for row in intervals),
            max(int(row["end_cycle"]) for row in intervals),
        )
    cycle = graph.get("endpoint", {}).get("cycle", 0)
    if isinstance(cycle, bool) or not isinstance(cycle, int) or cycle < 0:
        cycle = 0
    return cycle, cycle


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
