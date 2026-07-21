"""Single frozen stage contract for the three-stage SpecFlow method."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Dict, Optional, Tuple


FINAL_VERDICTS = frozenset({"accepted", "violated", "inconclusive", "unsupported"})
DIAGNOSIS_CLASSIFICATIONS = frozenset(
    {
        "design_violation",
        "obligation_error",
        "binding_error",
        "monitor_error",
        "assumption_error",
        "tool_or_identity_error",
        "inconclusive",
    }
)
OPERATION_STATUSES = frozenset(
    {
        "proven",
        "cex",
        "covered",
        "unreachable",
        "inconclusive",
        "timeout",
        "not_run",
        "tool_error",
        "missing",
    }
)
EXECUTION_STATUSES = frozenset({"completed", "partial", "tool_error"})
FORMAL_OUTCOMES = frozenset({"all_proven", "cex", "inconclusive", "not_run"})
EVIDENCE_STATUSES = frozenset({"complete", "vacuous", "incomplete", "invalid"})
SEMANTIC_CANDIDATES = frozenset(
    {"supported", "violated_candidate", "inconclusive"}
)
AUTHORING_STOP_STATUSES = frozenset(
    {"awaiting_review", "rejected", "unsupported", "ambiguous"}
)


@dataclass(frozen=True)
class StageSpec:
    name: str
    ordinal: int
    execution_kind: str
    model_policy: str
    required_predecessor: Optional[str]
    completion_gate: str
    artifact_contract: Tuple[str, ...]

    @property
    def directory_name(self) -> str:
        return f"{self.ordinal:02d}_{self.name}"


STAGE_SPECS: Tuple[StageSpec, ...] = (
    StageSpec(
        name="asset_authoring",
        ordinal=1,
        execution_kind="bounded_authoring",
        model_policy="bounded",
        required_predecessor=None,
        completion_gate="reviewed_verification_package",
        artifact_contract=(
            "stage_inputs.json",
            "authoring_candidates.json",
            "candidate_asset_delta.json",
            "review_record.json",
            "verification_package.json",
        ),
    ),
    StageSpec(
        name="compile_verify",
        ordinal=2,
        execution_kind="deterministic",
        model_policy="forbidden",
        required_predecessor="asset_authoring",
        completion_gate="exact_semantic_evidence",
        artifact_contract=(
            "package_applicability.json",
            "verification_package_ref.json",
            "overlay_manifest.json",
            "source_assertion_delta.json",
            "elaboration_certificate.json",
            "verification_operation_plan.json",
            "property_result_map.json",
            "semantic_evidence.json",
            "trace_manifest.json",
            "proof_events.jsonl",
            "jaspergold.log",
        ),
    ),
    StageSpec(
        name="diagnose",
        ordinal=3,
        execution_kind="conditional_diagnosis",
        model_policy="conditional",
        required_predecessor="compile_verify",
        completion_gate="reviewed_final_verdict",
        artifact_contract=(
            "evidence_projection.json",
            "diagnosis_candidate.json",
            "diagnosis_review.json",
            "source_ranking.json",
            "revision_request.json",
            "final_verdict.json",
            "counterexample_analysis.md",
        ),
    ),
)

_STAGE_BY_NAME: Dict[str, StageSpec] = {spec.name: spec for spec in STAGE_SPECS}
SPEC_FLOW_STAGES = tuple(spec.name for spec in STAGE_SPECS)


def get_stage_spec(stage: str) -> StageSpec:
    try:
        return _STAGE_BY_NAME[stage]
    except KeyError as exc:
        raise ValueError(f"unknown SpecFlow stage: {stage}") from exc


def stage_contract_snapshot() -> Tuple[Dict[str, object], ...]:
    """Return the JSON-ready stage contract embedded in future manifests."""

    return tuple(
        {
            "name": spec.name,
            "ordinal": spec.ordinal,
            "execution_kind": spec.execution_kind,
            "model_policy": spec.model_policy,
            "required_predecessor": spec.required_predecessor,
            "completion_gate": spec.completion_gate,
            "artifact_contract": list(spec.artifact_contract),
        }
        for spec in STAGE_SPECS
    )
