"""Deterministic local gates for Verilog2Chisel v2."""

import re
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Dict, Iterable, List


@dataclass
class LintResult:
    success: bool
    errors: List[str] = field(default_factory=list)
    counts: Dict[str, int] = field(default_factory=dict)


@dataclass
class GeneratedVerilogResult:
    success: bool
    generated_files: List[str] = field(default_factory=list)
    errors: List[str] = field(default_factory=list)


def lint_scala_sources(scala_files: Iterable[Path], input_summary: Dict[str, Any]) -> LintResult:
    texts = []
    errors: List[str] = []
    counts = {
        "forbidden_enum_destructuring": 0,
        "nd_constant_replacements": 0,
        "lfsr_random_usage": 0,
        "missing_verilog_generator": 0,
        "uint_bit_select_assignment": 0,
    }

    for path in scala_files:
        text = path.read_text(encoding="utf-8", errors="replace")
        texts.append(text)
        if re.search(r"::\s*Nil\s*=\s*Enum\s*\(", text):
            counts["forbidden_enum_destructuring"] += 1
        if re.search(r"\b(?:LFSR|scala\.util\.Random|random\.LFSR)\b", text):
            counts["lfsr_random_usage"] += 1
        uint_names = set(
            re.findall(
                r"\bval\s+([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(?:Wire|Reg|RegInit)\s*\(\s*UInt\b",
                text,
            )
        )
        for name in uint_names:
            if re.search(rf"\b{re.escape(name)}\s*\([0-9]+\)\s*:=", text):
                counts["uint_bit_select_assignment"] += 1
                break

    combined = "\n".join(texts)
    if counts["forbidden_enum_destructuring"]:
        errors.append("forbidden_enum_destructuring")
    if counts["lfsr_random_usage"]:
        errors.append("nd_modeled_as_lfsr")
    if "object VerilogGenerator extends App" not in combined:
        counts["missing_verilog_generator"] = 1
        errors.append("missing_verilog_generator")
    if counts["uint_bit_select_assignment"]:
        errors.append("uint_bit_select_assignment")

    nd_count = sum(item.get("nd_call_count", 0) for item in input_summary.get("files", []))
    if nd_count > 0:
        has_input = "Input(" in combined
        has_constraint = (
            "assume(" in combined
            or "assume (" in combined
            or "assert(" in combined
            or "assert (" in combined
        )
        if not has_input or not has_constraint:
            counts["nd_constant_replacements"] += 1
            errors.append("nd_not_modeled_as_formal_input")

    return LintResult(success=not errors, errors=errors, counts=counts)


def check_prompt_leak(text: str) -> LintResult:
    patterns = [
        r"label\.txt",
        r"has_error",
        r"preserve the 4-floor",
        r"McMillan/Schwalbe",
    ]
    hits = [pattern for pattern in patterns if re.search(pattern, text, flags=re.IGNORECASE)]
    errors = ["benchmark_specific_prompt_leak"] if hits else []
    return LintResult(
        success=not errors,
        errors=errors,
        counts={"benchmark_specific_prompt_leak": len(hits)},
    )


def check_generated_verilog(generated_dir: Path, top_module: str) -> GeneratedVerilogResult:
    files = sorted(
        path for suffix in ("*.v", "*.sv") for path in generated_dir.glob(suffix)
    )
    if not files:
        return GeneratedVerilogResult(success=False, errors=["missing_generated_verilog"])

    combined = "\n".join(path.read_text(encoding="utf-8", errors="replace") for path in files)
    errors: List[str] = []
    top_candidates = {top_module, top_module[:1].upper() + top_module[1:]}
    has_expected_top = any(
        re.search(rf"\bmodule\s+{re.escape(name)}\b", combined)
        for name in top_candidates
    )
    has_any_top = bool(re.search(r"\bmodule\s+[A-Za-z_][A-Za-z0-9_$]*\b", combined))
    if not has_expected_top and not has_any_top:
        errors.append("missing_top_module")
    non_comment_lines = [
        line for line in combined.splitlines() if line.strip() and not line.strip().startswith("//")
    ]
    if len(non_comment_lines) < 1:
        errors.append("empty_generated_verilog")
    return GeneratedVerilogResult(
        success=not errors,
        generated_files=[path.name for path in files],
        errors=errors,
    )


def evaluate_formal_readiness(
    *,
    input_summary: Dict[str, Any],
    scala_files: Iterable[Path],
    compile_success: bool,
    generated_verilog_success: bool,
    generated_verilog_text: str = "",
) -> Dict[str, Any]:
    """Return the hard formal-readiness gate result for a v2c output."""
    scala_texts = [
        path.read_text(encoding="utf-8", errors="replace")
        for path in scala_files
    ]
    combined_scala = "\n".join(scala_texts)
    blocking_issues: List[str] = []
    checks: Dict[str, Any] = {
        "compile_success": bool(compile_success),
        "generated_verilog_success": bool(generated_verilog_success),
        "formal_smoke_success": _formal_smoke_success(generated_verilog_text),
        "initial_state": [],
        "nondet_connectivity": [],
        "clock_mapping": [],
    }

    if not compile_success:
        blocking_issues.append("compile_failed")
    if not generated_verilog_success:
        blocking_issues.append("generated_verilog_failed")
    if checks["formal_smoke_success"] is False:
        blocking_issues.append("assumption_inconsistent")

    initial_failures = _initial_state_failures(input_summary, combined_scala)
    if initial_failures:
        blocking_issues.append("initial_not_preserved")
    checks["initial_state"] = initial_failures

    nd_failures = _nondet_connectivity_failures(input_summary, combined_scala)
    if nd_failures:
        blocking_issues.append("nd_driven_by_dontcare")
    checks["nondet_connectivity"] = nd_failures

    clock_failures = _clock_mapping_failures(input_summary, combined_scala)
    if clock_failures:
        blocking_issues.append("unused_clock_io")
    checks["clock_mapping"] = clock_failures

    ordered_issues = []
    for issue in blocking_issues:
        if issue not in ordered_issues:
            ordered_issues.append(issue)

    return {
        "schema_version": "v2c_formal_readiness.v1",
        "ready": not ordered_issues,
        "blocking_issues": ordered_issues,
        "compile_success": bool(compile_success),
        "generated_verilog_success": bool(generated_verilog_success),
        "formal_smoke_success": checks["formal_smoke_success"],
        "checks": checks,
    }


def _initial_state_failures(input_summary: Dict[str, Any], scala_text: str) -> List[Dict[str, Any]]:
    failures: List[Dict[str, Any]] = []
    for assignment in _source_records(input_summary, "initial_assignments"):
        lhs = str(assignment.get("lhs") or "")
        base = re.sub(r"\[[^\]]+\]", "", lhs).split(".")[-1]
        if not base:
            continue
        if _initial_preserved_for_signal(scala_text, base):
            continue
        item = dict(assignment)
        item["reason"] = "no_RegInit_or_reset_initialization_for_source_initial_assignment"
        failures.append(item)
    return failures


def _initial_preserved_for_signal(scala_text: str, signal: str) -> bool:
    escaped = re.escape(signal)
    patterns = [
        rf"\bval\s+{escaped}\b[^=\n]*=\s*RegInit\s*\(",
        rf"\b{escaped}\s*:=\s*RegInit\s*\(",
        rf"\breset\.asBool\b[\s\S]{{0,600}}\b{escaped}\s*:=",
        rf"\bwithReset\s*\([^)]+\)\s*\{{[\s\S]{{0,600}}\b{escaped}\b[^=\n]*=\s*RegInit\s*\(",
    ]
    return any(re.search(pattern, scala_text) for pattern in patterns)


def _nondet_connectivity_failures(input_summary: Dict[str, Any], scala_text: str) -> List[Dict[str, Any]]:
    if not _source_records(input_summary, "nd_occurrences"):
        return []
    failures: List[Dict[str, Any]] = []
    dontcare_pattern = re.compile(
        r"(?P<lhs>\b(?:[A-Za-z_][A-Za-z0-9_]*\.)*(?:io\.)?nd[A-Za-z0-9_.$]*)\s*:=\s*DontCare\b",
        re.IGNORECASE,
    )
    for match in dontcare_pattern.finditer(scala_text):
        failures.append(
            {
                "signal": match.group("lhs"),
                "reason": "nondeterministic_signal_driven_by_DontCare",
            }
        )
    if re.search(r"\bnd[A-Za-z0-9_]*\b\s*:=\s*(?:false|true)\.B\b|\bnd[A-Za-z0-9_]*\b\s*:=\s*\d+\.U\b", scala_text):
        failures.append({"reason": "nondeterministic_signal_driven_by_constant"})
    return failures


def _clock_mapping_failures(input_summary: Dict[str, Any], scala_text: str) -> List[Dict[str, Any]]:
    clocked = _source_records(input_summary, "clocked_always")
    if not clocked:
        return []
    failures: List[Dict[str, Any]] = []
    has_reg = bool(re.search(r"\bReg(?:Init)?\s*\(", scala_text))
    if not has_reg:
        return []
    for item in clocked:
        clock = str(item.get("clock") or "")
        if not clock:
            continue
        has_clock_io = bool(
            re.search(
                rf"\b(?:val\s+)?{re.escape(clock)}\b[^=\n]*=\s*IO\s*\(\s*Input\s*\(\s*Clock\s*\(\s*\)\s*\)\s*\)",
                scala_text,
            )
            or re.search(
                rf"\bval\s+{re.escape(clock)}\b\s*=\s*Input\s*\(\s*Clock\s*\(\s*\)\s*\)",
                scala_text,
            )
        )
        uses_with_clock = bool(re.search(rf"\bwithClock\s*\([^)]*{re.escape(clock)}", scala_text))
        if has_clock_io and not uses_with_clock:
            failure = dict(item)
            failure["reason"] = "source_clock_preserved_as_io_but_registers_use_implicit_clock"
            failures.append(failure)
    return failures


def _formal_smoke_success(generated_verilog_text: str) -> bool:
    if not generated_verilog_text:
        return True
    inconsistent_patterns = [
        r"\bassume\s*(?:property)?\s*\(\s*1'?h?0\s*\)",
        r"\bassume\s*(?:property)?\s*\(\s*1'?b0\s*\)",
        r":noConflict\s+cex",
        r"\bassumption\b.*\b(conflict|inconsistent)\b",
        r"\breset\b.*\bconflict\b",
    ]
    return not any(
        re.search(pattern, generated_verilog_text, flags=re.IGNORECASE)
        for pattern in inconsistent_patterns
    )


def _source_records(input_summary: Dict[str, Any], key: str) -> List[Dict[str, Any]]:
    records: List[Dict[str, Any]] = []
    for item in input_summary.get("files", []):
        value = item.get(key) if isinstance(item, dict) else None
        if isinstance(value, list):
            records.extend(record for record in value if isinstance(record, dict))
    return records
