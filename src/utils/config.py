"""
Runtime configuration & secret loading.

API keys are read exclusively from:
    1. Process environment variables, OR
    2. A `.env` file at the project root (loaded on first access).

A bare-bones `.env` parser is shipped here so the project has ZERO hard
dependency on `python-dotenv`; if `python-dotenv` *is* installed we use it
for better parity with tooling conventions.

Supported variable names (in order of precedence):

    CHISELLMFV_LLM_API_KEY, CHISELLMFV_EMBEDDING_API_KEY, CHISELLMFV_RERANKER_API_KEY
    LLM_API_KEY,            EMBEDDING_API_KEY,             RERANKER_API_KEY

Endpoint / model overrides (optional — otherwise the defaults in
`llm_properties.py` are used):

    CHISELLMFV_LLM_URL, CHISELLMFV_LLM_BASE_URL, CHISELLMFV_LLM_MODEL, ...
    CHISELLMFV_LLM_EXTRA_BODY (JSON object)
    CHISELLMFV_ENABLE_PROMPT_CACHE_KEY (true/false; OpenAI defaults to true)
"""

from __future__ import annotations

import os
from pathlib import Path
from typing import Dict, Optional


_PROJECT_ROOT = Path(__file__).resolve().parents[2]
_ENV_FILE = _PROJECT_ROOT / ".env"
_ENV_LOADED = False


def _load_env_file(path: Path) -> None:
    """Populate os.environ from a `.env` file without overwriting existing values.

    Syntax supported: `KEY=VALUE`, comments starting with `#`, blank lines,
    optional single/double quoting, optional `export ` prefix.
    """
    global _ENV_LOADED
    if _ENV_LOADED:
        return
    _ENV_LOADED = True

    if not path.is_file():
        return

    # Prefer python-dotenv when available for wider compatibility.
    try:
        from dotenv import load_dotenv  # type: ignore

        load_dotenv(dotenv_path=str(path), override=False)
        return
    except ImportError:
        pass

    try:
        text = path.read_text(encoding="utf-8")
    except OSError:
        return

    for raw in text.splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        if line.startswith("export "):
            line = line[len("export "):].lstrip()
        if "=" not in line:
            continue
        key, _, value = line.partition("=")
        key = key.strip()
        value = value.strip()
        # Strip surrounding quotes
        if len(value) >= 2 and value[0] == value[-1] and value[0] in ("'", '"'):
            value = value[1:-1]
        if key and key not in os.environ:
            os.environ[key] = value


def _env_first(*names: str) -> Optional[str]:
    """Return the first non-empty environment variable among *names*."""
    for name in names:
        val = os.environ.get(name)
        if val:
            return val
    return None


def load_env_once(dotenv_path: Optional[Path] = None) -> None:
    """Public hook if callers want to trigger .env loading explicitly."""
    _load_env_file(dotenv_path or _ENV_FILE)


def get_llm_credentials() -> Dict[str, str]:
    """Return the LLM / embedding / reranker API keys.

    Missing keys are returned as empty strings — `LLMClient` will raise a
    clear error if it is actually asked to make a request without one.
    """
    _load_env_file(_ENV_FILE)

    return {
        "llm": _env_first("CHISELLMFV_LLM_API_KEY", "LLM_API_KEY") or "",
        "embedding": _env_first(
            "CHISELLMFV_EMBEDDING_API_KEY", "EMBEDDING_API_KEY"
        ) or "",
        "reranker": _env_first(
            "CHISELLMFV_RERANKER_API_KEY", "RERANKER_API_KEY"
        ) or "",
    }


def get_endpoint_overrides() -> Dict[str, Optional[str]]:
    """Return optional endpoint / model overrides from the environment."""
    _load_env_file(_ENV_FILE)
    return {
        "llm_url": _env_first("CHISELLMFV_LLM_URL"),
        "llm_base_url": _env_first("CHISELLMFV_LLM_BASE_URL"),
        "llm_model": _env_first("CHISELLMFV_LLM_MODEL"),
        "llm_extra_body": _env_first("CHISELLMFV_LLM_EXTRA_BODY"),
        "enable_prompt_cache_key": _env_first("CHISELLMFV_ENABLE_PROMPT_CACHE_KEY"),
        "embedding_url": _env_first("CHISELLMFV_EMBEDDING_URL"),
        "embedding_model": _env_first("CHISELLMFV_EMBEDDING_MODEL"),
        "reranker_url": _env_first("CHISELLMFV_RERANKER_URL"),
        "reranker_model": _env_first("CHISELLMFV_RERANKER_MODEL"),
    }


__all__ = [
    "get_llm_credentials",
    "get_endpoint_overrides",
    "load_env_once",
]
