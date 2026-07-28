"""Three-call, two-query causal evidence loop for SpecFlow Stage 3."""

from __future__ import annotations

import json
from pathlib import Path
import time
from typing import Any, Dict, Mapping, Optional, Protocol, Sequence

from src.core.artifact_contract import file_sha256
from src.core.formal_operations import canonical_sha256

from .causal_contract import (
    CAUSAL_GRAPH_V2_SCHEMA,
    CAUSAL_GRAPH_V3_SCHEMA,
    CausalContractError,
    causal_graph_schema,
    causal_graph_stable_ids,
    stable_candidate_id,
)
from .causal_tools import (
    READ_TOOL_NAMES,
    TERMINATION_TOOL_NAMES,
    CausalToolError,
    build_causal_tool_schemas,
    execute_causal_read_tool,
    new_visibility,
)
from .stages import DIAGNOSIS_CLASSIFICATIONS


MAX_MODEL_CALLS = 3
MAX_EVIDENCE_QUERIES = 2
MAX_PROTOCOL_REPAIRS = 1


class DiagnosisModel(Protocol):
    def chat_with_tools(self, **kwargs: Any) -> Dict[str, Any]: ...


class CausalLoopError(ValueError):
    """Raised when deterministic loop inputs are malformed."""


def run_causal_evidence_loop(
    model: DiagnosisModel,
    *,
    projection: Mapping[str, Any],
    graph_manifest: Mapping[str, Any],
    source_projection: Mapping[str, Any],
    graphs: Mapping[str, Mapping[str, Any]],
    reviewed_package: Mapping[str, Any],
    frozen_clauses: Sequence[Mapping[str, Any]],
    project_root: Path,
    stage3: Path,
    track_d: bool = False,
) -> tuple[Dict[str, Any], Dict[str, int]]:
    """Run the complete bounded loop and write replayable audit artifacts."""

    stage3 = Path(stage3)
    overviews = _graph_overviews(graphs)
    visibility = new_visibility(graphs, overviews)
    domain = _diagnosis_domain(projection)
    if any(
        overview.get("status") == "unsupported"
        for overview in overviews.values()
    ):
        candidate = deterministic_inconclusive_candidate(
            domain, reason="unsupported_causal_graph_schema"
        )
        _write_loop_audit(
            stage3,
            model_rows=[],
            query_rows=[],
            attempt_rows=[],
            status="deterministic_inconclusive",
            graph_manifest=graph_manifest,
            source_projection=source_projection,
            reason="unsupported_causal_graph_schema",
        )
        return candidate, {
            "model_calls": 0,
            "evidence_queries": 0,
            "protocol_repairs": 0,
        }
    _bind_causal_domain(
        domain,
        graph_manifest=graph_manifest,
        graphs=graphs,
        source_projection=source_projection,
    )
    base_evidence_refs = set(domain["evidence_refs"])
    base_evidence_refs.update(
        f"causal_graph:{graph_id}" for graph_id in sorted(graphs)
    )
    initial_context = {
        "task": "classify bounded deterministic formal and causal evidence",
        "projection": _projection_view(projection),
        "frozen_spec_clauses": [dict(row) for row in frozen_clauses],
        "reviewed_obligations": list(reviewed_package.get("obligations", [])),
        "reviewed_monitor_ir": list(reviewed_package.get("monitors", [])),
        "causal_graph_manifest": dict(graph_manifest),
        "causal_overviews": overviews,
        "allowed_ids": {
            "operation_ids": domain["operation_ids"],
            "object_ids": domain["object_ids"],
            "monitor_state_ids": domain["state_ids"],
            "graph_ids": sorted(graphs),
            "node_ids": sorted(visibility["node_ids"]),
            "edge_ids": [],
            "source_candidate_ids": [],
        },
        "allowed_evidence_refs": sorted(base_evidence_refs),
        "budget": {
            "max_model_calls": MAX_MODEL_CALLS,
            "max_evidence_queries": MAX_EVIDENCE_QUERIES,
            "max_protocol_repairs": MAX_PROTOCOL_REPAIRS,
            "parallel_tool_calls": False,
            "exactly_one_tool_per_call": True,
        },
        "authority": {
            "model_may_approve": False,
            "model_may_set_final_verdict": False,
            "model_may_request_path_signal_cycle_or_regex": False,
            "model_may_patch": False,
        },
        "track_d": bool(track_d),
    }
    if _contains_absolute_path(initial_context):
        raise CausalLoopError("initial diagnosis context contains an absolute path")
    messages: list[Dict[str, Any]] = [
        {
            "role": "system",
            "content": (
                "You are a bounded evidence classifier. Use exactly one supplied "
                "tool per call. You cannot approve, set a verdict, request paths or "
                "signals, or propose a patch."
            ),
        },
        {
            "role": "user",
            "content": json.dumps(initial_context, sort_keys=True),
        },
    ]
    model_rows = []
    query_rows = []
    attempt_rows = []
    model_calls = 0
    query_calls = 0
    repairs = 0
    candidate: Optional[Dict[str, Any]] = None
    termination_reason = "budget_exhausted_without_submission"

    while model_calls < MAX_MODEL_CALLS and candidate is None:
        evidence_refs = _current_evidence_refs(
            base_evidence_refs, visibility
        )
        tools = build_causal_tool_schemas(
            visibility,
            operation_ids=domain["operation_ids"],
            failure_cycles=domain["failure_cycles"],
            object_ids=domain["object_ids"],
            monitor_state_ids=domain["state_ids"],
            clause_locators=domain["clause_locators"],
            evidence_refs=evidence_refs,
        )
        before = {
            "model_calls_remaining": MAX_MODEL_CALLS - model_calls,
            "evidence_queries_remaining": MAX_EVIDENCE_QUERIES - query_calls,
            "protocol_repairs_remaining": MAX_PROTOCOL_REPAIRS - repairs,
        }
        usage_before = _model_usage_snapshot(model)
        call_started = time.perf_counter()
        try:
            response = model.chat_with_tools(
                messages=messages,
                tools=tools,
                max_tokens=4096,
                temperature=0.0,
                tool_choice="required",
                parallel_tool_calls=False,
                usage_metadata={
                    "stage": "diagnose",
                    "task_type": "causal_evidence_diagnosis",
                },
            )
        except Exception as exc:
            wall_time_s = time.perf_counter() - call_started
            model_calls += 1
            model_rows.append(
                {
                    "schema_version": "specflow_model_call.v1",
                    "sequence": model_calls,
                    "stage": "diagnose",
                    "parallel_tool_calls": False,
                    "messages_sha256": canonical_sha256(messages),
                    "tools_sha256": canonical_sha256(tools),
                    "response_sha256": None,
                    "budget_before": before,
                    "response_type": "model_error",
                    "outcome": "model_error",
                    "error": _safe_error(exc),
                    "budget_after": _budget_after(
                        model_calls, query_calls, repairs
                    ),
                    "wall_time_s": round(wall_time_s, 6),
                    "token_usage": _model_usage_delta(
                        usage_before, _model_usage_snapshot(model)
                    ),
                }
            )
            termination_reason = "model_call_failed"
            break
        model_calls += 1
        wall_time_s = time.perf_counter() - call_started
        sequence = model_calls
        model_row = {
            "schema_version": "specflow_model_call.v1",
            "sequence": sequence,
            "stage": "diagnose",
            "parallel_tool_calls": False,
            "messages_sha256": canonical_sha256(messages),
            "tools_sha256": canonical_sha256(tools),
            "response_sha256": canonical_sha256(response),
            "budget_before": before,
            "response_type": (
                response.get("type")
                if isinstance(response, Mapping)
                else type(response).__name__
            ),
            "wall_time_s": round(wall_time_s, 6),
            "token_usage": _model_usage_delta(
                usage_before, _model_usage_snapshot(model)
            ),
        }
        calls = (
            response.get("function_calls")
            if isinstance(response, Mapping)
            and response.get("type") == "function_calls"
            else None
        )
        if not isinstance(calls, list) or len(calls) != 1:
            error = "exactly one diagnosis tool call is required"
            model_row.update(
                {
                    "outcome": "protocol_error",
                    "error": error,
                    "budget_after": _budget_after(
                        model_calls, query_calls, repairs
                    ),
                }
            )
            model_rows.append(model_row)
            attempt_rows.append(
                _attempt_row(sequence, None, "protocol_error", error)
            )
            if repairs >= MAX_PROTOCOL_REPAIRS:
                termination_reason = "protocol_repair_exhausted"
                break
            repairs += 1
            messages.append(
                {
                    "role": "user",
                    "content": "Bounded protocol repair required: " + error,
                }
            )
            continue
        call = calls[0]
        name = call.get("name")
        arguments = call.get("arguments")
        call_id = str(call.get("id") or f"diagnosis_call_{sequence}")
        model_row.update({"call_id": call_id, "tool_name": name})
        if name in READ_TOOL_NAMES:
            if query_calls >= MAX_EVIDENCE_QUERIES:
                error = "causal evidence query budget is exhausted"
                model_row.update(
                    {
                        "outcome": "query_budget_exhausted",
                        "error": error,
                        "budget_after": _budget_after(
                            model_calls, query_calls, repairs
                        ),
                    }
                )
                model_rows.append(model_row)
                termination_reason = "query_budget_exhausted"
                break
            query_calls += 1
            query_sequence = query_calls
            try:
                result_payload = execute_causal_read_tool(
                    str(name),
                    arguments,
                    graphs=graphs,
                    source_projection=source_projection,
                    visibility=visibility,
                    project_root=project_root,
                )
                query_status = "complete"
                query_error = None
            except (CausalToolError, ValueError) as exc:
                result_payload = {
                    "status": "rejected",
                    "error_code": "causal_query_rejected",
                    "error": str(exc),
                }
                query_status = "rejected"
                query_error = str(exc)
            result = {
                "schema_version": "causal_query_result.v1",
                "sequence": query_sequence,
                "tool_name": name,
                "arguments": (
                    dict(arguments)
                    if query_status == "complete"
                    and isinstance(arguments, Mapping)
                    else {
                        "invalid_arguments_sha256": canonical_sha256(arguments)
                    }
                ),
                "status": query_status,
                "result": result_payload,
                "remaining_budget": _budget_after(
                    model_calls, query_calls, repairs
                ),
            }
            if _contains_absolute_path(result):
                raise CausalLoopError("causal query result leaked an absolute path")
            result_path = (
                stage3
                / "causal_queries"
                / f"query_{query_sequence:04d}.json"
            )
            _write_json(result_path, result)
            graph_id = (
                arguments.get("graph_id")
                if isinstance(arguments, Mapping)
                else None
            )
            query_row = {
                "schema_version": "causal_query_log.v1",
                "sequence": query_sequence,
                "model_call_sequence": sequence,
                "tool_name": name,
                "arguments_sha256": canonical_sha256(arguments),
                "graph_id": graph_id,
                "result": {
                    "path": result_path.relative_to(stage3).as_posix(),
                    "sha256": file_sha256(result_path),
                },
                "result_item_counts": _result_item_counts(result_payload),
                "budget_before": before,
                "budget_after": _budget_after(
                    model_calls, query_calls, repairs
                ),
                "outcome": query_status,
            }
            if query_error:
                query_row["error"] = query_error
            query_rows.append(query_row)
            model_row.update(
                {
                    "outcome": "tool_result",
                    "query_result_sha256": file_sha256(result_path),
                    "budget_after": _budget_after(
                        model_calls, query_calls, repairs
                    ),
                }
            )
            model_rows.append(model_row)
            messages.extend(
                [
                    {
                        "role": "assistant",
                        "tool_calls": [
                            {
                                "id": call_id,
                                "type": "function",
                                "function": {
                                    "name": name,
                                    "arguments": json.dumps(
                                        arguments, sort_keys=True
                                    ),
                                },
                            }
                        ],
                    },
                    {
                        "role": "tool",
                        "tool_call_id": call_id,
                        "content": json.dumps(result, sort_keys=True),
                    },
                ]
            )
            continue
        if name not in TERMINATION_TOOL_NAMES:
            error = "unexpected diagnosis tool"
            model_row.update(
                {
                    "outcome": "protocol_error",
                    "error": error,
                    "budget_after": _budget_after(
                        model_calls, query_calls, repairs
                    ),
                }
            )
            model_rows.append(model_row)
            attempt_rows.append(
                _attempt_row(sequence, name, "protocol_error", error)
            )
            if repairs >= MAX_PROTOCOL_REPAIRS:
                termination_reason = "protocol_repair_exhausted"
                break
            repairs += 1
            messages.append(
                {
                    "role": "user",
                    "content": "Bounded protocol repair required: " + error,
                }
            )
            continue
        try:
            if name == "submit_diagnosis_candidate":
                candidate = validate_diagnosis_candidate_v2(
                    arguments,
                    domain=domain,
                    visibility=visibility,
                    evidence_refs=evidence_refs,
                    model_call_ref=call_id,
                )
            else:
                candidate = _inconclusive_from_report(
                    arguments,
                    domain=domain,
                    evidence_refs=evidence_refs,
                    model_call_ref=call_id,
                )
            attempt_rows.append(
                _attempt_row(
                    sequence,
                    name,
                    "accepted_candidate",
                    "",
                    arguments=arguments,
                )
            )
            model_row.update(
                {
                    "outcome": "accepted_candidate",
                    "classification": candidate["classification"],
                    "budget_after": _budget_after(
                        model_calls, query_calls, repairs
                    ),
                }
            )
            model_rows.append(model_row)
        except CausalLoopError as exc:
            error = str(exc)
            attempt_rows.append(
                _attempt_row(
                    sequence,
                    name,
                    "validation_error",
                    error,
                    arguments=arguments,
                )
            )
            model_row.update(
                {
                    "outcome": "validation_error",
                    "error": error,
                    "budget_after": _budget_after(
                        model_calls, query_calls, repairs
                    ),
                }
            )
            model_rows.append(model_row)
            if repairs >= MAX_PROTOCOL_REPAIRS:
                termination_reason = "protocol_repair_exhausted"
                break
            repairs += 1
            messages.append(
                {
                    "role": "user",
                    "content": "Bounded protocol repair required: " + error,
                }
            )

    if candidate is None:
        candidate = deterministic_inconclusive_candidate(
            domain,
            reason=termination_reason,
        )
    _write_loop_audit(
        stage3,
        model_rows=model_rows,
        query_rows=query_rows,
        attempt_rows=attempt_rows,
        status=(
            "deterministic_inconclusive"
            if candidate.get("model_call_ref")
            == "controller:deterministic_inconclusive"
            else "candidate_submitted"
        ),
        graph_manifest=graph_manifest,
        source_projection=source_projection,
    )
    return candidate, {
        "model_calls": model_calls,
        "evidence_queries": query_calls,
        "protocol_repairs": repairs,
    }


def validate_diagnosis_candidate_v2(
    value: Any,
    *,
    domain: Mapping[str, Any],
    visibility: Mapping[str, set[str]],
    evidence_refs: Sequence[str],
    model_call_ref: str,
) -> Dict[str, Any]:
    fields = {
        "classification",
        "operation_id",
        "failure_cycle",
        "object_ids",
        "monitor_state_ids",
        "spec_clause_locator",
        "evidence_refs",
        "causal_graph_ids",
        "causal_node_ids",
        "causal_edge_ids",
        "ranked_source_candidates",
        "summary",
    }
    if not isinstance(value, Mapping) or set(value) != fields:
        raise CausalLoopError("diagnosis candidate has an invalid exact schema")
    candidate = dict(value)
    if candidate["classification"] not in DIAGNOSIS_CLASSIFICATIONS:
        raise CausalLoopError("unknown diagnosis classification")
    if candidate["operation_id"] not in domain["operation_ids"]:
        raise CausalLoopError("diagnosis references an unknown operation")
    operation_domain = domain["by_operation"][candidate["operation_id"]]
    if candidate["failure_cycle"] != operation_domain["failure_cycle"]:
        raise CausalLoopError(
            "diagnosis failure cycle is not bound to its operation"
        )
    _subset(
        candidate["object_ids"],
        operation_domain["object_ids"],
        "object IDs",
        True,
    )
    _subset(
        candidate["monitor_state_ids"],
        operation_domain["state_ids"],
        "monitor state IDs",
        False,
    )
    if (
        candidate["spec_clause_locator"]
        != operation_domain["clause_locator"]
    ):
        raise CausalLoopError(
            "diagnosis spec clause is not bound to its operation"
        )
    operation_graph_ids = domain["graph_ids_by_operation"].get(
        candidate["operation_id"], []
    )
    _subset(
        candidate["causal_graph_ids"],
        operation_graph_ids,
        "causal graph IDs",
        False,
    )
    cited_graph_ids = candidate["causal_graph_ids"]
    allowed_node_ids = sorted(
        {
            node_id
            for graph_id in cited_graph_ids
            for node_id in domain["node_ids_by_graph"][graph_id]
        }
    )
    allowed_edge_ids = sorted(
        {
            edge_id
            for graph_id in cited_graph_ids
            for edge_id in domain["edge_ids_by_graph"][graph_id]
        }
    )
    _subset(
        candidate["causal_node_ids"],
        allowed_node_ids,
        "causal node IDs",
        False,
    )
    _subset(
        candidate["causal_edge_ids"],
        allowed_edge_ids,
        "causal edge IDs",
        False,
    )
    allowed_candidate_ids = {
        candidate_id
        for candidate_id, graph_ids in domain["source_graph_ids"].items()
        if set(graph_ids) & set(cited_graph_ids)
    }
    scoped_evidence_refs = set(operation_domain["evidence_refs"])
    scoped_evidence_refs.update(
        f"causal_graph:{row}" for row in cited_graph_ids
    )
    scoped_evidence_refs.update(
        f"causal_node:{row}" for row in candidate["causal_node_ids"]
    )
    scoped_evidence_refs.update(
        f"causal_edge:{row}" for row in candidate["causal_edge_ids"]
    )
    scoped_evidence_refs.update(
        f"source_candidate:{row}" for row in allowed_candidate_ids
    )
    _subset(
        candidate["evidence_refs"],
        sorted(scoped_evidence_refs & set(evidence_refs)),
        "evidence refs",
        True,
    )
    if not isinstance(candidate["summary"], str) or not candidate["summary"].strip():
        raise CausalLoopError("diagnosis summary is required")
    if _contains_unsafe_free_text(candidate["summary"]):
        raise CausalLoopError("diagnosis summary contains path or shell-like text")
    ranking = candidate["ranked_source_candidates"]
    if not isinstance(ranking, list):
        raise CausalLoopError("ranked source candidates must be a list")
    seen = set()
    for row in ranking:
        if not isinstance(row, Mapping) or set(row) != {
            "candidate_id",
            "rank_group",
            "evidence_refs",
        }:
            raise CausalLoopError("ranked source candidate is malformed")
        candidate_id = row["candidate_id"]
        if (
            candidate_id in seen
            or candidate_id not in visibility["source_candidate_ids"]
            or candidate_id not in allowed_candidate_ids
        ):
            raise CausalLoopError("ranked source candidate ID is unknown")
        seen.add(candidate_id)
        if (
            isinstance(row["rank_group"], bool)
            or not isinstance(row["rank_group"], int)
            or row["rank_group"] < 1
        ):
            raise CausalLoopError("rank group must be a positive integer")
        _subset(
            row["evidence_refs"],
            sorted(scoped_evidence_refs & set(evidence_refs)),
            "ranking evidence refs",
            True,
        )
    candidate["schema_version"] = "diagnosis_candidate.v2"
    candidate["model_call_ref"] = model_call_ref
    body = dict(candidate)
    candidate["candidate_id"] = stable_candidate_id(body)
    return candidate


def deterministic_inconclusive_candidate(
    domain: Mapping[str, Any], *, reason: str
) -> Dict[str, Any]:
    operation = domain["operation_ids"][0]
    operation_domain = domain["by_operation"][operation]
    cycle = operation_domain["failure_cycle"]
    object_id = operation_domain["object_ids"][0]
    clause = operation_domain["clause_locator"]
    refs = operation_domain["evidence_refs"] or ["projection:incomplete"]
    body = {
        "schema_version": "diagnosis_candidate.v2",
        "classification": "inconclusive",
        "operation_id": operation,
        "failure_cycle": cycle,
        "object_ids": [object_id],
        "monitor_state_ids": [],
        "spec_clause_locator": clause,
        "evidence_refs": [refs[0]],
        "causal_graph_ids": [],
        "causal_node_ids": [],
        "causal_edge_ids": [],
        "ranked_source_candidates": [],
        "summary": f"Deterministic inconclusive: {reason}.",
        "model_call_ref": "controller:deterministic_inconclusive",
    }
    body["candidate_id"] = stable_candidate_id(body)
    return body


def write_not_required_loop_artifacts(
    stage3: Path,
    *,
    reason: str,
    graph_manifest: Mapping[str, Any],
    source_projection: Mapping[str, Any],
) -> None:
    _write_loop_audit(
        Path(stage3),
        model_rows=[],
        query_rows=[],
        attempt_rows=[],
        status="not_required",
        graph_manifest=graph_manifest,
        source_projection=source_projection,
        reason=reason,
    )


def _inconclusive_from_report(
    value: Any,
    *,
    domain: Mapping[str, Any],
    evidence_refs: Sequence[str],
    model_call_ref: str,
) -> Dict[str, Any]:
    fields = {"reason_code", "evidence_refs", "missing_evidence"}
    if not isinstance(value, Mapping) or set(value) != fields:
        raise CausalLoopError("inconclusive report has an invalid exact schema")
    if value["reason_code"] not in {
        "causal_evidence_incomplete",
        "causal_identity_ambiguous",
        "causal_query_budget_exhausted",
        "exact_source_projection_missing",
        "formal_evidence_incomplete",
    }:
        raise CausalLoopError("inconclusive reason code is invalid")
    _subset(value["evidence_refs"], list(evidence_refs), "evidence refs", True)
    missing = value["missing_evidence"]
    if (
        not isinstance(missing, list)
        or not missing
        or any(not isinstance(row, str) or not row for row in missing)
        or len(missing) != len(set(missing))
        or any(_contains_unsafe_free_text(row) for row in missing)
    ):
        raise CausalLoopError("missing evidence must be a unique non-empty list")
    operation = domain["operation_ids"][0]
    operation_domain = domain["by_operation"][operation]
    body = {
        "schema_version": "diagnosis_candidate.v2",
        "classification": "inconclusive",
        "operation_id": operation,
        "failure_cycle": operation_domain["failure_cycle"],
        "object_ids": [operation_domain["object_ids"][0]],
        "monitor_state_ids": [],
        "spec_clause_locator": operation_domain["clause_locator"],
        "evidence_refs": list(value["evidence_refs"]),
        "causal_graph_ids": [],
        "causal_node_ids": [],
        "causal_edge_ids": [],
        "ranked_source_candidates": [],
        "summary": (
            f"Inconclusive ({value['reason_code']}): "
            + ", ".join(missing)
        ),
        "model_call_ref": model_call_ref,
    }
    body["candidate_id"] = stable_candidate_id(body)
    return body


def _diagnosis_domain(
    projection: Mapping[str, Any],
) -> Dict[str, Any]:
    traces = [
        row
        for row in projection.get("traces", [])
        if isinstance(row, Mapping) and row.get("operation_id")
    ]
    operation_ids = sorted({str(row["operation_id"]) for row in traces})
    if len(operation_ids) != len(traces):
        raise CausalLoopError("projection repeats an operation identity")
    failure_cycles = sorted(
        {
            int(row["failure_cycle"])
            for row in traces
            if isinstance(row.get("failure_cycle"), int)
            and not isinstance(row.get("failure_cycle"), bool)
        }
    )
    object_ids = sorted(
        {
            str(obj["object_id"])
            for row in traces
            for obj in row.get("source_objects", [])
            if obj.get("object_id")
        }
    )
    state_ids = sorted(
        {
            str(state["state_id"])
            for row in traces
            for state in row.get("monitor_states", [])
            if state.get("state_id")
        }
    )
    clauses = sorted(
        {
            str(row["spec_clause"]["locator"])
            for row in traces
            if isinstance(row.get("spec_clause"), Mapping)
            and row["spec_clause"].get("locator")
        }
    )
    if not operation_ids or not failure_cycles or not object_ids or not clauses:
        raise CausalLoopError(
            "exact CEX diagnosis domain is missing stable identities"
        )
    refs = set()
    by_operation: Dict[str, Dict[str, Any]] = {}
    for row in traces:
        operation = str(row["operation_id"])
        cycle = row.get("failure_cycle")
        row_object_ids = sorted(
            {
                str(obj["object_id"])
                for obj in row.get("source_objects", [])
                if obj.get("object_id")
            }
        )
        row_state_ids = sorted(
            {
                str(state["state_id"])
                for state in row.get("monitor_states", [])
                if state.get("state_id")
            }
        )
        locator = row.get("spec_clause", {}).get("locator")
        if (
            isinstance(cycle, bool)
            or not isinstance(cycle, int)
            or cycle < 0
            or not row_object_ids
            or not locator
        ):
            raise CausalLoopError(
                "operation diagnosis domain is missing exact identities"
            )
        operation_refs = {
            "operation:" + operation,
            f"trace_cycle:{operation}:{cycle}",
            "spec:" + str(locator),
        }
        for obj in row.get("source_objects", []):
            operation_refs.add("object:" + str(obj["object_id"]))
        for state in row.get("monitor_states", []):
            operation_refs.add("state:" + str(state["state_id"]))
        refs.update(operation_refs)
        by_operation[operation] = {
            "failure_cycle": cycle,
            "object_ids": row_object_ids,
            "state_ids": row_state_ids,
            "clause_locator": str(locator),
            "evidence_refs": sorted(operation_refs),
        }
    return {
        "operation_ids": operation_ids,
        "failure_cycles": failure_cycles,
        "object_ids": object_ids,
        "state_ids": state_ids,
        "clause_locators": clauses,
        "evidence_refs": sorted(refs),
        "by_operation": by_operation,
    }


def _bind_causal_domain(
    domain: Dict[str, Any],
    *,
    graph_manifest: Mapping[str, Any],
    graphs: Mapping[str, Mapping[str, Any]],
    source_projection: Mapping[str, Any],
) -> None:
    """Bind graph, node, edge, and source identities to exact operations."""

    manifest_rows = graph_manifest.get("graphs", [])
    if not isinstance(manifest_rows, list):
        raise CausalLoopError("causal graph manifest rows are invalid")
    graph_ids_by_operation = {
        operation_id: [] for operation_id in domain["operation_ids"]
    }
    manifest_graph_ids = set()
    for row in manifest_rows:
        if not isinstance(row, Mapping):
            raise CausalLoopError("causal graph manifest row is invalid")
        graph_id = row.get("graph_id")
        operation_id = row.get("operation_id")
        if (
            not isinstance(graph_id, str)
            or graph_id in manifest_graph_ids
            or operation_id not in graph_ids_by_operation
        ):
            raise CausalLoopError(
                "causal graph manifest has an unbound identity"
            )
        manifest_graph_ids.add(graph_id)
        graph_ids_by_operation[operation_id].append(graph_id)
    if manifest_graph_ids != set(graphs):
        raise CausalLoopError(
            "causal graph manifest does not bind the loaded graph set"
        )
    node_ids_by_graph = {}
    edge_ids_by_graph = {}
    for graph_id, graph in graphs.items():
        if graph.get("graph_id") != graph_id:
            raise CausalLoopError("loaded causal graph identity is inconsistent")
        try:
            node_ids, edge_ids = causal_graph_stable_ids(graph)
        except CausalContractError as exc:
            raise CausalLoopError(str(exc)) from exc
        node_ids_by_graph[graph_id] = sorted(node_ids)
        edge_ids_by_graph[graph_id] = sorted(edge_ids)
    source_graph_ids = {}
    for row in source_projection.get("source_candidates", []):
        if (
            isinstance(row, Mapping)
            and isinstance(row.get("candidate_id"), str)
            and isinstance(row.get("graph_ids"), list)
        ):
            graph_ids = sorted(set(row["graph_ids"]))
            if not set(graph_ids) <= manifest_graph_ids:
                raise CausalLoopError(
                    "source candidate references an unbound causal graph"
                )
            source_graph_ids[row["candidate_id"]] = graph_ids
    domain["graph_ids_by_operation"] = {
        key: sorted(value) for key, value in graph_ids_by_operation.items()
    }
    domain["node_ids_by_graph"] = node_ids_by_graph
    domain["edge_ids_by_graph"] = edge_ids_by_graph
    domain["source_graph_ids"] = source_graph_ids


def _projection_view(projection: Mapping[str, Any]) -> Dict[str, Any]:
    rows = []
    for trace in projection.get("traces", []):
        failure_cycle = trace.get("failure_cycle")
        failure_row = next(
            (
                row
                for row in trace.get("cycles", [])
                if row.get("cycle") == failure_cycle
            ),
            None,
        )
        rows.append(
            {
                "operation_id": trace.get("operation_id"),
                "emitted_property_id": trace.get("emitted_property_id"),
                "source_property_id": trace.get("source_property_id"),
                "status": trace.get("status"),
                "failure_cycle": failure_cycle,
                "failure_time": trace.get("failure_time"),
                "environment": trace.get("environment"),
                "failure_sample": failure_row,
                "source_objects": trace.get("source_objects", []),
                "monitor_states": trace.get("monitor_states", []),
                "spec_clause": trace.get("spec_clause"),
                "causal_seed": trace.get("causal_seed"),
                "errors": trace.get("errors", []),
            }
        )
    return {
        "schema_version": projection.get("schema_version"),
        "round_id": projection.get("round_id"),
        "status": projection.get("status"),
        "identity": projection.get("identity"),
        "traces": rows,
        "errors": projection.get("errors", []),
    }


def _graph_overviews(
    graphs: Mapping[str, Mapping[str, Any]]
) -> Dict[str, Dict[str, Any]]:
    if not graphs:
        return {}
    rows = {}
    for graph_id, graph in sorted(graphs.items()):
        try:
            schema = causal_graph_schema(graph)
            causal_graph_stable_ids(graph)
        except CausalContractError as exc:
            rows[graph_id] = {
                "schema_version": "causal_overview_unavailable.v1",
                "graph_id": graph_id,
                "status": "unsupported",
                "error": str(exc),
            }
            continue
        if schema == CAUSAL_GRAPH_V2_SCHEMA:
            try:
                from verilog_causal_analysis import get_overview
            except Exception as exc:
                raise CausalLoopError(
                    "causal overview backend is unavailable"
                ) from exc
            rows[graph_id] = get_overview(
                graph, top_k=min(10, max(1, len(graph["nodes"])))
            )
        elif schema == CAUSAL_GRAPH_V3_SCHEMA:
            try:
                from verilog_causal_analysis import get_semantic_overview
            except Exception:
                rows[graph_id] = {
                    "schema_version": "causal_overview_unavailable.v1",
                    "graph_id": graph_id,
                    "status": "unsupported",
                    "error": "V3 causal overview support is unavailable",
                }
            else:
                rows[graph_id] = get_semantic_overview(graph, top_k=10)
    return rows


def _current_evidence_refs(
    base: set[str], visibility: Mapping[str, set[str]]
) -> list[str]:
    refs = set(base)
    refs.update(f"causal_graph:{row}" for row in visibility["graph_ids"])
    refs.update(f"causal_node:{row}" for row in visibility["node_ids"])
    refs.update(f"causal_edge:{row}" for row in visibility["edge_ids"])
    refs.update(
        f"source_candidate:{row}" for row in visibility["source_candidate_ids"]
    )
    return sorted(refs)


def _subset(
    value: Any,
    allowed: Sequence[Any],
    label: str,
    nonempty: bool,
) -> None:
    if (
        not isinstance(value, list)
        or (nonempty and not value)
        or len(value) != len(set(value))
        or not set(value) <= set(allowed)
    ):
        raise CausalLoopError(f"{label} contain duplicates or unknown identities")


def _attempt_row(
    sequence: int,
    tool_name: Optional[str],
    outcome: str,
    error: str,
    *,
    arguments: Any = None,
) -> Dict[str, Any]:
    row = {
        "schema_version": "diagnosis_candidate_attempt.v1",
        "model_call_sequence": sequence,
        "tool_name": tool_name,
        "outcome": outcome,
    }
    if arguments is not None:
        row["arguments_sha256"] = canonical_sha256(arguments)
    if error:
        row["error"] = error
    return row


def _budget_after(
    model_calls: int, query_calls: int, repairs: int
) -> Dict[str, int]:
    return {
        "model_calls_remaining": max(0, MAX_MODEL_CALLS - model_calls),
        "evidence_queries_remaining": max(
            0, MAX_EVIDENCE_QUERIES - query_calls
        ),
        "protocol_repairs_remaining": max(
            0, MAX_PROTOCOL_REPAIRS - repairs
        ),
    }


def _result_item_counts(value: Mapping[str, Any]) -> Dict[str, int]:
    return {
        key: len(value.get(key, []))
        for key in (
            "nodes",
            "edges",
            "paths",
            "results",
            "intervals",
            "events",
            "members",
            "occupancy",
            "source_projection",
            "source_candidates",
        )
        if isinstance(value.get(key), list)
    }


def _write_loop_audit(
    stage3: Path,
    *,
    model_rows: Sequence[Mapping[str, Any]],
    query_rows: Sequence[Mapping[str, Any]],
    attempt_rows: Sequence[Mapping[str, Any]],
    status: str,
    graph_manifest: Mapping[str, Any],
    source_projection: Mapping[str, Any],
    reason: Optional[str] = None,
) -> None:
    model_path = stage3 / "model_calls.jsonl"
    query_path = stage3 / "causal_query_log.jsonl"
    attempt_path = stage3 / "candidate_attempts.jsonl"
    _write_jsonl(model_path, model_rows)
    _write_jsonl(query_path, query_rows)
    _write_jsonl(attempt_path, attempt_rows)
    query_results = []
    query_dir = stage3 / "causal_queries"
    if query_dir.is_dir():
        query_results = [
            {
                "path": path.relative_to(stage3).as_posix(),
                "sha256": file_sha256(path),
            }
            for path in sorted(query_dir.glob("query_*.json"))
        ]
    manifest = {
        "schema_version": "diagnosis_transcript_manifest.v1",
        "status": status,
        "budget": {
            "max_model_calls": MAX_MODEL_CALLS,
            "max_evidence_queries": MAX_EVIDENCE_QUERIES,
            "max_protocol_repairs": MAX_PROTOCOL_REPAIRS,
            "parallel_tool_calls": False,
            "exactly_one_tool_per_call": True,
        },
        "artifacts": {
            "causal_graph_manifest_sha256": canonical_sha256(
                dict(graph_manifest)
            ),
            "causal_source_projection_sha256": canonical_sha256(
                dict(source_projection)
            ),
            "model_calls_sha256": file_sha256(model_path),
            "causal_query_log_sha256": file_sha256(query_path),
            "candidate_attempts_sha256": file_sha256(attempt_path),
        },
        "query_results": query_results,
        "counts": {
            "model_calls": len(model_rows),
            "evidence_queries": len(query_rows),
            "candidate_attempts": len(attempt_rows),
        },
        "metrics": {
            "model_wall_time_s": round(
                sum(float(row.get("wall_time_s", 0.0)) for row in model_rows), 6
            ),
            "prompt_tokens": _sum_usage(model_rows, "prompt_tokens"),
            "completion_tokens": _sum_usage(model_rows, "completion_tokens"),
            "total_tokens": _sum_usage(model_rows, "total_tokens"),
            "token_accounting_complete": all(
                row.get("token_usage", {}).get("status") == "available"
                for row in model_rows
            ),
        },
        "reason": reason,
    }
    _write_json(stage3 / "diagnosis_transcript_manifest.json", manifest)


def _contains_absolute_path(value: Any) -> bool:
    if isinstance(value, str):
        return value.startswith("/")
    if isinstance(value, Mapping):
        return any(_contains_absolute_path(row) for row in value.values())
    if isinstance(value, list):
        return any(_contains_absolute_path(row) for row in value)
    return False


def _model_usage_snapshot(model: Any) -> Optional[Dict[str, int]]:
    getter = getattr(model, "get_token_usage", None)
    if not callable(getter):
        return None
    try:
        usage = getter()
    except Exception:
        return None
    if not isinstance(usage, Mapping):
        return None
    return {
        "prompt_tokens": int(usage.get("llm_prompt_tokens", 0)),
        "completion_tokens": int(usage.get("llm_completion_tokens", 0)),
        "total_tokens": int(usage.get("llm_total_tokens", 0)),
    }


def _model_usage_delta(
    before: Optional[Mapping[str, int]],
    after: Optional[Mapping[str, int]],
) -> Dict[str, Any]:
    if before is None or after is None:
        return {"status": "unavailable"}
    return {
        "status": "available",
        **{
            key: max(0, int(after.get(key, 0)) - int(before.get(key, 0)))
            for key in ("prompt_tokens", "completion_tokens", "total_tokens")
        },
    }


def _sum_usage(rows: Sequence[Mapping[str, Any]], key: str) -> Optional[int]:
    values = [
        row.get("token_usage", {}).get(key)
        for row in rows
        if row.get("token_usage", {}).get("status") == "available"
    ]
    return sum(int(value) for value in values) if len(values) == len(rows) else None


def _contains_unsafe_free_text(value: str) -> bool:
    return (
        value.startswith("/")
        or "../" in value
        or "..\\" in value
        or "$(" in value
        or "`" in value
        or "\x00" in value
    )


def _safe_error(error: Exception) -> str:
    text = f"{type(error).__name__}: {error}"
    return " ".join(
        "<redacted-path>" if part.startswith("/") else part
        for part in text.split()
    )[:1000]


def _write_json(path: Path, value: Mapping[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + ".tmp")
    temporary.write_text(
        json.dumps(value, indent=2, sort_keys=True) + "\n", encoding="utf-8"
    )
    temporary.replace(path)


def _write_jsonl(path: Path, rows: Sequence[Mapping[str, Any]]) -> None:
    if path.exists():
        raise CausalLoopError(f"audit log already exists: {path.name}")
    path.write_text(
        "".join(json.dumps(dict(row), sort_keys=True) + "\n" for row in rows),
        encoding="utf-8",
    )
