"""Single source of truth for active CoupledL2 workflow stages."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Dict, Optional, Tuple


@dataclass(frozen=True)
class StageSpec:
    name: str
    ordinal: int
    execution_kind: str
    required_predecessor: Optional[str]
    tool_budget: int
    model_turn_budget: int
    token_budget: Optional[int]
    completion_gate: str
    artifact_contract: Tuple[str, ...]

    @property
    def directory_name(self) -> str:
        return f"{self.ordinal:02d}_{self.name}"


STAGE_SPECS: Tuple[StageSpec, ...] = (
    StageSpec(
        name="write_assertions",
        ordinal=2,
        execution_kind="agent",
        required_predecessor="preflight",
        tool_budget=24,
        model_turn_budget=10,
        token_budget=None,
        completion_gate="assertion_compilation",
        artifact_contract=("assertion_map.json", "generated_assertion_scan.json"),
    ),
    StageSpec(
        name="invoke_verification",
        ordinal=3,
        execution_kind="deterministic",
        required_predecessor="write_assertions",
        tool_budget=0,
        model_turn_budget=0,
        token_budget=None,
        completion_gate="formal_result",
        artifact_contract=("formal_result.json", "property_status.json"),
    ),
    StageSpec(
        name="waveform_explanation",
        ordinal=4,
        execution_kind="agent",
        required_predecessor="invoke_verification",
        tool_budget=18,
        model_turn_budget=8,
        token_budget=None,
        completion_gate="diagnosis",
        artifact_contract=("diagnosis.json", "counterexample_analysis.md"),
    ),
    StageSpec(
        name="propose_bugfix",
        ordinal=5,
        execution_kind="agent",
        required_predecessor="waveform_explanation",
        tool_budget=18,
        model_turn_budget=10,
        token_budget=None,
        completion_gate="repair_regression",
        artifact_contract=("repair_result.json", "repair_history.json"),
    ),
)

_STAGE_BY_NAME: Dict[str, StageSpec] = {spec.name: spec for spec in STAGE_SPECS}
COUPLEDL2_STAGES = [spec.name for spec in STAGE_SPECS]


def get_stage_spec(stage: str) -> StageSpec:
    try:
        return _STAGE_BY_NAME[stage]
    except KeyError as exc:
        raise ValueError(f"unknown CoupledL2 stage: {stage}") from exc
