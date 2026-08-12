"""Single runner for experiment.md's frozen paper experiment.

The preparation path performs no model or formal call.  It materializes the
9.1 corpus contract and the 9.2 public configuration, then owns all canonical
JSONL writes and the mandatory VCD-to-FST conversion boundary used by later
steps.
"""

from __future__ import annotations

import argparse
import difflib
import hashlib
import json
import os
import subprocess
import sys
import time
from datetime import datetime
from pathlib import Path
from typing import Any, Iterable, Mapping
from urllib.parse import urlparse
from zoneinfo import ZoneInfo


FAMILY_GROUPS = {
    "counter": "Development",
    "fsm_16": "Development",
    "i2c": "Development",
    "alu": "Evaluation",
    "decoder_3_to_8": "Evaluation",
    "arbiter": "Evaluation",
    "led_controller": "Evaluation",
    "sdram_controller": "Evaluation",
    "reed_solomon_decoder": "Evaluation",
    "sha3": "Evaluation",
}
BUG_COUNTS = {
    "counter": 3,
    "fsm_16": 4,
    "i2c": 6,
    "alu": 6,
    "decoder_3_to_8": 6,
    "arbiter": 3,
    "led_controller": 4,
    "sdram_controller": 3,
    "reed_solomon_decoder": 3,
    "sha3": 3,
}
WIT_DIRS = {
    family: f"benchmark/Wit-HW/buggy_designs/{family}"
    for family in FAMILY_GROUPS
}
WIT_DIRS["sha3"] = "benchmark/Wit-HW/buggy_designs/sha3/low_throughput_core"

GOLD_NEEDLES: dict[str, tuple[tuple[str, str], ...]] = {
    "counter": (
        ("src/main/scala/FirstCounter.scala", "counter := counter +% variant.increment.U"),
        ("src/main/scala/FirstCounter.scala", "overflow := variant.overflowAtMax.B"),
        ("src/main/scala/FirstCounter.scala", "if (variant.resetCounter) {"),
    ),
    "fsm_16": tuple(
        ("src/main/scala/Fsm16.scala", f'"fsm_16_buggy_{index}"')
        for index in range(1, 5)
    ),
    "i2c": (
        ("src/main/scala/I2CMaster.scala", '"STICKY_ACK" -> (if (variant.stickyBitCommandAck) 1 else 0)'),
        ("src/main/scala/I2CMaster.scala", "if (variant.writePrescalerLowIntoHigh) {"),
        ("src/main/scala/I2CMaster.scala", "prer := variant.asyncPrescalerResetValue.U(16.W)"),
        ("src/main/scala/I2CMaster.scala", "wb_adr_i === variant.commandRegisterAddress.U(3.W)"),
        ("src/main/scala/I2CMaster.scala", '"FORCE_START_B_CLOCK_LOW" -> (if (variant.forceStartBClockLow) 1 else 0)'),
        ("src/main/scala/I2CMaster.scala", "if (variant.issueReadAfterStartForWrite) 1 else 0"),
    ),
    "alu": (
        ("src/main/scala/Alu.scala", "if (variant.swapAddSub) a - b else a + b"),
        ("src/main/scala/Alu.scala", "val result = WireDefault(variant.defaultValue.U(8.W))"),
        ("src/main/scala/Alu.scala", '"ZERO_MODE" -> variant.zeroOverride.fold'),
        ("src/main/scala/Alu.scala", '"OVERFLOW_MODE" -> variant.overflowOverride.fold'),
        ("src/main/scala/Alu.scala", '"ZERO_MODE" -> variant.zeroOverride.fold'),
        ("src/main/scala/Alu.scala", '"OVERFLOW_MODE" -> variant.overflowOverride.fold'),
    ),
    "decoder_3_to_8": tuple(
        ("src/main/scala/Decoder3to8.scala", f'"decoder_3_to_8_buggy_{index}"')
        for index in range(1, 7)
    ),
    "arbiter": (
        ("src/main/scala/Arbiter.scala", "afterRequest3(0) := variant.request3Tag.U(3.W)"),
        ("src/main/scala/Arbiter.scala", "if (variant.request3CopiesUpdatedCoda2)"),
        ("src/main/scala/Arbiter.scala", "if (variant.request1UsesPreviousHigh)"),
    ),
    "led_controller": (
        ("src/main/scala/LedController.scala", "lights := variant.goExpiredLight.U(3.W)"),
        ("src/main/scala/LedController.scala", "lights := variant.warnPedestrianLight.U(3.W)"),
        ("src/main/scala/LedController.scala", "when(count < variant.stopCountLimit.S(32.W))"),
        ("src/main/scala/LedController.scala", "nextState := variant.warnPedestrianNextState.U(2.W)"),
    ),
    "sdram_controller": (
        ("src/main/scala/SdramController.scala", 'Map("READ_NOP1_CODE" -> variant.readNop1Code)'),
        ("src/main/scala/SdramController.scala", "busy_r := variant.resetBusyValue.B"),
        ("src/main/scala/SdramController.scala", "if (variant.mainResetOnReadDisable)"),
    ),
    "reed_solomon_decoder": (
        ("src/main/scala/BM_lamda.scala", "const_timing := (if (bug1) 244.U else 500.U)"),
        ("src/main/scala/RS_dec.scala", "S_flag := (!variant.bug2).B"),
        ("src/main/scala/out_stage.scala", "Valid_out_out_reg := bug3.B"),
    ),
    "sha3": (
        ("src/main/scala/Sha3.scala", "if (variant.latchOutReady) {"),
        ("src/main/scala/Sha3.scala", "roundIndex(variant.calcStopBit)"),
        ("src/main/scala/Sha3.scala", "if (variant.updateOutputOnlyOnAccept)"),
    ),
}

LEDGERS = (
    "track_p.jsonl",
    "track_d_admission.jsonl",
    "track_d.jsonl",
    "coupledl2.jsonl",
)
LEDGER_REQUIRED_FIELDS = {
    "track_p.jsonl": {"task", "method", "status", "cost", "input_hashes", "artifacts"},
    "track_d_admission.jsonl": {"task", "status", "input_hashes", "artifacts"},
    "track_d.jsonl": {"task", "method", "status", "cost", "input_hashes", "artifacts"},
    "coupledl2.jsonl": {"task", "status", "cost", "input_hashes", "artifacts"},
}
SENSITIVE_ENV_NAMES = {
    "CHISELLMFV_LLM_API_KEY",
}
P0_OUTPUT_BUDGET = (32768,)
P1_OUTPUT_BUDGET = (8192, 8192, 16384)
SELECTED_AUTHORING_SCOPE = {
    "counter": ("CTR-P-TIM-001", "CTR-P-TIM-001"),
    "fsm_16": ("FSM16-P-TIM-001", "FSM16-P-TIM-001"),
    "i2c": ("I2C-P004", "I2C-P004.bit-phase-completion"),
    "alu": ("ALU-P-SAF-001", "ALU-P-SAF-001"),
    "decoder_3_to_8": ("DEC-P-SAF-001", "DEC-P-SAF-001"),
    "arbiter": ("ARB-P001", "ARB-P001"),
    "led_controller": ("LED-P003", "LED-P003"),
    "sdram_controller": ("SDR-P001", "SDR-P001"),
    "reed_solomon_decoder": ("RS204-P-REL-001", "RS204-P-REL-001"),
    "sha3": ("K512-P-REL-001", "K512-P-REL-001"),
}
SELECTED_AUTHORING_CLAUSES = {
    "fsm_16": ("FSM16-N-021",),
    "i2c": ("I2C-010",),
    "led_controller": ("LED-004",),
}
DEVELOPMENT_FAMILIES = ("counter", "fsm_16", "i2c")
EVALUATION_FAMILIES = (
    "alu",
    "decoder_3_to_8",
    "arbiter",
    "led_controller",
    "sdram_controller",
    "reed_solomon_decoder",
    "sha3",
)


class ExperimentContractError(ValueError):
    """Raised when a frozen input or result row is incomplete."""


def _json_bytes(value: Any) -> bytes:
    return (json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True) + "\n").encode()


def _write_json(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(_json_bytes(value))


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def _ref(repo: Path, path: Path) -> dict[str, Any]:
    resolved = path.resolve()
    if not resolved.is_file():
        raise ExperimentContractError(f"missing required input: {path}")
    try:
        relative = resolved.relative_to(repo.resolve()).as_posix()
    except ValueError:
        relative = str(resolved)
    return {
        "path": relative,
        "sha256": _sha256(resolved),
        "size_bytes": resolved.stat().st_size,
    }


def _resolve_wit_path(repo: Path, raw: str) -> Path:
    normalized = raw.removeprefix("./")
    return repo / "benchmark/Wit-HW" / normalized


def _bug_info_path(repo: Path, family: str, index: int) -> Path:
    return repo / WIT_DIRS[family] / f"bug-info-{index}.json"


def _scala_sources(repo: Path, family: str) -> list[Path]:
    root = repo / "benchmark/synth" / family / "src/main/scala"
    return sorted(
        path for path in root.glob("*.scala")
        if path.name not in {"EmitSpecFlow.scala", "Generate.scala"}
    )


def _candidate_universe(repo: Path, family: str) -> dict[str, Any]:
    candidates = []
    for path in _scala_sources(repo, family):
        relative = path.relative_to(repo / "benchmark/synth" / family).as_posix()
        for line_number, text in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
            stripped = text.strip()
            if not stripped or stripped.startswith(("//", "/*", "*", "*/")):
                continue
            locator = f"{relative}:{line_number}"
            candidates.append({
                "candidate_id": hashlib.sha256(locator.encode()).hexdigest()[:16],
                "path": relative,
                "line": line_number,
                "text_sha256": hashlib.sha256(stripped.encode()).hexdigest(),
            })
    if not candidates:
        raise ExperimentContractError(f"{family}: empty source candidate universe")
    return {
        "schema_version": "source_candidate_universe",
        "family": family,
        "candidate_count": len(candidates),
        "candidates": candidates,
    }


def _gold_locations(repo: Path, family: str) -> dict[str, Any]:
    records = []
    family_root = repo / "benchmark/synth" / family
    needles = GOLD_NEEDLES[family]
    if len(needles) != BUG_COUNTS[family]:
        raise ExperimentContractError(f"{family}: gold locator count mismatch")
    for index, (relative, needle) in enumerate(needles, 1):
        path = family_root / relative
        matches = [
            line_number
            for line_number, text in enumerate(path.read_text(encoding="utf-8").splitlines(), 1)
            if needle in text
        ]
        if len(matches) != 1:
            raise ExperimentContractError(
                f"{family} bug {index}: gold needle matched {len(matches)} lines"
            )
        records.append({
            "bug_id": f"{family}-{index}",
            "path": relative,
            "line": matches[0],
            "locator": f"{relative}:{matches[0]}",
        })
    return {
        "schema_version": "gold_source_locations",
        "family": family,
        "locations": records,
    }


def _write_diff(correct: Path, buggy: Path, output: Path, repo: Path) -> None:
    correct_lines = correct.read_text(encoding="utf-8", errors="replace").splitlines(True)
    buggy_lines = buggy.read_text(encoding="utf-8", errors="replace").splitlines(True)
    text = "".join(difflib.unified_diff(
        correct_lines,
        buggy_lines,
        fromfile=correct.relative_to(repo).as_posix(),
        tofile=buggy.relative_to(repo).as_posix(),
    ))
    if not text:
        raise ExperimentContractError(f"empty bug diff: {buggy}")
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(text, encoding="utf-8")


def _family_entry(repo: Path, run_dir: Path, family: str) -> dict[str, Any]:
    family_root = repo / "benchmark/synth" / family
    project = family_root / "specflow/project.json"
    spec = family_root / "specflow/spec.md"
    config = family_root / "specflow/configs/cfg_000.json"
    decomposition_path = family_root / "specflow/property_decomposition.json"

    # Reuse the public parser so this manifest cannot bless an invalid contract.
    from src.chiselspecflow.config import (
        load_generator_configuration,
        load_project_contract,
    )
    from src.chiselspecflow.property_decomposition import (
        build_authoring_scope,
        load_property_decomposition,
    )
    from src.chiselspecflow.specification import load_public_spec_package

    parsed_project = load_project_contract(project)
    parsed_config = load_generator_configuration(config, parsed_project)
    if parsed_config.configuration_id != "cfg_000":
        raise ExperimentContractError(f"{family}: main configuration is not cfg_000")
    if not decomposition_path.is_file():
        raise ExperimentContractError(f"{family}: missing property decomposition")
    public_spec = load_public_spec_package(
        spec, repo / "benchmark/synth/SPECIFICATIONS.sha256"
    )
    decomposition = load_property_decomposition(decomposition_path, public_spec)
    selected_property, selected_primary = SELECTED_AUTHORING_SCOPE[family]
    selected_scope = build_authoring_scope(
        decomposition,
        public_spec,
        (selected_property,),
        (selected_primary,),
        SELECTED_AUTHORING_CLAUSES.get(family, ()),
    )
    expected_group = decomposition["component_groups"].get(selected_primary)
    if (
        selected_scope["primary_component_ids"] != [selected_primary]
        or not selected_scope["require_complete_primary_set"]
        or not expected_group
        or selected_scope["component_ids"] != expected_group
    ):
        raise ExperimentContractError(
            f"{family}: selected primary component group is incomplete"
        )

    frozen_root = run_dir / "raw/frozen_inputs" / family
    candidates_path = frozen_root / "source_candidate_universe.json"
    gold_path = frozen_root / "gold_source_locations.json"
    _write_json(candidates_path, _candidate_universe(repo, family))
    _write_json(gold_path, _gold_locations(repo, family))
    gold_records = json.loads(gold_path.read_text(encoding="utf-8"))["locations"]

    source_refs = [_ref(repo, path) for path in _scala_sources(repo, family)]
    bugs = []
    clean_rtl: dict[str, dict[str, Any]] = {}
    for index in range(1, BUG_COUNTS[family] + 1):
        info_path = _bug_info_path(repo, family, index)
        info = json.loads(info_path.read_text(encoding="utf-8"))
        correct = _resolve_wit_path(repo, info["correct_design"])
        buggy = _resolve_wit_path(repo, info["buggy_design"])
        includes = [_resolve_wit_path(repo, raw) for raw in info.get("include_files", [])]
        for path in [correct, *includes]:
            reference = _ref(repo, path)
            clean_rtl[reference["path"]] = reference
        diff_path = frozen_root / "bug_diffs" / f"bug_{index:02d}.diff"
        _write_diff(correct, buggy, diff_path, repo)
        bugs.append({
            "bug_id": f"{family}-{index}",
            "variant_index": index,
            "chisel": {
                "files": source_refs,
                "generator_parameters": {"variantIndex": index},
            },
            "rtl": [_ref(repo, path) for path in [buggy, *includes]],
            "bug_metadata": _ref(repo, info_path),
            "bug_diff": _ref(repo, diff_path),
            "gold_source_location": gold_records[index - 1],
        })

    return {
        "family": family,
        "group": FAMILY_GROUPS[family],
        "main_configuration_id": "cfg_000",
        "project": _ref(repo, project),
        "specification": _ref(repo, spec),
        "configuration": _ref(repo, config),
        "property_decomposition": _ref(repo, decomposition_path),
        "selected_authoring_scope": selected_scope,
        "clean": {
            "variant_index": 0,
            "chisel": {
                "files": source_refs,
                "generator_parameters": {"variantIndex": 0},
            },
            "rtl": [clean_rtl[key] for key in sorted(clean_rtl)],
        },
        "bugs": bugs,
        "candidate_universe": _ref(repo, candidates_path),
        "gold_source_locations": _ref(repo, gold_path),
    }


def _tree_digest(root: Path) -> dict[str, Any]:
    if not root.is_dir():
        raise ExperimentContractError(f"missing case directory: {root}")
    digest = hashlib.sha256()
    count = 0
    excluded = {".git", "target", "build", "generated", "logs", "runs"}
    for path in sorted(item for item in root.rglob("*") if item.is_file()):
        if any(part in excluded for part in path.relative_to(root).parts):
            continue
        relative = path.relative_to(root).as_posix()
        digest.update(relative.encode())
        digest.update(b"\0")
        digest.update(_sha256(path).encode())
        digest.update(b"\n")
        count += 1
    if count == 0:
        raise ExperimentContractError(f"empty case input tree: {root}")
    return {"path": str(root), "file_count": count, "tree_sha256": digest.hexdigest()}


def _coupledl2_entries(repo: Path) -> list[dict[str, Any]]:
    definitions = (
        (
            "deadlock-v0",
            "CoupledL2-Verification/code/CaseStudy_1/XiangShan-CoupledL2-deadlock-v0",
            "mshr_wait_bound_poc",
        ),
        (
            "write_read",
            "CoupledL2-Verification/code/CaseStudy_1/XiangShan-CoupledL2-write_read",
            "write_read_poc",
        ),
    )
    entries = []
    for case_id, relative, profile_id in definitions:
        case_path = repo / relative
        entries.append({
            "case_id": case_id,
            "case": _tree_digest(case_path),
            "property_profile": _ref(
                repo, repo / f"src/coupledl2/property_assets/profiles/{profile_id}.json"
            ),
            "formal_contract": _ref(
                repo,
                repo / f"src/coupledl2/property_assets/formal_contracts/{profile_id}.json",
            ),
        })
    return entries


def _aggregate_hashes(value: Any) -> str:
    hashes: list[tuple[str, str]] = []

    def visit(item: Any) -> None:
        if isinstance(item, dict):
            if isinstance(item.get("path"), str) and isinstance(item.get("sha256"), str):
                hashes.append((item["path"], item["sha256"]))
            if isinstance(item.get("path"), str) and isinstance(item.get("tree_sha256"), str):
                hashes.append((item["path"], item["tree_sha256"]))
            for child in item.values():
                visit(child)
        elif isinstance(item, list):
            for child in item:
                visit(child)

    visit(value)
    return hashlib.sha256(_json_bytes(sorted(set(hashes)))).hexdigest()


def _git_identity(repo: Path) -> dict[str, str]:
    revision = subprocess.run(
        ["git", "rev-parse", "HEAD"],
        cwd=repo,
        check=True,
        text=True,
        capture_output=True,
    ).stdout.strip()
    diff = subprocess.run(
        ["git", "diff", "--binary", "HEAD", "--", ".", ":(exclude)runs/specflow-experiments"],
        cwd=repo,
        check=True,
        capture_output=True,
    ).stdout
    untracked = subprocess.run(
        ["git", "ls-files", "--others", "--exclude-standard", "-z"],
        cwd=repo,
        check=True,
        capture_output=True,
    ).stdout.split(b"\0")
    digest = hashlib.sha256(diff)
    for raw in sorted(item for item in untracked if item):
        relative = os.fsdecode(raw)
        if relative.startswith("runs/specflow-experiments/"):
            continue
        path = repo / relative
        if not path.is_file():
            continue
        digest.update(relative.encode())
        digest.update(b"\0")
        digest.update(_sha256(path).encode())
        digest.update(b"\n")
    return {"revision": revision, "worktree_diff_sha256": digest.hexdigest()}


def _validate_url(url: str) -> None:
    parsed = urlparse(url)
    if parsed.scheme not in {"http", "https"} or not parsed.netloc:
        raise ExperimentContractError("model URL must be an absolute HTTP(S) URL")


def prepare(args: argparse.Namespace) -> Path:
    from src.utils.config import get_llm_settings

    repo = Path(args.repo).resolve()
    _validate_url(args.url)
    if not args.model.strip():
        raise ExperimentContractError("model is required; no default is allowed")
    if args.max_output_tokens != sum(P0_OUTPUT_BUDGET):
        raise ExperimentContractError(
            f"Development output budget must be {sum(P0_OUTPUT_BUDGET)}"
        )
    if sum(P0_OUTPUT_BUDGET) != sum(P1_OUTPUT_BUDGET):
        raise ExperimentContractError("P0/P1 output budgets differ")
    stamp = datetime.now(ZoneInfo("Asia/Shanghai")).strftime("%Y%m%d-%H%M%S")
    run_dir = repo / "runs/specflow-experiments" / f"{stamp}-paper"
    if run_dir.exists():
        raise ExperimentContractError(f"experiment directory already exists: {run_dir}")
    run_dir.mkdir(parents=True)

    try:
        families = [_family_entry(repo, run_dir, family) for family in FAMILY_GROUPS]
        corpus = {
            "schema_version": "specflow_paper_corpus",
            "experiment_id": run_dir.name,
            "families": families,
            "coupledl2_cases": _coupledl2_entries(repo),
        }
        corpus_path = run_dir / "corpus.json"
        _write_json(corpus_path, corpus)

        prompt = repo / "src/experiments/assets/direct_one_shot_prompt.md"
        scoring = repo / "src/experiments/scoring.py"
        suite_ledger = repo / "benchmark/synth/SPECIFICATIONS.sha256"
        frozen_inputs = {
            "corpus": _ref(repo, corpus_path),
            "prompt": _ref(repo, prompt),
            "scoring_script": _ref(repo, scoring),
            "experiment_runner": _ref(repo, repo / "src/experiments/paper.py"),
            "direct_baseline": _ref(repo, repo / "src/experiments/direct.py"),
            "llm_client": _ref(repo, repo / "src/core/llm_client.py"),
            "specflow_authoring": _ref(
                repo, repo / "src/chiselspecflow/authoring.py"
            ),
            "specflow_tool_schema": _ref(
                repo, repo / "src/chiselspecflow/authoring_tools.py"
            ),
            "suite_ledger": _ref(repo, suite_ledger),
            "input_set_sha256": _aggregate_hashes(corpus),
        }
        environment_extra_body = get_llm_settings()["extra_body"]
        config = {
            "schema_version": "specflow_paper_experiment",
            "experiment_id": run_dir.name,
            "status": "development",
            "created_at": datetime.now(ZoneInfo("Asia/Shanghai")).isoformat(),
            "git": _git_identity(repo),
            "model": {
                "name": args.model,
                "url": args.url,
                "temperature": 0,
                "max_output_tokens": sum(P0_OUTPUT_BUDGET),
                "output_budget_tokens": {
                    "p0": list(P0_OUTPUT_BUDGET),
                    "p1": list(P1_OUTPUT_BUDGET),
                },
                "environment_extra_body": environment_extra_body,
                "request_mode": {
                    "thinking": {"type": "disabled"},
                    "parallel_tool_calls": False,
                    "tool_choice": "required_named_tool",
                },
            },
            "specflow_formal": {
                "global_timeout_seconds": 300,
                "per_property_timeout_seconds": 60,
            },
            "coupledl2_formal": {
                "global_timeout_seconds": 1800,
                "per_property_timeout_seconds": 170,
                "engine_threads": 16,
                "max_jobs": 32,
                "profile_ids": ["mshr_wait_bound_poc", "write_read_poc"],
            },
            "vca": {
                "max_depth": 12,
                "max_nodes": 120,
                "random_seed": 0,
                "methods": {
                    "d0": {"mode": "structural", "features": []},
                    "d1": {
                        "mode": "chisel_aware",
                        "features": [
                            "instance_graph", "endpoint_projection",
                            "compiler_net_normalization", "register_transition",
                            "aggregate", "handshake", "pipeline", "source_provenance",
                        ],
                    },
                    "d2": {
                        "mode": "chisel_aware",
                        "features": [
                            "instance_graph", "endpoint_projection",
                            "compiler_net_normalization", "register_transition",
                            "aggregate", "handshake", "pipeline", "source_provenance",
                            "temporal_interval", "waitfor",
                        ],
                    },
                },
            },
            "frozen_inputs": frozen_inputs,
            "invocation": {
                "argv": list(args.recorded_argv),
                "working_directory": str(repo),
                "environment_variable_names": sorted(SENSITIVE_ENV_NAMES | {
                    "CHISELLMFV_LLM_BASE_URL",
                    "CHISELLMFV_LLM_URL",
                    "CHISELLMFV_LLM_MODEL",
                    "CHISELLMFV_LLM_EXTRA_BODY",
                    "LM_LICENSE_FILE",
                }),
            },
        }
        _write_json(run_dir / "config.development.json", config)
        _write_json(run_dir / "config.json", config)
        (run_dir / "config.development.sha256").write_text(
            _sha256(run_dir / "config.development.json")
            + "  config.development.json\n",
            encoding="utf-8",
        )
        for ledger in LEDGERS:
            (run_dir / ledger).touch(exist_ok=False)
        for relative in (
            "raw/track_p", "raw/track_d", "raw/coupledl2",
        ):
            (run_dir / relative).mkdir(parents=True, exist_ok=True)
        (run_dir / "run.log").write_text(
            f"{config['created_at']} prepared {run_dir.name}; no model or formal invoked\n",
            encoding="utf-8",
        )
        write_report(run_dir)
        validate_prepared(run_dir)
    except Exception:
        # A partially materialized experiment is never resumable.  Preserve it
        # for diagnosis and mark the failure instead of silently deleting it.
        failure = run_dir / "PREPARATION_FAILED"
        failure.write_text("preparation did not pass the 9.1/9.2 gate\n", encoding="utf-8")
        raise
    return run_dir


def _load_jsonl(path: Path) -> list[dict[str, Any]]:
    rows = []
    for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        if not line.strip():
            continue
        value = json.loads(line)
        if not isinstance(value, dict):
            raise ExperimentContractError(f"{path}:{number}: row must be an object")
        rows.append(value)
    return rows


def append_row(run_dir: Path, ledger_name: str, row_path: Path) -> None:
    if ledger_name not in LEDGERS:
        raise ExperimentContractError(f"unknown canonical ledger: {ledger_name}")
    row = json.loads(row_path.read_text(encoding="utf-8"))
    if not isinstance(row, dict):
        raise ExperimentContractError("result row must be a JSON object")
    missing = LEDGER_REQUIRED_FIELDS[ledger_name] - set(row)
    if missing:
        raise ExperimentContractError(f"result row missing fields: {sorted(missing)}")
    ledger = run_dir / ledger_name
    existing = _load_jsonl(ledger)
    identity = (row["task"], row.get("method"))
    if any((item["task"], item.get("method")) == identity for item in existing):
        raise ExperimentContractError(f"duplicate result row: {identity}")
    with ledger.open("a", encoding="utf-8") as stream:
        stream.write(json.dumps(row, ensure_ascii=False, sort_keys=True) + "\n")


def convert_vcd(
    run_dir: Path,
    input_vcd: Path,
    output_fst: Path,
    case_id: str,
    property_id: str,
) -> dict[str, Any]:
    input_vcd = input_vcd.resolve()
    output_fst = output_fst.resolve()
    if not input_vcd.is_file():
        raise ExperimentContractError(f"missing exact VCD: {input_vcd}")
    output_fst.parent.mkdir(parents=True, exist_ok=True)
    result = subprocess.run(
        ["vcd2fst", str(input_vcd), str(output_fst)],
        cwd=run_dir,
        text=True,
        capture_output=True,
    )
    record = {
        "schema_version": "vcd_to_fst_conversion",
        "case_id": case_id,
        "property_id": property_id,
        "command": ["vcd2fst", str(input_vcd), str(output_fst)],
        "input_vcd": {"path": str(input_vcd), "sha256": _sha256(input_vcd)},
        "exit_code": result.returncode,
        "stderr": result.stderr[-4000:],
    }
    if result.returncode != 0 or not output_fst.is_file() or output_fst.stat().st_size == 0:
        record["status"] = "incomplete"
        record["reason"] = "vcd2fst_failed"
    else:
        record["status"] = "complete"
        record["output_fst"] = {
            "path": str(output_fst),
            "sha256": _sha256(output_fst),
            "size_bytes": output_fst.stat().st_size,
        }
    record_path = output_fst.with_suffix(output_fst.suffix + ".conversion.json")
    _write_json(record_path, record)
    return record


def write_report(run_dir: Path) -> None:
    from src.experiments.scoring import status_counts

    decision_path = run_dir / "decision.json"
    status = "development"
    if decision_path.is_file():
        status = json.loads(decision_path.read_text(encoding="utf-8"))["decision"]
    sections = [
        "# SpecFlow paper experiment report",
        "",
        f"- Experiment ID: `{run_dir.name}`",
        f"- Config status: `{status}`",
        "",
        "## Canonical ledger status",
        "",
        "| Ledger | Rows | Status counts |",
        "|---|---:|---|",
    ]
    for ledger in LEDGERS:
        rows = _load_jsonl(run_dir / ledger)
        counts = json.dumps(status_counts(rows), ensure_ascii=False, sort_keys=True)
        sections.append(f"| `{ledger}` | {len(rows)} | `{counts}` |")
    sections.extend([
        "",
        "No statistical conclusion is emitted until the scheduled rows exist.",
        "",
    ])
    (run_dir / "report.md").write_text("\n".join(sections), encoding="utf-8")


def validate_prepared(run_dir: Path) -> None:
    config_path = run_dir / "config.json"
    development_config_path = run_dir / "config.development.json"
    corpus_path = run_dir / "corpus.json"
    config = json.loads(config_path.read_text(encoding="utf-8"))
    development_config = json.loads(
        development_config_path.read_text(encoding="utf-8")
    )
    corpus = json.loads(corpus_path.read_text(encoding="utf-8"))
    if config != development_config:
        raise ExperimentContractError("Development config snapshot differs from config.json")
    if config["experiment_id"] != run_dir.name or corpus["experiment_id"] != run_dir.name:
        raise ExperimentContractError("experiment identity mismatch")
    if len(corpus["families"]) != 10 or len(corpus["coupledl2_cases"]) != 2:
        raise ExperimentContractError("corpus must contain 10 families and 2 CoupledL2 cases")
    if any(entry["main_configuration_id"] != "cfg_000" for entry in corpus["families"]):
        raise ExperimentContractError("every family must use cfg_000")
    for entry in corpus["families"]:
        decomposition = entry.get("property_decomposition")
        scope = entry.get("selected_authoring_scope")
        if not isinstance(decomposition, dict) or not decomposition.get("sha256"):
            raise ExperimentContractError(
                f"{entry['family']}: corpus lacks decomposition hash"
            )
        if (
            not isinstance(scope, dict)
            or len(scope.get("primary_component_ids", [])) != 1
            or not scope.get("component_ids")
            or not scope.get("require_complete_primary_set")
        ):
            raise ExperimentContractError(
                f"{entry['family']}: selected authoring scope is incomplete"
            )
    required = {
        "name", "url", "temperature", "max_output_tokens",
        "output_budget_tokens", "request_mode"
    }
    if required - set(config["model"]):
        raise ExperimentContractError("model budget is incomplete")
    budgets = config["model"]["output_budget_tokens"]
    if (
        budgets.get("p0") != list(P0_OUTPUT_BUDGET)
        or budgets.get("p1") != list(P1_OUTPUT_BUDGET)
        or sum(budgets["p0"]) != sum(budgets["p1"])
        or config["model"]["request_mode"]
        != {
            "thinking": {"type": "disabled"},
            "parallel_tool_calls": False,
            "tool_choice": "required_named_tool",
        }
    ):
        raise ExperimentContractError("P0/P1 request mode or output budget differs")
    if config["frozen_inputs"]["corpus"]["sha256"] != _sha256(corpus_path):
        raise ExperimentContractError("corpus hash mismatch")
    for ledger in LEDGERS:
        if not (run_dir / ledger).is_file():
            raise ExperimentContractError(f"missing canonical ledger: {ledger}")


def _verify_frozen_reference(repo: Path, reference: Mapping[str, Any]) -> None:
    path = _repo_path(repo, reference)
    if (
        not path.is_file()
        or _sha256(path) != reference.get("sha256")
        or path.stat().st_size != reference.get("size_bytes")
    ):
        raise ExperimentContractError(f"frozen input drift: {reference.get('path')}")


def _experiment_config(
    run_dir: Path, repo: Path, group: str
) -> dict[str, Any]:
    validate_prepared(run_dir)
    value = json.loads(
        (run_dir / "config.development.json").read_text(encoding="utf-8")
    )
    decision_path = run_dir / "decision.json"
    if group == "Development":
        if decision_path.exists():
            raise ExperimentContractError("Development decision already exists")
        if value.get("status") != "development":
            raise ExperimentContractError(
                "Development Track P requires development status"
            )
        return value
    if group != "Evaluation":
        raise ExperimentContractError(f"unknown corpus group: {group}")
    if not decision_path.is_file():
        raise ExperimentContractError("Evaluation requires decision.json")
    decision = json.loads(decision_path.read_text(encoding="utf-8"))
    if (
        decision.get("decision") != "frozen"
        or decision.get("evaluation_authorized") is not True
    ):
        raise ExperimentContractError("Evaluation is not authorized by a frozen decision")
    if decision.get("config_development_sha256") != _sha256(
        run_dir / "config.development.json"
    ):
        raise ExperimentContractError("frozen config drift")
    decision_digest_path = run_dir / "decision.sha256"
    expected_decision_line = (
        _sha256(decision_path) + "  decision.json\n"
    )
    if (
        not decision_digest_path.is_file()
        or decision_digest_path.read_text(encoding="utf-8")
        != expected_decision_line
    ):
        raise ExperimentContractError("decision hash drift")
    frozen_inputs = decision.get("frozen_inputs")
    if not isinstance(frozen_inputs, Mapping):
        raise ExperimentContractError("frozen decision lacks input references")
    for name in (
        "corpus",
        "prompt",
        "scoring_script",
        "experiment_runner",
        "direct_baseline",
        "specflow_tool_schema",
        "specflow_authoring",
        "llm_client",
        "suite_ledger",
    ):
        reference = frozen_inputs.get(name)
        if not isinstance(reference, Mapping):
            raise ExperimentContractError(f"frozen decision lacks {name}")
        _verify_frozen_reference(repo, reference)
    if frozen_inputs.get("input_set_sha256") != value["frozen_inputs"].get(
        "input_set_sha256"
    ):
        raise ExperimentContractError("frozen input-set hash drift")
    return value


def _corpus_family(run_dir: Path, family: str, group: str) -> dict[str, Any]:
    corpus = json.loads((run_dir / "corpus.json").read_text(encoding="utf-8"))
    matches = [row for row in corpus["families"] if row["family"] == family]
    if len(matches) != 1 or matches[0]["group"] != group:
        raise ExperimentContractError(f"not one {group} family: {family}")
    return matches[0]


def _repo_path(repo: Path, reference: Mapping[str, Any]) -> Path:
    path = Path(reference["path"])
    return path if path.is_absolute() else repo / path


def _scheduled_track_p_tasks(group: str) -> tuple[str, ...]:
    families = (
        DEVELOPMENT_FAMILIES if group == "Development" else EVALUATION_FAMILIES
    )
    return tuple(f"{family}-{method}" for family in families for method in ("p0", "p1"))


def _assert_track_p_task_order(
    run_dir: Path, group: str, family: str, method: str
) -> None:
    task = f"{family}-{method}"
    schedule = _scheduled_track_p_tasks(group)
    if task not in schedule:
        raise ExperimentContractError(f"{task} is not scheduled for {group}")
    rows = [
        row
        for row in _load_jsonl(run_dir / "track_p.jsonl")
        if row.get("group") == group
    ]
    actual = tuple(row.get("task") for row in rows)
    expected_prefix = schedule[: schedule.index(task)]
    if actual != expected_prefix:
        raise ExperimentContractError(
            f"{group} Track P order mismatch: expected={expected_prefix}, actual={actual}"
        )


def track_p_author(args: argparse.Namespace) -> Path:
    """Perform the single scheduled authoring action for one P0/P1 task."""

    from src.chiselspecflow.authoring import run_asset_authoring
    from src.chiselspecflow.config import SpecFlowRunConfig
    from src.chiselspecflow.preflight import prepare_workspace
    from src.chiselspecflow.workspace import SpecFlowWorkspace
    from src.core.llm_client import LLMClient
    from src.experiments.direct import run_direct_one_shot

    run_dir = Path(args.run).resolve()
    repo = Path(args.repo).resolve()
    group = FAMILY_GROUPS[args.family]
    config = _experiment_config(run_dir, repo, group)
    family_entry = _corpus_family(run_dir, args.family, group)
    _assert_track_p_task_order(run_dir, group, args.family, args.method)
    method_root = run_dir / "raw/track_p" / args.family / args.method
    source_run = method_root / "source_run"
    state_path = method_root / "task_state.json"
    if state_path.exists() or source_run.exists():
        raise ExperimentContractError(
            f"scheduled authoring task already started: {args.family}-{args.method}"
        )
    method_root.mkdir(parents=True, exist_ok=True)
    project = _repo_path(repo, family_entry["project"])
    specification = _repo_path(repo, family_entry["specification"])
    configuration = _repo_path(repo, family_entry["configuration"])
    suite_ledger = repo / "benchmark/synth/SPECIFICATIONS.sha256"
    started = time.monotonic()
    workspace = prepare_workspace(
        SpecFlowRunConfig(
            project_contract=project,
            specification=specification,
            configuration=configuration,
            run_root=method_root,
            opaque_task_id=f"{args.family}-{args.method}",
            expected_property_ids=tuple(
                family_entry["selected_authoring_scope"]["expected_property_ids"]
            ),
            component_ids=tuple(
                family_entry["selected_authoring_scope"]["primary_component_ids"]
            ),
            clause_ids=tuple(
                family_entry["selected_authoring_scope"]["clause_ids"]
            ),
        ),
        source_run,
        suite_ledger,
    )
    client = LLMClient(
        model=config["model"]["name"],
        llm_url=config["model"]["url"],
        raw_response_dir=method_root / "raw_responses",
    )
    try:
        if args.method == "p0":
            result = run_direct_one_shot(
                workspace,
                client,
                max_tokens=config["model"]["output_budget_tokens"]["p0"][0],
            )
        else:
            result = run_asset_authoring(workspace, client)
        status = result["status"] if isinstance(result, dict) else result.status
    except Exception as exc:
        usage = client.get_token_usage()
        state = {
            "schema_version": "track_p_task_state",
            "task": f"{args.family}-{args.method}",
            "family": args.family,
            "method": args.method,
            "status": "authoring_error",
            "error": f"{type(exc).__name__}: {exc}",
            "source_run": str(source_run),
            "model_usage": usage,
            "model_request_attempts": int(usage.get("llm_calls", 0)),
            "wall_time_seconds": time.monotonic() - started,
        }
        _write_json(state_path, state)
        _append_terminal_track_p(run_dir, family_entry, method_root, state)
        return state_path
    state = {
        "schema_version": "track_p_task_state",
        "task": f"{args.family}-{args.method}",
        "family": args.family,
        "method": args.method,
        "status": status,
        "source_run": str(source_run),
        "model_usage": client.get_token_usage(),
        "model_request_attempts": int(client.get_token_usage().get("llm_calls", 0)),
        "wall_time_seconds": time.monotonic() - started,
    }
    _write_json(state_path, state)
    expected_ready = "completed" if args.method == "p0" else "awaiting_review"
    if status != expected_ready:
        _append_terminal_track_p(run_dir, family_entry, method_root, state)
    with (run_dir / "run.log").open("a", encoding="utf-8") as stream:
        stream.write(
            f"{datetime.now(ZoneInfo('Asia/Shanghai')).isoformat()} "
            f"authored {args.family}-{args.method} status={status}\n"
        )
    return state_path


def _append_terminal_track_p(
    run_dir: Path,
    family_entry: Mapping[str, Any],
    method_root: Path,
    state: Mapping[str, Any],
) -> Path:
    row = {
        "schema_version": "track_p_result",
        "task": state["task"],
        "family": state["family"],
        "group": family_entry["group"],
        "method": state["method"],
        "status": state["status"],
        "error": state.get("error"),
        "metrics": {
            "executable_variant_rate": None,
            "clean_false_alarm": None,
            "bug_kill_count": None,
            "bug_count": len(family_entry["bugs"]),
            "bug_kill_rate": None,
            "non_vacuous_property_rate": None,
        },
        "clean": None,
        "variants": [],
        "cost": {
            "model": state["model_usage"],
            "model_request_attempts": state.get("model_request_attempts"),
            "authoring_wall_time_seconds": state["wall_time_seconds"],
            "formal_wall_time_seconds": 0.0,
        },
        "input_hashes": {
            "corpus_sha256": _sha256(run_dir / "corpus.json"),
            "config_development_sha256": _sha256(
                run_dir / "config.development.json"
            ),
            "family_input_set_sha256": _aggregate_hashes(family_entry),
        },
        "artifacts": {
            "method_root": str(method_root),
            "source_run": state["source_run"],
            "verification_package_sha256": None,
        },
    }
    row_path = method_root / "track_p_row.json"
    _write_json(row_path, row)
    append_row(run_dir, "track_p.jsonl", row_path)
    write_report(run_dir)
    return row_path


def _terminal_authoring_state(method_root: Path) -> dict[str, Any]:
    """Resolve the terminal P1 state after the one external review."""

    state_path = method_root / "task_state.json"
    state = json.loads(state_path.read_text(encoding="utf-8"))
    if state.get("status") != "awaiting_review":
        return state
    if state.get("method") != "p1":
        raise ExperimentContractError("only P1 may await external review")
    source_run = Path(state["source_run"])
    manifest = json.loads(
        (source_run / "manifest.json").read_text(encoding="utf-8")
    )
    stage_result = json.loads(
        (
            source_run
            / "stages/01_asset_authoring/stage_result.json"
        ).read_text(encoding="utf-8")
    )
    if (
        manifest.get("review_state") != "rejected"
        or stage_result.get("status") != "rejected"
        or stage_result.get("error_kind") != "review_rejected"
    ):
        raise ExperimentContractError(
            "awaiting-review task has no installed terminal rejection"
        )
    terminal = dict(state)
    terminal["status"] = "rejected"
    terminal["error"] = stage_result.get("reason")
    _write_json(state_path, terminal)
    return terminal


def _variant_parameters(family: str, index: int) -> dict[str, Any]:
    if family == "counter":
        rows = {
            1: {"increment": 2, "overflowAtMax": True, "resetCounter": True},
            2: {"increment": 1, "overflowAtMax": False, "resetCounter": True},
            3: {"increment": 1, "overflowAtMax": True, "resetCounter": False},
        }
        return rows[index]
    return {"variantIndex": index}


def _variant_config(
    repo: Path,
    method_root: Path,
    family_entry: Mapping[str, Any],
    family: str,
    index: int,
) -> Path:
    clean = json.loads(
        _repo_path(repo, family_entry["configuration"]).read_text(encoding="utf-8")
    )
    value = {
        "schema_version": clean["schema_version"],
        "configuration_id": f"paper_bug_{index:02d}",
        "parameters": _variant_parameters(family, index),
    }
    path = method_root / "variant_inputs" / f"bug_{index:02d}.json"
    _write_json(path, value)
    return path


def _formal_summary(stage2: Path) -> dict[str, Any]:
    result_map = json.loads(
        (stage2 / "property_result_map.json").read_text(encoding="utf-8")
    )
    evidence = json.loads(
        (stage2 / "semantic_evidence.json").read_text(encoding="utf-8")
    )
    primary = [
        row for row in evidence["properties"]
        if isinstance(row.get("primary_status"), str)
    ]
    return {
        "execution_status": result_map["execution_status"],
        "formal_outcome": result_map["formal_outcome"],
        "evidence_status": result_map["evidence_status"],
        "operation_set_complete": result_map["operation_set_complete"],
        "expected_operation_count": result_map["expected_operation_count"],
        "accounted_operation_count": result_map["accounted_operation_count"],
        "status_counts": result_map["status_counts"],
        "primary_statuses": [row["primary_status"] for row in primary],
        "non_vacuous_properties": sum(
            row["evidence_status"] == "complete" for row in primary
        ),
        "property_count": len(primary),
    }


def track_p_verify(args: argparse.Namespace) -> Path:
    """Compile/formal-check clean plus all frozen bugs and append one row."""

    from src.chiselspecflow.config import SpecFlowRunConfig
    from src.chiselspecflow.preflight import prepare_workspace
    from src.chiselspecflow.runner import (
        run_compile_verify,
        run_direct_compile_verify,
        run_direct_frozen_package_replay,
        run_frozen_package_replay,
    )

    run_dir = Path(args.run).resolve()
    repo = Path(args.repo).resolve()
    group = FAMILY_GROUPS[args.family]
    config = _experiment_config(run_dir, repo, group)
    family_entry = _corpus_family(run_dir, args.family, group)
    _assert_track_p_task_order(run_dir, group, args.family, args.method)
    method_root = run_dir / "raw/track_p" / args.family / args.method
    state_path = method_root / "task_state.json"
    state = json.loads(state_path.read_text(encoding="utf-8"))
    source_run = Path(state["source_run"])
    if any(
        row["task"] == f"{args.family}-{args.method}"
        and row.get("method") == args.method
        for row in _load_jsonl(run_dir / "track_p.jsonl")
    ):
        raise ExperimentContractError("scheduled Track P row already exists")
    manifest = json.loads((source_run / "manifest.json").read_text(encoding="utf-8"))
    expected_state = "direct_submission" if args.method == "p0" else "approved"
    if manifest.get("review_state") != expected_state:
        raise ExperimentContractError(
            f"{args.family}-{args.method} is not ready for formal: "
            f"{manifest.get('review_state')}"
        )
    started = time.monotonic()
    timeout = config["specflow_formal"]["global_timeout_seconds"]
    per_property = config["specflow_formal"]["per_property_timeout_seconds"]

    def failure_summary(exc: Exception) -> dict[str, Any]:
        return {
            "execution_status": "compile_error",
            "formal_outcome": "not_run",
            "evidence_status": "incomplete",
            "operation_set_complete": False,
            "expected_operation_count": 0,
            "accounted_operation_count": 0,
            "status_counts": {},
            "primary_statuses": [],
            "non_vacuous_properties": 0,
            "property_count": 0,
            "error": f"{type(exc).__name__}: {exc}",
        }

    def record_failure(
        exc: Exception,
        *,
        clean: Mapping[str, Any],
        variants: list[dict[str, Any]],
    ) -> Path:
        row = {
            "schema_version": "track_p_result",
            "task": f"{args.family}-{args.method}",
            "family": args.family,
            "group": group,
            "method": args.method,
            "status": "compile_error",
            "error": f"{type(exc).__name__}: {exc}",
            "metrics": {
                "executable_variant_rate": 0.0,
                "clean_false_alarm": None,
                "bug_kill_count": 0,
                "bug_count": len(family_entry["bugs"]),
                "bug_kill_rate": 0.0,
                "non_vacuous_property_rate": None,
            },
            "clean": dict(clean),
            "variants": variants,
            "cost": {
                "model": state["model_usage"],
                "model_request_attempts": state.get("model_request_attempts"),
                "authoring_wall_time_seconds": state["wall_time_seconds"],
                "formal_wall_time_seconds": time.monotonic() - started,
            },
            "input_hashes": {
                "corpus_sha256": _sha256(run_dir / "corpus.json"),
                "config_development_sha256": _sha256(
                    run_dir / "config.development.json"
                ),
                "family_input_set_sha256": _aggregate_hashes(family_entry),
            },
            "artifacts": {
                "method_root": str(method_root),
                "source_run": str(source_run),
                "verification_package_sha256": _sha256(
                    source_run
                    / "stages/01_asset_authoring/verification_package.json"
                ),
            },
        }
        row_path = method_root / "track_p_row.json"
        _write_json(row_path, row)
        append_row(run_dir, "track_p.jsonl", row_path)
        write_report(run_dir)
        return row_path

    clean_runner = (
        run_direct_compile_verify if args.method == "p0" else run_compile_verify
    )
    try:
        clean_runner(
            source_run,
            timeout_seconds=timeout,
            per_property_seconds=per_property,
        )
    except Exception as exc:
        return record_failure(exc, clean=failure_summary(exc), variants=[])
    clean_stage = source_run / "stages/02_compile_verify"
    try:
        clean_summary = _formal_summary(clean_stage)
    except Exception as exc:
        return record_failure(exc, clean=failure_summary(exc), variants=[])
    variants = []
    project = _repo_path(repo, family_entry["project"])
    specification = _repo_path(repo, family_entry["specification"])
    suite_ledger = repo / "benchmark/synth/SPECIFICATIONS.sha256"
    replay_runner = (
        run_direct_frozen_package_replay
        if args.method == "p0"
        else run_frozen_package_replay
    )
    for bug in family_entry["bugs"]:
        index = int(bug["variant_index"])
        variant_config = _variant_config(
            repo, method_root, family_entry, args.family, index
        )
        target_run = method_root / "variants" / f"bug_{index:02d}"
        prepare_workspace(
            SpecFlowRunConfig(
                project_contract=project,
                specification=specification,
                configuration=variant_config,
                run_root=target_run.parent,
                opaque_task_id=f"{args.family}-{args.method}-bug-{index:02d}",
                expected_property_ids=tuple(
                    family_entry["selected_authoring_scope"][
                        "expected_property_ids"
                    ]
                ),
                component_ids=tuple(
                    family_entry["selected_authoring_scope"][
                        "primary_component_ids"
                    ]
                ),
                clause_ids=tuple(
                    family_entry["selected_authoring_scope"]["clause_ids"]
                ),
            ),
            target_run,
            suite_ledger,
        )
        try:
            replay_runner(
                target_run,
                source_run,
                timeout_seconds=timeout,
                per_property_seconds=per_property,
            )
            summary = _formal_summary(target_run / "stages/02_compile_verify")
        except Exception as exc:
            summary = failure_summary(exc)
        variants.append(
            {
                "bug_id": bug["bug_id"],
                "variant_index": index,
                "run": str(target_run),
                "summary": summary,
            }
        )
    killed = [
        row["bug_id"]
        for row in variants
        if "cex" in row["summary"]["primary_statuses"]
    ]
    clean_false_alarm = "cex" in clean_summary["primary_statuses"]
    all_summaries = [clean_summary, *[row["summary"] for row in variants]]
    executable = sum(
        summary["operation_set_complete"]
        and summary["execution_status"] != "tool_error"
        for summary in all_summaries
    )
    row = {
        "schema_version": "track_p_result",
        "task": f"{args.family}-{args.method}",
        "family": args.family,
        "group": group,
        "method": args.method,
        "status": (
            "completed"
            if executable == len(all_summaries)
            else "partial"
        ),
        "error": None,
        "metrics": {
            "executable_variant_rate": executable / len(all_summaries),
            "clean_false_alarm": clean_false_alarm,
            "bug_kill_count": len(killed),
            "bug_count": len(variants),
            "bug_kill_rate": len(killed) / len(variants),
            "non_vacuous_property_rate": (
                clean_summary["non_vacuous_properties"]
                / clean_summary["property_count"]
                if clean_summary["property_count"]
                else None
            ),
        },
        "clean": clean_summary,
        "variants": variants,
        "cost": {
            "model": state["model_usage"],
            "model_request_attempts": state.get("model_request_attempts"),
            "authoring_wall_time_seconds": state["wall_time_seconds"],
            "formal_wall_time_seconds": time.monotonic() - started,
        },
        "input_hashes": {
            "corpus_sha256": _sha256(run_dir / "corpus.json"),
            "config_development_sha256": _sha256(
                run_dir / "config.development.json"
            ),
            "family_input_set_sha256": _aggregate_hashes(family_entry),
        },
        "artifacts": {
            "method_root": str(method_root),
            "source_run": str(source_run),
            "verification_package_sha256": _sha256(
                source_run / "stages/01_asset_authoring/verification_package.json"
            ),
        },
    }
    row_path = method_root / "track_p_row.json"
    _write_json(row_path, row)
    append_row(run_dir, "track_p.jsonl", row_path)
    write_report(run_dir)
    with (run_dir / "run.log").open("a", encoding="utf-8") as stream:
        stream.write(
            f"{datetime.now(ZoneInfo('Asia/Shanghai')).isoformat()} "
            f"verified {args.family}-{args.method} status={row['status']}\n"
        )
    return row_path


def _development_rows(run_dir: Path) -> list[dict[str, Any]]:
    rows = [
        row
        for row in _load_jsonl(run_dir / "track_p.jsonl")
        if row.get("group") == "Development"
    ]
    expected = {
        (family, method)
        for family in ("counter", "fsm_16", "i2c")
        for method in ("p0", "p1")
    }
    actual = {(row.get("family"), row.get("method")) for row in rows}
    if actual != expected or len(rows) != 6:
        raise ExperimentContractError(
            f"9.4 requires exactly six Development rows; missing={sorted(expected - actual)}"
        )
    schema_keys = {tuple(sorted(row)) for row in rows}
    if len(schema_keys) != 1:
        raise ExperimentContractError("P0/P1 rows do not share one exact schema")
    return rows


def _has_verification_package_and_clean_run(row: Mapping[str, Any]) -> bool:
    return (
        isinstance((row.get("artifacts") or {}).get("verification_package_sha256"), str)
        and isinstance(row.get("clean"), Mapping)
        and "execution_status" in row["clean"]
        and row["clean"].get("operation_set_complete") is True
    )


def _write_development_decision(
    run_dir: Path,
    config: Mapping[str, Any],
    rows: list[dict[str, Any]],
    decision: str,
) -> None:
    decision_path = run_dir / "decision.json"
    if decision_path.exists():
        raise ExperimentContractError("Development decision already exists")
    repo = Path.cwd().resolve()
    record = {
        "schema_version": "specflow_development_decision",
        "experiment_id": run_dir.name,
        "decision": decision,
        "evaluation_authorized": decision == "frozen",
        "decided_at": datetime.now(ZoneInfo("Asia/Shanghai")).isoformat(),
        "config_development_sha256": _sha256(
            run_dir / "config.development.json"
        ),
        "track_p_development_sha256": _sha256(run_dir / "track_p.jsonl"),
        "task_statuses": {row["task"]: row["status"] for row in rows},
        "frozen_inputs": {
        "corpus": _ref(repo, run_dir / "corpus.json"),
        "prompt": _ref(repo, repo / "src/experiments/assets/direct_one_shot_prompt.md"),
        "scoring_script": _ref(repo, repo / "src/experiments/scoring.py"),
        "experiment_runner": _ref(repo, repo / "src/experiments/paper.py"),
        "direct_baseline": _ref(repo, repo / "src/experiments/direct.py"),
        "specflow_tool_schema": _ref(
            repo, repo / "src/chiselspecflow/authoring_tools.py"
        ),
        "specflow_authoring": _ref(repo, repo / "src/chiselspecflow/authoring.py"),
        "llm_client": _ref(repo, repo / "src/core/llm_client.py"),
        "suite_ledger": config["frozen_inputs"]["suite_ledger"],
        "input_set_sha256": config["frozen_inputs"]["input_set_sha256"],
        },
    }
    if decision == "pilot_only":
        record["reasons"] = [
            row["task"]
            for row in rows
            if not _has_verification_package_and_clean_run(row)
        ]
    _write_json(decision_path, record)
    (run_dir / "decision.sha256").write_text(
        _sha256(decision_path) + "  decision.json\n",
        encoding="utf-8",
    )
    write_report(run_dir)
    with (run_dir / "run.log").open("a", encoding="utf-8") as stream:
        stream.write(
            f"{record['decided_at']} 9.4 decision={decision} "
            f"track_p_sha256={record['track_p_development_sha256']}\n"
        )


def freeze_development(run_dir: Path) -> None:
    """Write the one 9.4 freeze decision without changing Development config."""

    run_dir = Path(run_dir).resolve()
    config = _experiment_config(run_dir, Path.cwd().resolve(), "Development")
    rows = _development_rows(run_dir)
    if not all(_has_verification_package_and_clean_run(row) for row in rows):
        raise ExperimentContractError(
            "freeze requires six packages and six clean deterministic verification runs"
        )
    _write_development_decision(run_dir, config, rows, "frozen")


def close_development_pilot(run_dir: Path) -> None:
    """Record the 9.4 no-freeze decision when Development requires changes."""

    run_dir = Path(run_dir).resolve()
    config = _experiment_config(run_dir, Path.cwd().resolve(), "Development")
    rows = _development_rows(run_dir)
    if all(_has_verification_package_and_clean_run(row) for row in rows):
        raise ExperimentContractError("Development passed; use the freeze gate")
    _write_development_decision(run_dir, config, rows, "pilot_only")


def add_parser(subparsers: argparse._SubParsersAction[argparse.ArgumentParser]) -> None:
    experiment = subparsers.add_parser(
        "experiment", help="prepare and maintain the frozen paper experiment"
    )
    actions = experiment.add_subparsers(dest="experiment_action", required=True)
    prepare_parser = actions.add_parser("prepare")
    prepare_parser.add_argument("--repo", default=".")
    prepare_parser.add_argument("--model", required=True)
    prepare_parser.add_argument("--url", required=True)
    prepare_parser.add_argument("--max-output-tokens", type=int, required=True)

    validate_parser = actions.add_parser("validate")
    validate_parser.add_argument("--run", required=True)

    record_parser = actions.add_parser("record")
    record_parser.add_argument("--run", required=True)
    record_parser.add_argument("--ledger", required=True, choices=LEDGERS)
    record_parser.add_argument("--row", required=True)

    report_parser = actions.add_parser("report")
    report_parser.add_argument("--run", required=True)

    convert_parser = actions.add_parser("convert-vcd")
    convert_parser.add_argument("--run", required=True)
    convert_parser.add_argument("--input-vcd", required=True)
    convert_parser.add_argument("--output-fst", required=True)
    convert_parser.add_argument("--case-id", required=True)
    convert_parser.add_argument("--property-id", required=True)

    author_parser = actions.add_parser("track-p-author")
    author_parser.add_argument("--run", required=True)
    author_parser.add_argument("--repo", default=".")
    author_parser.add_argument(
        "--family", required=True, choices=list(FAMILY_GROUPS)
    )
    author_parser.add_argument("--method", required=True, choices=["p0", "p1"])

    verify_parser = actions.add_parser("track-p-verify")
    verify_parser.add_argument("--run", required=True)
    verify_parser.add_argument("--repo", default=".")
    verify_parser.add_argument(
        "--family", required=True, choices=list(FAMILY_GROUPS)
    )
    verify_parser.add_argument("--method", required=True, choices=["p0", "p1"])

    freeze_parser = actions.add_parser("freeze")
    freeze_parser.add_argument("--run", required=True)

    terminal_parser = actions.add_parser("record-terminal-authoring")
    terminal_parser.add_argument("--run", required=True)
    terminal_parser.add_argument("--repo", default=".")
    terminal_parser.add_argument(
        "--family", required=True, choices=["counter", "fsm_16", "i2c"]
    )
    terminal_parser.add_argument("--method", required=True, choices=["p0", "p1"])

    pilot_parser = actions.add_parser("close-pilot")
    pilot_parser.add_argument("--run", required=True)


def run(args: argparse.Namespace) -> None:
    if args.experiment_action == "prepare":
        args.recorded_argv = tuple(sys.argv)
        run_dir = prepare(args)
        print(json.dumps({"experiment_id": run_dir.name, "run_dir": str(run_dir)}, sort_keys=True))
        return
    run_dir = Path(args.run).resolve()
    if args.experiment_action == "validate":
        validate_prepared(run_dir)
        print(json.dumps({"run_dir": str(run_dir), "status": "valid"}, sort_keys=True))
    elif args.experiment_action == "record":
        append_row(run_dir, args.ledger, Path(args.row))
        write_report(run_dir)
        print(json.dumps({"run_dir": str(run_dir), "ledger": args.ledger}, sort_keys=True))
    elif args.experiment_action == "report":
        write_report(run_dir)
        print(json.dumps({"run_dir": str(run_dir), "report": str(run_dir / "report.md")}, sort_keys=True))
    elif args.experiment_action == "convert-vcd":
        record = convert_vcd(
            run_dir,
            Path(args.input_vcd),
            Path(args.output_fst),
            args.case_id,
            args.property_id,
        )
        print(json.dumps(record, ensure_ascii=False, sort_keys=True))
        if record["status"] != "complete":
            raise SystemExit(1)
    elif args.experiment_action == "track-p-author":
        state_path = track_p_author(args)
        print(json.dumps({"task_state": str(state_path)}, sort_keys=True))
    elif args.experiment_action == "track-p-verify":
        row_path = track_p_verify(args)
        print(json.dumps({"track_p_row": str(row_path)}, sort_keys=True))
    elif args.experiment_action == "freeze":
        freeze_development(run_dir)
        print(json.dumps({"run_dir": str(run_dir), "status": "frozen"}, sort_keys=True))
    elif args.experiment_action == "record-terminal-authoring":
        family_entry = _corpus_family(
            run_dir, args.family, FAMILY_GROUPS[args.family]
        )
        method_root = run_dir / "raw/track_p" / args.family / args.method
        state = _terminal_authoring_state(method_root)
        row_path = _append_terminal_track_p(
            run_dir, family_entry, method_root, state
        )
        print(json.dumps({"track_p_row": str(row_path)}, sort_keys=True))
    elif args.experiment_action == "close-pilot":
        close_development_pilot(run_dir)
        print(
            json.dumps(
                {"run_dir": str(run_dir), "status": "pilot_only"},
                sort_keys=True,
            )
        )
    else:
        raise ExperimentContractError(f"unknown experiment action: {args.experiment_action}")
