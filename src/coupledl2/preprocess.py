"""Workspace-local preprocessing and formal-surface cleanup."""

from __future__ import annotations

import re
from pathlib import Path
from typing import Any, Dict, Iterable, List, Optional, Set, Tuple


AUTO_VERIFY_GENERATED_DIR = "generated"
FORMAL_CALL_RE = re.compile(
    r"(?<!\bdef\s)(?:\bFormal\.(?:assert|assume)|\bfvAssert|"
    r"\bAssertProperty|\bastLiveness|\bastRelaxedLiveness|"
    r"\bassertLivenessTimer|\bassert|\bassume)\s*\("
)
BORING_CALL_RE = re.compile(r"\bBoringUtils\.[A-Za-z_][A-Za-z0-9_]*\s*\(")
VAL_RE = re.compile(r"^\s*(?:private\s+|protected\s+|lazy\s+)*val\s+([A-Za-z_][A-Za-z0-9_]*)\b")
STRUCTURAL_BINDINGS = {"module", "io", "node", "clock", "reset"}


def preprocess_coupledl2_workspace(case_workspace: Path) -> Dict[str, Any]:
    """Patch AutoVerify and remove inherited verification-only statements."""
    patched_autoverify = patch_autoverify_outputs(case_workspace)
    cleanup = clean_formal_surface(case_workspace)
    return {
        "schema_version": "coupledl2_preprocess.v2",
        "success": cleanup["success"],
        "patched_autoverify": patched_autoverify,
        "cleanup": cleanup,
        "generated_output_dir": f"workspace/case/Chisel/{AUTO_VERIFY_GENERATED_DIR}",
    }


def verification_source_files(case_workspace: Path) -> List[Path]:
    """Return verification harness sources subject to Scala-level cleanup."""
    chisel_dir = case_workspace / "Chisel"
    return sorted(path for path in chisel_dir.rglob("VerifyTop*.scala") if path.is_file())


def scan_formal_surface(case_workspace: Path) -> Dict[str, Any]:
    assertions: List[Dict[str, Any]] = []
    boringutils: List[Dict[str, Any]] = []
    files = verification_source_files(case_workspace)
    for path in files:
        text = path.read_text(encoding="utf-8", errors="ignore")
        for line_no, (line, code) in enumerate(
            zip(text.splitlines(), _code_lines_without_comments(text)),
            1,
        ):
            if FORMAL_CALL_RE.search(code):
                assertions.append(_record(path, case_workspace, line_no, line))
            if "BoringUtils" in code:
                boringutils.append(_record(path, case_workspace, line_no, line))
    return {
        "schema_version": "formal_surface_scan.v1",
        "scope": "VerifyTop*.scala",
        "files_checked": [_rel(path, case_workspace) for path in files],
        "assertion_count": len(assertions),
        "boringutils_count": len(boringutils),
        "assertions": assertions,
        "boringutils": boringutils,
    }


def clean_formal_surface(case_workspace: Path) -> Dict[str, Any]:
    """Remove complete inherited formal statements from copied VerifyTop sources.

    Statement ranges are selected with balanced delimiters. Ambiguous or
    unterminated calls fail closed and leave the source unchanged.
    """
    changed_files: List[str] = []
    removed: List[Dict[str, Any]] = []
    failures: List[Dict[str, Any]] = []

    for path in verification_source_files(case_workspace):
        original = path.read_text(encoding="utf-8", errors="ignore")
        lines = original.splitlines(keepends=True)
        code_lines = _code_lines_without_comments(original)
        ranges: List[Tuple[int, int]] = []
        removed_identifiers: Set[str] = set()
        removed_names: Set[str] = set()
        file_failures: List[Dict[str, Any]] = []
        helper_ranges: Dict[int, Tuple[int, int, str]] = {}
        replacements: Dict[int, str] = {}
        clean_boring = True

        for index, line in enumerate(lines):
            if not clean_boring or not BORING_CALL_RE.search(code_lines[index]):
                continue
            helper = _enclosing_definition(lines, index)
            if helper:
                helper_ranges[index] = helper
                ranges.append((helper[0], helper[1]))
                removed_names.add(helper[2])
                removed_text = "".join(lines[helper[0]:helper[1] + 1])
                removed_identifiers.update(_identifiers(removed_text))

        for index, line in enumerate(lines):
            code = code_lines[index]
            if clean_boring and re.match(r"^\s*import\b.*\bBoringUtils\b", code):
                ranges.append((index, index))
                continue
            if index in helper_ranges or any(start <= index <= end for start, end, _ in helper_ranges.values()):
                continue
            helper_call = next(
                (name for name in removed_names if re.search(rf"\b{re.escape(name)}\s*\(", code)),
                None,
            )
            if helper_call:
                end = _balanced_statement_end(lines, index)
                if end is None:
                    file_failures.append({
                        "path": _rel(path, case_workspace),
                        "line": index + 1,
                        "reason": f"unbalanced call to removed helper {helper_call}",
                    })
                else:
                    ranges.append((index, end))
                    removed_identifiers.update(_identifiers("".join(lines[index:end + 1])))
                continue
            formal_trigger = FORMAL_CALL_RE.search(code)
            boring_trigger = BORING_CALL_RE.search(code) if clean_boring else None
            if not (formal_trigger or boring_trigger):
                continue
            trigger = formal_trigger or boring_trigger
            prefix = code[:trigger.start()] if trigger else code
            expression_prefix = re.match(
                r"^\s*(?:"
                r"(?:private\s+|protected\s+|override\s+)*def\b.*=|"
                r"case\b.*=>|"
                r"(?:when|if)\s*\(.*\)\s*\{"
                r")\s*$",
                prefix,
            )
            call_end = _call_end_on_line(code, trigger.start()) if trigger else None
            if formal_trigger and expression_prefix and call_end is not None:
                raw_line = lines[index]
                replacements[index] = (
                    raw_line[:trigger.start()]
                    + "()"
                    + raw_line[call_end + 1:]
                )
                removed.append({
                    "path": _rel(path, case_workspace),
                    "line_start": index + 1,
                    "line_end": index + 1,
                    "text": raw_line.strip(),
                })
                continue
            inline_guard = re.match(r"^\s*(?:when|if)\s*\(", prefix)
            if ("{" in prefix or "}" in prefix or ";" in prefix) and not inline_guard:
                file_failures.append({
                    "path": _rel(path, case_workspace),
                    "line": index + 1,
                    "reason": "formal call shares a line with an enclosing Scala structure",
                })
                continue
            end = _balanced_statement_end(lines, index)
            if end is None:
                file_failures.append({
                    "path": _rel(path, case_workspace),
                    "line": index + 1,
                    "reason": "unbalanced formal statement",
                })
                continue
            ranges.append((index, end))
            removed_text = "".join(lines[index:end + 1])
            match = VAL_RE.match(_code_without_line_comment(lines[index]))
            if match:
                removed_names.add(match.group(1))
            removed_identifiers.update(_identifiers(removed_text))
            removed.append({
                "path": _rel(path, case_workspace),
                "line_start": index + 1,
                "line_end": end + 1,
                "text": removed_text.strip(),
            })

        if file_failures:
            failures.extend(file_failures)
            continue
        for index, replacement in replacements.items():
            lines[index] = replacement
        updated_lines = _remove_ranges(lines, ranges)
        updated_lines, removed_names = _remove_dependent_verification_code(
            updated_lines,
            removed_names,
        )
        updated_lines = _remove_unused_verification_bindings(updated_lines, removed_identifiers)
        updated = "".join(updated_lines)
        if updated != original:
            path.write_text(updated, encoding="utf-8")
            changed_files.append(_rel(path, case_workspace))

    after = scan_formal_surface(case_workspace)
    success = not failures and after["assertion_count"] == 0 and after["boringutils_count"] == 0
    return {
        "schema_version": "formal_surface_cleanup.v1",
        "success": success,
        "changed_files": changed_files,
        "removed_statements": removed,
        "failures": failures,
        "after": after,
    }


def patch_autoverify_outputs(case_workspace: Path) -> List[str]:
    """Route AutoVerify post-processed Verilog into Chisel/generated."""
    chisel_dir = case_workspace / "Chisel"
    patched: List[str] = []
    for path in sorted(chisel_dir.rglob("AutoVerify.scala")):
        original = path.read_text(encoding="utf-8", errors="ignore")
        updated = _patch_autoverify_text(original)
        if updated == original:
            continue
        path.write_text(updated, encoding="utf-8")
        patched.append(_rel(path, case_workspace))
    return patched


def _patch_autoverify_text(text: str) -> str:
    updated = re.sub(
        r'val\s+path\s*=\s*"\.\./Verilog"',
        f'val path = "{AUTO_VERIFY_GENERATED_DIR}"',
        text,
    )
    if f'val path = "{AUTO_VERIFY_GENERATED_DIR}"' not in updated or "mkdirGenerated" in updated:
        return updated

    return re.sub(
        r'(?m)^(\s*)val rm = s"rm -f \$\{path\}/\$\{filename\}".!',
        rf'\1val mkdirGenerated = s"mkdir -p ${{path}}".!' "\n" r'\g<0>',
        updated,
        count=1,
    )


def _balanced_statement_end(lines: List[str], start: int) -> Optional[int]:
    paren = bracket = brace = 0
    saw_call = False
    for index in range(start, len(lines)):
        code = _code_without_line_comment(lines[index])
        for char in _without_strings(code):
            if char == "(":
                paren += 1
                saw_call = True
            elif char == ")":
                paren -= 1
            elif char == "[":
                bracket += 1
            elif char == "]":
                bracket -= 1
            elif char == "{":
                brace += 1
            elif char == "}":
                brace -= 1
            if min(paren, bracket, brace) < 0:
                return None
        if saw_call and paren == bracket == brace == 0:
            return index
    return None


def _remove_ranges(lines: List[str], ranges: Iterable[Tuple[int, int]]) -> List[str]:
    removed = {index for start, end in ranges for index in range(start, end + 1)}
    return [line for index, line in enumerate(lines) if index not in removed]


def _remove_unused_verification_bindings(lines: List[str], candidates: Set[str]) -> List[str]:
    while True:
        text = "".join(lines)
        removed_one = False
        for index, line in enumerate(lines):
            match = VAL_RE.match(_code_without_line_comment(line))
            if not match or match.group(1) not in candidates:
                continue
            name = match.group(1)
            if name in STRUCTURAL_BINDINGS or "{" in _code_without_line_comment(line):
                continue
            if len(re.findall(rf"\b{re.escape(name)}\b", text)) != 1:
                continue
            end = _balanced_declaration_end(lines, index)
            if end is None or end - index > 4:
                continue
            lines = _remove_ranges(lines, [(index, end)])
            removed_one = True
            break
        if not removed_one:
            return lines


def _balanced_declaration_end(lines: List[str], start: int) -> Optional[int]:
    paren = bracket = brace = 0
    for index in range(start, len(lines)):
        for char in _without_strings(_code_without_line_comment(lines[index])):
            if char == "(":
                paren += 1
            elif char == ")":
                paren -= 1
            elif char == "[":
                bracket += 1
            elif char == "]":
                bracket -= 1
            elif char == "{":
                brace += 1
            elif char == "}":
                brace -= 1
            if min(paren, bracket, brace) < 0:
                return None
        if paren == bracket == brace == 0:
            return index
    return None


def _enclosing_definition(lines: List[str], target: int) -> Optional[Tuple[int, int, str]]:
    for start in range(target, -1, -1):
        code = _code_without_line_comment(lines[start])
        match = re.match(
            r"^\s*(?:private\s+|protected\s+|override\s+)*def\s+"
            r"([A-Za-z_][A-Za-z0-9_]*)\b.*\{",
            code,
        )
        if not match:
            continue
        end = _balanced_block_end(lines, start)
        if end is not None and target <= end:
            return start, end, match.group(1)
    return None


def _balanced_block_end(lines: List[str], start: int) -> Optional[int]:
    depth = 0
    opened = False
    for index in range(start, len(lines)):
        for char in _without_strings(_code_without_line_comment(lines[index])):
            if char == "{":
                depth += 1
                opened = True
            elif char == "}":
                depth -= 1
                if depth < 0:
                    return None
        if opened and depth == 0:
            return index
    return None


def _remove_dependent_verification_code(
    lines: List[str],
    removed_names: Set[str],
) -> Tuple[List[str], Set[str]]:
    while removed_names:
        changed = False
        for index, line in enumerate(lines):
            code = _code_without_line_comment(line)
            val_match = VAL_RE.match(code)
            if val_match:
                end = _balanced_declaration_end(lines, index)
                if (
                    end is None
                    or end - index > 4
                    or val_match.group(1) in STRUCTURAL_BINDINGS
                    or "{" in code
                ):
                    continue
                statement = "".join(lines[index:end + 1])
                rhs = statement.split("=", 1)[1] if "=" in statement else ""
                if _references_any(rhs, removed_names):
                    removed_names.add(val_match.group(1))
                    lines = _remove_ranges(lines, [(index, end)])
                    changed = True
                    break
            if re.match(r"^\s*(?:when|switch)\s*\(", code) and _references_any(code, removed_names):
                end = _balanced_block_end(lines, index)
                if end is not None:
                    lines = _remove_ranges(lines, [(index, end)])
                    changed = True
                    break
        if not changed:
            return lines, removed_names
    return lines, removed_names


def _references_any(text: str, names: Set[str]) -> bool:
    return any(re.search(rf"\b{re.escape(name)}\b", text) for name in names)


def _call_end_on_line(code: str, start: int) -> Optional[int]:
    open_paren = code.find("(", start)
    if open_paren < 0:
        return None
    depth = 0
    in_string = False
    escaped = False
    for index in range(open_paren, len(code)):
        char = code[index]
        if escaped:
            escaped = False
        elif char == "\\" and in_string:
            escaped = True
        elif char == '"':
            in_string = not in_string
        elif not in_string and char == "(":
            depth += 1
        elif not in_string and char == ")":
            depth -= 1
            if depth == 0:
                return index
    return None


def _identifiers(text: str) -> Set[str]:
    return set(re.findall(r"\b[A-Za-z_][A-Za-z0-9_]*\b", _without_strings(text)))


def _code_without_line_comment(line: str) -> str:
    in_string = False
    escaped = False
    for index, char in enumerate(line):
        if escaped:
            escaped = False
        elif char == "\\" and in_string:
            escaped = True
        elif char == '"':
            in_string = not in_string
        elif char == "/" and not in_string and index + 1 < len(line) and line[index + 1] == "/":
            return line[:index]
    return line


def _without_strings(text: str) -> str:
    return re.sub(r'"(?:\\.|[^"\\])*"', '""', text)


def _code_lines_without_comments(text: str) -> List[str]:
    result: List[str] = []
    in_block = False
    for raw_line in text.splitlines(keepends=True):
        line = raw_line
        output = []
        index = 0
        in_string = False
        escaped = False
        while index < len(line):
            pair = line[index:index + 2]
            char = line[index]
            if in_block:
                if pair == "*/":
                    in_block = False
                    index += 2
                else:
                    index += 1
                continue
            if escaped:
                output.append(char)
                escaped = False
            elif char == "\\" and in_string:
                output.append(char)
                escaped = True
            elif char == '"':
                output.append(char)
                in_string = not in_string
            elif not in_string and pair == "/*":
                in_block = True
                index += 2
                continue
            elif not in_string and pair == "//":
                break
            else:
                output.append(char)
            index += 1
        result.append("".join(output))
    return result


def _record(path: Path, case_workspace: Path, line: int, text: str) -> Dict[str, Any]:
    return {
        "path": _rel(path, case_workspace),
        "line": line,
        "text": text.strip(),
    }


def _rel(path: Path, case_workspace: Path) -> str:
    return "workspace/case/" + path.relative_to(case_workspace).as_posix()
