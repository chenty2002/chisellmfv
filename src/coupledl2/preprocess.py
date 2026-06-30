"""Workspace-local preprocessing and formal-surface cleanup."""

from __future__ import annotations

import re
from pathlib import Path
from typing import Any, Dict, Iterable, List, Optional, Set, Tuple


AUTO_VERIFY_GENERATED_DIR = "generated"
EXPLICIT_FORMAL_CALL_RE = re.compile(
    r"(?<!\bdef\s)(?:\bchisel3\.(?:assert|assume)|"
    r"\bFormal\.(?:assert|assume)|\bfvAssert|"
    r"\bAssertProperty|\bastLiveness|\bastRelaxedLiveness|"
    r"\bassertLivenessTimer|\bassertAt|\bassertAfterNStepWhen|"
    r"\bassertNextStepWhen|\bassertAlwaysAfterNStepWhen)\s*\("
)
UNQUALIFIED_FORMAL_CALL_RE = re.compile(r"(?<![\w.])(?:assert|assume)\s*\(")
BORING_CALL_RE = re.compile(r"\bBoringUtils\.[A-Za-z_][A-Za-z0-9_]*\s*\(")
VAL_RE = re.compile(r"^\s*(?:private\s+|protected\s+|lazy\s+)*val\s+([A-Za-z_][A-Za-z0-9_]*)\b")
STRUCTURAL_BINDINGS = {"module", "io", "node", "clock", "reset"}
FORMAL_MIXIN_RE = re.compile(r"\s+with\s+Formal\b")
HARDWARE_SCOPE_RE = re.compile(
    r"\b(?:extends|new)\b[^{]*(?:\bModule\b|\bRawModule\b|\bBlackBox\b|"
    r"\bLazyModuleImp\b|\b[A-Za-z_][A-Za-z0-9_]*Module\b|"
    r"\bBase[A-Z][A-Za-z0-9_]*\b)|"
    r"\bdef\b[^{]*(?:\bBool\b|\bUInt\b|\bSInt\b|\bData\b)"
)
SCALA_ASSERT_HINT_RE = re.compile(
    r"\bPredef\.assert\b|"
    r"\.(?:length|size|exists|forall|isDefined|isEmpty|nonEmpty|"
    r"isInstanceOf|contains)\b"
)
CHISEL_ASSERT_HINT_RE = re.compile(
    r"\b(?:io|clock|reset)\b|===|=/=|:=|"
    r"\.(?:B|U|W|fire|valid|ready|bits|andR|orR)\b|"
    r"\b(?:Reg|RegInit|RegNext|Wire|WireDefault|Mux|PopCount|Cat|VecInit)\b"
)


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
    """Return all copied Chisel Scala sources subject to formal cleanup."""
    chisel_dir = case_workspace / "Chisel"
    if not chisel_dir.is_dir():
        return []
    return sorted(path for path in chisel_dir.rglob("*.scala") if path.is_file())


def scan_formal_surface(case_workspace: Path) -> Dict[str, Any]:
    assertions: List[Dict[str, Any]] = []
    boringutils: List[Dict[str, Any]] = []
    files = verification_source_files(case_workspace)
    for path in files:
        text = path.read_text(encoding="utf-8", errors="ignore")
        lines = text.splitlines(keepends=True)
        code_lines = _code_lines_without_comments(text)
        hardware_ranges = _scope_ranges(lines, HARDWARE_SCOPE_RE)
        formal_ranges = _scope_ranges(lines, FORMAL_MIXIN_RE)
        assert_aliases = _chisel_assert_aliases(code_lines)
        harness = _is_verification_harness(path, case_workspace)
        for line_no, (line, code) in enumerate(
            zip(text.splitlines(), code_lines),
            1,
        ):
            index = line_no - 1
            if _formal_call_trigger(
                code,
                index,
                hardware_ranges,
                formal_library=path.stem == "Formal",
                assert_aliases=assert_aliases,
            ):
                assertions.append(_record(path, case_workspace, line_no, line))
            if FORMAL_MIXIN_RE.search(code):
                assertions.append(_record(path, case_workspace, line_no, line))
            if BORING_CALL_RE.search(code) and (
                harness or _index_in_ranges(index, formal_ranges)
            ):
                boringutils.append(_record(path, case_workspace, line_no, line))
    return {
        "schema_version": "formal_surface_scan.v1",
        "scope": "Chisel/**/*.scala hardware/formal instrumentation",
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
        hardware_ranges = _scope_ranges(lines, HARDWARE_SCOPE_RE)
        formal_ranges = _scope_ranges(lines, FORMAL_MIXIN_RE)
        assert_aliases = _chisel_assert_aliases(code_lines)
        harness = _is_verification_harness(path, case_workspace)
        formal_library = path.stem == "Formal"
        ranges: List[Tuple[int, int]] = []
        removed_identifiers: Set[str] = set()
        removed_names: Set[str] = set()
        file_failures: List[Dict[str, Any]] = []
        helper_ranges: Dict[int, Tuple[int, int, str]] = {}
        replacements: Dict[int, str] = {}
        boring_indices = {
            index
            for index, code in enumerate(code_lines)
            if BORING_CALL_RE.search(code)
            and (harness or _index_in_ranges(index, formal_ranges))
        }
        all_boring_indices = {
            index for index, code in enumerate(code_lines) if BORING_CALL_RE.search(code)
        }
        remove_boring_import = (
            (harness or bool(formal_ranges))
            and boring_indices == all_boring_indices
        )

        for index, line in enumerate(lines):
            if FORMAL_MIXIN_RE.search(code_lines[index]):
                replacements[index] = FORMAL_MIXIN_RE.sub("", line)

        for index, line in enumerate(lines):
            if index not in boring_indices:
                continue
            helper = _enclosing_definition(lines, index)
            if helper:
                helper_ranges[index] = helper
                ranges.append((helper[0], helper[1]))
                removed_names.add(helper[2])
                removed_text = "".join(lines[helper[0]:helper[1] + 1])
                removed_identifiers.update(_identifiers(removed_text))
                continue
            declaration = _enclosing_declaration(lines, index)
            if declaration:
                ranges.append(declaration)
                declaration_text = "".join(lines[declaration[0]:declaration[1] + 1])
                match = VAL_RE.match(_code_without_line_comment(lines[declaration[0]]))
                if match:
                    removed_names.add(match.group(1))
                removed_identifiers.update(_identifiers(declaration_text))

        for index, line in enumerate(lines):
            code = code_lines[index]
            if remove_boring_import and re.match(
                r"^\s*import\b.*\bBoringUtils\b",
                code,
            ):
                ranges.append((index, index))
                continue
            if index in helper_ranges or any(start <= index <= end for start, end, _ in helper_ranges.values()):
                continue
            if any(start <= index <= end for start, end in ranges):
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
            formal_trigger = _formal_call_trigger(
                code,
                index,
                hardware_ranges,
                formal_library=formal_library,
                assert_aliases=assert_aliases,
            )
            boring_trigger = BORING_CALL_RE.search(code) if index in boring_indices else None
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


def _enclosing_declaration(
    lines: List[str],
    target: int,
    lookback: int = 12,
) -> Optional[Tuple[int, int]]:
    for start in range(target, max(-1, target - lookback), -1):
        if not VAL_RE.match(_code_without_line_comment(lines[start])):
            continue
        end = _balanced_declaration_end(lines, start)
        if end is not None and target <= end:
            return start, end
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


def _scope_ranges(lines: List[str], pattern: re.Pattern) -> List[Tuple[int, int]]:
    ranges: List[Tuple[int, int]] = []
    for start, line in enumerate(lines):
        code = _code_without_line_comment(line)
        if not pattern.search(code):
            continue
        end = _balanced_block_end(lines, start)
        if end is not None:
            ranges.append((start, end))
    return ranges


def _index_in_ranges(index: int, ranges: Iterable[Tuple[int, int]]) -> bool:
    return any(start <= index <= end for start, end in ranges)


def _is_verification_harness(path: Path, case_workspace: Path) -> bool:
    relative = path.relative_to(case_workspace / "Chisel")
    stem = path.stem.lower()
    parts = {part.lower() for part in relative.parts}
    return (
        stem.startswith("verifytop")
        or stem == "testtop"
        or "verification" in parts
        or "coupledl2verification" in parts
    )


def _formal_call_trigger(
    code: str,
    index: int,
    hardware_ranges: Iterable[Tuple[int, int]],
    *,
    formal_library: bool,
    assert_aliases: Set[str],
) -> Optional[re.Match]:
    explicit = EXPLICIT_FORMAL_CALL_RE.search(code)
    if explicit:
        return explicit
    for alias in assert_aliases:
        aliased = re.search(rf"(?<![\w.]){re.escape(alias)}\s*\(", code)
        if aliased:
            return aliased
    unqualified = UNQUALIFIED_FORMAL_CALL_RE.search(code)
    if not unqualified:
        return None
    if re.search(r"\bdef\s*$", code[:unqualified.start()]):
        return None
    hardware_hint = CHISEL_ASSERT_HINT_RE.search(code)
    if SCALA_ASSERT_HINT_RE.search(code) and not hardware_hint:
        return None
    chisel_bool_signature = (
        re.search(r"\bdef\b", code[:unqualified.start()])
        and re.search(r"\bBool\b", code[:unqualified.start()])
    )
    if (
        hardware_hint
        or formal_library
        or chisel_bool_signature
        or _index_in_ranges(index, hardware_ranges)
    ):
        return unqualified
    return None


def _chisel_assert_aliases(code_lines: Iterable[str]) -> Set[str]:
    aliases: Set[str] = set()
    for code in code_lines:
        aliases.update(
            re.findall(
                r"\b(?:assert|assume)\s*=>\s*([A-Za-z_][A-Za-z0-9_]*)",
                code,
            )
        )
    return aliases


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
