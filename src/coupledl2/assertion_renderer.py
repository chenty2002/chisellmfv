"""Render repository templates into a single profile marker transactionally."""

from __future__ import annotations

import difflib
import hashlib
import re
from dataclasses import dataclass
from pathlib import Path
from typing import Dict

from .property_catalog import PLACEHOLDER_RE, PropertyCatalog


GENERATED_BEGIN = "// CHISELLMFV_GENERATED_BEGIN"
GENERATED_END = "// CHISELLMFV_GENERATED_END"


class AssertionRenderError(ValueError):
    """Raised before source is changed when a template cannot be rendered."""


@dataclass(frozen=True)
class RenderResult:
    target_path: Path
    rendered_fragments: tuple[str, ...]
    base_label: str
    sha256_before: str
    sha256_after: str
    diff: str


def render_property_source(
    target_path: Path,
    manifest: Dict,
    catalog: PropertyCatalog,
) -> RenderResult:
    """Replace profile markers with repository-owned source and assertion blocks."""
    target_path = Path(target_path)
    original = target_path.read_text(encoding="utf-8")
    marker = catalog.profile["target"]["marker_text"]
    if original.count(marker) != 1:
        raise AssertionRenderError("property marker must occur exactly once")
    if GENERATED_BEGIN in original or GENERATED_END in original:
        raise AssertionRenderError("generated fragment markers already exist")

    instance = manifest["instances"][0]
    template = catalog.templates[instance["template_id"]]
    replacements = {
        role: catalog.candidates[candidate_id]["expression"]
        for role, candidate_id in instance["bindings"].items()
    }
    replacements.update(
        {name: str(value) for name, value in instance["parameters"].items()}
    )
    replacements["base_label"] = instance["base_label"]
    assertion = _render_fragment(
        template["fragments"]["assertion_block"],
        replacements,
    )
    support = template["fragments"]["support_block"]
    if support:
        allowed_support = set(replacements) | {"ASSERTION_BLOCK"}
        unknown_support = set(PLACEHOLDER_RE.findall(support)) - allowed_support
        if unknown_support:
            raise AssertionRenderError(
                f"undeclared placeholder in support block: {sorted(unknown_support)}"
            )
        if support.count("{{ASSERTION_BLOCK}}") != 1:
            raise AssertionRenderError("support block must contain one assertion placeholder")
        rendered_support = _render_fragment(
            support.replace("{{ASSERTION_BLOCK}}", assertion),
            replacements,
        )
        rendered = rendered_support
        fragments = (support, assertion)
    else:
        rendered = assertion
        fragments = (assertion,)
    if PLACEHOLDER_RE.search(rendered):
        raise AssertionRenderError("undeclared placeholder remains after rendering")

    updated = _render_into_marker(original, marker, rendered)
    if template.get("requires_formal_mixin") or "fvAssert(" in rendered:
        formal_anchor = "new LazyModuleImp(this) with Formal {"
        plain_anchor = "new LazyModuleImp(this) {"
        if updated.count(formal_anchor) == 0:
            if updated.count(plain_anchor) != 1:
                raise AssertionRenderError(
                    "Formal mixin anchor must occur exactly once"
                )
            updated = updated.replace(plain_anchor, formal_anchor, 1)
        elif updated.count(formal_anchor) != 1:
            raise AssertionRenderError("Formal mixin must be unique")

    source_updates: Dict[Path, tuple[str, str]] = {}
    source_block = template["fragments"].get("source_block", "")
    source_targets = catalog.profile.get("source_targets", [])
    if source_block and not source_targets:
        raise AssertionRenderError("source block requires at least one source target")
    if source_targets and not source_block:
        raise AssertionRenderError("source target requires a source block")
    case_root = _case_root_for_target(
        target_path,
        catalog.profile["target"]["relative_path"],
    )
    for source_target in source_targets:
        source_path = case_root / source_target["relative_path"]
        if not source_path.is_file():
            raise AssertionRenderError(f"source target not found: {source_target['relative_path']}")
        source_original = source_path.read_text(encoding="utf-8")
        source_marker = source_target["marker_text"]
        if source_original.count(source_marker) != 1:
            raise AssertionRenderError("source marker must occur exactly once")
        source_replacements = dict(replacements)
        source_replacements["source_label"] = instance["base_label"]
        rendered_source = _render_fragment(source_block, source_replacements)
        source_updated = _render_into_marker(source_original, source_marker, rendered_source)
        source_updates[source_path] = (source_original, source_updated)
        fragments = (*fragments, rendered_source)

    before_hash = hashlib.sha256(original.encode("utf-8")).hexdigest()
    after_hash = hashlib.sha256(updated.encode("utf-8")).hexdigest()
    diff = "".join(
        difflib.unified_diff(
            original.splitlines(keepends=True),
            updated.splitlines(keepends=True),
            fromfile=str(target_path),
            tofile=str(target_path),
        )
    )
    for source_path, (source_original, source_updated) in source_updates.items():
        diff += "".join(
            difflib.unified_diff(
                source_original.splitlines(keepends=True),
                source_updated.splitlines(keepends=True),
                fromfile=str(source_path),
                tofile=str(source_path),
            )
        )
    for source_path, (_, source_updated) in source_updates.items():
        source_path.write_text(source_updated, encoding="utf-8")
    target_path.write_text(updated, encoding="utf-8")
    return RenderResult(
        target_path=target_path,
        rendered_fragments=fragments,
        base_label=instance["base_label"],
        sha256_before=before_hash,
        sha256_after=after_hash,
        diff=diff,
    )


def _render_fragment(fragment: str, replacements: Dict[str, str]) -> str:
    names = set(PLACEHOLDER_RE.findall(fragment))
    unknown = names - set(replacements)
    if unknown:
        raise AssertionRenderError(f"undeclared placeholder: {sorted(unknown)}")
    rendered = fragment
    for name in sorted(names):
        rendered = rendered.replace("{{" + name + "}}", replacements[name])
    if PLACEHOLDER_RE.search(rendered):
        raise AssertionRenderError("undeclared placeholder remains after rendering")
    return rendered


def _render_into_marker(original: str, marker: str, rendered: str) -> str:
    marker_line = next(
        line for line in original.splitlines() if marker in line
    )
    indent = re.match(r"\s*", marker_line).group(0) + "  "
    block_lines = [GENERATED_BEGIN, *rendered.splitlines(), GENERATED_END]
    block = "\n".join(indent + line if line else "" for line in block_lines)
    return original.replace(marker, marker + "\n" + block, 1)


def _case_root_for_target(target_path: Path, target_relative_path: str) -> Path:
    root = target_path
    for _part in Path(target_relative_path).parts:
        root = root.parent
    return root
