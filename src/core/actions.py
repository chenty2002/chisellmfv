from __future__ import annotations

"""
Action execution functions for formal verification workflow.
Handles file operations, compilation, and waveform analysis actions.
"""

import os
import json
import subprocess
import difflib
import hashlib
from pathlib import Path
from typing import Dict, List, Any, Optional, Callable

try:
    from .waveform_actions import execute_waveform_action
except ModuleNotFoundError:
    execute_waveform_action = None
from ..causal_analysis import CausalAnalysisActions
from .records import normalize_tool_result


DEFAULT_READ_FILE_MAX_CHARS = 4000


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
    """Resolve a tool-supplied path inside the CoupledL2 workspace."""
    if workspace_root:
        return str(_resolve_workspace_path(file_path, workspace_root, work_dir))

    if os.path.isabs(file_path):
        return os.path.normpath(file_path)

    normalized = os.path.normpath(file_path)
    return os.path.normpath(os.path.join(os.path.abspath(work_dir), normalized))


def _resolve_workspace_path(path: str, workspace_root: str, work_dir: Optional[str] = None) -> Path:
    """Resolve a model path inside the run workspace and reject escapes."""
    root = Path(workspace_root).resolve()
    work = Path(work_dir).resolve() if work_dir else root
    raw = Path(path or ".")
    if any(part == ".." for part in raw.parts):
        raise ValueError(f"path escapes workspace: {path}")
    if raw.is_absolute():
        candidate = raw.resolve()
    else:
        first = raw.parts[0] if raw.parts else ""
        if first == "workspace":
            candidate = (root / raw).resolve()
            if not (root / "workspace").exists():
                candidate = (root / Path(*raw.parts[1:])).resolve()
        elif first in {"case", "skills", "rules", "memories"}:
            if (root / "workspace").is_dir() and not (root / first).exists():
                candidate = (root / "workspace" / raw).resolve()
            else:
                candidate = (root / raw).resolve()
        elif first in {"indexes", "results", "logs"}:
            candidate = (root / raw).resolve()
        else:
            candidate = (work / raw).resolve()
    if candidate != root and root not in candidate.parents:
        raise ValueError(f"path escapes workspace: {path}")
    return candidate


def _workspace_relative(path: Path, workspace_root: str) -> str:
    resolved = path.resolve()
    root = Path(workspace_root).resolve()
    workspace = root / "workspace"
    if workspace.is_dir() and (resolved == workspace or workspace in resolved.parents):
        return resolved.relative_to(workspace).as_posix()
    return resolved.relative_to(root).as_posix()


def _resolve_readable_path(path: str, workspace_root: str, work_dir: str) -> Path:
    resolved = _resolve_workspace_path(path, workspace_root, work_dir)
    if resolved.exists() or resolved.suffix:
        return resolved
    for suffix in (".json", ".jsonl", ".md"):
        candidate = resolved.with_suffix(suffix)
        if candidate.exists():
            return candidate
    return resolved


def _with_md_suffix(name: str) -> str:
    path = Path(name)
    return path.with_suffix(".md").as_posix() if not path.suffix else path.as_posix()


def execute_stage_actions(
    actions: List[Dict[str, Any]], 
    work_dir: str,
    waveform_actions: Optional[Any],
    causal_actions: Optional[CausalAnalysisActions],
    read_file_func: Callable,
    write_file_func: Callable,
    logger,
    workspace_root: Optional[str] = None,
) -> List[Dict[str, Any]]:
    """
    Execute actions for the current stage.
    
    Args:
        actions: List of action dictionaries
        work_dir: Working directory for the target
        waveform_actions: WaveformActions instance (if available)
        causal_actions: CausalAnalysisActions instance (if causal JSON is available)
        read_file_func: Function to read files
        write_file_func: Function to write files
        logger: Logger instance
        
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

            elif action_type == "edit_file":
                result = _execute_edit_file(action, work_dir, read_file_func, write_file_func, workspace_root)

            elif action_type == "complete_stage":
                result = _execute_complete_stage(action)
            
            elif action_type == "write_report":
                result = _execute_write_report(action, work_dir, logger)
            
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
        
        results.append(normalize_tool_result(result))
    
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
    if max_chars is None:
        max_chars = DEFAULT_READ_FILE_MAX_CHARS
    files_result = []
    
    for fp in file_paths:
        try:
            if workspace_root:
                fp = str(_resolve_readable_path(fp, workspace_root, work_dir))
            else:
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


def _sha256_text(content: str) -> str:
    return hashlib.sha256(content.encode("utf-8")).hexdigest()


def _line_count(content: str) -> int:
    return len(content.splitlines())


def _make_unified_diff(path: str, before: str, after: str) -> str:
    return "".join(
        difflib.unified_diff(
            before.splitlines(keepends=True),
            after.splitlines(keepends=True),
            fromfile=f"a/{path}",
            tofile=f"b/{path}",
        )
    )


def _normalize_insert_content(content: str) -> List[str]:
    if content == "":
        return []
    return content.splitlines(keepends=True)


def _replace_lines(
    before: str,
    line_start: Any,
    line_end: Any,
    replacement: str,
) -> str:
    lines = before.splitlines(keepends=True)
    try:
        start = int(line_start)
        end = int(line_end)
    except (TypeError, ValueError) as exc:
        raise ValueError("replace_range/delete_range require integer line_start and line_end") from exc
    if start < 1 or end < start or end > len(lines):
        raise ValueError(f"invalid line range: {line_start}-{line_end}")
    replacement_lines = replacement.splitlines(keepends=True)
    if replacement and not replacement.endswith("\n"):
        replacement_lines = replacement.splitlines(keepends=True)
    return "".join(lines[: start - 1] + replacement_lines + lines[end:])


def _insert_at_line(before: str, line_no: Any, content: str, *, before_line: bool) -> str:
    lines = before.splitlines(keepends=True)
    try:
        anchor = int(line_no)
    except (TypeError, ValueError) as exc:
        raise ValueError("insert_before/insert_after require line_start or unique old_text") from exc
    if anchor < 1 or anchor > len(lines):
        raise ValueError(f"invalid insert anchor line: {line_no}")
    idx = anchor - 1 if before_line else anchor
    return "".join(lines[:idx] + _normalize_insert_content(content) + lines[idx:])


def _insert_at_text(before: str, old_text: str, content: str, *, before_text: bool) -> str:
    count = before.count(old_text)
    if count != 1:
        raise ValueError(f"old_text must match exactly once for unique insert anchor; matched {count}")
    idx = before.index(old_text)
    insert_idx = idx if before_text else idx + len(old_text)
    return before[:insert_idx] + content + before[insert_idx:]


def _apply_simple_unified_patch(before: str, patch: str) -> str:
    """Apply a single-file unified diff without shelling out."""
    lines = before.splitlines(keepends=True)
    patch_lines = patch.splitlines(keepends=True)
    output: List[str] = []
    src_index = 0
    i = 0
    while i < len(patch_lines):
        line = patch_lines[i]
        if not line.startswith("@@"):
            i += 1
            continue
        header = line
        try:
            old_range = header.split()[1]
            old_start = int(old_range[1:].split(",", 1)[0])
        except (IndexError, ValueError) as exc:
            raise ValueError(f"invalid unified diff hunk header: {header.strip()}") from exc
        hunk_start = max(old_start - 1, 0)
        if hunk_start < src_index:
            raise ValueError("overlapping unified diff hunks are not supported")
        output.extend(lines[src_index:hunk_start])
        src_index = hunk_start
        i += 1
        while i < len(patch_lines) and not patch_lines[i].startswith("@@"):
            hunk_line = patch_lines[i]
            marker = hunk_line[:1]
            body = hunk_line[1:]
            if marker == " ":
                if src_index >= len(lines) or lines[src_index] != body:
                    raise ValueError("unified diff context does not match file")
                output.append(lines[src_index])
                src_index += 1
            elif marker == "-":
                if src_index >= len(lines) or lines[src_index] != body:
                    raise ValueError("unified diff removal does not match file")
                src_index += 1
            elif marker == "+":
                output.append(body)
            elif marker == "\\":
                pass
            else:
                raise ValueError(f"unsupported unified diff line: {hunk_line.rstrip()}")
            i += 1
    output.extend(lines[src_index:])
    return "".join(output)


def _edit_file_content(action: Dict[str, Any], before: str) -> str:
    operation = action.get("operation")
    if operation == "replace_file":
        return action.get("content", "")
    if operation == "replace_text":
        old_text = action.get("old_text", "")
        count = before.count(old_text)
        if not old_text or count != 1:
            raise ValueError(f"old_text must match exactly once for unique replacement; matched {count}")
        return before.replace(old_text, action.get("new_text", ""), 1)
    if operation == "replace_range":
        return _replace_lines(before, action.get("line_start"), action.get("line_end"), action.get("content", ""))
    if operation == "delete_range":
        return _replace_lines(before, action.get("line_start"), action.get("line_end"), "")
    if operation == "insert_before":
        if action.get("old_text"):
            return _insert_at_text(before, action["old_text"], action.get("content", ""), before_text=True)
        return _insert_at_line(before, action.get("line_start"), action.get("content", ""), before_line=True)
    if operation == "insert_after":
        if action.get("old_text"):
            return _insert_at_text(before, action["old_text"], action.get("content", ""), before_text=False)
        return _insert_at_line(before, action.get("line_start"), action.get("content", ""), before_line=False)
    if operation == "apply_patch":
        return _apply_simple_unified_patch(before, action.get("content", ""))
    raise ValueError(f"unsupported edit_file operation: {operation}")


def _execute_edit_file(
    action: Dict[str, Any],
    work_dir: str,
    read_file_func,
    write_file_func,
    workspace_root: Optional[str] = None,
) -> Dict[str, Any]:
    if not workspace_root:
        return {"type": "edit_file", "success": False, "error": "workspace tools are not available"}

    raw_path = action.get("file_path", "")
    try:
        path = _resolve_workspace_path(raw_path, workspace_root, work_dir)
        before = read_file_func(str(path))
        if isinstance(before, str) and before.startswith("Error reading file:"):
            before = ""
        after = _edit_file_content(action, before)
        changed = before != after
        ok = True
        err = ""
        if changed:
            ok, err = write_file_func(str(path), after)
        rel_path = _workspace_relative(path, workspace_root)
        return {
            "type": "edit_file",
            "success": ok,
            "path": rel_path,
            "file_path": str(path),
            "operation": action.get("operation"),
            "changed": changed,
            "diff": _make_unified_diff(rel_path, before, after),
            "line_delta": _line_count(after) - _line_count(before),
            "sha256_before": _sha256_text(before),
            "sha256_after": _sha256_text(after),
            "error": err if not ok else None,
        }
    except Exception as exc:
        return {
            "type": "edit_file",
            "success": False,
            "path": raw_path,
            "operation": action.get("operation"),
            "error": str(exc),
        }


def _execute_complete_stage(action: Dict[str, Any]) -> Dict[str, Any]:
    """Record an explicit stage completion request for workflow gates."""
    return {
        "type": "complete_stage",
        "success": True,
        "summary": action.get("summary", ""),
        "evidence": action.get("evidence", []),
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


def _execute_waveform_action(action: Dict[str, Any], waveform_actions: Optional[Any]) -> Dict[str, Any]:
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
