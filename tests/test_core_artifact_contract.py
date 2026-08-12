from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
import sys

import pytest


ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))


from src.core.artifact_contract import (  # noqa: E402
    StageArtifactError,
    validate_completed_stage,
    validate_stage_artifacts,
    write_stage_outcome,
)


@dataclass(frozen=True)
class _Stage:
    name: str = "unit"
    artifact_contract: tuple[str, ...] = ("result.json", "events.jsonl")


def _write_artifacts(stage_dir: Path) -> None:
    (stage_dir / "result.json").write_text('{"ok": true}\n', encoding="utf-8")
    (stage_dir / "events.jsonl").write_text('{"event": "done"}\n', encoding="utf-8")


def test_generic_artifact_contract_detects_hash_mismatch(tmp_path):
    _write_artifacts(tmp_path)
    spec = _Stage()
    written = write_stage_outcome(tmp_path, spec, {"success": True})

    assert written["stage"] == "unit"
    assert validate_completed_stage(tmp_path, spec) == written

    (tmp_path / "result.json").write_text('{"ok": false}\n', encoding="utf-8")
    assert validate_completed_stage(tmp_path, spec) is None


def test_generic_artifact_contract_rejects_missing_and_invalid_rows(tmp_path):
    spec = _Stage()
    (tmp_path / "result.json").write_text('{}\n', encoding="utf-8")
    with pytest.raises(StageArtifactError, match="missing events.jsonl"):
        validate_stage_artifacts(tmp_path, spec)

    (tmp_path / "events.jsonl").write_text('[]\n', encoding="utf-8")
    with pytest.raises(StageArtifactError, match="not valid JSONL"):
        validate_stage_artifacts(tmp_path, spec)
