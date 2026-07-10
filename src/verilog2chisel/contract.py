"""Translation contract artifact builders for Verilog2Chisel v2."""

from __future__ import annotations

import re
from pathlib import Path
from typing import Any, Dict, Iterable, List, Optional


def build_translation_contract(
    *,
    target: str,
    input_summary: Dict[str, Any],
    scala_files: Iterable[Path],
    input_hash: Optional[str],
    top_module: str,
) -> Dict[str, Any]:
    """Build the source-only contract consumed by downstream formal stages."""
    scala_text = "\n".join(
        path.read_text(encoding="utf-8", errors="replace") for path in scala_files
    )
    initial_assignments = _source_records(input_summary, "initial_assignments")
    nd_occurrences = _source_records(input_summary, "nd_occurrences")
    clocked_always = _source_records(input_summary, "clocked_always")
    excluded_inputs = [
        str(Path("verilog2chisel") / "verilog" / target / name)
        for name in input_summary.get("excluded_files", [])
    ]
    return {
        "schema_version": "v2c_translation_contract.v1",
        "target": target,
        "input_hash": input_hash,
        "top_module": top_module,
        "clock_mapping": _clock_mapping(clocked_always, scala_text),
        "initial_state": {
            "preserved": all(
                _initial_preserved(scala_text, assignment.get("lhs"))
                for assignment in initial_assignments
            )
            if initial_assignments
            else True,
            "assignments": [
                {
                    **assignment,
                    "preserved": _initial_preserved(scala_text, assignment.get("lhs")),
                }
                for assignment in initial_assignments
            ],
        },
        "nondet_sources": [
            {
                **occurrence,
                "chisel_signal": _find_chisel_nd_signal(scala_text, occurrence),
                "constraint": _find_nd_constraint(scala_text, occurrence),
                "driver_path": _find_nd_driver_path(scala_text, occurrence),
            }
            for occurrence in nd_occurrences
        ],
        "source_only": True,
        "excluded_inputs": excluded_inputs,
    }


def _clock_mapping(clocked_always: List[Dict[str, Any]], scala_text: str) -> List[Dict[str, Any]]:
    mapping = []
    for item in clocked_always:
        clock = str(item.get("clock") or "")
        if not clock:
            continue
        mapping.append(
            {
                **item,
                "chisel_clock": clock if f"withClock({clock}" in scala_text else "implicit_clock",
                "source_clock_io_preserved": bool(
                    re.search(rf"\b{re.escape(clock)}\b[^=\n]*=\s*IO\s*\(\s*Input\s*\(\s*Clock", scala_text)
                    or re.search(rf"\bval\s+{re.escape(clock)}\b\s*=\s*Input\s*\(\s*Clock", scala_text)
                ),
                "uses_with_clock": bool(re.search(rf"\bwithClock\s*\([^)]*{re.escape(clock)}", scala_text)),
            }
        )
    return mapping


def _initial_preserved(scala_text: str, lhs: Any) -> bool:
    signal = re.sub(r"\[[^\]]+\]", "", str(lhs or "")).split(".")[-1]
    if not signal:
        return True
    escaped = re.escape(signal)
    return bool(
        re.search(rf"\bval\s+{escaped}\b[^=\n]*=\s*RegInit\s*\(", scala_text)
        or re.search(rf"\breset\.asBool\b[\s\S]{{0,600}}\b{escaped}\s*:=", scala_text)
        or re.search(rf"\bwithReset\s*\([^)]+\)\s*\{{[\s\S]{{0,600}}\b{escaped}\b[^=\n]*=\s*RegInit\s*\(", scala_text)
    )


def _find_chisel_nd_signal(scala_text: str, occurrence: Dict[str, Any]) -> Optional[str]:
    assigned = occurrence.get("assigned_signal")
    candidates = []
    if assigned:
        candidates.append(str(assigned))
    candidates.extend(re.findall(r"\bnd[A-Za-z0-9_]*\b", scala_text))
    for candidate in candidates:
        if re.search(rf"\b{re.escape(candidate)}\b", scala_text):
            return candidate
    return None


def _find_nd_constraint(scala_text: str, occurrence: Dict[str, Any]) -> Optional[str]:
    signal = _find_chisel_nd_signal(scala_text, occurrence)
    if not signal:
        return None
    pattern = re.compile(
        rf"\b(?:assume|assert)\s*\((?P<body>[^)]*\b{re.escape(signal)}\b[^)]*)\)",
        re.DOTALL,
    )
    match = pattern.search(scala_text)
    return " ".join(match.group("body").split()) if match else None


def _find_nd_driver_path(scala_text: str, occurrence: Dict[str, Any]) -> Optional[str]:
    signal = _find_chisel_nd_signal(scala_text, occurrence)
    if not signal:
        return None
    match = re.search(
        rf"(?P<lhs>\b(?:[A-Za-z_][A-Za-z0-9_]*\.)*(?:io\.)?{re.escape(signal)}\b)\s*:=",
        scala_text,
    )
    return match.group("lhs") if match else signal


def _source_records(input_summary: Dict[str, Any], key: str) -> List[Dict[str, Any]]:
    records: List[Dict[str, Any]] = []
    for item in input_summary.get("files", []):
        value = item.get(key) if isinstance(item, dict) else None
        if isinstance(value, list):
            records.extend(record for record in value if isinstance(record, dict))
    return records
