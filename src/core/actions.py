from __future__ import annotations

"""
Action execution functions for formal verification workflow.
Handles file operations, compilation, and waveform analysis actions.
"""

import os
from typing import Dict, List, Any, Optional, Callable

try:
    from .waveform_actions import execute_waveform_action, WaveformActions
except ModuleNotFoundError:
    execute_waveform_action = None
    WaveformActions = Any
from ..causal_analysis import CausalAnalysisActions


def _coerce_file_paths(action: Dict[str, Any]) -> List[str]:
    """Accept common read_files path shapes without depending on model exactness."""
    file_paths = action.get("file_paths")
    if file_paths is None:
        file_paths = action.get("file_path")
    if file_paths is None:
        file_paths = action.get("paths")
    if file_paths is None:
        file_paths = action.get("path")
    if file_paths is None:
        file_paths = action.get("files", [])

    if isinstance(file_paths, str):
        return [file_paths]

    if not isinstance(file_paths, list):
        return []

    paths: List[str] = []
    for item in file_paths:
        if isinstance(item, str):
            paths.append(item)
        elif isinstance(item, dict):
            candidate = item.get("file_path") or item.get("path")
            if isinstance(candidate, str):
                paths.append(candidate)
    return paths


def _resolve_work_path(file_path: str, work_dir: str) -> str:
    """
    Resolve a tool-supplied path to the current benchmark work directory.

    File tools should be independent of the process cwd. Basenames and generated
    subpaths are rooted at extra_bench/<target>, while prompt-visible
    workspace-relative paths such as chisel/extra_bench/<target>/foo.scala or
    verilog/extra_bench/<target>/Main.sv are rooted at the workspace.
    """
    work_abs = os.path.abspath(work_dir)
    work_parent = os.path.dirname(work_abs)
    chisel_dir = os.path.dirname(work_parent)
    workspace_abs = os.path.dirname(chisel_dir)
    target = os.path.basename(work_abs)

    if os.path.isabs(file_path):
        abs_path = os.path.normpath(file_path)
        try:
            inside_extra_bench = os.path.commonpath([abs_path, work_parent]) == work_parent
        except ValueError:
            inside_extra_bench = False
        if inside_extra_bench:
            rel = os.path.relpath(abs_path, work_parent)
            parts = [part for part in rel.split(os.sep) if part and part != "."]
            if target in parts:
                target_idx = len(parts) - 1 - parts[::-1].index(target)
                tail = parts[target_idx + 1:]
                if tail:
                    return os.path.normpath(os.path.join(work_abs, *tail))
                return work_abs
        return abs_path

    normalized = os.path.normpath(file_path)
    parts = [part for part in normalized.split(os.sep) if part and part != "."]
    if parts:
        workspace_name = os.path.basename(workspace_abs)
        if parts[0] == workspace_name:
            return os.path.normpath(os.path.join(os.path.dirname(workspace_abs), *parts))
        if parts[0] in {"chisel", "verilog", "benchmark", "log", "VerilogCausalAnalysis"}:
            return os.path.normpath(os.path.join(workspace_abs, *parts))

    if target in parts:
        target_idx = len(parts) - 1 - parts[::-1].index(target)
        tail = parts[target_idx + 1:]
        if tail:
            return os.path.normpath(os.path.join(work_abs, *tail))
        return work_abs

    return os.path.normpath(os.path.join(work_abs, normalized))


def execute_stage_actions(
    actions: List[Dict[str, Any]], 
    work_dir: str,
    waveform_actions: Optional[WaveformActions],
    causal_actions: Optional[CausalAnalysisActions],
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
        causal_actions: CausalAnalysisActions instance (if causal JSON is available)
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

            elif action_type.startswith("causal_"):
                result = _execute_causal_action(action, causal_actions)
            
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
    file_paths = _coerce_file_paths(action)
    line_start = action.get("line_start")
    line_end = action.get("line_end")
    max_chars = action.get("max_chars")
    files_result = []
    
    for fp in file_paths:
        fp = _resolve_work_path(fp, work_dir)
        content = read_file_func(fp)
        success = "Error reading file" not in content
        display_content = None
        read_meta: Dict[str, Any] = {}
        if success:
            display_content, read_meta = _filter_read_content(
                content,
                line_start=line_start,
                line_end=line_end,
                max_chars=max_chars,
            )
        files_result.append({
            "file_path": fp,
            "content": display_content if success else None,
            "error": content if not success else None,
            "success": success,
            **read_meta,
        })
    
    return {
        "type": "read_files",
        "files": files_result,
        "success": all(f["success"] for f in files_result)
    }


def _filter_read_content(
    content: str,
    line_start: Optional[int] = None,
    line_end: Optional[int] = None,
    max_chars: Optional[int] = None,
) -> tuple[str, Dict[str, Any]]:
    """Apply optional line/char limits to read_files results."""
    original_chars = len(content)
    original_lines = content.count("\n") + (1 if content else 0)
    selected = content
    meta: Dict[str, Any] = {
        "original_chars": original_chars,
        "original_lines": original_lines,
        "truncated": False,
    }

    if line_start is not None or line_end is not None:
        start = max(int(line_start or 1), 1)
        end = int(line_end or original_lines)
        end = max(end, start)
        lines = content.splitlines()
        selected_lines = lines[start - 1:end]
        selected = "\n".join(
            f"{line_no}: {line}"
            for line_no, line in enumerate(selected_lines, start=start)
        )
        meta["line_start"] = start
        meta["line_end"] = min(end, original_lines)

    if max_chars is not None and max_chars > 0 and len(selected) > max_chars:
        selected = selected[:max_chars] + "\n... [truncated by max_chars]"
        meta["truncated"] = True
        meta["returned_chars"] = len(selected)
    else:
        meta["returned_chars"] = len(selected)

    return selected, meta


def _execute_write_file(action: Dict[str, Any], work_dir: str, write_file_func) -> Dict[str, Any]:
    """Execute write_file action."""
    content = action.get("content", "")
    file_path = action.get("file_path", "")
    
    file_path = _resolve_work_path(file_path, work_dir)
    
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
    file_path = _resolve_work_path(file_path, work_dir)
    
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
    
    file_path = _resolve_work_path(file_path, work_dir)
    
    ok, err = write_file_func(file_path, content)
    
    round_summary_path = None

    if action.get("round_summary"):
        round_summary = action.get("round_summary", "")
        summary_path = os.path.join(work_dir, "repair_round_summary.md")
        try:
            with open(summary_path, 'w', encoding='utf-8') as f:
                f.write(round_summary)
            round_summary_path = summary_path
            logger.info(f"Repair round summary written to: {summary_path}")
        except Exception as e:
            logger.warning(f"Failed to write repair round summary: {e}")
    
    return {
        "type": "write_fix",
        "file_path": file_path,
        "round_summary_path": round_summary_path,
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
    if waveform_actions and execute_waveform_action is not None:
        return execute_waveform_action(action, waveform_actions)
    else:
        return {
            "type": action.get("type", ""),
            "error": "Waveform actions not available",
            "success": False
        }


def _execute_causal_action(
    action: Dict[str, Any],
    causal_actions: Optional[CausalAnalysisActions]
) -> Dict[str, Any]:
    """Execute causal-analysis JSON query actions."""
    if causal_actions:
        return causal_actions.execute(action)
    return {
        "type": action.get("type", ""),
        "error": "Causal analysis JSON is not available for this waveform",
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
    
    harness_file_abs = _resolve_work_path(harness_file, work_dir)
    
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
