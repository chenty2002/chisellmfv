"""
Prompt builder for Chisel formal verification workflow.
Builds prompts for each of the 5 stages:
  1. build_top_module
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
import hashlib
import json
import os
from ..utils.llm_properties import MAX_ITERATIONS, WAVEFORM_MAX_ITER


PROMPT_VERSION = "formal-v2-cache-aware"


CHISEL6_LTL_DOC = """
## Chisel 6 LTL Assertion API Reference

Import Statements: 
```scala
import chisel3.ltl._
import chisel3.ltl.Sequence._  // Enables Bool → Sequence implicit conversion
```

### Core Concepts

1. **Sequence**: Represents a temporal sequence of boolean conditions
2. **Property**: Represents a temporal property to be verified

### Building Sequences

**From Bool values**:
```scala
val seq1: Sequence = mySignal  // Bool implicitly becomes Sequence
```

**Delay operators**:
```scala
seq.delay()              // ##1 in SVA - delay by 1 cycle (default)
seq.delay(3)             // ##3 in SVA - delay by 3 cycles
seq.delayRange(2, 5)     // ##[2:5] in SVA - delay between 2-5 cycles
seq.delayAtLeast(3)      // ##[3:$] in SVA - delay at least 3 cycles
```

**Sequence composition**:
```scala
seq1.concat(seq2)        // seq1 ##0 seq2 in SVA - concatenate sequences
seq1 ### seq2            // seq1 ##1 seq2 in SVA - concat with 1 cycle delay
seq1 ##* seq2            // seq1 ##[0:$] seq2 in SVA - concat with unbounded delay
seq1 ##+ seq2            // seq1 ##[1:$] seq2 in SVA - concat with >=1 delay

seq1.and(seq2)           // seq1 and seq2 in SVA - both sequences must hold
seq1.or(seq2)            // seq1 or seq2 in SVA - at least one must hold
```

**Convenience constructor**:
```scala
Sequence(a, Delay(), b, Delay(2), c, Delay(3, 9), d)
// Equivalent to: a.concat(b.delay()).concat(c.delay(2)).concat(d.delayRange(3, 9))
```

### Building Properties

**Property operators**:
```scala
prop.not                 // not prop in SVA
prop.eventually          // s_eventually prop in SVA (must hold in finite time)
prop1.and(prop2)         // prop1 and prop2 in SVA
prop1.or(prop2)          // prop1 or prop2 in SVA
```

### Assertion Constructs

**AssertProperty** - assert that property holds:
```scala
// Simple boolean assertion
AssertProperty(mySignal)
AssertProperty(mySignal, "label_name")

// Using implicit clock from Module context
AssertProperty(request |-> Sequence(grant).delay(1, 10))
```

### CRITICAL: No `when` Blocks Around Assertions

```scala
// WRONG - Don't do this:
when(enable) {
  AssertProperty(prop)
}

// CORRECT - Encode condition in the property:
AssertProperty(!enable || prop)
// Or use implication:
AssertProperty(enable |-> prop)
```
"""

CHISELFV_API_DOC = """
## ChiselFV API Reference

Import Statement: import chiselFv._
Wrapper: Wrap target module with trait Formal 

### Available Assertions (in trait Formal)

1. Basic Assertion:
   fvAssert(cond: Bool, msg: String = "")
   // Asserts: cond must be true

2. Timed Assertions:
   assertAt(n: UInt, cond: Bool, msg: String = "")
   // Asserts: cond must be true exactly at cycle n after reset
   
   assertAfterNStepWhen(cond: Bool, n: Int, asert: Bool, msg: String = "")
   // Asserts: when cond fires, asert must be true after exactly n cycles
   
   assertNextStepWhen(cond: Bool, asert: Bool, msg: String = "")
   // Asserts: when cond fires, asert must be true in the next cycle
   
   assertAlwaysAfterNStepWhen(cond: Bool, n: Int, asert: Bool, msg: String = "")
   // Asserts: once cond fires, asert must be true forever starting from n cycles later

3. Liveness Assertions:
   astLiveness(req: Bool, resp: Bool, msg: String = "")
   // Asserts: req |-> eventually(resp) - unbounded, may not be supported by all backends
   
   astRelaxedLiveness(req: Bool, resp: Bool, n: Int, msg: String = "")
   // Asserts: req |-> resp within 1 to n cycles - RECOMMENDED for deadlock detection
   
   assertLivenessTimer(cond: Bool, reset: Bool, n: Int, msg: String = "")
   // Asserts: timer counting while cond is true must not exceed n; reset clears timer

4. Mutex & Encoding Assertions:
   assertMutex(conds: Seq[Bool], msg: String = "")
   // Asserts: at most one condition in conds is true
   
   assertOneHot(signal: UInt, msg: String = "")
   // Asserts: exactly one bit in signal is set
   
   assertOneHot0(signal: UInt, msg: String = "")
   // Asserts: at most one bit in signal is set

5. Stability Assertions:
   assertStable(signal: UInt, msg: String = "")
   // Asserts: signal value is unchanged from the previous cycle
   
   assertStableWhen(en: Bool, signal: UInt, msg: String = "")
   // Asserts: when en is high, signal must equal its previous value

6. Edge Detection Assertions:
   assertOnRise(signal: Bool, cond: Bool, msg: String = "")
   // Asserts: on rising edge of signal (0->1), cond must be true
   
   assertOnFall(signal: Bool, cond: Bool, msg: String = "")
   // Asserts: on falling edge of signal (1->0), cond must be true

7. Implication Assertions:
   assertImplies(antecedent: Bool, consequent: Bool, msg: String = "")
   // Asserts: antecedent |-> consequent in the same cycle
   
   assertImpliesDelay(antecedent: Bool, consequent: Bool, n: Int, msg: String = "")
   // Asserts: antecedent |-> consequent after exactly n cycles
   
**All the above assertion APIs only accept Chisel Bool conditions, not LTL formulas.**

### CRITICAL: Assertions Must NOT Be Inside `when` Blocks

```scala
// WRONG (assertion inside when block - will not work correctly):
when(someCondition) {
  fvAssert(property, "msg")
}

// CORRECT:
fvAssert(!someCondition || property, "msg")
```

For liveness assertions**, integrate the condition into the request/response signals:
```scala
// WRONG:
when(mshr_active) {
  astRelaxedLiveness(request_valid, done, 1000, "msg")
}

// CORRECT:
astRelaxedLiveness(mshr_active && request_valid, done || !mshr_active, 1000, "msg")
```

### BoringUtils for Signal Access
```scala
import chisel3.util.experimental.BoringUtils

// In outer module, access internal signals from submodules
val internal_signal = BoringUtils.bore(submodule.internal_reg)
```

"""

def build_system_prompt(
    stage: str = "build_top_module",
    target: str = "",
    chisel_dir: str = "",
    workspace_dir: str = "",
    work_dir_files: Optional[List[str]] = None,
) -> str:
    """
    Build the system prompt for a specific stage.
    This is the static instruction that defines the LLM's role and capabilities.
    
    Args:
        stage: Current stage (build_top_module, write_assertions, etc.)
        target/chisel_dir/workspace_dir/work_dir_files: Kept for API compatibility.
            Benchmark-specific context is intentionally emitted in the user
            prompt so the system prompt keeps a long cacheable prefix across
            benchmark targets.
        
    Returns:
        System prompt string for the `role: system` message
    """
    # Determine max iterations based on stage
    if stage == "waveform_explanation":
        max_iter = WAVEFORM_MAX_ITER
    else:
        max_iter = MAX_ITERATIONS
    
    # Base system prompt - common across all stages
    base_prompt = [
        "# Chisel Formal Verification Assistant",
        "",
        "You are an expert in hardware design and formal verification, specializing in Chisel/Scala and formal verification techniques.",
    ]
    
    base_prompt.extend([
        "",
        f"## Prompt Version: {PROMPT_VERSION}",
        "",
        "## Response Format Requirements",
        "- You MUST respond ONLY with tool calls. Never respond with plain text.",
        "- Always use the provided tools to perform actions",
        "- Each response must contain at least one tool call",
        "- Set `stage_complete=true` in your final tool call when the stage objective is achieved",
        "",
        f"## Current Stage: {stage.replace('_', ' ').title()}",
        "",
        f"## Iteration Limit",
        f"Maximum iterations allowed: {max_iter}. Make each iteration count.",
        "",
    ])
    
    stage_section = _build_generic_stage_prompt(stage)
    
    cache_note = [
        "## Benchmark Context",
        "Benchmark-specific target names, paths, source manifests, waveform paths,",
        "and prior reports are provided in the user message. Prefer relative paths",
        "when calling tools.",
        "",
    ]
    
    return "\n".join(base_prompt + stage_section + cache_note)


def _display_path(path: Optional[str], workspace_dir: Optional[str]) -> str:
    """Prefer stable relative paths in prompts while keeping unknown paths intact."""
    if not path:
        return ""
    if workspace_dir:
        try:
            return os.path.relpath(path, workspace_dir)
        except ValueError:
            pass
    return path


def _scala_symbol_summary(content: str) -> str:
    """Extract a compact symbol summary to help the model choose files to read."""
    import re

    symbols: List[str] = []
    package_match = re.search(r"^\s*package\s+([\w.]+)", content, re.MULTILINE)
    if package_match:
        symbols.append(f"package {package_match.group(1)}")

    for kind, name in re.findall(
        r"^\s*(class|object|trait)\s+([A-Za-z_]\w*)",
        content,
        flags=re.MULTILINE,
    )[:12]:
        symbols.append(f"{kind} {name}")

    return ", ".join(symbols) if symbols else "no top-level symbols detected"


def _format_source_manifest(scala_sources: Dict[str, str]) -> List[str]:
    """Render a compact deterministic source manifest instead of full files."""
    lines = [
        "## Source Manifest",
        "",
        "Source files are summarized here to keep benchmark prompts small. Use",
        "`read_files` to inspect exact code before confirming, modifying, or fixing",
        "a file.",
        "",
    ]

    for fpath in sorted(scala_sources):
        content = scala_sources[fpath]
        fname = os.path.basename(fpath)
        digest = hashlib.sha256(content.encode("utf-8")).hexdigest()[:12]
        line_count = content.count("\n") + (1 if content else 0)
        lines.append(
            f"- `{fname}`: {line_count} lines, {len(content)} chars, "
            f"sha256:{digest}; symbols: {_scala_symbol_summary(content)}"
        )

    lines.extend(["", "---", ""])
    return lines


def build_user_prompt(
    context: Dict[str, Any],
    stage: str = "build_top_module",
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
    target = env.get("target") or env.get("benchmark", "")
    
    # User query/task first: it is mostly stage-static in benchmark runs, which
    # improves prompt-cache locality before benchmark-specific data appears.
    sections.extend([
        "## Task",
        context.get("user_query", "Complete the current stage of formal verification."),
        "",
        "## Benchmark Context",
        f"- Benchmark: `{target}`",
        f"- Work Directory: `{_display_path(env.get('work_dir'), workspace_dir)}`",
        f"- Source Directory: `{_display_path(env.get('verify_src'), workspace_dir)}`",
        f"- Generated Verilog: `chisel/extra_bench/{target}/generated/`",
        f"- Verilog Output: `verilog/extra_bench/{target}/`",
        "- Writable Directory: the benchmark work directory",
        "",
    ])

    if context.get("existing_harness_files"):
        sections.append("## Existing Harness Candidates")
        for path in sorted(context.get("existing_harness_files", [])):
            sections.append(f"- `{_display_path(path, workspace_dir)}`")
        sections.append("")
    
    # Add source files if provided
    if scala_sources and stage != "invoke_verification":
        sections.extend(_format_source_manifest(scala_sources))
    
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
    Build a message for compilation errors to append to the conversation.
    
    Args:
        error: The compilation error message
        
    Returns:
        Formatted error message string
    """
    return f"""## Compilation Failed

Your code did not compile. Please fix the following errors:

```
{error}
```

**Action Required**: Use the appropriate tool to write corrected code and set `stage_complete=true` again."""


def _build_generic_stage_prompt(stage: str) -> list:
    """Generate generic prompt for extra_bench target (no detailed docs)."""
    stage_prompts = {
        "build_top_module": [
            "## Objective",
            "Confirm or generate the main Chisel module.",
            "Ensure the module compiles and can generate Verilog.",
            "",
            "## Build Command",
            "The compilation command is fixed:",
            "```",
            "sbt \"runMain llmverify.VerilogGenerator --target-dir generated\"",
            "```",
            "This means:",
            "- The entry point must be an **object** named `VerilogGenerator` in package `llmverify`",
            "- The object must have a `main` method (typically by extending `App` or defining `def main(args: Array[String])`)",
            "- It should use a Chisel method to generate Verilog and output to the `generated` directory (or it uses command-line args to specify the output directory)",
            "",
            "## CRITICAL DECISION RULES",
            "You MUST follow these steps in order:",
            "",
            "**Step 1: Analyze Existing Files**",
            "- Use the source manifest to choose files, then call `read_files` for exact source before deciding.",
            "- Check if source files contain an object that:",
            "  1. Is named `VerilogGenerator` in package `llmverify`",
            "  2. Instantiates the DUT (design under test)",
            "  3. Has a main method or extends App that uses a Chisel method to generate Verilog",
            "",
            "**Step 2: Decision**",
            "- IF such a generator EXISTS and appears STRUCTURALLY COMPLETE:",
            "  Use `confirm_existing_harness` with `stage_complete=true`",
            "",
            "- IF NO generator exists OR existing generator has CLEAR STRUCTURAL ERRORS:",
            "  Use `write_file` to create/fix the generator",
            "",
            "## Guidelines for New Generator (if needed)",
            "- Analyze the relevant Scala source files via `read_files` to understand the design",
            "- Create `VerilogGenerator.scala` with proper structure:",
            "  ```scala",
            "  package llmverify",
            "  ",
            "  import chisel3._",
            "  ",
            "  object VerilogGenerator extends App {",
            "    emitVerilog(new YourDUT(), args)",
            "  }",
            "  ```",
            "- The harness should ONLY set up the test environment, NOT include assertions",
            "",
            "## Reset Tool",
            "If you realize your previous output has fundamental issues (wrong structure, too many wrong attempts),",
            "use `reset_stage` tool to restore all files to their initial state and start fresh (the message history before will be cleared).",
            "Provide a detailed reason explaining what went wrong.",
            "",
            "## Actions",
            "Use `confirm_existing_harness` OR `write_file` OR `reset_stage`.",
            "Mark `stage_complete=true` when the module is ready for compilation.",
            "",
        ],
        "write_assertions": [
            "## Objective",
            "Add formal verification assertions to the Chisel design.",
            "",
            "## Guidelines",
            "- Use the source manifest to select files, then call `read_files` for exact design logic",
            "- Identify critical properties to verify",
            "- Add assertions using Chisel's formal verification APIs",
            "- Consider using ChiselFV APIs or Chisel LTL assertions",
            "- Do not use BoringUtils to access IO signals because they are not visible from outer modules. You can connect IOs to Wire signals and tap them if you must",
            "",
            CHISELFV_API_DOC,
            "",
            CHISEL6_LTL_DOC,
            "",
            "## Actions",
            "Modify the source files to add assertions.",
            "Mark `stage_complete=true` when assertions are added.",
            "",
        ],
        "invoke_verification": [
            "## Objective",
            "Compile the design and check for errors.",
            "",
            "## Actions",
            "Verify the code compiles successfully.",
            "Fix any compilation errors if they occur.",
            "",
        ],
        "waveform_explanation": [
            "## Objective",
            "Analyze the counterexample waveform to identify the root cause of the assertion failure.",
            "",
            "## Prior Evidence: Causal Analysis Report",
            "If a `## Prior Causal Analysis` section is present in the user prompt, it contains a",
            "root-cause hypothesis computed by an external Verilog causal-analysis tool",
            "(`VerilogCausalAnalysis`). Use it as a starting point — review its candidate root",
            "causes first, then VERIFY each of them against the waveform and source code. Do NOT",
            "blindly copy it; treat it as one piece of evidence among many.",
            "",
            "## Multiple Root Cause Possibilities",
            "The counterexample may be caused by one of the following:",
            "1. **Bug in the Original Design**: The DUT has a genuine bug that violates the property.",
            "2. **Incorrect Assertion**: The assertion itself may be wrong (e.g., wrong condition, wrong timing bound).",
            "3. **Incorrect Top Module Setup**: The TestTop configuration may be invalid (e.g., missing constraints, wrong parameters, unrealistic stimulus).",
            "",
            "Your analysis should determine WHICH category the issue falls into before proposing fixes.",
            "",
            "## Chisel to Verilog Naming Convention",
            "When Chisel compiles to Verilog, signal names follow these rules:",
            "- **Hierarchy separator**: Chisel uses `.` but Verilog uses `_` (e.g., `io.input.valid` → `io_input_valid`)",
            "- **Vec indexing**: `vec(i)` becomes `vec_i` (e.g., `regs(0)` → `regs_0`)",
            "- **Bundle fields**: `bundle.field` becomes `bundle_field`",
            "- **when/otherwise**: Generates intermediate signals with `_T_`, `_GEN_` prefixes",
            "- **Module instances**: `moduleName` becomes `moduleName_` prefix for internal signals",
            "- **Registers**: Keep their Chisel names but may have `_REG` suffix in some cases",
            "- **Temporary signals**: `_T_0`, `_T_1`, etc. are compiler-generated intermediates",
            "- **Generated signals**: `_GEN_0`, `_GEN_1`, etc. are mux outputs from when/otherwise",
            "",
            "**Tip**: Signal naming may vary by backend. Always trust exact names returned by waveform tools.",
            "",
            "## Critical Signal Lookup Rules",
            "- For `waveform_get_signal_value`, use the **exact** signal name returned by `waveform_find_signals`/`waveform_get_active_signals`.",
            "- Do NOT strip bit-range suffixes. Example: use `abp.a.lfsr [15:0]`, not `abp.a.lfsr`.",
            "- If one lookup fails, do NOT repeat the same call more than once. Switch to `waveform_find_signals` to resolve the correct name.",
            "",
            "## Analysis Workflow",
            "1. **Locate the Assertion**: Extract the assertion name from the waveform filename, then search source files to find the corresponding assertion in the code.",
            "2. **Understand the Property**: Read the assertion and surrounding code to understand what property is being verified.",
            "3. **Analyze the Waveform**: Use waveform tools to explore signals and identify the sequence of events leading to the assertion failure.",
            "4. **Trace Root Cause**: Correlate waveform signals with source code to pinpoint the buggy logic.",
            "",
            "## Report Structure (Required)",
            "Your final report must include the following sections:",
            "",
            "### 1. Verification Environment",
            "- Top module name and structure",
            "- Key components and their connections",
            "- Brief description of the design under test",
            "",
            "### 2. Violated Assertion",
            "- Full assertion name (from waveform filename)",
            "- Code snippet showing the assertion",
            "- Natural language description of the property being checked",
            "- File location (path and line number)",
            "",
            "### 3. Waveform Information",
            "- Full path to the waveform file",
            "- Time range and key time points (in nanoseconds)",
            "- Critical signal values at failure point",
            "",
            "### 4. Root Cause Analysis",
            "- Buggy code location (file, line number, function/module name)",
            "- Description of the bug (what logic is wrong)",
            "- Evidence from waveform (signal traces showing the bug)",
            "- Why this causes the assertion to fail",
            "",
            "## Actions",
            "1. Extract assertion name from waveform filename",
            "2. Search source files for the assertion",
            "3. Use waveform tools to analyze signals (all time values are in nanoseconds)",
            "4. Read relevant source files to understand the logic",
            "5. Use `write_report` with complete markdown content to save your analysis (this is the ONLY way to complete the stage). You can invoke multiple times to refine your report if needed.",
            "",
            "**Important**: Only `write_report` can complete this stage. All other tools are for investigation only.",
            "Do not focus on signals starting with `_`, as they are intermediate signals generated in compilation.",
            "",
        ],
        "propose_bugfix": [
            "## Objective",
            "Based on the waveform analysis report, fix the identified issue.",
            "",
            "## Determine What to Fix",
            "Based on the analysis report, identify which component needs fixing:",
            "",
            "### Case 1: Bug in Original Design (DUT)",
            "- Fix the DUT source code to correct the bug",
            "",
            "### Case 2: Incorrect Assertion",
            "- Fix the assertion in the source code",
            "",
            "### Case 3: Incorrect Top Module Setup",
            "- Fix the top module to add proper constraints or fix configuration",
            "",
            "## Fix Principles",
            "1. **Minimal change**: Make the smallest possible modification to fix the issue",
            "2. **Readable code**: Keep the fix simple and easy to understand",
            "3. **Low performance impact**: Avoid introducing unnecessary overhead",
            "4. **Low side effects**: Minimize impact on other parts of the codebase",
            "5. **Preserve functionality**: Do not break existing features",
            "",
            "## Actions",
            "Use `write_fix` to apply the fix with `stage_complete=true` and `bugfix_report`",
            "The `bugfix_report` should describe what was fixed (DUT/assertion/setup) and why",
            "",
            "**Note**: After you write the fix, the system will automatically verify compilation.",
            "If compilation fails, you will be asked to fix the errors.",
            "",
        ],
    }
    
    return stage_prompts.get(stage, ["## Unknown Stage", ""])
