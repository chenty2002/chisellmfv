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
