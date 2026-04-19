"""
Utility functions for file operations with validation.

Includes:
- File read/write with validation
- Path permission checking
- Scala source loading for LLM context
- Copyright/license comment removal
"""

from typing import Dict, List, Optional, Tuple, Any
import os
import re
import logging


# ============================================================================
# Basic File Operations
# ============================================================================

def read_file(file_path: str) -> str:
    """
    Read file contents with error handling.
    
    Args:
        file_path: Path to the file to read
        
    Returns:
        File contents or error message string (prefixed with "Error reading file:")
    """
    try:
        if not os.path.exists(file_path):
            return f"Error reading file: File does not exist: {file_path}"
        if not os.path.isfile(file_path):
            return f"Error reading file: Path is not a file: {file_path}"
        with open(file_path, 'r', encoding='utf-8') as f:
            return f.read()
    except Exception as e:
        return f"Error reading file: {str(e)}"


def write_file(
    file_path: str,
    content: str,
    allowed_dirs: Optional[List[str]] = None,
) -> Tuple[bool, str]:
    """
    Write content to file with optional path validation.
    
    Args:
        file_path: Path to write to
        content: Content to write
        allowed_dirs: List of allowed directories for writing.
                      If None, no directory check is performed.
        
    Returns:
        Tuple of (success, error_message)
    """
    try:
        # Check allowed directories if specified
        if allowed_dirs is not None and not is_path_in_allowed_dirs(file_path, allowed_dirs):
            return False, f"Path not writable (outside allowed directories): {file_path}"
        
        parent_dir = os.path.dirname(file_path)
        if parent_dir and not os.path.exists(parent_dir):
            os.makedirs(parent_dir, exist_ok=True)
        
        with open(file_path, 'w', encoding='utf-8') as f:
            f.write(content)
        return True, ""
    except Exception as e:
        return False, str(e)


def is_path_in_allowed_dirs(file_path: str, allowed_dirs: List[str]) -> bool:
    """
    Check if a file path is in one of the allowed directories.
    
    Args:
        file_path: Path to check
        allowed_dirs: List of allowed directory paths
        
    Returns:
        True if the path is in an allowed directory
    """
    abs_path = os.path.abspath(file_path)
    return any(abs_path.startswith(os.path.abspath(d)) for d in allowed_dirs if d)


def _is_copyright_or_license(text: str) -> bool:
    """Check if text contains copyright or license keywords."""
    return bool(re.search(r'(copyright|license)', text, re.IGNORECASE))


def _process_multiline_comment(lines: list, result_lines: list, start_idx: int) -> int:
    """Process multi-line /* */ comment and return next index."""
    line = lines[start_idx]
    comment_start = line.find('/*')
    comment_end = line.find('*/', comment_start)
    
    if comment_end != -1:  # Single line /* */ comment
        comment_text = line[comment_start:comment_end + 2]
        if _is_copyright_or_license(comment_text):
            result_lines[start_idx] = line[:comment_start] + line[comment_end + 2:]
            if not result_lines[start_idx].strip():
                result_lines[start_idx] = ''
        return start_idx + 1
    
    # Multi-line comment
    comment_lines = [start_idx]
    j = start_idx + 1
    while j < len(lines) and '*/' not in lines[j]:
        comment_lines.append(j)
        j += 1
    if j < len(lines):
        comment_lines.append(j)
    
    full_comment = '\n'.join([lines[idx] for idx in comment_lines])
    if _is_copyright_or_license(full_comment):
        for idx in comment_lines:
            result_lines[idx] = ''
    
    return j + 1


def remove_copyright_license_comments(content: str) -> str:
    """
    Remove comments containing copyright or license while preserving line numbers.
    
    Args:
        content: Original file content
        
    Returns:
        Cleaned content with copyright/license comments replaced by empty lines
    """
    lines = content.split('\n')
    result_lines = lines.copy()
    
    i = 0
    while i < len(lines):
        line = lines[i]
        
        if '/*' in line:
            i = _process_multiline_comment(lines, result_lines, i)
        elif '//' in line:
            comment_start = line.find('//')
            comment_text = line[comment_start:]
            if _is_copyright_or_license(comment_text):
                result_lines[i] = line[:comment_start].rstrip()
                if not result_lines[i].strip():
                    result_lines[i] = ''
            i += 1
        else:
            i += 1
    
    return '\n'.join(result_lines)


# ============================================================================
# Scala Source Loading
# ============================================================================

def load_files_from_directory(
    directory: str,
    extension: str = ".scala",
    logger: Optional[logging.Logger] = None,
) -> Dict[str, str]:
    """
    Load all files with given extension from a directory.
    
    Args:
        directory: Directory to load files from
        extension: File extension to filter by
        logger: Optional logger for warnings
        
    Returns:
        Dictionary mapping filenames to their contents
    """
    sources = {}
    try:
        for filename in os.listdir(directory):
            if filename.endswith(extension):
                full_path = os.path.join(directory, filename)
                if os.path.isfile(full_path):
                    try:
                        with open(full_path, 'r', encoding='utf-8') as f:
                            content = f.read()
                        content = remove_copyright_license_comments(content)
                        sources[filename] = content
                    except Exception as e:
                        if logger:
                            logger.warning(f"Failed to read {filename}: {e}")
    except Exception as e:
        if logger:
            logger.error(f"Failed to list directory {directory}: {e}")
    return sources


def load_files_from_list(
    file_list: List[str],
    base_dir: str,
    logger: Optional[logging.Logger] = None,
) -> Dict[str, str]:
    """
    Load files from a list of relative paths.
    
    Args:
        file_list: List of relative file paths
        base_dir: Base directory to resolve paths from
        logger: Optional logger for warnings
        
    Returns:
        Dictionary mapping full paths to their contents
    """
    sources = {}
    for rel_path in file_list:
        full_path = os.path.join(base_dir, rel_path)
        if os.path.exists(full_path):
            try:
                with open(full_path, 'r', encoding='utf-8') as f:
                    content = f.read()
                content = remove_copyright_license_comments(content)
                sources[full_path] = content
            except Exception as e:
                if logger:
                    logger.warning(f"Failed to load {full_path}: {e}")
    return sources


# ============================================================================
# Workspace File Management
# ============================================================================

def capture_stage_snapshot(work_dir: str, logger: Optional[logging.Logger] = None) -> Dict[str, str]:
    """
    Capture a snapshot of all .scala and .md files in work_dir.
    
    Args:
        work_dir: Directory to capture snapshot from
        logger: Optional logger for warnings
        
    Returns:
        Dictionary mapping file paths to their contents
    """
    snapshot = {}
    if not os.path.exists(work_dir):
        return snapshot
    
    try:
        for filename in os.listdir(work_dir):
            if filename.endswith('.scala') or filename.endswith('.md'):
                full_path = os.path.join(work_dir, filename)
                if os.path.isfile(full_path):
                    try:
                        with open(full_path, 'r', encoding='utf-8') as f:
                            snapshot[full_path] = f.read()
                    except Exception as e:
                        if logger:
                            logger.warning(f"Failed to read {filename} for snapshot: {e}")
    except Exception as e:
        if logger:
            logger.error(f"Failed to capture stage snapshot: {e}")
    
    return snapshot


def restore_stage_snapshot(
    work_dir: str, 
    snapshot: Dict[str, str], 
    reason: str = "",
    logger: Optional[logging.Logger] = None
) -> Dict[str, Any]:
    """
    Restore work_dir to a previous snapshot state.
    
    Deletes all current .scala and .md files and restores files from snapshot.
    
    Args:
        work_dir: Directory to restore
        snapshot: Dictionary mapping file paths to their contents
        reason: Description of why reset is needed
        logger: Optional logger for info/warnings
        
    Returns:
        Dictionary with reset operation result
    """
    if not snapshot:
        return {
            "success": False,
            "error": "No snapshot available"
        }
    
    try:
        # First, delete all current .scala and .md files
        for filename in os.listdir(work_dir):
            if filename.endswith('.scala') or filename.endswith('.md'):
                full_path = os.path.join(work_dir, filename)
                if os.path.isfile(full_path):
                    try:
                        os.remove(full_path)
                        if logger:
                            logger.info(f"Deleted: {filename}")
                    except Exception as e:
                        if logger:
                            logger.warning(f"Failed to delete {filename}: {e}")
        
        # Restore files from snapshot
        restored_files = []
        for file_path, content in snapshot.items():
            try:
                with open(file_path, 'w', encoding='utf-8') as f:
                    f.write(content)
                restored_files.append(os.path.basename(file_path))
                if logger:
                    logger.info(f"Restored: {os.path.basename(file_path)}")
            except Exception as e:
                if logger:
                    logger.warning(f"Failed to restore {file_path}: {e}")
        
        if logger:
            logger.info(f"Stage reset complete. Reason: {reason}")
        
        return {
            "success": True,
            "reason": reason,
            "restored_files": restored_files,
            "message": f"Stage reset complete. Restored {len(restored_files)} files."
        }
    except Exception as e:
        return {
            "success": False,
            "error": str(e)
        }


def get_directory_files(
    directory: str, 
    extensions: List[str], 
    logger: Optional[logging.Logger] = None
) -> List[str]:
    """
    Get list of files with specific extensions in a directory.
    
    Args:
        directory: Directory to list files from
        extensions: List of file extensions to filter (e.g., ['.scala', '.md'])
        logger: Optional logger for errors
        
    Returns:
        Sorted list of filenames
    """
    files = []
    if not os.path.exists(directory):
        return files
    
    try:
        for filename in os.listdir(directory):
            if any(filename.endswith(ext) for ext in extensions):
                if os.path.isfile(os.path.join(directory, filename)):
                    files.append(filename)
    except Exception as e:
        if logger:
            logger.error(f"Failed to list directory files: {e}")
    
    return sorted(files)
