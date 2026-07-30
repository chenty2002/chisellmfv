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
from datetime import datetime
from pathlib import Path
from typing import Any, Iterable
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

    # Reuse the public parser so this manifest cannot bless an invalid contract.
    from src.chiselspecflow.config import (
        load_generator_configuration,
        load_project_contract,
    )

    parsed_project = load_project_contract(project)
    parsed_config = load_generator_configuration(config, parsed_project)
    if parsed_config.configuration_id != "cfg_000":
        raise ExperimentContractError(f"{family}: main configuration is not cfg_000")

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
    repo = Path(args.repo).resolve()
    _validate_url(args.url)
    if not args.model.strip():
        raise ExperimentContractError("model is required; no default is allowed")
    if args.max_output_tokens < 1:
        raise ExperimentContractError("max output tokens must be positive")
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
            "suite_ledger": _ref(repo, suite_ledger),
            "input_set_sha256": _aggregate_hashes(corpus),
        }
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
                "max_output_tokens": args.max_output_tokens,
                "hard_token_limit": 32768,
                "calls_per_task": 1,
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
        _write_json(run_dir / "config.json", config)
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

    sections = [
        "# SpecFlow paper experiment report",
        "",
        f"- Experiment ID: `{run_dir.name}`",
        "- Config status: `development`",
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
    corpus_path = run_dir / "corpus.json"
    config = json.loads(config_path.read_text(encoding="utf-8"))
    corpus = json.loads(corpus_path.read_text(encoding="utf-8"))
    if config["experiment_id"] != run_dir.name or corpus["experiment_id"] != run_dir.name:
        raise ExperimentContractError("experiment identity mismatch")
    if len(corpus["families"]) != 10 or len(corpus["coupledl2_cases"]) != 2:
        raise ExperimentContractError("corpus must contain 10 families and 2 CoupledL2 cases")
    if any(entry["main_configuration_id"] != "cfg_000" for entry in corpus["families"]):
        raise ExperimentContractError("every family must use cfg_000")
    required = {
        "name", "url", "temperature", "max_output_tokens", "hard_token_limit"
    }
    if required - set(config["model"]):
        raise ExperimentContractError("model budget is incomplete")
    if config["frozen_inputs"]["corpus"]["sha256"] != _sha256(corpus_path):
        raise ExperimentContractError("corpus hash mismatch")
    for ledger in LEDGERS:
        if not (run_dir / ledger).is_file():
            raise ExperimentContractError(f"missing canonical ledger: {ledger}")


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
    else:
        raise ExperimentContractError(f"unknown experiment action: {args.experiment_action}")
