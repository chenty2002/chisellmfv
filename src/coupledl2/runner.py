"""Own CoupledL2 run lifecycle, resume validation, and stage progression."""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any, Dict, Optional

from .backend import CoupledL2BuildOperations
from .binding_stage import BindingStage
from .indexer import compute_index_hashes, compute_workspace_hash, refresh_indexes
from .preflight import CoupledL2Preflight
from .stages import COUPLEDL2_STAGES, get_stage_spec
from .workspace import CoupledL2Workspace, initialize_stage_context
from ..core.artifact_contract import (
    file_sha256,
    validate_completed_stage,
    write_stage_outcome,
)
from .result_contract import (
    ResultContractError,
    build_semantic_evidence,
    validate_operation_plan,
    validate_property_result_map,
)
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
        resumed: bool = False,
    ):
        self.workspace = workspace
        self.logger = logger
        self.llm_client = llm_client
        self.resumed = resumed
        self.last_verification_result: Optional[Dict[str, Any]] = None
        cost_path = workspace.results_dir / "run_cost_summary.json"
        self.previous_cost_summary = (
            json.loads(cost_path.read_text(encoding="utf-8"))
            if resumed and cost_path.is_file()
            else None
        )

    def preflight(self) -> Dict[str, Any]:
        result = CoupledL2Preflight(
            self.workspace,
            CoupledL2BuildOperations(self.workspace, self.logger),
        ).run()
        if result.get("success"):
            refresh_indexes(self.workspace)
        return result

    def _run_deterministic_verification(self) -> Dict[str, Any]:
        """Execute Stage 3 directly; no generic agent loop or model call exists."""
        spec = get_stage_spec("invoke_verification")
        stage_dir = self.workspace.results_dir / "by_stage" / spec.directory_name
        backend_result = CoupledL2BuildOperations(
            self.workspace, self.logger
        ).run_full_verification_flow()
        property_map = backend_result.get("property_result_map")
        if not isinstance(property_map, dict):
            result = write_stage_outcome(
                stage_dir,
                spec,
                {
                    "schema_version": "stage_result",
                    "stage": spec.name,
                    "success": False,
                    "termination_reason": "deterministic_verification_failed",
                    "error_kind": "property_result_map_missing",
                    "summary": backend_result.get("summary"),
                    "model_calls": 0,
                },
            )
            return {"success": False, "stage_result": result}

        stage2_dir = self.workspace.results_dir / "by_stage" / get_stage_spec(
            "bind_properties"
        ).directory_name
        package = json.loads(
            (stage2_dir / "property_package.json").read_text(encoding="utf-8")
        )
        operation_plan = package.get("operation_plan")
        try:
            validate_operation_plan(operation_plan)
            validate_property_result_map(property_map, operation_plan=operation_plan)
        except (ResultContractError, TypeError) as exc:
            result = write_stage_outcome(
                stage_dir,
                spec,
                {
                    "schema_version": "stage_result",
                    "stage": spec.name,
                    "success": False,
                    "termination_reason": "deterministic_verification_failed",
                    "error_kind": "invalid_result_contract",
                    "summary": str(exc),
                    "model_calls": 0,
                },
            )
            return {"success": False, "stage_result": result}

        _write_json(stage_dir / "property_result_map.json", property_map)
        result_map_sha256 = file_sha256(stage_dir / "property_result_map.json")
        semantic = build_semantic_evidence(
            property_map,
            property_result_map_sha256=result_map_sha256,
        )
        _write_json(stage_dir / "semantic_evidence.json", semantic)
        if not (stage_dir / "jaspergold.log").is_file():
            (stage_dir / "jaspergold.log").write_text(
                str(backend_result.get("summary") or "deterministic verification completed") + "\n",
                encoding="utf-8",
            )
        if not (stage_dir / "proof_events.jsonl").is_file():
            operations = [
                operation
                for instance in property_map.get("instances", [])
                for operation in instance.get("operations", [])
            ]
            (stage_dir / "proof_events.jsonl").write_text(
                "".join(
                    json.dumps(
                        {"schema_version": "proof_event", "event": "property_finalized", "sequence": index, **item},
                        ensure_ascii=False,
                        sort_keys=True,
                    )
                    + "\n"
                    for index, item in enumerate(operations)
                ),
                encoding="utf-8",
            )
        cex_work_items = property_map.get("cex_work_items", [])
        primary_operations = [
            item
            for instance in property_map.get("instances", [])
            for item in instance.get("operations", [])
            if item.get("role") == "primary_assertion"
        ]
        result = write_stage_outcome(
            stage_dir,
            spec,
            {
                "schema_version": "stage_result",
                "stage": spec.name,
                "success": True,
                "termination_reason": "deterministic_verification_completed",
                "summary": backend_result.get("summary"),
                "model_calls": 0,
                "execution_status": property_map.get("execution_status"),
                "formal_outcome": property_map.get("formal_outcome"),
                "semantic_status": property_map.get("semantic_status"),
                "experiment_status": property_map.get("experiment_status"),
                "exclusion_reasons": property_map.get("exclusion_reasons", []),
                "operation_set_complete": property_map.get("operation_set_complete"),
                "expected_operation_count": property_map.get("expected_operation_count"),
                "accounted_operation_count": property_map.get("accounted_operation_count"),
                "cex_count": sum(item.get("status") == "cex" for item in primary_operations),
                "proven_count": sum(item.get("status") == "proven" for item in primary_operations),
                "trace_paths": [
                    item.get("trace_path")
                    for item in cex_work_items
                    if item.get("trace_path")
                ],
                "cex_work_items": cex_work_items,
                "property_result_map_path": "property_result_map.json",
                "semantic_evidence_path": "semantic_evidence.json",
            },
        )
        return {"success": True, "stage_result": result}

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
            execution_kind = get_stage_spec(current_stage).execution_kind
            if execution_kind == "binding":
                stage_context = initialize_stage_context(
                    self.workspace, current_stage
                )
                result = BindingStage(
                    self.workspace,
                    CoupledL2BuildOperations(self.workspace, self.logger),
                    self.llm_client,
                    self.logger,
                    stage_context,
                ).run()
            elif execution_kind == "deterministic":
                initialize_stage_context(self.workspace, current_stage)
                result = self._run_deterministic_verification()
            else:
                raise ValueError(f"unsupported execution kind: {execution_kind}")
            completed_stage = current_stage
            detail = dict(result.get("stage_result") or {})
            detail.setdefault("stage", current_stage)
            stage_results.append(detail)
            success = bool(result.get("success"))
            if not success:
                break

            if current_stage == "bind_properties":
                state = refresh_indexes(self.workspace)
                self._bind_state_to_handoff(current_stage, state)

            if current_stage == "invoke_verification":
                self.last_verification_result = detail
                if detail.get("formal_outcome") == "all_proven":
                    break
                if detail.get("formal_outcome") != "cex":
                    break
                if not any(
                    item.get("diagnosis_readiness") == "ready"
                    for item in detail.get("cex_work_items", [])
                ):
                    break

        summary = {
            "schema_version": "coupledl2_run_result",
            "run_dir": str(self.workspace.run_dir),
            "success": success,
            "completed_stage": completed_stage,
            "resumed": self.resumed,
            "execution_status": "not_run",
            "formal_outcome": "not_run",
            "semantic_status": "inconclusive",
            "experiment_status": "excluded",
        }
        verification = next(
            (item for item in reversed(stage_results) if item.get("stage") == "invoke_verification"),
            None,
        )
        if verification:
            for key in (
                "execution_status",
                "formal_outcome",
                "semantic_status",
                "experiment_status",
            ):
                if verification.get(key) is not None:
                    summary[key] = verification[key]
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
        completed = validate_completed_stage(
            self._handoff_path(predecessor).parent,
            get_stage_spec(predecessor),
        )
        if completed is None:
            raise ValueError(f"{stage} requires a successful {predecessor} handoff")

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
        handoff["source_state"] = {
            "workspace_hash": state["workspace_hash"],
            "index_hashes": state["index_hashes"],
        }
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
        current = build_run_cost_summary(
            usage,
            stage_results=stage_results,
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
        payload["schema_version"] = "final_result"
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
