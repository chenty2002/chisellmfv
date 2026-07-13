"""Frozen V4 operation, observation, trace, and result contracts.

The module deliberately contains no tool or model calls.  It validates the
immutable Stage-2 operation plan and reduces deterministic Stage-3 operation
rows into the four orthogonal result states used by the runner:

``execution_status`` -> ``formal_outcome`` -> ``semantic_status`` ->
``experiment_status``.

Missing operation rows are materialized as ``not_run`` rows, while unexpected
rows make the result set incomplete.  In particular, a proven primary
assertion is never used as evidence for a semantic gate by itself.
"""

from __future__ import annotations

import hashlib
import json
import re
from collections import defaultdict
from typing import Any, Dict, Iterable, Mapping, Optional, Sequence


OPERATION_PLAN_SCHEMA_VERSION = "verification_operation_plan.v1"
OBSERVATION_MAP_SCHEMA_VERSION = "observation_map.v1"
TRACE_DECODE_CONTRACT_SCHEMA_VERSION = "trace_decode_contract.v1"
PROPERTY_RESULT_MAP_SCHEMA_VERSION = "property_result_map.v4"
SEMANTIC_EVIDENCE_SCHEMA_VERSION = "semantic_evidence.v2"

OPERATION_ROLES = frozenset(
    {
        "primary_assertion",
        "trigger_cover",
        "observer_cover",
        "state_cover",
        "assumption_sat",
        "negative_oracle",
    }
)
OPERATION_STATUSES = frozenset(
    {
        "proven",
        "cex",
        "covered",
        "unreachable",
        "inconclusive",
        "not_run",
        "tool_error",
    }
)
EXECUTION_STATUSES = frozenset({"completed", "partial", "tool_error"})
FORMAL_OUTCOMES = frozenset({"all_proven", "cex", "inconclusive", "not_run"})
SEMANTIC_STATUSES = frozenset({"eligible", "ineligible", "inconclusive"})
EXPERIMENT_STATUSES = frozenset({"eligible", "excluded", "invalid"})
SEMANTIC_GATE_ROLES = (
    "trigger_cover",
    "observer_cover",
    "state_cover",
    "assumption_sat",
    "negative_oracle",
)

_SHA256_RE = re.compile(r"^[0-9a-f]{64}$")


class ResultContractError(ValueError):
    """Raised when a V4 contract or result ledger is malformed."""


def canonical_sha256(value: Any) -> str:
    """Hash a JSON value with the repository's deterministic encoding."""
    payload = json.dumps(
        value,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")
    return hashlib.sha256(payload).hexdigest()


def operation_id(instance_id: str, role: str, target: str) -> str:
    """Return the stable ID for one instance/role/target tuple."""
    for name, value in (("instance_id", instance_id), ("role", role), ("target", target)):
        if not isinstance(value, str) or not value.strip():
            raise ResultContractError(f"{name} must be a non-empty string")
    if role not in OPERATION_ROLES:
        raise ResultContractError(f"unsupported operation role: {role}")
    safe_target = re.sub(r"[^A-Za-z0-9_.-]+", "_", target).strip("_")
    if not safe_target:
        raise ResultContractError("operation target becomes empty after normalization")
    return f"{instance_id}__{role}__{safe_target}"


def build_primary_operation_plan(
    traceability: Mapping[str, Any],
    *,
    package_sha256: str,
) -> Dict[str, Any]:
    """Lower the existing assertion delta into the Iteration-0 operation plan.

    Iteration 0 intentionally emits only primary assertion operations.  The
    semantic roles remain declared by the reducer, so this plan cannot produce
    a false semantic pass before Iteration 1 adds real cover operations.
    """
    _require_sha(package_sha256, "package_sha256")
    properties = traceability.get("properties")
    if not isinstance(properties, list) or not properties:
        raise ResultContractError("traceability.properties must be a non-empty list")
    operations = []
    for prop_index, prop in enumerate(properties):
        if not isinstance(prop, Mapping):
            raise ResultContractError(f"traceability.properties[{prop_index}] must be an object")
        instance_id = _string(prop, "instance_id", f"traceability.properties[{prop_index}]")
        rtl_properties = prop.get("rtl_properties")
        if not isinstance(rtl_properties, list) or not rtl_properties:
            raise ResultContractError(f"{instance_id} has no RTL properties")
        for rtl_index, rtl in enumerate(rtl_properties):
            if not isinstance(rtl, Mapping):
                raise ResultContractError(f"{instance_id}.rtl_properties[{rtl_index}] must be an object")
            label = _string(rtl, "rtl_label", f"{instance_id}.rtl_properties[{rtl_index}]")
            property_id = rtl.get("expected_property_id")
            if not isinstance(property_id, str) or not property_id:
                top_module = rtl.get("top_module") or traceability.get("top_module")
                if isinstance(top_module, str) and top_module:
                    property_id = f"{top_module}.{label}"
                else:
                    raise ResultContractError(
                        f"{instance_id}.{label} has no exact expected_property_id"
                    )
            target = label
            operations.append(
                {
                    "operation_id": operation_id(instance_id, "primary_assertion", target),
                    "instance_id": instance_id,
                    "role": "primary_assertion",
                    "target": target,
                    "rtl_property_id": property_id,
                    "expected_statuses": [
                        "proven",
                        "cex",
                        "unreachable",
                        "inconclusive",
                        "not_run",
                        "tool_error",
                    ],
                    "trace_required": False,
                    "budget_class": "proof",
                    "evidence_target": f"primary:{label}",
                }
            )
    plan = {
        "schema_version": OPERATION_PLAN_SCHEMA_VERSION,
        "package_sha256": package_sha256,
        "required_roles": ["primary_assertion"],
        "operations": operations,
    }
    validate_operation_plan(plan)
    return plan


def bind_operation_plan_to_package(package: Mapping[str, Any]) -> Dict[str, Any]:
    """Return a package copy with the plan bound to its semantic package hash.

    A package cannot contain the byte hash of itself without a circular
    fixed-point.  V4 therefore binds ``operation_plan.package_sha256`` to the
    canonical package hash with that one binding field blanked.  The actual
    artifact hash is still recorded independently in Stage 3.
    """
    bound = _deepcopy_json(package)
    plan = bound.get("operation_plan")
    if not isinstance(plan, dict):
        raise ResultContractError("property package has no operation_plan")
    plan["package_sha256"] = ""
    observation_map = bound.get("observation_map")
    if isinstance(observation_map, dict) and "property_package_sha256" in observation_map:
        observation_map["property_package_sha256"] = ""
    if "package_semantics_sha256" in bound:
        bound["package_semantics_sha256"] = ""
    semantic_hash = canonical_sha256(bound)
    plan["package_sha256"] = semantic_hash
    if isinstance(observation_map, dict) and "property_package_sha256" in observation_map:
        observation_map["property_package_sha256"] = semantic_hash
    if "package_semantics_sha256" in bound:
        bound["package_semantics_sha256"] = semantic_hash
    validate_operation_plan(plan, expected_package_sha256=semantic_hash)
    return bound


def build_unmaterialized_observation_map(
    *, top_module: str, package_sha256: str, reason: str
) -> Dict[str, Any]:
    """Create the explicit fail-closed Iteration-0 observation map."""
    _require_sha(package_sha256, "package_sha256")
    if not isinstance(top_module, str) or not top_module:
        raise ResultContractError("observation map requires a top module")
    if not isinstance(reason, str) or not reason:
        raise ResultContractError("unmaterialized observation map requires a reason")
    value = {
        "schema_version": OBSERVATION_MAP_SCHEMA_VERSION,
        "status": "not_materialized",
        "top_module": top_module,
        "property_package_sha256": package_sha256,
        "rtl_input_hashes": [],
        "clock": {"role": "clock", "rtl_name": None, "width": 1},
        "reset": {"role": "reset", "rtl_name": None, "width": 1},
        "observations": [],
        "fail_closed_reason": reason,
    }
    validate_observation_map(value)
    return value


def build_trace_decode_contract(
    *,
    operation_id_value: str,
    instance_id: str,
    rtl_property_id: str,
    trace_path: str,
    trace_sha256: str,
    observation_map_sha256: str,
    signal_map: Mapping[str, Any],
    required_observations: Sequence[str],
    clock_reset_sampling: Mapping[str, Any],
    transaction_matching_keys: Sequence[str] = (),
    wait_edge_recipes: Sequence[Mapping[str, Any]] = (),
) -> Dict[str, Any]:
    """Build and validate a ready trace-decode contract."""
    value = {
        "schema_version": TRACE_DECODE_CONTRACT_SCHEMA_VERSION,
        "operation_id": operation_id_value,
        "instance_id": instance_id,
        "rtl_property_id": rtl_property_id,
        "trace_path": trace_path,
        "trace_sha256": trace_sha256,
        "observation_map_sha256": observation_map_sha256,
        "observation_map_subset": sorted(signal_map),
        "clock_reset_sampling": dict(clock_reset_sampling),
        "signal_map": dict(signal_map),
        "required_observations": list(required_observations),
        "transaction_matching_keys": list(transaction_matching_keys),
        "wait_edge_recipes": [dict(item) for item in wait_edge_recipes],
        "decode_readiness": "ready",
        "fail_closed_reason": None,
    }
    validate_trace_decode_contract(value)
    return value


def validate_operation_plan(
    value: Mapping[str, Any], *, expected_package_sha256: Optional[str] = None
) -> None:
    """Validate operation IDs, role/status domains, and exact bindings."""
    _object(value, "verification_operation_plan")
    _fields(
        value,
        required={"schema_version", "package_sha256", "operations"},
        optional={"required_roles", "compiler", "selection_coverage"},
        path="verification_operation_plan",
    )
    if value["schema_version"] != OPERATION_PLAN_SCHEMA_VERSION:
        raise ResultContractError("unsupported verification operation plan version")
    _require_sha(value["package_sha256"], "verification_operation_plan.package_sha256")
    if expected_package_sha256 is not None and value["package_sha256"] != expected_package_sha256:
        raise ResultContractError("operation plan package hash does not match package")
    operations = value["operations"]
    if not isinstance(operations, list) or not operations:
        raise ResultContractError("verification operation plan requires operations")
    required_roles = value.get("required_roles", [])
    if not isinstance(required_roles, list) or not all(
        isinstance(role, str) and role in OPERATION_ROLES for role in required_roles
    ):
        raise ResultContractError("operation plan required_roles is invalid")
    ids: set[str] = set()
    rtl_ids: set[str] = set()
    tuples: set[tuple[str, str, str]] = set()
    for index, item in enumerate(operations):
        path = f"verification_operation_plan.operations[{index}]"
        _object(item, path)
        _fields(
            item,
            required={
                "operation_id",
                "instance_id",
                "role",
                "target",
                "rtl_property_id",
                "expected_statuses",
                "trace_required",
                "budget_class",
                "evidence_target",
            },
            optional={"resource_budget", "assumption_ids", "mutation_id"},
            path=path,
        )
        instance = _string(item, "instance_id", path)
        role = _string(item, "role", path)
        if role not in OPERATION_ROLES:
            raise ResultContractError(f"{path}.role is not an approved operation role")
        target = _string(item, "target", path)
        expected_id = operation_id(instance, role, target)
        actual_id = _string(item, "operation_id", path)
        if actual_id != expected_id:
            raise ResultContractError(f"{path}.operation_id is not deterministic")
        if actual_id in ids:
            raise ResultContractError(f"duplicate operation_id: {actual_id}")
        ids.add(actual_id)
        rtl_id = _string(item, "rtl_property_id", path)
        if rtl_id in rtl_ids:
            raise ResultContractError(f"duplicate rtl_property_id: {rtl_id}")
        rtl_ids.add(rtl_id)
        tuple_key = (instance, role, target)
        if tuple_key in tuples:
            raise ResultContractError(f"duplicate operation tuple: {tuple_key}")
        tuples.add(tuple_key)
        statuses = item["expected_statuses"]
        if not isinstance(statuses, list) or not statuses or not all(
            isinstance(status, str) and status in OPERATION_STATUSES for status in statuses
        ):
            raise ResultContractError(f"{path}.expected_statuses is invalid")
        if not isinstance(item["trace_required"], bool):
            raise ResultContractError(f"{path}.trace_required must be boolean")
        _string(item, "budget_class", path)
        _string(item, "evidence_target", path)


def validate_observation_map(value: Mapping[str, Any]) -> None:
    """Validate a precise elaborated observation map or explicit unavailability."""
    _object(value, "observation_map")
    _fields(
        value,
        required={
            "schema_version",
            "top_module",
            "clock",
            "reset",
            "observations",
        },
        optional={
            "status",
            "property_package_sha256",
            "rtl_input_hashes",
            "fail_closed_reason",
        },
        path="observation_map",
    )
    if value["schema_version"] != OBSERVATION_MAP_SCHEMA_VERSION:
        raise ResultContractError("unsupported observation map version")
    _string(value, "top_module", "observation_map")
    status = value.get("status", "materialized")
    if status not in {"materialized", "not_materialized"}:
        raise ResultContractError("observation_map.status is invalid")
    if "property_package_sha256" in value:
        _require_sha(value["property_package_sha256"], "observation_map.property_package_sha256")
    if not isinstance(value.get("rtl_input_hashes", []), list):
        raise ResultContractError("observation_map.rtl_input_hashes must be a list")
    _validate_clock_reset(value["clock"], "observation_map.clock", allow_unmaterialized=status == "not_materialized")
    _validate_clock_reset(value["reset"], "observation_map.reset", allow_unmaterialized=status == "not_materialized")
    observations = value["observations"]
    if not isinstance(observations, list):
        raise ResultContractError("observation_map.observations must be a list")
    keys: set[tuple[str, str]] = set()
    rtl_names: set[str] = set()
    for index, item in enumerate(observations):
        path = f"observation_map.observations[{index}]"
        _object(item, path)
        _fields(
            item,
            required={"instance_id", "role", "rtl_name", "width", "kind", "source_anchor", "rtl_file_sha256"},
            optional={"encoding_ref"},
            path=path,
        )
        instance = _string(item, "instance_id", path)
        role = _string(item, "role", path)
        rtl_name = _string(item, "rtl_name", path)
        if rtl_name in rtl_names:
            raise ResultContractError(f"duplicate elaborated RTL observation: {rtl_name}")
        rtl_names.add(rtl_name)
        width = item["width"]
        if not isinstance(width, int) or width <= 0:
            raise ResultContractError(f"{path}.width must be a positive integer")
        _string(item, "kind", path)
        _string(item, "source_anchor", path)
        _require_sha(item["rtl_file_sha256"], f"{path}.rtl_file_sha256")
        key = (instance, role)
        if key in keys:
            raise ResultContractError(f"duplicate observation role: {key}")
        keys.add(key)
    if status == "not_materialized":
        if observations:
            raise ResultContractError("not_materialized observation map cannot contain observations")
        if not isinstance(value.get("fail_closed_reason"), str) or not value["fail_closed_reason"]:
            raise ResultContractError("not_materialized observation map requires fail_closed_reason")


def validate_trace_decode_contract(value: Mapping[str, Any]) -> None:
    """Validate the exact signal contract consumed by deterministic decoding."""
    _object(value, "trace_decode_contract")
    _fields(
        value,
        required={
            "schema_version",
            "operation_id",
            "instance_id",
            "rtl_property_id",
            "trace_sha256",
            "observation_map_sha256",
            "observation_map_subset",
            "clock_reset_sampling",
            "signal_map",
            "required_observations",
            "transaction_matching_keys",
            "wait_edge_recipes",
            "decode_readiness",
            "fail_closed_reason",
        },
        optional={"trace_path", "property_id"},
        path="trace_decode_contract",
    )
    if value["schema_version"] != TRACE_DECODE_CONTRACT_SCHEMA_VERSION:
        raise ResultContractError("unsupported trace decode contract version")
    for key in ("operation_id", "instance_id", "rtl_property_id"):
        _string(value, key, "trace_decode_contract")
    _require_sha(value["trace_sha256"], "trace_decode_contract.trace_sha256")
    _require_sha(value["observation_map_sha256"], "trace_decode_contract.observation_map_sha256")
    if not isinstance(value["observation_map_subset"], list) or not all(
        isinstance(item, str) and item for item in value["observation_map_subset"]
    ):
        raise ResultContractError("trace_decode_contract.observation_map_subset is invalid")
    if not isinstance(value["clock_reset_sampling"], Mapping):
        raise ResultContractError("trace_decode_contract.clock_reset_sampling must be an object")
    signal_map = value["signal_map"]
    if not isinstance(signal_map, Mapping):
        raise ResultContractError("trace_decode_contract.signal_map must be an object")
    for role, item in signal_map.items():
        if not isinstance(role, str) or not role or not isinstance(item, Mapping):
            raise ResultContractError("trace_decode_contract.signal_map has an invalid entry")
        _fields(item, required={"rtl_name", "width"}, optional={"kind", "encoding_ref"}, path=f"signal_map.{role}")
        _string(item, "rtl_name", f"signal_map.{role}")
        if not isinstance(item["width"], int) or item["width"] <= 0:
            raise ResultContractError(f"signal_map.{role}.width must be positive")
    for key in ("required_observations", "transaction_matching_keys", "wait_edge_recipes"):
        if not isinstance(value[key], list):
            raise ResultContractError(f"trace_decode_contract.{key} must be a list")
    readiness = value["decode_readiness"]
    if readiness not in {"ready", "unavailable"}:
        raise ResultContractError("trace_decode_contract.decode_readiness is invalid")
    if readiness == "ready":
        _string(value, "trace_path", "trace_decode_contract")
        if not signal_map:
            raise ResultContractError("ready trace decode contract has an empty signal map")
        if value["fail_closed_reason"] is not None:
            raise ResultContractError("ready trace decode contract cannot have a fail-closed reason")
    elif not isinstance(value["fail_closed_reason"], str) or not value["fail_closed_reason"]:
        raise ResultContractError("unavailable trace decode contract requires a reason")


def validate_property_result_map(
    value: Mapping[str, Any], *, operation_plan: Optional[Mapping[str, Any]] = None
) -> None:
    """Validate the canonical V4 result ledger and, when supplied, its exact set."""
    _object(value, "property_result_map")
    _fields(
        value,
        required={
            "schema_version",
            "property_profile_id",
            "property_package_sha256",
            "assertion_delta_sha256",
            "execution_status",
            "formal_outcome",
            "semantic_status",
            "experiment_status",
            "exclusion_reasons",
            "expected_operation_count",
            "accounted_operation_count",
            "instances",
        },
        optional={
            "operation_plan_sha256",
            "operations",
            "cex_work_items",
            "formal",
            "unmatched_tool_results",
            "operation_set_complete",
            "unexpected_operation_ids",
            "missing_operation_ids",
        },
        path="property_result_map",
    )
    if value["schema_version"] != PROPERTY_RESULT_MAP_SCHEMA_VERSION:
        raise ResultContractError("unsupported property result map version")
    _string(value, "property_profile_id", "property_result_map")
    _require_sha(value["property_package_sha256"], "property_result_map.property_package_sha256")
    _require_sha(value["assertion_delta_sha256"], "property_result_map.assertion_delta_sha256")
    if value["execution_status"] not in EXECUTION_STATUSES:
        raise ResultContractError("property_result_map.execution_status is invalid")
    if value["formal_outcome"] not in FORMAL_OUTCOMES:
        raise ResultContractError("property_result_map.formal_outcome is invalid")
    if value["semantic_status"] not in SEMANTIC_STATUSES:
        raise ResultContractError("property_result_map.semantic_status is invalid")
    if value["experiment_status"] not in EXPERIMENT_STATUSES:
        raise ResultContractError("property_result_map.experiment_status is invalid")
    if not isinstance(value["exclusion_reasons"], list) or not all(
        isinstance(item, str) and item for item in value["exclusion_reasons"]
    ):
        raise ResultContractError("property_result_map.exclusion_reasons is invalid")
    for key in ("expected_operation_count", "accounted_operation_count"):
        if not isinstance(value[key], int) or value[key] < 0:
            raise ResultContractError(f"property_result_map.{key} must be non-negative")
    instances = value["instances"]
    if not isinstance(instances, list):
        raise ResultContractError("property_result_map.instances must be a list")
    result_ids: set[str] = set()
    instance_ids: set[str] = set()
    for index, instance in enumerate(instances):
        path = f"property_result_map.instances[{index}]"
        _object(instance, path)
        _fields(
            instance,
            required={"instance_id", "operations", "semantic_verdict"},
            optional={"property_schema_id", "template_id", "base_label", "refs"},
            path=path,
        )
        instance_id = _string(instance, "instance_id", path)
        if instance_id in instance_ids:
            raise ResultContractError(f"duplicate result instance: {instance_id}")
        instance_ids.add(instance_id)
        _validate_instance_verdict(instance["semantic_verdict"], path)
        operations = instance["operations"]
        if not isinstance(operations, list):
            raise ResultContractError(f"{path}.operations must be a list")
        for op_index, result in enumerate(operations):
            _validate_operation_result(result, f"{path}.operations[{op_index}]")
            result_id = result["operation_id"]
            if result_id in result_ids:
                raise ResultContractError(f"duplicate operation result: {result_id}")
            result_ids.add(result_id)
            if result["instance_id"] != instance_id:
                raise ResultContractError(f"operation result instance mismatch: {result_id}")
    if value["accounted_operation_count"] != len(result_ids):
        raise ResultContractError("accounted_operation_count does not match operation rows")
    if value["expected_operation_count"] < value["accounted_operation_count"]:
        raise ResultContractError("accounted operations exceed expected operations")
    if operation_plan is not None:
        validate_operation_plan(operation_plan)
        plan_ids = {item["operation_id"] for item in operation_plan["operations"]}
        if plan_ids != result_ids:
            raise ResultContractError("property result operation set does not exactly match plan")
        if value["expected_operation_count"] != len(plan_ids):
            raise ResultContractError("expected_operation_count does not match operation plan")


def reduce_property_result_map(
    *,
    operation_plan: Mapping[str, Any],
    operation_results: Iterable[Mapping[str, Any]],
    property_profile_id: str,
    property_package_sha256: str,
    assertion_delta_sha256: str,
    instance_metadata: Optional[Mapping[str, Mapping[str, Any]]] = None,
    execution_status_hint: Optional[str] = None,
    formal_metadata: Optional[Mapping[str, Any]] = None,
    unmatched_tool_results: Optional[Sequence[Mapping[str, Any]]] = None,
) -> Dict[str, Any]:
    """Reduce deterministic operation rows into a total V4 result map."""
    validate_operation_plan(operation_plan)
    _string({"property_profile_id": property_profile_id}, "property_profile_id", "result")
    _require_sha(property_package_sha256, "property_package_sha256")
    _require_sha(assertion_delta_sha256, "assertion_delta_sha256")
    plan_by_id = {item["operation_id"]: item for item in operation_plan["operations"]}
    raw_by_id: Dict[str, Dict[str, Any]] = {}
    unexpected: list[str] = []
    for raw in operation_results:
        if not isinstance(raw, Mapping):
            raise ResultContractError("operation result must be an object")
        operation_id_value = raw.get("operation_id")
        if not isinstance(operation_id_value, str) or not operation_id_value:
            raise ResultContractError("operation result has no operation_id")
        if operation_id_value not in plan_by_id:
            unexpected.append(operation_id_value)
            continue
        if operation_id_value in raw_by_id:
            raise ResultContractError(f"duplicate operation result: {operation_id_value}")
        raw_by_id[operation_id_value] = dict(raw)

    normalized: list[Dict[str, Any]] = []
    missing: list[str] = []
    for plan_item in operation_plan["operations"]:
        op_id = plan_item["operation_id"]
        raw = raw_by_id.get(op_id)
        if raw is None:
            missing.append(op_id)
            raw = {"status": "not_run", "reason": "missing_operation_result"}
        normalized.append(_normalize_operation_result(plan_item, raw))

    groups: Dict[str, list[Dict[str, Any]]] = defaultdict(list)
    for item in normalized:
        groups[item["instance_id"]].append(item)
    metadata = instance_metadata or {}
    instances = []
    for instance_id in sorted(groups):
        item = dict(metadata.get(instance_id) or {})
        item["instance_id"] = instance_id
        item["operations"] = sorted(groups[instance_id], key=lambda row: row["operation_id"])
        item["semantic_verdict"] = _reduce_instance_semantics(
            item["operations"],
            operation_plan,
            operation_set_complete=not unexpected and not missing,
        )
        instances.append(item)

    execution_status = _reduce_execution_status(
        normalized,
        unexpected=unexpected,
        missing=missing,
        hint=execution_status_hint,
    )
    formal_outcome = _reduce_formal_outcome(normalized)
    semantic_status, semantic_reasons = _reduce_overall_semantics(
        instances,
        operation_set_complete=not unexpected and not missing,
    )
    cex_work_items = _build_cex_work_items(normalized)
    exclusion_reasons = sorted(
        set(semantic_reasons)
        | set(_formal_exclusion_reasons(formal_outcome, normalized))
        | ({"operation_set_mismatch"} if unexpected or missing else set())
        | ({"trace_decode_unavailable"} if any(
            item["diagnosis_readiness"] == "unavailable" for item in cex_work_items
        ) else set())
    )
    experiment_status = "eligible"
    if unexpected:
        experiment_status = "invalid"
    elif execution_status != "completed" and semantic_status == "eligible":
        experiment_status = "excluded"
        exclusion_reasons.append("execution_incomplete")
    elif semantic_status != "eligible":
        experiment_status = "excluded"
    elif formal_outcome != "all_proven":
        experiment_status = "excluded"
    elif missing:
        experiment_status = "invalid"
    exclusion_reasons = sorted(set(exclusion_reasons))
    result = {
        "schema_version": PROPERTY_RESULT_MAP_SCHEMA_VERSION,
        "property_profile_id": property_profile_id,
        "property_package_sha256": property_package_sha256,
        "assertion_delta_sha256": assertion_delta_sha256,
        "operation_plan_sha256": canonical_sha256(operation_plan),
        "execution_status": execution_status,
        "formal_outcome": formal_outcome,
        "semantic_status": semantic_status,
        "experiment_status": experiment_status,
        "exclusion_reasons": exclusion_reasons,
        "expected_operation_count": len(plan_by_id),
        "accounted_operation_count": len(normalized),
        "operation_set_complete": not unexpected and not missing,
        "missing_operation_ids": missing,
        "unexpected_operation_ids": sorted(set(unexpected)),
        "instances": instances,
        "cex_work_items": cex_work_items,
        "formal": dict(formal_metadata or {}),
        "unmatched_tool_results": [dict(item) for item in (unmatched_tool_results or [])],
    }
    validate_property_result_map(result)
    return result


def build_semantic_evidence(
    result_map: Mapping[str, Any], *, property_result_map_sha256: str
) -> Dict[str, Any]:
    """Materialize the Stage-3 semantic eligibility view from the result map."""
    validate_property_result_map(result_map)
    _require_sha(property_result_map_sha256, "property_result_map_sha256")
    evidence = {
        "schema_version": SEMANTIC_EVIDENCE_SCHEMA_VERSION,
        "property_package_sha256": result_map["property_package_sha256"],
        "property_result_map_sha256": property_result_map_sha256,
        "execution_status": result_map["execution_status"],
        "formal_outcome": result_map["formal_outcome"],
        "semantic_status": result_map["semantic_status"],
        "experiment_status": result_map["experiment_status"],
        "exclusion_reasons": list(result_map["exclusion_reasons"]),
        "instances": [
            {
                "instance_id": item["instance_id"],
                "semantic_verdict": item["semantic_verdict"],
                "operation_refs": [row["operation_id"] for row in item["operations"]],
            }
            for item in result_map["instances"]
        ],
    }
    validate_semantic_evidence(evidence)
    return evidence


def validate_semantic_evidence(value: Mapping[str, Any]) -> None:
    _object(value, "semantic_evidence")
    _fields(
        value,
        required={
            "schema_version",
            "property_package_sha256",
            "property_result_map_sha256",
            "execution_status",
            "formal_outcome",
            "semantic_status",
            "experiment_status",
            "exclusion_reasons",
            "instances",
        },
        optional=set(),
        path="semantic_evidence",
    )
    if value["schema_version"] != SEMANTIC_EVIDENCE_SCHEMA_VERSION:
        raise ResultContractError("unsupported semantic evidence version")
    _require_sha(value["property_package_sha256"], "semantic_evidence.property_package_sha256")
    _require_sha(value["property_result_map_sha256"], "semantic_evidence.property_result_map_sha256")
    if value["execution_status"] not in EXECUTION_STATUSES:
        raise ResultContractError("semantic_evidence.execution_status is invalid")
    if value["formal_outcome"] not in FORMAL_OUTCOMES:
        raise ResultContractError("semantic_evidence.formal_outcome is invalid")
    if value["semantic_status"] not in SEMANTIC_STATUSES:
        raise ResultContractError("semantic_evidence.semantic_status is invalid")
    if value["experiment_status"] not in EXPERIMENT_STATUSES:
        raise ResultContractError("semantic_evidence.experiment_status is invalid")
    if not isinstance(value["exclusion_reasons"], list):
        raise ResultContractError("semantic_evidence.exclusion_reasons must be a list")
    if not isinstance(value["instances"], list):
        raise ResultContractError("semantic_evidence.instances must be a list")


def map_primary_results_to_operations(
    operation_plan: Mapping[str, Any], primary_results: Iterable[Mapping[str, Any]]
) -> list[Dict[str, Any]]:
    """Map the existing exact assertion ledger into operation rows."""
    validate_operation_plan(operation_plan)
    by_property_id: Dict[str, Mapping[str, Any]] = {}
    by_label: Dict[str, Mapping[str, Any]] = {}
    for raw in primary_results:
        if not isinstance(raw, Mapping):
            raise ResultContractError("primary result must be an object")
        property_id = raw.get("expected_property_id") or raw.get("rtl_property_id")
        if isinstance(property_id, str) and property_id:
            by_property_id[property_id] = raw
        label = raw.get("rtl_label")
        if isinstance(label, str) and label:
            by_label[label] = raw
    rows = []
    for plan_item in operation_plan["operations"]:
        raw = by_property_id.get(plan_item["rtl_property_id"])
        if raw is None:
            raw = by_label.get(plan_item.get("target", ""))
        if raw is None:
            rows.append(
                {
                    "operation_id": plan_item["operation_id"],
                    "status": "not_run",
                    "reason": "missing_primary_result",
                }
            )
        else:
            row = dict(raw)
            row["operation_id"] = plan_item["operation_id"]
            rows.append(row)
    return rows


def _reduce_instance_semantics(
    operations: Sequence[Mapping[str, Any]],
    plan: Mapping[str, Any],
    *,
    operation_set_complete: bool,
) -> Dict[str, Any]:
    by_role: Dict[str, list[Mapping[str, Any]]] = defaultdict(list)
    for item in operations:
        by_role[item["role"]].append(item)
    declared_roles = set(plan.get("required_roles", [])) | set(SEMANTIC_GATE_ROLES)
    gate_verdicts: Dict[str, Dict[str, Any]] = {}
    reasons: list[str] = []
    for role in sorted(declared_roles):
        if role == "primary_assertion":
            rows = by_role.get(role, [])
            if not rows or any(row["status"] in {"not_run", "tool_error", "inconclusive"} for row in rows):
                gate_verdicts[role] = {"status": "inconclusive", "operation_refs": [row["operation_id"] for row in rows]}
                reasons.append("primary_operation_incomplete")
            else:
                gate_verdicts[role] = {"status": "passed", "operation_refs": [row["operation_id"] for row in rows]}
            continue
        rows = by_role.get(role, [])
        refs = [row["operation_id"] for row in rows]
        if not rows:
            gate_verdicts[role] = {"status": "not_run", "operation_refs": refs}
            reasons.append(f"{role}_not_run")
            continue
        statuses = {row["status"] for row in rows}
        if any(status in {"tool_error", "inconclusive", "not_run"} for status in statuses):
            gate_verdicts[role] = {"status": "inconclusive", "operation_refs": refs}
            reasons.append(f"{role}_not_run")
        elif role == "negative_oracle":
            killed = all(
                row.get("oracle_verdict") == "killed" or row["status"] == "killed"
                for row in rows
            )
            gate_verdicts[role] = {"status": "passed" if killed else "failed", "operation_refs": refs}
            if not killed:
                reasons.append("negative_oracle_failed")
        elif all(status == "covered" for status in statuses):
            gate_verdicts[role] = {"status": "passed", "operation_refs": refs}
        else:
            gate_verdicts[role] = {"status": "failed", "operation_refs": refs}
            reasons.append(f"{role}_failed")
    if not operation_set_complete:
        reasons.append("operation_set_mismatch")
    statuses = {item["status"] for item in gate_verdicts.values()}
    if "failed" in statuses:
        status = "ineligible"
    elif "inconclusive" in statuses or "not_run" in statuses:
        status = "inconclusive"
    else:
        status = "eligible"
    return {
        "status": status,
        "gate_verdicts": gate_verdicts,
        "reasons": sorted(set(reasons)),
    }


def _reduce_overall_semantics(
    instances: Sequence[Mapping[str, Any]], *, operation_set_complete: bool
) -> tuple[str, list[str]]:
    statuses = [item["semantic_verdict"]["status"] for item in instances]
    reasons = [
        reason
        for item in instances
        for reason in item["semantic_verdict"].get("reasons", [])
    ]
    if not operation_set_complete:
        return "inconclusive", sorted(set(reasons + ["operation_set_mismatch"]))
    if "ineligible" in statuses:
        return "ineligible", sorted(set(reasons))
    if not statuses or "inconclusive" in statuses:
        return "inconclusive", sorted(set(reasons))
    return "eligible", sorted(set(reasons))


def _reduce_formal_outcome(operations: Sequence[Mapping[str, Any]]) -> str:
    primary = [item for item in operations if item["role"] == "primary_assertion"]
    if not primary or all(item["status"] == "not_run" for item in primary):
        return "not_run"
    if any(item["status"] == "cex" for item in primary):
        return "cex"
    if all(item["status"] == "proven" for item in primary):
        return "all_proven"
    return "inconclusive"


def _reduce_execution_status(
    operations: Sequence[Mapping[str, Any]],
    *,
    unexpected: Sequence[str],
    missing: Sequence[str],
    hint: Optional[str],
) -> str:
    if hint == "tool_error" or any(item["status"] == "tool_error" for item in operations):
        return "tool_error"
    if unexpected or missing or any(item["status"] in {"not_run", "inconclusive"} for item in operations):
        return "partial"
    if hint in EXECUTION_STATUSES:
        return hint
    return "completed"


def _formal_exclusion_reasons(formal_outcome: str, operations: Sequence[Mapping[str, Any]]) -> list[str]:
    if formal_outcome == "cex":
        return ["formal_counterexample"]
    if formal_outcome == "not_run":
        return ["formal_not_run"]
    if formal_outcome == "inconclusive":
        return ["formal_inconclusive"]
    if any(
        item["role"] == "negative_oracle"
        and item["status"] != "covered"
        and item.get("oracle_verdict") != "killed"
        for item in operations
    ):
        return ["negative_oracle_not_run"]
    return []


def _build_cex_work_items(operations: Sequence[Mapping[str, Any]]) -> list[Dict[str, Any]]:
    work_items = []
    for item in operations:
        if item["role"] != "primary_assertion" or item["status"] != "cex":
            continue
        contract = item.get("trace_decode_contract")
        ready = False
        unavailable_reason = "trace_decode_contract_missing"
        if isinstance(contract, Mapping):
            try:
                validate_trace_decode_contract(contract)
                if contract.get("trace_path") != item.get("trace_path"):
                    unavailable_reason = "trace_decode_path_mismatch"
                else:
                    ready = contract.get("decode_readiness") == "ready"
                    unavailable_reason = contract.get("fail_closed_reason") or "trace_decode_unavailable"
            except ResultContractError as exc:
                unavailable_reason = f"invalid_trace_decode_contract:{exc}"
        work_items.append(
            {
                "operation_id": item["operation_id"],
                "instance_id": item["instance_id"],
                "rtl_property_id": item["rtl_property_id"],
                "trace_path": item.get("trace_path"),
                "trace_decode_contract": contract,
                "diagnosis_readiness": "ready" if ready else "unavailable",
                "readiness_reason": None if ready else unavailable_reason,
            }
        )
    return work_items


def _normalize_operation_result(plan_item: Mapping[str, Any], raw: Mapping[str, Any]) -> Dict[str, Any]:
    status = _normalize_status(raw.get("status"))
    reason = str(raw.get("reason") or "unspecified")
    if status not in plan_item["expected_statuses"]:
        reason = f"status_not_allowed_for_operation:{status}"
        status = "tool_error"
    result = {
        "operation_id": plan_item["operation_id"],
        "instance_id": plan_item["instance_id"],
        "role": plan_item["role"],
        "rtl_property_id": plan_item["rtl_property_id"],
        "status": status,
        "reason": reason,
    }
    for key in (
        "observed_property_id",
        "engine",
        "bound",
        "runtime_s",
        "trace_path",
        "trace_decode_contract",
        "evidence_refs",
        "oracle_verdict",
        "mutation_id",
    ):
        if key in raw and raw[key] is not None:
            result[key] = raw[key]
    return result


def _normalize_status(value: Any) -> str:
    if value in OPERATION_STATUSES:
        return str(value)
    if value in {"timeout", "missing", "invalid", "unknown", "undetermined", "error"}:
        return "inconclusive" if value not in {"missing"} else "not_run"
    if value == "killed":
        # Mutation result rows use the normal operation status plus an oracle
        # verdict; accepting this value here makes the reducer fail closed only
        # if the caller omitted that verdict.
        return "cex"
    return "tool_error"


def _validate_operation_result(value: Mapping[str, Any], path: str) -> None:
    _object(value, path)
    _fields(
        value,
        required={"operation_id", "instance_id", "role", "rtl_property_id", "status", "reason"},
        optional={
            "observed_property_id",
            "engine",
            "bound",
            "runtime_s",
            "trace_path",
            "trace_decode_contract",
            "evidence_refs",
            "oracle_verdict",
            "mutation_id",
        },
        path=path,
    )
    _string(value, "operation_id", path)
    _string(value, "instance_id", path)
    if value["role"] not in OPERATION_ROLES:
        raise ResultContractError(f"{path}.role is invalid")
    _string(value, "rtl_property_id", path)
    if value["status"] not in OPERATION_STATUSES:
        raise ResultContractError(f"{path}.status is invalid")
    _string(value, "reason", path)
    if "trace_decode_contract" in value and value["trace_decode_contract"] is not None:
        contract = value["trace_decode_contract"]
        validate_trace_decode_contract(contract)
        if "trace_path" in contract and "trace_path" in value:
            if contract.get("trace_path") != value.get("trace_path"):
                raise ResultContractError(
                    f"{path}.trace_decode_contract.trace_path does not match trace_path"
                )


def _validate_instance_verdict(value: Mapping[str, Any], path: str) -> None:
    _object(value, f"{path}.semantic_verdict")
    _fields(value, required={"status", "gate_verdicts", "reasons"}, optional=set(), path=f"{path}.semantic_verdict")
    if value["status"] not in SEMANTIC_STATUSES:
        raise ResultContractError(f"{path}.semantic_verdict.status is invalid")
    if not isinstance(value["gate_verdicts"], Mapping):
        raise ResultContractError(f"{path}.semantic_verdict.gate_verdicts must be an object")
    for role, gate in value["gate_verdicts"].items():
        if role not in OPERATION_ROLES:
            raise ResultContractError(f"{path}.semantic_verdict has unknown role {role}")
        if not isinstance(gate, Mapping) or gate.get("status") not in {"passed", "failed", "inconclusive", "not_run"}:
            raise ResultContractError(f"{path}.semantic_verdict gate {role} is invalid")
        if not isinstance(gate.get("operation_refs"), list):
            raise ResultContractError(f"{path}.semantic_verdict gate {role} has no refs")
    if not isinstance(value["reasons"], list) or not all(isinstance(item, str) and item for item in value["reasons"]):
        raise ResultContractError(f"{path}.semantic_verdict.reasons is invalid")


def _validate_clock_reset(value: Any, path: str, *, allow_unmaterialized: bool) -> None:
    _object(value, path)
    _fields(value, required={"role", "rtl_name", "width"}, optional={"active_level"}, path=path)
    _string(value, "role", path)
    if value["rtl_name"] is None and allow_unmaterialized:
        pass
    else:
        _string(value, "rtl_name", path)
    if not isinstance(value["width"], int) or value["width"] <= 0:
        raise ResultContractError(f"{path}.width must be positive")


def _deepcopy_json(value: Any) -> Any:
    return json.loads(json.dumps(value, ensure_ascii=False))


def _object(value: Any, path: str) -> None:
    if not isinstance(value, Mapping):
        raise ResultContractError(f"{path} must be an object")


def _fields(value: Mapping[str, Any], *, required: set[str], optional: set[str], path: str) -> None:
    unknown = set(value) - required - optional
    missing = required - set(value)
    if unknown:
        raise ResultContractError(f"{path} has unknown fields: {sorted(unknown)}")
    if missing:
        raise ResultContractError(f"{path} is missing fields: {sorted(missing)}")


def _string(value: Mapping[str, Any], key: str, path: str) -> str:
    item = value.get(key)
    if not isinstance(item, str) or not item:
        raise ResultContractError(f"{path}.{key} must be a non-empty string")
    return item


def _require_sha(value: Any, path: str) -> None:
    if not isinstance(value, str) or not _SHA256_RE.fullmatch(value):
        raise ResultContractError(f"{path} must be a lowercase SHA-256")


# Public names used by the stage/compiler boundary.  They are intentionally
# aliases to the single implementations above, rather than parallel readers.
validate_verification_operation_plan = validate_operation_plan
validate_result_map = validate_property_result_map


def reduce_semantic_verdict(
    operation_plan: Mapping[str, Any], operation_results: Iterable[Mapping[str, Any]]
) -> Dict[str, Any]:
    """Reduce only the semantic-facing view for unit and fixture tests."""
    result = reduce_property_result_map(
        operation_plan=operation_plan,
        operation_results=operation_results,
        property_profile_id="unbound",
        property_package_sha256="0" * 64,
        assertion_delta_sha256="0" * 64,
    )
    return {
        "semantic_status": result["semantic_status"],
        "experiment_status": result["experiment_status"],
        "exclusion_reasons": result["exclusion_reasons"],
        "formal_outcome": result["formal_outcome"],
        "execution_status": result["execution_status"],
        "instances": result["instances"],
        "cex_work_items": result["cex_work_items"],
    }


evaluate_semantic_evidence = reduce_semantic_verdict
