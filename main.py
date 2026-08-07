#!/usr/bin/env python3
"""Command-line entry points for SpecFlow and CoupledL2."""

from __future__ import annotations

import argparse
import json
import os
import sys
from datetime import datetime, timezone
from pathlib import Path

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "src"))


def main_specflow(args: argparse.Namespace) -> None:
    """Run the three-stage SpecFlow method."""

    if args.specflow_action == "start":
        from src.chiselspecflow.authoring import run_asset_authoring
        from src.chiselspecflow.config import SpecFlowRunConfig, load_project_contract
        from src.chiselspecflow.preflight import prepare_workspace
        from src.core.llm_client import LLMClient

        project_path = Path(args.project_contract).resolve()
        project = load_project_contract(project_path)
        run_root = Path(args.run_root).resolve()
        stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
        run_dir = run_root / f"{stamp}-{project.project_id}-{os.urandom(4).hex()}"
        workspace = prepare_workspace(
            SpecFlowRunConfig(
                project_contract=project_path,
                specification=Path(args.spec).resolve(),
                configuration=Path(args.config).resolve(),
                run_root=run_root,
                opaque_task_id=args.task_id,
                expected_property_ids=tuple(args.expected_property_id or ()),
                component_ids=tuple(args.component_id or ()),
            ),
            run_dir,
            Path(args.suite_ledger).resolve(),
        )
        result = run_asset_authoring(
            workspace,
            LLMClient(
                model=args.model,
                llm_url=args.url,
                max_token_budget=args.max_tokens,
                raw_response_dir=run_dir / "logs/raw_model_responses",
            ),
        )
        print(
            json.dumps(
                {"run_dir": str(run_dir), "status": result.status},
                sort_keys=True,
            )
        )
        return

    if args.specflow_action == "review":
        from src.chiselspecflow.review import install_review

        result = install_review(Path(args.run), Path(args.review_record))
        print(
            json.dumps(
                {
                    "run_dir": str(Path(args.run).resolve()),
                    "status": result.get("status"),
                    "success": result.get("success") is True,
                },
                sort_keys=True,
            )
        )
        return

    if args.specflow_action == "resume":
        from src.chiselspecflow.runner import run_compile_verify

        run_path = Path(args.run)
        if args.through == "compile_verify":
            result = run_compile_verify(
                run_path,
                timeout_seconds=args.timeout_seconds,
                per_property_seconds=args.per_property_seconds,
            )
        else:
            from src.chiselspecflow.diagnosis import run_diagnose
            from src.chiselspecflow.runner import load_existing_workspace
            from src.chiselspecflow.stages import get_stage_spec
            from src.core.artifact_contract import validate_completed_stage

            workspace = load_existing_workspace(run_path)
            stage2 = workspace.stage_dir("compile_verify")
            if (
                validate_completed_stage(
                    stage2, get_stage_spec("compile_verify")
                )
                is None
            ):
                run_compile_verify(
                    run_path,
                    timeout_seconds=args.timeout_seconds,
                    per_property_seconds=args.per_property_seconds,
                )
            result = run_diagnose(run_path)
        print(
            json.dumps(
                {
                    "run_dir": str(run_path.resolve()),
                    "status": result.get("status"),
                    "formal_outcome": result.get("formal_outcome"),
                    "evidence_status": result.get("evidence_status"),
                    "final_verdict": result.get("final_verdict"),
                    "model_calls": result.get("model_calls"),
                },
                sort_keys=True,
            )
        )
        return

    raise ValueError("specflow requires start, review, or resume")


def main_coupledl2_run(args: argparse.Namespace) -> None:
    """Create or resume one CoupledL2 run."""

    from src.core.llm_client import LLMClient
    from src.coupledl2.config import CoupledL2RunConfig
    from src.coupledl2.runner import CoupledL2Runner
    from src.coupledl2.workspace import (
        create_coupledl2_workspace,
        load_coupledl2_workspace,
    )
    from src.utils.logger import get_logger

    if not (args.preflight_only or args.full or args.stage):
        raise SystemExit("CoupledL2 run requires --preflight-only, --stage, or --full")
    if args.resume_run and (args.preflight_only or args.full or not args.stage):
        raise SystemExit("--resume-run requires one explicit --stage")

    if args.resume_run:
        workspace = load_coupledl2_workspace(Path(args.resume_run))
    else:
        if not args.property_profile:
            raise SystemExit("a fresh run requires --property-profile")
        workspace = create_coupledl2_workspace(
            CoupledL2RunConfig(
                case_path=Path(args.case),
                property_profile=args.property_profile,
                verify_mode=args.mode,
                input_mode=args.input_mode,
                run_root=Path(args.run_root),
            )
        )
    logger = get_logger(
        __name__,
        console_output=True,
    )

    if not args.resume_run:
        preflight = CoupledL2Runner(
            workspace=workspace,
            logger=logger,
            llm_client=None,
        ).preflight()
        if args.preflight_only:
            print(
                json.dumps(
                    {"run_dir": str(workspace.run_dir), **preflight},
                    indent=2,
                    ensure_ascii=False,
                )
            )
            if not preflight.get("success"):
                raise SystemExit(1)
            return
        if not preflight.get("success"):
            raise SystemExit(
                f"CoupledL2 preflight failed: {preflight.get('termination_reason')}"
            )

    client = LLMClient(
        max_token_budget=args.max_tokens,
        logger=logger,
    )
    result = CoupledL2Runner(
        workspace=workspace,
        logger=logger,
        llm_client=client,
        resumed=bool(args.resume_run),
    ).run(stage=args.stage, full=args.full)
    logger.info("model usage: %s", client.get_token_usage())
    print(json.dumps(result, indent=2, ensure_ascii=False))
    if not result.get("success"):
        raise SystemExit(1)


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="ChiselLMFV")
    subparsers = parser.add_subparsers(dest="command", required=True)

    run = subparsers.add_parser("run", help="CoupledL2 preflight and verification")
    source = run.add_mutually_exclusive_group(required=True)
    source.add_argument("--case")
    source.add_argument("--resume-run")
    run.add_argument("--mode", default="small", choices=["small"])
    run.add_argument(
        "--input-mode", default="coupledl2asl1", choices=["coupledl2asl1"]
    )
    from src.coupledl2.config import list_property_profiles

    run.add_argument("--property-profile", choices=list_property_profiles())
    run.add_argument("--run-root", default="runs")
    run.add_argument("--full", action="store_true")
    run.add_argument(
        "--stage",
        choices=["bind_properties", "invoke_verification"],
    )
    run.add_argument("--preflight-only", action="store_true")
    run.add_argument("--max-tokens", type=int)

    specflow = subparsers.add_parser("specflow", help="three-stage SpecFlow")
    actions = specflow.add_subparsers(dest="specflow_action", required=True)
    start = actions.add_parser("start")
    start.add_argument("--project-contract", required=True)
    start.add_argument("--spec", required=True)
    start.add_argument("--config", required=True)
    start.add_argument("--run-root", default="runs/specflow")
    start.add_argument(
        "--suite-ledger", default="benchmark/synth/SPECIFICATIONS.sha256"
    )
    start.add_argument("--task-id")
    start.add_argument("--expected-property-id", action="append")
    start.add_argument("--component-id", action="append")
    start.add_argument("--max-tokens", type=int)
    start.add_argument("--model")
    start.add_argument("--url")
    review = actions.add_parser("review")
    review.add_argument("--run", required=True)
    review.add_argument("--review-record", required=True)
    resume = actions.add_parser("resume")
    resume.add_argument("--run", required=True)
    resume.add_argument(
        "--through",
        choices=["compile_verify", "diagnose"],
        default="compile_verify",
    )
    resume.add_argument("--timeout-seconds", type=int, default=300)
    resume.add_argument("--per-property-seconds", type=int, default=60)
    from src.experiments.paper import add_parser as add_experiment_parser
    from src.experiments.specflow_exp import add_parser as add_specflow_exp_parser
    from src.experiments.chiselcause_exp import add_parser as add_chiselcause_exp_parser

    add_experiment_parser(subparsers)
    add_specflow_exp_parser(subparsers)
    add_chiselcause_exp_parser(subparsers)
    return parser.parse_args(argv)


def main() -> None:
    args = parse_args()
    {
        "specflow": main_specflow,
        "run": main_coupledl2_run,
        "experiment": _main_experiment,
        "specflow-exp": _main_specflow_experiment,
        "chiselcause-exp": _main_chiselcause_experiment,
    }[args.command](args)


def _main_experiment(args: argparse.Namespace) -> None:
    from src.experiments.paper import run

    run(args)


def _main_specflow_experiment(args: argparse.Namespace) -> None:
    from src.experiments.specflow_exp import run

    run(args)


def _main_chiselcause_experiment(args: argparse.Namespace) -> None:
    from src.experiments.chiselcause_exp import run

    run(args)


if __name__ == "__main__":
    main()
