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
    """Replace the profile marker with one generated repository-owned block."""
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
        unknown_support = set(PLACEHOLDER_RE.findall(support)) - {"ASSERTION_BLOCK"}
        if unknown_support:
            raise AssertionRenderError(
                f"undeclared placeholder in support block: {sorted(unknown_support)}"
            )
        if support.count("{{ASSERTION_BLOCK}}") != 1:
            raise AssertionRenderError("support block must contain one assertion placeholder")
        rendered = support.replace("{{ASSERTION_BLOCK}}", assertion)
        fragments = (support, assertion)
    else:
        rendered = assertion
        fragments = (assertion,)
    if PLACEHOLDER_RE.search(rendered):
        raise AssertionRenderError("undeclared placeholder remains after rendering")

    marker_line = next(
        line for line in original.splitlines() if marker in line
    )
    indent = re.match(r"\s*", marker_line).group(0) + "  "
    block_lines = [GENERATED_BEGIN, *rendered.splitlines(), GENERATED_END]
    block = "\n".join(indent + line if line else "" for line in block_lines)
    updated = original.replace(marker, marker + "\n" + block, 1)
    if "fvAssert(" in rendered:
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
