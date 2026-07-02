"""Own CoupledL2 run lifecycle, resume validation, and stage progression."""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any, Callable, Dict, Optional, Type

from .backend import CoupledL2BuildOperations
from .binding_stage import BindingStage
from .indexer import compute_index_hashes, compute_workspace_hash, refresh_indexes
from .preflight import CoupledL2Preflight
from .stages import COUPLEDL2_STAGES, get_stage_spec
from .workspace import CoupledL2Workspace
from ..core.records import (
    build_run_cost_summary,
    merge_run_cost_summaries,
    normalize_stage_result,
)


class CoupledL2Runner:
    """Advance one preflighted workspace without ever creating a replacement."""

    def __init__(
        self,
        *,
        workspace: CoupledL2Workspace,
        logger: Any,
        llm_client: Optional[Any],
        workflow_cls: Optional[Type[Any]] = None,
        query_for_stage: Optional[Callable[[str, str], str]] = None,
        max_repair_rounds: int = 3,
        resumed: bool = False,
    ):
        if workflow_cls is None:
            from ..core.workflow import FormalWorkflow

            workflow_cls = FormalWorkflow
        self.workspace = workspace
        self.logger = logger
        self.llm_client = llm_client
        self.workflow_cls = workflow_cls
        self.query_for_stage = query_for_stage or (
            lambda stage, case: f"Complete CoupledL2 stage {stage} for {case}."
        )
        self.max_repair_rounds = max(0, int(max_repair_rounds))
        self.resumed = resumed
        self.last_verification_result: Optional[Dict[str, Any]] = None
        cost_path = workspace.results_dir / "run_cost_summary.json"
        self.previous_cost_summary = (
            json.loads(cost_path.read_text(encoding="utf-8"))
            if resumed and cost_path.is_file()
            else None
        )
        self.compaction_offsets = {
            path: len(path.read_text(encoding="utf-8").splitlines())
            for path in (workspace.results_dir / "by_stage").glob(
                "*/context_compactions.jsonl"
            )
        }
        self.operation_offsets = {
            path: len(path.read_text(encoding="utf-8").splitlines())
            for path in (workspace.results_dir / "by_stage").glob(
                "*/operations.jsonl"
            )
        }

    def preflight(self) -> Dict[str, Any]:
        result = CoupledL2Preflight(
            self.workspace,
            CoupledL2BuildOperations(self.workspace, self.logger),
        ).run()
        if result.get("success"):
            refresh_indexes(self.workspace)
        return result

    def run(
        self,
        *,
        stage: Optional[str] = None,
        full: bool = False,
    ) -> Dict[str, Any]:
        if bool(stage) == bool(full):
            raise ValueError("select exactly one of stage or full")
        self._validate_preflight()
        if self.resumed:
            self._validate_workspace_integrity()
            self.last_verification_result = self._read_stage_result("invoke_verification")
        if not self.resumed and stage and stage != "bind_properties":
            raise ValueError("fresh run must start at bind_properties")

        stages = self._stages_to_run(stage=stage, full=full)
        completed_stage: Optional[str] = None
        success = True
        stage_results = []

        for current_stage in stages:
            self._validate_predecessor(current_stage)
            if current_stage == "bind_properties":
                result = BindingStage(
                    self.workspace,
                    CoupledL2BuildOperations(self.workspace, self.logger),
                    self.llm_client,
                    self.logger,
                ).run()
            else:
                waveform_path = self._counterexample_path() if current_stage == "waveform_explanation" else None
                workflow = self.workflow_cls(
                    llm_client=self.llm_client,
                    chisel_dir=".",
                    workspace_dir=str(self.workspace.run_dir),
                    logger=self.logger,
                    waveform_path=waveform_path,
                    stage=current_stage,
                    target=self.workspace.config.case_name,
                    max_repair_rounds=self.max_repair_rounds,
                    initial_verification_result=self.last_verification_result,
                    run_context=self.workspace,
                )
                result = workflow.process_task(
                    self.query_for_stage(current_stage, self.workspace.config.case_name)
                )
            completed_stage = current_stage
            detail = dict(result.get("stage_result") or {})
            detail.setdefault("stage", current_stage)
            stage_results.append(detail)
            success = bool(result.get("success"))
            if not success:
                break

            if current_stage in {"bind_properties", "propose_bugfix"}:
                state = refresh_indexes(self.workspace)
                self._bind_state_to_handoff(current_stage, state)

            if current_stage == "invoke_verification":
                self.last_verification_result = detail
                if detail.get("verification_passed"):
                    break
            if current_stage == "waveform_explanation":
                diagnoses = self._read_diagnoses()
                if not diagnoses_allow_bugfix(diagnoses):
                    self._write_revision_request(diagnoses)
                    break

        summary = {
            "schema_version": "coupledl2_run_result.v1",
            "run_dir": str(self.workspace.run_dir),
            "success": success,
            "completed_stage": completed_stage,
            "resumed": self.resumed,
        }
        self._write_cost_summary(stage_results)
        self._write_final_result(summary, stage_results)
        return summary

    def _stages_to_run(self, *, stage: Optional[str], full: bool) -> list[str]:
        if stage:
            get_stage_spec(stage)
            return [stage]
        if self.resumed:
            raise ValueError("resumed run requires an explicit stage")
        return list(COUPLEDL2_STAGES)

    def _validate_preflight(self) -> None:
        manifest = self._manifest()
        if manifest.get("preflight_status") != "success":
            raise ValueError("CoupledL2 runner requires successful preflight")

    def _validate_workspace_integrity(self) -> None:
        expected = self._manifest().get("workspace_hash")
        if not expected:
            raise ValueError("resume manifest has no workspace_hash")
        actual = compute_workspace_hash(self.workspace.case_workspace)
        if actual != expected:
            raise ValueError("resume workspace hash does not match manifest")
        expected_indexes = self._manifest().get("index_hashes")
        if not expected_indexes:
            raise ValueError("resume manifest has no index_hashes")
        if compute_index_hashes(self.workspace.indexes_dir) != expected_indexes:
            raise ValueError("resume index hashes do not match manifest")

    def _validate_predecessor(self, stage: str) -> None:
        predecessor = get_stage_spec(stage).required_predecessor
        if predecessor == "preflight":
            return
        handoff = self._read_handoff(predecessor)
        if not handoff or handoff.get("success") is not True:
            raise ValueError(f"{stage} requires a successful {predecessor} handoff")
        if stage == "waveform_explanation":
            verification = handoff.get("verification") or {}
            if verification.get("verification_passed") is True:
                raise ValueError("waveform_explanation cannot run after all properties were proven")
            if not verification.get("counterexample_path"):
                raise ValueError("waveform_explanation requires a counterexample path")
        if stage == "propose_bugfix":
            diagnoses = self._read_diagnoses()
            if not diagnoses_allow_bugfix(diagnoses):
                raise ValueError(
                    "propose_bugfix requires every diagnosis to be design_bug"
                )

    def _counterexample_path(self) -> Optional[str]:
        handoff = self._read_handoff("invoke_verification") or {}
        value = (handoff.get("verification") or {}).get("counterexample_path")
        if not value:
            return None
        path = Path(value)
        return str(path if path.is_absolute() else self.workspace.run_dir / path)

    def _read_diagnoses(self) -> list[Dict[str, Any]]:
        path = (
            self.workspace.results_dir
            / "by_stage"
            / get_stage_spec("waveform_explanation").directory_name
            / "diagnosis.json"
        )
        if not path.is_file():
            return []
        payload = json.loads(path.read_text(encoding="utf-8"))
        diagnoses = payload.get("diagnoses")
        return diagnoses if isinstance(diagnoses, list) else []

    def _write_revision_request(self, diagnoses: list[Dict[str, Any]]) -> None:
        layer_by_classification = {
            "property_schema_error": "property_schema",
            "template_error": "assertion_template",
            "binding_error": "binding_manifest",
            "environment_error": "environment",
            "assumption_error": "assumptions",
            "inconclusive": "analysis",
        }
        requests = []
        for item in diagnoses:
            classification = item.get("classification")
            if classification == "design_bug":
                continue
            requests.append(
                {
                    "property": item.get("property"),
                    "jaspergold_property_id": item.get(
                        "jaspergold_property_id"
                    ),
                    "classification": classification,
                    "asset_layer": layer_by_classification.get(
                        classification,
                        "analysis",
                    ),
                    "evidence": item.get("evidence", []),
                }
            )
        path = (
            self.workspace.results_dir
            / "by_stage"
            / get_stage_spec("waveform_explanation").directory_name
            / "revision_request.json"
        )
        _write_json(
            path,
            {
                "schema_version": "revision_request.v1",
                "requests": requests,
            },
        )

    def _read_handoff(self, stage: str) -> Optional[Dict[str, Any]]:
        path = self._handoff_path(stage)
        if not path.is_file():
            return None
        payload = json.loads(path.read_text(encoding="utf-8"))
        if payload.get("schema_version") != "stage_handoff.v1" or payload.get("stage") != stage:
            raise ValueError(f"invalid {stage} handoff")
        return payload

    def _read_stage_result(self, stage: str) -> Optional[Dict[str, Any]]:
        path = self._handoff_path(stage).with_name("stage_result.json")
        if not path.is_file():
            return None
        payload = json.loads(path.read_text(encoding="utf-8"))
        if payload.get("stage") != stage:
            raise ValueError(f"invalid {stage} stage result")
        return payload

    def _bind_state_to_handoff(self, stage: str, state: Dict[str, Any]) -> None:
        path = self._handoff_path(stage)
        if not path.is_file():
            raise ValueError(f"successful {stage} did not write handoff.json")
        handoff = json.loads(path.read_text(encoding="utf-8"))
        handoff["workspace_hash"] = state["workspace_hash"]
        handoff["index_hashes"] = state["index_hashes"]
        _write_json(path, handoff)

    def _handoff_path(self, stage: str) -> Path:
        return (
            self.workspace.results_dir
            / "by_stage"
            / get_stage_spec(stage).directory_name
            / "handoff.json"
        )

    def _write_cost_summary(self, stage_results: list[Dict[str, Any]]) -> None:
        usage = (
            self.llm_client.get_token_usage()
            if self.llm_client is not None and hasattr(self.llm_client, "get_token_usage")
            else {}
        )
        compactions = []
        tool_results = []
        compaction_paths = (self.workspace.results_dir / "by_stage").glob(
            "*/context_compactions.jsonl"
        )
        for path in sorted(compaction_paths):
            lines = path.read_text(encoding="utf-8").splitlines()
            for line in lines[self.compaction_offsets.get(path, 0):]:
                if line.strip():
                    compactions.append(json.loads(line))
        operation_paths = (self.workspace.results_dir / "by_stage").glob(
            "*/operations.jsonl"
        )
        for path in sorted(operation_paths):
            lines = path.read_text(encoding="utf-8").splitlines()
            for line in lines[self.operation_offsets.get(path, 0):]:
                if line.strip():
                    item = json.loads(line)
                    if item.get("kind") == "tool_result":
                        tool_results.append(item)
        current = build_run_cost_summary(
            usage,
            stage_results=stage_results,
            compactions=compactions,
            tool_results=tool_results,
        )
        _write_json(
            self.workspace.results_dir / "run_cost_summary.json",
            merge_run_cost_summaries(self.previous_cost_summary, current),
        )

    def _write_final_result(
        self,
        summary: Dict[str, Any],
        stage_results: list[Dict[str, Any]],
    ) -> None:
        payload = dict(summary)
        payload["schema_version"] = "final_result.v2"
        persisted = {}
        for stage in COUPLEDL2_STAGES:
            result = self._read_stage_result(stage)
            if result:
                persisted[stage] = result
        for result in stage_results:
            if result.get("stage"):
                stage = str(result["stage"])
                persisted[stage] = normalize_stage_result(stage, result)
        payload["stage_results"] = [
            persisted[stage]
            for stage in COUPLEDL2_STAGES
            if stage in persisted
        ]
        _write_json(self.workspace.results_dir / "final_result.json", payload)

    def _manifest(self) -> Dict[str, Any]:
        return json.loads(self.workspace.manifest_path.read_text(encoding="utf-8"))


def _write_json(path: Path, value: Dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(value, indent=2, ensure_ascii=False, sort_keys=True) + "\n",
        encoding="utf-8",
    )


def diagnoses_allow_bugfix(diagnoses: list[Dict[str, Any]]) -> bool:
    """Stage 5 is reachable only for a non-empty all-design-bug diagnosis set."""
    return bool(diagnoses) and all(
        item.get("classification") == "design_bug" for item in diagnoses
    )
