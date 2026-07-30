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
    completion_gate: str
    artifact_contract: Tuple[str, ...]

    @property
    def directory_name(self) -> str:
        return f"{self.ordinal:02d}_{self.name}"


STAGE_SPECS: Tuple[StageSpec, ...] = (
    StageSpec(
        name="bind_properties",
        ordinal=2,
        execution_kind="binding",
        required_predecessor="preflight",
        completion_gate="property_binding",
        artifact_contract=(
            "stage_inputs.json",
            "binding_manifest.json",
            "property_package.json",
            "assertion_delta.json",
            "render_result.json",
            "assertion_diff.patch",
            "build_result.json",
        ),
    ),
    StageSpec(
        name="invoke_verification",
        ordinal=3,
        execution_kind="deterministic",
        required_predecessor="bind_properties",
        completion_gate="property_result_map",
        artifact_contract=(
            "property_result_map.json",
            "semantic_evidence.json",
            "proof_events.jsonl",
            "jaspergold.log",
        ),
    ),
)

_STAGE_BY_NAME: Dict[str, StageSpec] = {spec.name: spec for spec in STAGE_SPECS}
COUPLEDL2_STAGES = [spec.name for spec in STAGE_SPECS]


def get_stage_spec(stage: str) -> StageSpec:
    try:
        return _STAGE_BY_NAME[stage]
    except KeyError as exc:
        raise ValueError(f"unknown CoupledL2 stage: {stage}") from exc
