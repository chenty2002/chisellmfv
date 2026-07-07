"""
Action execution functions for Verilog to Chisel conversion workflow.
"""

import os
from typing import Dict, Any, List


def execute_action(action: Dict[str, Any], write_chisel_file_func) -> Dict[str, Any]:
    """
    Execute a single action from LLM for Verilog to Chisel conversion.
    
    Args:
        action: Action dictionary with 'tool' and 'arguments'
        write_chisel_file_func: Function to write Chisel files
        
    Returns:
        Result dictionary
    """
    tool = action["tool"]
    args = action["arguments"]
    
    if tool == "write_files":
        return _execute_write_files(args, write_chisel_file_func)
    else:
        return {
            "tool": tool,
            "success": False,
            "error": f"Unknown tool: {tool}"
        }


def _execute_write_files(args: Dict[str, Any], write_chisel_file_func) -> Dict[str, Any]:
    """
    Execute write_files action to generate Chisel code files.
    
    Args:
        args: Arguments containing files list
        write_chisel_file_func: Function to write Chisel files
        
    Returns:
        Result dictionary with success status and generated files
    """
    files = args.get("files", [])
    validation_errors = _validate_files(files)
    if validation_errors:
        return {
            "tool": "write_files",
            "success": False,
            "error": "; ".join(validation_errors),
            "results": [],
            "stage_complete": False,
            "generated_files": [],
        }

    results = []
    generated_files = []
    
    for file_info in files:
        file_path = file_info["file_path"]
        content = file_info["content"]
        success, message = write_chisel_file_func(file_path, content)
        results.append({
            "file": file_path,
            "success": success,
            "message": message
        })
        # Save content for potential error feedback
        if success:
            generated_files.append({
                "file_path": file_path,
                "char_count": len(content),
            })
    
    return {
        "tool": "write_files",
        "success": all(r["success"] for r in results),
        "results": results,
        "stage_complete": args.get("stage_complete", False),
        "generated_files": generated_files
    }


def _validate_files(files: Any) -> List[str]:
    errors: List[str] = []
    if not isinstance(files, list) or not files:
        return ["files must be a non-empty list"]
    if len(files) > 3:
        errors.append("write_files accepts at most 3 Scala files")
    for index, file_info in enumerate(files):
        if not isinstance(file_info, dict):
            errors.append(f"files[{index}] must be an object")
            continue
        file_path = file_info.get("file_path")
        content = file_info.get("content")
        if not isinstance(file_path, str) or not file_path:
            errors.append(f"files[{index}].file_path must be a non-empty string")
            continue
        normalized = os.path.normpath(file_path)
        if os.path.isabs(file_path) or normalized.startswith("..") or ".." in normalized.split(os.sep):
            errors.append(f"unsafe file path: {file_path}")
        if not file_path.endswith(".scala"):
            errors.append(f"file path must end with .scala: {file_path}")
        if not isinstance(content, str):
            errors.append(f"files[{index}].content must be a string")
        elif len(content) > 80000:
            errors.append(f"file content exceeds 80000 characters: {file_path}")
    return errors
