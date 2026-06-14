from __future__ import annotations

"""
Action execution functions for formal verification workflow.
Handles file operations, compilation, and waveform analysis actions.
"""

import os
import json
import subprocess
from pathlib import Path
from typing import Dict, List, Any, Optional, Callable

from .waveform_actions import execute_waveform_action, WaveformActions
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


def _resolve_work_path(file_path: str, work_dir: str, workspace_root: Optional[str] = None) -> str:
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

    if workspace_root:
        return str(_resolve_workspace_path(file_path, workspace_root, work_dir))

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


def _resolve_workspace_path(path: str, workspace_root: str, work_dir: Optional[str] = None) -> Path:
    """Resolve a model path inside the run workspace and reject escapes."""
    root = Path(workspace_root).resolve()
    work = Path(work_dir).resolve() if work_dir else root
    raw = Path(path or ".")
    if raw.is_absolute():
        candidate = raw.resolve()
    else:
        first = raw.parts[0] if raw.parts else ""
        if first in {"workspace", "case", "skills", "rules", "memories", "indexes", "results", "logs"}:
            candidate = (root / raw).resolve()
            if first == "workspace" and not (root / "workspace").exists():
                candidate = (root / Path(*raw.parts[1:])).resolve()
        else:
            candidate = (work / raw).resolve()
    if candidate != root and root not in candidate.parents:
        raise ValueError(f"path escapes workspace: {path}")
    return candidate


def _workspace_relative(path: Path, workspace_root: str) -> str:
    return path.resolve().relative_to(Path(workspace_root).resolve()).as_posix()


def _with_md_suffix(name: str) -> str:
    path = Path(name)
    return path.with_suffix(".md").as_posix() if not path.suffix else path.as_posix()


def execute_stage_actions(
    actions: List[Dict[str, Any]], 
    work_dir: str,
    waveform_actions: Optional[WaveformActions],
    causal_actions: Optional[CausalAnalysisActions],
    read_file_func: Callable,
    write_file_func: Callable,
    logger,
    reset_stage_func: Optional[Callable] = None,
    workspace_root: Optional[str] = None,
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
                result = _execute_read_files(action, work_dir, read_file_func, workspace_root)

            elif action_type == "list_files":
                result = _execute_list_files(action, work_dir, workspace_root)

            elif action_type == "rg":
                result = _execute_rg(action, work_dir, workspace_root)

            elif action_type == "read_skill":
                result = _execute_read_asset(action, work_dir, workspace_root, "skills")

            elif action_type == "read_rule":
                result = _execute_read_asset(action, work_dir, workspace_root, "rules")

            elif action_type == "read_memory":
                result = _execute_read_asset(action, work_dir, workspace_root, "memories")

            elif action_type == "write_memory":
                result = _execute_write_memory(action, work_dir, workspace_root)
            
            elif action_type == "confirm_existing_harness":
                result = _execute_confirm_existing_harness(action, work_dir, logger)
            
            elif action_type == "write_file":
                result = _execute_write_file(action, work_dir, write_file_func, workspace_root)
            
            elif action_type == "write_assertions":
                result = _execute_write_assertions(action, work_dir, write_file_func, workspace_root)
            
            elif action_type == "write_fix":
                result = _execute_write_fix(action, work_dir, write_file_func, logger, workspace_root)
            
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


def _execute_read_files(
    action: Dict[str, Any],
    work_dir: str,
    read_file_func,
    workspace_root: Optional[str] = None,
) -> Dict[str, Any]:
    """Execute read_files action."""
    file_paths = _coerce_file_paths(action)
    line_start = action.get("line_start")
    line_end = action.get("line_end")
    max_chars = action.get("max_chars")
    files_result = []
    
    for fp in file_paths:
        try:
            fp = _resolve_work_path(fp, work_dir, workspace_root)
            content = read_file_func(fp)
            success = "Error reading file" not in content
        except Exception as exc:
            files_result.append({
                "file_path": fp,
                "content": None,
                "error": str(exc),
                "success": False,
            })
            continue
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


def _execute_list_files(
    action: Dict[str, Any],
    work_dir: str,
    workspace_root: Optional[str],
) -> Dict[str, Any]:
    if not workspace_root:
        return {"type": "list_files", "success": False, "error": "workspace tools are not available"}
    root = _resolve_workspace_path(action.get("path", "."), workspace_root, work_dir)
    pattern = action.get("pattern") or "*"
    if not root.exists():
        return {"type": "list_files", "success": False, "error": f"path does not exist: {action.get('path', '.')}"}
    files = root.rglob(pattern) if root.is_dir() else [root]
    items = [
        {"path": _workspace_relative(path, workspace_root), "bytes": path.stat().st_size}
        for path in files
        if path.is_file()
    ]
    return {"type": "list_files", "success": True, "files": sorted(items, key=lambda item: item["path"])}


def _execute_rg(
    action: Dict[str, Any],
    work_dir: str,
    workspace_root: Optional[str],
) -> Dict[str, Any]:
    if not workspace_root:
        return {"type": "rg", "success": False, "error": "workspace tools are not available"}
    pattern = action.get("pattern") or ""
    if not pattern:
        return {"type": "rg", "success": False, "error": "pattern cannot be empty"}
    root = _resolve_workspace_path(action.get("path", "."), workspace_root, work_dir)
    argv = ["rg", "--json", pattern, str(root)]
    if action.get("glob"):
        argv[1:1] = ["-g", str(action["glob"])]
    try:
        completed = subprocess.run(
            argv,
            cwd=workspace_root,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            timeout=30,
            check=False,
        )
    except FileNotFoundError:
        return {"type": "rg", "success": False, "error": "rg is not available"}
    if completed.returncode == 1:
        return {"type": "rg", "success": True, "matches": []}
    if completed.returncode != 0:
        return {"type": "rg", "success": False, "error": completed.stdout[-4000:]}
    max_matches = max(1, int(action.get("max_matches") or 100))
    matches = []
    for line in completed.stdout.splitlines():
        event = json.loads(line)
        if event.get("type") != "match":
            continue
        data = event["data"]
        path = Path(data["path"]["text"]).resolve()
        matches.append({
            "path": _workspace_relative(path, workspace_root),
            "line": data["line_number"],
            "text": data["lines"]["text"].rstrip("\n"),
        })
        if len(matches) >= max_matches:
            break
    return {"type": "rg", "success": True, "matches": matches}


def _execute_read_asset(
    action: Dict[str, Any],
    work_dir: str,
    workspace_root: Optional[str],
    subdir: str,
) -> Dict[str, Any]:
    if not workspace_root:
        return {"type": f"read_{subdir[:-1]}", "success": False, "error": "workspace tools are not available"}
    name = action.get("name") or ("project.md" if subdir == "memories" else "index.md")
    rel = Path(subdir) / _with_md_suffix(str(name))
    try:
        path = _resolve_workspace_path(rel.as_posix(), workspace_root, work_dir)
        content = path.read_text(encoding="utf-8")
        return {"type": f"read_{subdir[:-1]}", "success": True, "path": _workspace_relative(path, workspace_root), "content": content}
    except Exception as exc:
        return {"type": f"read_{subdir[:-1]}", "success": False, "error": str(exc)}


def _execute_write_memory(
    action: Dict[str, Any],
    work_dir: str,
    workspace_root: Optional[str],
) -> Dict[str, Any]:
    if not workspace_root:
        return {"type": "write_memory", "success": False, "error": "workspace tools are not available"}
    try:
        rel = Path("memories") / _with_md_suffix(str(action.get("name") or "project.md"))
        path = _resolve_workspace_path(rel.as_posix(), workspace_root, work_dir)
        path.parent.mkdir(parents=True, exist_ok=True)
        mode = "a" if action.get("append") else "w"
        with path.open(mode, encoding="utf-8") as handle:
            handle.write(action.get("content", ""))
        return {"type": "write_memory", "success": True, "path": _workspace_relative(path, workspace_root), "bytes": path.stat().st_size}
    except Exception as exc:
        return {"type": "write_memory", "success": False, "error": str(exc)}


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


def _execute_write_file(
    action: Dict[str, Any],
    work_dir: str,
    write_file_func,
    workspace_root: Optional[str] = None,
) -> Dict[str, Any]:
    """Execute write_file action."""
    content = action.get("content", "")
    file_path = action.get("file_path", "")
    
    try:
        file_path = (
            str(_resolve_workspace_path(file_path, work_dir, work_dir))
            if workspace_root
            else _resolve_work_path(file_path, work_dir)
        )
    except Exception as exc:
        return {"type": "write_file", "file_path": file_path, "success": False, "error": str(exc)}
    
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
    write_file_func,
    workspace_root: Optional[str] = None,
) -> Dict[str, Any]:
    """Execute write_assertions action."""
    content = action.get("content", "")
    
    file_path = action.get("file_path", "Main.scala")
    try:
        file_path = (
            str(_resolve_workspace_path(file_path, work_dir, work_dir))
            if workspace_root
            else _resolve_work_path(file_path, work_dir)
        )
    except Exception as exc:
        return {"type": "write_assertions", "file_path": file_path, "success": False, "error": str(exc)}
    
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
    logger,
    workspace_root: Optional[str] = None,
) -> Dict[str, Any]:
    """Execute write_fix action."""
    file_path = action.get("file_path", "")
    content = action.get("content", "")
    
    try:
        file_path = (
            str(_resolve_workspace_path(file_path, work_dir, work_dir))
            if workspace_root
            else _resolve_work_path(file_path, work_dir)
        )
    except Exception as exc:
        return {"type": "write_fix", "file_path": file_path, "success": False, "error": str(exc)}
    
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
