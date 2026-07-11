"""Deterministically decode bounded TileLink observations into cycle events."""

from __future__ import annotations

from pathlib import Path
from typing import Any, Dict, Iterable


CHANNELS = ("a", "b", "c", "d", "e")


def decode_tilelink_cycles(cycles: Iterable[Dict[str, Any]], *, signal_map: Dict[str, str]) -> Dict[str, Any]:
    events = []
    for cycle_index, sample in enumerate(cycles):
        cycle = int(sample.get("cycle", cycle_index))
        for channel in CHANNELS:
            valid = _value(sample, signal_map.get(f"{channel}.valid"))
            ready = _value(sample, signal_map.get(f"{channel}.ready"))
            if not (valid == 1 and ready == 1):
                continue
            event = {"cycle": cycle, "channel": channel.upper(), "fire": True}
            for field in ("opcode", "param", "source", "sink", "address", "beat"):
                value = _value(sample, signal_map.get(f"{channel}.{field}"))
                if value is not None:
                    event[field] = value
            events.append(event)
    return {"schema_version": "transaction_trace.v1", "events": events}


def sample_vcd(
    waveform_path: Path,
    *,
    signal_map: Dict[str, str],
    clock_signal: str | None = None,
) -> list[Dict[str, Any]]:
    """Sample exact VCD signals, optionally on rising clock edges."""
    lines = Path(waveform_path).read_text(encoding="utf-8", errors="replace").splitlines()
    scopes: list[str] = []
    id_to_name: Dict[str, str] = {}
    for line in lines:
        stripped = line.strip()
        if stripped.startswith("$scope "):
            parts = stripped.split()
            scopes.append(parts[2])
        elif stripped.startswith("$upscope"):
            if scopes:
                scopes.pop()
        elif stripped.startswith("$var "):
            parts = stripped.split()
            if len(parts) >= 6:
                identifier = parts[3]
                reference = parts[4]
                id_to_name[identifier] = ".".join((*scopes, reference))
        elif stripped.startswith("$enddefinitions"):
            break
    requested = set(signal_map.values())
    if clock_signal:
        requested.add(clock_signal)
    name_to_id = {name: identifier for identifier, name in id_to_name.items() if name in requested}
    missing = sorted(requested - set(name_to_id))
    if missing:
        raise ValueError(f"exact VCD signals not found: {missing}")

    values: Dict[str, Any] = {}
    samples: list[Dict[str, Any]] = []
    current_time = 0
    previous_clock: Any = 0 if clock_signal else None
    in_values = False

    def flush() -> None:
        nonlocal previous_clock
        if not in_values or not values:
            return
        clock = values.get(clock_signal) if clock_signal else None
        take = clock_signal is None or (previous_clock == 0 and clock == 1)
        if take:
            sample = {"cycle": len(samples), "time": current_time}
            for semantic, exact_name in signal_map.items():
                if exact_name in values:
                    sample[exact_name] = values[exact_name]
            samples.append(sample)
        previous_clock = clock

    for line in lines:
        stripped = line.strip()
        if stripped.startswith("$enddefinitions"):
            in_values = True
            continue
        if not in_values or not stripped or stripped.startswith("$"):
            continue
        if stripped.startswith("#"):
            flush()
            current_time = int(stripped[1:])
            continue
        if stripped[0] in "01xXzZ":
            raw, identifier = stripped[0], stripped[1:]
        elif stripped[0] in "bBrR":
            fields = stripped.split()
            if len(fields) != 2:
                continue
            raw, identifier = fields
            raw = raw[1:]
        else:
            continue
        name = id_to_name.get(identifier)
        if name in requested:
            values[name] = _parse_vcd_value(raw)
    flush()
    return samples


def _parse_vcd_value(raw: str) -> Any:
    if not raw or any(char.lower() in {"x", "z"} for char in raw):
        return raw.lower()
    try:
        return int(raw, 2) if len(raw) > 1 else int(raw)
    except ValueError:
        return raw


def _value(sample: Dict[str, Any], signal: str | None) -> Any:
    if signal is None or signal not in sample:
        return None
    value = sample[signal]
    if isinstance(value, str):
        try:
            return int(value, 0)
        except ValueError:
            return value
    return value
