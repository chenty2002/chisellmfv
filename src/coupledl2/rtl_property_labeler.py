"""Deterministically name elaborated RTL properties for traceability."""

from __future__ import annotations

import re
from dataclasses import dataclass
from pathlib import Path
from typing import Dict, Sequence

from .property_catalog import PropertyCatalog


CONCRETE_LABEL_RE = re.compile(r"\b(?:CL2|TL)_[A-Z0-9_]+__E\d+\b")
ASSERT_RE = re.compile(
    r"^(?P<indent>\s*)(?:(?P<label>[A-Za-z_][A-Za-z0-9_$]*)\s*:\s*)?"
    r"(?P<body>(?P<kind>assert|cover)\s*(?:property\s*)?\()",
    re.IGNORECASE,
)
STANDALONE_LABEL_RE = re.compile(
    r"^(?P<indent>\s*)(?P<label>[A-Za-z_][A-Za-z0-9_$]*)\s*:\s*$"
)


class RTLPropertyLabelError(ValueError):
    """Raised when generated RTL cannot be labelled without ambiguity."""


@dataclass(frozen=True)
class RTLProperty:
    instance_id: str
    rtl_label: str
    rtl_file: str
    rtl_line: int
    elaboration_index: int
    role: str
    target: str


def label_rtl_properties(
    generated_files: Sequence[Path],
    manifest: Dict,
    catalog: PropertyCatalog,
    *,
    require_evidence: bool = False,
) -> tuple[RTLProperty, ...]:
    """Label every elaboration matching the selected template source annotation."""
    suffixes = (
        [catalog.templates[manifest["instances"][0]["template_id"]]["rtl_match"]["source_annotation_suffix"]]
        if len(manifest["instances"]) == 1
        else [
            _instance_source_suffix(generated_files, item, catalog)
            for item in manifest["instances"]
        ]
    )
    if len(suffixes) != len(set(suffixes)):
        raise RTLPropertyLabelError(
            "batch instances require distinct RTL source annotations"
        )
    results: list[RTLProperty] = []
    for instance, suffix in zip(manifest["instances"], suffixes):
        results.extend(
            _label_single(
                generated_files,
                instance,
                catalog,
                suffix,
                require_evidence=require_evidence,
            )
        )
    labels = [item.rtl_label for item in results]
    if len(set(labels)) != len(labels):
        raise RTLPropertyLabelError("concrete RTL labels are not globally unique")
    return tuple(results)


def _label_single(
    generated_files: Sequence[Path],
    instance: Dict,
    catalog: PropertyCatalog,
    source_suffix: str,
    *,
    require_evidence: bool,
) -> tuple[RTLProperty, ...]:
    template = catalog.templates[instance["template_id"]]
    match_contract = template["rtl_match"]
    suffix = source_suffix
    base_label = instance["base_label"]
    evidence_roots = _evidence_roots(base_label, template["evidence_fragments"])
    files = sorted({Path(path).resolve() for path in generated_files})
    existing: list[str] = []
    occurrences: list[tuple[Path, int, int, str | None, str, str]] = []
    texts: Dict[Path, list[str]] = {}

    for path in files:
        if not path.is_file():
            continue
        text = path.read_text(encoding="utf-8", errors="ignore")
        existing.extend(CONCRETE_LABEL_RE.findall(text))
        lines = text.splitlines(keepends=True)
        texts[path] = lines
        for index, line in enumerate(lines):
            assertion = ASSERT_RE.match(line)
            if assertion is None:
                continue
            end = index
            while end < len(lines) and ";" not in lines[end]:
                end += 1
            if end >= len(lines):
                raise RTLPropertyLabelError("generated RTL property is unterminated")
            statement = "".join(lines[index:end + 1])
            if suffix not in statement:
                continue
            label_line = index
            existing_label = assertion.group("label")
            if existing_label is None and index > 0:
                standalone = STANDALONE_LABEL_RE.match(
                    lines[index - 1].rstrip("\r\n")
                )
                if standalone is not None:
                    label_line = index - 1
                    existing_label = standalone.group("label")
            kind = assertion.group("kind").lower()
            if kind == "assert":
                generated_label = existing_label and re.fullmatch(r"_GEN_\d+", existing_label)
                base_expansion = existing_label and re.fullmatch(
                    rf"{re.escape(base_label)}(?:_\d+)?",
                    existing_label,
                )
                if existing_label and not generated_label and not base_expansion:
                    raise RTLPropertyLabelError(
                        "matching RTL assertion has a non-generated label"
                    )
                role, target = "primary_assertion", "primary"
            else:
                evidence_label = existing_label or _source_evidence_label(
                    path,
                    statement,
                    instance,
                    catalog,
                )
                matched = next(
                    (
                        (role, target)
                        for root, role, target in evidence_roots
                        if evidence_label == root
                        or (
                            isinstance(evidence_label, str)
                            and re.fullmatch(re.escape(root) + r"_\d+", evidence_label)
                        )
                    ),
                    None,
                )
                if matched is None:
                    raise RTLPropertyLabelError(
                        "matching RTL cover has no repository-owned evidence label: "
                        f"{evidence_label!r}"
                    )
                role, target = matched
            occurrences.append((path, index, label_line, existing_label, role, target))

    conflicting = sorted(
        label for label in existing if label.startswith(base_label + "__E")
    )
    if conflicting:
        raise RTLPropertyLabelError(
            "generated RTL already contains labels owned by the selected property instance"
        )
    minimum = int(match_contract["minimum_occurrences"])
    if len(occurrences) < minimum:
        raise RTLPropertyLabelError("no matching generated RTL properties")
    expected_evidence = {(role, target) for _root, role, target in evidence_roots}
    observed_evidence = {(role, target) for *_prefix, role, target in occurrences}
    if require_evidence and not expected_evidence <= observed_evidence:
        raise RTLPropertyLabelError("generated RTL omits evidence cover properties")
    if not match_contract["allow_multiple_occurrences"] and len(occurrences) != 1:
        raise RTLPropertyLabelError("template forbids multiple RTL property matches")

    results: list[RTLProperty] = []
    per_file: Dict[Path, list[tuple[int, int, str | None, str]]] = {}
    role_indexes: Dict[tuple[str, str], int] = {}
    primary_index = 0
    for path, line_index, label_line, existing_label, role, target in (
        sorted(occurrences, key=lambda item: (item[0].as_posix(), item[1]))
    ):
        if role == "primary_assertion":
            elaboration_index = primary_index
            primary_index += 1
            label = f"{base_label}__E{elaboration_index}"
        else:
            key = (role, target)
            elaboration_index = role_indexes.get(key, 0)
            role_indexes[key] = elaboration_index + 1
            label = (
                f"{base_label}__NV__{role}__{_safe_label(target)}__E{elaboration_index}"
            )
        per_file.setdefault(path, []).append(
            (line_index, label_line, existing_label, label)
        )
        results.append(
            RTLProperty(
                instance_id=instance["instance_id"],
                rtl_label=label,
                rtl_file=str(path),
                rtl_line=line_index + 1,
                elaboration_index=elaboration_index,
                role=role,
                target=target,
            )
        )
    for path, edits in per_file.items():
        lines = texts[path]
        for line_index, label_line, existing_label, label in edits:
            if label_line != line_index:
                newline = "\n" if lines[label_line].endswith("\n") else ""
                indent = re.match(r"\s*", lines[label_line]).group(0)
                lines[label_line] = f"{indent}{label}:{newline}"
                continue
            match = ASSERT_RE.match(lines[line_index])
            if match is None:
                raise RTLPropertyLabelError("RTL property changed during labelling")
            lines[line_index] = (
                match.group("indent")
                + label
                + ": "
                + lines[line_index][match.start("body"):]
            )
        path.write_text("".join(lines), encoding="utf-8")

    all_text = "\n".join(
        path.read_text(encoding="utf-8", errors="ignore")
        for path in files
        if path.is_file()
    )
    for result in results:
        if len(re.findall(rf"\b{re.escape(result.rtl_label)}\b", all_text)) != 1:
            raise RTLPropertyLabelError("concrete RTL label is not globally unique")
    return tuple(results)


def _instance_source_suffix(
    generated_files: Sequence[Path],
    instance: Dict,
    catalog: PropertyCatalog,
) -> str:
    """Resolve the rendered assertion's exact source line for batch ownership."""
    relative = Path(catalog.profile["target"]["relative_path"])
    for generated in generated_files:
        path = Path(generated).resolve()
        chisel_root = next(
            (parent for parent in (path, *path.parents) if parent.name == "Chisel"),
            None,
        )
        if chisel_root is None:
            continue
        source_relative = Path(*relative.parts[1:]) if relative.parts and relative.parts[0] == "Chisel" else relative
        source = chisel_root / source_relative
        if not source.is_file():
            continue
        matches = [
            index
            for index, line in enumerate(source.read_text(encoding="utf-8").splitlines(), 1)
            if instance["base_label"] in line and "assert" in line
        ]
        if len(matches) == 1:
            annotation_path = source_relative.as_posix()
            return f"{annotation_path} {matches[0]}:"
    return catalog.templates[instance["template_id"]]["rtl_match"]["source_annotation_suffix"]


def _source_evidence_label(
    generated: Path,
    statement: str,
    instance: Dict,
    catalog: PropertyCatalog,
) -> str | None:
    """Resolve an unlabeled RTL cover through its exact Chisel source annotation."""
    relative = Path(catalog.profile["target"]["relative_path"])
    chisel_root = next(
        (parent for parent in (generated, *generated.parents) if parent.name == "Chisel"),
        None,
    )
    if chisel_root is None:
        return None
    source_relative = (
        Path(*relative.parts[1:])
        if relative.parts and relative.parts[0] == "Chisel"
        else relative
    )
    source = chisel_root / source_relative
    if not source.is_file():
        return None
    suffix = catalog.templates[instance["template_id"]]["rtl_match"][
        "source_annotation_suffix"
    ]
    match = re.search(re.escape(suffix) + r"(?:\s+|:)(\d+):", statement)
    if match is None:
        return None
    lines = source.read_text(encoding="utf-8").splitlines()
    line_number = int(match.group(1))
    if line_number < 1 or line_number > len(lines):
        return None
    source_line = lines[line_number - 1]
    label = re.search(
        rf'"({re.escape(instance["base_label"])}__NV__[A-Za-z0-9_]+)"',
        source_line,
    )
    return label.group(1) if label else None


def _evidence_roots(
    base_label: str, evidence: Dict
) -> list[tuple[str, str, str]]:
    rows = [
        (f"{base_label}__NV__trigger_cover__{_safe_label(event_id)}", "trigger_cover", event_id)
        for event_id in sorted(evidence["events"])
    ]
    for role, group in (
        ("observer_cover", evidence["observers"]),
        ("state_cover", evidence["states"]),
    ):
        for evidence_id, targets in sorted(group.items()):
            for target in targets:
                semantic_target = f"{evidence_id}__{target['target']}"
                rows.append(
                    (
                        f"{base_label}__NV__{role}__{_safe_label(semantic_target)}",
                        role,
                        semantic_target,
                    )
                )
    rows.append(
        (
            f"{base_label}__NV__assumption_sat__environment",
            "assumption_sat",
            "environment",
        )
    )
    return sorted(rows, key=lambda item: len(item[0]), reverse=True)


def _safe_label(value: str) -> str:
    return re.sub(r"[^A-Za-z0-9_]+", "_", value).strip("_")
