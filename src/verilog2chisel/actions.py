"""
Action execution functions for Verilog to Chisel conversion workflow.
"""

from typing import Dict, Any


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
    results = []
    generated_files = {}  # Track generated files for potential retry
    
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
            generated_files[file_path] = content
    
    return {
        "tool": "write_files",
        "success": all(r["success"] for r in results),
        "results": results,
        "stage_complete": args.get("stage_complete", False),
        "generated_files": generated_files
    }
