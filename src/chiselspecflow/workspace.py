"""Pure path contracts for isolated SpecFlow runs and immutable rounds."""

from __future__ import annotations

import re
from dataclasses import dataclass
from pathlib import Path
from typing import Optional

from .config import RUN_MANIFEST_SCHEMA_VERSION, SpecFlowRunConfig
from .stages import get_stage_spec, stage_contract_snapshot


_SHA256_RE = re.compile(r"^[0-9a-f]{64}$")


@dataclass(frozen=True)
class SpecFlowRound:
    round_id: int
    parent_round: Optional[int] = None
    revision_request_sha256: Optional[str] = None

    def __post_init__(self) -> None:
        if (
            not isinstance(self.round_id, int)
            or isinstance(self.round_id, bool)
            or self.round_id < 1
        ):
            raise ValueError("round_id must be a positive integer")
        if self.parent_round is not None:
            if (
                not isinstance(self.parent_round, int)
                or isinstance(self.parent_round, bool)
                or self.parent_round < 1
                or self.parent_round >= self.round_id
            ):
                raise ValueError("parent_round must be a positive earlier round")
            if (
                not isinstance(self.revision_request_sha256, str)
                or not _SHA256_RE.fullmatch(self.revision_request_sha256)
            ):
                raise ValueError(
                    "a child round requires a lowercase revision request SHA-256"
                )
        elif self.revision_request_sha256 is not None:
            raise ValueError("the first round cannot have a revision request hash")

    @property
    def directory_name(self) -> str:
        return f"{self.round_id:04d}"


@dataclass(frozen=True)
class SpecFlowWorkspace:
    """Resolved workspace layout without any Iteration-1 creation side effects."""

    run_dir: Path
    config: SpecFlowRunConfig

    def __post_init__(self) -> None:
        object.__setattr__(self, "run_dir", Path(self.run_dir).resolve())

    @property
    def manifest_path(self) -> Path:
        return self.run_dir / "manifest.json"

    @property
    def inputs_dir(self) -> Path:
        return self.run_dir / "inputs"

    @property
    def project_workspace(self) -> Path:
        return self.run_dir / "workspace" / "project"

    @property
    def indexes_dir(self) -> Path:
        return self.run_dir / "indexes"

    @property
    def logs_dir(self) -> Path:
        return self.run_dir / "logs"

    @property
    def rounds_dir(self) -> Path:
        return self.run_dir / "rounds"

    @property
    def final_result_path(self) -> Path:
        return self.run_dir / "final_result.json"

    @property
    def cost_summary_path(self) -> Path:
        return self.run_dir / "run_cost_summary.json"

    def round_dir(self, round_ref: SpecFlowRound | int) -> Path:
        ref = (
            round_ref
            if isinstance(round_ref, SpecFlowRound)
            else SpecFlowRound(round_ref)
        )
        return self.rounds_dir / ref.directory_name

    def stage_dir(self, round_ref: SpecFlowRound | int, stage: str) -> Path:
        return self.round_dir(round_ref) / get_stage_spec(stage).directory_name

    def manifest_contract(self) -> dict:
        """Return only the fields frozen before Iteration 1 fills live hashes."""

        return {
            "schema_version": RUN_MANIFEST_SCHEMA_VERSION,
            "copy_strategy": self.config.copy_strategy,
            "stages": list(stage_contract_snapshot()),
            "rounds_root": "rounds",
            "workspace_project": "workspace/project",
        }
