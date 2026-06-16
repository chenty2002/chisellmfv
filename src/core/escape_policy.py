from __future__ import annotations

import json
from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional, Set


@dataclass(frozen=True)
class EscapePolicyConfig:
    """Data knobs for agent-loop escape rules."""

    repeat_limit: int = 3
    max_empty_searches: int = 3
    compact_after_messages: int = 18


@dataclass(frozen=True)
class EscapeAction:
    """A policy decision for the workflow to apply."""

    action_type: str
    message: str = ""
    metadata: Dict[str, Any] = field(default_factory=dict)


class EscapePolicy:
    """Detect no-progress tool loops and return structured escape actions."""

    def __init__(self, config: Optional[EscapePolicyConfig] = None):
        self.config = config or EscapePolicyConfig()
        self._counts: Dict[str, int] = {}
        self._notified: Set[str] = set()

    def observe(
        self,
        *,
        stage: str,
        function_calls: List[Dict[str, Any]],
        action_results: List[Dict[str, Any]],
        messages: Optional[List[Dict[str, Any]]] = None,
    ) -> List[EscapeAction]:
        actions: List[EscapeAction] = []

        for function_call, result in zip(function_calls, action_results):
            action = self._observe_repeated_waveform_value(stage, function_call, result)
            if action is not None:
                actions.append(action)

            action = self._observe_empty_rg(function_call, result)
            if action is not None:
                actions.append(action)

        if messages is not None and len(messages) >= self.config.compact_after_messages:
            actions.append(
                EscapeAction(
                    action_type="compact",
                    metadata={"rule": "message_budget", "message_count": len(messages)},
                )
            )

        return actions

    def _observe_repeated_waveform_value(
        self,
        stage: str,
        function_call: Dict[str, Any],
        result: Dict[str, Any],
    ) -> Optional[EscapeAction]:
        if stage != "waveform_explanation" or function_call.get("name") != "waveform_get_signal_value":
            return None

        args = function_call.get("arguments", {}) or {}
        if "signal_names" not in args and "signal_name" in args:
            normalized_args = {
                "signal_names": [args.get("signal_name")],
                "times": [args.get("time", 0)],
            }
        else:
            normalized_args = {
                "signal_names": args.get("signal_names", []),
                "times": args.get("times", []),
            }

        progress_signature = {
            "value": result.get("value"),
            "resolved_name": result.get("resolved_name"),
            "error": result.get("error"),
            "failed_count": result.get("failed_count", 0),
        }
        repeat_key = self._count_key(
            {
                "rule": "repeated_waveform_value",
                "tool": function_call.get("name"),
                "args": normalized_args,
                "progress": progress_signature,
            }
        )
        if not self._mark_and_should_fire(repeat_key, self.config.repeat_limit):
            return None

        suggestions = result.get("suggestions") or []
        guidance = (
            "You are repeating the same `waveform_get_signal_value` query with the same outcome. "
            "Stop repeating it. First call `waveform_find_signals` with a focused pattern, then use the exact "
            "returned signal name, including bit-range suffixes when present. If the exact signal cannot be "
            "resolved after one retry, move on to other relevant signals and continue the analysis."
        )
        if suggestions:
            guidance += "\n\nCandidates: " + ", ".join(str(item) for item in suggestions[:5])

        return EscapeAction(
            action_type="nudge",
            message=guidance,
            metadata={"rule": "repeated_waveform_value", "key": repeat_key},
        )

    def _observe_empty_rg(
        self,
        function_call: Dict[str, Any],
        result: Dict[str, Any],
    ) -> Optional[EscapeAction]:
        if function_call.get("name") != "rg":
            return None
        if not result.get("success") or result.get("matches") != []:
            return None

        args = function_call.get("arguments", {}) or {}
        pattern = str(args.get("pattern", ""))
        repeat_key = self._count_key(
            {
                "rule": "empty_rg",
                "pattern": pattern,
                "path": args.get("path", "."),
                "glob": args.get("glob"),
            }
        )
        if not self._mark_and_should_fire(repeat_key, self.config.max_empty_searches):
            return None

        return EscapeAction(
            action_type="nudge",
            message=(
                f"The repeated empty `rg` search for `{pattern}` is not making progress. "
                "Change strategy: broaden or simplify the pattern, list candidate files, read likely source "
                "regions, or explain why this search path should be abandoned."
            ),
            metadata={"rule": "empty_rg", "key": repeat_key},
        )

    def _mark_and_should_fire(self, key: str, limit: int) -> bool:
        count = self._counts.get(key, 0) + 1
        self._counts[key] = count
        if count < max(1, limit) or key in self._notified:
            return False
        self._notified.add(key)
        return True

    @staticmethod
    def _count_key(parts: Dict[str, Any]) -> str:
        return json.dumps(parts, ensure_ascii=False, sort_keys=True)
