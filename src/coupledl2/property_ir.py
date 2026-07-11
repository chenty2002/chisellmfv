"""Semantic validator for the V3 protocol-obligation intermediate representation."""

from __future__ import annotations

from typing import Any, Dict, Iterable


CHANNEL_DIRECTIONS = {
    "A": "client_to_manager",
    "B": "manager_to_client",
    "C": "client_to_manager",
    "D": "manager_to_client",
    "E": "client_to_manager",
}
CHANNEL_MESSAGES = {
    "A": {"PutFullData", "PutPartialData", "ArithmeticData", "LogicalData", "Get", "Hint", "AcquireBlock", "AcquirePerm"},
    "B": {"Probe"},
    "C": {"AccessAck", "AccessAckData", "HintAck", "ProbeAck", "ProbeAckData", "Release", "ReleaseData"},
    "D": {"AccessAck", "AccessAckData", "HintAck", "Grant", "GrantData", "ReleaseAck"},
    "E": {"GrantAck"},
}
OBLIGATION_KINDS = {
    "safety", "response", "serialization", "permission", "data",
    "bounded_liveness",
}
LOWERING_FAMILIES = {
    "stability", "response_scoreboard", "serialization", "multibeat",
    "permission_state", "data_relation", "bounded_liveness",
}


class PropertyIRError(ValueError):
    """Raised when an obligation is structurally present but semantically invalid."""


def validate_property_schema_v3(value: Dict[str, Any]) -> None:
    fields = {
        "schema_version", "property_schema_id", "category", "layer", "title",
        "source", "rule_id", "obligation_kind", "channel_scope",
        "event_automaton", "correlation", "cardinality", "permission_relation",
        "optional_paths", "environment_boundary", "observable_contract",
        "oracle_plan", "transfer_policy", "lowering_family", "template_ids",
        "review_required",
    }
    _exact(value, fields, "property_schema")
    if value["schema_version"] != "property_schema.v3":
        raise PropertyIRError("unsupported property schema version")
    if value["obligation_kind"] not in OBLIGATION_KINDS:
        raise PropertyIRError("invalid obligation kind")
    if value["lowering_family"] not in LOWERING_FAMILIES:
        raise PropertyIRError("unsupported lowering family")
    _validate_source(value)
    _validate_channel_scope(value["channel_scope"])
    events = _validate_automaton(value["event_automaton"])
    _validate_correlation(value["correlation"], events)
    _validate_cardinality(value["cardinality"])
    _validate_permission(value)
    _validate_optional_paths(value["optional_paths"], events)
    _validate_environment(value)
    _validate_observables(value["observable_contract"])
    _validate_oracle_plan(value["oracle_plan"], value["event_automaton"], events)
    observation_ids = {
        item["id"] for item in value["observable_contract"]["observations"]
    }
    if not set(value["oracle_plan"]["non_vacuity"]["observer_requirements"]) <= observation_ids:
        raise PropertyIRError("non-vacuity references an unknown observer")
    _validate_transfer(value["transfer_policy"])
    if not isinstance(value["template_ids"], list) or not value["template_ids"]:
        raise PropertyIRError("property schema requires a lowering template")
    if value["obligation_kind"] == "bounded_liveness":
        boundary = value["environment_boundary"]
        bound = boundary["proof_bound"]
        if bound["kind"] != "cycles" or bound["maximum"] <= 0:
            raise PropertyIRError("bounded liveness requires a positive cycle bound")
        if not boundary["fairness"]:
            raise PropertyIRError("bounded liveness must state fairness explicitly")
    family = value["lowering_family"]
    correlation = value["correlation"]
    if family in {"response_scoreboard", "serialization", "data_relation"} and not correlation["keys"]:
        raise PropertyIRError("stateful lowering requires an explicit correlation lifetime")
    if family == "multibeat":
        event_ids = set(events)
        if not {"first_beat", "last_beat"} <= event_ids or correlation["consumed_by"] != "last_beat":
            raise PropertyIRError("multibeat lowering requires first/final beat completion")


def structural_completeness(value: Dict[str, Any]) -> bool:
    """Structural completeness counts explicit empty sets as present."""
    try:
        validate_property_schema_v3(value)
    except (PropertyIRError, TypeError, KeyError):
        return False
    return True


def semantic_review_completeness(value: Dict[str, Any], review_status: str) -> bool:
    """Semantic completeness is distinct from structural field presence."""
    return structural_completeness(value) and review_status == "approved"


def observation_slots(value: Dict[str, Any]) -> Dict[str, str]:
    return {
        item["role"]: item["type"]
        for item in value["observable_contract"]["observations"]
    }


def _validate_source(value: Dict[str, Any]) -> None:
    source = value["source"]
    _exact(source, {"kind", "document", "locator", "statement"}, "property_schema.source")
    kinds = {"protocol_requirement", "implementation_requirement", "historical_counterexample"}
    if source["kind"] not in kinds:
        raise PropertyIRError("invalid property source kind")
    if (source["kind"] == "protocol_requirement") != isinstance(value["rule_id"], str):
        raise PropertyIRError("only protocol obligations carry rule_id")


def _validate_channel_scope(value: Dict[str, Any]) -> None:
    _exact(value, {"channels", "message_classes", "scope"}, "channel_scope")
    if not isinstance(value["channels"], list) or any(ch not in CHANNEL_DIRECTIONS for ch in value["channels"]):
        raise PropertyIRError("invalid channel scope")


def _validate_automaton(value: Dict[str, Any]) -> Dict[str, Dict[str, Any]]:
    _exact(value, {"initial_state", "states", "events", "transitions", "terminal_states"}, "event_automaton")
    states = value["states"]
    if not isinstance(states, list) or not states or len(states) != len(set(states)):
        raise PropertyIRError("automaton states must be unique")
    if value["initial_state"] not in states or any(state not in states for state in value["terminal_states"]):
        raise PropertyIRError("automaton initial or terminal state is unknown")
    events: Dict[str, Dict[str, Any]] = {}
    for item in value["events"]:
        _exact(item, {"event_id", "kind", "channel", "direction", "message_classes"}, "event_automaton.events[]")
        event_id = item["event_id"]
        if event_id in events:
            raise PropertyIRError("automaton event ids must be unique")
        channel = item["channel"]
        if channel is not None:
            if channel not in CHANNEL_DIRECTIONS or item["direction"] != CHANNEL_DIRECTIONS[channel]:
                raise PropertyIRError("event channel polarity is invalid")
            illegal = set(item["message_classes"]) - CHANNEL_MESSAGES[channel]
            if illegal:
                raise PropertyIRError(f"message class is illegal on channel {channel}: {sorted(illegal)}")
        elif item["direction"] != "internal":
            raise PropertyIRError("channel-less events must be internal")
        events[event_id] = item
    if not events:
        raise PropertyIRError("automaton requires events")
    for item in value["transitions"]:
        _exact(item, {"from", "event", "to", "condition"}, "event_automaton.transitions[]")
        if item["from"] not in states or item["to"] not in states or item["event"] not in events:
            raise PropertyIRError("automaton transition references an unknown node")
    return events


def _validate_correlation(value: Dict[str, Any], events: Dict[str, Dict[str, Any]]) -> None:
    _exact(value, {"keys", "created_by", "consumed_by", "lifetime", "reuse_policy"}, "correlation")
    if not isinstance(value["keys"], list):
        raise PropertyIRError("correlation keys must be a list")
    for name in ("created_by", "consumed_by"):
        if value[name] is not None and value[name] not in events:
            raise PropertyIRError("correlation lifetime references unknown event")
    if value["keys"] and (value["created_by"] is None or value["consumed_by"] is None):
        raise PropertyIRError("correlation keys require a complete lifetime")


def _validate_cardinality(value: Dict[str, Any]) -> None:
    _exact(value, {"subject", "minimum", "maximum", "scope"}, "cardinality")
    if value["minimum"] < 0 or (value["maximum"] is not None and value["maximum"] < value["minimum"]):
        raise PropertyIRError("invalid cardinality bound")


def _validate_permission(value: Dict[str, Any]) -> None:
    relation = value["permission_relation"]
    if value["obligation_kind"] == "permission":
        if not isinstance(relation, dict):
            raise PropertyIRError("permission obligation requires a permission relation")
        _exact(relation, {"states", "transitions", "invariants"}, "permission_relation")
        states = set(relation["states"])
        if not {"Nothing", "Branch", "Trunk", "Tip"} <= states:
            raise PropertyIRError("permission relation omits TileLink permission states")
        for transition in relation["transitions"]:
            _exact(transition, {"from", "to", "event"}, "permission_relation.transitions[]")
            if transition["from"] not in states or transition["to"] not in states:
                raise PropertyIRError("permission transition references unknown state")
    elif relation is not None:
        raise PropertyIRError("non-permission obligation must use null permission relation")


def _validate_optional_paths(paths: Any, events: Dict[str, Dict[str, Any]]) -> None:
    if not isinstance(paths, list):
        raise PropertyIRError("optional paths must be a list")
    for item in paths:
        _exact(item, {"path_id", "description", "events", "required"}, "optional_paths[]")
        if item["required"] is not False or any(event not in events for event in item["events"]):
            raise PropertyIRError("optional path is malformed or promoted to a requirement")


def _validate_environment(value: Dict[str, Any]) -> None:
    boundary = value["environment_boundary"]
    _exact(boundary, {"protocol_premises", "fairness", "harness_restrictions", "proof_simplifications", "proof_bound"}, "environment_boundary")
    for name in ("protocol_premises", "fairness", "harness_restrictions", "proof_simplifications"):
        if not isinstance(boundary[name], list):
            raise PropertyIRError("environment boundary entries must be explicit lists")
    bound = boundary["proof_bound"]
    _exact(bound, {"kind", "minimum", "maximum"}, "environment_boundary.proof_bound")
    if bound["kind"] not in {"none", "cycles"}:
        raise PropertyIRError("invalid proof bound kind")


def _validate_observables(value: Dict[str, Any]) -> None:
    _exact(value, {"observations", "ghost_state", "clock_domain", "reset"}, "observable_contract")
    if not value["observations"]:
        raise PropertyIRError("obligation requires an observable")
    roles = set()
    for item in value["observations"]:
        _exact(item, {"id", "role", "type", "description", "reachability"}, "observable_contract.observations[]")
        if item["role"] in roles or item["reachability"] not in {"trigger", "toggle", "stable", "not_applicable"}:
            raise PropertyIRError("observation roles or reachability requirements are invalid")
        roles.add(item["role"])
    if not isinstance(value["ghost_state"], list):
        raise PropertyIRError("ghost state must be an explicit list")


def _validate_oracle_plan(
    value: Dict[str, Any], automaton: Dict[str, Any], events: Dict[str, Dict[str, Any]]
) -> None:
    _exact(value, {"positive_traces", "negative_traces", "mutation_classes", "non_vacuity"}, "oracle_plan")
    if not value["positive_traces"] or not value["negative_traces"] or not value["mutation_classes"]:
        raise PropertyIRError("oracle plan requires positive and negative witnesses")
    for expected, traces in (("satisfy", value["positive_traces"]), ("violate", value["negative_traces"])):
        for trace in traces:
            _exact(trace, {"trace_id", "steps", "expected"}, "oracle_plan.trace[]")
            if trace["expected"] != expected or not trace["steps"] or any(step not in events for step in trace["steps"]):
                raise PropertyIRError("oracle trace skeleton is invalid")
            state = automaton["initial_state"]
            for event_id in trace["steps"]:
                matches = [
                    transition for transition in automaton["transitions"]
                    if transition["from"] == state and transition["event"] == event_id
                ]
                if len(matches) != 1:
                    raise PropertyIRError("oracle trace is not an executable automaton path")
                state = matches[0]["to"]
            if expected == "violate" and state != "violation":
                raise PropertyIRError("negative oracle trace does not reach violation")
            if expected == "satisfy" and state == "violation":
                raise PropertyIRError("positive oracle trace reaches violation")
    non_vacuity = value["non_vacuity"]
    _exact(non_vacuity, {"required", "trigger_event_ids", "observer_requirements", "assumption_satisfiable"}, "oracle_plan.non_vacuity")
    if non_vacuity["required"] is not True or not non_vacuity["trigger_event_ids"]:
        raise PropertyIRError("minimum non-vacuity is mandatory")
    if any(event not in events for event in non_vacuity["trigger_event_ids"]):
        raise PropertyIRError("non-vacuity trigger references unknown event")


def _validate_transfer(value: Dict[str, Any]) -> None:
    _exact(value, {"stable_anchors", "implementation_anchors", "fail_closed_conditions"}, "transfer_policy")
    if not value["stable_anchors"] or not value["fail_closed_conditions"]:
        raise PropertyIRError("transfer policy must define stable anchors and failures")


def _exact(value: Any, fields: Iterable[str], path: str) -> None:
    if not isinstance(value, dict):
        raise PropertyIRError(f"{path} must be an object")
    unknown = set(value) - set(fields)
    missing = set(fields) - set(value)
    if unknown:
        raise PropertyIRError(f"{path} unknown fields: {sorted(unknown)}")
    if missing:
        raise PropertyIRError(f"{path} missing fields: {sorted(missing)}")
