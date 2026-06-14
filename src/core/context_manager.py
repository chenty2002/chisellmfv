"""
Context compaction helpers for long-running waveform analysis loops.

Waveform debugging can involve dozens of tool calls. Keeping every raw
assistant/tool message in the chat history is expensive and tends to drown the
next reasoning step in stale JSON. The EvidenceNotebook below keeps a compact,
deterministic ledger of observations while the workflow retains only the most
recent verbatim tool turns.
"""

from __future__ import annotations

import json
from typing import Any, Dict, List


NOTEBOOK_MARKER = "## Waveform Evidence Notebook"


class StageNotebook:
    """Compact deterministic summary of stage evidence."""

    def __init__(self, stage: str = "waveform_explanation", max_entries: int = 80):
        self.stage = stage
        self.max_entries = max_entries
        self.entries: List[str] = []
        self._seen: set[str] = set()

    def record_iteration(self, iteration: Dict[str, Any]) -> None:
        """Record a workflow iteration as concise evidence lines."""
        calls = iteration.get("function_calls", []) or []
        results = iteration.get("action_results", []) or []
        iteration_no = iteration.get("iteration", "?")

        for call, result in zip(calls, results):
            name = call.get("name") or result.get("type") or "<unknown>"
            args = call.get("arguments", {}) or {}
            summary = self._summarize_result(name, args, result)
            if summary:
                self._add(f"iter {iteration_no}: {summary}")

        if iteration.get("compilation_error"):
            self._add(f"iter {iteration_no}: compilation failed; latest error is in the recent raw history.")
        if iteration.get("error"):
            self._add(f"iter {iteration_no}: error: {self._truncate(str(iteration['error']), 240)}")

    def to_message(self) -> Dict[str, str]:
        """Return a chat message that can replace older raw waveform turns."""
        if self.entries:
            body = "\n".join(f"- {entry}" for entry in self.entries[-self.max_entries:])
        else:
            body = f"- No compacted {self.stage.replace('_', ' ')} evidence yet."

        marker = _notebook_marker(self.stage)
        content = (
            f"{marker}\n\n"
            "Older stage/tool turns have been compacted into this notebook. "
            "Treat these entries as established observations, and use the recent "
            "raw tool messages below for exact JSON details.\n\n"
            f"{body}"
        )
        return {"role": "user", "content": content}

    def _add(self, entry: str) -> None:
        normalized = " ".join(entry.split())
        if normalized in self._seen:
            return
        self._seen.add(normalized)
        self.entries.append(entry)
        if len(self.entries) > self.max_entries * 2:
            self.entries = self.entries[-self.max_entries:]
            self._seen = {" ".join(e.split()) for e in self.entries}

    def _summarize_result(self, name: str, args: Dict[str, Any], result: Dict[str, Any]) -> str:
        success = result.get("success")
        status = "ok" if success else "failed" if success is False else "done"

        if name == "waveform_find_signals":
            pattern = args.get("pattern", "")
            signals = [s.get("name", "") for s in result.get("signals", [])[:8]]
            return (
                f"waveform_find_signals({pattern!r}) {status}, "
                f"count={result.get('count')}, first={signals}"
            )

        if name == "waveform_get_signal_value":
            values = []
            for item in result.get("results", [])[:12]:
                sig = item.get("resolved_name") or item.get("signal_name")
                values.append(f"{sig}@{item.get('time')}={item.get('value')}")
            failed = result.get("failed_count", 0)
            suffix = f", failed={failed}" if failed else ""
            return f"waveform_get_signal_value {status}: {', '.join(values)}{suffix}"

        if name == "waveform_trace_signal":
            sig = result.get("resolved_name") or args.get("signal_name")
            changes = result.get("changes", [])[:8]
            return (
                f"waveform_trace_signal({sig}) {status}, "
                f"total_changes={result.get('total_changes')}, first_changes={changes}"
            )

        if name == "waveform_get_active_signals":
            active = [
                f"{s.get('name')}={s.get('value')}"
                for s in result.get("active_signals", [])[:12]
            ]
            return (
                f"waveform_get_active_signals(time={args.get('time')}) {status}, "
                f"count={result.get('count')}, first={active}"
            )

        if name == "waveform_compare_signals":
            comparison = self._truncate_json(result.get("comparison", {}), 800)
            return f"waveform_compare_signals {status}: {comparison}"

        if name == "waveform_find_transitions":
            sig = result.get("resolved_name") or args.get("signal_name")
            transitions = result.get("transitions", [])[:12]
            return (
                f"waveform_find_transitions({sig}, {args.get('from_value')}->{args.get('to_value')}) "
                f"{status}, count={result.get('count')}, first={transitions}"
            )

        if name == "causal_get_roots":
            roots = [
                f"{r.get('signal')}@{r.get('cycle')} score={r.get('suspect_score')}"
                for r in result.get("roots", [])[:8]
            ]
            return f"causal_get_roots {status}, returned={len(result.get('roots', []))}: {roots}"

        if name == "causal_trace_path":
            path_bits = []
            for path in result.get("paths", [])[:3]:
                nodes = path.get("nodes", [])
                path_bits.append(" -> ".join(f"{n.get('signal')}@{n.get('cycle')}" for n in nodes))
            return f"causal_trace_path {status}, paths={len(result.get('paths', []))}: {path_bits}"

        if name == "causal_get_node_evidence":
            node = result.get("node") or {}
            return (
                f"causal_get_node_evidence {status}: "
                f"{node.get('signal')}@{node.get('cycle')}, "
                f"in={len(result.get('incoming_edges', []))}, out={len(result.get('outgoing_edges', []))}"
            )

        if name == "read_files":
            files = [
                f"{f.get('file_path')}:{'ok' if f.get('success') else 'error'}"
                for f in result.get("files", [])
            ]
            return f"read_files {status}: {files}"

        if name == "write_report":
            return f"write_report {status}: {result.get('file_path') or result.get('error')}"

        if result.get("error"):
            return f"{name} {status}: {self._truncate(str(result.get('error')), 240)}"

        return f"{name} {status}: {self._truncate_json(result, 500)}"

    @staticmethod
    def _truncate(value: str, max_chars: int) -> str:
        if len(value) <= max_chars:
            return value
        return value[: max_chars - 3] + "..."

    def _truncate_json(self, value: Any, max_chars: int) -> str:
        try:
            text = json.dumps(value, ensure_ascii=False, sort_keys=True)
        except TypeError:
            text = str(value)
        return self._truncate(text, max_chars)


class EvidenceNotebook(StageNotebook):
    """Backward-compatible waveform notebook name."""

    def __init__(self, max_entries: int = 80):
        super().__init__(stage="waveform_explanation", max_entries=max_entries)


def compact_messages_with_notebook(
    messages: List[Dict[str, Any]],
    notebook: StageNotebook,
    keep_recent_messages: int = 12,
    compact_after_messages: int = 18,
) -> bool:
    """
    Replace older raw chat history with a notebook message.

    Returns True when compaction changed the message list.
    """
    if len(messages) <= compact_after_messages:
        return False

    protected = messages[:2]
    marker = _notebook_marker(notebook.stage)
    rest = [
        msg for msg in messages[2:]
        if not (
            msg.get("role") == "user"
            and isinstance(msg.get("content"), str)
            and msg["content"].startswith(marker)
        )
    ]

    if len(protected) < 2:
        return False

    tail_start = max(0, len(rest) - keep_recent_messages)
    while tail_start > 0 and rest[tail_start].get("role") == "tool":
        tail_start -= 1

    tail = rest[tail_start:]
    compacted = protected + [notebook.to_message()] + tail

    if len(compacted) >= len(messages):
        return False

    messages[:] = compacted
    return True


def _notebook_marker(stage: str) -> str:
    if stage == "waveform_explanation":
        return NOTEBOOK_MARKER
    return f"## {stage.replace('_', ' ').title()} Notebook"
