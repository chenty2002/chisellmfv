"""SpecFlow-side contracts for deterministic causal evidence."""

from __future__ import annotations

import json
import re
from pathlib import Path
from typing import Any, Dict, Mapping

from src.core.formal_operations import canonical_sha256


CAUSAL_GRAPH_MANIFEST_SCHEMA = "causal_graph_manifest"
STRUCTURAL_GRAPH_SCHEMA = "verilog_causal_graph"
CAUSAL_GRAPH_SCHEMA = "verilog_causal_semantic_graph"
CAUSAL_SOURCE_PROJECTION_SCHEMA = "causal_source_projection"

CAUSAL_POLICIES = frozenset(
    {
        "off",
        "best_effort",
        "required_for_design_violation",
        "required_for_track_d",
    }
)
CAUSAL_MANIFEST_STATUSES = frozenset(
    {"complete", "incomplete", "unsupported", "not_required"}
)
SOURCE_PROJECTION_STATUSES = frozenset(
    {"complete", "incomplete", "unsupported", "not_required"}
)
EDGE_PROJECTION_STATUSES = frozenset(
    {"exact", "rtl_only", "ambiguous", "missing"}
)

DEFAULT_CAUSAL_CONFIG = {
    "causal_backend": "verilog_causal_analysis",
    "causal_policy": "best_effort",
    "clock_domain": "formal_primary",
    "max_depth": 12,
    "max_nodes": 120,
    "random_seed": 0,
}

_SHA256_RE = re.compile(r"^[0-9a-f]{64}$")
_SAFE_ID_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_.:-]*$")


class CausalContractError(ValueError):
    """Raised when a SpecFlow causal artifact is not exact."""


def causal_graph_schema(value: Mapping[str, Any]) -> str:
    """Return one explicitly supported graph schema without field inference."""

    schema = value.get("schema_version")
    if schema not in {STRUCTURAL_GRAPH_SCHEMA, CAUSAL_GRAPH_SCHEMA}:
        raise CausalContractError("unsupported causal graph schema")
    return str(schema)


def causal_graph_stable_ids(
    value: Mapping[str, Any],
) -> tuple[set[str], set[str]]:
    """Extract the materialized stable node/edge domain for one graph."""

    schema = causal_graph_schema(value)
    if schema == STRUCTURAL_GRAPH_SCHEMA:
        rows = value.get("nodes", [])
        if not isinstance(rows, list):
            raise CausalContractError("causal graph identity rows are invalid")
        node_rows = [(row, "node_id") for row in rows]
    else:
        signal_rows = value.get("signal_nodes", [])
        semantic_rows = value.get("semantic_nodes", [])
        if not isinstance(signal_rows, list) or not isinstance(
            semantic_rows, list
        ):
            raise CausalContractError("semantic causal graph node rows are invalid")
        node_rows = [
            *((row, "node_id") for row in signal_rows),
            *((row, "semantic_id") for row in semantic_rows),
        ]
    edge_rows = value.get("edges", [])
    if not isinstance(node_rows, list) or not isinstance(edge_rows, list):
        raise CausalContractError("causal graph identity rows are invalid")

    node_ids: set[str] = set()
    semantic_ids: set[str] = set()
    for row, identity_field in node_rows:
        if (
            not isinstance(row, Mapping)
            or not isinstance(row.get(identity_field), str)
            or not row[identity_field]
            or (
                schema == CAUSAL_GRAPH_SCHEMA
                and identity_field == "node_id"
                and "semantic_id" in row
            )
            or (
                schema == CAUSAL_GRAPH_SCHEMA
                and identity_field == "semantic_id"
                and "node_id" in row
            )
        ):
            raise CausalContractError("causal graph node row is invalid")
        identity = str(row[identity_field])
        if identity in node_ids:
            raise CausalContractError(
                "causal graph contains an unknown or duplicate node ID class"
            )
        node_ids.add(identity)
        if identity_field == "semantic_id":
            semantic_ids.add(identity)

    if schema == CAUSAL_GRAPH_SCHEMA:
        for row in value.get("root_candidates", []):
            path = row.get("semantic_path") if isinstance(row, Mapping) else None
            if (
                not isinstance(row, Mapping)
                or row.get("semantic_id") not in semantic_ids
                or not isinstance(path, list)
                or any(item not in semantic_ids for item in path)
            ):
                raise CausalContractError(
                    "root candidate references an unknown semantic ID"
                )

    edge_ids: set[str] = set()
    endpoint_fields = (
        "src_node_id",
        "dst_node_id",
        "src_semantic_id",
        "dst_semantic_id",
    )
    for row in edge_rows:
        if (
            not isinstance(row, Mapping)
            or not isinstance(row.get("edge_id"), str)
            or not row["edge_id"]
            or row["edge_id"] in edge_ids
        ):
            raise CausalContractError(
                "causal graph contains an invalid or duplicate edge ID"
            )
        referenced = [
            str(row[field])
            for field in endpoint_fields
            if field in row
        ]
        if referenced and any(item not in node_ids for item in referenced):
            raise CausalContractError(
                "causal graph edge references an unknown stable ID"
            )
        edge_ids.add(str(row["edge_id"]))
    return node_ids, edge_ids


def effective_causal_config(
    value: Mapping[str, Any] | None = None,
) -> Dict[str, Any]:
    config = dict(DEFAULT_CAUSAL_CONFIG if value is None else value)
    if set(config) != set(DEFAULT_CAUSAL_CONFIG):
        raise CausalContractError("diagnosis causal config has invalid exact fields")
    if config["causal_backend"] != "verilog_causal_analysis":
        raise CausalContractError("unsupported causal backend")
    if config["causal_policy"] not in CAUSAL_POLICIES:
        raise CausalContractError("unsupported causal policy")
    if config["clock_domain"] != "formal_primary":
        raise CausalContractError("only the primary formal clock is supported")
    for field in ("max_depth", "max_nodes"):
        if (
            isinstance(config[field], bool)
            or not isinstance(config[field], int)
            or config[field] < 1
        ):
            raise CausalContractError(f"{field} must be a positive integer")
    if (
        isinstance(config["random_seed"], bool)
        or not isinstance(config["random_seed"], int)
        or config["random_seed"] < 0
    ):
        raise CausalContractError("random_seed must be a non-negative integer")
    return config


def validate_causal_seed(value: Mapping[str, Any]) -> Dict[str, Any]:
    fields = {
        "status",
        "operation_id",
        "failure_cycle",
        "endpoint_candidates",
        "clock_signal",
        "errors",
    }
    if not isinstance(value, Mapping) or set(value) != fields:
        raise CausalContractError("causal seed has an invalid exact schema")
    if value["status"] not in {"ready", "ambiguous"}:
        raise CausalContractError("causal seed status is invalid")
    if not _safe_id(value["operation_id"]):
        raise CausalContractError("causal seed operation ID is invalid")
    cycle = value["failure_cycle"]
    if cycle is not None and (
        isinstance(cycle, bool) or not isinstance(cycle, int) or cycle < 0
    ):
        raise CausalContractError("causal seed failure cycle is invalid")
    if not isinstance(value["clock_signal"], str) or not value["clock_signal"]:
        raise CausalContractError("causal seed clock must be exact")
    candidates = value["endpoint_candidates"]
    if not isinstance(candidates, list):
        raise CausalContractError("causal seed endpoint candidates must be a list")
    seen = set()
    for row in candidates:
        expected = {
            "object_id",
            "binding_id",
            "emitted_signal",
            "selection_reason",
        }
        if not isinstance(row, Mapping) or set(row) != expected:
            raise CausalContractError("causal endpoint has an invalid exact schema")
        if row["selection_reason"] != "failed_expression_observer":
            raise CausalContractError("causal endpoint selection is not typed")
        identity = (row["object_id"], row["binding_id"])
        if identity in seen or not all(_safe_id(item) for item in identity):
            raise CausalContractError("causal endpoint identity is invalid")
        seen.add(identity)
        if not isinstance(row["emitted_signal"], str) or not row["emitted_signal"]:
            raise CausalContractError("causal endpoint signal is invalid")
    if value["status"] == "ready" and (cycle is None or not candidates):
        raise CausalContractError("ready causal seed has no exact endpoint")
    if not isinstance(value["errors"], list):
        raise CausalContractError("causal seed errors must be a list")
    return dict(value)


def validate_causal_graph_manifest(value: Mapping[str, Any]) -> Dict[str, Any]:
    fields = {
        "schema_version",
        "round_id",
        "status",
        "policy",
        "analyzer",
        "inputs",
        "graphs",
        "errors",
    }
    if not isinstance(value, Mapping) or set(value) != fields:
        raise CausalContractError("causal graph manifest has invalid exact fields")
    if value["schema_version"] != CAUSAL_GRAPH_MANIFEST_SCHEMA:
        raise CausalContractError("causal graph manifest schema is invalid")
    if value["status"] not in CAUSAL_MANIFEST_STATUSES:
        raise CausalContractError("causal graph manifest status is invalid")
    if value["policy"] not in CAUSAL_POLICIES:
        raise CausalContractError("causal graph manifest policy is invalid")
    if not isinstance(value["graphs"], list) or not isinstance(value["errors"], list):
        raise CausalContractError("causal graph manifest lists are invalid")
    for digest in value["inputs"].values():
        if not isinstance(digest, str) or not _SHA256_RE.fullmatch(digest):
            raise CausalContractError("causal graph input identity is invalid")
    for row in value["graphs"]:
        expected = {
            "operation_id",
            "endpoint_object_id",
            "graph_id",
            "path",
            "sha256",
            "status",
        }
        if not isinstance(row, Mapping) or set(row) != expected:
            raise CausalContractError("causal graph row has invalid exact fields")
        path = Path(str(row["path"]))
        if path.is_absolute() or ".." in path.parts:
            raise CausalContractError("causal graph path must be stage-relative")
        if not _SHA256_RE.fullmatch(str(row["sha256"])):
            raise CausalContractError("causal graph hash is invalid")
    return dict(value)


def stable_candidate_id(value: Mapping[str, Any]) -> str:
    return "diag_" + canonical_sha256(dict(value))[:24]


def stable_source_candidate_id(
    graph_id: str, anchor: Mapping[str, Any]
) -> str:
    return "sc_" + canonical_sha256(
        {"graph_id": graph_id, "source_anchor": dict(anchor)}
    )[:24]


def canonical_json_bytes(value: Any) -> bytes:
    return json.dumps(
        value,
        sort_keys=True,
        separators=(",", ":"),
        ensure_ascii=False,
        allow_nan=False,
    ).encode("utf-8")


def _safe_id(value: Any) -> bool:
    return isinstance(value, str) and bool(_SAFE_ID_RE.fullmatch(value))
