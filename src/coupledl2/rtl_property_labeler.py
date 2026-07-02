"""Deterministically name elaborated RTL properties for traceability."""

from __future__ import annotations

import re
from dataclasses import dataclass
from pathlib import Path
from typing import Dict, Sequence

from .property_catalog import PropertyCatalog


CONCRETE_LABEL_RE = re.compile(r"\bCL2_[A-Z0-9_]+__E\d+\b")
ASSERT_RE = re.compile(
    r"^(?P<indent>\s*)(?:(?P<label>[A-Za-z_][A-Za-z0-9_$]*)\s*:\s*)?"
    r"(?P<body>assert\s*(?:property\s*)?\()",
    re.IGNORECASE,
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
    occurrences: list[tuple[Path, int]] = []
    texts: Dict[Path, list[str]] = {}

    for path in files:
        if not path.is_file():
            continue
        text = path.read_text(encoding="utf-8", errors="ignore")
        existing.extend(CONCRETE_LABEL_RE.findall(text))
        lines = text.splitlines(keepends=True)
        texts[path] = lines
        last_source_line = -100
        for index, line in enumerate(lines):
            if suffix in line:
                last_source_line = index
            assertion = ASSERT_RE.match(line)
            if assertion and index - last_source_line <= 8:
                if assertion.group("label"):
                    raise RTLPropertyLabelError("matching RTL property already has a label")
                occurrences.append((path, index))

    if existing:
        raise RTLPropertyLabelError("generated RTL contains baseline or duplicate CL2 labels")
    minimum = int(match_contract["minimum_occurrences"])
    if len(occurrences) < minimum:
        raise RTLPropertyLabelError("no matching generated RTL properties")
    if not match_contract["allow_multiple_occurrences"] and len(occurrences) != 1:
        raise RTLPropertyLabelError("template forbids multiple RTL property matches")

    results: list[RTLProperty] = []
    per_file: Dict[Path, list[tuple[int, str]]] = {}
    for elaboration_index, (path, line_index) in enumerate(
        sorted(occurrences, key=lambda item: (item[0].as_posix(), item[1]))
    ):
        label = f"{base_label}__E{elaboration_index}"
        per_file.setdefault(path, []).append((line_index, label))
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
        for line_index, label in edits:
            match = ASSERT_RE.match(lines[line_index])
            if match is None:
                raise RTLPropertyLabelError("RTL property changed during labelling")
            lines[line_index] = (
                match.group("indent")
                + label
                + ": "
                + lines[line_index][len(match.group("indent")):]
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
