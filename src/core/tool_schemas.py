"""
Tool schemas for Chisel formal verification workflow.
Defines structured actions as function declarations for the LLM to call.

5 Stages:
  1. build_top_module - Generate TestTop.scala with InclusiveCache
  2. write_assertions - Add deadlock detection assertions using ChiselFV
  3. invoke_verification - Compile and run formal verification
  4. waveform_explanation - Analyze counterexample waveforms
  5. propose_bugfix - Fix identified bugs
"""

from dataclasses import dataclass
from typing import Dict, List, Any, Optional, Iterable, Set
from copy import deepcopy

# Supported formal verification stages
FORMAL_STAGES = ["build_top_module", "write_assertions", "invoke_verification", 
                 "waveform_explanation", "propose_bugfix"]


@dataclass(frozen=True)
class ToolSpec:
    """Registered tool schema plus workflow policy metadata."""

    name: str
    schema: Dict[str, Any]
    allowed_stages: Set[str]
    write_policy: str = "read_only"
    completion_capability: bool = False
    audit_level: str = "standard"
    workspace_only: bool = False


class ToolRegistry:
    """Stage-aware registry for formal workflow tool schemas."""

    def __init__(self, specs: Optional[Iterable[ToolSpec]] = None):
        self._specs_by_name: Dict[str, List[ToolSpec]] = {}
        self._ordered_specs: List[ToolSpec] = []
        for spec in specs or []:
            self.register(spec)

    def register(self, spec: ToolSpec) -> None:
        self._ordered_specs.append(spec)
        self._specs_by_name.setdefault(spec.name, []).append(spec)

    def get(self, name: str) -> ToolSpec:
        return self._specs_by_name[name][-1]

    def get_stage_specs(self, stage: str, *, include_workspace: bool = False) -> List[ToolSpec]:
        return [
            spec
            for spec in self._ordered_specs
            if stage in spec.allowed_stages
            and (include_workspace or not spec.workspace_only)
        ]

    def get_tool_schemas(self, stage: str, *, include_workspace: bool = False) -> List[Dict[str, Any]]:
        return [deepcopy(spec.schema) for spec in self.get_stage_specs(stage, include_workspace=include_workspace)]


STAGE_COMPLETION_PARAMS = {
    "stage_complete": {
        "type": "boolean",
        "description": "Set to true if this action completes the current stage",
        "default": False
    }
}

def add_complete_params(properties: Dict) -> Dict:
    """Add stage completion parameters to a properties dict."""
    result = deepcopy(properties)
    result.update(STAGE_COMPLETION_PARAMS)
    return result

WAVEFORM_TOOLS = [
    {
        "name": "waveform_find_signals",
        "description": "Find signals in the waveform matching a pattern",
        "parameters": {
            "type": "object",
            "properties": {
                "pattern": {
                    "type": "string",
                    "description": "Pattern to search for in signal names"
                },
                "regex": {
                    "type": "boolean",
                    "description": "Whether the pattern is a regex",
                    "default": False
                },
                "max_results": {
                    "type": "integer",
                    "description": "Maximum number of results to return",
                    "default": 50
                },
            },
            "required": ["pattern"]
        }
    },
    {
        "name": "waveform_get_signal_value",
        "description": "Get the values of one or more signals at their corresponding time points. Accepts parallel arrays: signal_names[i] is queried at times[i]. Signal names must be exact waveform names (including bit-range suffix like ' [15:0]' when present).",
        "parameters": {
            "type": "object",
            "properties": {
                "signal_names": {
                    "type": "array",
                    "items": {"type": "string"},
                    "description": "List of full hierarchical signal names to query"
                },
                "times": {
                    "type": "array",
                    "items": {"type": "integer"},
                    "description": "List of time points corresponding to each signal (must be same length as signal_names)"
                }
            },
            "required": ["signal_names", "times"]
        }
    },
    {
        "name": "waveform_trace_signal",
        "description": "Trace a signal's value changes over a time range",
        "parameters": {
            "type": "object",
            "properties": {
                "signal_name": {"type": "string", "description": "Full hierarchical name of the signal"},
                "start_time": {"type": "integer", "description": "Optional start time"},
                "end_time": {"type": "integer", "description": "Optional end time"},
                "max_changes": {"type": "integer", "description": "Maximum changes to return", "default": 100}
            },
            "required": ["signal_name"]
        }
    },
    {
        "name": "waveform_get_active_signals",
        "description": "Get all non-zero signals at a specific time point",
        "parameters": {
            "type": "object",
            "properties": {
                "time": {"type": "integer", "description": "Time point to query"},
                "scope_pattern": {"type": "string", "description": "Optional pattern to filter by scope"},
                "max_results": {"type": "integer", "description": "Maximum results", "default": 100}
            },
            "required": ["time"]
        }
    },
    {
        "name": "waveform_compare_signals",
        "description": "Compare multiple signals at multiple time points",
        "parameters": {
            "type": "object",
            "properties": {
                "signal_names": {"type": "array", "items": {"type": "string"}, "description": "List of signal names"},
                "times": {"type": "array", "items": {"type": "integer"}, "description": "List of time points"}
            },
            "required": ["signal_names", "times"]
        }
    },
    {
        "name": "waveform_find_transitions",
        "description": "Find all time points where a signal transitions between values",
        "parameters": {
            "type": "object",
            "properties": {
                "signal_name": {"type": "string", "description": "Full hierarchical name of the signal"},
                "from_value": {"type": "string", "description": "Value to transition from"},
                "to_value": {"type": "string", "description": "Value to transition to"},
                "max_matches": {"type": "integer", "description": "Maximum transitions", "default": 50}
            },
            "required": ["signal_name", "from_value", "to_value"]
        }
    }
]

CAUSAL_TOOLS = [
    {
        "name": "causal_get_roots",
        "description": "Query structured VerilogCausalAnalysis JSON for ranked root-cause candidate nodes. Use this before manually expanding many waveform signals.",
        "parameters": {
            "type": "object",
            "properties": {
                "limit": {
                    "type": "integer",
                    "description": "Maximum number of root candidates to return",
                    "default": 10
                },
                "min_score": {
                    "type": "number",
                    "description": "Optional minimum suspect score filter"
                }
            }
        }
    },
    {
        "name": "causal_trace_path",
        "description": "Trace causal paths in the structured causal DAG, typically from root candidates toward the endpoint assertion failure.",
        "parameters": {
            "type": "object",
            "properties": {
                "source_node_id": {
                    "type": "string",
                    "description": "Optional source/root node id. If omitted, paths from roots are considered."
                },
                "target_node_id": {
                    "type": "string",
                    "description": "Optional destination node id. If omitted, the endpoint node is used."
                },
                "signal": {
                    "type": "string",
                    "description": "Optional signal-name substring used to choose candidate source nodes"
                },
                "max_depth": {
                    "type": "integer",
                    "description": "Maximum path depth to traverse",
                    "default": 12
                },
                "max_paths": {
                    "type": "integer",
                    "description": "Maximum number of paths to return",
                    "default": 5
                }
            }
        }
    },
    {
        "name": "causal_get_node_evidence",
        "description": "Get detailed node evidence from VerilogCausalAnalysis JSON, including incoming/outgoing edges and RTL references.",
        "parameters": {
            "type": "object",
            "properties": {
                "node_id": {
                    "type": "string",
                    "description": "Exact causal node id"
                },
                "signal": {
                    "type": "string",
                    "description": "Signal-name substring to find a node when node_id is unknown"
                },
                "cycle": {
                    "type": "integer",
                    "description": "Optional cycle filter when searching by signal"
                }
            }
        }
    }
]

WRITE_REPORT_TOOL = {
    "name": "write_report",
    "description": "Write the counterexample analysis report to counterexample_analysis.md in the work directory. This is the final step to complete the stage.",
    "parameters": {
        "type": "object",
        "properties": {
            "content": {
                "type": "string",
                "description": "Markdown content of the analysis report"
            },
            "stage_complete": {"type": "boolean", "description": "Set to true if this action completes the current stage", "default": False},
            "error_type": {
                "type": "string",
                "enum": ["dut_bug", "assertion_error", "setup_error"],
                "description": "Type of error found in counterexample (REQUIRED if stage_complete=true). Choose: 'dut_bug' for real DUT bugs, 'assertion_error' for assertion writing errors, 'setup_error' for top module configuration errors"
            }
        },
        "required": ["content"]
    }
}

def create_read_files_tool(extra_description: str = "") -> Dict:
    """
    Create a read_files tool schema.
    
    Args:
        extra_description: Additional context for the description
    """
    base_desc = "Read source files to correlate with waveform signals" if not extra_description else extra_description
    
    return {
        "name": "read_files",
        "description": base_desc,
        "parameters": {
            "type": "object",
            "properties": add_complete_params({
                "file_paths": {
                    "type": "array",
                    "items": {"type": "string"},
                    "description": "List of source file paths. Paths are resolved against the benchmark work directory, independent of the shell cwd. Prefer the basename shown in the source manifest, e.g. 'arbiter.scala'; workspace-relative paths like 'chisel/extra_bench/<benchmark>/arbiter.scala' are also accepted."
                },
                "line_start": {
                    "type": "integer",
                    "description": "Optional 1-based first line to read from each file"
                },
                "line_end": {
                    "type": "integer",
                    "description": "Optional 1-based last line to read from each file"
                },
                "max_chars": {
                    "type": "integer",
                    "description": "Optional maximum characters to return per file after line filtering"
                },
                "reason": {
                    "type": "string",
                    "description": "Reason for reading these files"
                }
            }),
            "required": ["file_paths", "reason"]
        }
    }


def create_coupledl2_read_files_tool() -> Dict:
    """Create a CoupledL2 workspace-oriented read_files schema."""
    return {
        "name": "read_files",
        "description": "Read source or artifact files inside the CoupledL2 run workspace. Prefer workspace-relative paths with line_start/line_end when possible.",
        "parameters": {
            "type": "object",
            "properties": {
                "file_paths": {
                    "type": "array",
                    "items": {"type": "string"},
                    "description": "Workspace-relative files to read, such as case/Chisel/src/test/scala/coupledl2/VerifyTop.scala or results/by_stage/03_invoke_verification/formal_result.json."
                },
                "line_start": {
                    "type": "integer",
                    "description": "Optional 1-based first line to read from each file"
                },
                "line_end": {
                    "type": "integer",
                    "description": "Optional 1-based last line to read from each file"
                },
                "max_chars": {
                    "type": "integer",
                    "description": "Optional maximum characters to return per file after line filtering"
                },
                "reason": {
                    "type": "string",
                    "description": "Reason for reading these files"
                }
            },
            "required": ["file_paths", "reason"]
        }
    }


WORKSPACE_CONTEXT_TOOLS = [
    {
        "name": "list_files",
        "description": "List files under the CoupledL2 run workspace. Paths are workspace-relative and cannot escape the workspace.",
        "parameters": {
            "type": "object",
            "properties": {
                "path": {"type": "string", "description": "Workspace-relative directory or file path", "default": "."},
                "pattern": {"type": "string", "description": "Glob pattern for files", "default": "*"},
            },
        },
    },
    {
        "name": "rg",
        "description": "Search CoupledL2 workspace files with ripgrep and return structured line matches.",
        "parameters": {
            "type": "object",
            "properties": {
                "pattern": {"type": "string", "description": "Search pattern"},
                "path": {"type": "string", "description": "Workspace-relative path to search", "default": "."},
                "glob": {"type": "string", "description": "Optional ripgrep glob, such as *.scala"},
                "max_matches": {"type": "integer", "description": "Maximum matches to return", "default": 100},
            },
            "required": ["pattern"],
        },
    },
    {
        "name": "read_skill",
        "description": "Read one installed CoupledL2 workspace skill by name or relative path.",
        "parameters": {
            "type": "object",
            "properties": {
                "name": {"type": "string", "description": "Skill file name, with or without .md suffix"},
            },
        },
    },
    {
        "name": "read_rule",
        "description": "Read one installed CoupledL2 workspace rule by name or relative path.",
        "parameters": {
            "type": "object",
            "properties": {
                "name": {"type": "string", "description": "Rule file name, with or without .md suffix"},
            },
        },
    },
    {
        "name": "read_memory",
        "description": "Read a workspace-scoped memory note from the CoupledL2 run workspace.",
        "parameters": {
            "type": "object",
            "properties": {
                "name": {"type": "string", "description": "Memory note name", "default": "project.md"},
            },
        },
    },
    {
        "name": "write_memory",
        "description": "Write or append a workspace-scoped memory note inside the CoupledL2 run workspace.",
        "parameters": {
            "type": "object",
            "properties": {
                "name": {"type": "string", "description": "Memory note name"},
                "content": {"type": "string", "description": "Markdown content"},
                "append": {"type": "boolean", "description": "Append instead of replacing", "default": False},
            },
            "required": ["name", "content"],
        },
    },
]


EDIT_FILE_TOOL = {
    "name": "edit_file",
    "description": "Edit one workspace file with a focused operation. Prefer replace_text, replace_range, insert_before/after, or apply_patch over replacing entire files.",
    "parameters": {
        "type": "object",
        "properties": add_complete_params({
            "file_path": {
                "type": "string",
                "description": "Workspace-relative file path. Absolute paths and paths escaping the workspace are rejected.",
            },
            "operation": {
                "type": "string",
                "enum": [
                    "replace_file",
                    "apply_patch",
                    "replace_range",
                    "replace_text",
                    "insert_after",
                    "insert_before",
                    "delete_range",
                ],
                "description": "Focused edit operation to apply.",
            },
            "content": {
                "type": "string",
                "description": "Full replacement content for replace_file, inserted content for insert operations, or unified diff for apply_patch.",
            },
            "old_text": {
                "type": "string",
                "description": "Unique text to replace, or anchor text for insert_before/insert_after.",
            },
            "new_text": {
                "type": "string",
                "description": "Replacement text for replace_text.",
            },
            "line_start": {
                "type": "integer",
                "description": "1-based start line for replace_range/delete_range, or anchor line for insert_after/insert_before.",
            },
            "line_end": {
                "type": "integer",
                "description": "1-based inclusive end line for replace_range/delete_range.",
            },
            "reason": {
                "type": "string",
                "description": "Brief reason for the edit.",
            },
        }),
        "required": ["file_path", "operation", "reason"],
    },
}


COMPLETE_STAGE_TOOL = {
    "name": "complete_stage",
    "description": "Declare the current stage ready for deterministic workflow validation. Use this after required files or reports have been written and evidence has been collected.",
    "parameters": {
        "type": "object",
        "properties": {
            "summary": {
                "type": "string",
                "description": "Compact stage summary grounded in tool evidence.",
            },
            "evidence": {
                "type": "array",
                "items": {"type": "string"},
                "description": "Artifact paths, tool observations, or file references supporting completion.",
            },
            "verification_passed": {
                "type": "boolean",
                "description": "Whether the stage result indicates verification passed when relevant.",
            },
            "counterexample_path": {
                "type": "string",
                "description": "Counterexample waveform path when relevant.",
            },
            "root_cause": {
                "type": "string",
                "description": "Root cause or diagnosis when relevant.",
            },
            "files_modified": {
                "type": "array",
                "items": {"type": "string"},
                "description": "Workspace-relative files modified during the stage.",
            },
            "error_type": {
                "type": "string",
                "enum": ["dut_bug", "assertion_error", "setup_error", "inconclusive"],
                "description": "Failure or repair category when relevant.",
            },
            "target_assertion_label": {
                "type": "string",
                "description": "Original failing assertion/property label handled by a repair stage.",
            },
            "homologous_assertions": {
                "type": "array",
                "items": {"type": "string"},
                "description": "Structurally identical assertions inspected or repaired with the target assertion.",
            },
        },
        "required": ["summary", "evidence"],
    },
}


COUPLEDL2_WRITE_REPORT_TOOL = {
    "name": "write_report",
    "description": "Write the counterexample analysis report to counterexample_analysis.md in the current CoupledL2 case workspace.",
    "parameters": {
        "type": "object",
        "properties": {
            "content": {
                "type": "string",
                "description": "Markdown content of the analysis report"
            },
            "error_type": {
                "type": "string",
                "enum": ["dut_bug", "assertion_error", "setup_error", "inconclusive"],
                "description": "Type of error found in counterexample."
            }
        },
        "required": ["content"]
    }
}


# Reset stage tool - only for benchmark build_top_module
RESET_STAGE_TOOL = {
    "name": "reset_stage",
    "description": "Reset the stage to its initial state, restoring all files to their original content. Use this when you realize your previous outputs have fundamental issues that require starting fresh (e.g., wrong module structure, incorrect assumptions, or misunderstanding of requirements).",
    "parameters": {
        "type": "object",
        "properties": {
            "reason": {
                "type": "string",
                "description": "Detailed explanation of what was wrong with the previous output and why a reset is needed. This helps avoid making the same mistakes again."
            },
            "issues_identified": {
                "type": "array",
                "items": {"type": "string"},
                "description": "List of specific issues in the previous output that led to the need for reset"
            }
        },
        "required": ["reason", "issues_identified"]
    }
}

BUILD_TOP_TOOL_SCHEMAS = [
    {
        "name": "confirm_existing_harness",
        "description": "Confirm that the existing verification harness is correct and complete. Use this INSTEAD of rewriting when a harness class/object designed for generating Verilog already exists and appears correct.",
        "parameters": {
            "type": "object",
            "properties": add_complete_params({
                "harness_file": {
                    "type": "string",
                    "description": "Existing harness source file. Prefer the basename shown in the source manifest, e.g. 'arbiter.scala'; paths are resolved against the benchmark work directory."
                },
                "analysis": {
                    "type": "string",
                    "description": "Brief analysis of why the existing harness is correct (e.g., 'Proper module instantiation, ready for assertions')"
                }
            }),
            "required": ["harness_file", "analysis", "stage_complete"]
        }
    },
    {
        "name": "write_file",
        "description": "Write or modify a Chisel/Scala source file in the extra_bench directory. Use this ONLY if no suitable verification harness exists or it has errors that need fixing.",
        "parameters": {
            "type": "object",
            "properties": add_complete_params({
                "file_path": {
                    "type": "string",
                    "description": "Source file to write. Prefer a basename within the benchmark work directory, e.g. 'TestHarness.scala'; paths are resolved independent of the shell cwd."
                },
                "content": {
                    "type": "string",
                    "description": "Complete Scala source code for the file"
                }
            }),
            "required": ["file_path", "content"]
        }
    },
    create_read_files_tool("Read source files to understand the existing design. The prompt only includes a source manifest, so read exact files before confirming or modifying code. Use line_start/line_end when possible."),
    RESET_STAGE_TOOL
]

WRITE_ASSERTIONS_TOOL_SCHEMAS = [
    {
        "name": "write_assertions",
        "description": "Add formal verification assertions directly inside the existing DUT module/class emitted by VerilogGenerator. Use ChiselFV APIs or Chisel LTL assertions; do not create a standalone *Formal wrapper/sibling module whose assertions are not emitted.",
        "parameters": {
            "type": "object",
            "properties": add_complete_params({
                "file_path": {
                    "type": "string",
                    "description": "Existing emitted DUT source file to modify. Prefer the basename shown in the source manifest, e.g. 'arbiter.scala'; paths are resolved against the benchmark work directory."
                },
                "content": {
                    "type": "string",
                    "description": "Complete Scala source code with assertions placed in the original emitted DUT module/class"
                }
            }),
            "required": ["file_path", "content"]
        }
    },
    create_read_files_tool("Read source files to understand signal structure for assertion writing")
]

WAVEFORM_EXPLANATION_TOOL_SCHEMAS = (
    WAVEFORM_TOOLS + 
    CAUSAL_TOOLS +
    [create_read_files_tool("Read source files to correlate with waveform signals")] +
    [WRITE_REPORT_TOOL]
)

PROPOSE_BUGFIX_TOOL_SCHEMAS = [
    create_read_files_tool("Read source files to understand the code that needs fixing"),
    {
        "name": "write_fix",
        "description": "Write the fixed source file in the extra_bench directory.",
        "parameters": {
            "type": "object",
            "properties": add_complete_params({
                "file_path": {
                    "type": "string",
                    "description": "Source file to fix. Prefer the basename shown in the source manifest; paths are resolved against the benchmark work directory, independent of the shell cwd."
                },
                "content": {
                    "type": "string",
                    "description": "Complete fixed source code"
                },
                "round_summary": {
                    "type": "string",
                    "description": "Compact repair-round summary required when stage_complete=true. Include failing property handled, error category, root cause, files/lines changed, expected effect, and any assertion-label preservation or homologous-assertion work."
                },
                "error_type": {
                    "type": "string",
                    "enum": ["dut_bug", "assertion_error", "setup_error", "inconclusive"],
                    "description": "Repair category for this round. Use assertion_error when fixing generated/source assertions rather than DUT behavior."
                },
                "target_assertion_label": {
                    "type": "string",
                    "description": "Original failing assertion/property label handled in this round. Preserve this exact label in the repaired source unless the issue is explicitly only a setup/name mapping bug."
                },
                "homologous_assertions": {
                    "type": "array",
                    "items": {"type": "string"},
                    "description": "For assertion_error repairs, list all structurally identical assertions found and repaired in the same way. Use an empty list only after inspecting the source and finding no homologous assertions."
                }
            }),
            "required": ["file_path", "content"]
        }
    }
]


def _completion_capable(schema: Dict[str, Any]) -> bool:
    properties = schema.get("parameters", {}).get("properties", {})
    return "stage_complete" in properties


def _register_many(
    registry: ToolRegistry,
    schemas: Iterable[Dict[str, Any]],
    stages: Iterable[str],
    *,
    write_policy: str = "read_only",
    audit_level: str = "standard",
    workspace_only: bool = False,
) -> None:
    allowed_stages = set(stages)
    for schema in schemas:
        registry.register(
            ToolSpec(
                name=schema["name"],
                schema=schema,
                allowed_stages=allowed_stages,
                write_policy=write_policy,
                completion_capability=_completion_capable(schema),
                audit_level=audit_level,
                workspace_only=workspace_only,
            )
        )


def get_default_tool_registry() -> ToolRegistry:
    """Build the default stage-aware registry for formal workflow tools."""
    registry = ToolRegistry()
    all_stages = set(FORMAL_STAGES)
    source_write_stages = {"build_top_module", "write_assertions", "propose_bugfix"}

    _register_many(
        registry,
        WORKSPACE_CONTEXT_TOOLS,
        all_stages,
        workspace_only=True,
    )
    _register_many(
        registry,
        [EDIT_FILE_TOOL],
        source_write_stages,
        write_policy="workspace_source",
        audit_level="diff",
        workspace_only=True,
    )
    _register_many(
        registry,
        BUILD_TOP_TOOL_SCHEMAS,
        {"build_top_module"},
        write_policy="source_file",
        audit_level="diff",
    )
    _register_many(
        registry,
        WRITE_ASSERTIONS_TOOL_SCHEMAS,
        {"write_assertions"},
        write_policy="source_file",
        audit_level="diff",
    )
    _register_many(
        registry,
        WAVEFORM_EXPLANATION_TOOL_SCHEMAS,
        {"waveform_explanation"},
        audit_level="evidence",
    )
    _register_many(
        registry,
        PROPOSE_BUGFIX_TOOL_SCHEMAS,
        {"propose_bugfix"},
        write_policy="source_file",
        audit_level="diff",
    )
    return registry


def get_coupledl2_tool_schemas(formal_stage: str = "build_top_module") -> List[Dict[str, Any]]:
    """Return CoupledL2-only tools without legacy benchmark write surfaces."""
    stage = formal_stage if formal_stage in FORMAL_STAGES else "build_top_module"
    schemas: List[Dict[str, Any]] = []
    source_write_stages = {"build_top_module", "write_assertions", "propose_bugfix"}

    schemas.extend(deepcopy(WORKSPACE_CONTEXT_TOOLS))
    schemas.append(create_coupledl2_read_files_tool())

    if stage in source_write_stages:
        schemas.append(deepcopy(EDIT_FILE_TOOL))

    if stage == "waveform_explanation":
        schemas.extend(deepcopy(WAVEFORM_TOOLS))
        schemas.extend(deepcopy(CAUSAL_TOOLS))
        schemas.append(deepcopy(COUPLEDL2_WRITE_REPORT_TOOL))

    if stage != "invoke_verification":
        schemas.append(deepcopy(COMPLETE_STAGE_TOOL))

    return schemas


def get_tool_schemas(
    formal_stage: str = "build_top_module",
    target: Optional[str] = None,
    coupledl2: bool = False,
) -> List[Dict[str, Any]]:
    """
    Get the appropriate tool schemas for a formal verification stage.
    
    Args:
        formal_stage: Which stage we're in ('build_top_module', 'write_assertions', 
                     'waveform_explanation', 'propose_bugfix')
        target: Verification target (benchmark name like 'gigamax')
        
    Returns:
        List of tool schema dictionaries
    """
    if coupledl2:
        return get_coupledl2_tool_schemas(formal_stage)

    stage = formal_stage if formal_stage in FORMAL_STAGES else "build_top_module"
    return get_default_tool_registry().get_tool_schemas(stage, include_workspace=coupledl2)


def convert_tool_call_to_action(tool_name: str, tool_args: Dict[str, Any]) -> Dict[str, Any]:
    """
    Convert a function call (tool name + arguments) to action format.
    
    Args:
        tool_name: Name of the tool/function being called
        tool_args: Arguments passed to the tool
        
    Returns:
        Action dictionary in the format expected by execute_actions
    """
    action = {"type": tool_name}
    action.update(tool_args)
    return action
