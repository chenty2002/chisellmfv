"""
CoupledL2 formal workflow tool schemas.

The formal workflow exposes only workspace-scoped CoupledL2 tools for stages 2-5.
"""

from copy import deepcopy
from dataclasses import dataclass
from typing import Any, Dict, Iterable, List, Optional, Set

from .budget import BudgetPhase
from ..coupledl2.stages import COUPLEDL2_STAGES


FORMAL_STAGES = COUPLEDL2_STAGES


@dataclass(frozen=True)
class ToolSpec:
    """Registered tool schema plus workflow policy metadata."""

    name: str
    schema: Dict[str, Any]
    allowed_stages: Set[str]
    write_policy: str = "read_only"
    completion_capability: bool = False
    audit_level: str = "standard"
    workspace_only: bool = True


class ToolRegistry:
    """Stage-aware registry for CoupledL2 workflow tool schemas."""

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

    def get_stage_specs(self, stage: str, *, include_workspace: bool = True) -> List[ToolSpec]:
        return [spec for spec in self._ordered_specs if stage in spec.allowed_stages]

    def get_tool_schemas(self, stage: str, *, include_workspace: bool = True) -> List[Dict[str, Any]]:
        return [deepcopy(spec.schema) for spec in self.get_stage_specs(stage)]


WORKSPACE_CONTEXT_TOOLS = [
    {
        "name": "list_files",
        "description": "List a bounded workspace directory view. Default discovery is shallow and excludes caches, build outputs, and generated RTL; explicitly naming a generated/build directory permits bounded inspection.",
        "parameters": {
            "type": "object",
            "properties": {
                "path": {"type": "string", "description": "Workspace-relative directory or file path", "default": "."},
                "pattern": {"type": "string", "description": "Glob pattern for files", "default": "*"},
                "recursive": {
                    "type": "boolean",
                    "description": "Recursively descend, subject to max_depth and path policy",
                    "default": False,
                },
                "max_depth": {
                    "type": "integer",
                    "description": "Maximum traversal depth from path",
                    "minimum": 1,
                    "maximum": 6,
                    "default": 1,
                },
                "max_entries": {
                    "type": "integer",
                    "description": "Maximum file and directory entries returned",
                    "minimum": 1,
                    "maximum": 500,
                    "default": 200,
                },
                "after": {
                    "type": ["string", "null"],
                    "description": "Stable path cursor from a previous truncated response",
                    "default": None,
                },
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


READ_FILES_TOOL = {
    "name": "read_files",
    "description": "Read source or artifact files inside the CoupledL2 run workspace. Use either file_paths with shared bounds or up to four independent slices, never both.",
    "parameters": {
        "type": "object",
        "properties": {
            "file_paths": {
                "type": "array",
                "items": {"type": "string"},
                "description": "Workspace-relative files to read, such as case/Chisel/src/test/scala/coupledl2/VerifyTop.scala or results/by_stage/03_invoke_verification/formal_result.json.",
            },
            "slices": {
                "type": "array",
                "minItems": 1,
                "maxItems": 4,
                "items": {
                    "type": "object",
                    "properties": {
                        "path": {
                            "type": "string",
                            "description": "Workspace-relative concrete file path",
                        },
                        "line_start": {
                            "type": "integer",
                            "minimum": 1,
                            "description": "Optional 1-based first line",
                        },
                        "line_end": {
                            "type": "integer",
                            "minimum": 1,
                            "description": "Optional 1-based last line",
                        },
                        "max_chars": {
                            "type": "integer",
                            "minimum": 1,
                            "maximum": 12000,
                            "description": "Maximum returned characters for this slice",
                        },
                    },
                    "required": ["path"],
                },
                "description": "Independent bounded source slices; mutually exclusive with file_paths",
            },
            "line_start": {
                "type": "integer",
                "description": "Optional 1-based first line to read from each file",
            },
            "line_end": {
                "type": "integer",
                "description": "Optional 1-based last line to read from each file",
            },
            "max_chars": {
                "type": "integer",
                "description": "Optional maximum characters to return per file after line filtering. Omit for the workflow default bounded preview; use line_start/line_end for precise source inspection.",
            },
            "reason": {
                "type": "string",
                "description": "Reason for reading these files",
            },
        },
        "required": ["reason"],
        "oneOf": [
            {"required": ["file_paths"]},
            {"required": ["slices"]},
        ],
    },
}


EDIT_FILE_TOOL = {
    "name": "edit_file",
    "description": "Edit one workspace file with a focused operation. Prefer replace_text, replace_range, insert_before/after, or apply_patch over replacing entire files.",
    "parameters": {
        "type": "object",
        "properties": {
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
        },
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
                "enum": [
                    "design_bug", "property_schema_error", "template_error",
                    "binding_error", "environment_error", "assumption_error",
                    "inconclusive",
                ],
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


WAVEFORM_TOOLS = [
    {
        "name": "waveform_find_signals",
        "description": "Find signals in the waveform matching a pattern",
        "parameters": {
            "type": "object",
            "properties": {
                "pattern": {"type": "string", "description": "Pattern to search for in signal names"},
                "regex": {"type": "boolean", "description": "Whether the pattern is a regex", "default": False},
                "max_results": {"type": "integer", "description": "Maximum number of results to return", "default": 50},
            },
            "required": ["pattern"],
        },
    },
    {
        "name": "waveform_get_signal_value",
        "description": "Get values of one or more exact waveform signals at corresponding time points.",
        "parameters": {
            "type": "object",
            "properties": {
                "signal_names": {"type": "array", "items": {"type": "string"}, "description": "Full hierarchical signal names to query"},
                "times": {"type": "array", "items": {"type": "integer"}, "description": "Time points corresponding to each signal"},
            },
            "required": ["signal_names", "times"],
        },
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
                "max_changes": {"type": "integer", "description": "Maximum changes to return", "default": 100},
            },
            "required": ["signal_name"],
        },
    },
    {
        "name": "waveform_get_active_signals",
        "description": "Get all non-zero signals at a specific time point",
        "parameters": {
            "type": "object",
            "properties": {
                "time": {"type": "integer", "description": "Time point to query"},
                "scope_pattern": {"type": "string", "description": "Optional pattern to filter by scope"},
                "max_results": {"type": "integer", "description": "Maximum results", "default": 100},
            },
            "required": ["time"],
        },
    },
    {
        "name": "waveform_compare_signals",
        "description": "Compare multiple signals at multiple time points",
        "parameters": {
            "type": "object",
            "properties": {
                "signal_names": {"type": "array", "items": {"type": "string"}, "description": "List of signal names"},
                "times": {"type": "array", "items": {"type": "integer"}, "description": "List of time points"},
            },
            "required": ["signal_names", "times"],
        },
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
                "max_matches": {"type": "integer", "description": "Maximum transitions", "default": 50},
            },
            "required": ["signal_name", "from_value", "to_value"],
        },
    },
]


CAUSAL_TOOLS = [
    {
        "name": "causal_get_roots",
        "description": "Query structured VerilogCausalAnalysis JSON for ranked root-cause candidate nodes.",
        "parameters": {
            "type": "object",
            "properties": {
                "limit": {"type": "integer", "description": "Maximum number of root candidates to return", "default": 10},
                "min_score": {"type": "number", "description": "Optional minimum suspect score filter"},
            },
        },
    },
    {
        "name": "causal_trace_path",
        "description": "Trace causal paths in the structured causal DAG.",
        "parameters": {
            "type": "object",
            "properties": {
                "source_node_id": {"type": "string", "description": "Optional source/root node id"},
                "target_node_id": {"type": "string", "description": "Optional destination node id"},
                "signal": {"type": "string", "description": "Optional signal-name substring used to choose candidate source nodes"},
                "max_depth": {"type": "integer", "description": "Maximum path depth to traverse", "default": 12},
                "max_paths": {"type": "integer", "description": "Maximum number of paths to return", "default": 5},
            },
        },
    },
    {
        "name": "causal_get_node_evidence",
        "description": "Get detailed node evidence from VerilogCausalAnalysis JSON.",
        "parameters": {
            "type": "object",
            "properties": {
                "node_id": {"type": "string", "description": "Exact causal node id"},
                "signal": {"type": "string", "description": "Signal-name substring to find a node when node_id is unknown"},
                "cycle": {"type": "integer", "description": "Optional cycle filter when searching by signal"},
            },
        },
    },
]


WRITE_REPORT_TOOL = {
    "name": "write_report",
    "description": "Write the counterexample analysis report to counterexample_analysis.md in the current CoupledL2 case workspace.",
    "parameters": {
        "type": "object",
        "properties": {
            "content": {
                "type": "string",
                "description": "Markdown content of the analysis report",
            },
            "error_type": {
                "type": "string",
                "enum": [
                    "design_bug", "property_schema_error", "template_error",
                    "binding_error", "environment_error", "assumption_error",
                    "inconclusive",
                ],
                "description": "Type of error found in counterexample.",
            },
        },
        "required": ["content"],
    },
}


SUBMIT_PROPERTY_DIAGNOSES_TOOL = {
    "name": "submit_property_diagnoses",
    "description": (
        "Submit one complete diagnosis array covering every CEX primary property. "
        "Each property must have its own conclusion even when evidence is shared."
    ),
    "parameters": {
        "type": "object",
        "properties": {
            "diagnoses": {
                "type": "array",
                "minItems": 1,
                "items": {
                    "type": "object",
                    "additionalProperties": False,
                    "properties": {
                        "property": {"type": "string"},
                        "classification": {
                            "type": "string",
                            "enum": [
                                "design_bug", "property_schema_error", "template_error",
                                "binding_error", "environment_error", "assumption_error",
                                "inconclusive",
                            ],
                        },
                        "evidence_refs": {
                            "type": "array",
                            "minItems": 1,
                            "items": {"type": "string"},
                        },
                        "uncertainty": {"type": "string"},
                        "revision_target": {
                            "type": "string",
                            "enum": [
                                "design_source", "property_schema", "assertion_template",
                                "binding_manifest", "formal_contract", "assumptions", "none",
                            ],
                        },
                    },
                    "required": [
                        "property", "classification", "evidence_refs",
                        "uncertainty", "revision_target",
                    ],
                },
            },
            "summary": {"type": "string"},
        },
        "required": ["diagnoses"],
        "additionalProperties": False,
    },
}


def _completion_capable(schema: Dict[str, Any]) -> bool:
    return schema["name"] == "complete_stage"


def _register_many(
    registry: ToolRegistry,
    schemas: Iterable[Dict[str, Any]],
    stages: Iterable[str],
    *,
    write_policy: str = "read_only",
    audit_level: str = "standard",
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
            )
        )


def get_default_tool_registry() -> ToolRegistry:
    """Build the stage-aware registry for CoupledL2 workflow tools."""
    registry = ToolRegistry()
    agent_stages = {"waveform_explanation", "propose_bugfix"}

    _register_many(registry, WORKSPACE_CONTEXT_TOOLS, agent_stages)
    _register_many(registry, [READ_FILES_TOOL], agent_stages)
    _register_many(
        registry,
        [EDIT_FILE_TOOL],
        {"propose_bugfix"},
        write_policy="workspace_source",
        audit_level="diff",
    )
    _register_many(
        registry,
        WAVEFORM_TOOLS + CAUSAL_TOOLS + [WRITE_REPORT_TOOL, SUBMIT_PROPERTY_DIAGNOSES_TOOL],
        {"waveform_explanation"},
        audit_level="evidence",
    )
    _register_many(
        registry,
        [COMPLETE_STAGE_TOOL],
        agent_stages,
        audit_level="completion",
    )
    return registry


def get_coupledl2_tool_schemas(formal_stage: str = "waveform_explanation") -> List[Dict[str, Any]]:
    """Return CoupledL2-only tools."""
    if formal_stage in {"bind_properties", "invoke_verification"}:
        return []
    stage = formal_stage if formal_stage in FORMAL_STAGES else "waveform_explanation"
    return get_default_tool_registry().get_tool_schemas(stage)


def get_budgeted_tool_schemas(
    formal_stage: str,
    *,
    phase: BudgetPhase,
    tool_calls_remaining: int,
    forced_finalization: bool = False,
    completion_required: bool = False,
    repair_edit_required: bool = False,
    discovery_calls_remaining: Optional[int] = None,
) -> List[Dict[str, Any]]:
    """Return the runtime tool surface allowed by the current budget phase."""
    schemas = get_coupledl2_tool_schemas(formal_stage)
    if forced_finalization or completion_required or tool_calls_remaining <= 1:
        allowed = {"complete_stage"}
    elif repair_edit_required:
        allowed = {"edit_file"}
    elif phase is BudgetPhase.DISCOVERY:
        return schemas
    elif formal_stage == "propose_bugfix":
        allowed = (
            {"read_files", "edit_file", "complete_stage"}
            if phase is BudgetPhase.EXECUTION
            else {"edit_file", "complete_stage"}
        )
    elif formal_stage == "waveform_explanation":
        evidence_tools = {
            schema["name"]
            for schema in WAVEFORM_TOOLS + CAUSAL_TOOLS
        }
        evidence_tools.add("submit_property_diagnoses")
        allowed = (
            evidence_tools | {"read_files", "write_report", "complete_stage"}
            if phase is BudgetPhase.EXECUTION
            else {"submit_property_diagnoses", "write_report", "complete_stage"}
        )
    else:
        allowed = set()
    return [schema for schema in schemas if schema["name"] in allowed]


def get_tool_schemas(
    formal_stage: str = "waveform_explanation",
    target: Optional[str] = None,
    coupledl2: bool = False,
) -> List[Dict[str, Any]]:
    """
    Get CoupledL2 tool schemas for a formal verification stage.

    `target` and `coupledl2` are accepted only to keep call sites simple; the
    formal workflow no longer exposes any non-CoupledL2 tool surface.
    """
    return get_coupledl2_tool_schemas(formal_stage)


def convert_tool_call_to_action(tool_name: str, tool_args: Dict[str, Any]) -> Dict[str, Any]:
    """Convert a tool call into the action dictionary used by execution."""
    action = {"type": tool_name}
    action.update(tool_args)
    return action
