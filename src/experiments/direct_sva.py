"""Isolated Direct-SVA baseline for the SpecFlow paper experiment.

This module intentionally has no dependency on SpecFlow typed IR, repository
property assets, or the monitor compiler.  The model submits raw SVA and the
runner places it unchanged in a small run-local wrapper.
"""

from __future__ import annotations

import json
import re
from pathlib import Path
from typing import Any, Mapping, Sequence

from src.chiselspecflow.backend import JasperGoldBackend
from src.core.artifact_contract import file_sha256


DIRECT_SVA_TOOL = "submit_direct_sva"
_ID_RE = re.compile(r"[A-Za-z_][A-Za-z0-9_]*")


def direct_sva_tool() -> dict[str, Any]:
    item = {
        "type": "object",
        "properties": {
            "property_id": {"type": "string", "minLength": 1},
            "sva": {"type": "string", "minLength": 1},
        },
        "required": ["property_id", "sva"],
        "additionalProperties": False,
    }
    return {
        "name": DIRECT_SVA_TOOL,
        "description": "Submit raw SVA properties without typed SpecFlow artifacts.",
        "strict": True,
        "parameters": {
            "type": "object",
            "properties": {
                "properties": {"type": "array", "minItems": 1, "items": item}
            },
            "required": ["properties"],
            "additionalProperties": False,
        },
    }


def generate_direct_sva(
    model: Any,
    context: Mapping[str, Any],
    output_dir: Path,
    *,
    max_tokens: int,
) -> list[dict[str, str]]:
    """Make exactly one direct-generation call and retain its submitted SVA."""

    output_dir = Path(output_dir)
    output_dir.mkdir(parents=True, exist_ok=False)
    prompt = (Path(__file__).with_name("assets") / "direct_sva_prompt.md").read_text(
        encoding="utf-8"
    )
    response = model.chat_with_tools(
        messages=[
            {"role": "system", "content": prompt},
            {"role": "user", "content": json.dumps(context, sort_keys=True)},
        ],
        tools=[direct_sva_tool()],
        max_tokens=max_tokens,
        temperature=0.0,
        tool_choice={"type": "function", "function": {"name": DIRECT_SVA_TOOL}},
        enable_thinking=False,
        parallel_tool_calls=False,
        usage_metadata={"stage": "direct_sva", "task_type": "property_authoring"},
    )
    _write_json(
        output_dir / "model_response.json",
        {"response": response, "usage": model.get_token_usage()},
    )
    calls = response.get("function_calls") if response.get("type") == "function_calls" else None
    if not isinstance(calls, list) or len(calls) != 1:
        raise ValueError("Direct SVA requires exactly one tool call")
    call = calls[0]
    arguments = call.get("arguments")
    if call.get("name") != DIRECT_SVA_TOOL or not isinstance(arguments, Mapping):
        raise ValueError("Direct SVA used the wrong tool or arguments")
    if set(arguments) != {"properties"} or not isinstance(arguments["properties"], list):
        raise ValueError("Direct SVA submission fields differ")
    rows: list[dict[str, str]] = []
    seen = set()
    for row in arguments["properties"]:
        if not isinstance(row, Mapping) or set(row) != {"property_id", "sva"}:
            raise ValueError("Direct SVA property fields differ")
        property_id = row["property_id"]
        sva = row["sva"]
        if (
            not isinstance(property_id, str)
            or _ID_RE.fullmatch(property_id) is None
            or property_id in seen
            or not isinstance(sva, str)
            or not sva.strip()
        ):
            raise ValueError("Direct SVA property ID or text is invalid")
        if re.search(r"\b(endmodule|module|bind)\b", sva, re.IGNORECASE):
            raise ValueError("Direct SVA must contain wrapper-body declarations only")
        assert_label = property_id + "__assert"
        activation_label = property_id + "__activation"
        if not re.search(rf"\b{re.escape(assert_label)}\s*:\s*assert\s+property\b", sva):
            raise ValueError(f"Direct SVA is missing exact assertion label {assert_label}")
        if not re.search(rf"\b{re.escape(activation_label)}\s*:\s*cover\s+property\b", sva):
            raise ValueError(f"Direct SVA is missing exact activation label {activation_label}")
        seen.add(property_id)
        rows.append({"property_id": property_id, "sva": sva})
    if not rows:
        raise ValueError("Direct SVA submission is empty")
    _write_json(output_dir / "direct_sva_submission.json", {"properties": rows})
    return rows


def render_direct_harness(
    *,
    top: str,
    formal: Mapping[str, Any],
    baseline: Mapping[str, Any],
    semantic_index: Mapping[str, Any],
    properties: Sequence[Mapping[str, str]],
) -> str:
    """Wrap raw SVA around one DUT instance without interpreting the SVA."""

    ports = []
    seen = set()
    elaborated_ports = {
        row["name"]
        for row in baseline.get("objects", [])
        if row.get("owner_module") == top
        and row.get("direction") in {"input", "output", "inout"}
    }
    for row in semantic_index.get("objects", []):
        if (
            row.get("owner_module") != top
            or row.get("direction") not in {"input", "output", "inout"}
            or row.get("name") not in elaborated_ports
            or row.get("name") in seen
        ):
            continue
        name = str(row["name"])
        if _ID_RE.fullmatch(name) is None:
            raise ValueError(f"unsupported Direct SVA port name: {name}")
        seen.add(name)
        width = int(row["chisel_type"]["width"])
        signed = " signed" if row["chisel_type"].get("signed") else ""
        packed = "" if width == 1 else f" [{width - 1}:0]"
        ports.append((name, f"  logic{signed}{packed} {name};"))
    for name in (formal["clock"], formal["reset"]):
        if name not in seen:
            ports.append((name, f"  logic {name};"))
            seen.add(name)
    connections = ", ".join(f".{name}({name})" for name, _line in ports)
    lines = ["module SpecFlowDirectHarness;", *[line for _name, line in ports]]
    lines.append(f"  {top} dut ({connections});")
    for row in properties:
        lines.append("")
        lines.extend("  " + line for line in row["sva"].splitlines())
    lines.extend(["endmodule", ""])
    return "\n".join(lines)


def run_direct_sva_formal(
    workspace: Any,
    properties: Sequence[Mapping[str, str]],
    output_dir: Path,
    *,
    timeout_seconds: int,
    per_property_seconds: int,
) -> dict[str, Any]:
    """Elaborate the already-prepared baseline plus raw SVA in JasperGold."""

    output_dir = Path(output_dir).resolve()
    output_dir.mkdir(parents=True, exist_ok=False)
    baseline = _read_json(workspace.indexes_dir / "baseline_elaboration.json")
    semantic = _read_json(workspace.indexes_dir / "chisel_semantic_index.json")
    project = _read_json(workspace.inputs_dir / "project_contract.json")
    harness = output_dir / "direct_sva_harness.sv"
    harness.write_text(
        render_direct_harness(
            top=baseline["top"],
            formal=project["formal"],
            baseline=baseline,
            semantic_index=semantic,
            properties=properties,
        ),
        encoding="utf-8",
    )
    generated_files = []
    for row in baseline["generated_files"]:
        path = workspace.project_workspace / row["path"]
        generated_files.append({"path": str(path.resolve()), "sha256": file_sha256(path)})
    generated_files.append({"path": str(harness), "sha256": file_sha256(harness)})
    certificate = {
        "schema_version": "direct_sva_elaboration_certificate",
        "wrapper_top": "SpecFlowDirectHarness",
        "generated_files": generated_files,
    }
    operations = []
    for row in properties:
        property_id = row["property_id"]
        for role, suffix in (
            ("primary_assertion", "__assert"),
            ("activation_cover", "__activation"),
        ):
            emitted = f"SpecFlowDirectHarness.{property_id}{suffix}"
            operations.append(
                {
                    "operation_id": f"{property_id}:{role}",
                    "source_property_id": property_id,
                    "role": role,
                    "emitted_property_id": emitted,
                }
            )
    operation_plan = {
        "schema_version": "direct_sva_operation_plan",
        "operations": operations,
    }
    _write_json(output_dir / "elaboration_certificate.json", certificate)
    _write_json(output_dir / "verification_operation_plan.json", operation_plan)
    result = JasperGoldBackend(timeout_seconds, per_property_seconds).run(
        output_dir, certificate, operation_plan, project["formal"]
    )
    log_text = (output_dir / "jaspergold.log").read_text(encoding="utf-8")
    result["execution_status"] = classify_direct_sva_execution(result, log_text)
    _write_json(output_dir / "direct_sva_result.json", result)
    return result


def classify_direct_sva_execution(
    result: Mapping[str, Any], log_text: str
) -> str:
    """Separate generated-HDL compile failures from formal tool failures."""

    statuses = [row.get("status") for row in result.get("operation_results", [])]
    if any(status == "tool_error" for status in statuses) and re.search(
        r"(?:\[ERROR \((?:VERI|VLOG|SV)|ERROR \(ENL|syntax error|is not declared|"
        r"module\s+.+?\s+ignored due to previous errors)",
        log_text,
        re.IGNORECASE,
    ):
        return "compile_error"
    if any(status in {"tool_error", "timeout", "inconclusive", "missing"} for status in statuses):
        return "tool_error"
    return "completed"


def summarize_direct_sva(result: Mapping[str, Any]) -> dict[str, dict[str, Any]]:
    rows: dict[str, dict[str, Any]] = {}
    for operation in result.get("operation_results", []):
        property_id, role = operation["operation_id"].split(":", 1)
        rows.setdefault(property_id, {})[role] = operation
    return rows


def _read_json(path: Path) -> dict[str, Any]:
    value = json.loads(Path(path).read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ValueError(f"JSON object required: {path}")
    return value


def _write_json(path: Path, value: Any) -> None:
    Path(path).write_text(
        json.dumps(value, indent=2, ensure_ascii=False, sort_keys=True) + "\n",
        encoding="utf-8",
    )
