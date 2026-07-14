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
    base_labels: tuple[str, ...]
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

    rendered_instances = []
    fragments = ()
    source_renderings: Dict[Path, list[str]] = {}
    case_root = _case_root_for_target(target_path, catalog.profile["target"]["relative_path"])
    for instance in manifest["instances"]:
        template = catalog.templates[instance["template_id"]]
        replacements = {
            role: catalog.candidates[candidate_id]["expression"]
            for role, candidate_id in instance["bindings"].items()
        }
        replacements.update({name: str(value) for name, value in instance["parameters"].items()})
        replacements["base_label"] = instance["base_label"]
        assertion = _render_fragment(template["fragments"]["assertion_block"], replacements)
        assertion += "\n" + _render_evidence_covers(
            template["evidence_fragments"], replacements
        )
        support = template["fragments"]["support_block"]
        if support:
            allowed_support = set(replacements) | {"ASSERTION_BLOCK"}
            unknown_support = set(PLACEHOLDER_RE.findall(support)) - allowed_support
            if unknown_support or support.count("{{ASSERTION_BLOCK}}") != 1:
                raise AssertionRenderError("invalid support block placeholders")
            rendered = _render_fragment(support.replace("{{ASSERTION_BLOCK}}", assertion), replacements)
            fragments = (*fragments, support, assertion)
        else:
            rendered = assertion
            fragments = (*fragments, assertion)
        rendered_instances.append(rendered)
        source_block = template["fragments"].get("source_block", "")
        source_targets = catalog.profile.get("source_targets", [])
        if bool(source_block) != bool(source_targets):
            raise AssertionRenderError("source block and source targets must be declared together")
        for source_target in source_targets:
            source_path = case_root / source_target["relative_path"]
            source_replacements = dict(replacements)
            source_replacements["source_label"] = instance["base_label"]
            rendered_source = _render_fragment(source_block, source_replacements)
            source_renderings.setdefault(source_path, []).append(rendered_source)
            fragments = (*fragments, rendered_source)

    rendered = "\n".join(rendered_instances)
    updated = _render_into_marker(original, marker, rendered)
    if any(catalog.templates[item["template_id"]].get("requires_formal_mixin") for item in manifest["instances"]) or "fvAssert(" in rendered:
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
    for source_path, blocks in source_renderings.items():
        if not source_path.is_file():
            raise AssertionRenderError(f"source target not found: {source_path}")
        source_original = source_path.read_text(encoding="utf-8")
        source_target = next(item for item in catalog.profile["source_targets"] if case_root / item["relative_path"] == source_path)
        source_marker = source_target["marker_text"]
        if source_original.count(source_marker) != 1:
            raise AssertionRenderError("source marker must occur exactly once")
        source_updated = _render_into_marker(source_original, source_marker, "\n".join(blocks))
        source_updates[source_path] = (source_original, source_updated)

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
        base_label=manifest["instances"][0]["base_label"],
        base_labels=tuple(item["base_label"] for item in manifest["instances"]),
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


def _render_evidence_covers(evidence: Dict, replacements: Dict[str, str]) -> str:
    """Render repository-owned non-vacuity goals next to the primary property."""
    base_label = replacements["base_label"]
    rows = []
    for event_id, expression in sorted(evidence["events"].items()):
        rows.append(("trigger_cover", event_id, expression))
    for role, group in (
        ("observer_cover", evidence["observers"]),
        ("state_cover", evidence["states"]),
    ):
        for evidence_id, targets in sorted(group.items()):
            for target in targets:
                rows.append(
                    (role, f"{evidence_id}__{target['target']}", target["expression"])
                )
    rows.append(("assumption_sat", "environment", evidence["assumption_guard"]))
    return "\n".join(
        "chisel3.cover("
        + _render_fragment(expression, replacements)
        + f', "{base_label}__NV__{role}__{_safe_label(target)}")'
        for role, target, expression in rows
    )


def _safe_label(value: str) -> str:
    return re.sub(r"[^A-Za-z0-9_]+", "_", value).strip("_")


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
