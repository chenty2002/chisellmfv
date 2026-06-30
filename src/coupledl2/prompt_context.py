"""Bounded static prompt assets for CoupledL2 stages."""

from __future__ import annotations

import hashlib
from pathlib import Path
from typing import Any, Dict, Iterable, List


MAX_PROMPT_ASSET_CHARS = 8_000
MAX_PROMPT_BUNDLE_CHARS = 16_000


def build_prompt_bundle(
    workspace_dir: Path,
    *,
    rules: Iterable[Path],
    skills: Iterable[Path],
) -> List[Dict[str, Any]]:
    """Load one stage's selected rules and skills into a bounded prompt bundle."""
    workspace_root = workspace_dir.resolve()
    selected = [("rules", path) for path in rules]
    selected.extend(("skills", path) for path in skills)
    bundle: List[Dict[str, Any]] = []
    seen: set[Path] = set()
    total_chars = 0

    for asset_kind, path in selected:
        path = Path(path)
        if path.is_symlink():
            raise ValueError(f"prompt asset must not be a symlink: {path}")
        if not path.exists() or not path.is_file():
            raise ValueError(f"missing prompt asset: {path}")

        resolved = path.resolve()
        expected_root = (workspace_root / asset_kind).resolve()
        if resolved in seen:
            raise ValueError(f"duplicate prompt asset: {path}")
        if expected_root not in resolved.parents:
            raise ValueError(f"prompt asset escapes workspace/{asset_kind}: {path}")
        seen.add(resolved)

        try:
            content = resolved.read_text(encoding="utf-8")
        except UnicodeDecodeError as exc:
            raise ValueError(f"prompt asset is not valid UTF-8: {path}") from exc
        chars = len(content)
        if chars > MAX_PROMPT_ASSET_CHARS:
            raise ValueError(
                f"prompt asset {resolved.relative_to(workspace_root)} exceeds "
                f"{MAX_PROMPT_ASSET_CHARS} characters"
            )
        total_chars += chars
        if total_chars > MAX_PROMPT_BUNDLE_CHARS:
            raise ValueError(
                f"stage prompt bundle exceeds {MAX_PROMPT_BUNDLE_CHARS} characters"
            )
        bundle.append(
            {
                "path": resolved.relative_to(workspace_root).as_posix(),
                "sha256": hashlib.sha256(content.encode("utf-8")).hexdigest(),
                "chars": chars,
                "content": content,
            }
        )

    return bundle
