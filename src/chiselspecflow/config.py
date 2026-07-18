"""Frozen input and schema contracts for ChiselSpecFlow V5."""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from types import MappingProxyType
from typing import Mapping, Optional


PROJECT_SCHEMA_VERSION = "specflow_project.v1"
PUBLIC_SPEC_PACKAGE_SCHEMA_VERSION = "public_spec_package.v1"
RUN_MANIFEST_SCHEMA_VERSION = "specflow_run_manifest.v1"
MODEL_VIEW_MANIFEST_SCHEMA_VERSION = "model_view_manifest.v1"
OBLIGATION_SCHEMA_VERSION = "verification_obligations.v1"
SEMANTIC_INDEX_SCHEMA_VERSION = "chisel_semantic_index.v1"
BINDING_SCHEMA_VERSION = "chisel_bindings.v1"
MONITOR_SCHEMA_VERSION = "chisel_monitors.v1"
EXPRESSION_SCHEMA_VERSION = "expression_ir.v1"
REVIEW_RECORD_SCHEMA_VERSION = "review_record.v1"
ELABORATION_CERTIFICATE_SCHEMA_VERSION = "elaboration_certificate.v1"
OPERATION_PLAN_SCHEMA_VERSION = "verification_operation_plan.v2"
PROPERTY_RESULT_MAP_SCHEMA_VERSION = "property_result_map.v5"
EVIDENCE_PROJECTION_SCHEMA_VERSION = "evidence_projection.v1"
DIAGNOSIS_CANDIDATE_SCHEMA_VERSION = "diagnosis_candidate.v1"
FINAL_VERDICT_SCHEMA_VERSION = "final_verdict.v1"
REVISION_REQUEST_SCHEMA_VERSION = "revision_request.v1"

SCHEMA_VERSIONS: Mapping[str, str] = MappingProxyType(
    {
        "project": PROJECT_SCHEMA_VERSION,
        "public_spec_package": PUBLIC_SPEC_PACKAGE_SCHEMA_VERSION,
        "run_manifest": RUN_MANIFEST_SCHEMA_VERSION,
        "model_view_manifest": MODEL_VIEW_MANIFEST_SCHEMA_VERSION,
        "obligations": OBLIGATION_SCHEMA_VERSION,
        "semantic_index": SEMANTIC_INDEX_SCHEMA_VERSION,
        "bindings": BINDING_SCHEMA_VERSION,
        "monitors": MONITOR_SCHEMA_VERSION,
        "expression": EXPRESSION_SCHEMA_VERSION,
        "review_record": REVIEW_RECORD_SCHEMA_VERSION,
        "elaboration_certificate": ELABORATION_CERTIFICATE_SCHEMA_VERSION,
        "operation_plan": OPERATION_PLAN_SCHEMA_VERSION,
        "property_result_map": PROPERTY_RESULT_MAP_SCHEMA_VERSION,
        "evidence_projection": EVIDENCE_PROJECTION_SCHEMA_VERSION,
        "diagnosis_candidate": DIAGNOSIS_CANDIDATE_SCHEMA_VERSION,
        "final_verdict": FINAL_VERDICT_SCHEMA_VERSION,
        "revision_request": REVISION_REQUEST_SCHEMA_VERSION,
    }
)


@dataclass(frozen=True)
class SpecFlowRunConfig:
    """Paths fixed before a production SpecFlow run is materialized.

    Iteration 0 normalizes the input boundary but does not read or validate the
    three documents.  Their schema, visibility, and build validation belong to
    Iteration 1.
    """

    project_contract: Path
    specification: Path
    configuration: Path
    run_root: Path = Path("runs/specflow")
    copy_strategy: str = "isolated_copy"
    opaque_task_id: Optional[str] = None

    def __post_init__(self) -> None:
        for field_name in (
            "project_contract",
            "specification",
            "configuration",
            "run_root",
        ):
            value = Path(getattr(self, field_name)).resolve()
            object.__setattr__(self, field_name, value)
        if self.copy_strategy != "isolated_copy":
            raise ValueError(
                "SpecFlow requires the isolated_copy workspace strategy"
            )
        if self.opaque_task_id is not None and (
            not isinstance(self.opaque_task_id, str) or not self.opaque_task_id.strip()
        ):
            raise ValueError("opaque_task_id must be a non-empty string when supplied")
