"""
TileLinkLLM Core Module

核心形式化验证工作流，包含 LLM 交互、工作流管理、提示词构建等。

核心组件：
- LLMClient: LLM API 客户端
- FormalWorkflow: 5 阶段形式化验证工作流
- PromptBuilder: 提示词构建器
- WaveformActions: 波形分析工具
- ToolSchemas: 工具定义和 JSON Schema
"""

from .llm_client import LLMClient, TokenBudgetExceeded
from .workflow import FormalWorkflow
from .prompt_builder import (
    build_system_prompt,
    build_user_prompt,
    build_tool_result_message,
    build_compilation_error_message,
)
from .waveform_actions import WaveformActions
from .tool_schemas import (
    FORMAL_STAGES,
    get_tool_schemas,
    convert_tool_call_to_action,
)

__all__ = [
    # LLM and workflow
    "LLMClient",
    "TokenBudgetExceeded",
    "FormalWorkflow",
    "build_system_prompt",
    "build_user_prompt",
    "build_tool_result_message",
    "build_compilation_error_message",
    "WaveformActions",
    "FORMAL_STAGES",
    "get_tool_schemas",
    "convert_tool_call_to_action",
]

__version__ = "0.3.0"
