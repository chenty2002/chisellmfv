""" exact operation planning and semantic-evidence reduction."""

from __future__ import annotations

import json
from collections import Counter
from pathlib import Path
from typing import Any, Dict, Iterable, Mapping

from src.core.artifact_contract import file_sha256
from src.core.formal_operations import (
    FormalOperationError,
    join_exact_operation_rows,
    stable_operation_id,
)

from .stages import OPERATION_STATUSES


class SpecFlowResultError(ValueError):
    """Raised when a Stage-2 operation or result ledger is malformed."""


_ROLES = {
    "primary_assertion",
    "activation_cover",
    "observer_cover",
    "state_cover",
    "assumption_sat",
}


def build_operation_plan(
    certificate: Mapping[str, Any],
    *,
    certificate_path: Path,
    verification_package_sha256: str,
) -> Dict[str, Any]:
    certificate_sha256 = file_sha256(certificate_path)
    operations = []
    for identity in certificate.get("property_identities", []):
        role = identity.get("role")
        if role not in _ROLES:
            raise SpecFlowResultError(f"unsupported operation role: {role}")
        target = identity.get("emitted_property_id")
        source_property_id = identity.get("source_property_id")
        operation_id = stable_operation_id([source_property_id, role, target])
        primary = role == "primary_assertion"
        operations.append(
            {
                "operation_id": operation_id,
                "source_property_id": source_property_id,
                "obligation_id": identity["obligation_id"],
                "role": role,
                "target": target,
                "emitted_property_id": target,
                "expected_statuses": (
                    ["proven", "cex", "inconclusive", "timeout"]
                    if primary
                    else ["covered", "unreachable", "inconclusive", "timeout"]
                ),
                "trace_required": primary,
                "budget_class": "primary" if primary else "evidence",
                "certificate_sha256": certificate_sha256,
            }
        )
    plan = {
        "schema_version": "verification_operation_plan",
        "verification_package_sha256": verification_package_sha256,
        "certificate_sha256": certificate_sha256,
        "expected_operation_count": len(operations),
        "operations": sorted(operations, key=lambda row: row["operation_id"]),
    }
    validate_operation_plan(plan)
    return plan


def validate_operation_plan(value: Mapping[str, Any]) -> None:
    required = {
        "schema_version",
        "verification_package_sha256",
        "certificate_sha256",
        "expected_operation_count",
        "operations",
    }
    if set(value) != required or value.get("schema_version") != "verification_operation_plan":
        raise SpecFlowResultError("operation plan has an invalid exact schema")
    rows = value.get("operations")
    if not isinstance(rows, list) or not rows:
        raise SpecFlowResultError("operation plan cannot be empty")
    if value.get("expected_operation_count") != len(rows):
        raise SpecFlowResultError("operation plan count mismatch")
    seen = set()
    emitted = set()
    for row in rows:
        fields = {
            "operation_id",
            "source_property_id",
            "obligation_id",
            "role",
            "target",
            "emitted_property_id",
            "expected_statuses",
            "trace_required",
            "budget_class",
            "certificate_sha256",
        }
        if not isinstance(row, Mapping) or set(row) != fields:
            raise SpecFlowResultError("operation row has an invalid exact schema")
        if row["operation_id"] in seen:
            raise SpecFlowResultError("duplicate operation ID")
        if row["emitted_property_id"] in emitted:
            raise SpecFlowResultError("duplicate emitted property operation")
        seen.add(row["operation_id"])
        emitted.add(row["emitted_property_id"])
        if row["role"] not in _ROLES or row["target"] != row["emitted_property_id"]:
            raise SpecFlowResultError("operation role or target is invalid")
        if row["certificate_sha256"] != value["certificate_sha256"]:
            raise SpecFlowResultError("operation certificate hash mismatch")


def reduce_property_results(
    operation_plan: Mapping[str, Any],
    actual_results: Iterable[Mapping[str, Any]],
    *,
    operation_plan_path: Path,
    trace_manifest_sha256: str,
    tool: Mapping[str, Any],
) -> tuple[Dict[str, Any], Dict[str, Any]]:
    validate_operation_plan(operation_plan)
    try:
        joined = join_exact_operation_rows(
            operation_plan["operations"],
            actual_results,
            missing_row_factory=lambda expected: {
                "operation_id": expected["operation_id"],
                "status": "missing",
                "reason": "missing_operation_result",
                "observed_property_id": None,
                "runtime_s": None,
                "trace_path": None,
            },
        )
    except FormalOperationError as exc:
        raise SpecFlowResultError(str(exc)) from exc
    rows = joined["rows"]
    for expected, row in zip(operation_plan["operations"], rows):
        status = row.get("status")
        if status not in OPERATION_STATUSES:
            raise SpecFlowResultError(f"invalid operation status: {status}")
        observed = row.get("observed_property_id")
        if observed is not None and observed != expected["emitted_property_id"]:
            row["status"] = "tool_error"
            row["reason"] = "emitted_property_identity_mismatch"
        if row["status"] in {"proven", "cex", "covered", "unreachable"} and observed != expected["emitted_property_id"]:
            row["status"] = "tool_error"
            row["reason"] = "exact_observed_property_required"
    unexpected = joined["unexpected_operation_ids"]
    operation_set_complete = joined["operation_set_complete"]
    counts = Counter(row["status"] for row in rows)
    primary = [
        (expected, actual)
        for expected, actual in zip(operation_plan["operations"], rows)
        if expected["role"] == "primary_assertion"
    ]
    primary_statuses = [actual["status"] for _, actual in primary]
    formal_outcome = (
        "cex"
        if "cex" in primary_statuses
        else "all_proven"
        if primary_statuses and all(status == "proven" for status in primary_statuses)
        else "not_run"
        if primary_statuses and all(status in {"not_run", "missing"} for status in primary_statuses)
        else "inconclusive"
    )
    execution_status = (
        "tool_error"
        if counts["tool_error"] or unexpected
        else "partial"
        if any(counts[name] for name in ("missing", "not_run", "timeout", "inconclusive"))
        else "completed"
    )
    evidence_rows = _reduce_semantic_evidence(operation_plan["operations"], rows)
    evidence_statuses = {row["evidence_status"] for row in evidence_rows}
    semantic_candidates = {row["semantic_candidate"] for row in evidence_rows}
    overall_evidence = (
        "invalid"
        if not operation_set_complete or "invalid" in evidence_statuses
        else "incomplete"
        if "incomplete" in evidence_statuses
        else "vacuous"
        if "vacuous" in evidence_statuses
        else "complete"
    )
    overall_candidate = (
        "violated_candidate"
        if "violated_candidate" in semantic_candidates
        else "supported"
        if semantic_candidates == {"supported"}
        else "inconclusive"
    )
    result_map = {
        "schema_version": "property_result_map",
        "operation_plan_sha256": file_sha256(operation_plan_path),
        "certificate_sha256": operation_plan["certificate_sha256"],
        "trace_manifest_sha256": trace_manifest_sha256,
        "execution_status": execution_status,
        "formal_outcome": formal_outcome,
        "evidence_status": overall_evidence,
        "semantic_candidate": overall_candidate,
        "expected_operation_count": len(operation_plan["operations"]),
        "accounted_operation_count": len(rows),
        "operation_set_complete": operation_set_complete,
        "missing_operation_ids": joined["missing_operation_ids"],
        "unexpected_operation_ids": unexpected,
        "status_counts": dict(sorted(counts.items())),
        "operation_results": rows,
        "tool": dict(tool),
    }
    semantic_evidence = {
        "schema_version": "semantic_evidence",
        "operation_plan_sha256": result_map["operation_plan_sha256"],
        "trace_manifest_sha256": trace_manifest_sha256,
        "evidence_status": overall_evidence,
        "semantic_candidate": overall_candidate,
        "properties": evidence_rows,
    }
    return result_map, semantic_evidence


def write_json(path: Path, value: Mapping[str, Any]) -> None:
    Path(path).write_text(
        json.dumps(value, indent=2, sort_keys=True) + "\n", encoding="utf-8"
    )


def _reduce_semantic_evidence(
    expected_rows: list[Mapping[str, Any]], actual_rows: list[Mapping[str, Any]]
) -> list[Dict[str, Any]]:
    by_obligation: Dict[str, list[tuple[Mapping[str, Any], Mapping[str, Any]]]] = {}
    for expected, actual in zip(expected_rows, actual_rows):
        by_obligation.setdefault(expected["obligation_id"], []).append((expected, actual))
    reduced = []
    for obligation_id, pairs in sorted(by_obligation.items()):
        primaries = [pair for pair in pairs if pair[0]["role"] == "primary_assertion"]
        if len(primaries) != 1:
            raise SpecFlowResultError(
                f"obligation {obligation_id} requires exactly one primary assertion"
            )
        primary_expected, primary_actual = primaries[0]
        auxiliary = [pair for pair in pairs if pair[0]["role"] != "primary_assertion"]
        gates = [
            {
                "operation_id": expected["operation_id"],
                "source_property_id": expected["source_property_id"],
                "role": expected["role"],
                "status": actual["status"],
            }
            for expected, actual in auxiliary
        ]
        primary_status = primary_actual["status"]
        auxiliary_statuses = [row["status"] for row in gates]
        if primary_status == "cex" and primary_actual.get("trace_path"):
            evidence_status = (
                "complete"
                if all(status == "covered" for status in auxiliary_statuses)
                else "incomplete"
            )
            candidate = "violated_candidate"
        elif primary_status == "proven":
            if any(status == "unreachable" for status in auxiliary_statuses):
                evidence_status, candidate = "vacuous", "inconclusive"
            elif auxiliary_statuses and all(status == "covered" for status in auxiliary_statuses):
                evidence_status, candidate = "complete", "supported"
            else:
                evidence_status, candidate = "incomplete", "inconclusive"
        else:
            evidence_status, candidate = "incomplete", "inconclusive"
        reduced.append(
            {
                "source_property_id": primary_expected["source_property_id"],
                "obligation_id": obligation_id,
                "primary_operation_id": primary_expected["operation_id"],
                "primary_status": primary_status,
                "required_evidence_roles": sorted({row["role"] for row in gates}),
                "evidence_gates": gates,
                "evidence_status": evidence_status,
                "semantic_candidate": candidate,
                "trace_path": primary_actual.get("trace_path"),
            }
        )
    return reduced
