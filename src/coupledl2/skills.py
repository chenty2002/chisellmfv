"""CoupledL2 context asset loading."""

from __future__ import annotations

from pathlib import Path
from typing import Any, Dict, List, Optional


STAGE_SKILLS: Dict[str, List[str]] = {
    "waveform_explanation": ["waveform_diagnosis.md"],
}

STAGE_RULES: Dict[str, List[str]] = {
    "waveform_explanation": ["agent_rules.md"],
}


def install_context_assets(workspace_dir: Path) -> None:
    """Copy built-in skills/rules into the run workspace."""
    for subdir in ["skills", "rules"]:
        (workspace_dir / subdir).mkdir(parents=True, exist_ok=True)

    asset_root = Path(__file__).resolve().parent / "context_assets"
    for relative_path in _asset_paths():
        text = (asset_root / relative_path).read_text(encoding="utf-8")
        dst = workspace_dir / relative_path
        dst.parent.mkdir(parents=True, exist_ok=True)
        dst.write_text(text, encoding="utf-8")


def stage_skill_paths(
    workspace_dir: Path,
    stage: str,
    context_indexes: Optional[Dict[str, Dict[str, Any]]] = None,
) -> List[Path]:
    names = list(STAGE_SKILLS.get(stage, []))
    return [workspace_dir / "skills" / name for name in names]


def stage_rule_paths(workspace_dir: Path, stage: str) -> List[Path]:
    return [workspace_dir / "rules" / name for name in STAGE_RULES.get(stage, [])]


def _asset_paths() -> List[str]:
    paths = ["rules/agent_rules.md"]
    for names in STAGE_SKILLS.values():
        for name in names:
            path = f"skills/{name}"
            if path not in paths:
                paths.append(path)
    return paths
