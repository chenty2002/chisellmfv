"""
Workflow for Chisel formal verification - Single Stage Mode Only.

Supports running one stage at a time:
1. build_top_module - Generate TestTop.scala
2. write_assertions - Add deadlock detection assertions
3. invoke_verification - Compile and run formal verification
4. waveform_explanation - Analyze counterexample waveforms
5. propose_bugfix - Fix identified bugs

For full workflow, call process_task() for each stage sequentially from the caller.

This implementation uses standard agent loop with message history:
- role: system - Stage-specific system prompt
- role: user - Initial task prompt
- role: assistant - LLM response with tool calls
- role: tool - Tool execution results
"""

import os
import json
import hashlib
from pathlib import Path
from typing import List, Dict, Any, Optional
import logging

from .llm_client import LLMClient, TokenBudgetExceeded
from .llm_router import LLMRouter
from ..utils.llm_properties import MAX_ITERATIONS, WAVEFORM_MAX_ITER
from ..utils.llm_logging import LLMLogger
from ..utils.file_utils import *
from .prompt_builder import (
    PROMPT_VERSION,
    build_system_prompt,
    build_user_prompt,
    build_assistant_tool_call_message,
    build_tool_result_message,
    build_compilation_error_message
)
from .context_manager import EvidenceNotebook, compact_messages_with_notebook
from .escape_policy import EscapePolicy
from .records import (
    OPERATION_SCHEMA_VERSION,
    STAGE_HANDOFF_SCHEMA_VERSION,
    build_run_cost_summary,
    make_stage_event,
    normalize_stage_result,
)
try:
    from .waveform_actions import WaveformActions
except ModuleNotFoundError:
    WaveformActions = None
from .tool_schemas import get_tool_schemas, convert_tool_call_to_action
from .actions import execute_stage_actions, _resolve_workspace_path, _workspace_relative
from ..coupledl2.backend import CoupledL2BuildOperations
from .repair_loop import (
    DEFAULT_MAX_REPAIR_ROUNDS,
    build_final_repair_result,
    check_repair_target_presence,
    extract_failing_properties,
    select_next_counterexample,
    snapshot_waveform_artifacts,
    write_repair_json,
)
from ..coupledl2.config import CoupledL2RunConfig
from ..coupledl2.workspace import (
    CoupledL2Workspace,
    StageContext,
    create_coupledl2_workspace,
    initialize_stage_context,
)
from ..causal_analysis import (
    CausalAnalysisActions,
    run_causal_analysis_result_if_available,
)


class FormalWorkflow:
    """
    Single-stage workflow for Chisel formal verification.
    
    Each call to process_task() runs exactly ONE stage.
    For full workflow, the caller (e.g., main.py) should invoke each stage sequentially.
    
    Supports CoupledL2 run configurations only.
    """
    
    def __init__(
        self,
        llm_client: Optional[Any],
        chisel_dir: str,
        workspace_dir: str,
        logger: logging.Logger,
        waveform_path: Optional[str] = None,
        stage: str = "build_top_module",
        target: str = "gigamax",
        ablation_modes: Optional[List[str]] = None,
        max_repair_rounds: int = DEFAULT_MAX_REPAIR_ROUNDS,
        initial_verification_result: Optional[Dict[str, Any]] = None,
        coupledl2_config: Optional[CoupledL2RunConfig] = None,
    ):
        """
        Initialize the formal verification workflow for a single stage.
        
        Args:
            llm_client: LLM client for API interactions
            chisel_dir: Path to the chisel directory
            workspace_dir: Root directory of the project
            logger: Logger instance
            waveform_path: Path to counterexample waveform (for waveform stage)
            stage: Which stage to run (single stage only)
            target: CoupledL2 case label used in logs and prompt metadata
            ablation_modes: Optional experiment switches that disable selected
                workflow mechanisms for controlled ablation studies.
        """
        self.llm = llm_client
        self.chisel_dir = os.path.join(os.path.abspath(workspace_dir), chisel_dir)
        self.workspace_dir = workspace_dir
        self.logger = logger
        self.waveform_path = waveform_path
        self.current_stage = stage
        self.target = target
        self.ablation_modes = set(ablation_modes or [])
        self.max_repair_rounds = max(0, int(max_repair_rounds))
        self.last_verification_result = initial_verification_result
        self.causal_actions: Optional[CausalAnalysisActions] = None
        self.coupledl2_config = coupledl2_config
        self.run_context: Optional[CoupledL2Workspace] = None
        self.stage_context: Optional[StageContext] = None

        if self.coupledl2_config is None:
            raise ValueError("FormalWorkflow now requires a CoupledL2RunConfig")
        
        # Initialize paths based on target
        self._init_paths()
        
        # Initialize helper classes
        self._init_helpers()
        
        # Initialize waveform actions if waveform path provided
        self._init_waveform_actions()
    
    def _init_paths(self) -> None:
        """Initialize directory paths based on target."""
        self.run_context = create_coupledl2_workspace(self.coupledl2_config)
        self.work_dir = str(self.run_context.case_workspace)
        self.verify_src_dir = str(self.run_context.case_workspace / "Chisel")
        self.generated_dir = str(self.run_context.case_workspace / "Chisel" / "generated")
        self.verilog_dir = str(self.run_context.case_workspace / "Verilog")
        self._allowed_write_dirs = [self.work_dir]
    
    def _init_helpers(self) -> None:
        """Initialize helper classes for build operations."""
        assert self.run_context is not None
        self.build_ops = CoupledL2BuildOperations(
            workspace=self.run_context,
            logger=self.logger,
        )
    
    def _init_waveform_actions(self) -> None:
        """Initialize waveform actions if waveform path is provided."""
        self.waveform_actions = None
        if self.waveform_path:
            if WaveformActions is None:
                self.logger.error("Failed to initialize waveform actions: pylibfst is not installed")
                return
            try:
                self.waveform_actions = WaveformActions(self.waveform_path)
            except Exception as e:
                self.logger.error(f"Failed to initialize waveform actions: {e}")
    
    def get_work_dir_files(self) -> List[str]:
        """
        Get list of .scala and .md files in work_dir.
        
        Returns:
            List of filenames
        """
        return get_directory_files(self.work_dir, ['.scala', '.md'], self.logger)
    
    def create_initial_context(self, user_query: str) -> Dict[str, Any]:
        """Create initial context for the workflow."""
        env_info = {
            "verify_src": self.verify_src_dir,
            "target": self.target,
        }
        if self.ablation_modes:
            env_info["ablation_modes"] = sorted(self.ablation_modes)

        if self.run_context is not None:
            self.stage_context = initialize_stage_context(self.run_context, self.current_stage)
            env_info["coupledl2"] = self._coupledl2_environment()
        
        env_info["work_dir"] = self.work_dir
        
        context = {
            "user_query": user_query,
            "chisel_dir": self.chisel_dir,
            "workspace_dir": self.workspace_dir,
            "current_stage": self.current_stage,
            "environment": env_info,
            "iterations": [],
            "stage_results": {},
        }

        if self.waveform_path:
            context["environment"]["waveform_path"] = self.waveform_path
            if self.waveform_actions and self.waveform_actions.metadata:
                context["environment"]["waveform_metadata"] = self.waveform_actions.metadata
        
        return context

    def _coupledl2_environment(self) -> Dict[str, Any]:
        """Return serializable CoupledL2 initialization context for prompts and logs."""
        assert self.run_context is not None
        stage_context = self.stage_context
        return {
            "case_name": self.run_context.config.case_name,
            "run_dir": str(self.run_context.run_dir),
            "workspace_case_path": str(self.run_context.case_workspace),
            "manifest_path": str(self.run_context.manifest_path),
            "indexes_dir": str(self.run_context.indexes_dir),
            "verify_mode": self.run_context.config.verify_mode,
            "input_mode": self.run_context.config.input_mode,
            "property_category": self.run_context.config.property_category,
            "stage_context": (
                stage_context.to_dict(self.run_context.workspace_dir)
                if stage_context is not None
                else None
            ),
        }
    
    def process_task(self, user_query: str) -> Dict[str, Any]:
        """
        Process a single stage of the formal verification task.
        
        This method runs exactly ONE stage (self.current_stage).
        For full workflow, call this method for each stage sequentially.
        
        Args:
            user_query: The user's query/prompt
            
        Returns:
            Dictionary containing results from the single stage
        """
        stage = self.current_stage
        self.logger.info(f"Starting formal verification stage: {stage}")
        print(f"Starting formal verification stage: {stage}")
        
        context = self.create_initial_context(user_query)
        
        result = {
            "original_query": user_query,
            "stage": stage,
            "success": False,
            "ablation_modes": sorted(self.ablation_modes),
        }
        
        context["iterations"] = []
        
        self.logger.info(f"=== Running stage: {stage} ===")
        print(f"\n=== Running stage: {stage} ===")
        
        # invoke_verification stage runs automatically without LLM.
        # propose_bugfix is now a bounded repair-regression loop.
        if stage == "invoke_verification":
            stage_result = self.build_ops.run_full_verification_flow()
            self.last_verification_result = stage_result
        elif stage == "propose_bugfix":
            stage_result = self._run_repair_loop(user_query)
        else:
            stage_result = self._run_stage(context, stage)
        
        result["stage_result"] = stage_result
        result["success"] = stage_result.get("success", False)
        self._record_coupledl2_stage_result(stage, stage_result, result)
        
        # Handle waveform path update from verification result
        if stage == "invoke_verification":
            if stage_result.get("verification_passed", True):
                self.logger.info("Verification passed - no counterexample generated")
            else:
                self._update_waveform_from_result(stage_result, context)
        
        return result

    def _run_repair_loop(self, user_query: str) -> Dict[str, Any]:
        """Run stage 5 as a bounded repair-regression loop."""
        repair_root = Path(self.work_dir) / "repair_loop"
        repair_root.mkdir(parents=True, exist_ok=True)

        initial_stage3 = self.last_verification_result
        if not initial_stage3:
            self.logger.info("No prior stage-3 result available; running initial verification for repair loop.")
            initial_stage3 = self.build_ops.run_full_verification_flow()
            self.last_verification_result = initial_stage3

        initial_properties = extract_failing_properties(initial_stage3)
        if initial_stage3.get("verification_passed", False) or not initial_properties:
            final = build_final_repair_result(
                max_repair_rounds=self.max_repair_rounds,
                rounds=[],
                initial_stage3_result=initial_stage3,
                final_stage3_result=initial_stage3,
                repair_success=True,
                target_presence=check_repair_target_presence(initial_properties, initial_stage3),
            )
            write_repair_json(repair_root / "final_repair_result.json", final)
            return {
                "success": True,
                "summary": "No counterexample remains before repair.",
                "verification_passed": True,
                "repair_loop": final,
            }

        current_stage3 = initial_stage3
        current_property: Optional[str] = None
        rounds: List[Dict[str, Any]] = []
        target_presence = check_repair_target_presence(initial_properties, current_stage3)

        for round_idx in range(1, self.max_repair_rounds + 1):
            selected = select_next_counterexample(
                current_stage3,
                previous_property=current_property,
                original_failing_properties=initial_properties,
            )
            if not selected:
                final = build_final_repair_result(
                    max_repair_rounds=self.max_repair_rounds,
                    rounds=rounds,
                    initial_stage3_result=initial_stage3,
                    final_stage3_result=current_stage3,
                    repair_success=True,
                    target_presence=target_presence,
                )
                write_repair_json(repair_root / "final_repair_result.json", final)
                return {
                    "success": True,
                    "summary": "Repair loop completed; no counterexample remains.",
                    "verification_passed": True,
                    "repair_loop": final,
                }

            round_dir = repair_root / f"round_{round_idx:02d}"
            current_property = selected.get("name")
            waveform_path = selected.get("fst_file") or current_stage3.get("counterexample_path")
            selected_for_snapshot = dict(selected)
            if waveform_path and not selected_for_snapshot.get("fst_file"):
                selected_for_snapshot["counterexample_path"] = waveform_path
            selected_waveform_artifacts = snapshot_waveform_artifacts(
                selected_for_snapshot,
                round_dir / "waveforms" / "selected_cex",
            )
            selected = dict(selected)
            selected["waveform_artifacts"] = selected_waveform_artifacts
            write_repair_json(round_dir / "selected_cex.json", selected)

            if not self._analysis_report_available() or round_idx > 1:
                analysis_result = self._run_repair_waveform_analysis(waveform_path)
                if not analysis_result.get("success", False):
                    final = build_final_repair_result(
                        max_repair_rounds=self.max_repair_rounds,
                        rounds=rounds,
                        initial_stage3_result=initial_stage3,
                        final_stage3_result=current_stage3,
                        repair_success=False,
                        target_presence=target_presence,
                    )
                    final["error"] = analysis_result.get("error", "waveform analysis failed")
                    write_repair_json(repair_root / "final_repair_result.json", final)
                    return {
                        "success": False,
                        "summary": "Repair loop failed while regenerating counterexample analysis.",
                        "verification_passed": False,
                        "repair_loop": final,
                    }
                self._copy_analysis_report(round_dir / "counterexample_analysis.md")
            else:
                self._copy_analysis_report(round_dir / "counterexample_analysis.md")

            repair_context = self.create_initial_context(user_query)
            repair_context.setdefault("environment", {}).update({
                "repair_round": f"{round_idx}/{self.max_repair_rounds}",
                "selected_counterexample": selected,
                "stage3_summary": self._compact_stage3_summary(current_stage3),
                "repair_history": rounds,
            })

            round_result = self._run_stage(repair_context, "propose_bugfix")
            if not round_result.get("success", False):
                final = build_final_repair_result(
                    max_repair_rounds=self.max_repair_rounds,
                    rounds=rounds,
                    initial_stage3_result=initial_stage3,
                    final_stage3_result=current_stage3,
                    repair_success=False,
                    target_presence=target_presence,
                )
                final["error"] = round_result.get("error", "repair round failed")
                write_repair_json(repair_root / "final_repair_result.json", final)
                return {
                    "success": False,
                    "summary": "Repair loop failed during a repair round.",
                    "verification_passed": False,
                    "repair_loop": final,
                }

            summary = str(round_result.get("summary", "")).strip()
            if summary:
                (round_dir / "repair_round_summary.md").write_text(summary, encoding="utf-8")

            post_stage3 = self.build_ops.run_full_verification_flow()
            self.last_verification_result = post_stage3
            post_stage3_waveform_artifacts = snapshot_waveform_artifacts(
                post_stage3,
                round_dir / "waveforms" / "post_stage3",
            )
            post_stage3_artifact_record = dict(post_stage3)
            post_stage3_artifact_record["waveform_artifacts"] = post_stage3_waveform_artifacts
            write_repair_json(round_dir / "stage3_result.json", post_stage3_artifact_record)

            target_presence = check_repair_target_presence(initial_properties, post_stage3)
            round_info = {
                "round": round_idx,
                "target_assertion_label": round_result.get("target_assertion_label") or current_property,
                "selected_cex": selected,
                "round_summary": summary,
                "error_type": round_result.get("error_type"),
                "homologous_assertions": round_result.get("homologous_assertions", []),
                "post_cex_count": post_stage3.get("cex_count", 0),
                "post_failing_properties": extract_failing_properties(post_stage3),
                "selected_waveform_artifacts": selected_waveform_artifacts,
                "post_stage3_waveform_artifacts": post_stage3_waveform_artifacts,
                "target_presence": target_presence,
            }
            rounds.append(round_info)
            write_repair_json(repair_root / "repair_history.json", {"rounds": rounds})

            if target_presence.get("all_present") is False:
                final = build_final_repair_result(
                    max_repair_rounds=self.max_repair_rounds,
                    rounds=rounds,
                    initial_stage3_result=initial_stage3,
                    final_stage3_result=post_stage3,
                    repair_success=False,
                    target_presence=target_presence,
                )
                final["error"] = "repair removed or renamed original failing assertion labels"
                write_repair_json(repair_root / "final_repair_result.json", final)
                return {
                    "success": False,
                    "summary": "Repair loop failed because an original failing assertion label is missing.",
                    "verification_passed": False,
                    "repair_loop": final,
                }

            current_stage3 = post_stage3
            if post_stage3.get("verification_passed", False) or not extract_failing_properties(post_stage3):
                final = build_final_repair_result(
                    max_repair_rounds=self.max_repair_rounds,
                    rounds=rounds,
                    initial_stage3_result=initial_stage3,
                    final_stage3_result=post_stage3,
                    repair_success=True,
                    target_presence=target_presence,
                )
                write_repair_json(repair_root / "final_repair_result.json", final)
                return {
                    "success": True,
                    "summary": "Repair loop completed; no counterexample remains.",
                    "verification_passed": True,
                    "repair_loop": final,
                }

        final = build_final_repair_result(
            max_repair_rounds=self.max_repair_rounds,
            rounds=rounds,
            initial_stage3_result=initial_stage3,
            final_stage3_result=current_stage3,
            repair_success=False,
            target_presence=target_presence,
        )
        write_repair_json(repair_root / "final_repair_result.json", final)
        return {
            "success": False,
            "summary": "Repair loop reached the maximum number of rounds with remaining counterexamples.",
            "verification_passed": False,
            "repair_loop": final,
        }

    def _analysis_report_available(self) -> bool:
        return os.path.exists(os.path.join(self.work_dir, "counterexample_analysis.md"))

    def _copy_analysis_report(self, dst: Path) -> None:
        src = Path(self.work_dir) / "counterexample_analysis.md"
        if src.exists():
            dst.parent.mkdir(parents=True, exist_ok=True)
            dst.write_text(src.read_text(encoding="utf-8"), encoding="utf-8")

    def _run_repair_waveform_analysis(self, waveform_path: Optional[str]) -> Dict[str, Any]:
        if not waveform_path:
            return {"success": False, "error": "no waveform path for selected counterexample"}
        workflow = FormalWorkflow(
            llm_client=self.llm,
            chisel_dir=os.path.relpath(self.chisel_dir, self.workspace_dir),
            workspace_dir=self.workspace_dir,
            logger=self.logger,
            waveform_path=waveform_path,
            stage="waveform_explanation",
            target=self.target,
            ablation_modes=sorted(self.ablation_modes),
            max_repair_rounds=self.max_repair_rounds,
            initial_verification_result=self.last_verification_result,
            coupledl2_config=self.coupledl2_config,
        )
        result = workflow.process_task(
            "Analyze the selected post-repair counterexample for the next repair round."
        )
        return result.get("stage_result", result)

    def _compact_stage3_summary(self, stage3_result: Dict[str, Any]) -> Dict[str, Any]:
        return {
            "success": stage3_result.get("success"),
            "verification_passed": stage3_result.get("verification_passed"),
            "summary": stage3_result.get("summary"),
            "cex_count": stage3_result.get("cex_count"),
            "proven_count": stage3_result.get("proven_count"),
            "failing_properties": extract_failing_properties(stage3_result),
        }
    
    def _update_waveform_from_result(self, stage_result: Dict[str, Any], context: Dict[str, Any]) -> None:
        """Update waveform actions from verification result."""
        cex_path = stage_result.get("counterexample_path")
        if cex_path and os.path.exists(cex_path):
            self.waveform_path = cex_path
            if WaveformActions is None:
                self.logger.error("Failed to load counterexample: pylibfst is not installed")
                return
            try:
                self.waveform_actions = WaveformActions(cex_path)
                context["environment"]["waveform_path"] = cex_path
                context["environment"]["waveform_metadata"] = self.waveform_actions.metadata
            except Exception as e:
                self.logger.error(f"Failed to load counterexample: {e}")
    
    def _run_stage(self, context: Dict[str, Any], stage: str) -> Dict[str, Any]:
        """
        Run a single stage of the workflow using standard agent loop.
        
        Uses message history with roles: system, user, assistant, tool
        This replaces the previous iteration-in-prompt approach.
        """
        # Use stage-specific max iterations
        if stage == "waveform_explanation":
            max_iterations = WAVEFORM_MAX_ITER
        else:
            max_iterations = MAX_ITERATIONS
        
        iterations = []
        iteration_count = 0
        waveform_notebook = (
            EvidenceNotebook()
            if stage == "waveform_explanation"
            and "no_waveform_notebook" not in self.ablation_modes
            else None
        )
        self.escape_policy = EscapePolicy()
        
        tool_schemas = get_tool_schemas(
            stage,
            target=self.target,
            coupledl2=self.run_context is not None,
        )
        cache_metadata = self._build_prompt_cache_metadata(stage, tool_schemas)
        
        # Load analysis report for propose_bugfix stage
        analysis_report = self._load_analysis_report(stage)

        # Run Verilog causal analysis as prior evidence for waveform_explanation.
        # The generated summary is attached to the context and injected into the
        # LLM user prompt as additional root-cause hints. The structured JSON is
        # also exposed through causal_* tools so the model can query it on demand.
        self.causal_actions = None
        if (
            stage == "waveform_explanation"
            and self.waveform_path
            and "no_causal_prior" not in self.ablation_modes
        ):
            try:
                causal_result = run_causal_analysis_result_if_available(
                    workspace_dir=self.workspace_dir,
                    logger=self.logger,
                    fst_path=self.waveform_path,
                    target=self.target,
                )
                if causal_result and causal_result.summary:
                    self.logger.info("Causal analysis report attached to waveform_explanation prompt.")
                    context.setdefault("environment", {})["causal_analysis_report"] = causal_result.summary
                if causal_result and causal_result.json_report:
                    self.causal_actions = CausalAnalysisActions(
                        json_report=causal_result.json_report,
                        summary=causal_result.summary,
                        output_dir=causal_result.output_dir,
                    )
                    context.setdefault("environment", {})["causal_analysis_index"] = (
                        self.causal_actions.get_index()
                    )
            except Exception as e:
                # Never fail the workflow because the auxiliary analyser stumbled.
                self.logger.warning(f"Causal analysis failed to run: {e}")

        # Build initial message history
        messages = self._build_initial_messages(context, stage, None, analysis_report)
        
        for i in range(1, max_iterations + 1):
            self._log_llm_request(stage, i, [messages[0], messages[-1]], tool_schemas)
            
            # Use chat_with_tools for agent loop style API call
            response = self._chat_with_stage_model(
                messages,
                tool_schemas,
                stage=stage,
                task_type="stage_loop",
                prompt_cache_key=cache_metadata["prompt_cache_key"],
                usage_metadata=cache_metadata,
            )
            
            try:
                self._log_llm_response(stage, i, response)
                
                if response["type"] == "function_calls":
                    result = self._handle_function_calls(
                        response, stage, context, iterations, iteration_count, messages
                    )
                    iteration_count = result["iteration_count"]
                    
                    if result.get("completed"):
                        return result["result"]

                    self._maybe_compact_waveform_context(
                        stage, context, messages, waveform_notebook
                    )
                else:
                    # LLM returned text instead of tool calls - add error message and retry
                    iteration_count = self._handle_text_response(
                        response, stage, context, iterations, iteration_count, messages
                    )
                    self._maybe_compact_waveform_context(
                        stage, context, messages, waveform_notebook
                    )
                    
            except TokenBudgetExceeded:
                raise  # Always propagate token budget errors
            except Exception as e:
                iteration_count += 1
                self.logger.error(f"Stage {stage} Iteration {iteration_count} error: {e}")
                iteration = {"iteration": iteration_count, "error": str(e)}
                iterations.append(iteration)
                context["iterations"].append(iteration)
                # Add error message to history for recovery
                messages.append({
                    "role": "user",
                    "content": f"Error occurred: {str(e)}. Please try again with tool calls only."
                })
                self._maybe_compact_waveform_context(
                    stage, context, messages, waveform_notebook
                )
        
        return {
            "success": False,
            "iterations": iterations,
            "error": "Max iterations reached",
        }

    def _build_prompt_cache_metadata(
        self,
        stage: str,
        tool_schemas: List[Dict[str, Any]],
    ) -> Dict[str, str]:
        """Build stable cache metadata for providers and usage reports."""
        tool_hash = hashlib.sha256(
            json.dumps(tool_schemas, ensure_ascii=False, sort_keys=True).encode("utf-8")
        ).hexdigest()[:10]
        prompt_hash = hashlib.sha256(PROMPT_VERSION.encode("utf-8")).hexdigest()[:8]
        return {
            "prompt_cache_key": f"chisellmfv:formal:{stage}:p{prompt_hash}:t{tool_hash}",
            "stage": stage,
            "target": self.target,
            "prompt_version": PROMPT_VERSION,
            "tool_schema_hash": tool_hash,
        }

    def _chat_with_stage_model(
        self,
        messages: List[Dict[str, Any]],
        tool_schemas: List[Dict[str, Any]],
        *,
        stage: str,
        task_type: str,
        prompt_cache_key: Optional[str],
        usage_metadata: Dict[str, str],
    ) -> Dict[str, Any]:
        """Call either LLMRouter or a single LLMClient."""
        metadata = dict(usage_metadata)
        if isinstance(self.llm, LLMRouter):
            return self.llm.chat_with_tools(
                messages,
                tool_schemas,
                stage=stage,
                task_type=task_type,
                prompt_cache_key=prompt_cache_key,
                usage_metadata=metadata,
            )
        if self.llm is None:
            raise ValueError("LLM client is not configured for model-backed stage execution")
        metadata.setdefault("model_role", getattr(self.llm, "model_role", None) or "pro")
        return self.llm.chat_with_tools(
            messages,
            tool_schemas,
            prompt_cache_key=prompt_cache_key,
            usage_metadata=metadata,
        )
    
    def _build_initial_messages(
        self,
        context: Dict[str, Any],
        stage: str,
        scala_sources: Optional[Dict[str, str]],
        analysis_report: Optional[str]
    ) -> List[Dict[str, Any]]:
        """Build initial message list for the agent loop."""
        # System prompt - stage-specific instructions
        system_prompt = build_system_prompt(
            stage=stage,
            target=self.target,
            chisel_dir=self.chisel_dir,
            workspace_dir=self.workspace_dir,
            work_dir_files=None,
        )
        
        # User prompt - task description and context
        user_prompt = build_user_prompt(
            context=context,
            stage=stage,
            scala_sources=scala_sources,
            analysis_report=analysis_report,
        )
        
        return [
            {"role": "system", "content": system_prompt},
            {"role": "user", "content": user_prompt}
        ]
    
    def _load_analysis_report(self, stage: str) -> Optional[str]:
        """Load analysis report for propose_bugfix stage."""
        if stage != "propose_bugfix":
            return None
        
        report_path = os.path.join(self.work_dir, "counterexample_analysis.md")
        if os.path.exists(report_path):
            try:
                with open(report_path, 'r', encoding='utf-8') as f:
                    return f.read()
            except Exception as e:
                self.logger.warning(f"Failed to load analysis report: {e}")
        return None
    
    def _handle_function_calls(
        self,
        response: Dict[str, Any],
        stage: str,
        context: Dict[str, Any],
        iterations: list,
        iteration_count: int,
        messages: List[Dict[str, Any]],
    ) -> Dict[str, Any]:
        """
        Handle function call responses from LLM.
        
        Executes tool calls and updates message history with:
        - Assistant message containing tool_calls
        - Tool result messages for each executed tool
        """
        function_calls = response["function_calls"]
        raw_message = response.get("raw_message", {})
        
        actions = [convert_tool_call_to_action(fc["name"], fc["arguments"]) 
                  for fc in function_calls]
        
        iteration_count += 1
        
        self.logger.info(f"Stage {stage} Iteration {iteration_count} Actions: {[a['type'] for a in actions]}")
        print(f"  Iteration {iteration_count}: {[a['type'] for a in actions]}")
        
        # Execute actions
        action_results = self._execute_stage_actions(actions, stage)
        
        # Store iteration data
        iteration = {
            "iteration": iteration_count,
            "function_calls": function_calls,
            "action_results": action_results,
        }
        iterations.append(iteration)
        context["iterations"].append(iteration)
        
        # Add assistant message with tool_calls to history.
        assistant_message = build_assistant_tool_call_message(
            raw_message,
            function_calls,
        )
        messages.append(assistant_message)
        
        # Add tool result messages to history
        for fc, result in zip(function_calls, action_results):
            tool_message = build_tool_result_message(
                tool_call_id=fc["id"],
                tool_name=fc["name"],
                result=result
            )
            messages.append(tool_message)

        # Detect and discourage no-progress tool loops through the escape policy.
        self._handle_repeated_tool_calls(stage, function_calls, action_results, messages)
        
        # CoupledL2 stages complete only through the explicit complete_stage tool.
        for fc in function_calls:
            args = fc.get("arguments", {})
            if fc.get("name") == "complete_stage":
                result = self._handle_stage_completion(
                    args, stage, context, iterations, iteration_count, messages
                )
                if result:
                    return {
                        "completed": True,
                        "result": result,
                        "iteration_count": iteration_count,
                    }
        
        return {"completed": False, "iteration_count": iteration_count}

    def _maybe_compact_waveform_context(
        self,
        stage: str,
        context: Dict[str, Any],
        messages: List[Dict[str, Any]],
        notebook: Optional[EvidenceNotebook],
    ) -> None:
        """Compact long waveform histories into a deterministic evidence notebook."""
        if stage != "waveform_explanation" or notebook is None:
            return

        iterations = context.get("iterations", [])
        if iterations:
            notebook.record_iteration(iterations[-1])

        changed = compact_messages_with_notebook(messages, notebook)
        if changed:
            self.logger.info(
                "Compacted waveform_explanation message history into evidence notebook "
                f"({len(messages)} messages retained)."
            )

    def _handle_repeated_tool_calls(
        self,
        stage: str,
        function_calls: List[Dict[str, Any]],
        action_results: List[Dict[str, Any]],
        messages: List[Dict[str, Any]],
    ) -> None:
        """Inject guidance when the escape policy detects no-progress behavior."""
        if not hasattr(self, "escape_policy"):
            self.escape_policy = EscapePolicy()

        actions = self.escape_policy.observe(
            stage=stage,
            function_calls=function_calls,
            action_results=action_results,
            messages=messages,
        )
        for action in actions:
            if action.action_type != "nudge":
                continue
            if (
                action.metadata.get("rule") == "repeated_waveform_value"
                and "no_repeated_waveform_guard" in self.ablation_modes
            ):
                continue
            self.logger.warning(
                "Escape policy nudge triggered: %s",
                action.metadata.get("rule", "unknown"),
            )
            messages.append({"role": "user", "content": action.message})
    
    def _handle_stage_completion(
        self,
        args: Dict[str, Any],
        stage: str,
        context: Dict[str, Any],
        iterations: list,
        iteration_count: int,
        messages: List[Dict[str, Any]],
    ) -> Optional[Dict[str, Any]]:
        """
        Handle stage completion signal from LLM.
        
        For stages requiring compilation, verifies the code compiles.
        If compilation fails, adds error message to history for retry.
        """
        summary = args.get("summary", args.get("round_summary", ""))
        error_type = args.get("error_type")  # Extract error_type if present
        
        self.logger.info(f"Stage {stage} marked complete by LLM: {summary if summary else ''}")
        if error_type:
            self.logger.info(f"Error type identified: {error_type}")
            print(f"  Error type: {error_type}")
            context["error_type"] = error_type

        if (
            stage == "propose_bugfix"
            and error_type == "assertion_error"
            and "homologous_assertions" not in args
        ):
            messages.append({
                "role": "user",
                "content": (
                    "For an `assertion_error` repair you must inspect and report homologous assertions. "
                    "Call `complete_stage` again with `homologous_assertions` listing every structurally "
                    "identical assertion repaired, or an empty list only if none exist."
                ),
            })
            return None
        
        # Stages that require compilation verification
        if stage in ["build_top_module", "write_assertions", "propose_bugfix"]:
            compile_result = self.build_ops.verify_compilation(
                require_assertions=(
                    stage == "write_assertions"
                    and "no_assertion_presence_gate" not in self.ablation_modes
                )
            )
            if compile_result["success"]:
                self.logger.info(f"Stage {stage} compilation verified successfully")
                result = {
                    "success": True,
                    "iterations": iterations,
                    "summary": summary,
                    "verification_passed": True,
                    "counterexample_path": None,
                    "root_cause": None,
                }
                # Include error_type if present (for RL reward calculation)
                if error_type:
                    result["error_type"] = error_type
                if stage == "propose_bugfix":
                    result["target_assertion_label"] = args.get("target_assertion_label")
                    result["homologous_assertions"] = args.get("homologous_assertions", [])
                context["stage_results"][stage] = result
                if stage == "propose_bugfix" and result.get("success"):
                    context["workflow_complete"] = True
                    context["bug_fixed"] = True
                    if error_type:
                        context["fix_type"] = error_type
                return result
            else:
                self.logger.warning(f"Stage {stage} final build check failed, attaching to iteration {iteration_count}")
                iterations[-1]["compilation_error"] = compile_result["error"]
                context["iterations"][-1]["compilation_error"] = compile_result["error"]
                print(f"  Final build check failed, asking LLM to fix...")
                
                # Add compilation error as user message to history
                error_message = build_compilation_error_message(compile_result["error"])
                messages.append({
                    "role": "user",
                    "content": error_message
                })
                
                return None  # Continue iteration
        else:
            result = {
                "success": True,
                "iterations": iterations,
                "summary": args.get("summary", ""),
                "verification_passed": args.get("verification_passed", True),
                "counterexample_path": args.get("counterexample_path"),
                "root_cause": args.get("root_cause"),
                "files_modified": args.get("files_modified", []),
            }
            # Include error_type if present (for RL reward calculation)
            if error_type:
                result["error_type"] = error_type
            if stage == "waveform_explanation":
                self._write_coupledl2_diagnosis(args, result)
            context["stage_results"][stage] = result
            if stage == "propose_bugfix" and result.get("success"):
                context["workflow_complete"] = True
                context["bug_fixed"] = True
                if error_type:
                    context["fix_type"] = error_type
            return result
    
    def _handle_text_response(
        self,
        response: Dict[str, Any],
        stage: str,
        context: Dict[str, Any],
        iterations: list,
        iteration_count: int,
        messages: List[Dict[str, Any]],
    ) -> int:
        """
        Handle unexpected text response from LLM.
        
        Since tool_choice="required", this should rarely happen.
        Adds error message to history to guide LLM to use tools.
        """
        iteration_count += 1
        text = response.get("content", "")
        self.logger.warning(f"Stage {stage} Iteration {iteration_count}: Got text response instead of function calls")
        
        iteration = {
            "iteration": iteration_count,
            "error": "LLM returned text instead of function calls",
            "raw_response": text[:1000],
        }
        iterations.append(iteration)
        context["iterations"].append(iteration)
        
        # Add assistant text response to history
        if text:
            messages.append({
                "role": "assistant",
                "content": text
            })
        
        # Add user message to guide LLM to use tools
        messages.append({
            "role": "user",
            "content": "ERROR: You must respond with tool calls only. Do not respond with plain text. Use the available tools to complete the task."
        })
        
        return iteration_count
    
    def _log_llm_request(self, stage: str, iteration: int, prompt: List[Dict[str, Any]], tool_schemas: List[Dict[str, Any]]) -> None:
        """Log LLM API request details."""
        system_prompt = prompt[0].get("content", "") if prompt else ""
        user_prompt = prompt[1].get("content", "") if len(prompt) > 1 else ""
        dynamic_messages = prompt[2:]
        full_prompt = "[System Prompt]\n" + system_prompt + "\n\n[User Prompt]\n" + user_prompt
        if dynamic_messages:
            full_prompt += (
                "\n\n[Dynamic Conversation Context]\n"
                + LLMLogger._format_dynamic_messages(dynamic_messages)
            )
        filtered_prompt = LLMLogger.filter_scala_code_in_prompt(full_prompt)
        log_msg = LLMLogger.format_request(
            filtered_prompt,
            tool_schemas,
            stage=stage,
            iteration=iteration,
            include_details=(iteration == 1),
            dynamic_messages=dynamic_messages,
        )
        self.logger.info(log_msg)
    
    def _log_llm_response(self, stage: str, iteration: int, response: Dict[str, Any]) -> None:
        """Log LLM API response details."""
        log_msg = LLMLogger.format_response(
            response, stage=stage, iteration=iteration, truncate_content=False
        )
        self.logger.info(log_msg)
            
    def _execute_stage_actions(self, actions: List[Dict[str, Any]], stage: str) -> List[Dict[str, Any]]:
        """Execute actions for the current stage."""
        edit_snapshots = self._capture_edit_snapshots(actions)
        results = execute_stage_actions(
            actions=actions,
            work_dir=self.work_dir,
            waveform_actions=self.waveform_actions,
            causal_actions=self.causal_actions,
            read_file_func=self.read_file,
            write_file_func=self.write_file,
            logger=self.logger,
            workspace_root=str(self.run_context.workspace_dir) if self.run_context is not None else None,
        )
        self._record_stage_operations(stage, actions, results, edit_snapshots)
        return results

    def _capture_edit_snapshots(self, actions: List[Dict[str, Any]]) -> Dict[str, Dict[str, Any]]:
        """Capture pre-edit content for lazy source snapshots."""
        if self.run_context is None:
            return {}
        snapshots: Dict[str, Dict[str, Any]] = {}
        workspace_root = str(self.run_context.workspace_dir)
        for action in actions:
            if action.get("type") != "edit_file":
                continue
            raw_path = str(action.get("file_path", ""))
            try:
                path = _resolve_workspace_path(raw_path, workspace_root, self.work_dir)
                rel_path = _workspace_relative(path, workspace_root)
                if rel_path in snapshots:
                    continue
                content = path.read_bytes() if path.exists() else b""
                snapshots[rel_path] = {
                    "path": path,
                    "content": content,
                    "manifest": self._file_manifest(path, content=content),
                }
            except Exception as exc:
                self.logger.warning(f"Failed to capture pre-edit snapshot for {raw_path}: {exc}")
        return snapshots

    def _record_stage_operations(
        self,
        stage: str,
        actions: List[Dict[str, Any]],
        results: List[Dict[str, Any]],
        edit_snapshots: Dict[str, Dict[str, Any]],
    ) -> None:
        """Append tool operations and lazy edit snapshots to the stage ledger."""
        stage_dir = self._coupledl2_stage_dir(stage)
        if stage_dir is None or self.run_context is None:
            return

        operations_path = stage_dir / "operations.jsonl"
        existing_count = self._jsonl_line_count(operations_path)
        for offset, (action, result) in enumerate(zip(actions, results), start=1):
            sequence = existing_count + offset
            tool = str(result.get("type") or action.get("type") or "unknown")
            operation = {
                "schema_version": OPERATION_SCHEMA_VERSION,
                "kind": "tool_result",
                "stage": stage,
                "iteration": int(action.get("_iteration", 0) or 0),
                "sequence": sequence,
                "tool": tool,
                "success": bool(result.get("success", False)),
                "action": self._compact_action_for_record(action),
                "metrics": result.get("metrics", {}),
                "artifacts": dict(result.get("artifacts", {})),
            }
            if result.get("error"):
                operation["error"] = result.get("error")
            if tool == "edit_file" and result.get("changed"):
                self._record_edit_snapshot(stage_dir, operation, result, edit_snapshots, sequence)
            self._append_jsonl(operations_path, operation)
            self._append_jsonl(
                stage_dir / "stage_events.jsonl",
                make_stage_event(
                    event="tool_result",
                    stage=stage,
                    success=bool(result.get("success", False)),
                    iteration=operation["iteration"],
                    tool=tool,
                    artifact="operations.jsonl",
                    data={"sequence": sequence},
                ),
            )

    def _record_edit_snapshot(
        self,
        stage_dir: Path,
        operation: Dict[str, Any],
        result: Dict[str, Any],
        edit_snapshots: Dict[str, Dict[str, Any]],
        sequence: int,
    ) -> None:
        rel_path = result.get("path")
        if not rel_path:
            return
        before = edit_snapshots.get(rel_path)
        after_path = Path(result.get("file_path", ""))
        if not before or not after_path.exists():
            return

        before_snapshot = stage_dir / "source_snapshot" / "before" / rel_path
        after_snapshot = stage_dir / "source_snapshot" / "after" / rel_path
        before_snapshot.parent.mkdir(parents=True, exist_ok=True)
        after_snapshot.parent.mkdir(parents=True, exist_ok=True)
        before_snapshot.write_bytes(before["content"])
        after_content = after_path.read_bytes()
        after_snapshot.write_bytes(after_content)

        diff_artifact = f"diffs/iter_{operation['iteration']:03d}_edit_file_{sequence:03d}.patch"
        diff_path = stage_dir / diff_artifact
        diff_path.parent.mkdir(parents=True, exist_ok=True)
        diff_path.write_text(str(result.get("diff", "")), encoding="utf-8")

        operation["artifacts"]["before_snapshot"] = str(
            before_snapshot.relative_to(stage_dir).as_posix()
        )
        operation["artifacts"]["after_snapshot"] = str(
            after_snapshot.relative_to(stage_dir).as_posix()
        )
        operation["artifacts"]["diff_artifact"] = diff_artifact
        self._merge_snapshot_manifest(
            stage_dir / "snapshot_manifest_before.json",
            rel_path,
            before["manifest"],
        )
        self._merge_snapshot_manifest(
            stage_dir / "snapshot_manifest_after.json",
            rel_path,
            self._file_manifest(after_path, content=after_content),
        )

    def _compact_action_for_record(self, action: Dict[str, Any]) -> Dict[str, Any]:
        """Keep operation records useful without duplicating large edit payloads."""
        compact: Dict[str, Any] = {}
        for key in ("type", "file_path", "operation", "reason", "line_start", "line_end", "pattern", "path"):
            if key in action:
                compact[key] = action[key]
        for key in ("content", "old_text", "new_text"):
            value = action.get(key)
            if isinstance(value, str):
                compact[f"{key}_chars"] = len(value)
        return compact

    def _file_manifest(self, path: Path, *, content: Optional[bytes] = None) -> Dict[str, Any]:
        data = content if content is not None else (path.read_bytes() if path.exists() else b"")
        return {
            "exists": path.exists(),
            "bytes": len(data),
            "sha256": hashlib.sha256(data).hexdigest(),
        }

    def _merge_snapshot_manifest(self, path: Path, rel_path: str, entry: Dict[str, Any]) -> None:
        if path.is_file():
            manifest = json.loads(path.read_text(encoding="utf-8"))
        else:
            manifest = {"schema_version": "snapshot_manifest.v1", "files": {}}
        manifest.setdefault("files", {})[rel_path] = entry
        self._write_json(path, manifest)

    def _jsonl_line_count(self, path: Path) -> int:
        if not path.is_file():
            return 0
        return len(path.read_text(encoding="utf-8").splitlines())
    
    def read_file(self, file_path: str) -> str:
        return read_file(file_path)
    
    def write_file(self, file_path: str, content: str):
        return write_file(file_path, content, self._allowed_write_dirs)

    def _coupledl2_stage_dir(self, stage: str) -> Optional[Path]:
        if self.run_context is None:
            return None
        from ..coupledl2.workspace import COUPLEDL2_STAGES

        index = COUPLEDL2_STAGES.index(stage) + 1
        path = self.run_context.results_dir / "by_stage" / f"{index:02d}_{stage}"
        path.mkdir(parents=True, exist_ok=True)
        return path

    def _record_coupledl2_stage_result(
        self,
        stage: str,
        stage_result: Dict[str, Any],
        workflow_result: Dict[str, Any],
    ) -> None:
        """Persist CoupledL2 stage/final records in the run results tree."""
        stage_dir = self._coupledl2_stage_dir(stage)
        if stage_dir is None or self.run_context is None:
            return

        normalized_stage_result = normalize_stage_result(stage, stage_result)
        self._write_json(stage_dir / "stage_result.json", normalized_stage_result)
        handoff = self._build_stage_handoff(stage, normalized_stage_result, workflow_result)
        self._write_json(stage_dir / "handoff.json", handoff)
        self._append_jsonl(
            stage_dir / "stage_events.jsonl",
            make_stage_event(
                event="stage_handoff",
                stage=stage,
                success=bool(workflow_result.get("success")),
                artifact="handoff.json",
                data={"schema_version": handoff.get("schema_version")},
            ),
        )
        self._append_jsonl(
            stage_dir / "stage_events.jsonl",
            make_stage_event(
                event="stage_result",
                stage=stage,
                success=bool(workflow_result.get("success")),
                artifact="stage_result.json",
                data={
                    "summary": stage_result.get("summary"),
                    "schema_version": normalized_stage_result.get("schema_version"),
                },
            ),
        )
        self._write_run_cost_summary()

        if stage == "invoke_verification":
            formal = stage_result.get("jaspergold_result") or stage_result.get("formal_result")
            if isinstance(formal, dict):
                self._write_json(stage_dir / "formal_result.json", formal)
                self._write_json(stage_dir / "property_status.json", formal.get("property_statuses", {}))

        if stage == "propose_bugfix":
            repair = stage_result.get("repair_loop") or stage_result
            if isinstance(repair, dict):
                self._write_json(stage_dir / "repair_result.json", repair)
                self._write_json(stage_dir / "repair_history.json", {"rounds": repair.get("rounds", [])})

        if stage == "propose_bugfix" or workflow_result.get("success") is False:
            final = {
                "schema_version": "final_result.v1",
                "stage": stage,
                "success": bool(workflow_result.get("success")),
                "stage_result": normalized_stage_result,
                "run_dir": str(self.run_context.run_dir),
                "case_name": self.run_context.config.case_name,
            }
            self._write_json(self.run_context.results_dir / "final_result.json", final)
            self._append_jsonl(
                stage_dir / "stage_events.jsonl",
                make_stage_event(
                    event="final_result",
                    stage=stage,
                    success=bool(workflow_result.get("success")),
                    artifact="../../final_result.json",
                ),
            )

    def _build_stage_handoff(
        self,
        stage: str,
        stage_result: Dict[str, Any],
        workflow_result: Dict[str, Any],
    ) -> Dict[str, Any]:
        """Build the compact cross-stage contract for downstream stages."""
        handoff: Dict[str, Any] = {
            "schema_version": STAGE_HANDOFF_SCHEMA_VERSION,
            "stage": stage,
            "success": bool(workflow_result.get("success")),
            "summary": stage_result.get("summary"),
            "artifacts": {
                "stage_result": "stage_result.json",
                "stage_events": "stage_events.jsonl",
                "operations": "operations.jsonl",
            },
        }
        if stage in {"build_top_module", "write_assertions", "propose_bugfix"}:
            handoff["source_snapshot"] = {
                "before_manifest": "snapshot_manifest_before.json",
                "after_manifest": "snapshot_manifest_after.json",
            }
        if stage == "build_top_module":
            handoff["build"] = {
                "generated_files": stage_result.get("generated_files", []),
                "top_module": stage_result.get("top_module"),
                "verification_passed": stage_result.get("verification_passed"),
            }
        elif stage == "write_assertions":
            handoff["assertions"] = {
                "assertion_map": "assertion_map.json",
                "generated_assertion_scan": "generated_assertion_scan.json",
                "verification_passed": stage_result.get("verification_passed"),
            }
        elif stage == "invoke_verification":
            handoff["verification"] = {
                "verification_passed": stage_result.get("verification_passed"),
                "counterexample_path": stage_result.get("counterexample_path"),
                "cex_count": stage_result.get("cex_count"),
                "formal_result": "formal_result.json",
                "property_status": "property_status.json",
            }
        elif stage == "waveform_explanation":
            handoff["diagnosis"] = {
                "error_type": stage_result.get("error_type"),
                "root_cause": stage_result.get("root_cause"),
                "counterexample_path": stage_result.get("counterexample_path"),
                "diagnosis": "diagnosis.json",
                "counterexample_analysis": "counterexample_analysis.md",
            }
        elif stage == "propose_bugfix":
            repair = stage_result.get("repair_loop") or stage_result
            handoff["repair"] = {
                "repair_success": repair.get("repair_success") if isinstance(repair, dict) else None,
                "repair_result": "repair_result.json",
                "repair_history": "repair_history.json",
            }
        return handoff

    def _write_run_cost_summary(self) -> None:
        """Persist token/cost metrics if the configured LLM exposes usage."""
        if self.run_context is None or not hasattr(self.llm, "get_token_usage"):
            return
        try:
            usage = self.llm.get_token_usage()
        except Exception as exc:
            self.logger.warning(f"Failed to collect run cost summary: {exc}")
            return
        self._write_json(
            self.run_context.results_dir / "run_cost_summary.json",
            build_run_cost_summary(usage),
        )

    def _write_coupledl2_diagnosis(self, args: Dict[str, Any], result: Dict[str, Any]) -> None:
        """Write the stage-4 machine-readable diagnosis artifact."""
        stage_dir = self._coupledl2_stage_dir("waveform_explanation")
        if stage_dir is None:
            return

        property_name = (
            args.get("property")
            or args.get("target_assertion_label")
            or self._first_failing_property()
            or "unknown"
        )
        classification = args.get("error_type") or "inconclusive"
        evidence = []
        for key in ("summary", "root_cause", "counterexample_path"):
            value = args.get(key) or result.get(key)
            if value:
                evidence.append({"kind": key, "value": value})
        diagnosis = {
            "diagnoses": [
                {
                    "property": property_name,
                    "classification": classification,
                    "evidence": evidence,
                    "uncertainty": args.get("uncertainty") or "not_reported",
                }
            ],
            "summary": args.get("summary", result.get("summary", "")),
        }
        self._write_json(stage_dir / "diagnosis.json", diagnosis)
        report_path = stage_dir / "counterexample_analysis.md"
        if not report_path.exists():
            report_path.write_text(str(diagnosis["summary"]), encoding="utf-8")

    def _first_failing_property(self) -> Optional[str]:
        data = self.last_verification_result or {}
        for key in ("failing_properties", "inconclusive_properties"):
            values = data.get(key)
            if isinstance(values, list) and values:
                return str(values[0])
        nested = data.get("jaspergold_result")
        if isinstance(nested, dict):
            for key in ("failing_properties", "inconclusive_properties"):
                values = nested.get(key)
                if isinstance(values, list) and values:
                    return str(values[0])
        return None

    @staticmethod
    def _write_json(path: Path, value: Dict[str, Any]) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(
            json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
            encoding="utf-8",
        )

    @staticmethod
    def _append_jsonl(path: Path, value: Dict[str, Any]) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        with path.open("a", encoding="utf-8") as handle:
            handle.write(json.dumps(value, ensure_ascii=False, sort_keys=True) + "\n")
