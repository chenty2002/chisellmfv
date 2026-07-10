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
    r"(?P<body>assert\s*(?:property\s*)?\()",
    re.IGNORECASE,
)
STANDALONE_LABEL_RE = re.compile(
    r"^(?P<indent>\s*)(?P<label>[A-Za-z_][A-Za-z0-9_$]*)\s*:\s*$"
)


class RTLPropertyLabelError(ValueError):
    """Raised when generated RTL cannot be labelled without ambiguity."""


@dataclass(frozen=True)
class RTLProperty:
    rtl_label: str
    rtl_file: str
    rtl_line: int
    elaboration_index: int


def label_rtl_properties(
    generated_files: Sequence[Path],
    manifest: Dict,
    catalog: PropertyCatalog,
) -> tuple[RTLProperty, ...]:
    """Label every elaboration matching the selected template source annotation."""
    instance = manifest["instances"][0]
    template = catalog.templates[instance["template_id"]]
    match_contract = template["rtl_match"]
    suffix = match_contract["source_annotation_suffix"]
    base_label = instance["base_label"]
    files = sorted({Path(path).resolve() for path in generated_files})
    existing: list[str] = []
    occurrences: list[tuple[Path, int, int, str | None]] = []
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
            if existing_label:
                generated_label = re.fullmatch(r"_GEN_\d+", existing_label)
                base_expansion = re.fullmatch(
                    rf"{re.escape(base_label)}(?:_\d+)?",
                    existing_label,
                )
                if not generated_label and not base_expansion:
                    raise RTLPropertyLabelError(
                        "matching RTL property has a non-generated label"
                    )
            occurrences.append((path, index, label_line, existing_label))

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
    if not match_contract["allow_multiple_occurrences"] and len(occurrences) != 1:
        raise RTLPropertyLabelError("template forbids multiple RTL property matches")

    results: list[RTLProperty] = []
    per_file: Dict[Path, list[tuple[int, int, str | None, str]]] = {}
    for elaboration_index, (path, line_index, label_line, existing_label) in enumerate(
        sorted(occurrences, key=lambda item: (item[0].as_posix(), item[1]))
    ):
        label = f"{base_label}__E{elaboration_index}"
        per_file.setdefault(path, []).append(
            (line_index, label_line, existing_label, label)
        )
        results.append(
            RTLProperty(
                rtl_label=label,
                rtl_file=str(path),
                rtl_line=line_index + 1,
                elaboration_index=elaboration_index,
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
