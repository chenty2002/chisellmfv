"""
TileLinkLLM Utils Module

通用工具函数，包括文件操作、日志配置、LLM 配置等。

核心组件：
- get_logger: 日志配置
- read_file: 文件读取
- write_file: 文件写入
- LLM 配置常量
"""

from .logger import get_logger, setup_logging
from .file_utils import read_file, write_file, remove_copyright_license_comments
from .llm_properties import (
    LLM_URL,
    LLM_MODEL,
    LLM_API_KEY,
    MAX_ITERATIONS,
    WAVEFORM_MAX_ITER,
    LOG_PATH,
)

__all__ = [
    "get_logger",
    "setup_logging",
    "read_file",
    "write_file",
    "remove_copyright_license_comments",
    "LLM_URL",
    "LLM_MODEL",
    "LLM_API_KEY",
    "MAX_ITERATIONS",
    "WAVEFORM_MAX_ITER",
    "LOG_PATH",
]

__version__ = "0.2.0"
