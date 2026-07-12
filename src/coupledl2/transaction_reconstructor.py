"""Reconstruct TileLink transactions, selected state, and bounded wait chains."""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any, Dict, Iterable

from .trace_decoder import decode_tilelink_cycles, sample_vcd


ACQUIRE_OPCODES = {6, 7}
PROBE_OPCODES = {6}
PROBE_ACK_OPCODES = {4, 5}
RELEASE_OPCODES = {6, 7}
GRANT_OPCODES = {4, 5}
RELEASE_ACK_OPCODE = 6


def reconstruct_transactions(trace: Dict[str, Any]) -> Dict[str, Any]:
    pending_a: Dict[Any, list[str]] = {}
    pending_probe: Dict[Any, list[str]] = {}
    pending_release: Dict[Any, list[str]] = {}
    pending_grant_ack: Dict[Any, list[str]] = {}
    events = []
    counter = 0
    for raw in trace.get("events", []):
        event = dict(raw)
        channel = event.get("channel")
        opcode = event.get("opcode")
        source = event.get("source")
        sink = event.get("sink")
        kind = "unmatched"
        if channel == "A":
            txid = f"tx{counter}"
            counter += 1
            pending_a.setdefault(source, []).append(txid)
            kind = "acquire" if opcode in ACQUIRE_OPCODES else "a_request"
        elif channel == "B" and opcode in PROBE_OPCODES:
            txid = f"tx{counter}"
            counter += 1
            pending_probe.setdefault(source, []).append(txid)
            kind = "probe"
        elif channel == "C" and opcode in PROBE_ACK_OPCODES and pending_probe.get(source):
            txid = pending_probe[source].pop(0)
            kind = "probe_ack"
        elif channel == "C" and opcode in RELEASE_OPCODES:
            txid = f"tx{counter}"
            counter += 1
            pending_release.setdefault(source, []).append(txid)
            kind = "release"
        elif channel == "D" and opcode == RELEASE_ACK_OPCODE and pending_release.get(source):
            txid = pending_release[source].pop(0)
            kind = "release_ack"
        elif channel == "D" and pending_a.get(source):
            txid = pending_a[source].pop(0)
            kind = "grant" if opcode in GRANT_OPCODES else "d_response"
            if opcode in GRANT_OPCODES and sink is not None:
                pending_grant_ack.setdefault(sink, []).append(txid)
        elif channel == "E" and pending_grant_ack.get(sink):
            txid = pending_grant_ack[sink].pop(0)
            kind = "grant_ack"
        else:
            txid = f"unmatched{counter}"
            counter += 1
        event["matched_transaction_id"] = txid
        event["transaction_kind"] = kind
        events.append(event)
    unmatched = {
        "a": sum(len(items) for items in pending_a.values()),
        "probe": sum(len(items) for items in pending_probe.values()),
        "release": sum(len(items) for items in pending_release.values()),
        "grant_ack": sum(len(items) for items in pending_grant_ack.values()),
    }
    return {
        "schema_version": "transaction_trace.v1",
        "events": events,
        "unmatched_pending": unmatched,
    }


def select_state_trace(cycles: Iterable[Dict[str, Any]], required_observations: Iterable[str]) -> Dict[str, Any]:
    allowed = tuple(required_observations)
    return {"schema_version": "state_trace.v1", "cycles": [{"cycle": item.get("cycle", index), "observations": {key: item[key] for key in allowed if key in item}} for index, item in enumerate(cycles)]}


def build_wait_chain(edges: Iterable[Dict[str, Any]]) -> Dict[str, Any]:
    normalized = [{"cycle": item.get("cycle"), "waiter": item["waiter"], "resource": item["resource"], "owner": item.get("owner")} for item in edges]
    return {"schema_version": "wait_chain.v1", "edges": normalized}


def diagnosis_evidence(property_result: Dict[str, Any], *, binding_ref: str, source_ref: str, transaction_ref: str, state_ref: str | None = None, wait_ref: str | None = None) -> Dict[str, Any]:
    refs = {"binding": binding_ref, "source": source_ref, "transaction_trace": transaction_ref}
    if state_ref:
        refs["state_trace"] = state_ref
    if wait_ref:
        refs["wait_chain"] = wait_ref
    return {"schema_version": "diagnosis_evidence.v1", "property": property_result["rtl_label"], "trace_path": property_result.get("trace_path"), "refs": refs}


def materialize_diagnosis_artifacts(input_path: Path, output_dir: Path) -> Dict[str, str]:
    """Materialize deterministic evidence from an explicit decode contract.

    The input must supply exact waveform signal names and sampled values.  The
    function deliberately does not infer names from Chisel or RTL identifiers.
    """
    payload = json.loads(Path(input_path).read_text(encoding="utf-8"))
    if payload.get("schema_version") != "trace_decode_input.v1":
        raise ValueError("unsupported trace decode input")
    cycles = payload.get("cycles")
    signal_map = payload.get("signal_map")
    if not isinstance(signal_map, dict):
        raise ValueError("trace decode input requires an exact signal map")
    if cycles is None:
        waveform_path = Path(payload.get("waveform_path", ""))
        if not waveform_path.is_absolute():
            waveform_path = Path(input_path).parent / waveform_path
        cycles = sample_vcd(
            waveform_path,
            signal_map=signal_map,
            clock_signal=payload.get("clock_signal"),
        )
    if not isinstance(cycles, list):
        raise ValueError("trace decode input cycles must be a list")
    output_dir = Path(output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)
    transaction = reconstruct_transactions(decode_tilelink_cycles(cycles, signal_map=signal_map))
    state = select_state_trace(cycles, payload.get("required_observations", []))
    wait = build_wait_chain(payload.get("wait_edges", []))
    artifacts = {
        "transaction_trace": output_dir / "transaction_trace.json",
        "state_trace": output_dir / "state_trace.json",
        "wait_chain": output_dir / "wait_chain.json",
    }
    for key, path in artifacts.items():
        value = {"transaction_trace": transaction, "state_trace": state, "wait_chain": wait}[key]
        path.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    evidence_records = [
        diagnosis_evidence(
            item,
            binding_ref=str(payload.get("binding_ref", "../02_bind_properties/binding_manifest.json")),
            source_ref=str(payload.get("source_ref", "../02_bind_properties/property_package.json#traceability")),
            transaction_ref="transaction_trace.json",
            state_ref="state_trace.json",
            wait_ref="wait_chain.json",
        )
        for item in payload.get("properties", [])
    ]
    evidence_path = output_dir / "diagnosis_evidence.json"
    evidence_path.write_text(
        json.dumps(
            {
                "schema_version": "diagnosis_evidence_set.v1",
                "reconstruction_status": payload.get(
                    "reconstruction_status", "complete"
                ),
                "uncertainty": payload.get("uncertainty"),
                "properties": evidence_records,
            },
            indent=2,
            sort_keys=True,
        ) + "\n",
        encoding="utf-8",
    )
    artifacts["diagnosis_evidence"] = evidence_path
    return {key: str(path) for key, path in artifacts.items()}
