"""Own CoupledL2 run lifecycle, resume validation, and stage progression."""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any, Callable, Dict, Optional, Type

from .backend import CoupledL2BuildOperations
from .binding_stage import BindingStage
from .repair_proposal_stage import RepairProposalStage
from .indexer import compute_index_hashes, compute_workspace_hash, refresh_indexes
from .preflight import CoupledL2Preflight
from .stages import COUPLEDL2_STAGES, get_stage_spec
from .workspace import CoupledL2Workspace, initialize_stage_context
from .artifacts import (
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
from .revision import design_bug_is_eligible
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
                    "schema_version": "stage_result.v2",
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
                    "schema_version": "stage_result.v2",
                    "stage": spec.name,
                    "success": False,
                    "termination_reason": "deterministic_verification_failed",
                    "error_kind": "invalid_v4_result_contract",
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
                        {"schema_version": "proof_event.v1", "event": "property_finalized", "sequence": index, **item},
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
                "schema_version": "stage_result.v2",
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
            v2c_block = self._v2c_readiness_block(current_stage)
            if v2c_block is not None:
                result = {"success": False, "stage_result": v2c_block}
                self._write_stage_failure(current_stage, v2c_block)
            elif execution_kind == "binding":
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
            elif execution_kind == "repair_proposal":
                stage_context = initialize_stage_context(
                    self.workspace, current_stage
                )
                result = RepairProposalStage(
                    self.workspace,
                    self.llm_client,
                    self.logger,
                    stage_context,
                ).run()
            else:
                if current_stage == "waveform_explanation":
                    self._materialize_trace_evidence()
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
                detail = dict(result.get("stage_result") or {})
                detail.setdefault("stage", current_stage)
                result["stage_result"] = write_stage_outcome(
                    self._handoff_path(current_stage).parent,
                    get_stage_spec(current_stage),
                    detail,
                )
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
            if current_stage == "waveform_explanation":
                diagnoses = self._read_diagnoses()
                if not self._diagnoses_allow_bugfix(diagnoses):
                    self._write_revision_request(diagnoses)
                    break

        summary = {
            "schema_version": "coupledl2_run_result.v1",
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
        if stage == "waveform_explanation":
            verification = completed
            if verification.get("formal_outcome") == "all_proven":
                raise ValueError("waveform_explanation cannot run after all properties were proven")
            if verification.get("formal_outcome") != "cex":
                raise ValueError("waveform_explanation requires a formal counterexample")
            ready_cex = [
                item
                for item in verification.get("cex_work_items", [])
                if item.get("diagnosis_readiness") == "ready"
            ]
            if not ready_cex:
                raise ValueError(
                    "waveform_explanation requires at least one decode-ready counterexample"
                )
        if stage == "propose_bugfix":
            diagnoses = self._read_diagnoses()
            if not self._diagnoses_allow_bugfix(diagnoses):
                raise ValueError(
                    "propose_bugfix requires every diagnosis to be design_bug"
                )

    def _counterexample_path(self) -> Optional[str]:
        result = self._read_stage_result("invoke_verification") or {}
        value = next(
            (
                item.get("trace_path")
                for item in result.get("cex_work_items", [])
                if item.get("diagnosis_readiness") == "ready" and item.get("trace_path")
            ),
            None,
        )
        if not value:
            return None
        path = Path(value)
        return str(path if path.is_absolute() else self.workspace.run_dir / path)

    def _materialize_trace_evidence(self) -> None:
        """Leave trace decoding to the V4 trace contracts.

        Iteration 0 intentionally has no elaboration-time observation map.  It
        therefore must not synthesize the retired empty ``signal_map`` input.
        A future Stage 4 invocation consumes the ready per-CEX contracts
        directly.
        """
        return

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
            "template_error": "template",
            "binding_error": "binding",
            "environment_error": "environment",
            "assumption_error": "formal_contract",
            "inconclusive": "environment",
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
                    "revision_target": layer_by_classification.get(
                        classification,
                        "environment",
                    ),
                    "parent_run_id": self.workspace.run_dir.name,
                    "old_asset_sha256": self._revision_asset_hash(
                        layer_by_classification.get(classification, "environment"),
                        item,
                    ),
                    "reason": item.get("uncertainty") or classification,
                    "evidence_refs": item.get("evidence_refs", []),
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
                "schema_version": "revision_request.v2",
                "requests": requests,
            },
        )

    def _revision_asset_hash(self, target: str, diagnosis: Dict[str, Any]) -> str:
        import hashlib

        stage2 = self.workspace.results_dir / "by_stage" / get_stage_spec("bind_properties").directory_name
        if target == "binding":
            path = stage2 / "binding_manifest.json"
        elif target in {"formal_contract", "environment"}:
            path = self.workspace.results_dir / "preflight" / "formal_contract.json"
        else:
            package = json.loads((stage2 / "property_package.json").read_text(encoding="utf-8"))
            traceability = package["traceability"]
            prop = next(
                item for item in traceability.get("properties", [])
                if item.get("instance_id") == diagnosis.get("instance_id")
                or item.get("property_schema_id") == diagnosis.get("property_schema_id")
            )
            prefix = "schemas/" if target == "property_schema" else "templates/"
            asset_id = prop["property_schema_id"] if target == "property_schema" else prop["template_id"]
            reviewed = prop.get("review", {}).get("asset_hashes", {})
            return next(
                sha for asset_path, sha in reviewed.items()
                if asset_path.startswith(prefix) and Path(asset_path).stem.lower() == asset_id.lower()
            )
        return hashlib.sha256(path.read_bytes()).hexdigest()

    def _diagnoses_allow_bugfix(self, diagnoses: list[Dict[str, Any]]) -> bool:
        stage2 = self.workspace.results_dir / "by_stage" / get_stage_spec("bind_properties").directory_name
        stage4 = self.workspace.results_dir / "by_stage" / get_stage_spec("waveform_explanation").directory_name
        evidence_path = stage4 / "diagnosis_evidence.json"
        review_ok = False
        formal_review_ok = False
        package_path = stage2 / "property_package.json"
        if package_path.is_file():
            package = json.loads(package_path.read_text(encoding="utf-8"))
            review = package.get("review") or {}
            review_ok = review.get("reviewer") == "codex" and review.get("review_status") == "approved"
            formal_path = self.workspace.results_dir / "preflight" / "formal_contract.json"
            if formal_path.is_file():
                formal = json.loads(formal_path.read_text(encoding="utf-8"))
                formal_review_ok = any(
                    item.get("kind") == "formal_contract"
                    and item.get("sha256") == formal.get("sha256")
                    for item in review.get("assets", [])
                )
            result_map_path = (
                self.workspace.results_dir
                / "by_stage"
                / get_stage_spec("invoke_verification").directory_name
                / "property_result_map.json"
            )
            if result_map_path.is_file():
                result_map = json.loads(
                    result_map_path.read_text(encoding="utf-8")
                )
                review_ok = review_ok and (
                    result_map.get("property_package_sha256")
                    == file_sha256(package_path)
                )
        evidence_properties = set()
        reconstruction_complete = False
        if evidence_path.is_file():
            evidence = json.loads(evidence_path.read_text(encoding="utf-8"))
            reconstruction_complete = (
                evidence.get("reconstruction_status") == "complete"
            )
            evidence_properties = {
                item.get("property") for item in evidence.get("properties", [])
            }
        return bool(diagnoses) and all(
            design_bug_is_eligible(
                item,
                package_approved=review_ok,
                formal_contract_approved=formal_review_ok,
                reconstruction_available=(
                    reconstruction_complete
                    and item.get("property") in evidence_properties
                ),
            )
            for item in diagnoses
        )

    def _read_handoff(self, stage: str) -> Optional[Dict[str, Any]]:
        path = self._handoff_path(stage)
        if not path.is_file():
            return None
        payload = json.loads(path.read_text(encoding="utf-8"))
        if payload.get("schema_version") != "stage_handoff.v2" or payload.get("stage") != stage:
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

    def _v2c_readiness_block(self, stage: str) -> Optional[Dict[str, Any]]:
        if stage != "bind_properties":
            return None
        path = self.workspace.case_workspace / "formal_readiness.json"
        if not path.is_file():
            return None
        readiness = json.loads(path.read_text(encoding="utf-8"))
        if readiness.get("ready") is not False:
            return None
        return {
            "schema_version": "stage_result.v2",
            "stage": stage,
            "success": False,
            "termination_reason": "invalid_v2c_environment",
            "error_kind": "invalid_v2c_environment",
            "summary": "V2C formal readiness failed before property binding.",
            "invalid_v2c_environment": True,
            "blocking_issues": readiness.get("blocking_issues", []),
            "formal_readiness": "formal_readiness.json",
        }

    def _write_stage_failure(self, stage: str, stage_result: Dict[str, Any]) -> None:
        path = self._handoff_path(stage)
        write_stage_outcome(
            path.parent, get_stage_spec(stage), stage_result
        )

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
