"""Explicit reviewed capability outcomes for not-yet-supported families."""

from __future__ import annotations

import json
import re
from pathlib import Path
from typing import Any, Dict, Mapping


SCHEMA_VERSION = "specflow_capability_assessment.v1"
_FIELDS = {
    "schema_version",
    "project_id",
    "specification_id",
    "spec_sha256",
    "status",
    "attempted_expected_property_ids",
    "missing_capabilities",
    "reason",
    "evidence_refs",
    "reviewer",
    "reviewed_at",
    "decision",
}
_CAPABILITIES = {
    "algorithmic_reference_relation",
    "compositional_monitor",
    "hierarchical_observer",
    "reviewed_clean_reference",
    "transaction_scoreboard",
}


class CapabilityAssessmentError(ValueError):
    """Raised when an explicit supported/unsupported accounting row is invalid."""


def load_capability_assessment(
    path: Path, public_spec: Mapping[str, Any]
) -> Dict[str, Any]:
    try:
        value = json.loads(Path(path).read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as exc:
        raise CapabilityAssessmentError(f"cannot read capability assessment: {path}") from exc
    if not isinstance(value, dict):
        raise CapabilityAssessmentError("capability assessment must be an object")
    return validate_capability_assessment(value, public_spec)


def validate_capability_assessment(
    value: Mapping[str, Any], public_spec: Mapping[str, Any]
) -> Dict[str, Any]:
    if not isinstance(value, Mapping) or set(value) != _FIELDS:
        raise CapabilityAssessmentError("capability assessment has an invalid exact schema")
    if (
        value.get("schema_version") != SCHEMA_VERSION
        or value.get("project_id") != public_spec.get("family")
        or value.get("specification_id") != public_spec.get("specification_id")
        or value.get("spec_sha256") != public_spec.get("spec_sha256")
    ):
        raise CapabilityAssessmentError("capability assessment identity mismatch")
    if value.get("status") not in {"supported", "unsupported"}:
        raise CapabilityAssessmentError("capability status is invalid")
    attempted = value.get("attempted_expected_property_ids")
    if attempted != public_spec.get("expected_property_ids"):
        raise CapabilityAssessmentError("capability assessment did not account for the full public property set")
    missing = value.get("missing_capabilities")
    if (
        not isinstance(missing, list)
        or any(row not in _CAPABILITIES for row in missing)
        or len(set(missing)) != len(missing)
        or (value["status"] == "unsupported") != bool(missing)
    ):
        raise CapabilityAssessmentError("missing capability accounting is invalid")
    if value.get("reviewer") != "codex" or value.get("decision") != "approved":
        raise CapabilityAssessmentError("capability assessment is not Codex-approved")
    if not re.fullmatch(r"[0-9]{4}-[0-9]{2}-[0-9]{2}", str(value.get("reviewed_at"))):
        raise CapabilityAssessmentError("capability review date is invalid")
    if not _text(value.get("reason")):
        raise CapabilityAssessmentError("capability assessment reason is required")
    evidence = value.get("evidence_refs")
    if not isinstance(evidence, list) or not evidence or any(not _text(row) for row in evidence):
        raise CapabilityAssessmentError("capability assessment evidence is required")
    return dict(value)


def _text(value: Any) -> bool:
    return isinstance(value, str) and bool(value.strip())
