"""Frozen SpecFlow-side contracts for V6 deterministic causal evidence."""

from __future__ import annotations

import json
import re
from pathlib import Path
from typing import Any, Dict, Mapping

from src.core.formal_operations import canonical_sha256


CAUSAL_GRAPH_MANIFEST_SCHEMA = "causal_graph_manifest.v1"
CAUSAL_SOURCE_PROJECTION_SCHEMA = "causal_source_projection.v1"
DIAGNOSIS_CANDIDATE_SCHEMA = "diagnosis_candidate.v2"
DIAGNOSIS_TRANSCRIPT_SCHEMA = "diagnosis_transcript_manifest.v1"

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
    "causal_backend": "verilog_causal_analysis.v2",
    "causal_policy": "best_effort",
    "clock_domain": "formal_primary",
    "max_depth": 12,
    "max_nodes": 120,
    "random_seed": 0,
    "max_model_calls": 3,
    "max_evidence_queries": 2,
    "max_source_context_lines": 5,
}

_SHA256_RE = re.compile(r"^[0-9a-f]{64}$")
_SAFE_ID_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_.:-]*$")


class CausalContractError(ValueError):
    """Raised when a SpecFlow V6 causal artifact is not exact."""


def effective_causal_config(
    value: Mapping[str, Any] | None = None,
) -> Dict[str, Any]:
    config = dict(DEFAULT_CAUSAL_CONFIG if value is None else value)
    if set(config) != set(DEFAULT_CAUSAL_CONFIG):
        raise CausalContractError("diagnosis causal config has invalid exact fields")
    if config["causal_backend"] != "verilog_causal_analysis.v2":
        raise CausalContractError("unsupported causal backend")
    if config["causal_policy"] not in CAUSAL_POLICIES:
        raise CausalContractError("unsupported causal policy")
    if config["clock_domain"] != "formal_primary":
        raise CausalContractError("only the primary formal clock is supported")
    for field in ("max_depth", "max_nodes", "max_model_calls", "max_source_context_lines"):
        if (
            isinstance(config[field], bool)
            or not isinstance(config[field], int)
            or config[field] < 1
        ):
            raise CausalContractError(f"{field} must be a positive integer")
    for field in ("random_seed", "max_evidence_queries"):
        if (
            isinstance(config[field], bool)
            or not isinstance(config[field], int)
            or config[field] < 0
        ):
            raise CausalContractError(f"{field} must be a non-negative integer")
    if config["max_model_calls"] != 3 or config["max_evidence_queries"] != 2:
        raise CausalContractError("V6 Iteration 3 budget is frozen at 3 calls/2 queries")
    if config["max_source_context_lines"] != 5:
        raise CausalContractError("source context is frozen at five lines")
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
