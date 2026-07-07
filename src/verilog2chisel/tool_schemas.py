"""
Tool schemas for Verilog to Chisel conversion workflow.
Only provides write_files tool for LLM to generate Chisel code.
"""

from typing import Dict, List, Any


def get_verilog2chisel_tool_schemas() -> List[Dict[str, Any]]:
    """
    Get tool schemas for Verilog to Chisel conversion.
    
    Returns:
        List containing only the write_files tool schema
    """
    return [
        {
            "name": "write_files",
            "description": (
                "Write Chisel files to the chisel directory. "
                "Write at most 3 Scala files; combine multiple modules when needed. "
                "The generated Chisel code should be syntactically correct and compilable."
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "files": {
                        "type": "array",
                        "minItems": 1,
                        "maxItems": 3,
                        "items": {
                            "type": "object",
                            "properties": {
                                "file_path": {
                                    "type": "string",
                                    "description": "Relative .scala path under chisel/ directory (e.g., 'Zero.scala')"
                                },
                                "content": {
                                    "type": "string",
                                    "maxLength": 80000,
                                    "description": "Complete Scala/Chisel source code"
                                }
                            },
                            "required": ["file_path", "content"]
                        },
                        "description": "List of Chisel files to write"
                    },
                    "stage_complete": {
                        "type": "boolean",
                        "description": "Set to true when all Verilog files have been converted",
                        "default": False
                    }
                },
                "required": ["files", "stage_complete"]
            }
        }
    ]


def convert_tool_call_to_action(tool_call: Dict[str, Any]) -> Dict[str, Any]:
    """
    Convert LLM tool call to internal action format.
    
    Args:
        tool_call: Tool call from LLM with 'name' and 'arguments'
        
    Returns:
        Action dictionary for workflow execution
    """
    action = {
        "tool": tool_call["name"],
        "arguments": tool_call["arguments"]
    }
    return action
