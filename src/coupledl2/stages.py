"""Single source of truth for active CoupledL2 workflow stages."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Dict, Optional, Tuple


DEFAULT_RUN_TOKEN_BUDGET = 640000


@dataclass(frozen=True)
class StageSpec:
    name: str
    ordinal: int
    execution_kind: str
    required_predecessor: Optional[str]
    tool_budget: int
    discovery_budget: int
    finalization_reserve: int
    model_turn_budget: int
    soft_token_budget: Optional[int]
    token_budget: Optional[int]
    request_max_tokens: int
    compaction_max_tokens: int
    compaction_target_tokens: int
    compaction_digest_limit: int
    tool_result_token_limit: int
    tool_result_batch_token_limit: int
    repair_round_token_budget: Optional[int]
    completion_gate: str
    artifact_contract: Tuple[str, ...]
    completion_request_max_tokens: Optional[int] = None
    completion_gate_repair_limit: int = 0
    completion_error_token_limit: int = 4000

    @property
    def directory_name(self) -> str:
        return f"{self.ordinal:02d}_{self.name}"


STAGE_SPECS: Tuple[StageSpec, ...] = (
    StageSpec(
        name="bind_properties",
        ordinal=2,
        execution_kind="binding",
        required_predecessor="preflight",
        tool_budget=0,
        discovery_budget=0,
        finalization_reserve=0,
        model_turn_budget=2,
        soft_token_budget=None,
        token_budget=4096,
        request_max_tokens=2048,
        compaction_max_tokens=0,
        compaction_target_tokens=0,
        compaction_digest_limit=0,
        tool_result_token_limit=0,
        tool_result_batch_token_limit=0,
        repair_round_token_budget=None,
        completion_gate="property_binding",
        artifact_contract=(
            "binding_manifest.json",
            "assertion_traceability.json",
            "rtl_label_result.json",
        ),
    ),
    StageSpec(
        name="invoke_verification",
        ordinal=3,
        execution_kind="deterministic",
        required_predecessor="bind_properties",
        tool_budget=0,
        discovery_budget=0,
        finalization_reserve=0,
        model_turn_budget=0,
        soft_token_budget=None,
        token_budget=None,
        request_max_tokens=0,
        compaction_max_tokens=0,
        compaction_target_tokens=0,
        compaction_digest_limit=0,
        tool_result_token_limit=0,
        tool_result_batch_token_limit=0,
        repair_round_token_budget=None,
        completion_gate="formal_result",
        artifact_contract=("formal_result.json", "property_status.json"),
    ),
    StageSpec(
        name="waveform_explanation",
        ordinal=4,
        execution_kind="agent",
        required_predecessor="invoke_verification",
        tool_budget=36,
        discovery_budget=24,
        finalization_reserve=3,
        model_turn_budget=30,
        soft_token_budget=72000,
        token_budget=96000,
        request_max_tokens=4096,
        compaction_max_tokens=4096,
        compaction_target_tokens=1200,
        compaction_digest_limit=1600,
        tool_result_token_limit=6000,
        tool_result_batch_token_limit=10000,
        repair_round_token_budget=None,
        completion_gate="diagnosis",
        artifact_contract=("diagnosis.json", "counterexample_analysis.md"),
    ),
    StageSpec(
        name="propose_bugfix",
        ordinal=5,
        execution_kind="agent",
        required_predecessor="waveform_explanation",
        tool_budget=20,
        discovery_budget=10,
        finalization_reserve=4,
        model_turn_budget=12,
        soft_token_budget=None,
        token_budget=192000,
        request_max_tokens=4096,
        compaction_max_tokens=4096,
        compaction_target_tokens=1200,
        compaction_digest_limit=1600,
        tool_result_token_limit=6000,
        tool_result_batch_token_limit=10000,
        repair_round_token_budget=80000,
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
