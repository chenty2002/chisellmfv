"""Durable raw tool results and bounded model-visible result views."""

from __future__ import annotations

import json
import re
from pathlib import Path
from typing import Any, Dict, List, Sequence

from .llm_client import count_tokens


TOOL_RESULT_VIEW_SCHEMA_VERSION = "tool_result.v2"


def _json_text(value: Any) -> str:
    return json.dumps(
        value,
        ensure_ascii=False,
        separators=(",", ":"),
        sort_keys=True,
        default=str,
    )


def _token_count(value: Any) -> int:
    return count_tokens(_json_text(value))


def _json_safe(value: Any) -> Any:
    if value is None or isinstance(value, (bool, int, float, str)):
        return value
    if isinstance(value, dict):
        return {str(key): _json_safe(item) for key, item in value.items()}
    if isinstance(value, (list, tuple)):
        return [_json_safe(item) for item in value]
    return str(value)


def _bounded_value(value: Any, char_budget: int) -> Any:
    """Shrink values structurally: mappings by field, arrays by item, strings by text."""
    char_budget = max(0, int(char_budget))
    if value is None or isinstance(value, (bool, int, float)):
        return value
    if isinstance(value, str):
        if len(value) <= char_budget:
            return value
        if char_budget <= 1:
            return ""
        suffix = "...[truncated]"
        return value[: max(0, char_budget - len(suffix))] + suffix
    if isinstance(value, list):
        if not value or char_budget <= 2:
            return []
        output: List[Any] = []
        used = 2
        per_item_cap = max(32, char_budget // min(len(value), 16))
        for item in value:
            bounded = _bounded_value(item, min(per_item_cap, max(0, char_budget - used)))
            encoded = _json_text(bounded)
            added = len(encoded) + (1 if output else 0)
            if used + added > char_budget:
                break
            output.append(bounded)
            used += added
        return output
    if isinstance(value, dict):
        if not value or char_budget <= 2:
            return {}
        output: Dict[str, Any] = {}
        used = 2
        items = list(value.items())
        for index, (key, item) in enumerate(items):
            key = str(key)
            key_cost = len(_json_text(key)) + 1 + (1 if output else 0)
            remaining = char_budget - used - key_cost
            if remaining <= 0:
                break
            remaining_fields = max(1, len(items) - index)
            bounded = _bounded_value(item, max(16, remaining // remaining_fields))
            encoded = _json_text(bounded)
            if used + key_cost + len(encoded) > char_budget:
                bounded = _bounded_value(item, max(0, remaining))
                encoded = _json_text(bounded)
            if used + key_cost + len(encoded) > char_budget:
                break
            output[key] = bounded
            used += key_cost + len(encoded)
        return output
    return _bounded_value(str(value), char_budget)


def _safe_call_id(call_id: str) -> str:
    cleaned = re.sub(r"[^A-Za-z0-9_.-]+", "_", str(call_id or "call"))
    return cleaned[:96] or "call"


def prepare_model_view(
    raw_result: Dict[str, Any],
    *,
    artifact: str,
    token_limit: int,
) -> Dict[str, Any]:
    """Build one bounded view after its caller has durably stored the raw result."""
    result_type = str(raw_result.get("type", "unknown"))
    success = bool(raw_result.get("success", False))
    original_tokens = _token_count(raw_result)
    data = {
        key: value
        for key, value in raw_result.items()
        if key not in {"schema_version", "type", "success"}
    }
    view = {
        "schema_version": TOOL_RESULT_VIEW_SCHEMA_VERSION,
        "type": result_type,
        "success": success,
        "truncated": False,
        "original_tokens": original_tokens,
        "returned_tokens": 0,
        "artifact": artifact,
        "data": data,
    }
    if _token_count(view) > token_limit:
        view["truncated"] = True
        envelope_tokens = _token_count({**view, "data": {}})
        char_budget = max(0, (token_limit - envelope_tokens - 8) * 4)
        view["data"] = _bounded_value(data, char_budget)
        while _token_count(view) > token_limit and char_budget > 0:
            char_budget = int(char_budget * 0.75)
            view["data"] = _bounded_value(data, char_budget)
        if _token_count(view) > token_limit:
            view["data"] = {}

    for _ in range(4):
        returned_tokens = _token_count(view)
        if view["returned_tokens"] == returned_tokens:
            break
        view["returned_tokens"] = returned_tokens
    if _token_count(view) > token_limit:
        view["truncated"] = True
        view["data"] = {}
        for _ in range(4):
            returned_tokens = _token_count(view)
            if view["returned_tokens"] == returned_tokens:
                break
            view["returned_tokens"] = returned_tokens
    return view


class ToolResultLimiter:
    """Persist raw results before producing bounded, valid-JSON model views."""

    def __init__(
        self,
        stage_dir: Path,
        *,
        per_result_token_limit: int,
        batch_token_limit: int,
    ):
        self.stage_dir = Path(stage_dir)
        self.result_dir = self.stage_dir / "tool_results"
        self.per_result_token_limit = max(1, int(per_result_token_limit))
        self.batch_token_limit = max(1, int(batch_token_limit))

    def prepare_batch(
        self,
        raw_results: Sequence[Dict[str, Any]],
        *,
        iteration: int,
        call_ids: Sequence[str],
    ) -> List[Dict[str, Any]]:
        if len(raw_results) != len(call_ids):
            raise ValueError("raw_results and call_ids must have the same length")

        self.result_dir.mkdir(parents=True, exist_ok=True)
        prepared = []
        for sequence, (raw_result, call_id) in enumerate(
            zip(raw_results, call_ids),
            start=1,
        ):
            artifact_name = (
                f"{int(iteration):04d}-{sequence:02d}-{_safe_call_id(call_id)}.json"
            )
            artifact_path = self.result_dir / artifact_name
            safe_raw = _json_safe(raw_result)
            artifact_path.write_text(
                json.dumps(safe_raw, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
                encoding="utf-8",
            )
            artifact = f"tool_results/{artifact_name}"
            prepared.append((safe_raw, artifact))

        minimum_tokens = [
            prepare_model_view(
                safe_raw,
                artifact=artifact,
                token_limit=1,
            )["returned_tokens"]
            for safe_raw, artifact in prepared
        ]
        views: List[Dict[str, Any]] = []
        remaining = self.batch_token_limit
        for index, (safe_raw, artifact) in enumerate(prepared):
            future_minimum = sum(minimum_tokens[index + 1:])
            available = max(minimum_tokens[index], remaining - future_minimum)
            limit = min(self.per_result_token_limit, available)
            view = prepare_model_view(
                safe_raw,
                artifact=artifact,
                token_limit=limit,
            )
            views.append(view)
            remaining = max(0, remaining - int(view["returned_tokens"]))
        return views
