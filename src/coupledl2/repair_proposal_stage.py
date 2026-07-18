"""Stage 5 run-local repair proposal generation without source mutation."""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any, Dict, Optional

from ..core.artifact_contract import write_stage_outcome
from .stages import get_stage_spec
from .workspace import (
    StageContext,
    build_binding_source_snippets,
    initialize_stage_context,
)


class RepairProposalError(ValueError):
    """Raised when the proposal model violates the bounded Stage 5 contract."""


class RepairProposalStage:
    def __init__(
        self,
        workspace: Any,
        llm_client: Any,
        logger: Optional[Any],
        stage_context: Optional[StageContext] = None,
    ) -> None:
        self.workspace = workspace
        self.llm_client = llm_client
        self.logger = logger
        self.context = stage_context or initialize_stage_context(
            workspace, "propose_bugfix"
        )
        self.stage_dir = self.context.stage_dir

    def run(self) -> Dict[str, Any]:
        try:
            response = self.llm_client.chat_with_tools(
                messages=self._messages(),
                tools=[_proposal_tool()],
                tool_choice={
                    "type": "function",
                    "function": {"name": "submit_repair_proposal"},
                },
                max_tokens=4096,
                temperature=0,
                enable_thinking=False,
                parallel_tool_calls=False,
                stage="propose_bugfix",
                usage_metadata={"stage": "propose_bugfix"},
            )
            calls = response.get("function_calls") if isinstance(response, dict) else None
            if (
                not isinstance(response, dict)
                or response.get("type") != "function_calls"
                or not isinstance(calls, list)
                or len(calls) != 1
                or calls[0].get("name") != "submit_repair_proposal"
                or not isinstance(calls[0].get("arguments"), dict)
            ):
                raise RepairProposalError(
                    "repair model must call submit_repair_proposal exactly once"
                )
            proposal = _validate_proposal(calls[0]["arguments"])
            _write_json(self.stage_dir / "repair_proposal.json", proposal)
            patch_text = "\n".join(item["diff"].rstrip() for item in proposal["patches"])
            (self.stage_dir / "repair_proposal.patch").write_text(
                patch_text + "\n", encoding="utf-8"
            )
            result = write_stage_outcome(
                self.stage_dir,
                get_stage_spec("propose_bugfix"),
                {
                    "schema_version": "stage_result.v2",
                    "stage": "propose_bugfix",
                    "success": True,
                    "termination_reason": "repair_proposal_created",
                    "model_calls": 1,
                    "repository_source_modified": False,
                    "proposal_path": "repair_proposal.json",
                    "patch_path": "repair_proposal.patch",
                },
            )
            return {"success": True, "stage_result": result}
        except Exception as exc:
            result = write_stage_outcome(
                self.stage_dir,
                get_stage_spec("propose_bugfix"),
                {
                    "schema_version": "stage_result.v2",
                    "stage": "propose_bugfix",
                    "success": False,
                    "termination_reason": "repair_proposal_failed",
                    "error_kind": type(exc).__name__,
                    "error": str(exc),
                },
            )
            return {"success": False, "stage_result": result}

    def _messages(self) -> list[Dict[str, str]]:
        inputs: Dict[str, Any] = {"stage_inputs": self.context.stage_inputs}
        for key, relative in (
            ("diagnosis", "04_waveform_explanation/diagnosis.json"),
            ("diagnosis_evidence", "04_waveform_explanation/diagnosis_evidence.json"),
            ("property_package", "02_bind_properties/property_package.json"),
            ("property_result_map", "03_invoke_verification/property_result_map.json"),
        ):
            path = self.workspace.results_dir / "by_stage" / relative
            if path.is_file():
                inputs[key] = json.loads(path.read_text(encoding="utf-8"))
        if isinstance(inputs.get("property_package"), dict):
            inputs["source_snippets"] = build_binding_source_snippets(
                self.workspace, inputs["property_package"]
            )
        return [
            {
                "role": "system",
                "content": (
                    "Generate one run-local design repair proposal from the supplied "
                    "Codex-approved property package and deterministic CEX evidence. "
                    "Do not claim the patch was applied or verified. Use only workspace/case "
                    "source paths and unified diffs; do not revise property assets."
                ),
            },
            {"role": "user", "content": json.dumps(inputs, ensure_ascii=False)},
        ]


def _proposal_tool() -> Dict[str, Any]:
    return {
        "name": "submit_repair_proposal",
        "description": "Submit a run-local, unapplied design patch proposal.",
        "strict": True,
        "parameters": {
            "type": "object",
            "additionalProperties": False,
            "required": [
                "schema_version", "summary", "diagnosis_refs", "evidence_refs",
                "patches",
            ],
            "properties": {
                "schema_version": {"type": "string", "const": "repair_proposal.v1"},
                "summary": {"type": "string", "minLength": 1, "maxLength": 1200},
                "diagnosis_refs": {
                    "type": "array", "minItems": 1, "maxItems": 8,
                    "items": {"type": "string"},
                },
                "evidence_refs": {
                    "type": "array", "minItems": 1, "maxItems": 16,
                    "items": {"type": "string"},
                },
                "patches": {
                    "type": "array", "minItems": 1, "maxItems": 4,
                    "items": {
                        "type": "object",
                        "additionalProperties": False,
                        "required": ["path", "diff", "rationale"],
                        "properties": {
                            "path": {"type": "string"},
                            "diff": {"type": "string", "minLength": 1},
                            "rationale": {"type": "string", "minLength": 1},
                        },
                    },
                },
            },
        },
    }


def _validate_proposal(value: Dict[str, Any]) -> Dict[str, Any]:
    required = {
        "schema_version", "summary", "diagnosis_refs", "evidence_refs", "patches"
    }
    if not isinstance(value, dict) or set(value) != required:
        raise RepairProposalError("repair proposal fields do not match the contract")
    if value["schema_version"] != "repair_proposal.v1":
        raise RepairProposalError("unsupported repair proposal schema")
    if not value["diagnosis_refs"] or not value["evidence_refs"]:
        raise RepairProposalError("repair proposal requires diagnosis and evidence refs")
    patches = value.get("patches")
    if not isinstance(patches, list) or not 1 <= len(patches) <= 4:
        raise RepairProposalError("repair proposal requires one to four patches")
    for item in patches:
        if not isinstance(item, dict) or set(item) != {"path", "diff", "rationale"}:
            raise RepairProposalError("invalid patch proposal fields")
        path = Path(str(item["path"]))
        if (
            path.is_absolute()
            or ".." in path.parts
            or len(path.parts) < 3
            or path.parts[:2] != ("workspace", "case")
            or path.suffix not in {".scala", ".sv", ".v"}
        ):
            raise RepairProposalError("proposal path must name workspace/case design source")
        diff = str(item["diff"])
        if not diff.startswith("--- ") or "\n+++ " not in diff or "\n@@" not in diff:
            raise RepairProposalError("proposal patch must be a unified diff")
        if "src/coupledl2/property_assets" in diff:
            raise RepairProposalError("repair proposal cannot revise property assets")
    return value


def _write_json(path: Path, value: Dict[str, Any]) -> None:
    path.write_text(
        json.dumps(value, indent=2, ensure_ascii=False, sort_keys=True) + "\n",
        encoding="utf-8",
    )
