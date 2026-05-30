"""Utilities for the stage-5 repair-regression loop."""

from __future__ import annotations

import json
import math
from pathlib import Path
from typing import Any, Dict, Iterable, List, Optional, Sequence


DEFAULT_MAX_REPAIR_ROUNDS = 3


def extract_cex_assertions(stage3_result: Optional[Dict[str, Any]]) -> List[Dict[str, Any]]:
    """Return CEX assertion records from either workflow or raw JasperGold shape."""
    if not stage3_result:
        return []

    direct = stage3_result.get("cex_assertions")
    if isinstance(direct, list):
        return [item for item in direct if isinstance(item, dict)]

    jg_result = stage3_result.get("jaspergold_result") or {}
    nested = jg_result.get("cex_assertions")
    if isinstance(nested, list):
        return [item for item in nested if isinstance(item, dict)]

    return []


def extract_failing_properties(stage3_result: Optional[Dict[str, Any]]) -> List[str]:
    """Return sorted unique failing assertion/property names."""
    names = {
        str(item.get("name"))
        for item in extract_cex_assertions(stage3_result)
        if item.get("name")
    }
    return sorted(names)


def _trace_length(item: Dict[str, Any]) -> float:
    for key in ("trace_length", "max_length", "bound"):
        value = item.get(key)
        if value is None:
            continue
        try:
            return float(value)
        except (TypeError, ValueError):
            continue
    return math.inf


def select_next_counterexample(
    stage3_result: Optional[Dict[str, Any]],
    previous_property: Optional[str],
    original_failing_properties: Sequence[str],
) -> Optional[Dict[str, Any]]:
    """
    Deterministically choose the next CEX to analyze.

    Policy:
    1. keep the previous property if it still has a CEX;
    2. otherwise choose any original failing property that still has a CEX;
    3. otherwise choose the shortest-trace remaining CEX;
    4. break ties by property name.
    """
    cex = extract_cex_assertions(stage3_result)
    if not cex:
        return None

    by_name = {str(item.get("name")): item for item in cex if item.get("name")}

    if previous_property and previous_property in by_name:
        return by_name[previous_property]

    for name in sorted(set(original_failing_properties)):
        if name in by_name:
            return by_name[name]

    return sorted(
        cex,
        key=lambda item: (_trace_length(item), str(item.get("name", ""))),
    )[0]


def _all_assertion_names(stage3_result: Optional[Dict[str, Any]]) -> List[str]:
    if not stage3_result:
        return []
    assertions = stage3_result.get("assertions")
    if assertions is None:
        assertions = (stage3_result.get("jaspergold_result") or {}).get("assertions")
    if not isinstance(assertions, list):
        return []
    return sorted(
        str(item.get("name"))
        for item in assertions
        if isinstance(item, dict) and item.get("name")
    )


def check_repair_target_presence(
    initial_failing_properties: Sequence[str],
    stage3_result: Optional[Dict[str, Any]],
) -> Dict[str, Any]:
    """
    Check that original failing assertion/property labels still exist.

    Missing labels mean the repair deleted or renamed the target assertion and
    must not be credited as a successful repair.
    """
    requested = sorted(set(str(name) for name in initial_failing_properties if name))
    present = set(_all_assertion_names(stage3_result))
    missing = [name for name in requested if name not in present]
    return {
        "all_present": not missing,
        "checked_properties": requested,
        "missing_properties": missing,
        "present_properties": sorted(present),
    }


def build_final_repair_result(
    *,
    max_repair_rounds: int,
    rounds: Sequence[Dict[str, Any]],
    initial_stage3_result: Optional[Dict[str, Any]],
    final_stage3_result: Optional[Dict[str, Any]],
    repair_success: bool,
    target_presence: Optional[Dict[str, Any]] = None,
) -> Dict[str, Any]:
    """Build the machine-readable final repair-loop result."""
    initial = extract_failing_properties(initial_stage3_result)
    final = extract_failing_properties(final_stage3_result)
    initial_set = set(initial)
    final_set = set(final)
    history_summaries = [
        str(round_info.get("round_summary", "")).strip()
        for round_info in rounds
        if str(round_info.get("round_summary", "")).strip()
    ]

    result = {
        "max_repair_rounds": max_repair_rounds,
        "rounds_run": len(rounds),
        "repair_success": bool(repair_success),
        "initial_cex_count": int((initial_stage3_result or {}).get("cex_count", len(initial))),
        "final_cex_count": int((final_stage3_result or {}).get("cex_count", len(final))),
        "initial_failing_properties": initial,
        "final_failing_properties": final,
        "resolved_properties": sorted(initial_set - final_set),
        "persistent_properties": sorted(initial_set & final_set),
        "new_failing_properties": sorted(final_set - initial_set),
        "history_summaries": history_summaries,
        "rounds": list(rounds),
    }
    if target_presence is not None:
        result["target_presence"] = target_presence
        if not target_presence.get("all_present", True):
            result["repair_success"] = False
    return result


def write_repair_json(path: Path, data: Dict[str, Any]) -> None:
    """Write a repair-loop JSON artifact with stable formatting."""
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2, sort_keys=True), encoding="utf-8")
