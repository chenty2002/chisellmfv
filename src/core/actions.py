"""
Action execution functions for formal verification workflow.
Handles file operations, compilation, and waveform analysis actions.
"""

import os
from typing import Dict, List, Any, Optional, Callable

from .waveform_actions import execute_waveform_action, WaveformActions


def execute_stage_actions(
    actions: List[Dict[str, Any]], 
    work_dir: str,
    waveform_actions: Optional[WaveformActions],
    read_file_func: Callable,
    write_file_func: Callable,
    logger,
    reset_stage_func: Optional[Callable] = None,
) -> List[Dict[str, Any]]:
    """
    Execute actions for the current stage.
    
    Args:
        actions: List of action dictionaries
        target: Verification target (benchmark name like 'gigamax')
        work_dir: Working directory for the target
        workspace_dir: Root workspace directory
        waveform_actions: WaveformActions instance (if available)
        read_file_func: Function to read files
        write_file_func: Function to write files
        logger: Logger instance
        reset_stage_func: Optional function to reset stage
        
    Returns:
        List of result dictionaries for each action
    """
    results = []
    
    for action in actions:
        action_type = action.get("type", "")
        result = {"type": action_type}
        
        try:
            if action_type == "read_files":
                result = _execute_read_files(action, work_dir, read_file_func)
            
            elif action_type == "confirm_existing_harness":
                result = _execute_confirm_existing_harness(action, work_dir, logger)
            
            elif action_type == "write_file":
                result = _execute_write_file(action, work_dir, write_file_func)
            
            elif action_type == "write_assertions":
                result = _execute_write_assertions(action, work_dir, write_file_func)
            
            elif action_type == "write_fix":
                result = _execute_write_fix(action, work_dir, write_file_func, logger)
            
            elif action_type == "write_report":
                result = _execute_write_report(action, work_dir, logger)
            
            elif action_type == "reset_stage":
                result = _execute_reset_stage(action, reset_stage_func, logger)
            
            elif action_type.startswith("waveform_"):
                result = _execute_waveform_action(action, waveform_actions)
            
            else:
                result["error"] = f"Unknown action type: {action_type}"
                result["success"] = False
                
        except Exception as e:
            result["error"] = str(e)
            result["success"] = False
        
        results.append(result)
    
    return results


def _execute_read_files(action: Dict[str, Any], work_dir: str, read_file_func) -> Dict[str, Any]:
    """Execute read_files action."""
    file_paths = action.get("file_paths", [])
    files_result = []
    
    for fp in file_paths:
        # For benchmark targets, convert relative path to absolute
        if not os.path.isabs(fp):
            fp = os.path.join(work_dir, fp)
        content = read_file_func(fp)
        success = "Error reading file" not in content
        files_result.append({
            "file_path": fp,
            "content": content if success else None,
            "error": content if not success else None,
            "success": success,
        })
    
    return {
        "type": "read_files",
        "files": files_result,
        "success": all(f["success"] for f in files_result)
    }


def _execute_write_file(action: Dict[str, Any], work_dir: str, write_file_func) -> Dict[str, Any]:
    """Execute write_file action."""
    content = action.get("content", "")
    file_path = action.get("file_path", "")
    
    # For benchmark targets, convert relative path to absolute
    if not os.path.isabs(file_path):
        file_path = os.path.join(work_dir, file_path)
    
    ok, err = write_file_func(file_path, content)
    
    return {
        "type": "write_file",
        "file_path": file_path,
        "success": ok,
        "error": err if not ok else None
    }


def _execute_write_assertions(
    action: Dict[str, Any], 
    work_dir: str, 
    write_file_func
) -> Dict[str, Any]:
    """Execute write_assertions action."""
    content = action.get("content", "")
    
    file_path = action.get("file_path", "Main.scala")
    if not os.path.isabs(file_path):
        file_path = os.path.join(work_dir, file_path)
    
    ok, err = write_file_func(file_path, content)
    
    return {
        "type": "write_assertions",
        "file_path": file_path,
        "success": ok,
        "error": err if not ok else None
    }

def _execute_write_fix(
    action: Dict[str, Any],
    work_dir: str, 
    write_file_func,
    logger
) -> Dict[str, Any]:
    """Execute write_fix action."""
    file_path = action.get("file_path", "")
    content = action.get("content", "")
    
    # For benchmark targets, convert relative path to absolute
    if not os.path.isabs(file_path):
        file_path = os.path.join(work_dir, file_path)
    
    ok, err = write_file_func(file_path, content)
    
    bugfix_report_path = None
    # For benchmark targets: write bugfix_report.md if bugfix_report is provided
    if action.get("bugfix_report"):
        bugfix_report = action.get("bugfix_report", "")
        report_path = os.path.join(work_dir, "bugfix_report.md")
        try:
            with open(report_path, 'w') as f:
                f.write(bugfix_report)
            bugfix_report_path = report_path
            logger.info(f"Bugfix report written to: {report_path}")
        except Exception as e:
            logger.warning(f"Failed to write bugfix report: {e}")
    
    return {
        "type": "write_fix",
        "file_path": file_path,
        "bugfix_report_path": bugfix_report_path,
        "success": ok,
        "error": err if not ok else None
    }


def _execute_write_report(
    action: Dict[str, Any], 
    work_dir: str,
    logger
) -> Dict[str, Any]:
    """Execute write_report action."""
    content = action.get("content", "")
    
    # Determine report path based on target
    full_path = os.path.join(work_dir, "counterexample_analysis.md")
    
    try:
        with open(full_path, 'w') as f:
            f.write(content)
        logger.info(f"Report written to: {full_path}")
        return {
            "type": "write_report",
            "file_path": full_path,
            "success": True
        }
    except Exception as e:
        return {
            "type": "write_report",
            "success": False,
            "error": str(e)
        }


def _execute_waveform_action(action: Dict[str, Any], waveform_actions: Optional[WaveformActions]) -> Dict[str, Any]:
    """Execute waveform-related action."""
    if waveform_actions:
        return execute_waveform_action(action, waveform_actions)
    else:
        return {
            "type": action.get("type", ""),
            "error": "Waveform actions not available",
            "success": False
        }


def _execute_confirm_existing_harness(
    action: Dict[str, Any],
    work_dir: str,
    logger
) -> Dict[str, Any]:
    """
    Execute confirm_existing_harness action.
    
    This action confirms that an existing verification harness is correct
    and does not need to be regenerated.
    """
    harness_file = action.get("harness_file", "")
    analysis = action.get("analysis", "")
    
    # Convert relative path to absolute for benchmark targets
    if not os.path.isabs(harness_file):
        harness_file_abs = os.path.join(work_dir, harness_file)
    else:
        harness_file_abs = harness_file
    
    # Verify the file exists
    if not os.path.exists(harness_file_abs):
        return {
            "type": "confirm_existing_harness",
            "success": False,
            "error": f"Harness file not found: {harness_file_abs}"
        }
    
    logger.info(f"Confirmed existing harness: {harness_file}")
    logger.info(f"Analysis: {analysis}")
    
    return {
        "type": "confirm_existing_harness",
        "harness_file": harness_file,
        "analysis": analysis,
        "success": True,
        "message": f"✓ Existing harness '{harness_file}' confirmed as correct"
    }

def _execute_reset_stage(
    action: Dict[str, Any],
    reset_stage_func: Optional[Callable],
    logger
) -> Dict[str, Any]:
    """
    Execute reset_stage action.
    
    Resets all files in work_dir to their initial state at stage start.
    """
    reason = action.get("reason", "No reason provided")
    issues_identified = action.get("issues_identified", [])
    
    if reset_stage_func is None:
        return {
            "type": "reset_stage",
            "success": False,
            "error": "Stage reset is not available for this target"
        }
    
    logger.info(f"Executing stage reset. Reason: {reason}")
    logger.info(f"Issues identified: {issues_identified}")
    
    result = reset_stage_func(reason)
    result["type"] = "reset_stage"
    result["issues_identified"] = issues_identified
    
    return result