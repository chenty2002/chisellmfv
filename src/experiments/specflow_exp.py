"""Bug-level S0/S1/S2 runner for ``specflow_exp.md``."""

from __future__ import annotations

import argparse
import hashlib
import json
import time
from datetime import datetime
from pathlib import Path
from typing import Any, Mapping, Sequence
from zoneinfo import ZoneInfo

from src.experiments.paper import (
    BUG_COUNTS,
    FAMILY_GROUPS,
    SELECTED_AUTHORING_SCOPE,
    _family_entry,
    _variant_parameters,
)
from src.experiments.scoring import (
    family_method_summary,
    method_macro_summary,
    paired_family_differences,
    template_reuse_summary,
    validate_specflow_result,
)


DIFFICULTY = {
    "decoder_3_to_8": "easy",
    "alu": "easy",
    "counter": "medium",
    "led_controller": "medium",
    "arbiter": "medium",
    "fsm_16": "medium",
    "sdram_controller": "hard",
    "sha3": "hard",
    "i2c": "hard",
    "reed_solomon_decoder": "hard",
}
DEVELOPMENT_EXPOSED = frozenset({"counter", "fsm_16", "i2c", "led_controller"})
METHODS = ("s0", "s1", "s2")
DIAGNOSTIC_PROPERTIES = {
    "counter": {
        "property_id": "A3_COUNTER_INCREMENT",
        "sva": """A3_COUNTER_INCREMENT__assert: assert property (
  @(posedge clock) disable iff (reset)
  enable |=> counter_out == ($past(counter_out) + 4'd1));
A3_COUNTER_INCREMENT__activation: cover property (
  @(posedge clock) !reset && enable);""",
    },
    "fsm_16": {
        "property_id": "A3_FSM_S7_11",
        "sva": """A3_FSM_S7_11__assert: assert property (
  @(posedge clock) disable iff (reset)
  (state == 4'd7 && input1 && input2) |=> state == 4'd0);
A3_FSM_S7_11__activation: cover property (
  @(posedge clock) !reset && state == 4'd7 && input1 && input2);""",
    },
    "i2c": {
        "property_id": "A3_I2C_ACK_PULSE",
        "sva": """A3_I2C_ACK_PULSE__assert: assert property (
  @(posedge clock) disable iff (wb_rst_i || !arst_i)
  dut.byte_controller.bit_controller.cmd_ack
  |=> !dut.byte_controller.bit_controller.cmd_ack);
A3_I2C_ACK_PULSE__activation: cover property (
  @(posedge clock) dut.byte_controller.bit_controller.cmd_ack);""",
    },
    "led_controller": {
        "property_id": "A3_LED_GO_EXPIRED",
        "sva": """A3_LED_GO_EXPIRED__assert: assert property (
  @(posedge clock) disable iff (reset)
  (dut.state == 2'd1 && $signed(dut.count) >= 32'sd6)
  |-> lights == 3'b010);
A3_LED_GO_EXPIRED__activation: cover property (
  @(posedge clock) !reset && dut.state == 2'd1 && $signed(dut.count) >= 32'sd6);""",
    },
}


class SpecFlowExperimentError(ValueError):
    pass


def prepare(args: argparse.Namespace) -> Path:
    repo = Path(args.repo).resolve()
    experiment_id = args.experiment_id or datetime.now(
        ZoneInfo("Asia/Shanghai")
    ).strftime("%Y%m%d-%H%M%S")
    run_dir = repo / "runs/specflow-paper" / experiment_id
    if run_dir.exists():
        raise SpecFlowExperimentError(f"experiment already exists: {run_dir}")
    run_dir.mkdir(parents=True)
    family_entries = {
        family: _family_entry(repo, run_dir, family) for family in FAMILY_GROUPS
    }
    tasks = []
    for family in FAMILY_GROUPS:
        entry = family_entries[family]
        for bug in entry["bugs"]:
            index = int(bug["variant_index"])
            tasks.append(
                {
                    "bug_id": bug["bug_id"],
                    "family": family,
                    "difficulty": DIFFICULTY[family],
                    "development_exposed": family in DEVELOPMENT_EXPOSED,
                    "faulty_variant": {
                        "variant_index": index,
                        "generator_parameters": _variant_parameters(family, index),
                        "chisel": bug["chisel"],
                        "rtl": bug["rtl"],
                    },
                    "public_inputs": {
                        "project": entry["project"],
                        "specification": entry["specification"],
                        "configuration_template": entry["configuration"],
                        "selected_authoring_scope": entry["selected_authoring_scope"],
                    },
                    "hidden_evaluation": {
                        "clean_variant": entry["clean"],
                        "bug_metadata": bug["bug_metadata"],
                        "bug_diff": bug["bug_diff"],
                        "gold_source_location": bug["gold_source_location"],
                    },
                }
            )
    if len(tasks) != 41 or sum(BUG_COUNTS.values()) != 41:
        raise SpecFlowExperimentError("paper corpus must contain exactly 41 bug tasks")
    config = {
        "schema_version": "specflow_bug_level_experiment",
        "experiment_id": experiment_id,
        "created_at": datetime.now(ZoneInfo("Asia/Shanghai")).isoformat(),
        "model": {
            "name": args.model,
            "url": args.url,
            "temperature": 0.0,
            "max_output_tokens": args.max_output_tokens,
            "method_budgets": {
                "s0": [args.max_output_tokens],
                "s1": [args.max_output_tokens],
                "s2": [args.max_output_tokens // 2, args.max_output_tokens - args.max_output_tokens // 2],
            },
        },
        "formal": {
            "timeout_seconds": args.timeout_seconds,
            "per_property_seconds": args.per_property_seconds,
        },
        "methods": list(METHODS),
    }
    _write_json(run_dir / "config.json", config)
    _write_json(
        run_dir / "tasks.json",
        {"schema_version": "specflow_bug_tasks", "tasks": tasks},
    )
    (run_dir / "results.jsonl").touch()
    # ``_family_entry`` freezes bug diffs and gold locations under
    # ``raw/frozen_inputs`` while assembling the task manifest.
    (run_dir / "raw").mkdir(exist_ok=True)
    (run_dir / "tables").mkdir()
    write_report(run_dir)
    return run_dir


def run_task(args: argparse.Namespace) -> Path:
    from src.chiselspecflow.authoring import run_two_stage_authoring
    from src.chiselspecflow.config import SpecFlowRunConfig
    from src.chiselspecflow.preflight import prepare_workspace
    from src.chiselspecflow.runner import (
        run_direct_compile_verify,
        run_direct_frozen_package_replay,
    )
    from src.core.llm_client import LLMClient
    from src.experiments.direct import run_direct_one_shot
    from src.experiments.direct_sva import (
        generate_direct_sva,
        run_direct_sva_formal,
        summarize_direct_sva,
    )

    run_dir = Path(args.run).resolve()
    config = _read_json(run_dir / "config.json")
    task = _task(run_dir, args.bug_id)
    if args.method not in METHODS:
        raise SpecFlowExperimentError(f"unknown method: {args.method}")
    if _result_exists(run_dir, args.bug_id, args.method):
        raise SpecFlowExperimentError("task/method result already exists")
    repo = _repo_from_run(run_dir)
    method_root = run_dir / "raw" / args.bug_id / args.method
    method_root.mkdir(parents=True, exist_ok=False)
    faulty_config = _write_variant_config(repo, method_root, task, clean=False)
    clean_config = _write_variant_config(repo, method_root, task, clean=True)
    source = task["public_inputs"]
    expected, primary = SELECTED_AUTHORING_SCOPE[task["family"]]

    def workspace(name: str, configuration: Path):
        return prepare_workspace(
            SpecFlowRunConfig(
                project_contract=repo / source["project"]["path"],
                specification=repo / source["specification"]["path"],
                configuration=configuration,
                run_root=method_root,
                opaque_task_id=f"{args.bug_id}-{args.method}-{name}",
                expected_property_ids=(expected,),
                component_ids=(primary,),
            ),
            method_root / name,
            repo / "benchmark/synth/SPECIFICATIONS.sha256",
        )

    row = _empty_row(task, args.method, method_root)
    authored_property_ids: list[str] = []
    author_started = time.monotonic()
    client: Any = None
    try:
        client = LLMClient(
            model=args.model or config["model"]["name"],
            llm_url=args.url or config["model"]["url"],
            raw_response_dir=method_root / "raw_model_responses",
        )
        faulty_workspace = workspace("faulty", faulty_config)
        if args.method == "s0":
            properties = generate_direct_sva(
                client,
                _direct_context(faulty_workspace),
                method_root / "authoring",
                max_tokens=config["model"]["method_budgets"]["s0"][0],
            )
            authored_property_ids = [item["property_id"] for item in properties]
            row["generated_property_count"] = len(properties)
            row["authoring_seconds"] = time.monotonic() - author_started
            formal_started = time.monotonic()
            faulty_result = run_direct_sva_formal(
                faulty_workspace,
                properties,
                method_root / "faulty_formal",
                **config["formal"],
            )
            clean_workspace = workspace("clean", clean_config)
            clean_result = run_direct_sva_formal(
                clean_workspace,
                properties,
                method_root / "clean_formal",
                **config["formal"],
            )
            row["formal_seconds"] = time.monotonic() - formal_started
            _score_s0(
                row,
                summarize_direct_sva(clean_result),
                summarize_direct_sva(faulty_result),
                authored_property_ids,
                clean_executable=clean_result.get("execution_status") != "compile_error",
                faulty_executable=faulty_result.get("execution_status") != "compile_error",
            )
            row["status"] = _s0_status(clean_result, faulty_result)
            if row["status"] == "compile_error":
                row["failure_class"] = "property_intent"
        else:
            if args.method == "s1":
                outcome = run_direct_one_shot(
                    faulty_workspace,
                    client,
                    max_tokens=config["model"]["method_budgets"]["s1"][0],
                )
            else:
                outcome = run_two_stage_authoring(
                    faulty_workspace,
                    client,
                    max_tokens=tuple(config["model"]["method_budgets"]["s2"]),
                )
            if outcome.get("status") != "completed":
                raise SpecFlowExperimentError(
                    f"authoring ended with {outcome.get('status')}: {outcome.get('error')}"
                )
            package = _read_json(
                faulty_workspace.stage_dir("asset_authoring") / "verification_package.json"
            )
            row["generated_property_count"] = sum(
                prop["role"] == "primary_assertion"
                for monitor in package["monitors"]
                for prop in monitor["properties"]
            )
            authored_property_ids = sorted(
                {
                    prop["source_property_id"]
                    for monitor in package["monitors"]
                    for prop in monitor["properties"]
                    if prop["role"] == "primary_assertion"
                }
            )
            row["obligation_families"] = sorted(
                {item["family"] for item in package["obligations"]}
            )
            row["monitor_archetypes"] = sorted(
                {item["archetype_id"] for item in package["monitors"]}
            )
            row["authoring_seconds"] = time.monotonic() - author_started
            formal_started = time.monotonic()
            faulty_outcome = run_direct_compile_verify(
                faulty_workspace.run_dir,
                timeout_seconds=config["formal"]["timeout_seconds"],
                per_property_seconds=config["formal"]["per_property_seconds"],
            )
            clean_workspace = workspace("clean", clean_config)
            clean_outcome = run_direct_frozen_package_replay(
                clean_workspace.run_dir,
                faulty_workspace.run_dir,
                timeout_seconds=config["formal"]["timeout_seconds"],
                per_property_seconds=config["formal"]["per_property_seconds"],
            )
            row["formal_seconds"] = time.monotonic() - formal_started
            _score_typed(
                row,
                _typed_evidence(clean_workspace.run_dir),
                _typed_evidence(faulty_workspace.run_dir),
                clean_outcome,
                faulty_outcome,
                authored_property_ids,
            )
            row["status"] = (
                "completed"
                if clean_outcome.get("execution_status") == "completed"
                and faulty_outcome.get("execution_status") == "completed"
                else "tool_error"
            )
    except Exception as exc:
        row["status"] = _failure_status(exc)
        row["failure_class"] = _failure_class(exc)
        row["artifact_paths"]["error"] = f"{type(exc).__name__}: {exc}"
        if row["authoring_seconds"] == 0.0:
            row["authoring_seconds"] = time.monotonic() - author_started
    if not row["property_funnel"] and authored_property_ids:
        row["property_funnel"] = [
            _property_funnel_row(
                property_id,
                {},
                {},
                clean_executable=False,
                faulty_executable=False,
                typed=args.method != "s0",
            )
            for property_id in authored_property_ids
        ]
    if row["status"] in {"authoring_error", "compile_error"}:
        row["funnel_failure"] = "authoring_type_failure"
    elif row["status"] == "tool_error":
        row["funnel_failure"] = "formal_tool_failure"
    if client is not None:
        usage = client.get_token_usage()
        row["model_calls"] = int(usage.get("llm_calls", 0))
        row["input_tokens"] = int(usage.get("llm_prompt_tokens", 0))
        row["output_tokens"] = int(usage.get("llm_completion_tokens", 0))
    validate_specflow_result(row)
    row_path = method_root / "result.json"
    _write_json(row_path, row)
    _append_result(run_dir, row)
    score(run_dir)
    write_report(run_dir)
    return row_path


def run_diagnostic(args: argparse.Namespace) -> Path:
    """Run one A3 property without adding it to S0/S1/S2 results."""

    from src.chiselspecflow.config import SpecFlowRunConfig
    from src.chiselspecflow.preflight import prepare_workspace
    from src.experiments.direct_sva import run_direct_sva_formal, summarize_direct_sva

    run_dir = Path(args.run).resolve()
    config = _read_json(run_dir / "config.json")
    task = _task(run_dir, args.bug_id)
    family = task["family"]
    if family not in DIAGNOSTIC_PROPERTIES or task["faulty_variant"]["variant_index"] != 1:
        raise SpecFlowExperimentError("A3 accepts only the four development bug-1 cases")
    repo = _repo_from_run(run_dir)
    diagnostic_root = run_dir / "raw" / args.bug_id / "diagnostic"
    diagnostic_root.mkdir(parents=True, exist_ok=False)
    property_row = dict(DIAGNOSTIC_PROPERTIES[family])
    property_path = diagnostic_root / "diagnostic_property.json"
    _write_json(property_path, property_row)
    source = task["public_inputs"]
    expected, primary = SELECTED_AUTHORING_SCOPE[family]

    def workspace(name: str, configuration: Path):
        return prepare_workspace(
            SpecFlowRunConfig(
                project_contract=repo / source["project"]["path"],
                specification=repo / source["specification"]["path"],
                configuration=configuration,
                run_root=diagnostic_root,
                opaque_task_id=f"{args.bug_id}-diagnostic-{name}",
                expected_property_ids=(expected,),
                component_ids=(primary,),
            ),
            diagnostic_root / name,
            repo / "benchmark/synth/SPECIFICATIONS.sha256",
        )

    status = "running"
    failure_reason = None
    gates = _property_funnel_row(
        property_row["property_id"],
        {},
        {},
        clean_executable=False,
        faulty_executable=False,
        typed=False,
    )
    try:
        faulty_config = _write_variant_config(
            repo, diagnostic_root, task, clean=False
        )
        clean_config = _write_variant_config(repo, diagnostic_root, task, clean=True)
        faulty_workspace = workspace("faulty", faulty_config)
        faulty_result = run_direct_sva_formal(
            faulty_workspace,
            [property_row],
            diagnostic_root / "faulty_formal",
            **config["formal"],
        )
        clean_workspace = workspace("clean", clean_config)
        clean_result = run_direct_sva_formal(
            clean_workspace,
            [property_row],
            diagnostic_root / "clean_formal",
            **config["formal"],
        )
        gates = _property_funnel_row(
            property_row["property_id"],
            summarize_direct_sva(clean_result).get(property_row["property_id"], {}),
            summarize_direct_sva(faulty_result).get(property_row["property_id"], {}),
            clean_executable=clean_result.get("execution_status") != "compile_error",
            faulty_executable=faulty_result.get("execution_status") != "compile_error",
            typed=False,
        )
        status = _s0_status(clean_result, faulty_result)
        if status != "completed":
            failure_reason = status
    except Exception as exc:
        status = _failure_status(exc)
        failure_reason = f"{type(exc).__name__}: {exc}"

    result = {
        "schema_version": "specflow_capability_diagnostic.v1",
        "bug_id": args.bug_id,
        "family": family,
        "status": status,
        "capability_available": status == "completed" and gates["valid_detection"],
        "gates": gates,
        "failure_reason": failure_reason,
        "identity": {
            "property_sha256": hashlib.sha256(property_path.read_bytes()).hexdigest(),
            "clean_configuration_id": "paper_clean",
            "faulty_configuration_id": "paper_bug_01",
        },
        "artifacts": {
            "property": str(property_path.relative_to(run_dir)),
            "clean_formal": str((diagnostic_root / "clean_formal").relative_to(run_dir)),
            "faulty_formal": str((diagnostic_root / "faulty_formal").relative_to(run_dir)),
        },
        "scope": {
            "model_calls": 0,
            "included_in_main_results": False,
            "repository_asset": False,
        },
    }
    result_path = diagnostic_root / "diagnostic_result.json"
    _write_json(result_path, result)
    _write_capability_table(run_dir)
    return result_path


def _write_capability_table(run_dir: Path) -> None:
    rows = [
        _read_json(path)
        for path in sorted(run_dir.glob("raw/*/diagnostic/diagnostic_result.json"))
    ]
    _write_json(
        run_dir / "tables/capability_smoke.json",
        {
            "schema_version": "specflow_capability_smoke.v1",
            "expected_families": sorted(DIAGNOSTIC_PROPERTIES),
            "completed_families": sorted(
                row["family"] for row in rows if row["status"] == "completed"
            ),
            "available_families": sorted(
                row["family"] for row in rows if row["capability_available"]
            ),
            "rows": rows,
        },
    )


def score(run_dir: Path) -> None:
    run_dir = Path(run_dir)
    rows = _load_results(run_dir)
    family = family_method_summary(rows)
    table1 = {
        "schema_version": "specflow_family_results",
        "families": family,
        "difficulty_summary": _difficulty_summary(family),
    }
    table2 = {
        "schema_version": "specflow_method_ablation",
        "method_macro": method_macro_summary(rows) if rows else [],
        "paired_differences": paired_family_differences(rows),
        "family_costs": family,
    }
    table3 = {
        "schema_version": "specflow_template_reuse",
        "templates": template_reuse_summary(rows),
    }
    funnel_properties = [
        {
            "bug_id": row["bug_id"],
            "family": row["family"],
            "method": row["method"],
            **item,
        }
        for row in rows
        for item in row["property_funnel"]
    ]
    table0 = {
        "schema_version": "specflow_property_funnel.v1",
        "properties": funnel_properties,
        "task_failures": [
            {
                "bug_id": row["bug_id"],
                "family": row["family"],
                "method": row["method"],
                "failure": row["funnel_failure"],
            }
            for row in rows
            if row["funnel_failure"] is not None
        ],
        "gate_counts": {
            gate: sum(item[gate] for item in funnel_properties)
            for gate in (
                "authoring_success",
                "executable_on_clean",
                "executable_on_faulty",
                "clean_proven",
                "clean_non_vacuous",
                "faulty_exact_cex",
                "valid_detection",
            )
        },
        "classification_counts": {
            classification: sum(
                item["classification"] == classification
                for item in funnel_properties
            )
            for classification in sorted(
                {item["classification"] for item in funnel_properties}
            )
        },
    }
    _write_json(run_dir / "tables/table0_property_funnel.json", table0)
    _write_json(run_dir / "tables/table1_family_results.json", table1)
    _write_json(run_dir / "tables/table2_method_ablation.json", table2)
    _write_json(run_dir / "tables/table3_template_reuse.json", table3)


def write_report(run_dir: Path) -> None:
    run_dir = Path(run_dir)
    rows = _load_results(run_dir) if (run_dir / "results.jsonl").is_file() else []
    scheduled = 41 * len(METHODS)
    properties = [item for row in rows for item in row["property_funnel"]]
    lines = [
        "# SpecFlow bug-level experiment",
        "",
        f"- Result rows: {len(rows)}/{scheduled}",
        f"- Completed rows: {sum(row['status'] == 'completed' for row in rows)}",
        f"- Failed/incomplete rows: {sum(row['status'] != 'completed' for row in rows)}",
        f"- Authored properties: {len(properties)}",
        f"- Executable on clean/faulty: {sum(item['executable_on_clean'] and item['executable_on_faulty'] for item in properties)}",
        f"- Clean proven and non-vacuous: {sum(item['clean_proven'] and item['clean_non_vacuous'] for item in properties)}",
        f"- Faulty exact CEX: {sum(item['faulty_exact_cex'] for item in properties)}",
        f"- Valid detections: {sum(item['valid_detection'] for item in properties)}",
        "",
        "## Method summary",
        "",
        "| Method | Families | Macro bug detection | Family bootstrap 95% CI |",
        "|---|---:|---:|---:|",
    ]
    for item in method_macro_summary(rows) if rows else []:
        low, high = item["family_bootstrap_95ci"]
        lines.append(
            f"| {item['method']} | {item['family_count']} | "
            f"{item['family_macro_bug_detection_rate']:.3f} | [{low:.3f}, {high:.3f}] |"
        )
    lines.extend(
        [
            "",
            "The report is descriptive until all 123 task/method rows exist. "
            "A row counts as valid only when the clean assertion is proven, "
            "its activation evidence is complete, and the faulty run has an exact CEX.",
            "",
        ]
    )
    (run_dir / "report.md").write_text("\n".join(lines), encoding="utf-8")


def add_parser(
    subparsers: argparse._SubParsersAction[argparse.ArgumentParser],
) -> None:
    parser = subparsers.add_parser("specflow-exp", help="bug-level SpecFlow paper experiment")
    actions = parser.add_subparsers(dest="specflow_exp_action", required=True)
    prepare_parser = actions.add_parser("prepare")
    prepare_parser.add_argument("--repo", default=".")
    prepare_parser.add_argument("--experiment-id")
    prepare_parser.add_argument("--model", required=True)
    prepare_parser.add_argument("--url", required=True)
    prepare_parser.add_argument("--max-output-tokens", type=int, default=32768)
    prepare_parser.add_argument("--timeout-seconds", type=int, default=300)
    prepare_parser.add_argument("--per-property-seconds", type=int, default=60)
    run_parser = actions.add_parser("run")
    run_parser.add_argument("--run", required=True)
    run_parser.add_argument("--bug-id", required=True)
    run_parser.add_argument("--method", choices=METHODS, required=True)
    run_parser.add_argument("--model")
    run_parser.add_argument("--url")
    diagnose_parser = actions.add_parser("diagnose")
    diagnose_parser.add_argument("--run", required=True)
    diagnose_parser.add_argument("--bug-id", required=True)
    score_parser = actions.add_parser("score")
    score_parser.add_argument("--run", required=True)
    report_parser = actions.add_parser("report")
    report_parser.add_argument("--run", required=True)


def run(args: argparse.Namespace) -> None:
    if args.specflow_exp_action == "prepare":
        path = prepare(args)
    elif args.specflow_exp_action == "run":
        path = run_task(args)
    elif args.specflow_exp_action == "diagnose":
        path = run_diagnostic(args)
    elif args.specflow_exp_action == "score":
        path = Path(args.run).resolve()
        score(path)
    elif args.specflow_exp_action == "report":
        path = Path(args.run).resolve()
        score(path)
        write_report(path)
    else:
        raise SpecFlowExperimentError("unknown specflow-exp action")
    print(json.dumps({"run_dir": str(path)}, sort_keys=True))


def _empty_row(task: Mapping[str, Any], method: str, method_root: Path) -> dict[str, Any]:
    return {
        "bug_id": task["bug_id"],
        "family": task["family"],
        "difficulty": task["difficulty"],
        "development_exposed": task["development_exposed"],
        "method": method,
        "status": "running",
        "generated_property_count": 0,
        "compiled_on_clean": 0,
        "compiled_on_faulty": 0,
        "clean_proven_count": 0,
        "clean_cex_count": 0,
        "faulty_cex_count": 0,
        "non_vacuous_count": 0,
        "valid_bug_detecting_property_count": 0,
        "property_funnel": [],
        "funnel_failure": None,
        "model_calls": 0,
        "input_tokens": 0,
        "output_tokens": 0,
        "authoring_seconds": 0.0,
        "formal_seconds": 0.0,
        "obligation_families": [],
        "monitor_archetypes": [],
        "failure_class": None,
        "artifact_paths": {"method_root": str(method_root)},
    }


def _score_s0(
    row: dict[str, Any],
    clean: Mapping[str, Mapping[str, Any]],
    faulty: Mapping[str, Mapping[str, Any]],
    property_ids: Sequence[str],
    *,
    clean_executable: bool,
    faulty_executable: bool,
) -> None:
    _require_exact_property_ids(property_ids, clean, faulty)
    row["property_funnel"] = [
        _property_funnel_row(
            property_id,
            clean.get(property_id, {}),
            faulty.get(property_id, {}),
            clean_executable=clean_executable,
            faulty_executable=faulty_executable,
            typed=False,
        )
        for property_id in property_ids
    ]
    _record_funnel_counts(row)


def _score_typed(
    row: dict[str, Any],
    clean: Mapping[str, Mapping[str, Any]],
    faulty: Mapping[str, Mapping[str, Any]],
    clean_outcome: Mapping[str, Any],
    faulty_outcome: Mapping[str, Any],
    property_ids: Sequence[str],
) -> None:
    _require_exact_property_ids(property_ids, clean, faulty)
    row["property_funnel"] = [
        _property_funnel_row(
            property_id,
            clean.get(property_id, {}),
            faulty.get(property_id, {}),
            clean_executable=clean_outcome.get("execution_status") != "compile_error",
            faulty_executable=faulty_outcome.get("execution_status") != "compile_error",
            typed=True,
        )
        for property_id in property_ids
    ]
    _record_funnel_counts(row)


def _property_funnel_row(
    property_id: str,
    clean: Mapping[str, Any],
    faulty: Mapping[str, Any],
    *,
    clean_executable: bool,
    faulty_executable: bool,
    typed: bool,
) -> dict[str, Any]:
    clean_primary = clean if typed else clean.get("primary_assertion", {})
    faulty_primary = faulty if typed else faulty.get("primary_assertion", {})
    clean_status = clean_primary.get("primary_status" if typed else "status")
    faulty_status = faulty_primary.get("primary_status" if typed else "status")
    non_vacuous = (
        clean_primary.get("evidence_status") == "complete"
        if typed
        else clean.get("activation_cover", {}).get("status") == "covered"
    )
    faulty_exact_cex = bool(
        faulty_status == "cex" and faulty_primary.get("trace_path")
    )
    clean_proven = clean_status == "proven"
    clean_cex = clean_status == "cex"
    valid = bool(
        clean_executable
        and faulty_executable
        and clean_proven
        and non_vacuous
        and faulty_exact_cex
    )
    if not clean_executable or not faulty_executable:
        classification = "authoring_type_failure"
    elif valid:
        classification = "valid_detection"
    elif clean_cex and faulty_exact_cex:
        classification = "faulty_sensitive_clean_false_alarm"
    elif clean_proven and not non_vacuous:
        classification = "non_vacuity_failure"
    elif clean_proven and non_vacuous:
        classification = "clean_valid_not_fault_sensitive"
    elif clean_cex:
        classification = "clean_false_alarm"
    else:
        classification = "incomplete_evidence"
    return {
        "property_id": property_id,
        "authoring_success": True,
        "executable_on_clean": bool(clean_executable),
        "executable_on_faulty": bool(faulty_executable),
        "clean_proven": clean_proven,
        "clean_non_vacuous": non_vacuous,
        "faulty_exact_cex": faulty_exact_cex,
        "valid_detection": valid,
        "clean_false_alarm": clean_cex,
        "classification": classification,
    }


def _record_funnel_counts(row: dict[str, Any]) -> None:
    items = row["property_funnel"]
    row["compiled_on_clean"] = sum(item["executable_on_clean"] for item in items)
    row["compiled_on_faulty"] = sum(item["executable_on_faulty"] for item in items)
    row["clean_proven_count"] = sum(item["clean_proven"] for item in items)
    row["clean_cex_count"] = sum(item["clean_false_alarm"] for item in items)
    row["faulty_cex_count"] = sum(item["faulty_exact_cex"] for item in items)
    row["non_vacuous_count"] = sum(item["clean_non_vacuous"] for item in items)
    row["valid_bug_detecting_property_count"] = sum(
        item["valid_detection"] for item in items
    )


def _require_exact_property_ids(
    property_ids: Sequence[str],
    clean: Mapping[str, Any],
    faulty: Mapping[str, Any],
) -> None:
    authored = set(property_ids)
    unexpected = (set(clean) | set(faulty)) - authored
    if len(authored) != len(property_ids) or unexpected:
        raise SpecFlowExperimentError(
            f"property evidence does not exact-join authored IDs: {sorted(unexpected)}"
        )


def _typed_evidence(run_dir: Path) -> dict[str, dict[str, Any]]:
    path = Path(run_dir) / "stages/02_compile_verify/semantic_evidence.json"
    value = _read_json(path)
    rows = value.get("properties", [])
    result = {row["source_property_id"]: row for row in rows}
    if len(result) != len(rows):
        raise SpecFlowExperimentError("duplicate exact property ID in semantic evidence")
    return result


def _direct_context(workspace: Any) -> dict[str, Any]:
    inputs = workspace.inputs_dir
    model_manifest = _read_json(inputs / "model_view_manifest.json")
    sources = []
    for row in model_manifest["files"]:
        path = inputs / "model_sources" / row["path"]
        sources.append({"path": row["path"], "text": path.read_text(encoding="utf-8")})
    return {
        "project": _read_json(inputs / "project_contract.json"),
        "configuration": _read_json(inputs / "configuration.json"),
        "specification": (inputs / "specification.md").read_text(encoding="utf-8"),
        "semantic_objects": _read_json(
            workspace.indexes_dir / "chisel_semantic_index.json"
        )["objects"],
        "faulty_chisel_sources": sources,
    }


def _write_variant_config(
    repo: Path, method_root: Path, task: Mapping[str, Any], *, clean: bool
) -> Path:
    template = _read_json(repo / task["public_inputs"]["configuration_template"]["path"])
    index = 0 if clean else int(task["faulty_variant"]["variant_index"])
    parameters = _variant_parameters(task["family"], index) if index else template["parameters"]
    value = {
        "schema_version": template["schema_version"],
        "configuration_id": "paper_clean" if clean else f"paper_bug_{index:02d}",
        "parameters": parameters,
    }
    path = method_root / "inputs" / ("clean.json" if clean else "faulty.json")
    _write_json(path, value)
    return path


def _difficulty_summary(family_rows: list[dict[str, Any]]) -> list[dict[str, Any]]:
    groups: dict[tuple[str, str], list[dict[str, Any]]] = {}
    for row in family_rows:
        groups.setdefault((row["difficulty"], row["method"]), []).append(row)
    return [
        {
            "difficulty": difficulty,
            "method": method,
            "family_count": len(items),
            "macro_bug_detection_rate": sum(item["bug_detection_rate"] for item in items) / len(items),
        }
        for (difficulty, method), items in sorted(groups.items())
    ]


def _failure_status(exc: Exception) -> str:
    text = f"{type(exc).__name__}: {exc}".lower()
    if "compile" in text or "elaboration" in text or "syntax" in text:
        return "compile_error"
    if "jasper" in text or "timeout" in text or "license" in text:
        return "tool_error"
    return "authoring_error"


def _s0_status(
    clean_result: Mapping[str, Any], faulty_result: Mapping[str, Any]
) -> str:
    execution_statuses = {
        result.get("execution_status") for result in (clean_result, faulty_result)
    }
    if "compile_error" in execution_statuses:
        return "compile_error"
    if "tool_error" in execution_statuses:
        return "tool_error"
    statuses = [
        row.get("status")
        for result in (clean_result, faulty_result)
        for row in result.get("operation_results", [])
    ]
    return (
        "tool_error"
        if any(status in {"tool_error", "timeout", "inconclusive", "missing"} for status in statuses)
        else "completed"
    )


def _failure_class(exc: Exception) -> str:
    text = str(exc).lower()
    if "binding" in text or "object" in text or "hierarch" in text:
        return "binding"
    if "archetype" in text or "template" in text or "monitor" in text:
        return "template_coverage"
    if "formal" in text or "jasper" in text or "timeout" in text or "license" in text:
        return "formal_scalability"
    return "property_intent"


def _task(run_dir: Path, bug_id: str) -> dict[str, Any]:
    tasks = _read_json(run_dir / "tasks.json")["tasks"]
    matches = [row for row in tasks if row["bug_id"] == bug_id]
    if len(matches) != 1:
        raise SpecFlowExperimentError(f"unknown bug_id: {bug_id}")
    return matches[0]


def _repo_from_run(run_dir: Path) -> Path:
    try:
        return run_dir.parents[2]
    except IndexError as exc:
        raise SpecFlowExperimentError("run is not under runs/specflow-paper") from exc


def _result_exists(run_dir: Path, bug_id: str, method: str) -> bool:
    return any(row["bug_id"] == bug_id and row["method"] == method for row in _load_results(run_dir))


def _append_result(run_dir: Path, row: Mapping[str, Any]) -> None:
    if _result_exists(run_dir, row["bug_id"], row["method"]):
        raise SpecFlowExperimentError("duplicate task/method result")
    with (run_dir / "results.jsonl").open("a", encoding="utf-8") as stream:
        stream.write(json.dumps(row, ensure_ascii=False, sort_keys=True) + "\n")


def _load_results(run_dir: Path) -> list[dict[str, Any]]:
    path = Path(run_dir) / "results.jsonl"
    if not path.is_file():
        return []
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def _read_json(path: Path) -> dict[str, Any]:
    value = json.loads(Path(path).read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise SpecFlowExperimentError(f"JSON object required: {path}")
    return value


def _write_json(path: Path, value: Any) -> None:
    Path(path).parent.mkdir(parents=True, exist_ok=True)
    Path(path).write_text(
        json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
