"""Minimum witness/non-vacuity evidence gate for V3 experiment eligibility."""

from __future__ import annotations

import copy
from typing import Any, Dict


PASS = {"reachable", "toggled", "satisfiable", "killed", "counterexample_witness"}
FAIL_REASONS = {
    "antecedent_unreachable", "observer_constant", "consequent_trivial",
    "environment_overconstrained", "non_vacuity_inconclusive", "negative_oracle_failed",
}


class NonVacuityError(ValueError):
    """Raised when witness evidence is malformed or targets the wrong instance."""


def apply_non_vacuity_evidence(
    semantic_evidence: Dict[str, Any], witness_plan: Dict[str, Any], evidence: Dict[str, Any]
) -> Dict[str, Any]:
    """Return a new semantic-evidence ledger; parent results are never inherited."""
    if witness_plan.get("schema_version") != "witness_plan.v1":
        raise NonVacuityError("unsupported witness plan")
    if evidence.get("schema_version") != "non_vacuity_evidence.v1":
        raise NonVacuityError("unsupported non-vacuity evidence")
    result = copy.deepcopy(semantic_evidence)
    planned = {item["instance_id"]: item for item in witness_plan["instances"]}
    supplied = {item.get("instance_id"): item for item in evidence.get("instances", [])}
    if set(supplied) != set(planned):
        raise NonVacuityError("non-vacuity evidence must cover the exact witness plan")
    ledger = {item["instance_id"]: item for item in result["instances"]}
    for instance_id, plan in planned.items():
        item = supplied[instance_id]
        required = {
            "instance_id", "trigger", "observers", "states", "assumptions", "negative_oracle",
            "consequent", "parent_evidence_reused",
        }
        if set(item) != required or item["parent_evidence_reused"] is not False:
            raise NonVacuityError("non-vacuity evidence fields are invalid or reuse parent evidence")
        trigger = item["trigger"]
        assumptions = item["assumptions"]
        negative = item["negative_oracle"]
        consequent = item["consequent"]
        observers = item["observers"]
        states = item["states"]
        observer_by_id = {obs.get("id"): obs for obs in observers}
        state_by_id = {state.get("id"): state for state in states}
        expected_observers = set(plan["observer_requirements"])
        reasons = []
        if trigger.get("status") not in {"reachable", "counterexample_witness"}:
            reasons.append("antecedent_unreachable" if trigger.get("status") == "unreachable" else "non_vacuity_inconclusive")
        elif not trigger.get("trace") or not isinstance(trigger.get("trigger_count"), int) or trigger["trigger_count"] <= 0:
            raise NonVacuityError("reachable trigger evidence requires a trace and positive count")
        for observer_id in expected_observers:
            status = observer_by_id.get(observer_id, {}).get("status")
            if status not in {"toggled", "reachable", "not_applicable"}:
                reasons.append("observer_constant" if status == "constant" else "non_vacuity_inconclusive")
        for state_id in plan["state_requirements"]:
            status = state_by_id.get(state_id, {}).get("status")
            if status not in {"toggled", "reachable", "not_applicable"}:
                reasons.append("observer_constant" if status == "constant" else "non_vacuity_inconclusive")
        if assumptions.get("status") != "satisfiable":
            reasons.append("environment_overconstrained" if assumptions.get("status") == "unsatisfiable" else "non_vacuity_inconclusive")
        if negative.get("mutation_class") not in plan["mutation_classes"]:
            raise NonVacuityError("negative oracle mutation class is not in the frozen plan")
        if negative.get("status") != "killed":
            reasons.append("negative_oracle_failed")
        elif not negative.get("trace") or not negative.get("mutation_id"):
            raise NonVacuityError("killed negative oracle requires mutation and trace evidence")
        if consequent.get("status") == "trivial":
            reasons.append("consequent_trivial")
        row = ledger[instance_id]
        row["positive_witness"] = trigger
        row["observer_reachability"] = "passed" if not any(reason == "observer_constant" for reason in reasons) else "failed"
        row["state_reachability"] = "passed" if all(
            state_by_id.get(state_id, {}).get("status") in {"toggled", "reachable", "not_applicable"}
            for state_id in plan["state_requirements"]
        ) else "failed"
        row["assumption_satisfiability"] = assumptions["status"]
        row["negative_oracle"] = negative["status"]
        if reasons:
            row["non_vacuity"] = "failed" if any(reason != "non_vacuity_inconclusive" for reason in reasons) else "inconclusive"
            row["non_vacuity_reason"] = sorted(set(reasons))
            row["experiment_eligible"] = False
        else:
            row["non_vacuity"] = "passed"
            row["non_vacuity_reason"] = None
            row["experiment_eligible"] = (
                row["ir_validation"] == "passed"
                and row["compile_status"] == "passed"
                and row["rtl_identity"] == "passed"
            )
    result["experiment_eligible"] = bool(result["instances"]) and all(
        item["experiment_eligible"] for item in result["instances"]
    )
    return result
