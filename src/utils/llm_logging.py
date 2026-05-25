"""
Common utilities for logging LLM requests and responses.
Reduces code duplication across workflow modules.
"""

import json
from typing import Dict, List, Any, Optional

try:
    import tiktoken
except ImportError:  # pragma: no cover - exercised only in minimal envs
    tiktoken = None


_tokenizer_cache: Dict[str, Any] = {}


def count_tokens(text: str, model: str = "cl100k_base") -> int:
    """Estimate tokens for request logging without importing the full core package."""
    if not text:
        return 0
    if tiktoken is None:
        return max(1, len(text) // 4)

    if model not in _tokenizer_cache:
        try:
            _tokenizer_cache[model] = tiktoken.get_encoding(model)
        except Exception:
            _tokenizer_cache[model] = tiktoken.get_encoding("cl100k_base")
    return len(_tokenizer_cache[model].encode(text))


class LLMLogger:
    """Helper class for formatted LLM request/response logging."""
    
    SEPARATOR = "=" * 80
    
    @staticmethod
    def format_request(
        prompt: str,
        tool_schemas: List[Dict[str, Any]],
        stage: Optional[str] = None,
        iteration: Optional[int] = None,
        include_details: bool = True,
        dynamic_messages: Optional[List[Dict[str, Any]]] = None,
    ) -> str:
        """
        Format LLM request for logging.
        
        Args:
            prompt: The prompt being sent
            tool_schemas: Available tools
            stage: Optional workflow stage name
            iteration: Optional iteration number
            include_details: Whether to include full tool schemas and prompt
            dynamic_messages: Non-static messages added after the initial prompt,
                such as tool results and retry/error feedback.
            
        Returns:
            Formatted log message
        """
        tool_names = [t.get("name", "unknown") for t in tool_schemas]
        tools_json = json.dumps(tool_schemas, ensure_ascii=False, indent=2)
        
        # Build header
        header_parts = ["LLM REQUEST"]
        if stage:
            header_parts.append(f"Stage: {stage}")
        if iteration is not None:
            header_parts.append(f"Iteration: {iteration}")
        header = " - ".join(header_parts)
        
        # Calculate token counts
        prompt_tokens = count_tokens(prompt)
        tools_tokens = count_tokens(tools_json)
        
        if include_details:
            detail_block = f"""
[Tool Schemas]
{tools_json}

[Full Prompt]
{prompt}
"""
        else:
            dynamic_block = LLMLogger._format_dynamic_messages(dynamic_messages or [])
            detail_block = (
                "Detailed tool schemas and prompt omitted; see iteration 1 for "
                "this stage.\n\n"
                "[Dynamic Conversation Context]\n"
                f"{dynamic_block}\n"
            )

        log_msg = f"""
{LLMLogger.SEPARATOR}
{header}
Prompt Tokens: {prompt_tokens} ({len(prompt)} chars)
Tools Tokens: {tools_tokens}
Available Tools: {', '.join(tool_names)}

{detail_block}
{LLMLogger.SEPARATOR}
"""
        return log_msg

    @staticmethod
    def _format_dynamic_messages(messages: List[Dict[str, Any]]) -> str:
        """Format non-static conversation messages for compact request logs."""
        if not messages:
            return "(none)"

        blocks = []
        for index, message in enumerate(messages, start=1):
            role = message.get("role", "unknown")
            header_bits = [f"[{index}] role={role}"]
            if message.get("name"):
                header_bits.append(f"name={message['name']}")
            if message.get("tool_call_id"):
                header_bits.append(f"tool_call_id={message['tool_call_id']}")
            header = " ".join(header_bits)

            content = message.get("content")
            if content is None:
                content_text = ""
            elif isinstance(content, str):
                content_text = content
            else:
                content_text = json.dumps(content, ensure_ascii=False, separators=(",", ":"))

            tool_calls = message.get("tool_calls")
            if tool_calls:
                tool_calls_text = json.dumps(
                    tool_calls,
                    ensure_ascii=False,
                    indent=2,
                )
                if content_text:
                    blocks.append(f"{header}\n[Content]\n{content_text}\n[Tool Calls]\n{tool_calls_text}")
                else:
                    blocks.append(f"{header}\n[Tool Calls]\n{tool_calls_text}")
            else:
                blocks.append(f"{header}\n{content_text}")

        return "\n\n".join(blocks)
    
    @staticmethod
    def format_response(
        response: Dict[str, Any],
        stage: Optional[str] = None,
        iteration: Optional[int] = None,
        truncate_content: bool = True,
        max_content_length: int = 200
    ) -> str:
        """
        Format LLM response for logging.
        
        Args:
            response: The LLM response
            stage: Optional workflow stage name
            iteration: Optional iteration number
            truncate_content: Whether to truncate long content
            max_content_length: Max length before truncation
            
        Returns:
            Formatted log message
        """
        # Build header
        header_parts = ["LLM RESPONSE"]
        if stage:
            header_parts.append(f"Stage: {stage}")
        if iteration is not None:
            header_parts.append(f"Iteration: {iteration}")
        header = " - ".join(header_parts)
        
        response_type = response.get("type", "unknown")
        
        if response_type == "function_calls":
            function_calls = response.get("function_calls", [])
            calls_info = []
            
            for fc in function_calls:
                name = fc.get("name", "unknown")
                args = fc.get("arguments", {})
                
                if truncate_content:
                    # Truncate long string values
                    args_display = {}
                    for k, v in args.items():
                        if isinstance(v, str) and len(v) > max_content_length:
                            args_display[k] = f"{v[:max_content_length]}... (total {len(v)} chars)"
                        elif isinstance(v, list) and k == "files":
                            args_display[k] = f"[{len(v)} files]"
                            for i, file_info in enumerate(v):
                                if isinstance(file_info, dict):
                                    file_path = file_info.get("file_path", "unknown")
                                    content_len = len(file_info.get("content", ""))
                                    args_display[f"  file_{i}"] = f"{file_path} ({content_len} chars)"
                        else:
                            args_display[k] = v
                    args_json = json.dumps(args_display, ensure_ascii=False, indent=4)
                else:
                    args_json = json.dumps(args, ensure_ascii=False, indent=4)
                
                calls_info.append(f"  • {name}:\n{args_json}")

            calls_block = "\n".join(calls_info)
            log_msg = f"""
{LLMLogger.SEPARATOR}
{header}
Response Type: function_calls
Function Calls ({len(function_calls)}):

{calls_block}
{LLMLogger.SEPARATOR}
"""
        else:
            content = response.get("content", "")
            content_tokens = count_tokens(content)
            if truncate_content and len(content) > max_content_length * 3:
                content = f"{content[:max_content_length * 3]}... (total {len(content)} chars)"
            
            log_msg = f"""
{LLMLogger.SEPARATOR}
{header}
Response Type: {response_type}
Content Tokens: {content_tokens} ({len(response.get('content', ''))} chars)

[Content]
{content}
{LLMLogger.SEPARATOR}
"""
        
        return log_msg
    
    @staticmethod
    def filter_scala_code_in_prompt(prompt: str) -> str:
        """
        Filter out scala code blocks between '## Key Source Files' and '## Task' sections.
        
        Args:
            prompt: The original prompt
            
        Returns:
            Filtered prompt with scala code blocks replaced by placeholders
        """
        import re
        
        # Find the Key Source Files section
        key_source_start = prompt.find("## Key Source Files")
        task_start = prompt.find("## Task")
        
        if key_source_start == -1 or task_start == -1 or key_source_start >= task_start:
            return prompt
        
        # Extract sections
        before_section = prompt[:key_source_start]
        source_section = prompt[key_source_start:task_start]
        after_section = prompt[task_start:]
        
        # Replace ```scala ... ``` blocks
        filtered_section = re.sub(
            r'```scala\n.*?```\n',
            '',
            source_section,
            flags=re.DOTALL
        )
        
        return before_section + filtered_section + after_section
