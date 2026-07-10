"""Workspace-local preprocessing and formal-surface cleanup."""

from __future__ import annotations

import hashlib
import re
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Dict, Iterable, List, Optional, Set, Tuple

from .property_catalog import PropertyCatalog


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


class PropertySurfaceError(ValueError):
    """Raised when a profile cannot be installed unambiguously."""


@dataclass(frozen=True)
class PreparedPropertySurface:
    target_path: Path
    marker_text: str
    sha256_before: str
    sha256_after: str


def prepare_profile_surface(
    case_workspace: Path,
    catalog: PropertyCatalog,
) -> PreparedPropertySurface:
    """Clean inherited verification code and install one profile marker."""
    case_workspace = Path(case_workspace)
    target = catalog.profile["target"]
    target_path = case_workspace / target["relative_path"]
    if not target_path.is_file():
        raise PropertySurfaceError(f"profile target not found: {target['relative_path']}")
    original = target_path.read_text(encoding="utf-8")
    _validate_marker_target(case_workspace, target, "profile target")
    for index, source_target in enumerate(catalog.profile.get("source_targets", [])):
        _validate_marker_target(case_workspace, source_target, f"profile source target {index}")
    _validate_candidate_provenance(case_workspace, catalog)

    cleanup_result = clean_formal_surface(case_workspace)
    if not cleanup_result["success"]:
        raise PropertySurfaceError("generic formal-surface cleanup failed")
    updated = target_path.read_text(encoding="utf-8")
    updated = _install_marker(updated, target)
    target_path.write_text(updated, encoding="utf-8")
    for source_target in catalog.profile.get("source_targets", []):
        source_path = case_workspace / source_target["relative_path"]
        source_text = source_path.read_text(encoding="utf-8")
        source_path.write_text(_install_marker(source_text, source_target), encoding="utf-8")
    return PreparedPropertySurface(
        target_path=target_path,
        marker_text=target["marker_text"],
        sha256_before=hashlib.sha256(original.encode("utf-8")).hexdigest(),
        sha256_after=hashlib.sha256(updated.encode("utf-8")).hexdigest(),
    )


def _validate_marker_target(case_workspace: Path, target: Dict[str, Any], label: str) -> None:
    target_path = case_workspace / target["relative_path"]
    if not target_path.is_file():
        raise PropertySurfaceError(f"{label} not found: {target['relative_path']}")
    original = target_path.read_text(encoding="utf-8")
    marker = target["marker_text"]
    marker_after = target["marker_after"]
    if original.count(marker):
        raise PropertySurfaceError(f"{label} marker already exists")
    if original.count(marker_after) != 1:
        raise PropertySurfaceError(f"{label} marker selector must match exactly once")
    cleanup = target["cleanup_region"]
    if cleanup is not None:
        if original.count(cleanup["start_text"]) != 1:
            raise PropertySurfaceError(f"{label} cleanup start selector must match exactly once")
        start = original.index(cleanup["start_text"])
        if original.find(cleanup["block_start_text"], start) < 0:
            raise PropertySurfaceError(f"{label} cleanup block selector not found")
        if original.count(cleanup["block_start_text"], start) != 1:
            raise PropertySurfaceError(f"{label} cleanup block selector must match exactly once")


def _install_marker(text: str, target: Dict[str, Any]) -> str:
    cleanup = target["cleanup_region"]
    if cleanup is not None:
        text = _remove_profile_cleanup_region(text, cleanup)
    marker = target["marker_text"]
    marker_after = target["marker_after"]
    if text.count(marker_after) != 1:
        raise PropertySurfaceError("marker selector changed during cleanup")
    lines = text.splitlines(keepends=True)
    selector_line = next(
        index for index, line in enumerate(lines) if marker_after in line
    )
    indent = re.match(r"\s*", lines[selector_line]).group(0)
    lines.insert(selector_line + 1, f"{indent}{marker}\n")
    updated = "".join(lines)
    if updated.count(marker) != 1:
        raise PropertySurfaceError("profile marker installation is not unique")
    return updated


def _validate_candidate_provenance(
    case_workspace: Path,
    catalog: PropertyCatalog,
) -> None:
    for candidate in catalog.candidates.values():
        provenance = candidate["provenance"]
        if provenance["kind"] == "source_scope":
            path = case_workspace / provenance["path"]
            if not path.is_file():
                raise PropertySurfaceError("candidate provenance source not found")
            text = path.read_text(encoding="utf-8", errors="ignore")
            if text.count(provenance["scope_anchor"]) != 1:
                raise PropertySurfaceError("candidate scope anchor must match exactly once")
            continue
        template = catalog.templates[provenance["template_id"]]
        if candidate["expression"] not in template["fragments"]["support_block"]:
            raise PropertySurfaceError("template candidate expression is not reconstructible")


def _remove_profile_cleanup_region(text: str, cleanup: Dict[str, Any]) -> str:
    search_start = text.index(cleanup["start_text"])
    block_start = text.index(cleanup["block_start_text"], search_start)
    brace_start = text.find("{", block_start)
    if brace_start < 0:
        raise PropertySurfaceError("cleanup block has no opening brace")
    depth = 0
    in_string = False
    escaped = False
    end = None
    for index in range(brace_start, len(text)):
        char = text[index]
        if in_string:
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif char == '"':
                in_string = False
            continue
        if char == '"':
            in_string = True
        elif char == "{":
            depth += 1
        elif char == "}":
            depth -= 1
            if depth == 0:
                end = index + 1
                break
    if end is None:
        raise PropertySurfaceError("cleanup block is unbalanced")
    while end < len(text) and text[end] in " \t":
        end += 1
    if end < len(text) and text[end] == "\n":
        end += 1
    return text[:block_start] + text[end:]


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
        if _is_formal_library(path, case_workspace):
            continue
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


def build_baseline_assertion_inventory(
    case_workspace: Path,
    *,
    disabled_labels: Iterable[str] = (),
) -> Dict[str, Any]:
    """Inventory inherited verification statements without changing them."""
    disabled = set(disabled_labels)
    entries: List[Dict[str, Any]] = []
    call_re = re.compile(
        r"(?P<kind>assert|assume|cover|fvAssert|AssertProperty|astLiveness|"
        r"astRelaxedLiveness|assertLivenessTimer|assertAt|"
        r"assertAfterNStepWhen|assertNextStepWhen|assertAlwaysAfterNStepWhen)\s*\("
    )
    label_re = re.compile(r"\b((?:CL2|TL)_[A-Z0-9_]+)\b")
    for path in verification_source_files(case_workspace):
        if _is_formal_library(path, case_workspace):
            continue
        text = path.read_text(encoding="utf-8", errors="ignore")
        for line_no, raw in enumerate(text.splitlines(), 1):
            code = _code_without_line_comment(raw)
            matches = list(call_re.finditer(code))
            if matches:
                match = matches[0]
                raw_kind = match.group("kind")
                kind = "assume" if raw_kind == "assume" else (
                    "cover" if raw_kind == "cover" else "assert"
                )
                label_match = label_re.search(raw)
                label = label_match.group(1) if label_match else None
                normalized = " ".join(raw.strip().split())
                is_disabled = bool(label and label in disabled)
                entries.append({
                    "source_path": _rel(path, case_workspace),
                    "line": line_no,
                    "kind": kind,
                    "label": label,
                    "sha256": hashlib.sha256(normalized.encode("utf-8")).hexdigest(),
                    "policy": "disabled" if is_disabled else "preserved",
                    "reason": (
                        "formal_contract.disabled_baseline_properties"
                        if is_disabled
                        else "inherited_formal_surface"
                    ),
                    "text": normalized[:240],
                })
            if BORING_CALL_RE.search(code):
                normalized = " ".join(raw.strip().split())
                entries.append({
                    "source_path": _rel(path, case_workspace),
                    "line": line_no,
                    "kind": "boring_observer",
                    "label": None,
                    "sha256": hashlib.sha256(normalized.encode("utf-8")).hexdigest(),
                    "policy": "preserved",
                    "reason": "inherited_observer_surface",
                    "text": normalized[:240],
                })
    entries.sort(key=lambda item: (item["source_path"], item["line"], item["kind"]))
    return {
        "schema_version": "baseline_assertion_inventory.v1",
        "entries": entries,
        "entry_count": len(entries),
        "preserved_count": sum(item["policy"] == "preserved" for item in entries),
        "disabled_count": sum(item["policy"] == "disabled" for item in entries),
    }


def clean_formal_surface(case_workspace: Path) -> Dict[str, Any]:
    """Remove only ChiselLMFV-owned generated regions from a copied case.

    Inherited assertions, assumptions, covers, and BoringUtils observers are
    deliberately preserved.  Profile-specific historical oracle removal is
    handled separately by the reviewed profile cleanup selector.
    """
    begin = "// CHISELLMFV_GENERATED_BEGIN"
    end = "// CHISELLMFV_GENERATED_END"
    changed_files: List[str] = []
    removed: List[Dict[str, Any]] = []
    failures: List[Dict[str, Any]] = []
    for path in verification_source_files(case_workspace):
        original = path.read_text(encoding="utf-8", errors="ignore")
        lines = original.splitlines(keepends=True)
        output: List[str] = []
        index = 0
        changed = False
        while index < len(lines):
            if begin not in lines[index]:
                output.append(lines[index])
                index += 1
                continue
            start = index
            cursor = index + 1
            while cursor < len(lines) and end not in lines[cursor]:
                if begin in lines[cursor]:
                    failures.append({
                        "path": _rel(path, case_workspace),
                        "line": cursor + 1,
                        "reason": "nested generated region",
                    })
                    break
                cursor += 1
            if cursor >= len(lines) or (cursor < len(lines) and begin in lines[cursor]):
                failures.append({
                    "path": _rel(path, case_workspace),
                    "line": start + 1,
                    "reason": "unterminated generated region",
                })
                output = lines
                changed = False
                break
            removed_text = "".join(lines[start:cursor + 1])
            removed.append({
                "path": _rel(path, case_workspace),
                "line_start": start + 1,
                "line_end": cursor + 1,
                "sha256": hashlib.sha256(removed_text.encode("utf-8")).hexdigest(),
                "reason": "profile_owned_generated_region",
            })
            changed = True
            index = cursor + 1
        if changed and not any(item["path"] == _rel(path, case_workspace) for item in failures):
            path.write_text("".join(output), encoding="utf-8")
            changed_files.append(_rel(path, case_workspace))
    after = scan_formal_surface(case_workspace)
    return {
        "schema_version": "formal_surface_cleanup.v2",
        "success": not failures,
        "policy": "profile_owned_generated_regions_only",
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


def _is_formal_library(path: Path, case_workspace: Path) -> bool:
    relative = path.relative_to(case_workspace)
    return (
        path.name == "Formal.scala"
        and "src" in relative.parts
        and "main" in relative.parts
        and "chiselFv" in relative.parts
    )


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
