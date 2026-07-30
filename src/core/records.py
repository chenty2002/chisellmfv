"""Small record helpers used by the active workflows."""

from __future__ import annotations

from typing import Any, Dict, Iterable, Optional


STAGE_RESULT_SCHEMA = "stage_result"
RUN_COST_SUMMARY_SCHEMA = "run_cost_summary"


def normalize_stage_result(stage: str, result: Dict[str, Any]) -> Dict[str, Any]:
    """Return the persisted stage result without inventing legacy artifacts."""

    normalized = dict(result)
    normalized["schema_version"] = STAGE_RESULT_SCHEMA
    normalized["stage"] = stage
    if (
        normalized.get("success") is False
        and not normalized.get("error_kind")
        and normalized.get("termination_reason")
    ):
        normalized["error_kind"] = normalized["termination_reason"]
    return normalized


def build_run_cost_summary(
    token_usage: Optional[Dict[str, Any]],
    *,
    stage_results: Optional[Iterable[Dict[str, Any]]] = None,
) -> Dict[str, Any]:
    """Persist only model usage and stage termination reasons."""

    usage = dict(token_usage or {})
    prompt_tokens = int(usage.get("llm_prompt_tokens", 0) or 0)
    cached_tokens = int(usage.get("llm_cached_prompt_tokens", 0) or 0)
    return {
        "schema_version": RUN_COST_SUMMARY_SCHEMA,
        "llm": {
            "calls": int(usage.get("llm_calls", 0) or 0),
            "prompt_tokens": prompt_tokens,
            "cached_prompt_tokens": cached_tokens,
            "completion_tokens": int(usage.get("llm_completion_tokens", 0) or 0),
            "reasoning_tokens": int(usage.get("llm_reasoning_tokens", 0) or 0),
            "total_tokens": int(usage.get("llm_total_tokens", 0) or 0),
        },
        "termination_reasons": {
            str(result["stage"]): str(result["termination_reason"])
            for result in (stage_results or [])
            if result.get("stage") and result.get("termination_reason")
        },
    }


def merge_run_cost_summaries(
    previous: Optional[Dict[str, Any]],
    current: Dict[str, Any],
) -> Dict[str, Any]:
    """Add usage from a resumed invocation to its existing run summary."""

    if not previous:
        return current
    merged = dict(current)
    merged_llm = dict(current["llm"])
    for metric in (
        "calls",
        "prompt_tokens",
        "cached_prompt_tokens",
        "completion_tokens",
        "reasoning_tokens",
        "total_tokens",
    ):
        merged_llm[metric] = int((previous.get("llm") or {}).get(metric, 0) or 0) + int(
            merged_llm.get(metric, 0) or 0
        )
    merged["llm"] = merged_llm
    reasons = dict(previous.get("termination_reasons") or {})
    reasons.update(current.get("termination_reasons") or {})
    merged["termination_reasons"] = reasons
    return merged
