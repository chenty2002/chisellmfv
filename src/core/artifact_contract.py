"""Workflow-independent stage artifact and handoff contracts.

The helpers in this module deliberately know only a stage name and its exact
artifact list.  Workflow-specific stage enums, reducers, and business payloads
remain outside the generic core.
"""

from __future__ import annotations

import hashlib
import json
from pathlib import Path
from typing import Any, Dict, Optional, Protocol, Tuple


class StageContract(Protocol):
    """Minimal structural contract required by the artifact helpers."""

    name: str
    artifact_contract: Tuple[str, ...]


class StageArtifactError(ValueError):
    """Raised when a successful stage does not satisfy its declared contract."""


def file_sha256(path: Path) -> str:
    """Return the lowercase SHA-256 digest of one file."""

    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def validate_stage_artifacts(
    stage_dir: Path, spec: StageContract
) -> Dict[str, Dict[str, Any]]:
    """Validate and hash exactly the artifacts declared by ``spec``."""

    stage_dir = Path(stage_dir)
    records: Dict[str, Dict[str, Any]] = {}
    allow_empty = set(getattr(spec, "allow_empty_artifacts", ()))
    for relative in spec.artifact_contract:
        path = stage_dir / relative
        if not path.is_file():
            raise StageArtifactError(
                f"{spec.name} artifact contract is missing {relative}"
            )
        size = path.stat().st_size
        if size <= 0 and relative not in allow_empty:
            raise StageArtifactError(
                f"{spec.name} artifact contract contains empty {relative}"
            )
        if path.suffix == ".json":
            try:
                payload = json.loads(path.read_text(encoding="utf-8"))
            except (OSError, json.JSONDecodeError) as exc:
                raise StageArtifactError(
                    f"{spec.name} artifact is not valid JSON: {relative}"
                ) from exc
            if not isinstance(payload, dict):
                raise StageArtifactError(
                    f"{spec.name} JSON artifact must be an object: {relative}"
                )
        elif path.suffix == ".jsonl":
            try:
                lines = [
                    line
                    for line in path.read_text(encoding="utf-8").splitlines()
                    if line.strip()
                ]
                if not lines and relative not in allow_empty:
                    raise ValueError("no records")
                for line in lines:
                    if not isinstance(json.loads(line), dict):
                        raise ValueError("record is not an object")
            except (OSError, json.JSONDecodeError, ValueError) as exc:
                raise StageArtifactError(
                    f"{spec.name} artifact is not valid JSONL: {relative}"
                ) from exc
        records[relative] = {
            "path": relative,
            "sha256": file_sha256(path),
            "bytes": size,
        }
    return records


def write_stage_outcome(
    stage_dir: Path,
    spec: StageContract,
    stage_result: Dict[str, Any],
    *,
    source_state: Optional[Dict[str, Any]] = None,
) -> Dict[str, Any]:
    """Write one stage result and one reference-only handoff from the same data."""

    stage_dir = Path(stage_dir)
    stage_dir.mkdir(parents=True, exist_ok=True)
    normalized = dict(stage_result)
    normalized.setdefault("schema_version", "stage_result")
    normalized["stage"] = spec.name
    success = normalized.get("success") is True
    artifact_records = validate_stage_artifacts(stage_dir, spec) if success else {}
    normalized["artifact_contract"] = list(spec.artifact_contract)
    normalized["artifacts"] = artifact_records
    _write_json(stage_dir / "stage_result.json", normalized)

    handoff: Dict[str, Any] = {
        "schema_version": "stage_handoff",
        "stage": spec.name,
        "success": success,
        "stage_result": {
            "path": "stage_result.json",
            "sha256": file_sha256(stage_dir / "stage_result.json"),
        },
        "artifacts": artifact_records,
    }
    if not success:
        handoff["error_kind"] = normalized.get("error_kind")
    if source_state:
        handoff["source_state"] = {
            key: source_state[key]
            for key in ("workspace_hash", "index_hashes")
            if key in source_state
        }
    _write_json(stage_dir / "handoff.json", handoff)
    return normalized


def validate_completed_stage(
    stage_dir: Path, spec: StageContract
) -> Optional[Dict[str, Any]]:
    """Return a result only when result, handoff, hashes, and contract agree."""

    stage_dir = Path(stage_dir)
    result_path = stage_dir / "stage_result.json"
    handoff_path = stage_dir / "handoff.json"
    if not result_path.is_file() or not handoff_path.is_file():
        return None
    try:
        result = json.loads(result_path.read_text(encoding="utf-8"))
        handoff = json.loads(handoff_path.read_text(encoding="utf-8"))
        if (
            result.get("stage") != spec.name
            or result.get("success") is not True
            or handoff.get("schema_version") != "stage_handoff"
            or handoff.get("stage") != spec.name
            or handoff.get("success") is not True
            or handoff.get("stage_result", {}).get("sha256")
            != file_sha256(result_path)
        ):
            return None
        actual = validate_stage_artifacts(stage_dir, spec)
        if handoff.get("artifacts") != actual or result.get("artifacts") != actual:
            return None
        if result.get("artifact_contract") != list(spec.artifact_contract):
            return None
        return result
    except (OSError, json.JSONDecodeError, StageArtifactError):
        return None


def _write_json(path: Path, value: Dict[str, Any]) -> None:
    path.write_text(
        json.dumps(value, indent=2, ensure_ascii=False, sort_keys=True) + "\n",
        encoding="utf-8",
    )
