"""Flash-model semantic compaction for long stage histories."""

from __future__ import annotations

import hashlib
import json
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

from .llm_client import count_tokens


CONTEXT_DIGEST_SCHEMA_VERSION = "context_digest.v1"
COMPACTION_PROMPT_VERSION = "context-compaction-v1"
CONTEXT_DIGEST_MARKER = "## Validated Context Digest\n"

_DIGEST_KEYS = (
    "schema_version",
    "stage",
    "objective",
    "established_facts",
    "files_inspected",
    "changes",
    "tool_outcomes",
    "errors",
    "rejected_paths",
    "pending_questions",
    "completion_readiness",
    "budget",
    "next_actions",
)

SUBMIT_CONTEXT_DIGEST_TOOL = {
    "name": "submit_context_digest",
    "description": "Submit the evidence-preserving digest for the supplied history.",
    "parameters": {
        "type": "object",
        "properties": {
            "schema_version": {"type": "string", "enum": [CONTEXT_DIGEST_SCHEMA_VERSION]},
            "stage": {"type": "string"},
            "objective": {"type": "string"},
            "established_facts": {"type": "array", "items": {"type": "string"}},
            "files_inspected": {
                "type": "array",
                "items": {
                    "type": "object",
                    "properties": {
                        "path": {"type": "string"},
                        "ranges": {"type": "array", "items": {"type": "string"}},
                        "finding": {"type": "string"},
                    },
                    "required": ["path", "ranges", "finding"],
                    "additionalProperties": False,
                },
            },
            "changes": {"type": "array", "items": {}},
            "tool_outcomes": {"type": "array", "items": {}},
            "errors": {"type": "array", "items": {}},
            "rejected_paths": {"type": "array", "items": {}},
            "pending_questions": {"type": "array", "items": {}},
            "completion_readiness": {
                "type": "object",
                "properties": {
                    "ready": {"type": "boolean"},
                    "missing": {"type": "array", "items": {"type": "string"}},
                },
                "required": ["ready", "missing"],
                "additionalProperties": False,
            },
            "budget": {
                "type": "object",
                "properties": {
                    "tool_calls_used": {"type": "integer"},
                    "tool_calls_remaining": {"type": "integer"},
                    "tokens_remaining": {"type": ["integer", "null"]},
                },
                "required": [
                    "tool_calls_used",
                    "tool_calls_remaining",
                    "tokens_remaining",
                ],
                "additionalProperties": False,
            },
            "next_actions": {"type": "array", "items": {"type": "string"}},
        },
        "required": list(_DIGEST_KEYS),
        "additionalProperties": False,
    },
}

COMPACTION_SYSTEM_PROMPT = """You compact an existing workflow history.
This is a no-tool, no-code-editing, no-problem-solving task except for the
single submit_context_digest response. Preserve only facts present in the
input. Never infer missing facts. Preserve the stage objective and completion
gate, exact files and line ranges, edits, errors, successful and failed tool
outcomes, rejected investigation paths, pending questions, completion
readiness, and budget state. Keep conflicting evidence and label the conflict.
Never claim completion unless the input contains a gate-accepted completion.
Return exactly one submit_context_digest call using context_digest.v1."""


@dataclass(frozen=True)
class ContextDigest:
    payload: Dict[str, Any]

    @classmethod
    def from_payload(cls, payload: Dict[str, Any], stage: str) -> "ContextDigest":
        if not isinstance(payload, dict):
            raise ValueError("context digest must be an object")
        missing = [key for key in _DIGEST_KEYS if key not in payload]
        extra = [key for key in payload if key not in _DIGEST_KEYS]
        if missing or extra:
            raise ValueError(
                "context digest keys are invalid: "
                f"missing={missing}, extra={extra}"
            )
        if payload["schema_version"] != CONTEXT_DIGEST_SCHEMA_VERSION:
            raise ValueError("unsupported context digest schema")
        if payload["stage"] != stage:
            raise ValueError("context digest stage does not match request")
        if not isinstance(payload["objective"], str):
            raise ValueError("context digest objective must be a string")
        for key in (
            "established_facts",
            "files_inspected",
            "changes",
            "tool_outcomes",
            "errors",
            "rejected_paths",
            "pending_questions",
            "next_actions",
        ):
            if not isinstance(payload[key], list):
                raise ValueError(f"context digest {key} must be a list")
        for key in (
            "established_facts",
            "pending_questions",
            "next_actions",
        ):
            if not all(isinstance(item, str) for item in payload[key]):
                raise ValueError(f"context digest {key} must contain strings")
        for item in payload["files_inspected"]:
            if (
                not isinstance(item, dict)
                or set(item) != {"path", "ranges", "finding"}
                or not isinstance(item["path"], str)
                or not isinstance(item["finding"], str)
                or not isinstance(item["ranges"], list)
                or not all(isinstance(value, str) for value in item["ranges"])
            ):
                raise ValueError("context digest files_inspected is invalid")
        readiness = payload["completion_readiness"]
        if (
            not isinstance(readiness, dict)
            or set(readiness) != {"ready", "missing"}
            or not isinstance(readiness.get("ready"), bool)
            or not isinstance(readiness.get("missing"), list)
            or not all(isinstance(item, str) for item in readiness["missing"])
        ):
            raise ValueError("context digest completion_readiness is invalid")
        budget = payload["budget"]
        if not isinstance(budget, dict) or set(budget) != {
            "tool_calls_used",
            "tool_calls_remaining",
            "tokens_remaining",
        }:
            raise ValueError("context digest budget is invalid")
        if (
            not isinstance(budget["tool_calls_used"], int)
            or not isinstance(budget["tool_calls_remaining"], int)
            or (
                budget["tokens_remaining"] is not None
                and not isinstance(budget["tokens_remaining"], int)
            )
        ):
            raise ValueError("context digest budget values are invalid")
        return cls(payload=dict(payload))

    def to_message(self) -> Dict[str, str]:
        return {
            "role": "user",
            "content": CONTEXT_DIGEST_MARKER
            + json.dumps(self.payload, ensure_ascii=False, sort_keys=True),
        }


@dataclass(frozen=True)
class CompactionRequest:
    stage: str
    stage_contract: Dict[str, Any]
    budget_snapshot: Dict[str, Any]
    messages: List[Dict[str, Any]]
    previous_digest: Optional[Dict[str, Any]]


@dataclass(frozen=True)
class CompactionResult:
    compacted: bool
    tokens_before: int
    tokens_after: int
    error: Optional[str] = None


class FlashContextCompactor:
    """Replace old raw turns only after a valid Flash digest is returned."""

    def __init__(
        self,
        llm_router: Any,
        audit_path: Path,
        *,
        trigger_tokens: int = 12000,
        keep_recent_exchanges: int = 3,
        output_tokens: int = 2000,
    ):
        if trigger_tokens <= 0:
            raise ValueError("trigger_tokens must be positive")
        if keep_recent_exchanges <= 0:
            raise ValueError("keep_recent_exchanges must be positive")
        self.llm_router = llm_router
        self.audit_path = Path(audit_path)
        self.trigger_tokens = trigger_tokens
        self.keep_recent_exchanges = keep_recent_exchanges
        self.output_tokens = output_tokens
        self._digests: Dict[str, ContextDigest] = {}

    def compact(
        self,
        messages: List[Dict[str, Any]],
        *,
        stage: str,
        stage_contract: Dict[str, Any],
        budget_snapshot: Dict[str, Any],
        force: bool = False,
    ) -> CompactionResult:
        tokens_before = _message_tokens(messages)
        protected, old_messages, tail = self._partition(messages)
        if (
            not old_messages
            or (
                not force
                and _message_tokens(old_messages) < self.trigger_tokens
            )
        ):
            return CompactionResult(False, tokens_before, tokens_before)

        errors: List[str] = []
        windows = [old_messages]
        if len(old_messages) > 1:
            windows.append(old_messages[len(old_messages) // 2 :])
        else:
            windows.append(old_messages)

        for attempt, window in enumerate(windows, 1):
            prefix = old_messages[: len(old_messages) - len(window)]
            usage_before = _usage_total(self.llm_router)
            try:
                digest = self._request_digest(
                    CompactionRequest(
                        stage=stage,
                        stage_contract=stage_contract,
                        budget_snapshot=budget_snapshot,
                        messages=window,
                        previous_digest=(
                            self._digests[stage].payload
                            if stage in self._digests
                            else None
                        ),
                    )
                )
                replacement = protected + prefix + [digest.to_message()] + tail
                tokens_after = _message_tokens(replacement)
                if tokens_after >= tokens_before:
                    raise ValueError(
                        "validated context digest did not reduce estimated tokens"
                    )
                messages[:] = replacement
                self._digests[stage] = digest
                self._append_audit(
                    success=True,
                    attempt=attempt,
                    stage=stage,
                    window=window,
                    tokens_before=tokens_before,
                    tokens_after=tokens_after,
                    digest=digest.payload,
                    usage={"total_tokens": _usage_total(self.llm_router) - usage_before},
                )
                return CompactionResult(True, tokens_before, tokens_after)
            except Exception as exc:
                error = f"{type(exc).__name__}: {exc}"
                errors.append(error)
                self._append_audit(
                    success=False,
                    attempt=attempt,
                    stage=stage,
                    window=window,
                    tokens_before=tokens_before,
                    tokens_after=tokens_before,
                    error=error,
                    usage={"total_tokens": _usage_total(self.llm_router) - usage_before},
                )

        return CompactionResult(
            False,
            tokens_before,
            tokens_before,
            error="; ".join(errors),
        )

    def _partition(
        self, messages: List[Dict[str, Any]]
    ) -> Tuple[List[Dict[str, Any]], List[Dict[str, Any]], List[Dict[str, Any]]]:
        protected = list(messages[:2])
        rest = [
            message
            for message in messages[2:]
            if not (
                message.get("role") == "user"
                and isinstance(message.get("content"), str)
                and message["content"].startswith(CONTEXT_DIGEST_MARKER)
            )
        ]
        assistant_seen = 0
        tail_start = len(rest)
        for index in range(len(rest) - 1, -1, -1):
            if rest[index].get("role") == "assistant":
                assistant_seen += 1
                if assistant_seen == self.keep_recent_exchanges:
                    tail_start = index
                    break
        if assistant_seen < self.keep_recent_exchanges:
            return protected, [], rest
        return protected, rest[:tail_start], rest[tail_start:]

    def _request_digest(self, request: CompactionRequest) -> ContextDigest:
        payload = {
            "prompt_version": COMPACTION_PROMPT_VERSION,
            "stage": request.stage,
            "stage_contract": request.stage_contract,
            "budget_snapshot": request.budget_snapshot,
            "previous_digest": request.previous_digest,
            "messages": request.messages,
        }
        response = self.llm_router.chat_with_tools(
            [
                {"role": "system", "content": COMPACTION_SYSTEM_PROMPT},
                {
                    "role": "user",
                    "content": json.dumps(payload, ensure_ascii=False, sort_keys=True),
                },
            ],
            [SUBMIT_CONTEXT_DIGEST_TOOL],
            role="flash",
            stage=request.stage,
            task_type="context_compaction",
            max_tokens=self.output_tokens,
            temperature=0,
            tool_choice={
                "type": "function",
                "function": {"name": "submit_context_digest"},
            },
            usage_metadata={
                "stage": request.stage,
                "prompt_version": COMPACTION_PROMPT_VERSION,
            },
        )
        calls = response.get("function_calls") if response.get("type") == "function_calls" else None
        if not calls or len(calls) != 1 or calls[0].get("name") != "submit_context_digest":
            raise ValueError("Flash must return exactly one submit_context_digest call")
        return ContextDigest.from_payload(calls[0].get("arguments"), request.stage)

    def _append_audit(
        self,
        *,
        success: bool,
        attempt: int,
        stage: str,
        window: List[Dict[str, Any]],
        tokens_before: int,
        tokens_after: int,
        digest: Optional[Dict[str, Any]] = None,
        error: Optional[str] = None,
        usage: Optional[Dict[str, int]] = None,
    ) -> None:
        client = getattr(self.llm_router, "flash_client", None)
        record = {
            "schema_version": "context_compaction.v1",
            "prompt_version": COMPACTION_PROMPT_VERSION,
            "success": success,
            "attempt": attempt,
            "stage": stage,
            "model_role": "flash",
            "model": getattr(client, "model", None),
            "timestamp": datetime.now(timezone.utc).isoformat(),
            "input_messages_sha256": _messages_hash(window),
            "candidate_turn_ids": [_message_id(message) for message in window],
            "removed_turn_ids": (
                [_message_id(message) for message in window] if success else []
            ),
            "tokens_before": tokens_before,
            "tokens_after": tokens_after,
            "usage": dict(usage or {}),
            "digest": digest,
            "error": error,
        }
        self.audit_path.parent.mkdir(parents=True, exist_ok=True)
        with self.audit_path.open("a", encoding="utf-8") as handle:
            handle.write(json.dumps(record, ensure_ascii=False, sort_keys=True) + "\n")


def _message_tokens(messages: List[Dict[str, Any]]) -> int:
    return count_tokens(json.dumps(messages, ensure_ascii=False, sort_keys=True))


def _messages_hash(messages: List[Dict[str, Any]]) -> str:
    encoded = json.dumps(messages, ensure_ascii=False, sort_keys=True).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def _message_id(message: Dict[str, Any]) -> str:
    for key in ("id", "tool_call_id"):
        if message.get(key):
            return str(message[key])
    return _messages_hash([message])[:16]


def _usage_total(llm_router: Any) -> int:
    if not hasattr(llm_router, "get_token_usage"):
        return 0
    usage = llm_router.get_token_usage() or {}
    return int(usage.get("llm_total_tokens", 0) or 0)
