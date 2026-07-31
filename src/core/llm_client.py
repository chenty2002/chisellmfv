"""One direct OpenAI-compatible chat client."""

from __future__ import annotations

import hashlib
import json
import logging
from pathlib import Path
from typing import Any, Dict, List, Optional, Union
from urllib.parse import urlparse

import requests
import tiktoken

from ..utils.config import get_llm_settings
from .budget import RunBudgetLedger


class LLMClient:
    """Send one request per call and return its tool calls without retries."""

    def __init__(
        self,
        *,
        model: Optional[str] = None,
        api_key: Optional[str] = None,
        llm_url: Optional[str] = None,
        logger: Optional[logging.Logger] = None,
        model_role: str = "main",
        max_token_budget: Optional[int] = None,
        budget_ledger: Optional[RunBudgetLedger] = None,
        raw_response_dir: Optional[Path] = None,
    ):
        settings = get_llm_settings()
        self.model = model or settings["model"]
        self.api_key = api_key or settings["api_key"]
        self.llm_url = _chat_url(llm_url or settings["url"])
        self.extra_body = settings["extra_body"]
        self.logger = logger
        self.model_role = model_role
        self.budget_ledger = budget_ledger or RunBudgetLedger(max_token_budget)
        self.raw_response_dir = (
            Path(raw_response_dir).resolve() if raw_response_dir is not None else None
        )
        self.session = requests.Session()
        self.usage = {
            "llm_calls": 0,
            "llm_prompt_tokens": 0,
            "llm_cached_prompt_tokens": 0,
            "llm_completion_tokens": 0,
            "llm_reasoning_tokens": 0,
            "llm_total_tokens": 0,
        }
        if not self.api_key:
            raise ValueError("CHISELLMFV_LLM_API_KEY is required for a model stage")

    def chat_with_tools(
        self,
        *,
        messages: List[Dict[str, Any]],
        tools: List[Dict[str, Any]],
        max_tokens: int = 4096,
        temperature: float = 0.0,
        tool_choice: Optional[Union[str, Dict[str, Any]]] = "required",
        enable_thinking: Optional[bool] = None,
        parallel_tool_calls: Optional[bool] = False,
        usage_metadata: Optional[Dict[str, str]] = None,
    ) -> Dict[str, Any]:
        wire_tools = [{"type": "function", "function": tool} for tool in tools]
        payload: Dict[str, Any] = dict(self.extra_body)
        payload.update({
            "model": self.model,
            "messages": messages,
            "tools": wire_tools,
            "max_tokens": max_tokens,
            "temperature": temperature,
            "stream": False,
        })
        if tool_choice is not None:
            payload["tool_choice"] = tool_choice
        if parallel_tool_calls is not None:
            payload["parallel_tool_calls"] = parallel_tool_calls
        if enable_thinking is not None:
            # DeepSeek's OpenAI-format API uses the structured ``thinking``
            # switch.  Remove the retired environment key so it cannot keep
            # thinking enabled after a caller explicitly disables it.
            payload.pop("enable_thinking", None)
            payload["thinking"] = {
                "type": "enabled" if enable_thinking else "disabled"
            }

        estimated = _count_tokens(messages) + _count_tokens(wire_tools) + max_tokens
        metadata = usage_metadata or {}
        self.budget_ledger.check_request(estimated_tokens=estimated)
        response = self.session.post(
            self.llm_url,
            headers={
                "Content-Type": "application/json",
                "Authorization": f"Bearer {self.api_key}",
            },
            json=payload,
            timeout=120,
        )
        if not response.ok:
            raise requests.HTTPError(
                f"{response.status_code} model request failed: {response.text[:2000]}",
                response=response,
            )
        result = response.json()
        usage = result.get("usage") or {}
        prompt = int(usage.get("prompt_tokens", 0) or 0)
        completion = int(usage.get("completion_tokens", 0) or 0)
        total = int(usage.get("total_tokens", prompt + completion) or 0)
        details = usage.get("completion_tokens_details") or {}
        cached = int((usage.get("prompt_tokens_details") or {}).get("cached_tokens", 0) or 0)
        reasoning = int(details.get("reasoning_tokens", 0) or 0)
        self.usage["llm_calls"] += 1
        self.usage["llm_prompt_tokens"] += prompt
        self.usage["llm_cached_prompt_tokens"] += cached
        self.usage["llm_completion_tokens"] += completion
        self.usage["llm_reasoning_tokens"] += reasoning
        self.usage["llm_total_tokens"] += total
        self.budget_ledger.record_usage(prompt_tokens=prompt, completion_tokens=completion)
        if self.logger:
            self.logger.info("model call stage=%s tokens=%s", metadata.get("stage"), total)

        choice = result["choices"][0]
        message = choice["message"]
        calls = message.get("tool_calls") or []
        self._save_raw_response(
            {
                "schema_version": "llm_raw_response",
                "request": {
                    "model": self.model,
                    "message_count": len(messages),
                    "messages_sha256": hashlib.sha256(
                        json.dumps(
                            messages,
                            ensure_ascii=False,
                            sort_keys=True,
                            default=str,
                        ).encode("utf-8")
                    ).hexdigest(),
                    "tool_names": [tool["name"] for tool in tools],
                    "max_output_tokens": max_tokens,
                    "temperature": temperature,
                    "tool_choice": tool_choice,
                    "enable_thinking": enable_thinking,
                    "parallel_tool_calls": parallel_tool_calls,
                    "usage_metadata": metadata,
                },
                "response_id": result.get("id"),
                "finish_reason": choice.get("finish_reason"),
                "usage": usage,
                "text": message.get("content"),
                "raw_tool_calls": [
                    {
                        "id": call.get("id"),
                        "name": (call.get("function") or {}).get("name"),
                        "arguments": (call.get("function") or {}).get("arguments"),
                    }
                    for call in calls
                ],
            }
        )
        if not calls:
            return {
                "type": "text",
                "content": message.get("content", ""),
                "finish_reason": choice.get("finish_reason"),
            }
        parsed = []
        for call in calls:
            function = call["function"]
            arguments = function["arguments"]
            if isinstance(arguments, str):
                try:
                    arguments = json.loads(arguments)
                except json.JSONDecodeError as exc:
                    try:
                        # Some OpenAI-compatible providers emit literal newlines
                        # inside otherwise valid JSON strings.  Accept only that
                        # JSON-standard-library relaxation; typed tool validators
                        # still enforce the complete submitted contract.
                        arguments = json.loads(arguments, strict=False)
                    except json.JSONDecodeError:
                        return {
                            "type": "invalid_tool_arguments",
                            "content": message.get("content", ""),
                            "finish_reason": choice.get("finish_reason"),
                            "parse_error": str(exc),
                        }
            if not isinstance(arguments, dict):
                raise ValueError("tool arguments must decode to a JSON object")
            parsed.append(
                {
                    "name": function["name"],
                    "arguments": arguments,
                    "id": call["id"],
                }
            )
        return {
            "type": "function_calls",
            "function_calls": parsed,
            "finish_reason": choice.get("finish_reason"),
        }

    def get_token_usage(self) -> Dict[str, Any]:
        return dict(self.usage)

    def reset_token_usage(self) -> None:
        for key in self.usage:
            self.usage[key] = 0
        self.budget_ledger.reset()

    def _save_raw_response(self, value: Dict[str, Any]) -> None:
        if self.raw_response_dir is None:
            return
        self.raw_response_dir.mkdir(parents=True, exist_ok=True)
        path = self.raw_response_dir / f"response_{self.usage['llm_calls']:02d}.json"
        if path.exists():
            raise FileExistsError(f"raw model response already exists: {path}")
        path.write_text(
            json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
            encoding="utf-8",
        )


def _chat_url(value: str) -> str:
    parsed = urlparse(value)
    if not parsed.scheme or not parsed.netloc:
        raise ValueError("CHISELLMFV_LLM_URL must be an absolute URL")
    path = parsed.path.rstrip("/")
    if not path.endswith("/chat/completions"):
        path = f"{path}/chat/completions" if path else "/chat/completions"
    return parsed._replace(path=path, params="", query="", fragment="").geturl()


def _count_tokens(value: Any) -> int:
    text = json.dumps(value, ensure_ascii=False, sort_keys=True, default=str)
    return len(tiktoken.get_encoding("cl100k_base").encode(text))
