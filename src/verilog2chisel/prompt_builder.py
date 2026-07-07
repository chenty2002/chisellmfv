"""Prompt construction for Verilog2Chisel v2."""

import json
from pathlib import Path
from typing import Any, Dict, Iterable, Mapping


ASSET_PATH = Path(__file__).resolve().parent / "context_assets" / "vis_conversion_rules.md"


def load_vis_rules() -> str:
    return ASSET_PATH.read_text(encoding="utf-8")


def build_v2c_conversion_prompt(
    input_summary: Dict[str, Any],
    verilog_text: str,
    rules_text: str,
) -> str:
    source_only_summary = {
        key: value
        for key, value in input_summary.items()
        if key not in {"excluded_files"}
    }
    return "\n".join(
        [
            "# Task: Convert VIS Verilog to Chisel",
            "",
            rules_text.rstrip(),
            "",
            "## Deterministic Source Summary",
            "",
            "```json",
            json.dumps(source_only_summary, indent=2, ensure_ascii=False),
            "```",
            "",
            "## Verilog Source",
            "",
            "```verilog",
            verilog_text.rstrip(),
            "```",
            "",
            "## Tool Output",
            "",
            "Use only the `write_files` tool.",
            "Return complete `.scala` files in package `llmverify`.",
            "Write at most three Scala files. Combine related modules into one file when the Verilog source has many modules.",
            "A valid compact layout is `package.scala`, `<Top>.scala`, and optionally `Submodules.scala`.",
            "Set `stage_complete=true` only when all generated source files are complete.",
        ]
    )


def build_v2c_repair_prompt(
    *,
    error_lines: Iterable[str],
    lint_errors: Iterable[str],
    scala_windows: Mapping[str, str],
) -> str:
    bounded_errors = list(error_lines)[:40]
    bounded_windows = {
        filename: "\n".join(window.splitlines()[:80])
        for filename, window in scala_windows.items()
    }
    return "\n".join(
        [
            "# Chisel Compile Repair",
            "",
            "Fix only the generated Chisel files listed below.",
            "Keep the VIS conversion rules unchanged.",
            "Use only the supplied Verilog source semantics and deterministic source summary.",
            "Do not infer benchmark-specific repairs, labels, README facts, or external bug knowledge.",
            "Return a complete replacement file through `write_files`.",
            "",
            "The previous compile failed with these bounded errors:",
            "",
            "```",
            "\n".join(bounded_errors),
            "```",
            "",
            "## Lint Summary",
            "",
            "```json",
            json.dumps(list(lint_errors), indent=2, ensure_ascii=False),
            "```",
            "",
            "## Relevant Scala Windows",
            "",
            "```json",
            json.dumps(bounded_windows, indent=2, ensure_ascii=False),
            "```",
            "",
            "The most common forbidden fixes are:",
            "- replacing `$ND` with constants;",
            "- adding LFSR/random generators;",
            "- changing source behavior to what seems more reasonable;",
            "- using `val a :: b :: Nil = Enum(...)`.",
            "- assigning to a UInt bit-select such as `x(0) := y`; use a Vec of Bool for per-bit writes.",
            "- leaving a Wire, Vec entry, or IO output element uninitialized; set safe defaults such as false.B or 0.U before conditional assignments.",
            "",
            "If firtool reports a sink is not fully initialized, initialize that exact Wire/IO output and any unused Vec indexes on every path.",
            "Use explicit UInt constants for enum-like Verilog values.",
        ]
    )
