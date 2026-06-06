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
from .waveform_actions import WaveformActions
from .tool_schemas import get_tool_schemas, convert_tool_call_to_action
from .actions import execute_stage_actions
from .build_operations import BuildOperations
from .repair_loop import (
    DEFAULT_MAX_REPAIR_ROUNDS,
    build_final_repair_result,
    check_repair_target_presence,
    extract_failing_properties,
    select_next_counterexample,
    snapshot_waveform_artifacts,
    write_repair_json,
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
    
    Supports targets:
    - '<benchmark_name>': Any benchmark in chisel/extra_bench/<benchmark_name>/
    """
    
    def __init__(
        self,
        llm_client: LLMClient,
        chisel_dir: str,
        workspace_dir: str,
        logger: logging.Logger,
        waveform_path: Optional[str] = None,
        stage: str = "build_top_module",
        target: str = "gigamax",
        ablation_modes: Optional[List[str]] = None,
        max_repair_rounds: int = DEFAULT_MAX_REPAIR_ROUNDS,
        initial_verification_result: Optional[Dict[str, Any]] = None,
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
            target: Verification target (benchmark name like 'gigamax', 'philo')
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
        
        # Initialize paths based on target
        self._init_paths()
        
        # Initialize helper classes
        self._init_helpers()
        
        # Initialize waveform actions if waveform path provided
        self._init_waveform_actions()
    
    def _init_paths(self) -> None:
        """Initialize directory paths based on target."""
        # For benchmark targets: work in chisel/extra_bench/<benchmark_name> directory
        self.work_dir = os.path.join(self.chisel_dir, "extra_bench", self.target)
        self.verify_src_dir = self.work_dir
        self.generated_dir = os.path.join(self.work_dir, "generated")
        self.verilog_dir = os.path.join(self.workspace_dir, "verilog", "extra_bench", self.target)

        # Build allowed directories list for file writing
        self._allowed_write_dirs = [self.verify_src_dir]
    
    def _init_helpers(self) -> None:
        """Initialize helper classes for build operations."""
        self.build_ops = BuildOperations(
            chisel_dir=self.chisel_dir,
            work_dir=self.work_dir,
            generated_dir=self.generated_dir,
            verilog_dir=self.verilog_dir,
            workspace_dir=self.workspace_dir,
            logger=self.logger,
        )
    
    def _init_waveform_actions(self) -> None:
        """Initialize waveform actions if waveform path is provided."""
        self.waveform_actions = None
        if self.waveform_path:
            try:
                self.waveform_actions = WaveformActions(self.waveform_path)
            except Exception as e:
                self.logger.error(f"Failed to initialize waveform actions: {e}")
    
    def reset_stage(self, reason: str = "") -> Dict[str, Any]:
        """
        Reset the stage to its initial state by restoring the snapshot.
        
        Args:
            reason: Description of why reset is needed
            
        Returns:
            Dictionary with reset operation result
        """
        if not hasattr(self, '_stage_snapshot') or not self._stage_snapshot:
            return {
                "success": False,
                "error": "No stage snapshot available"
            }
        
        return restore_stage_snapshot(
            self.work_dir, 
            self._stage_snapshot, 
            reason, 
            self.logger
        )
    
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
        
        env_info["work_dir"] = self.work_dir
        env_info["benchmark"] = self.target
        
        context = {
            "user_query": user_query,
            "chisel_dir": self.chisel_dir,
            "workspace_dir": self.workspace_dir,
            "current_stage": self.current_stage,
            "environment": env_info,
            "iterations": [],
            "stage_results": {},
        }

        harness_candidates = []
        for candidate in ("TestTop.scala", "Main.scala"):
            candidate_path = os.path.join(self.work_dir, candidate)
            if os.path.exists(candidate_path):
                harness_candidates.append(candidate_path)
        if harness_candidates:
            context["existing_harness_files"] = harness_candidates
        
        if self.waveform_path:
            context["environment"]["waveform_path"] = self.waveform_path
            if self.waveform_actions and self.waveform_actions.metadata:
                context["environment"]["waveform_metadata"] = self.waveform_actions.metadata
        
        return context
    
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
        self._repeated_tool_call_counts: Dict[str, int] = {}
        self._repeated_tool_call_notified: set = set()
        
        # Capture initial snapshot for benchmark targets (for reset functionality)
        self._stage_snapshot = capture_stage_snapshot(self.work_dir, self.logger)
        self.logger.info(f"Captured stage snapshot: {list(self._stage_snapshot.keys())}")
        
        tool_schemas = get_tool_schemas(stage, target=self.target)
        cache_metadata = self._build_prompt_cache_metadata(stage, tool_schemas)
        
        # Load Scala sources for initial prompt
        scala_for_prompt = None
        if stage != "invoke_verification":
            scala_for_prompt = load_files_from_directory(self.work_dir, ".scala", self.logger)
        
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
        messages = self._build_initial_messages(context, stage, scala_for_prompt, analysis_report)
        
        for i in range(1, max_iterations + 1):
            self._log_llm_request(stage, i, [messages[0], messages[-1]], tool_schemas)
            
            # Use chat_with_tools for agent loop style API call
            response = self.llm.chat_with_tools(
                messages,
                tool_schemas,
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
                    
                    if result.get("stage_complete"):
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
    
    def _build_initial_messages(
        self,
        context: Dict[str, Any],
        stage: str,
        scala_sources: Optional[Dict[str, str]],
        analysis_report: Optional[str]
    ) -> List[Dict[str, Any]]:
        """Build initial message list for the agent loop."""
        # Get work_dir files list for benchmark targets
        work_dir_files = None
        
        # System prompt - stage-specific instructions
        system_prompt = build_system_prompt(
            stage=stage,
            target=self.target,
            chisel_dir=self.chisel_dir,
            workspace_dir=self.workspace_dir,
            work_dir_files=work_dir_files,
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

        # Detect and discourage repeated no-progress tool calls
        self._handle_repeated_tool_calls(stage, function_calls, action_results, messages)
        
        # Check for stage completion
        for fc in function_calls:
            args = fc.get("arguments", {})
            if args.get("stage_complete", False):
                result = self._handle_stage_completion(
                    args, stage, context, iterations, iteration_count, messages
                )
                if result:
                    return {
                        "stage_complete": True,
                        "result": result,
                        "iteration_count": iteration_count,
                    }
        
        return {"stage_complete": False, "iteration_count": iteration_count}

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
        """Inject guidance when the model repeats identical no-progress tool calls."""
        if "no_repeated_waveform_guard" in self.ablation_modes:
            return
        if stage != "waveform_explanation":
            return

        for fc, result in zip(function_calls, action_results):
            if fc.get("name") != "waveform_get_signal_value":
                continue

            args = fc.get("arguments", {}) or {}
            if "signal_names" not in args and "signal_name" in args:
                normalized_args = {
                    "signal_names": [args.get("signal_name")],
                    "times": [args.get("time", 0)]
                }
            else:
                normalized_args = {
                    "signal_names": args.get("signal_names", []),
                    "times": args.get("times", [])
                }

            progress_signature = {
                "value": result.get("value"),
                "resolved_name": result.get("resolved_name"),
                "error": result.get("error"),
                "failed_count": result.get("failed_count", 0),
            }

            repeat_key = json.dumps(
                {
                    "tool": fc.get("name"),
                    "args": normalized_args,
                    "progress": progress_signature,
                },
                ensure_ascii=False,
                sort_keys=True,
            )

            count = self._repeated_tool_call_counts.get(repeat_key, 0) + 1
            self._repeated_tool_call_counts[repeat_key] = count

            if count < 3 or repeat_key in self._repeated_tool_call_notified:
                continue

            self._repeated_tool_call_notified.add(repeat_key)
            self.logger.warning(
                "Detected repeated waveform_get_signal_value call with no progress. "
                "Injecting guidance to switch strategy."
            )

            hints = []
            if result.get("suggestions"):
                hints.append(
                    "Candidates: " + ", ".join(result.get("suggestions", [])[:5])
                )

            guidance = (
                "You are repeating the same `waveform_get_signal_value` query with the same outcome. "
                "Stop repeating it. First call `waveform_find_signals` with a focused pattern, then use the exact "
                "returned signal name (including bit-range suffix like ` [15:0]` when present). "
                "If the exact signal cannot be resolved after one retry, move on to other relevant signals and continue the analysis."
            )
            if hints:
                guidance += "\n\n" + "\n".join(hints)

            messages.append({
                "role": "user",
                "content": guidance
            })
    
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
            and args.get("stage_complete", False)
            and error_type == "assertion_error"
            and "homologous_assertions" not in args
        ):
            messages.append({
                "role": "user",
                "content": (
                    "For an `assertion_error` repair you must inspect and report homologous assertions. "
                    "Call `write_fix` again with `homologous_assertions` listing every structurally identical "
                    "assertion repaired, or an empty list only if none exist."
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
        return execute_stage_actions(
            actions=actions,
            work_dir=self.work_dir,
            waveform_actions=self.waveform_actions,
            causal_actions=self.causal_actions,
            read_file_func=self.read_file,
            write_file_func=self.write_file,
            reset_stage_func=self.reset_stage,
            logger=self.logger
        )
    
    def read_file(self, file_path: str) -> str:
        return read_file(file_path)
    
    def write_file(self, file_path: str, content: str):
        return write_file(file_path, content, self._allowed_write_dirs)
