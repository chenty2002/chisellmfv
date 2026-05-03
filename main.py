#!/usr/bin/env python3
"""
ChiselLMFV - 统一入口

基于 LLM 的 Chisel 形式化验证工具，提供两种工作流：

1. 形式化验证 (Formal Verification)：五阶段自动化工作流
   - build_top_module → write_assertions → invoke_verification
   - → waveform_explanation (结合 VerilogCausalAnalysis 因果分析)
   - → propose_bugfix
2. Verilog→Chisel 转换 (Verilog2Chisel)：自动将 Verilog 代码转换为 Chisel

使用方式：
    # 形式化验证
    python main.py formal --full --chisel-dir chisel/ --target gigamax
    python main.py formal --stage write_assertions --target gigamax

    # Verilog 转 Chisel
    python main.py v2c --target <benchmark_name> --max-iterations 5
"""

import os
import sys
import argparse
from typing import Optional, List, Dict, Any

# 将 src 目录添加到路径
sys.path.insert(0, os.path.join(os.path.dirname(__file__), 'src'))


def _exit(llm_client, logger, success: bool):
    """辅助退出函数，打印 token 使用情况"""
    llm_client.print_token_usage(logger)
    sys.exit(0 if success else 1)


def main_formal(args):
    """运行五阶段形式化验证工作流"""
    from src.core.llm_client import LLMClient, TokenBudgetExceeded
    from src.core.workflow import FormalWorkflow
    from src.utils.logger import get_logger
    from src.core.tool_schemas import FORMAL_STAGES

    targets = _parse_formal_targets(args)

    # 设置日志
    log_target = "batched" if len(targets) > 1 else targets[0]
    if args.full:
        logger = get_logger(__name__, console_output=False, clear_log=True,
                            base_name=f"application-formal-full-{log_target}.log")
    elif args.stage:
        logger = get_logger(__name__, console_output=False, clear_log=True,
                            base_name=f"application-formal-{args.stage}-{log_target}.log")
    else:
        logger = get_logger(__name__, console_output=False, clear_log=True,
                            base_name=f"application-formal-{log_target}.log")

    # 创建 LLM 客户端
    max_tokens = getattr(args, 'max_tokens', None)
    llm_client = LLMClient(max_token_budget=max_tokens)
    if max_tokens:
        logger.info(f"Token budget set to {max_tokens}")

    try:
        if args.full and len(targets) > 1:
            success = _run_stage_batched_formal(
                args=args,
                targets=targets,
                llm_client=llm_client,
                logger=logger,
                workflow_cls=FormalWorkflow,
                formal_stages=FORMAL_STAGES,
            )
            _exit(llm_client, logger, success=success)

        if args.stage and len(targets) > 1:
            success = _run_multi_target_stage(
                args=args,
                targets=targets,
                llm_client=llm_client,
                logger=logger,
                workflow_cls=FormalWorkflow,
            )
            _exit(llm_client, logger, success=success)

        target = targets[0]
        workflow = FormalWorkflow(
            llm_client=llm_client,
            chisel_dir=args.chisel_dir,
            workspace_dir=args.workspace_dir,
            logger=logger,
            target=target,
            waveform_path=args.waveform,
            stage=args.stage if args.stage else FORMAL_STAGES[0],
        )

        if args.full:
            start_stage = args.start_stage if args.start_stage else FORMAL_STAGES[0]
            logger.info(f"Starting full workflow from stage: {start_stage}")

            start_idx = FORMAL_STAGES.index(start_stage)
            for stage in FORMAL_STAGES[start_idx:]:
                logger.info("=" * 80)
                logger.info(f"Running stage: {stage}")
                logger.info("=" * 80)

                workflow.current_stage = stage
                result = workflow.process_task(
                    user_query=get_default_query(stage=stage, target=target),
                )
                success = result.get("success", False)

                if not success:
                    logger.error(f"Stage {stage} failed. Stopping workflow.")
                    _exit(llm_client, logger, success=False)

                # If formal verification proved all assertions there is no
                # counterexample to analyse, so the workflow is done.
                if stage == "invoke_verification":
                    stage_result_detail = result.get("stage_result", {})
                    if stage_result_detail.get("verification_passed", False):
                        logger.info(
                            "All assertions proven - formal verification passed. "
                            "No counterexample to analyse, workflow complete."
                        )
                        print("\nAll assertions proven - formal verification passed!")
                        _exit(llm_client, logger, success=True)

            logger.info("Full workflow completed successfully!")
            _exit(llm_client, logger, success=True)

        elif args.stage:
            logger.info(f"Running single stage: {args.stage}")

            result = workflow.process_task(
                user_query=get_default_query(stage=args.stage, target=args.target),
            )
            success = result.get("success", False)

            if not success:
                logger.error(f"Stage {args.stage} failed")
            else:
                logger.info(f"Stage {args.stage} completed successfully")
            _exit(llm_client, logger, success=success)
        else:
            logger.error("Must specify either --full or --stage")
            _exit(llm_client, logger, success=False)
    except TokenBudgetExceeded as e:
        logger.error(f"Token budget exceeded during formal workflow: {e}")
        print(f"\nStopping: {e}")
        _exit(llm_client, logger, success=False)


def _parse_formal_targets(args) -> List[str]:
    """Parse either --target or comma-separated --targets."""
    raw_targets = getattr(args, "targets", None)
    if raw_targets:
        targets = [target.strip() for target in raw_targets.split(",") if target.strip()]
        if targets:
            return targets
    return [args.target]


def _run_multi_target_stage(
    args,
    targets: List[str],
    llm_client,
    logger,
    workflow_cls,
) -> bool:
    """Run one requested stage across several targets."""
    ok = True
    for target in targets:
        logger.info("=" * 80)
        logger.info(f"Running stage {args.stage} for target: {target}")
        logger.info("=" * 80)
        workflow = workflow_cls(
            llm_client=llm_client,
            chisel_dir=args.chisel_dir,
            workspace_dir=args.workspace_dir,
            logger=logger,
            target=target,
            waveform_path=args.waveform if len(targets) == 1 else None,
            stage=args.stage,
        )
        result = workflow.process_task(
            user_query=get_default_query(stage=args.stage, target=target),
        )
        if not result.get("success", False):
            logger.error(f"Stage {args.stage} failed for target {target}")
            ok = False
    return ok


def _run_stage_batched_formal(
    args,
    targets: List[str],
    llm_client,
    logger,
    workflow_cls,
    formal_stages: List[str],
) -> bool:
    """Run full formal workflow stage-first across targets for prompt-cache locality."""
    start_stage = args.start_stage if args.start_stage else formal_stages[0]
    start_idx = formal_stages.index(start_stage)
    active: Dict[str, bool] = {target: True for target in targets}
    failed: Dict[str, str] = {}
    state: Dict[str, Dict[str, Any]] = {
        target: {"waveform_path": None, "verification_passed": False}
        for target in targets
    }

    logger.info(f"Starting stage-batched full workflow from stage: {start_stage}")
    logger.info(f"Targets: {', '.join(targets)}")

    for stage in formal_stages[start_idx:]:
        runnable = [target for target in targets if active[target]]
        if not runnable:
            break

        logger.info("=" * 80)
        logger.info(f"Stage-batched run: {stage} for {len(runnable)} active targets")
        logger.info("=" * 80)

        for target in runnable:
            waveform_path = state[target].get("waveform_path") if stage == "waveform_explanation" else None
            if stage == "waveform_explanation" and not waveform_path:
                failed[target] = "missing_counterexample_waveform"
                active[target] = False
                logger.error(f"Target {target} has no waveform path for waveform_explanation")
                continue

            logger.info(f"Running stage {stage} for target: {target}")
            workflow = workflow_cls(
                llm_client=llm_client,
                chisel_dir=args.chisel_dir,
                workspace_dir=args.workspace_dir,
                logger=logger,
                target=target,
                waveform_path=waveform_path,
                stage=stage,
            )
            result = workflow.process_task(
                user_query=get_default_query(stage=stage, target=target),
            )

            if not result.get("success", False):
                failed[target] = stage
                active[target] = False
                logger.error(f"Stage {stage} failed for target {target}")
                continue

            if stage == "invoke_verification":
                detail = result.get("stage_result", {})
                if detail.get("verification_passed", False):
                    active[target] = False
                    state[target]["verification_passed"] = True
                    logger.info(f"Target {target}: all assertions proven; skipping CEX stages")
                    continue

                cex_path = detail.get("counterexample_path")
                if cex_path:
                    state[target]["waveform_path"] = cex_path
                    logger.info(f"Target {target}: counterexample waveform {cex_path}")
                else:
                    failed[target] = "missing_counterexample_waveform"
                    active[target] = False
                    logger.error(f"Target {target}: verification failed but no waveform was found")

    if failed:
        logger.error(f"Stage-batched workflow failures: {failed}")
    else:
        logger.info("Stage-batched workflow completed successfully")
    return not failed


def main_verilog2chisel(args):
    """运行 Verilog 到 Chisel 转换工作流"""
    from src.core.llm_client import LLMClient, TokenBudgetExceeded
    from src.verilog2chisel.workflow import Verilog2ChiselWorkflow
    from src.utils.logger import get_logger

    logger = get_logger(__name__, console_output=False, clear_log=True,
                        base_name=f"application-verilog2chisel-{args.target}.log")

    max_tokens = getattr(args, 'max_tokens', None)
    llm_client = LLMClient(max_token_budget=max_tokens)
    if max_tokens:
        logger.info(f"Token budget set to {max_tokens}")

    workflow = Verilog2ChiselWorkflow(
        llm_client=llm_client,
        workspace_dir=args.workspace_dir,
        benchmark=args.target,
        logger=logger,
        max_iterations=args.max_iterations,
    )

    try:
        result = workflow.convert()
        success = result.get("success", False)

        if success:
            logger.info("Verilog to Chisel conversion completed successfully!")
        else:
            logger.error("Verilog to Chisel conversion failed")
        _exit(llm_client, logger, success=success)
    except TokenBudgetExceeded as e:
        logger.error(f"Token budget exceeded during v2c workflow: {e}")
        print(f"\nStopping: {e}")
        _exit(llm_client, logger, success=False)


def get_default_query(stage: Optional[str] = None, target: str = "gigamax") -> str:
    """
    Get the default query for a stage or full workflow.

    Args:
        stage: Specific stage name, or None for full workflow
        target: Verification target (benchmark name like 'gigamax')

    Returns:
        Default query string
    """
    benchmark_queries = {
        "build_top_module": (
            "Verify the existing Chisel test harness module. "
            "If it already exists and is correct, confirm it. Otherwise, create or fix it."
        ),
        "write_assertions": (
            "Add formal verification assertions to the design using ChiselFV or Chisel LTL. "
            "Focus on key properties like safety and liveness."
        ),
        "invoke_verification": (
            "Compile the design with 'make verilog' and fix any compilation errors."
        ),
        "waveform_explanation": (
            "Analyze the counterexample waveform to identify the bug. "
            "Trace signal values and identify the root cause. "
            "Use the causal analysis report as prior evidence when available."
        ),
        "propose_bugfix": (
            "Based on the waveform analysis, implement a minimal fix for the identified bug."
        ),
    }

    if stage and stage in benchmark_queries:
        return benchmark_queries[stage]

    return (
        f"Verify and add formal verification assertions to the {target} Chisel design. "
        "Use ChiselFV or Chisel LTL to express key properties."
    )


def parse_args():
    """解析命令行参数"""
    parser = argparse.ArgumentParser(
        description="ChiselLMFV - LLM 驱动的 Chisel 形式化验证工具",
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )

    subparsers = parser.add_subparsers(dest='command', help='选择工作流')

    # 形式化验证
    formal_parser = subparsers.add_parser('formal', help='五阶段形式化验证工作流')
    formal_parser.add_argument('--full', action='store_true', help='运行完整工作流')
    formal_parser.add_argument('--stage', type=str,
                               choices=['build_top_module', 'write_assertions',
                                        'invoke_verification', 'waveform_explanation',
                                        'propose_bugfix'],
                               help='运行单个阶段')
    formal_parser.add_argument('--start-stage', type=str, default=None,
                               help='从指定阶段开始（仅 --full 模式）')
    formal_parser.add_argument('--chisel-dir', type=str, default='chisel',
                               help='Chisel 项目目录（默认: chisel）')
    formal_parser.add_argument('--workspace-dir', type=str, default=os.path.abspath('.'),
                               help='工作空间目录（默认: .）')
    formal_parser.add_argument('--target', type=str, default='gigamax',
                               help='验证目标：benchmark 名称（默认: gigamax）')
    formal_parser.add_argument('--targets', type=str, default=None,
                               help='逗号分隔的多个 benchmark 名称；--full 时按 stage 批处理以提高 prompt cache 命中率')
    formal_parser.add_argument('--waveform', type=str, help='波形文件路径（仅 waveform_explanation 阶段）')
    formal_parser.add_argument('--max-tokens', type=int, default=None,
                               help='Token 总量限制（所有 API 调用累计，超出后停止）')

    # Verilog → Chisel 转换
    v2c_parser = subparsers.add_parser('v2c', help='Verilog 到 Chisel 转换')
    v2c_parser.add_argument('--workspace-dir', type=str, default=os.path.abspath('.'),
                            help='工作空间目录（默认: .）')
    v2c_parser.add_argument('--target', type=str, required=True,
                            help='benchmark 名称（对应 verilog2chisel/verilog/<benchmark> 目录）')
    v2c_parser.add_argument('--max-iterations', type=int, default=5,
                            help='最大编译重试次数（默认: 5）')
    v2c_parser.add_argument('--max-tokens', type=int, default=None,
                            help='Token 总量限制（所有 API 调用累计，超出后停止）')

    return parser.parse_args()


def main():
    """主入口"""
    args = parse_args()

    if args.command == 'formal':
        main_formal(args)
    elif args.command == 'v2c':
        main_verilog2chisel(args)
    else:
        print("错误: 请指定工作流类型 (formal, v2c)")
        print("运行 'python main.py --help' 查看帮助")
        sys.exit(1)


if __name__ == "__main__":
    main()
