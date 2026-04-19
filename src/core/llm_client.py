import json
import logging
from typing import Dict, List, Any, Optional, Union

import requests
import tiktoken

from ..utils.llm_properties import *
from ..utils.config import get_endpoint_overrides

# Global tokenizer instance for efficiency
_tokenizer_cache: Dict[str, tiktoken.Encoding] = {}


class TokenBudgetExceeded(Exception):
    """Raised when cumulative token usage exceeds the configured budget."""

    def __init__(self, budget: int, used: int):
        self.budget = budget
        self.used = used
        super().__init__(
            f"Token budget exceeded: used {used} tokens, budget is {budget} tokens"
        )


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
                 reranker_model: Optional[str] = None):
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
        """
        overrides = get_endpoint_overrides()

        self.model = model or overrides["llm_model"] or LLM_MODEL
        self.embedding_model = (
            embedding_model or overrides["embedding_model"] or EMBEDDING_MODEL
        )
        self.reranker_model = (
            reranker_model or overrides["reranker_model"] or RERANKER_MODEL
        )

        self.llm_url = llm_url or overrides["llm_url"] or LLM_URL
        self.embedding_url = embedding_url or overrides["embedding_url"] or EMBEDDING_URL
        self.reranker_url = reranker_url or overrides["reranker_url"] or RERANKER_URL

        self.api_key = api_key or LLM_API_KEY
        self.embedding_api_key = embedding_api_key or EMBEDDING_API_KEY
        self.reranker_api_key = reranker_api_key or RERANKER_API_KEY
        self.logger = logger
        self.max_token_budget = max_token_budget

        # Initialize token usage tracking
        self.token_usage = {
            "llm_calls": 0,
            "embedding_calls": 0,
            "reranker_calls": 0,
            "llm_prompt_tokens": 0,
            "llm_completion_tokens": 0,
            "llm_total_tokens": 0,
            "embedding_prompt_tokens": 0,
            "embedding_completion_tokens": 0,
            "embedding_total_tokens": 0,
            "reranker_input_tokens": 0,
            "reranker_output_tokens": 0
        }

        if not self.api_key:
            raise ValueError(_MISSING_API_KEY_MSG)

    def _make_api_request(self, url: str, headers: dict, payload: dict, 
                          api_type: str = "llm") -> dict:
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
        response = requests.post(url, headers=headers, json=payload)
        
        if response.status_code != 200:
            # Include payload in error message for debugging (truncate large content)
            payload_str = json.dumps(payload, ensure_ascii=False, default=str)
            raise Exception(
                f"{api_type.upper()} API request failed with status "
                f"{response.status_code}: \n{response.text}\n\n"
                f"Request payload:\n{payload_str}"
            )
        
        result = response.json()
        
        
        # Log and print token usage for this request
        if "usage" in result:
            usage = result["usage"]
            current_request_tokens = usage.get("total_tokens", 0)
            print(f'LLM response: {usage.get("prompt_tokens", 0)} prompt tokens, '
                  f'{usage.get("completion_tokens", 0)} completion tokens, '
                  f'{current_request_tokens} total tokens')
            if self.logger:
                self.logger.info(f'Token usage - Prompt: {usage.get("prompt_tokens", 0)}, '
                                 f'Completion: {usage.get("completion_tokens", 0)}, '
                                 f'Total: {current_request_tokens}')
        
            if api_type == "llm":
                self.token_usage["llm_calls"] += 1
                self.token_usage["llm_prompt_tokens"] += usage.get("prompt_tokens", 0)
                self.token_usage["llm_completion_tokens"] += usage.get("completion_tokens", 0)
                self.token_usage["llm_total_tokens"] += usage.get("total_tokens", 0)
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
        
        # Check token budget after updating usage
        self._check_token_budget()
        
        return result
    
    def _check_token_budget(self) -> None:
        """Check if cumulative token usage exceeds the configured budget.
        
        Raises:
            TokenBudgetExceeded: If total tokens used exceeds max_token_budget.
        """
        if self.max_token_budget is None:
            return
        
        total_used = (
            self.token_usage["llm_total_tokens"]
            + self.token_usage["embedding_total_tokens"]
            + self.token_usage["reranker_input_tokens"]
            + self.token_usage["reranker_output_tokens"]
        )
        
        if total_used > self.max_token_budget:
            msg = (f"Token budget exceeded: used {total_used} tokens, "
                   f"budget is {self.max_token_budget} tokens")
            print(f"WARNING: {msg}")
            if self.logger:
                self.logger.warning(msg)
            raise TokenBudgetExceeded(self.max_token_budget, total_used)
    
    def _create_headers(self, api_key: str) -> dict:
        """Create standard API request headers."""
        return {
            "Content-Type": "application/json",
            "Authorization": f"Bearer {api_key}"
        }
    
    def chat_with_tools(self, 
                        messages: List[Dict[str, Any]],
                        tools: List[Dict[str, Any]],
                        max_tokens: int = 16384, 
                        temperature: float = 0.5) -> Dict[str, Any]:
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
        }

        # Count tokens in all messages
        prompt_tokens = sum(count_tokens(str(m.get("content", ""))) for m in messages)
        print(f'LLM chat_with_tools request: {prompt_tokens} tokens and {len(tools)} tools')

        result = self._make_api_request(
            self.llm_url,
            self._create_headers(self.api_key),
            payload,
            "llm"
        )

        # Parse response
        choice = result["choices"][0]
        message = choice["message"]
        
        if "tool_calls" in message and message["tool_calls"]:
            function_calls = [
                {
                    "name": tc["function"]["name"],
                    "arguments": json.loads(tc["function"]["arguments"]),
                    "id": tc["id"]
                }
                for tc in message["tool_calls"]
                if tc["type"] == "function"
            ]
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
        return self.token_usage.copy()
    
    def print_token_usage(self, logger: Optional[logging.Logger] = None) -> None:
        """
        Print a formatted summary of token usage statistics.
        """
        usage = self.get_token_usage()
        usage_summary = (
            "Token Usage Summary:\n",
            "=" * 40,
            f"LLM Calls: {usage['llm_calls']}",
            f"  Prompt Tokens: {usage['llm_prompt_tokens']}",
            f"  Completion Tokens: {usage['llm_completion_tokens']}",
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
        
        print("\n".join(usage_summary))
        if logger:
            logger.info("\n".join(usage_summary))
    
    def reset_token_usage(self) -> None:
        """
        Reset all token usage statistics to zero.
        """
        for key in self.token_usage:
            self.token_usage[key] = 0

    def get_total_tokens_used(self) -> int:
        """Return total tokens used across all API types (LLM + embedding + reranker)."""
        u = self.token_usage
        return (
            u.get("llm_total_tokens", 0)
            + u.get("embedding_total_tokens", 0)
            + u.get("reranker_input_tokens", 0)
            + u.get("reranker_output_tokens", 0)
        )

    def get_remaining_budget_pct(self) -> float:
        """Return remaining token budget as percentage (0.0–100.0).
        Returns 100.0 if no budget is set."""
        if self.max_token_budget is None or self.max_token_budget <= 0:
            return 100.0
        used = self.get_total_tokens_used()
        remaining = self.max_token_budget - used
        return max(0.0, remaining / self.max_token_budget * 100.0)


def get_tokenizer(model: str = "cl100k_base") -> tiktoken.Encoding:
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
    
    tokenizer = get_tokenizer(model)
    return [len(tokenizer.encode(text)) for text in texts]
