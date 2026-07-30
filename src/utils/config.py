"""Load the single model configuration from the environment."""

from __future__ import annotations

import json
import os
from pathlib import Path
from typing import Any, Dict


_ENV_PATH = Path(__file__).resolve().parents[2] / ".env"
_LOADED = False


def _load_env() -> None:
    global _LOADED
    if _LOADED:
        return
    _LOADED = True
    if not _ENV_PATH.is_file():
        return
    for raw in _ENV_PATH.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        value = value.strip().strip("\"'")
        if key.strip() and key.strip() not in os.environ:
            os.environ[key.strip()] = value


def get_llm_settings() -> Dict[str, Any]:
    _load_env()
    extra_raw = os.environ.get("CHISELLMFV_LLM_EXTRA_BODY", "").strip()
    extra = json.loads(extra_raw) if extra_raw else {}
    if not isinstance(extra, dict):
        raise ValueError("CHISELLMFV_LLM_EXTRA_BODY must be a JSON object")
    return {
        "api_key": os.environ.get("CHISELLMFV_LLM_API_KEY", ""),
        "url": os.environ.get(
            "CHISELLMFV_LLM_URL",
            os.environ.get(
                "CHISELLMFV_LLM_BASE_URL",
                "https://api.siliconflow.cn/v1/chat/completions",
            ),
        ),
        "model": os.environ.get(
            "CHISELLMFV_LLM_MODEL",
            "Pro/zai-org/GLM-4.7",
        ),
        "extra_body": extra,
    }
