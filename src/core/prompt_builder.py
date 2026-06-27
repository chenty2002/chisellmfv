"""
Prompt builder for Chisel formal verification workflow.
Builds prompts for active CoupledL2 stages:
  2. write_assertions
  3. invoke_verification
  4. waveform_explanation
  5. propose_bugfix

This module provides:
- build_system_prompt(): Returns stage-specific system prompt (for `role: system`)
- build_user_prompt(): Returns initial user task prompt (for `role: user`)
- build_tool_result_message(): Builds tool result messages (for `role: tool`)
"""

from typing import Dict, Any, Optional, List
import json
import os
from ..utils.llm_properties import MAX_ITERATIONS, WAVEFORM_MAX_ITER


PROMPT_VERSION = "coupledl2-v2-chisel-compat"

def build_system_prompt(
    stage: str = "write_assertions",
    target: str = "",
    chisel_dir: str = "",
    workspace_dir: str = "",
    work_dir_files: Optional[List[str]] = None,
) -> str:
    """
    Build the lightweight CoupledL2 system prompt for a specific stage.

    Stage-specific details live in workspace skills/rules listed by the user
    prompt. This keeps the main workflow prompt small and stable.
    """
    if stage == "waveform_explanation":
        max_iter = WAVEFORM_MAX_ITER
    else:
        max_iter = MAX_ITERATIONS

    base_prompt = [
        "# CoupledL2 Formal Verification Assistant",
        "",
        "You are an expert in CoupledL2 Chisel/Scala formal verification.",
        "",
        f"## Prompt Version: {PROMPT_VERSION}",
        "",
        "## Stable Rules",
        "- You MUST respond ONLY with tool calls. Never respond with plain text.",
        "- Always use the provided tools to inspect files, edit files, read skills, and complete stages.",
        "- Each response must contain at least one tool call.",
        "- Use `complete_stage` when it is available to declare the stage ready for deterministic validation.",
        "- Treat paths, indexes, skills, and rules in the user message as retrieved CoupledL2 run context.",
        "- Read listed rules and relevant stage skills before making source edits or diagnosis claims.",
        "- Before using Chisel, ChiselFV, LTL, or BoringUtils APIs, derive the case compatibility from `build_contract.chisel` and the versioned assertion skill.",
        "- Read exact source slices with line-limited tools before modifying files.",
        "- Keep edits inside the run workspace and cite concrete evidence in completion summaries.",
        "",
        "## Stage Instructions",
        f"## Current Stage: {stage.replace('_', ' ').title()}",
        "",
        f"## Iteration Limit",
        f"Maximum iterations allowed: {max_iter}. Make each iteration count.",
        "",
    ]

    return "\n".join(base_prompt + _build_coupledl2_stage_prompt(stage))


def _display_path(path: Optional[str], workspace_dir: Optional[str]) -> str:
    """Prefer stable relative paths in prompts while keeping unknown paths intact."""
    if not path:
        return ""
    if not os.path.isabs(path):
        return path
    if workspace_dir:
        try:
            return os.path.relpath(path, workspace_dir)
        except ValueError:
            pass
    return path


def build_user_prompt(
    context: Dict[str, Any],
    stage: str = "write_assertions",
    scala_sources: Optional[Dict[str, str]] = None,
    analysis_report: Optional[str] = None
) -> str:
    """
    Build the initial user prompt for a stage.
    This is the task description and context for the first `role: user` message.
    
    Args:
        context: Workflow context with user query and environment info
        stage: Current stage
        scala_sources: Dict mapping file paths to content for key Scala files
        analysis_report: Content of counterexample_analysis.md (for propose_bugfix stage)
        
    Returns:
        User prompt string for the initial `role: user` message
    """
    sections = []
    env = context.get("environment", {})
    workspace_dir = context.get("workspace_dir")
    target = env.get("target", "")
    coupledl2 = env.get("coupledl2")

    if not coupledl2:
        raise ValueError("CoupledL2 environment is required to build the formal workflow prompt")

    sections.extend([
        "## Task",
        context.get("user_query", "Complete the current stage of formal verification."),
        "",
    ])

    sections.extend([
        "## CoupledL2 Run Context",
        f"- Target: `{target}`",
        f"- Work Directory: `{_display_path(env.get('work_dir'), workspace_dir)}`",
        f"- Source Directory: `{_display_path(env.get('verify_src'), workspace_dir)}`",
        "- File Tool Path Rule: use workspace-relative paths such as `case/Chisel/...`, `indexes/...`, `skills/...`, `rules/...`, or `results/...`.",
        "- Source edits should use `edit_file`; finish the stage with `complete_stage` after evidence is available.",
        "",
    ])

    stage_context = coupledl2.get("stage_context") or {}
    sections.extend([
        "## CoupledL2 Stage Context",
        f"- Case: `{coupledl2.get('case_name', '')}`",
        f"- Property Category: `{coupledl2.get('property_category', '')}`",
        f"- Verify Mode: `{coupledl2.get('verify_mode', '')}`",
        f"- Input Mode: `{coupledl2.get('input_mode', '')}`",
        f"- Workspace Case Path: `{_display_path(coupledl2.get('workspace_case_path'), workspace_dir)}`",
        f"- Stage Directory: `{_display_path(stage_context.get('stage_dir'), workspace_dir)}`",
        f"- Stage Inputs: `{_display_path(stage_context.get('stage_inputs_path'), workspace_dir)}`",
        f"- Snapshot Directory: `{_display_path(stage_context.get('snapshot_dir'), workspace_dir)}`",
        "- Stage Inputs contain previous handoff records and the stable artifact contract for this stage.",
        "",
        "### Stage Skills",
    ])
    for path in stage_context.get("skills", []):
        sections.append(f"- `{path}`")
    sections.extend(["", "### Stage Rules"])
    for path in stage_context.get("rules", []):
        sections.append(f"- `{path}`")
    context_index_paths = stage_context.get("context_index_paths") or {}
    if context_index_paths:
        sections.extend(["", "### Context Index Paths"])
        for name, path in sorted(context_index_paths.items()):
            sections.append(f"- `{name}`: `{path}`")
    context_indexes = stage_context.get("context_indexes")
    chisel_compatibility = _extract_chisel_compatibility(stage_context)
    formal_surface_summary = _extract_formal_surface_summary(stage_context)
    if chisel_compatibility or formal_surface_summary:
        sections.extend([
            "",
            "## Chisel Compatibility",
            "Use this summary only as a routing hint. Take exact Formal and BoringUtils API rules from the listed versioned assertion skill, not from ad hoc source inference.",
            "```json",
            json.dumps(
                {
                    "chisel": chisel_compatibility,
                    "formal_surface": formal_surface_summary,
                },
                ensure_ascii=False,
                sort_keys=True,
                separators=(",", ":"),
            ),
            "```",
        ])
    if context_indexes:
        sections.extend([
            "",
            "### Retrieved Context Indexes",
            "```json",
            json.dumps(context_indexes, ensure_ascii=False, sort_keys=True, separators=(",", ":")),
            "```",
        ])
    sections.extend(["", "---", ""])

    # Waveform metadata for waveform_explanation stage
    if stage == "waveform_explanation" and "waveform_path" in context.get("environment", {}):
        waveform_path = context["environment"]["waveform_path"]
        waveform_display_path = _display_path(waveform_path, workspace_dir)
        waveform_filename = os.path.basename(waveform_path)
        sections.extend([
            "## Waveform Information",
            f"- Waveform File: `{waveform_display_path}`",
            f"- Waveform Filename: `{waveform_filename}`",
            "",
        ])
        
        if "waveform_metadata" in context.get("environment", {}):
            meta = context["environment"]["waveform_metadata"]
            start_time = meta.get("start_time", 0)
            end_time = meta.get("end_time", 0)
            timescale = meta.get("timescale", 0)
            time_unit_ns = 10 ** (timescale + 10)
            duration_cycle = (end_time - start_time) // time_unit_ns if time_unit_ns else 0
            
            sections.extend([
                f"- Waveform Duration: {duration_cycle} cycles ({end_time - start_time} ns)",
                f"- Time Range: {start_time} ns → {end_time} ns",
                "",
                "**Note**: All time values in waveform tools are in nanoseconds.",
                "",
                "**IMPORTANT**: Extract the assertion name from the waveform filename to locate the assertion in source code.",
                "",
            ])

        # Causal analysis prior evidence (optional, from VerilogCausalAnalysis submodule)
        causal_report = context.get("environment", {}).get("causal_analysis_report")
        causal_index = context.get("environment", {}).get("causal_analysis_index")
        if causal_report:
            sections.extend([
                "## Prior Causal Analysis (auxiliary evidence)",
                "",
                "The following report was produced by an independent Verilog causal-analysis tool",
                "(`VerilogCausalAnalysis`). Treat it as PRIOR evidence: it may suggest candidate",
                "root-cause signals and a causal DAG, but you must still verify each claim against",
                "the waveform and source code before finalising your analysis. Prefer the structured",
                "`causal_get_roots`, `causal_trace_path`, and `causal_get_node_evidence` tools when",
                "you need details from the DAG instead of repeatedly searching the waveform blindly.",
                "",
                causal_report,
                "",
                "---",
                "",
            ])
        if causal_index:
            sections.extend([
                "## Structured Causal DAG Index",
                "",
                "A compact index of the causal DAG is available below; query the full JSON through",
                "the causal_* tools when you need exact node/edge evidence.",
                "",
                "```json",
                json.dumps(
                    causal_index,
                    ensure_ascii=False,
                    sort_keys=True,
                    separators=(",", ":"),
                ),
                "```",
                "",
                "---",
                "",
            ])
    
    # Analysis report for propose_bugfix stage
    if analysis_report and stage == "propose_bugfix":
        sections.extend([
            "## Counterexample Analysis Report",
            "",
            "The following is the analysis report from the waveform_explanation stage:",
            "",
            "```markdown",
            analysis_report,
            "```",
            "",
            "---",
            "",
        ])

    if stage == "propose_bugfix":
        repair_round = env.get("repair_round")
        selected_cex = env.get("selected_counterexample")
        stage3_summary = env.get("stage3_summary")
        repair_history = env.get("repair_history")
        if repair_round or selected_cex or stage3_summary or repair_history:
            sections.extend(["## Repair Loop Context", ""])
            if repair_round:
                sections.append(f"- Repair round: `{repair_round}`")
            if selected_cex:
                sections.extend([
                    "- Selected counterexample:",
                    "```json",
                    json.dumps(selected_cex, ensure_ascii=False, sort_keys=True, separators=(",", ":")),
                    "```",
                ])
            if stage3_summary:
                sections.extend([
                    "- Latest stage-3 verification summary:",
                    "```json",
                    json.dumps(stage3_summary, ensure_ascii=False, sort_keys=True, separators=(",", ":")),
                    "```",
                ])
            if repair_history:
                sections.extend([
                    "- Accumulated repair history:",
                    "```json",
                    json.dumps(repair_history, ensure_ascii=False, sort_keys=True, separators=(",", ":")),
                    "```",
                ])
            sections.extend(["", "---", ""])
    
    return "\n".join(sections)


def build_tool_result_message(
    tool_call_id: str,
    tool_name: str,
    result: Dict[str, Any]
) -> Dict[str, Any]:
    """
    Build a tool result message for the message history.
    
    Args:
        tool_call_id: The ID of the tool call this is responding to
        tool_name: Name of the tool that was called
        result: The result dictionary from executing the tool
        
    Returns:
        Message dictionary with role: tool
    """
    # Format result content compactly; exact JSON is preserved, but repeated tool
    # turns no longer pay for pretty-print whitespace.
    content = json.dumps(result, ensure_ascii=False, separators=(",", ":"))
    
    return {
        "role": "tool",
        "tool_call_id": tool_call_id,
        "name": tool_name,
        "content": content
    }


def build_assistant_tool_call_message(
    raw_message: Dict[str, Any],
    function_calls: List[Dict[str, Any]],
) -> Dict[str, Any]:
    """
    Build an assistant history message after a tool-call response.

    DeepSeek thinking mode requires assistant.reasoning_content to be carried
    forward whenever that assistant turn made tool calls, so preserve it from
    the raw API message when present.
    """
    message = {
        "role": "assistant",
        "content": raw_message.get("content", None),
    }

    if "name" in raw_message:
        message["name"] = raw_message["name"]
    if "reasoning_content" in raw_message:
        message["reasoning_content"] = raw_message.get("reasoning_content")

    raw_tool_calls = raw_message.get("tool_calls")
    if raw_tool_calls:
        message["tool_calls"] = raw_tool_calls
    else:
        message["tool_calls"] = [
            {
                "id": fc["id"],
                "type": "function",
                "function": {
                    "name": fc["name"],
                    "arguments": json.dumps(fc["arguments"], ensure_ascii=False)
                }
            }
            for fc in function_calls
        ]

    return message


def build_compilation_error_message(error: str) -> str:
    """
    Build a message for final build-check errors to append to the conversation.
    
    Args:
        error: The compilation or generated-output validation error message
        
    Returns:
        Formatted error message string
    """
    return f"""## Final Build Check Failed

Your code did not pass the final build check. The design may have failed to compile,
or generated Verilog/SystemVerilog may be missing required assertions. Please fix the
following errors:

```
{error}
```

**Action Required**: Use the appropriate tool to write corrected code, then call `complete_stage` again with updated evidence."""


def _build_coupledl2_stage_prompt(stage: str) -> list:
    """Generate lightweight CoupledL2 stage instructions."""
    stage_prompts = {
        "write_assertions": [
            "## Objective",
            "Add high-value CoupledL2 formal properties in the emitted design.",
            "",
            "## Actions",
            "Determine the case Chisel version from `build_contract.chisel`, then read the listed assertion compatibility skill, `bounded_liveness.md`, `tilelink_protocol.md`, and the listed rules before editing.",
            "If `build_contract.chisel` is missing or unclear, inspect only build files such as `case/Chisel/build.sc`, `case/Chisel/common.sc`, or `case/Chisel/build.sbt` to choose the versioned assertion skill.",
            "Use retrieved indexes to choose source slices, then call `complete_stage` with assertion and build evidence.",
            "",
        ],
        "invoke_verification": [
            "## Objective",
            "Run the configured CoupledL2 build and formal verification flow.",
            "",
            "## Actions",
            "This stage is normally deterministic. If tools are available, inspect artifacts and complete only from concrete result evidence.",
            "",
        ],
        "waveform_explanation": [
            "## Objective",
            "Diagnose a CoupledL2 counterexample and classify the failure source.",
            "",
            "## Actions",
            "Read `waveform_diagnosis.md` and the listed rules before making diagnosis claims.",
            "Use waveform/source/causal tools for evidence, write the report with `write_report`, then call `complete_stage`.",
            "",
        ],
        "propose_bugfix": [
            "## Objective",
            "Perform one focused CoupledL2 repair round based on diagnosis and repair history.",
            "",
            "## Actions",
            "Read `repair_regression.md` and the listed rules before editing.",
            "Use `edit_file` for one focused repair, then call `complete_stage` with repair category and evidence.",
            "",
        ],
    }
    
    return stage_prompts.get(stage, ["## Unknown Stage", ""])


def _extract_chisel_compatibility(stage_context: Dict[str, Any]) -> Any:
    compatibility = stage_context.get("chisel_compatibility")
    if compatibility:
        return compatibility
    context_indexes = stage_context.get("context_indexes")
    if isinstance(context_indexes, dict):
        return context_indexes.get("build_contract", {}).get("chisel")
    return None


def _extract_formal_surface_summary(stage_context: Dict[str, Any]) -> Any:
    summary = stage_context.get("formal_surface_summary")
    if summary:
        return summary
    context_indexes = stage_context.get("context_indexes")
    if not isinstance(context_indexes, dict):
        return None
    formal_surface = context_indexes.get("formal_surface")
    if not isinstance(formal_surface, dict):
        return None
    return {
        "uses_chiselfv": formal_surface.get("uses_chiselfv"),
        "uses_boring_utils": formal_surface.get("uses_boring_utils"),
        "uses_ltl": formal_surface.get("uses_ltl"),
    }
