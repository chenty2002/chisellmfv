"""Workspace-local preprocessing and formal-surface cleanup."""

from __future__ import annotations

import hashlib
import re
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Dict, Iterable, List, Optional

from .property_catalog import PropertyCatalog


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
        code_lines = _code_lines_without_comments(text)
        harness = _is_verification_harness(path, case_workspace)
        for line_no, (line, code) in enumerate(
            zip(text.splitlines(), code_lines),
            1,
        ):
            if _formal_call_trigger(
                code,
                formal_library=path.stem == "Formal",
                harness=harness,
            ):
                assertions.append(_record(path, case_workspace, line_no, line))
            if FORMAL_MIXIN_RE.search(code):
                assertions.append(_record(path, case_workspace, line_no, line))
            if BORING_CALL_RE.search(code):
                boringutils.append(_record(path, case_workspace, line_no, line))
    return {
        "schema_version": "formal_surface_scan",
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
        "schema_version": "baseline_assertion_inventory",
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
        "schema_version": "formal_surface_cleanup",
        "success": not failures,
        "policy": "profile_owned_generated_regions_only",
        "changed_files": changed_files,
        "removed_statements": removed,
        "failures": failures,
        "after": after,
    }


def _is_formal_library(path: Path, case_workspace: Path) -> bool:
    relative = path.relative_to(case_workspace)
    return (
        path.name == "Formal.scala"
        and "src" in relative.parts
        and "main" in relative.parts
        and "chiselFv" in relative.parts
    )


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
    *,
    formal_library: bool,
    harness: bool,
) -> Optional[re.Match]:
    explicit = EXPLICIT_FORMAL_CALL_RE.search(code)
    if explicit:
        return explicit
    unqualified = UNQUALIFIED_FORMAL_CALL_RE.search(code)
    if not unqualified:
        return None
    if re.search(r"\bdef\s*$", code[:unqualified.start()]):
        return None
    if CHISEL_ASSERT_HINT_RE.search(code) or formal_library or harness:
        return unqualified
    return None


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
