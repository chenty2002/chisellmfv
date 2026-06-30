"""Flash-model semantic compaction for long stage histories."""

from __future__ import annotations

import hashlib
import json
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict, List, Optional

from .llm_client import count_tokens, estimate_chat_request_tokens


CONTEXT_DIGEST_SCHEMA_VERSION = "context_digest.v2"
COMPACTION_PROMPT_VERSION = "context-compaction-v2"
CONTEXT_DIGEST_MARKER = "## Validated Context Digest\n"
DEFAULT_COMPACTION_TARGET_TOKENS = 1200
DEFAULT_DIGEST_TOKEN_LIMIT = 1600
DEFAULT_RETRY_CANDIDATE_TOKENS = 8000

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
            "objective": {"type": "string", "maxLength": 240},
            "established_facts": {
                "type": "array",
                "maxItems": 12,
                "items": {"type": "string", "maxLength": 200},
            },
            "files_inspected": {
                "type": "array",
                "maxItems": 10,
                "items": {
                    "type": "object",
                    "properties": {
                        "path": {"type": "string", "maxLength": 400},
                        "ranges": {
                            "type": "array",
                            "maxItems": 4,
                            "items": {"type": "string", "maxLength": 40},
                        },
                        "finding": {"type": "string", "maxLength": 200},
                    },
                    "required": ["path", "ranges", "finding"],
                    "additionalProperties": False,
                },
            },
            "changes": {
                "type": "array",
                "maxItems": 8,
                "items": {
                    "type": "object",
                    "properties": {
                        "path": {"type": "string", "maxLength": 400},
                        "summary": {"type": "string", "maxLength": 240},
                        "status": {
                            "type": "string",
                            "enum": ["planned", "applied", "verified", "failed"],
                        },
                    },
                    "required": ["path", "summary", "status"],
                    "additionalProperties": False,
                },
            },
            "tool_outcomes": {
                "type": "array",
                "maxItems": 10,
                "items": {
                    "type": "object",
                    "properties": {
                        "tool": {"type": "string", "maxLength": 120},
                        "success": {"type": "boolean"},
                        "summary": {"type": "string", "maxLength": 200},
                    },
                    "required": ["tool", "success", "summary"],
                    "additionalProperties": False,
                },
            },
            "errors": {
                "type": "array",
                "maxItems": 6,
                "items": {
                    "type": "object",
                    "properties": {
                        "source": {"type": "string", "maxLength": 120},
                        "summary": {"type": "string", "maxLength": 240},
                        "blocking": {"type": "boolean"},
                    },
                    "required": ["source", "summary", "blocking"],
                    "additionalProperties": False,
                },
            },
            "rejected_paths": {
                "type": "array",
                "maxItems": 6,
                "items": {"type": "string", "maxLength": 200},
            },
            "pending_questions": {
                "type": "array",
                "maxItems": 6,
                "items": {"type": "string", "maxLength": 200},
            },
            "completion_readiness": {
                "type": "object",
                "properties": {
                    "ready": {"type": "boolean"},
                    "missing": {
                        "type": "array",
                        "maxItems": 6,
                        "items": {"type": "string", "maxLength": 160},
                    },
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
            "next_actions": {
                "type": "array",
                "maxItems": 5,
                "items": {"type": "string", "maxLength": 180},
            },
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
Return exactly one submit_context_digest call using context_digest.v2.

The digest must target at most {target_tokens} estimated tokens. Keep at most:
12 facts, 10 inspected files with 4 ranges each, 8 changes, 10 tool outcomes,
6 errors, 6 rejected paths, 6 pending questions, 6 readiness gaps, and 5 next
actions. Do not copy source code, raw tool output, full compiler logs, or
duplicate facts. Detailed evidence remains in the workflow operation log."""


class ContextCompactionError(ValueError):
    """A classified compaction response failure with provider metadata."""

    def __init__(
        self,
        error_kind: str,
        *,
        detail: Optional[str] = None,
        finish_reason: Optional[str] = None,
    ):
        message = f"{error_kind}: {detail}" if detail else error_kind
        super().__init__(message)
        self.error_kind = error_kind
        self.finish_reason = finish_reason


def _schema_failure(detail: str) -> None:
    raise ContextCompactionError(
        "context_digest_schema_invalid",
        detail=detail,
    )


def _validate_string(value: Any, field: str, max_length: int) -> None:
    if not isinstance(value, str) or len(value) > max_length:
        _schema_failure(f"{field} must be a string of at most {max_length} characters")


def _validate_string_list(
    value: Any,
    field: str,
    *,
    max_items: int,
    max_length: int,
) -> None:
    if not isinstance(value, list) or len(value) > max_items:
        _schema_failure(f"{field} must contain at most {max_items} items")
    for index, item in enumerate(value):
        _validate_string(item, f"{field}[{index}]", max_length)


@dataclass(frozen=True)
class ContextDigest:
    payload: Dict[str, Any]

    @classmethod
    def from_payload(
        cls,
        payload: Dict[str, Any],
        stage: str,
        *,
        digest_token_limit: int = DEFAULT_DIGEST_TOKEN_LIMIT,
    ) -> "ContextDigest":
        if not isinstance(payload, dict):
            _schema_failure("context digest must be an object")
        missing = [key for key in _DIGEST_KEYS if key not in payload]
        extra = [key for key in payload if key not in _DIGEST_KEYS]
        if missing or extra:
            _schema_failure(
                f"context digest keys are invalid: missing={missing}, extra={extra}"
            )
        if payload["schema_version"] != CONTEXT_DIGEST_SCHEMA_VERSION:
            _schema_failure("unsupported context digest schema")
        if payload["stage"] != stage:
            _schema_failure("context digest stage does not match request")

        digest_tokens = count_tokens(
            json.dumps(payload, ensure_ascii=False, sort_keys=True)
        )
        if digest_tokens > digest_token_limit:
            raise ContextCompactionError(
                "context_digest_oversized",
                detail=f"{digest_tokens} tokens exceeds limit {digest_token_limit}",
            )

        _validate_string(payload["objective"], "objective", 240)
        for key, max_items, max_length in (
            ("established_facts", 12, 200),
            ("rejected_paths", 6, 200),
            ("pending_questions", 6, 200),
            ("next_actions", 5, 180),
        ):
            _validate_string_list(
                payload[key],
                key,
                max_items=max_items,
                max_length=max_length,
            )

        list_limits = {
            "files_inspected": 10,
            "changes": 8,
            "tool_outcomes": 10,
            "errors": 6,
        }
        for key, max_items in list_limits.items():
            if not isinstance(payload[key], list) or len(payload[key]) > max_items:
                _schema_failure(f"{key} must contain at most {max_items} items")

        for index, item in enumerate(payload["files_inspected"]):
            if not isinstance(item, dict) or set(item) != {
                "path",
                "ranges",
                "finding",
            }:
                _schema_failure(f"files_inspected[{index}] has invalid fields")
            _validate_string(item["path"], f"files_inspected[{index}].path", 400)
            _validate_string_list(
                item["ranges"],
                f"files_inspected[{index}].ranges",
                max_items=4,
                max_length=40,
            )
            _validate_string(
                item["finding"],
                f"files_inspected[{index}].finding",
                200,
            )

        for index, item in enumerate(payload["changes"]):
            if not isinstance(item, dict) or set(item) != {
                "path",
                "summary",
                "status",
            }:
                _schema_failure(f"changes[{index}] has invalid fields")
            _validate_string(item["path"], f"changes[{index}].path", 400)
            _validate_string(item["summary"], f"changes[{index}].summary", 240)
            if item["status"] not in {"planned", "applied", "verified", "failed"}:
                _schema_failure(f"changes[{index}].status is invalid")

        for index, item in enumerate(payload["tool_outcomes"]):
            if not isinstance(item, dict) or set(item) != {
                "tool",
                "success",
                "summary",
            }:
                _schema_failure(f"tool_outcomes[{index}] has invalid fields")
            _validate_string(item["tool"], f"tool_outcomes[{index}].tool", 120)
            if not isinstance(item["success"], bool):
                _schema_failure(f"tool_outcomes[{index}].success must be boolean")
            _validate_string(
                item["summary"],
                f"tool_outcomes[{index}].summary",
                200,
            )

        for index, item in enumerate(payload["errors"]):
            if not isinstance(item, dict) or set(item) != {
                "source",
                "summary",
                "blocking",
            }:
                _schema_failure(f"errors[{index}] has invalid fields")
            _validate_string(item["source"], f"errors[{index}].source", 120)
            _validate_string(item["summary"], f"errors[{index}].summary", 240)
            if not isinstance(item["blocking"], bool):
                _schema_failure(f"errors[{index}].blocking must be boolean")

        readiness = payload["completion_readiness"]
        if not isinstance(readiness, dict) or set(readiness) != {
            "ready",
            "missing",
        }:
            _schema_failure("completion_readiness has invalid fields")
        if not isinstance(readiness["ready"], bool):
            _schema_failure("completion_readiness.ready must be boolean")
        _validate_string_list(
            readiness["missing"],
            "completion_readiness.missing",
            max_items=6,
            max_length=160,
        )

        budget = payload["budget"]
        if not isinstance(budget, dict) or set(budget) != {
            "tool_calls_used",
            "tool_calls_remaining",
            "tokens_remaining",
        }:
            _schema_failure("budget has invalid fields")
        if (
            type(budget["tool_calls_used"]) is not int
            or type(budget["tool_calls_remaining"]) is not int
            or (
                budget["tokens_remaining"] is not None
                and type(budget["tokens_remaining"]) is not int
            )
        ):
            _schema_failure("budget values are invalid")
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


@dataclass(frozen=True)
class _CompactionWindow:
    protected: List[Dict[str, Any]]
    candidate: List[Dict[str, Any]]
    preserved_middle: List[Dict[str, Any]]
    recent_tail: List[Dict[str, Any]]


class FlashContextCompactor:
    """Replace old raw turns only after a valid Flash digest is returned."""

    def __init__(
        self,
        llm_router: Any,
        audit_path: Path,
        *,
        trigger_tokens: int = 12000,
        keep_recent_exchanges: int = 3,
        output_tokens: int = 4096,
        target_tokens: int = DEFAULT_COMPACTION_TARGET_TOKENS,
        digest_token_limit: int = DEFAULT_DIGEST_TOKEN_LIMIT,
        retry_candidate_tokens: int = DEFAULT_RETRY_CANDIDATE_TOKENS,
        min_candidate_tokens: int = 3000,
        min_reduction_tokens: int = 2000,
    ):
        if trigger_tokens <= 0:
            raise ValueError("trigger_tokens must be positive")
        if keep_recent_exchanges <= 0:
            raise ValueError("keep_recent_exchanges must be positive")
        if output_tokens <= 0:
            raise ValueError("output_tokens must be positive")
        if target_tokens <= 0:
            raise ValueError("target_tokens must be positive")
        if digest_token_limit < target_tokens:
            raise ValueError("digest_token_limit must cover target_tokens")
        if retry_candidate_tokens <= 0:
            raise ValueError("retry_candidate_tokens must be positive")
        if min_candidate_tokens <= 0:
            raise ValueError("min_candidate_tokens must be positive")
        if min_reduction_tokens <= 0:
            raise ValueError("min_reduction_tokens must be positive")
        self.llm_router = llm_router
        self.audit_path = Path(audit_path)
        self.trigger_tokens = trigger_tokens
        self.keep_recent_exchanges = keep_recent_exchanges
        self.output_tokens = output_tokens
        self.target_tokens = target_tokens
        self.digest_token_limit = digest_token_limit
        self.retry_candidate_tokens = retry_candidate_tokens
        self.min_candidate_tokens = min_candidate_tokens
        self.min_reduction_tokens = min_reduction_tokens
        self._digests: Dict[str, ContextDigest] = {}

    def record_budget_rejection(
        self,
        *,
        stage: str,
        messages: List[Dict[str, Any]],
        estimated_tokens: int,
        remaining_tokens: Optional[int],
    ) -> None:
        """Audit a budget rejection that occurs before any Flash HTTP request."""
        tokens_before = _message_tokens(messages)
        self._append_audit(
            success=False,
            attempt=1,
            stage=stage,
            window=messages,
            tokens_before=tokens_before,
            tokens_after=tokens_before,
            error="Flash compaction request rejected by token budget preflight",
            error_kind="context_compaction_budget_rejected",
            request_sent=False,
            estimated_tokens=estimated_tokens,
            remaining_tokens=remaining_tokens,
            usage={"total_tokens": 0},
        )

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
        initial_window = self._partition(messages)
        candidate_tokens = _message_tokens(initial_window.candidate)
        if (
            not initial_window.candidate
            or candidate_tokens < self.min_candidate_tokens
            or (not force and candidate_tokens < self.trigger_tokens)
        ):
            return CompactionResult(False, tokens_before, tokens_before)

        retry_window = self._retry_window(initial_window)
        errors: List[str] = []
        window = initial_window
        attempt = 1
        while True:
            usage_before = _usage_total(self.llm_router)
            request: Optional[CompactionRequest] = None
            request_sent = False
            try:
                request = CompactionRequest(
                    stage=stage,
                    stage_contract=stage_contract,
                    budget_snapshot=budget_snapshot,
                    messages=window.candidate,
                    previous_digest=(
                        self._digests[stage].payload
                        if stage in self._digests
                        else None
                    ),
                )
                self._preflight_request(request)
                request_sent = True
                digest = self._request_digest(request)
                replacement = (
                    window.protected
                    + [digest.to_message()]
                    + window.preserved_middle
                    + window.recent_tail
                )
                tokens_after = _message_tokens(replacement)
                if tokens_before - tokens_after < self.min_reduction_tokens:
                    raise ContextCompactionError(
                        "context_digest_nonreducing",
                        detail=(
                            "validated digest did not reduce estimated tokens "
                            f"by at least {self.min_reduction_tokens}"
                        ),
                    )
                messages[:] = replacement
                self._digests[stage] = digest
                self._append_audit(
                    success=True,
                    attempt=attempt,
                    stage=stage,
                    window=window.candidate,
                    tokens_before=tokens_before,
                    tokens_after=tokens_after,
                    digest=digest.payload,
                    usage={"total_tokens": _usage_total(self.llm_router) - usage_before},
                )
                return CompactionResult(True, tokens_before, tokens_after)
            except Exception as exc:
                failure = (
                    exc
                    if isinstance(exc, ContextCompactionError)
                    else ContextCompactionError(
                        "context_compaction_provider_error",
                        detail=f"{type(exc).__name__}: {exc}",
                    )
                )
                error = f"{type(failure).__name__}: {failure}"
                errors.append(error)
                estimated_tokens = None
                remaining_tokens = None
                if (
                    request is not None
                    and failure.error_kind == "context_compaction_budget_rejected"
                ):
                    estimated_tokens, remaining_tokens = self._request_budget(
                        request
                    )
                self._append_audit(
                    success=False,
                    attempt=attempt,
                    stage=stage,
                    window=window.candidate,
                    tokens_before=tokens_before,
                    tokens_after=tokens_before,
                    error=error,
                    error_kind=failure.error_kind,
                    finish_reason=failure.finish_reason,
                    usage={"total_tokens": _usage_total(self.llm_router) - usage_before},
                    request_sent=request_sent,
                    estimated_tokens=estimated_tokens,
                    remaining_tokens=remaining_tokens,
                )
                if (
                    attempt == 1
                    and retry_window is not None
                    and self._is_retryable(failure)
                ):
                    window = retry_window
                    attempt = 2
                    continue
                break

        return CompactionResult(
            False,
            tokens_before,
            tokens_before,
            error="; ".join(errors),
        )

    def _partition(
        self,
        messages: List[Dict[str, Any]],
    ) -> _CompactionWindow:
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
            return _CompactionWindow(protected, [], [], rest)
        return _CompactionWindow(
            protected=protected,
            candidate=rest[:tail_start],
            preserved_middle=[],
            recent_tail=rest[tail_start:],
        )

    def _retry_window(
        self,
        initial: _CompactionWindow,
    ) -> Optional[_CompactionWindow]:
        groups = self._exchange_groups(initial.candidate)
        if len(groups) < 2:
            return None

        prefix: List[Dict[str, Any]] = []
        for group in groups[:-1]:
            candidate = prefix + group
            if _message_tokens(candidate) > self.retry_candidate_tokens:
                break
            prefix = candidate
        if (
            not prefix
            or _message_tokens(prefix) < self.min_candidate_tokens
            or _message_tokens(prefix) >= _message_tokens(initial.candidate)
        ):
            return None

        return _CompactionWindow(
            protected=initial.protected,
            candidate=prefix,
            preserved_middle=(
                initial.candidate[len(prefix):] + initial.preserved_middle
            ),
            recent_tail=initial.recent_tail,
        )

    @staticmethod
    def _exchange_groups(
        messages: List[Dict[str, Any]],
    ) -> List[List[Dict[str, Any]]]:
        groups: List[List[Dict[str, Any]]] = []
        current: List[Dict[str, Any]] = []
        for message in messages:
            if message.get("role") == "assistant" and current:
                groups.append(current)
                current = []
            current.append(message)
        if current:
            groups.append(current)
        return groups

    @staticmethod
    def _is_retryable(exc: Exception) -> bool:
        return getattr(exc, "error_kind", None) in {
            "context_digest_truncated",
            "context_digest_schema_invalid",
            "context_digest_nonreducing",
        }

    def _request_digest(self, request: CompactionRequest) -> ContextDigest:
        request_messages = self._request_messages(request)
        response = self.llm_router.chat_with_tools(
            request_messages,
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
            enable_thinking=False,
            usage_metadata={
                "stage": request.stage,
                "prompt_version": COMPACTION_PROMPT_VERSION,
                **{
                    key: request.budget_snapshot[key]
                    for key in ("budget_scope", "budget_scope_limit")
                    if key in request.budget_snapshot
                },
            },
        )
        finish_reason = response.get("finish_reason")
        if finish_reason == "length" and response.get("tool_parse_errors"):
            raise ContextCompactionError(
                "context_digest_truncated",
                finish_reason=finish_reason,
            )
        if response.get("tool_parse_errors"):
            raise ContextCompactionError(
                "context_digest_schema_invalid",
                detail="tool arguments were not valid JSON",
                finish_reason=finish_reason,
            )
        calls = response.get("function_calls") if response.get("type") == "function_calls" else None
        if not calls or len(calls) != 1 or calls[0].get("name") != "submit_context_digest":
            raise ContextCompactionError(
                "context_digest_schema_invalid",
                detail="Flash must return exactly one submit_context_digest call",
                finish_reason=finish_reason,
            )
        return ContextDigest.from_payload(
            calls[0].get("arguments"),
            request.stage,
            digest_token_limit=self.digest_token_limit,
        )

    def _request_messages(
        self,
        request: CompactionRequest,
    ) -> List[Dict[str, str]]:
        payload = {
            "prompt_version": COMPACTION_PROMPT_VERSION,
            "stage": request.stage,
            "stage_contract": request.stage_contract,
            "budget_snapshot": request.budget_snapshot,
            "previous_digest": request.previous_digest,
            "messages": request.messages,
        }
        return [
            {
                "role": "system",
                "content": COMPACTION_SYSTEM_PROMPT.format(
                    target_tokens=self.target_tokens
                ),
            },
            {
                "role": "user",
                "content": json.dumps(payload, ensure_ascii=False, sort_keys=True),
            },
        ]

    def _preflight_request(self, request: CompactionRequest) -> None:
        estimated, spendable = self._request_budget(request)
        if spendable is not None and estimated > spendable:
            raise ContextCompactionError(
                "context_compaction_budget_rejected",
                detail=(
                    f"estimated request {estimated} exceeds "
                    f"{spendable} non-reserved tokens"
                ),
            )

    def _request_budget(
        self,
        request: CompactionRequest,
    ) -> tuple[int, Optional[int]]:
        estimated = estimate_chat_request_tokens(
            self._request_messages(request),
            [SUBMIT_CONTEXT_DIGEST_TOOL],
            max_tokens=self.output_tokens,
        )
        ledger = getattr(self.llm_router, "budget_ledger", None)
        reserved_tokens = int(
            request.budget_snapshot.get("completion_reserved", 0) or 0
        )
        if (
            ledger is None
            or not hasattr(ledger, "spendable_tokens")
            or reserved_tokens <= 0
        ):
            return estimated, request.budget_snapshot.get("spendable_tokens")
        metadata = {
            key: request.budget_snapshot[key]
            for key in ("budget_scope", "budget_scope_limit")
            if key in request.budget_snapshot
        }
        spendable = ledger.spendable_tokens(
            request.stage,
            reserved_tokens=reserved_tokens,
            **metadata,
        )
        return estimated, spendable

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
        error_kind: Optional[str] = None,
        finish_reason: Optional[str] = None,
        usage: Optional[Dict[str, int]] = None,
        request_sent: bool = True,
        estimated_tokens: Optional[int] = None,
        remaining_tokens: Optional[int] = None,
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
            "error_kind": error_kind,
            "finish_reason": finish_reason,
            "request_sent": request_sent,
            "estimated_tokens": estimated_tokens,
            "remaining_tokens": remaining_tokens,
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
