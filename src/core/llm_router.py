from __future__ import annotations

import logging
from typing import Any, Dict, Optional

from .llm_client import LLMClient
from ..utils.config import get_endpoint_overrides


FLASH_TASK_TYPES = {
    "context_compaction",
    "tool_result_compaction",
    "tool_result_summary",
    "retrieval_assist",
    "lint",
    "failure_preclassification",
}


class LLMRouter:
    """Route workflow LLM calls between PRO and FLASH model clients."""

    def __init__(
        self,
        *,
        logger: Optional[logging.Logger] = None,
        max_token_budget: Optional[int] = None,
        client_cls=LLMClient,
        **client_kwargs: Any,
    ):
        overrides = get_endpoint_overrides()
        default_model = overrides["llm_model"]
        pro_model = overrides["llm_model_pro"] or default_model
        flash_model = overrides["llm_model_flash"] or default_model

        common_kwargs = dict(client_kwargs)
        common_kwargs.setdefault("logger", logger)
        common_kwargs.setdefault("max_token_budget", max_token_budget)

        self.pro_client = client_cls(
            model=pro_model,
            model_role="pro",
            **common_kwargs,
        )
        self.flash_client = client_cls(
            model=flash_model,
            model_role="flash",
            **common_kwargs,
        )

    def choose(
        self,
        *,
        role: Optional[str] = None,
        stage: Optional[str] = None,
        task_type: Optional[str] = None,
    ) -> LLMClient:
        """Return the client for an explicit role or conservative task policy."""
        if role:
            normalized = role.strip().lower()
            if normalized == "flash":
                return self.flash_client
            if normalized == "pro":
                return self.pro_client
            raise ValueError(f"unknown model role: {role}")

        if task_type in FLASH_TASK_TYPES:
            return self.flash_client

        # Any stage loop that can write files or complete a stage stays on PRO.
        if stage in {
            "write_assertions",
            "invoke_verification",
            "waveform_explanation",
            "propose_bugfix",
        }:
            return self.pro_client

        return self.pro_client

    def chat_with_tools(
        self,
        *args: Any,
        role: Optional[str] = None,
        stage: Optional[str] = None,
        task_type: Optional[str] = None,
        usage_metadata: Optional[Dict[str, str]] = None,
        **kwargs: Any,
    ) -> Dict[str, Any]:
        client = self.choose(role=role, stage=stage, task_type=task_type)
        metadata = dict(usage_metadata or {})
        metadata.setdefault("model_role", client.model_role)
        return client.chat_with_tools(*args, usage_metadata=metadata, **kwargs)

    def get_token_usage(self) -> Dict[str, Any]:
        pro = self.pro_client.get_token_usage()
        flash = self.flash_client.get_token_usage()
        merged = dict(pro)

        for key, value in flash.items():
            if key == "llm_usage_by_key":
                continue
            if isinstance(value, int):
                merged[key] = int(merged.get(key, 0)) + value

        usage_by_key = dict(pro.get("llm_usage_by_key", {}))
        usage_by_key.update(flash.get("llm_usage_by_key", {}))
        merged["llm_usage_by_key"] = usage_by_key
        merged["model_roles"] = {
            "pro": self.pro_client.model,
            "flash": self.flash_client.model,
        }
        return merged

    def print_token_usage(
        self,
        logger: Optional[logging.Logger] = None,
        include_cache_breakdown: bool = True,
    ) -> None:
        if logger:
            logger.info("PRO model token usage:")
        self.pro_client.print_token_usage(logger, include_cache_breakdown)
        if logger:
            logger.info("FLASH model token usage:")
        self.flash_client.print_token_usage(logger, include_cache_breakdown)

    def reset_token_usage(self) -> None:
        self.pro_client.reset_token_usage()
        self.flash_client.reset_token_usage()
