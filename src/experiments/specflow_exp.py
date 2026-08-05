"""Bug-level S0/S1/S2 runner for ``specflow_exp.md``."""

from __future__ import annotations

import argparse
import json
import time
from datetime import datetime
from pathlib import Path
from typing import Any, Mapping
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
    _write_json(run_dir / "tables/table1_family_results.json", table1)
    _write_json(run_dir / "tables/table2_method_ablation.json", table2)
    _write_json(run_dir / "tables/table3_template_reuse.json", table3)


def write_report(run_dir: Path) -> None:
    run_dir = Path(run_dir)
    rows = _load_results(run_dir) if (run_dir / "results.jsonl").is_file() else []
    scheduled = 41 * len(METHODS)
    lines = [
        "# SpecFlow bug-level experiment",
        "",
        f"- Result rows: {len(rows)}/{scheduled}",
        f"- Completed rows: {sum(row['status'] == 'completed' for row in rows)}",
        f"- Failed/incomplete rows: {sum(row['status'] != 'completed' for row in rows)}",
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
    score_parser = actions.add_parser("score")
    score_parser.add_argument("--run", required=True)
    report_parser = actions.add_parser("report")
    report_parser.add_argument("--run", required=True)


def run(args: argparse.Namespace) -> None:
    if args.specflow_exp_action == "prepare":
        path = prepare(args)
    elif args.specflow_exp_action == "run":
        path = run_task(args)
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
) -> None:
    ids = sorted(set(clean) | set(faulty))
    row["compiled_on_clean"] = sum(
        clean.get(pid, {}).get("primary_assertion", {}).get("status") != "tool_error"
        for pid in ids
        if "primary_assertion" in clean.get(pid, {})
    )
    row["compiled_on_faulty"] = sum(
        faulty.get(pid, {}).get("primary_assertion", {}).get("status") != "tool_error"
        for pid in ids
        if "primary_assertion" in faulty.get(pid, {})
    )
    for property_id in ids:
        clean_rows = clean.get(property_id, {})
        faulty_rows = faulty.get(property_id, {})
        clean_primary = clean_rows.get("primary_assertion", {})
        activation = clean_rows.get("activation_cover", {})
        faulty_primary = faulty_rows.get("primary_assertion", {})
        proven = clean_primary.get("status") == "proven"
        clean_cex = clean_primary.get("status") == "cex"
        non_vacuous = activation.get("status") == "covered"
        faulty_cex = (
            faulty_primary.get("status") == "cex" and faulty_primary.get("trace_path")
        )
        row["clean_proven_count"] += int(proven)
        row["clean_cex_count"] += int(clean_cex)
        row["non_vacuous_count"] += int(non_vacuous)
        row["faulty_cex_count"] += int(bool(faulty_cex))
        row["valid_bug_detecting_property_count"] += int(
            proven and non_vacuous and bool(faulty_cex)
        )


def _score_typed(
    row: dict[str, Any],
    clean: Mapping[str, Mapping[str, Any]],
    faulty: Mapping[str, Mapping[str, Any]],
    clean_outcome: Mapping[str, Any],
    faulty_outcome: Mapping[str, Any],
) -> None:
    generated = row["generated_property_count"]
    row["compiled_on_clean"] = generated if clean_outcome.get("execution_status") != "compile_error" else 0
    row["compiled_on_faulty"] = generated if faulty_outcome.get("execution_status") != "compile_error" else 0
    for property_id in sorted(set(clean) | set(faulty)):
        clean_row = clean.get(property_id, {})
        faulty_row = faulty.get(property_id, {})
        proven = clean_row.get("primary_status") == "proven"
        clean_cex = clean_row.get("primary_status") == "cex"
        non_vacuous = clean_row.get("evidence_status") == "complete"
        faulty_cex = (
            faulty_row.get("primary_status") == "cex" and faulty_row.get("trace_path")
        )
        row["clean_proven_count"] += int(proven)
        row["clean_cex_count"] += int(clean_cex)
        row["non_vacuous_count"] += int(non_vacuous)
        row["faulty_cex_count"] += int(bool(faulty_cex))
        row["valid_bug_detecting_property_count"] += int(
            proven and non_vacuous and bool(faulty_cex)
        )


def _typed_evidence(run_dir: Path) -> dict[str, dict[str, Any]]:
    path = Path(run_dir) / "stages/02_compile_verify/semantic_evidence.json"
    value = _read_json(path)
    return {row["source_property_id"]: row for row in value.get("properties", [])}


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
