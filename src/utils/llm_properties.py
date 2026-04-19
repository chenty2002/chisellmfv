"""
Non-sensitive default configuration for ChiselLMFV.

API keys and other secrets MUST NOT live in this file. They are resolved at
runtime from environment variables (see `src/utils/config.py`). To configure
credentials locally, either:

  1. Export env vars directly:  `export CHISELLMFV_LLM_API_KEY=...`
  2. Populate a `.env` file at the project root (see `.env.example`).

This file is safe to commit and share publicly.
"""

# ---------------------------------------------------------------------------
# LLM API defaults (endpoints, model names) — NOT secret
# ---------------------------------------------------------------------------
LLM_URL = "https://api.siliconflow.cn/v1/chat/completions"
LLM_MODEL = "Pro/zai-org/GLM-4.7"

EMBEDDING_URL = "https://api.siliconflow.cn/v1/embeddings"
EMBEDDING_MODEL = "Qwen/Qwen3-Embedding-8B"

RERANKER_URL = "https://api.siliconflow.cn/v1/rerank"
RERANKER_MODEL = "Qwen/Qwen3-Reranker-8B"

# ---------------------------------------------------------------------------
# Secrets resolved at import time from environment / .env
# ---------------------------------------------------------------------------
# Importing here keeps backward compatibility: existing code does
# `from ..utils.llm_properties import *` and receives the loaded keys.
from .config import get_llm_credentials  # noqa: E402  (circular-safe)

_creds = get_llm_credentials()
LLM_API_KEY = _creds["llm"]
EMBEDDING_API_KEY = _creds["embedding"]
RERANKER_API_KEY = _creds["reranker"]
del _creds, get_llm_credentials

# ---------------------------------------------------------------------------
# Vector / embedding configuration
# ---------------------------------------------------------------------------
KNOWLEDGE_BASE_DIR = "knowledge_base"
VECTOR_DB_PATH = "knowledge_base.db"
VECTOR_INDEX_PATH = "faiss_index.bin"
EMBEDDING_DIMENSION = 4096  # Dimension of embedding vectors
BATCH_SIZE = 32

# ---------------------------------------------------------------------------
# Document / workflow processing
# ---------------------------------------------------------------------------
CHUNK_SIZE = 2048
CHUNK_OVERLAP = 256

LOG_PATH = "log"

# Maximum iterations for different stages
MAX_ITERATIONS = 10         # Maximum iterations for most stages
WAVEFORM_MAX_ITER = 50      # Maximum iterations for waveform_explanation stage
