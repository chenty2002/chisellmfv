import json
import logging
import os
from typing import Dict, List, Any, Optional, Tuple, Union
from urllib.parse import urlparse

import requests
try:
    import tiktoken
except ImportError:  # pragma: no cover - exercised only in minimal envs
    tiktoken = None

from ..utils.llm_properties import *
from ..utils.config import get_endpoint_overrides
from .budget import RunBudgetLedger, TokenBudgetExceeded

# Global tokenizer instance for efficiency
_tokenizer_cache: Dict[str, Any] = {}
DEFAULT_CHAT_MAX_TOKENS = 16384


_MISSING_API_KEY_MSG = (
    "LLM API key not configured. Set it via one of:\n"
    "  - environment variable: CHISELLMFV_LLM_API_KEY=<key>\n"
    "  - or a project-root `.env` file (see `.env.example`).\n"
    "Legacy variable name `LLM_API_KEY` is also accepted."
)


class LLMClient:
    """Enhanced client for interacting with LLM APIs with embedding and reranking capabilities"""

    def __init__(self, model: Optional[str] = None, api_key: Optional[str] = None,
                 embedding_api_key: Optional[str] = None, reranker_api_key: Optional[str] = None,
                 logger: Optional[logging.Logger] = None,
                 max_token_budget: Optional[int] = None,
                 llm_url: Optional[str] = None,
                 embedding_url: Optional[str] = None,
                 reranker_url: Optional[str] = None,
                 embedding_model: Optional[str] = None,
                 reranker_model: Optional[str] = None,
                 llm_extra_body: Optional[Dict[str, Any]] = None,
                 model_role: Optional[str] = None,
                 budget_ledger: Optional[RunBudgetLedger] = None):
        """
        Initialize the LLM client.

        Args:
            model: Name of the LLM model (default from env/llm_properties)
            api_key: API key for the LLM service (default from env)
            embedding_api_key: API key for embeddings (default from env)
            reranker_api_key: API key for reranker (default from env)
            logger: Optional logger instance for detailed logging
            max_token_budget: Maximum total tokens allowed across all API calls
            llm_url / embedding_url / reranker_url: Optional endpoint overrides
            embedding_model / reranker_model: Optional model overrides
            llm_extra_body: Optional extra request fields for chat completion API
            model_role: Optional logical role for usage attribution
            budget_ledger: Shared run-wide token ledger
        """
        overrides = get_endpoint_overrides()

        self.model = model or overrides["llm_model"] or LLM_MODEL
        self.model_role = model_role
        self.embedding_model = (
            embedding_model or overrides["embedding_model"] or EMBEDDING_MODEL
        )
        self.reranker_model = (
            reranker_model or overrides["reranker_model"] or RERANKER_MODEL
        )

        llm_url_override = llm_url or overrides["llm_url"] or overrides["llm_base_url"] or LLM_URL
        self.llm_url = self._normalize_llm_url(llm_url_override)
        self.embedding_url = embedding_url or overrides["embedding_url"] or EMBEDDING_URL
        self.reranker_url = reranker_url or overrides["reranker_url"] or RERANKER_URL

        extra_body_override = overrides.get("llm_extra_body")
        raw_extra_body = (
            llm_extra_body
            if llm_extra_body is not None
            else self._parse_extra_body(extra_body_override)
        )
        self.llm_extra_body = self._normalize_provider_extra_body(raw_extra_body)
        self.enable_prompt_cache_key = self._should_enable_prompt_cache_key(
            overrides.get("enable_prompt_cache_key"),
            self.llm_url,
        )
        self.trust_env_proxy = self._should_trust_env_proxy(
            overrides.get("trust_env_proxy")
        )
        self.session = requests.Session()
        self.session.trust_env = self.trust_env_proxy

        self.api_key = api_key or LLM_API_KEY
        self.embedding_api_key = embedding_api_key or EMBEDDING_API_KEY
        self.reranker_api_key = reranker_api_key or RERANKER_API_KEY
        self.logger = logger
        if (
            budget_ledger is not None
            and max_token_budget is not None
            and budget_ledger.hard_token_limit != max_token_budget
        ):
            raise ValueError(
                "max_token_budget conflicts with the shared budget ledger"
            )
        self._owns_budget_ledger = budget_ledger is None
        self.budget_ledger = budget_ledger or RunBudgetLedger(max_token_budget)

        # Initialize token usage tracking
        self.token_usage = {
            "llm_calls": 0,
            "embedding_calls": 0,
            "reranker_calls": 0,
            "llm_prompt_tokens": 0,
            "llm_cached_prompt_tokens": 0,
            "llm_cache_miss_prompt_tokens": 0,
            "llm_completion_tokens": 0,
            "llm_total_tokens": 0,
            "llm_reasoning_tokens": 0,
            "embedding_prompt_tokens": 0,
            "embedding_completion_tokens": 0,
            "embedding_total_tokens": 0,
            "reranker_input_tokens": 0,
            "reranker_output_tokens": 0
        }
        self.llm_usage_by_key: Dict[str, Dict[str, int]] = {}

        if not self.api_key:
            raise ValueError(_MISSING_API_KEY_MSG)

        if not self.trust_env_proxy and self.logger:
            self.logger.info(
                "Ignoring loopback HTTP(S) proxy environment variables for LLM API requests. "
                "Set CHISELLMFV_TRUST_ENV_PROXY=true to force using them."
            )

    @staticmethod
    def _logger_writes_to_console(logger: Optional[logging.Logger]) -> bool:
        """Return True when logger propagation reaches a non-file stream handler."""
        current = logger
        while current:
            for handler in current.handlers:
                if isinstance(handler, logging.StreamHandler) and not isinstance(
                    handler, logging.FileHandler
                ):
                    return True
            if not current.propagate:
                break
            parent = current.parent
            if parent is current:
                break
            current = parent
        return False

    @staticmethod
    def _normalize_llm_url(url: str) -> str:
        """Accept either full chat-completions URL or an OpenAI-compatible base URL."""
        parsed = urlparse(url)
        if not parsed.scheme or not parsed.netloc:
            raise ValueError(f"Invalid LLM URL: {url}")

        path = (parsed.path or "").rstrip("/")
        if path.endswith("/chat/completions"):
            return url

        normalized_path = f"{path}/chat/completions" if path else "/chat/completions"
        return parsed._replace(path=normalized_path, params="", query="", fragment="").geturl()

    @staticmethod
    def _parse_extra_body(extra_body_raw: Optional[str]) -> Optional[Dict[str, Any]]:
        """Parse JSON object from CHISELLMFV_LLM_EXTRA_BODY env var if provided."""
        if not extra_body_raw:
            return None
        try:
            payload = json.loads(extra_body_raw)
        except json.JSONDecodeError as exc:
            raise ValueError(
                "CHISELLMFV_LLM_EXTRA_BODY must be a valid JSON object string"
            ) from exc

        if not isinstance(payload, dict):
            raise ValueError("CHISELLMFV_LLM_EXTRA_BODY must decode to a JSON object")
        return payload

    @staticmethod
    def _should_enable_prompt_cache_key(raw_value: Optional[str], llm_url: str) -> bool:
        """Enable OpenAI prompt-cache routing by default only for OpenAI URLs.

        Some OpenAI-compatible providers reject unknown top-level fields. Set
        CHISELLMFV_ENABLE_PROMPT_CACHE_KEY=true to opt in for another provider.
        """
        if raw_value is not None:
            return raw_value.strip().lower() in {"1", "true", "yes", "on"}
        return urlparse(llm_url).netloc.endswith("api.openai.com")

    @staticmethod
    def _parse_bool_env(raw_value: Optional[str]) -> Optional[bool]:
        if raw_value is None:
            return None
        value = raw_value.strip().lower()
        if value in {"1", "true", "yes", "on", "enabled"}:
            return True
        if value in {"0", "false", "no", "off", "disabled"}:
            return False
        raise ValueError(
            "CHISELLMFV_TRUST_ENV_PROXY must be one of true/false, yes/no, on/off, or 1/0"
        )

    @staticmethod
    def _env_proxy_hosts() -> List[str]:
        hosts: List[str] = []
        for name in (
            "HTTPS_PROXY",
            "HTTP_PROXY",
            "ALL_PROXY",
            "https_proxy",
            "http_proxy",
            "all_proxy",
        ):
            raw = os.environ.get(name)
            if not raw:
                continue
            parsed = urlparse(raw if "://" in raw else f"http://{raw}")
            host = parsed.hostname
            if host:
                hosts.append(host.lower())
        return hosts

    @classmethod
    def _has_loopback_env_proxy(cls) -> bool:
        for host in cls._env_proxy_hosts():
            if host == "localhost" or host == "::1" or host.startswith("127."):
                return True
        return False

    @classmethod
    def _should_trust_env_proxy(cls, raw_value: Optional[str]) -> bool:
        explicit = cls._parse_bool_env(raw_value)
        if explicit is not None:
            return explicit
        return not cls._has_loopback_env_proxy()

    def _is_deepseek_endpoint(self) -> bool:
        host = urlparse(self.llm_url).netloc.lower()
        return host == "api.deepseek.com" or host.endswith(".deepseek.com")

    @staticmethod
    def _coerce_bool(value: Any) -> bool:
        if isinstance(value, str):
            return value.strip().lower() in {"1", "true", "yes", "on", "enabled"}
        return bool(value)

    @staticmethod
    def _normalize_deepseek_reasoning_effort(value: Any) -> str:
        effort = str(value).strip().lower()
        return "max" if effort in {"max", "xhigh"} else "high"

    def _normalize_provider_extra_body(
        self,
        extra_body: Optional[Dict[str, Any]],
    ) -> Optional[Dict[str, Any]]:
        """Normalize provider-specific extra request fields before sending JSON."""
        if not extra_body:
            return extra_body

        payload = dict(extra_body)
        if not self._is_deepseek_endpoint():
            return payload

        if "enable_thinking" in payload and "thinking" not in payload:
            enabled = self._coerce_bool(payload.pop("enable_thinking"))
            thinking: Dict[str, Any] = {
                "type": "enabled" if enabled else "disabled",
            }
            reasoning_effort = payload.pop("reasoning_effort", None)
            if enabled and reasoning_effort is not None:
                thinking["reasoning_effort"] = (
                    self._normalize_deepseek_reasoning_effort(reasoning_effort)
                )
            payload["thinking"] = thinking
        else:
            payload.pop("enable_thinking", None)

        thinking = payload.get("thinking")
        if isinstance(thinking, dict):
            normalized_thinking = dict(thinking)
            if "reasoning_effort" in normalized_thinking:
                normalized_thinking["reasoning_effort"] = (
                    self._normalize_deepseek_reasoning_effort(
                        normalized_thinking["reasoning_effort"]
                    )
                )
            payload["thinking"] = normalized_thinking

        return payload

    def _deepseek_reasoning_mode_requested(self, payload: Dict[str, Any]) -> bool:
        model = str(payload.get("model", self.model)).lower()
        if "reasoner" in model:
            return True

        thinking = payload.get("thinking")
        if isinstance(thinking, dict):
            thinking_type = str(thinking.get("type", "enabled")).strip().lower()
            return thinking_type != "disabled"

        if "enable_thinking" in payload:
            return self._coerce_bool(payload["enable_thinking"])

        # DeepSeek v4 defaults to thinking mode when it is not explicitly disabled.
        return True

    def _adjust_payload_for_provider(self, payload: Dict[str, Any]) -> None:
        if not self._is_deepseek_endpoint():
            return

        if (
            payload.get("tool_choice") == "required"
            and self._deepseek_reasoning_mode_requested(payload)
        ):
            # DeepSeek thinking/reasoner mode rejects required tool choice. The
            # prompt still asks for tool-only output; workflow retry handling
            # catches the rare plain-text response.
            payload["tool_choice"] = "auto"

    def _make_api_request(self, url: str, headers: dict, payload: dict, 
                          api_type: str = "llm",
                          request_metadata: Optional[Dict[str, str]] = None) -> dict:
        """
        Common API request handler with error handling and token tracking.
        
        Args:
            url: API endpoint URL
            headers: Request headers
            payload: Request payload
            api_type: Type of API call for tracking ("llm", "embedding", "reranker")
            
        Returns:
            API response as dictionary
        """
        response = self.session.post(url, headers=headers, json=payload)
        
        if response.status_code != 200:
            # Include payload in error message for debugging (truncate large content)
            payload_str = json.dumps(payload, ensure_ascii=False, default=str)
            raise Exception(
                f"{api_type.upper()} API request failed with status "
                f"{response.status_code}: \n{response.text}\n\n"
                f"Request payload:\n{payload_str}"
            )
        
        result = response.json()
        
        
        # Log token usage; print only when the logger will not show it on console.
        if "usage" in result:
            usage = result["usage"]
            current_request_tokens = usage.get("total_tokens", 0)
            cached_tokens, miss_tokens = self._extract_prompt_cache_usage(usage)
            completion_details = usage.get("completion_tokens_details") or {}
            reasoning_tokens = completion_details.get("reasoning_tokens", 0)
            hit_rate = (
                cached_tokens / usage.get("prompt_tokens", 0)
                if usage.get("prompt_tokens", 0) else 0.0
            )
            token_usage_message = (
                f'Prompt: {usage.get("prompt_tokens", 0)}, '
                f'Completion: {usage.get("completion_tokens", 0)}, '
                f'Total: {current_request_tokens}, '
                f'Cached Prompt: {cached_tokens}, '
                f'Cache Miss Prompt: {miss_tokens}, '
                f'Cache Hit Rate: {hit_rate:.1%}, '
                f'Reasoning: {reasoning_tokens}'
            )
            if self.logger:
                self.logger.info(f"Token usage - {token_usage_message}")
            if not self._logger_writes_to_console(self.logger):
                print(f"LLM response token usage - {token_usage_message}")
        
            if api_type == "llm":
                self.token_usage["llm_calls"] += 1
                self.token_usage["llm_prompt_tokens"] += usage.get("prompt_tokens", 0)
                self.token_usage["llm_cached_prompt_tokens"] += cached_tokens
                self.token_usage["llm_cache_miss_prompt_tokens"] += miss_tokens
                self.token_usage["llm_completion_tokens"] += usage.get("completion_tokens", 0)
                self.token_usage["llm_total_tokens"] += usage.get("total_tokens", 0)
                self.token_usage["llm_reasoning_tokens"] += reasoning_tokens
                self._record_llm_usage_breakdown(
                    request_metadata=request_metadata,
                    usage=usage,
                    cached_tokens=cached_tokens,
                    miss_tokens=miss_tokens,
                    reasoning_tokens=reasoning_tokens,
                )
            elif api_type == "embedding":
                self.token_usage["embedding_calls"] += 1
                self.token_usage["embedding_prompt_tokens"] += usage.get("prompt_tokens", 0)
                self.token_usage["embedding_completion_tokens"] += usage.get("completion_tokens", 0)
                self.token_usage["embedding_total_tokens"] += usage.get("total_tokens", 0)
        
        if api_type == "reranker":
            self.token_usage["reranker_calls"] += 1
            if "tokens" in result:
                tokens = result["tokens"]
                self.token_usage["reranker_input_tokens"] += tokens.get("input_tokens", 0)
                self.token_usage["reranker_output_tokens"] += tokens.get("output_tokens", 0)
        
        self._record_shared_budget_usage(
            api_type=api_type,
            result=result,
            request_metadata=request_metadata,
        )
        
        return result

    def _record_llm_usage_breakdown(
        self,
        request_metadata: Optional[Dict[str, str]],
        usage: Dict[str, Any],
        cached_tokens: int,
        miss_tokens: int,
        reasoning_tokens: int,
    ) -> None:
        """Aggregate LLM usage by stage/prompt/tool key for run analysis."""
        metadata = dict(request_metadata or {})
        metadata.setdefault("model", self.model)
        if self.model_role:
            metadata.setdefault("model_role", self.model_role)
        key_parts = [
            f"{name}={metadata[name]}"
            for name in (
                "stage",
                "target",
                "prompt_version",
                "tool_schema_hash",
                "model_role",
                "model",
            )
            if metadata.get(name)
        ]
        key = "|".join(key_parts) if key_parts else "unlabeled"

        bucket = self.llm_usage_by_key.setdefault(
            key,
            {
                "calls": 0,
                "prompt_tokens": 0,
                "cached_prompt_tokens": 0,
                "cache_miss_prompt_tokens": 0,
                "completion_tokens": 0,
                "reasoning_tokens": 0,
                "total_tokens": 0,
            },
        )
        bucket["calls"] += 1
        bucket["prompt_tokens"] += usage.get("prompt_tokens", 0)
        bucket["cached_prompt_tokens"] += cached_tokens
        bucket["cache_miss_prompt_tokens"] += miss_tokens
        bucket["completion_tokens"] += usage.get("completion_tokens", 0)
        bucket["reasoning_tokens"] += reasoning_tokens
        bucket["total_tokens"] += usage.get("total_tokens", 0)

    @staticmethod
    def _extract_prompt_cache_usage(usage: Dict[str, Any]) -> Tuple[int, int]:
        """Return cached and uncached prompt-token counts across API variants.

        DeepSeek non-streaming responses expose `prompt_cache_hit_tokens` and
        `prompt_cache_miss_tokens`; OpenAI exposes cached tokens under
        `prompt_tokens_details.cached_tokens`.
        """
        prompt_tokens = usage.get("prompt_tokens", 0) or 0
        prompt_details = usage.get("prompt_tokens_details") or {}

        cached = (
            usage.get("prompt_cache_hit_tokens")
            or prompt_details.get("cached_tokens")
            or usage.get("cached_tokens")
            or 0
        )
        miss = usage.get("prompt_cache_miss_tokens")
        if miss is None:
            miss = max(prompt_tokens - cached, 0)

        return int(cached or 0), int(miss or 0)
    
    def _record_shared_budget_usage(
        self,
        *,
        api_type: str,
        result: Dict[str, Any],
        request_metadata: Optional[Dict[str, str]],
    ) -> None:
        metadata = dict(request_metadata or {})
        role = metadata.get("model_role") or self.model_role or api_type
        stage = metadata.get("stage")
        if api_type in {"llm", "embedding"}:
            usage = result.get("usage") or {}
            self.budget_ledger.record_usage(
                role=role,
                stage=stage,
                budget_scope=metadata.get("budget_scope"),
                budget_scope_limit=metadata.get("budget_scope_limit"),
                prompt_tokens=usage.get("prompt_tokens", 0),
                completion_tokens=usage.get("completion_tokens", 0),
            )
            return
        tokens = result.get("tokens") or {}
        self.budget_ledger.record_usage(
            role=role,
            stage=stage,
            budget_scope=metadata.get("budget_scope"),
            budget_scope_limit=metadata.get("budget_scope_limit"),
            other_tokens=(
                int(tokens.get("input_tokens", 0) or 0)
                + int(tokens.get("output_tokens", 0) or 0)
            ),
        )
    
    def _create_headers(self, api_key: str) -> dict:
        """Create standard API request headers."""
        return {
            "Content-Type": "application/json",
            "Authorization": f"Bearer {api_key}"
        }
    
    def chat_with_tools(self, 
                        messages: List[Dict[str, Any]],
                        tools: List[Dict[str, Any]],
                        max_tokens: int = DEFAULT_CHAT_MAX_TOKENS,
                        temperature: float = 0.5,
                        tool_choice: Optional[Union[str, Dict[str, Any]]] = "required",
                        enable_thinking: Optional[bool] = None,
                        prompt_cache_key: Optional[str] = None,
                        prompt_cache_retention: Optional[str] = None,
                        usage_metadata: Optional[Dict[str, str]] = None) -> Dict[str, Any]:
        """
        Chat completion with message history and function calling support.
        Uses standard agent loop format with role: assistant + role: tool messages.
        
        Args:
            messages: List of message dictionaries with 'role' and 'content' keys.
                      Supports roles: 'system', 'user', 'assistant', 'tool'
                      For 'tool' messages, also include 'tool_call_id' and 'name'
            tools: List of tool/function declarations
            max_tokens: Maximum number of tokens to generate
            temperature: Sampling temperature (0-1)
            tool_choice: OpenAI-compatible tool choice policy. Defaults to
                         "required" so the API enforces the workflow's
                         tool-only prompt contract. Provider adapters may
                         downgrade unsupported policies before sending.
            
        Returns:
            Dictionary containing:
                - 'type': 'function_calls' or 'text'
                - 'content': text response (if type='text')
                - 'function_calls': list of function calls with 'name', 'arguments', 'id' (if type='function_calls')
        """
        payload = {
            "model": self.model,
            "messages": messages,
            "tools": [{"type": "function", "function": tool} for tool in tools],
            "max_tokens": max_tokens,
            "temperature": temperature,
            "stream": False,
        }
        if tools and tool_choice is not None:
            payload["tool_choice"] = tool_choice
        if prompt_cache_key and self.enable_prompt_cache_key:
            payload["prompt_cache_key"] = prompt_cache_key
        if prompt_cache_retention and self.enable_prompt_cache_key:
            payload["prompt_cache_retention"] = prompt_cache_retention
        if self.llm_extra_body:
            # This client sends raw HTTP JSON, so provider-specific request
            # fields belong at the top level rather than under SDK-only
            # wrappers such as `extra_body`.
            payload.update(self.llm_extra_body)
        if enable_thinking is not None and self._is_deepseek_endpoint():
            payload["thinking"] = {
                "type": "enabled" if enable_thinking else "disabled"
            }
        self._adjust_payload_for_provider(payload)

        # Count tokens in all messages
        prompt_tokens = count_tokens(json.dumps(messages, ensure_ascii=False, default=str))
        tool_tokens = count_tokens(json.dumps(payload.get("tools", []), ensure_ascii=False, sort_keys=True))
        metadata = dict(usage_metadata or {})
        self.budget_ledger.check_request(
            estimated_tokens=prompt_tokens + tool_tokens + max_tokens,
            role=metadata.get("model_role") or self.model_role or "llm",
            stage=metadata.get("stage"),
            budget_scope=metadata.get("budget_scope"),
            budget_scope_limit=metadata.get("budget_scope_limit"),
        )
        print(f'LLM chat_with_tools request: ~{prompt_tokens + tool_tokens} prompt/tool tokens '
              f'({prompt_tokens} message, {tool_tokens} tool) and {len(tools)} tools')

        result = self._make_api_request(
            self.llm_url,
            self._create_headers(self.api_key),
            payload,
            "llm",
            request_metadata=usage_metadata,
        )

        # Parse response
        choice = result["choices"][0]
        message = choice["message"]
        
        if "tool_calls" in message and message["tool_calls"]:
            function_calls, parse_errors = self._parse_function_tool_calls(
                message["tool_calls"]
            )
            if parse_errors:
                error_text = (
                    "ERROR: The model returned a tool call whose arguments were "
                    "not valid JSON. Retry with complete, valid JSON arguments "
                    "for every tool call."
                )
                if self.logger:
                    self.logger.warning(
                        "%s Details: %s",
                        error_text,
                        json.dumps(parse_errors, ensure_ascii=False),
                    )
                return {
                    "type": "text",
                    "content": error_text,
                    "raw_message": message,
                    "tool_parse_errors": parse_errors,
                }
            return {
                "type": "function_calls",
                "function_calls": function_calls,
                "raw_message": message  # Include raw message for building history
            }
        else:
            return {
                "type": "text",
                "content": message.get("content", ""),
                "raw_message": message
            }

    @staticmethod
    def _parse_function_tool_calls(
        tool_calls: List[Dict[str, Any]],
    ) -> Tuple[List[Dict[str, Any]], List[Dict[str, Any]]]:
        function_calls: List[Dict[str, Any]] = []
        parse_errors: List[Dict[str, Any]] = []

        for idx, tool_call in enumerate(tool_calls):
            if tool_call.get("type") != "function":
                continue

            function = tool_call.get("function") or {}
            raw_arguments = function.get("arguments", "{}")
            if isinstance(raw_arguments, dict):
                arguments = raw_arguments
            else:
                if raw_arguments is None:
                    raw_arguments = "{}"
                elif not isinstance(raw_arguments, str):
                    raw_arguments = str(raw_arguments)
                try:
                    arguments = json.loads(raw_arguments)
                except json.JSONDecodeError as exc:
                    parse_errors.append(
                        {
                            "index": idx,
                            "id": tool_call.get("id"),
                            "name": function.get("name"),
                            "error": str(exc),
                            "arguments_prefix": raw_arguments[:500],
                        }
                    )
                    continue

            function_calls.append(
                {
                    "name": function["name"],
                    "arguments": arguments,
                    "id": tool_call["id"],
                }
            )

        return function_calls, parse_errors
    
    def get_embeddings(self, text: str) -> List[float]:
        """Generate an embedding vector for the provided text.

        Args:
            text: Single text block to embed.

        Returns:
            A list of float values representing the embedding vector.
        """
        payload = {
            "model": self.embedding_model,
            "input": text,
            "dimensions": EMBEDDING_DIMENSION
        }
        self.budget_ledger.check_request(
            estimated_tokens=count_tokens(text),
            role="embedding",
        )

        result = self._make_api_request(
            self.embedding_url,
            self._create_headers(self.embedding_api_key),
            payload,
            "embedding"
        )

        return result["data"][0]["embedding"]
    
    def rerank(self, query: str, documents: List[str], top_k: int = 5) -> List[Dict[str, Any]]:
        """Rerank documents based on semantic relevance.

        The reranker returns a payload shaped like::

            {
                "id": "<request id>",
                "results": [
                    {
                        "document": {"text": "<chunk content>"},
                        "index": <int>,
                        "relevance_score": <float>
                    },
                    ...
                ],
                "tokens": {
                    "input_tokens": <int>,
                    "output_tokens": <int>
                }
            }

        Args:
            query: The query string that triggered the retrieval.
            documents: Text chunks obtained from the vector store.
            top_k: Maximum reranked entries to request from the service.

        Returns:
            A simplified list where each element contains:
                - ``index``: the original position in ``documents``
                - ``score``: the provided ``relevance_score`` (defaults to 0.0)
                - ``text``: the document text echoed by the reranker (optional)
        """
        payload = {
            "model": self.reranker_model,
            "query": query,
            "documents": documents,
            "top_n": top_k
        }
        self.budget_ledger.check_request(
            estimated_tokens=(
                count_tokens(query)
                + sum(count_tokens(document) for document in documents)
                + max(32, top_k * 16)
            ),
            role="reranker",
        )

        result = self._make_api_request(
            self.reranker_url,
            self._create_headers(self.reranker_api_key),
            payload,
            "reranker"
        )

        simplified_results: List[Dict[str, Any]] = []
        for item in result.get("results", []):
            document = item.get("document") or {}
            simplified_results.append({
                "index": item.get("index"),
                "score": item.get("relevance_score", 0.0),
                "text": document.get("text")
            })

        return simplified_results
    
    def get_token_usage(self) -> Dict[str, Any]:
        """
        Get the current token usage statistics.
        
        Returns:
            Dictionary containing token usage statistics
        """
        usage = self.token_usage.copy()
        usage["llm_usage_by_key"] = {
            key: bucket.copy()
            for key, bucket in self.llm_usage_by_key.items()
        }
        return usage
    
    def print_token_usage(
        self,
        logger: Optional[logging.Logger] = None,
        include_cache_breakdown: bool = False,
    ) -> None:
        """
        Print a formatted summary of token usage statistics.
        """
        usage = self.get_token_usage()
        usage_summary = (
            "Token Usage Summary:\n",
            "=" * 40,
            f"LLM Calls: {usage['llm_calls']}",
            f"  Prompt Tokens: {usage['llm_prompt_tokens']}",
            f"  Cached Prompt Tokens: {usage['llm_cached_prompt_tokens']}",
            f"  Cache Miss Prompt Tokens: {usage['llm_cache_miss_prompt_tokens']}",
            f"  Cache Hit Rate: "
            f"{(usage['llm_cached_prompt_tokens'] / usage['llm_prompt_tokens'] * 100.0) if usage['llm_prompt_tokens'] else 0.0:.1f}%",
            f"  Completion Tokens: {usage['llm_completion_tokens']}",
            f"  Reasoning Tokens: {usage['llm_reasoning_tokens']}",
            f"  Total Tokens: {usage['llm_total_tokens']}",
            "",
            f"Embedding Calls: {usage['embedding_calls']}",
            f"  Prompt Tokens: {usage['embedding_prompt_tokens']}",
            f"  Completion Tokens: {usage['embedding_completion_tokens']}",
            f"  Total Tokens: {usage['embedding_total_tokens']}",
            "",
            f"Reranker Calls: {usage['reranker_calls']}",
            f"  Input Tokens: {usage['reranker_input_tokens']}",
            f"  Output Tokens: {usage['reranker_output_tokens']}",
            "=" * 40,
            f"Total API Calls: {usage['llm_calls'] + usage['embedding_calls'] + usage['reranker_calls']}",
            f"Total Tokens: {usage['llm_total_tokens'] + usage['embedding_total_tokens'] + usage['reranker_input_tokens'] + usage['reranker_output_tokens']}",
        )
        if include_cache_breakdown and self.llm_usage_by_key:
            usage_summary += ("", "LLM Cache Breakdown:")
            for key, bucket in sorted(self.llm_usage_by_key.items()):
                prompt = bucket["prompt_tokens"]
                hit_rate = (
                    bucket["cached_prompt_tokens"] / prompt * 100.0
                    if prompt else 0.0
                )
                usage_summary += (
                    f"  {key}",
                    f"    calls={bucket['calls']} prompt={prompt} "
                    f"cached={bucket['cached_prompt_tokens']} "
                    f"miss={bucket['cache_miss_prompt_tokens']} "
                    f"hit_rate={hit_rate:.1f}% total={bucket['total_tokens']}",
                )
        
        formatted_summary = "\n".join(usage_summary)
        if not self._logger_writes_to_console(logger):
            print(formatted_summary)
        if logger:
            logger.info(formatted_summary)
    
    def reset_token_usage(self) -> None:
        """
        Reset all token usage statistics to zero.
        """
        for key in self.token_usage:
            self.token_usage[key] = 0
        self.llm_usage_by_key.clear()
        if self._owns_budget_ledger:
            self.budget_ledger.reset()

    def get_remaining_budget_pct(self) -> float:
        """Return remaining token budget as percentage (0.0–100.0).
        Returns 100.0 if no budget is set."""
        if self.budget_ledger.hard_token_limit is None:
            return 100.0
        snapshot = self.budget_ledger.snapshot()
        return max(
            0.0,
            (snapshot.tokens_remaining or 0)
            / snapshot.hard_token_limit
            * 100.0,
        )


def get_tokenizer(model: str = "cl100k_base") -> Any:
    """
    Get a tokenizer instance with caching for efficiency.
    
    Args:
        model: The encoding name or model name to use.
               Common encodings:
               - "cl100k_base": Used by GPT-4, GPT-3.5-turbo, text-embedding-ada-002
               - "p50k_base": Used by Codex models
               - "r50k_base": Used by GPT-3 models
               
    Returns:
        A tiktoken Encoding instance
    """
    global _tokenizer_cache

    if tiktoken is None:
        raise ImportError("tiktoken is not installed")
    
    if model not in _tokenizer_cache:
        try:
            # Try to get encoding by name first
            _tokenizer_cache[model] = tiktoken.get_encoding(model)
        except ValueError:
            # Fall back to encoding for model
            try:
                _tokenizer_cache[model] = tiktoken.encoding_for_model(model)
            except KeyError:
                # Default to cl100k_base if model not recognized
                _tokenizer_cache[model] = tiktoken.get_encoding("cl100k_base")
    
    return _tokenizer_cache[model]


def count_tokens(text: str, model: str = "cl100k_base") -> int:
    """
    Count the number of tokens in a string using tiktoken.
    
    Args:
        text: The string to count tokens for
        model: The encoding name or model name to use for tokenization.
               Defaults to "cl100k_base" which is used by GPT-4 and GPT-3.5-turbo.
               
    Returns:
        The number of tokens in the string
    """
    if not text:
        return 0

    if tiktoken is None:
        # Fallback for environments without tiktoken. API-reported usage remains
        # authoritative; this estimate is only used for local request logging.
        return max(1, len(text) // 4)
    
    tokenizer = get_tokenizer(model)
    tokens = tokenizer.encode(text)
    return len(tokens)


def count_tokens_batch(texts: List[str], model: str = "cl100k_base") -> List[int]:
    """
    Count tokens for multiple strings efficiently.
    
    Args:
        texts: List of strings to count tokens for
        model: The encoding name or model name to use for tokenization
        
    Returns:
        List of token counts corresponding to each input string
    """
    if not texts:
        return []

    if tiktoken is None:
        return [count_tokens(text, model) for text in texts]
    
    tokenizer = get_tokenizer(model)
    return [len(tokenizer.encode(text)) for text in texts]


def estimate_chat_request_tokens(
    messages: List[Dict[str, Any]],
    tools: List[Dict[str, Any]],
    max_tokens: int = DEFAULT_CHAT_MAX_TOKENS,
) -> int:
    """Estimate the maximum tokens charged by one chat-with-tools request."""
    wire_tools = [{"type": "function", "function": tool} for tool in tools]
    return (
        count_tokens(json.dumps(messages, ensure_ascii=False, default=str))
        + count_tokens(json.dumps(wire_tools, ensure_ascii=False, sort_keys=True))
        + max_tokens
    )
