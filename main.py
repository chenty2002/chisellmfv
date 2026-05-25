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
import json
import shutil
from pathlib import Path
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

    if not _ensure_formal_targets_available(args, targets, logger):
        sys.exit(1)

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
                    user_query=_resolve_formal_query(args, stage=stage, target=target),
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
                user_query=_resolve_formal_query(args, stage=args.stage, target=target),
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


def _resolve_formal_query(args, stage: Optional[str], target: str) -> str:
    """Resolve a custom formal query, falling back to the stage default."""
    raw_query = getattr(args, "query", None)
    if raw_query is not None:
        query = str(raw_query)
        if query.strip().lower() != "none":
            return query

    raw_query_file = getattr(args, "query_file", None)
    if raw_query_file is not None:
        query_file = str(raw_query_file)
        if query_file.strip().lower() != "none":
            return Path(query_file).read_text(encoding="utf-8").rstrip("\n")

    return get_default_query(stage=stage, target=target)


def _resolve_under_workspace(workspace_dir: str, path: str) -> Path:
    """Resolve an absolute path or a path relative to the workspace root."""
    candidate = Path(path)
    if candidate.is_absolute():
        return candidate
    return Path(workspace_dir).resolve() / candidate


def _ensure_formal_targets_available(args, targets: List[str], logger) -> bool:
    """
    Ensure formal benchmark targets exist under chisel/extra_bench.

    Existing extra_bench targets are left untouched. Missing targets are copied
    from benchmark/vis-chisel/<target> when available.
    """
    workspace_root = Path(args.workspace_dir).resolve()
    chisel_root = _resolve_under_workspace(str(workspace_root), args.chisel_dir)
    extra_bench_root = chisel_root / "extra_bench"
    vis_chisel_root = workspace_root / "benchmark" / "vis-chisel"

    ok = True
    for target in targets:
        dst = extra_bench_root / target
        if dst.is_dir():
            logger.info(f"Using existing formal benchmark target: {dst}")
            continue

        src = vis_chisel_root / target
        if not src.is_dir():
            logger.error(
                f"Formal benchmark target {target!r} was not found in "
                f"{extra_bench_root} or {vis_chisel_root}"
            )
            print(
                f"错误: 未找到 benchmark {target!r}。已查找 "
                f"{extra_bench_root / target} 和 {src}"
            )
            ok = False
            continue

        try:
            extra_bench_root.mkdir(parents=True, exist_ok=True)
            shutil.copytree(
                src,
                dst,
                ignore=shutil.ignore_patterns("generated", "target", ".bsp", ".metals"),
            )
        except OSError as exc:
            logger.error(f"Failed to copy formal benchmark target {target!r}: {exc}")
            print(f"错误: 复制 benchmark {target!r} 失败: {exc}")
            ok = False
            continue

        logger.info(f"Copied formal benchmark target {target!r} from {src} to {dst}")
        print(f"Copied benchmark {target!r} from {src} to {dst}")

    return ok


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
            user_query=_resolve_formal_query(args, stage=args.stage, target=target),
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
                user_query=_resolve_formal_query(args, stage=stage, target=target),
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


def main_quality(args):
    """Run deterministic JasperGold quality evaluation."""
    from src.core.jaspergold_quality import (
        JasperGoldQualityRunner,
        QualityConfig,
        load_sidecars,
    )

    if args.counter:
        args.case_id = args.case_id or "counter"
        args.workdir = args.workdir or "verilog/extra_bench/counter"
        args.dut_sv = args.dut_sv or "TestTop.sv"
        args.extra_sv = args.extra_sv or "ResetCounter.sv"
        args.top = args.top or "Counter"
        args.clock = args.clock or "clock"
        args.reset = args.reset or "reset"
        args.expected_inputs = args.expected_inputs or "clock,reset"
        args.expected_outputs = args.expected_outputs or "io_out0,io_out1,io_out2"
        args.trace_signals = args.trace_signals or "io_out0,io_out1,io_out2"

    stages = _parse_quality_stages(args)
    config = QualityConfig(
        case_id=args.case_id,
        candidate_id=args.candidate_id,
        workdir=args.workdir,
        dut_sv=_split_csv(args.dut_sv),
        extra_sv=_split_csv(args.extra_sv),
        top=args.top,
        clock=args.clock,
        reset=args.reset,
        report_root=args.reports_dir,
        expected_inputs=_split_csv(args.expected_inputs),
        expected_outputs=_split_csv(args.expected_outputs),
        prove_time_limit=args.prove_time_limit,
        assume_time_limit=args.assume_time_limit,
        nv_time_limit=args.nv_time_limit,
        mutation_time_limit=args.mutation_time_limit,
        repair_regression_time_limit=args.repair_regression_time_limit,
        sec_time_limit=args.sec_time_limit,
        xprop_time_limit=args.xprop_time_limit,
        jg_timeout=args.jg_timeout,
        trace_signals=_split_csv(args.trace_signals),
    )
    sidecars = load_sidecars(args.sidecar) if "non_vacuity" in stages else []
    runner = JasperGoldQualityRunner(config)
    record = runner.run(
        stages,
        non_vacuity_sidecars=sidecars,
        max_mutants=args.max_mutants,
        repair_target_properties=_split_csv(args.repair_target_properties),
        sec_spec_sv=_split_csv(args.sec_spec_sv) or None,
        sec_imp_sv=_split_csv(args.sec_imp_sv) or None,
    )
    record_path = os.path.join(args.reports_dir, args.case_id, "quality_record.json")
    print(json.dumps({
        "record": record_path,
        "stages": stages,
        "scores": record.get("scores", {}),
    }, indent=2, ensure_ascii=False))


def _split_csv(value: Optional[str]) -> List[str]:
    if not value:
        return []
    return [item.strip() for item in value.split(",") if item.strip()]


def _parse_quality_stages(args) -> List[str]:
    if args.all:
        return [
            "build",
            "assertions",
            "assumptions",
            "non_vacuity",
            "mutation",
            "repair_regression",
            "sec",
            "xprop",
        ]
    stages = _split_csv(args.stages)
    return stages or ["build", "assertions"]


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
            "Place assertions directly inside the original DUT module/class emitted by VerilogGenerator, "
            "not in a separate *Formal wrapper or sibling module. "
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
        "Use ChiselFV or Chisel LTL to express key properties directly inside the original "
        "DUT module/class emitted by VerilogGenerator, not in a separate *Formal wrapper."
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
    formal_parser.add_argument('--query', type=str, default=None,
                               help="自定义本阶段 query；为 none 或不提供时使用默认 query")
    formal_parser.add_argument('--query-file', type=str, default=None,
                               help="从文件读取自定义 query；为 none 或不提供时使用默认 query")
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

    # JasperGold quality evaluation
    quality_parser = subparsers.add_parser('quality', help='JasperGold 质量评估')
    quality_parser.add_argument('--counter', action='store_true',
                                help='使用 verilog/extra_bench/counter 的默认 smoke 配置')
    quality_parser.add_argument('--all', action='store_true',
                                help='运行 build,assertions,assumptions,non_vacuity,mutation,repair_regression,sec,xprop')
    quality_parser.add_argument('--stages', type=str, default=None,
                                help='逗号分隔阶段：build,assertions,assumptions,non_vacuity,mutation,repair_regression,sec,xprop')
    quality_parser.add_argument('--case-id', type=str, default=None,
                                help='评估 case ID')
    quality_parser.add_argument('--candidate-id', type=str, default='run_001',
                                help='候选实现 ID')
    quality_parser.add_argument('--workdir', type=str, default=None,
                                help='包含待评估 Verilog 的工作目录')
    quality_parser.add_argument('--dut-sv', type=str, default=None,
                                help='逗号分隔 DUT/SystemVerilog 文件，相对 --workdir 或绝对路径')
    quality_parser.add_argument('--extra-sv', type=str, default='',
                                help='逗号分隔额外 SystemVerilog 文件，相对 --workdir 或绝对路径')
    quality_parser.add_argument('--top', type=str, default=None, help='JasperGold top module')
    quality_parser.add_argument('--clock', type=str, default=None, help='clock 信号')
    quality_parser.add_argument('--reset', type=str, default=None, help='reset 信号')
    quality_parser.add_argument('--expected-inputs', type=str, default='',
                                help='逗号分隔期望 input 端口')
    quality_parser.add_argument('--expected-outputs', type=str, default='',
                                help='逗号分隔期望 output 端口')
    quality_parser.add_argument('--trace-signals', type=str, default='',
                                help='逗号分隔普通 CEX 中要采样的信号')
    quality_parser.add_argument('--reports-dir', type=str, default='reports/jg',
                                help='artifact 根目录')
    quality_parser.add_argument('--sidecar', type=str, default=None,
                                help='non-vacuity assertion sidecar JSON')
    quality_parser.add_argument('--max-mutants', type=int, default=5,
                                help='mutation 阶段最多生成并运行的 mutants')
    quality_parser.add_argument('--repair-target-properties', type=str, default='',
                                help='逗号分隔 stage5 修改要回归验证的目标 assertion/property 名称')
    quality_parser.add_argument('--sec-spec-sv', type=str, default='',
                                help='SEC spec 文件列表；默认使用 --dut-sv')
    quality_parser.add_argument('--sec-imp-sv', type=str, default='',
                                help='SEC implementation 文件列表；默认使用 --dut-sv')
    quality_parser.add_argument('--prove-time-limit', type=str, default='5s')
    quality_parser.add_argument('--assume-time-limit', type=str, default='5s')
    quality_parser.add_argument('--nv-time-limit', type=str, default='5s')
    quality_parser.add_argument('--mutation-time-limit', type=str, default='5s')
    quality_parser.add_argument('--repair-regression-time-limit', type=str, default='5s')
    quality_parser.add_argument('--sec-time-limit', type=str, default='5s')
    quality_parser.add_argument('--xprop-time-limit', type=str, default='5s')
    quality_parser.add_argument('--jg-timeout', type=int, default=900,
                                help='单个 JasperGold 阶段的进程超时秒数')

    return parser.parse_args()


def main():
    """主入口"""
    args = parse_args()

    if args.command == 'formal':
        main_formal(args)
    elif args.command == 'v2c':
        main_verilog2chisel(args)
    elif args.command == 'quality':
        required = {
            "case_id": args.case_id,
            "workdir": args.workdir,
            "dut_sv": args.dut_sv,
            "top": args.top,
            "clock": args.clock,
            "reset": args.reset,
        }
        if not args.counter and any(value in {None, ""} for value in required.values()):
            missing = ", ".join(key for key, value in required.items() if value in {None, ""})
            print(f"错误: quality 缺少必要参数: {missing}")
            print("可使用 --counter 运行 counter smoke 默认配置")
            sys.exit(1)
        main_quality(args)
    else:
        print("错误: 请指定工作流类型 (formal, v2c)")
        print("运行 'python main.py --help' 查看帮助")
        sys.exit(1)


if __name__ == "__main__":
    main()
