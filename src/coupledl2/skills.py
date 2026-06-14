"""CoupledL2 context asset loading."""

from __future__ import annotations

from pathlib import Path
from typing import Dict, List


STAGE_SKILLS: Dict[str, List[str]] = {
    "build_top_module": ["coupledl2_build.md", "coupledl2_harness.md"],
    "write_assertions": ["tilelink_protocol.md", "bounded_liveness.md", "chiselfv_assertions.md"],
    "invoke_verification": ["jaspergold.md"],
    "waveform_explanation": ["waveform_diagnosis.md"],
    "propose_bugfix": ["repair_regression.md"],
}

STAGE_RULES: Dict[str, List[str]] = {
    "build_top_module": ["agent_rules.md"],
    "write_assertions": ["agent_rules.md"],
    "invoke_verification": ["agent_rules.md"],
    "waveform_explanation": ["agent_rules.md"],
    "propose_bugfix": ["agent_rules.md"],
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


def stage_skill_paths(workspace_dir: Path, stage: str) -> List[Path]:
    return [workspace_dir / "skills" / name for name in STAGE_SKILLS.get(stage, [])]


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
