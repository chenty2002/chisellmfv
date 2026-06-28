from __future__ import annotations

"""Typed helpers for workflow records written to artifacts and tool output."""

from dataclasses import asdict, dataclass, field
from datetime import datetime, timezone
from typing import Any, Dict, Optional


TOOL_RESULT_SCHEMA_VERSION = "tool_result.v1"
STAGE_RESULT_SCHEMA_VERSION = "stage_result.v1"
STAGE_EVENT_SCHEMA_VERSION = "stage_event.v1"
RUN_COST_SUMMARY_SCHEMA_VERSION = "run_cost_summary.v1"
OPERATION_SCHEMA_VERSION = "operation.v1"
STAGE_INPUTS_SCHEMA_VERSION = "stage_inputs.v1"
STAGE_HANDOFF_SCHEMA_VERSION = "stage_handoff.v1"


@dataclass(frozen=True)
class ToolResultRecord:
    """Normalized tool result envelope used by agent loops and JSON artifacts."""

    type: str
    success: bool
    data: Dict[str, Any] = field(default_factory=dict)
    metrics: Dict[str, Any] = field(default_factory=dict)
    artifacts: Dict[str, Any] = field(default_factory=dict)
    schema_version: str = TOOL_RESULT_SCHEMA_VERSION

    def to_dict(self) -> Dict[str, Any]:
        payload = dict(self.data)
        payload.update(
            {
                "schema_version": self.schema_version,
                "type": self.type,
                "success": self.success,
                "metrics": dict(self.metrics),
                "artifacts": dict(self.artifacts),
            }
        )
        return payload


@dataclass(frozen=True)
class StageEventRecord:
    """Machine-readable event for stage-local JSONL logs."""

    event: str
    stage: str
    success: Optional[bool] = None
    iteration: Optional[int] = None
    tool: Optional[str] = None
    artifact: Optional[str] = None
    data: Dict[str, Any] = field(default_factory=dict)
    schema_version: str = STAGE_EVENT_SCHEMA_VERSION
    timestamp: str = field(default_factory=lambda: datetime.now(timezone.utc).isoformat())

    def to_dict(self) -> Dict[str, Any]:
        payload = asdict(self)
        return {key: value for key, value in payload.items() if value is not None}


def normalize_tool_result(result: Dict[str, Any]) -> Dict[str, Any]:
    """Add stable schema, metrics, and artifact fields to a tool result."""
    if result.get("schema_version") == TOOL_RESULT_SCHEMA_VERSION:
        return result

    metrics: Dict[str, Any] = {}
    artifacts: Dict[str, Any] = {}
    passthrough = dict(result)

    for key in ("line_delta", "returned_chars", "original_chars", "original_lines"):
        if key in result:
            metrics[key] = result[key]

    for key in ("diff", "path", "file_path", "round_summary_path"):
        value = result.get(key)
        if value is not None:
            artifacts[key] = value

    return ToolResultRecord(
        type=str(result.get("type", "unknown")),
        success=bool(result.get("success", False)),
        data=passthrough,
        metrics=metrics,
        artifacts=artifacts,
    ).to_dict()


def normalize_stage_result(stage: str, result: Dict[str, Any]) -> Dict[str, Any]:
    """Return a stage result envelope with a stable schema version."""
    if result.get("schema_version") == STAGE_RESULT_SCHEMA_VERSION:
        return result
    normalized = dict(result)
    normalized["schema_version"] = STAGE_RESULT_SCHEMA_VERSION
    normalized["stage"] = stage
    return normalized


def make_stage_event(
    *,
    event: str,
    stage: str,
    success: Optional[bool] = None,
    iteration: Optional[int] = None,
    tool: Optional[str] = None,
    artifact: Optional[str] = None,
    data: Optional[Dict[str, Any]] = None,
) -> Dict[str, Any]:
    return StageEventRecord(
        event=event,
        stage=stage,
        success=success,
        iteration=iteration,
        tool=tool,
        artifact=artifact,
        data=data or {},
    ).to_dict()


def build_run_cost_summary(token_usage: Optional[Dict[str, Any]]) -> Dict[str, Any]:
    """Convert raw client token usage into a compact run cost artifact."""
    usage = dict(token_usage or {})
    prompt_tokens = int(usage.get("llm_prompt_tokens", 0) or 0)
    cached_tokens = int(usage.get("llm_cached_prompt_tokens", 0) or 0)
    model_roles: Dict[str, Dict[str, Any]] = {}

    for key, bucket in (usage.get("llm_usage_by_key") or {}).items():
        labels = _parse_usage_key(key)
        role = labels.get("model_role") or "unknown"
        model_bucket = model_roles.setdefault(
            role,
            {
                "model": labels.get("model"),
                "prompt_tokens": 0,
                "cached_prompt_tokens": 0,
                "cache_miss_prompt_tokens": 0,
                "completion_tokens": 0,
                "reasoning_tokens": 0,
                "total_tokens": 0,
            },
        )
        if labels.get("model") and not model_bucket.get("model"):
            model_bucket["model"] = labels["model"]
        for metric in (
            "prompt_tokens",
            "cached_prompt_tokens",
            "cache_miss_prompt_tokens",
            "completion_tokens",
            "reasoning_tokens",
            "total_tokens",
        ):
            model_bucket[metric] += int(bucket.get(metric, 0) or 0)

    return {
        "schema_version": RUN_COST_SUMMARY_SCHEMA_VERSION,
        "llm": {
            "calls": int(usage.get("llm_calls", 0) or 0),
            "prompt_tokens": prompt_tokens,
            "cached_prompt_tokens": cached_tokens,
            "cache_miss_prompt_tokens": int(usage.get("llm_cache_miss_prompt_tokens", 0) or 0),
            "completion_tokens": int(usage.get("llm_completion_tokens", 0) or 0),
            "reasoning_tokens": int(usage.get("llm_reasoning_tokens", 0) or 0),
            "total_tokens": int(usage.get("llm_total_tokens", 0) or 0),
            "cache_hit_rate": (cached_tokens / prompt_tokens) if prompt_tokens else 0.0,
        },
        "embedding": {
            "calls": int(usage.get("embedding_calls", 0) or 0),
            "total_tokens": int(usage.get("embedding_total_tokens", 0) or 0),
        },
        "reranker": {
            "calls": int(usage.get("reranker_calls", 0) or 0),
            "input_tokens": int(usage.get("reranker_input_tokens", 0) or 0),
            "output_tokens": int(usage.get("reranker_output_tokens", 0) or 0),
        },
        "model_roles": model_roles,
        "budget": dict(usage.get("budget") or {}),
    }


def _parse_usage_key(key: str) -> Dict[str, str]:
    labels: Dict[str, str] = {}
    for part in key.split("|"):
        name, sep, value = part.partition("=")
        if sep:
            labels[name] = value
    return labels
