#!/usr/bin/env python3
"""
Run a stage-batched ChiselLMFV experiment on a selected vis-chisel subset.

The script is intentionally a runner, not a replacement for main.py:

1. Select 50 simple but representative benchmarks from benchmark/vis-chisel.
2. Prepare an isolated experiment workspace in chisel/extra_bench layout.
3. Run one stage, a stage range, or the full stage-batched workflow.
4. Snapshot every target after every stage into separate result directories.

Use --dry-run to print the selected targets without preparing or running.
Use --prepare-only to create the workspace and manifests without LLM/formal runs.
Use --end-stage build_top_module to stop after stage 1.
Use --run-name <name> --start-stage write_assertions to resume the same run at stage 2.
"""

from __future__ import annotations

import argparse
import datetime as _dt
import hashlib
import importlib
import json
import os
import shutil
import sys
import traceback
from dataclasses import asdict, dataclass
from pathlib import Path
from types import SimpleNamespace
from typing import Any, Dict, Iterable, List, Optional, Sequence


REPO_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_BENCHMARK_ROOT = REPO_ROOT / "benchmark" / "vis-chisel"
DEFAULT_EXPERIMENT_ROOT = REPO_ROOT / "log" / "vis_chisel_formal"

FORMAL_STAGES = [
    "build_top_module",
    "write_assertions",
    "invoke_verification",
    "waveform_explanation",
    "propose_bugfix",
]

# Curated for small size plus coverage across counters, arithmetic/data paths,
# control/protocol examples, parameterized families, ITC99, LTL cases, and
# CPU/peripheral style blocks. All have llmverify.VerilogGenerator in this repo.
CURATED_VIS_CHISEL_TARGETS = [
    "reset",
    "short",
    "counter",
    "gray",
    "gcd",
    "lock",
    "swap",
    "rcnum_rcnum16",
    "rotate_rotate4",
    "rgraph",
    "cube_cubeAbs",
    "newhanoi",
    "segments",
    "cgw",
    "fourbyfour_luckysevenone",
    "smult",
    "param_minmax",
    "matrix_matrix",
    "bufal_bufferAlloc",
    "barrel_barrel4",
    "spinner",
    "barrier",
    "ibuf",
    "minmax_minMax",
    "peterson",
    "dekker",
    "bpb",
    "jam",
    "crc",
    "field",
    "reqack_reqAck",
    "reqack_reqAckRed",
    "itc99_b01",
    "itc99_b02",
    "itc99_b03",
    "itc99_b04",
    "arbiter_arbiter",
    "arbiter_arbiter_le",
    "param_arbiter3",
    "treearb_4-arbit",
    "param_treearb_treearb4",
    "bakery_good_bakery",
    "philo_nosel",
    "param_philo_philo4",
    "param_drop_drop4",
    "strltl_twoq_LTLM1",
    "strltl_peterson",
    "torch_regfile_ABypassCtrl",
    "silver-mau",
    "usbphy",
]

SOURCE_IGNORE_NAMES = {
    ".bloop",
    ".metals",
    ".bsp",
    ".idea",
    ".scala-build",
    "__pycache__",
    "generated",
    "project",
    "target",
}

ARTIFACT_IGNORE_NAMES = {
    ".bloop",
    ".metals",
    ".bsp",
    ".idea",
    ".scala-build",
    "__pycache__",
    "target",
}


@dataclass(frozen=True)
class BenchmarkMeta:
    name: str
    path: str
    family: str
    scala_files: int
    scala_loc: int
    decl_count: int
    has_generated_sv: bool
    has_generator: bool
    score: int


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Select and run 50 vis-chisel benchmarks with stage-isolated artifacts.",
    )
    parser.add_argument(
        "--benchmark-root",
        type=Path,
        default=DEFAULT_BENCHMARK_ROOT,
        help="Source benchmark root, default: benchmark/vis-chisel",
    )
    parser.add_argument(
        "--experiment-root",
        type=Path,
        default=DEFAULT_EXPERIMENT_ROOT,
        help="Root directory for experiment runs.",
    )
    parser.add_argument(
        "--run-name",
        default=None,
        help="Run directory name. Defaults to a timestamp.",
    )
    parser.add_argument(
        "--count",
        type=int,
        default=50,
        help="Number of benchmarks to select.",
    )
    parser.add_argument(
        "--selection-mode",
        choices=["curated", "auto"],
        default="curated",
        help="curated is reproducible; auto uses a size/diversity heuristic.",
    )
    parser.add_argument(
        "--targets",
        default=None,
        help="Optional comma-separated explicit target list. Overrides selection-mode.",
    )
    parser.add_argument(
        "--max-loc",
        type=int,
        default=240,
        help="LOC threshold for automatic filling/selection.",
    )
    parser.add_argument(
        "--max-per-family",
        type=int,
        default=3,
        help="Family cap used by automatic selection.",
    )
    parser.add_argument(
        "--start-stage",
        choices=FORMAL_STAGES,
        default=None,
        help="First formal stage to run. Existing run directories resume automatically when this is after stage 1.",
    )
    parser.add_argument(
        "--end-stage",
        choices=FORMAL_STAGES,
        default=None,
        help="Last formal stage to run, inclusive. Defaults to the final stage.",
    )
    parser.add_argument(
        "--stage",
        choices=FORMAL_STAGES,
        default=None,
        help="Run exactly one stage; equivalent to --start-stage STAGE --end-stage STAGE.",
    )
    parser.add_argument(
        "--resume",
        action="store_true",
        help="Reuse an existing run directory, selected benchmark manifest, and workspace.",
    )
    parser.add_argument(
        "--start-target",
        "--start-benchmark",
        dest="start_target",
        default=None,
        help="Resume within the start stage from this benchmark name, inclusive.",
    )
    parser.add_argument(
        "--max-tokens",
        type=int,
        default=None,
        help="Forwarded to LLMClient as the total token budget.",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="Print selected benchmarks and exit without creating a workspace.",
    )
    parser.add_argument(
        "--prepare-only",
        action="store_true",
        help="Create workspace/manifests but do not run LLM or formal tools.",
    )
    parser.add_argument(
        "--force",
        action="store_true",
        help="Replace an existing fresh run directory. Ignored when resuming from a later stage.",
    )
    parser.add_argument(
        "--copy-support-dirs",
        action="store_true",
        help="Copy chisel/chiselfv and VerilogCausalAnalysis instead of symlinking them.",
    )
    return parser.parse_args()


def benchmark_family(name: str) -> str:
    if name.startswith("itc99_"):
        return "itc99"
    if name.startswith("strltl_"):
        parts = name.split("_")
        return "_".join(parts[:2]) if len(parts) >= 2 else "strltl"
    if name.startswith("param_"):
        parts = name.split("_")
        return "_".join(parts[:2]) if len(parts) >= 2 else "param"
    if name.startswith("torch_"):
        parts = name.split("_")
        return "_".join(parts[:2]) if len(parts) >= 2 else "torch"
    if name.startswith("silver-"):
        return name.split("_", 1)[0]
    for sep in ("_", "-"):
        if sep in name:
            return name.split(sep, 1)[0]
    return name


def count_scala_loc(text: str) -> int:
    return sum(
        1
        for line in text.splitlines()
        if line.strip() and not line.strip().startswith("//")
    )


def collect_benchmark_meta(benchmark_root: Path) -> Dict[str, BenchmarkMeta]:
    metas: Dict[str, BenchmarkMeta] = {}
    for bench_dir in sorted(p for p in benchmark_root.iterdir() if p.is_dir()):
        scala_paths = sorted(bench_dir.glob("*.scala"))
        if not scala_paths:
            continue

        scala_texts = [p.read_text(encoding="utf-8", errors="ignore") for p in scala_paths]
        combined = "\n".join(scala_texts)
        scala_loc = sum(count_scala_loc(text) for text in scala_texts)
        decl_count = combined.count("class ") + combined.count("object ")
        has_generated_sv = (bench_dir / "generated").is_dir() and any(
            (bench_dir / "generated").glob("*.sv")
        )
        has_generator = "object VerilogGenerator" in combined
        score = (
            scala_loc
            + 25 * (len(scala_paths) - 1)
            + 3 * decl_count
            + (0 if has_generated_sv else 25)
        )
        metas[bench_dir.name] = BenchmarkMeta(
            name=bench_dir.name,
            path=str(bench_dir),
            family=benchmark_family(bench_dir.name),
            scala_files=len(scala_paths),
            scala_loc=scala_loc,
            decl_count=decl_count,
            has_generated_sv=has_generated_sv,
            has_generator=has_generator,
            score=score,
        )
    return metas


def parse_targets(raw_targets: str) -> List[str]:
    targets = [target.strip() for target in raw_targets.split(",") if target.strip()]
    deduped: List[str] = []
    seen = set()
    for target in targets:
        if target not in seen:
            deduped.append(target)
            seen.add(target)
    return deduped


def validate_targets(
    names: Sequence[str],
    metas: Dict[str, BenchmarkMeta],
    require_generator: bool = True,
) -> List[BenchmarkMeta]:
    selected: List[BenchmarkMeta] = []
    missing: List[str] = []
    no_generator: List[str] = []
    for name in names:
        meta = metas.get(name)
        if meta is None:
            missing.append(name)
            continue
        if require_generator and not meta.has_generator:
            no_generator.append(name)
            continue
        selected.append(meta)
    if missing:
        raise SystemExit(f"Unknown benchmarks: {', '.join(missing)}")
    if no_generator:
        raise SystemExit(
            "Benchmarks without object VerilogGenerator: " + ", ".join(no_generator)
        )
    return selected


def automatic_selection(
    metas: Dict[str, BenchmarkMeta],
    count: int,
    max_loc: int,
    max_per_family: int,
    seeds: Iterable[BenchmarkMeta] = (),
) -> List[BenchmarkMeta]:
    selected: List[BenchmarkMeta] = []
    selected_names = set()
    family_counts: Dict[str, int] = {}

    for meta in seeds:
        if meta.name in selected_names:
            continue
        selected.append(meta)
        selected_names.add(meta.name)
        family_counts[meta.family] = family_counts.get(meta.family, 0) + 1

    eligible = [
        meta
        for meta in metas.values()
        if meta.has_generator and meta.scala_loc <= max_loc and meta.name not in selected_names
    ]
    buckets: Dict[str, List[BenchmarkMeta]] = {}
    for meta in eligible:
        buckets.setdefault(meta.family, []).append(meta)
    for bucket in buckets.values():
        bucket.sort(key=lambda m: (m.score, m.scala_loc, m.name))

    family_cap = max_per_family
    while len(selected) < count:
        added = False
        ordered_families = sorted(
            buckets,
            key=lambda family: (
                buckets[family][0].score if buckets[family] else 10**9,
                family,
            ),
        )
        for family in ordered_families:
            if len(selected) >= count:
                break
            if family_counts.get(family, 0) >= family_cap:
                continue
            while buckets[family] and buckets[family][0].name in selected_names:
                buckets[family].pop(0)
            if not buckets[family]:
                continue
            meta = buckets[family].pop(0)
            selected.append(meta)
            selected_names.add(meta.name)
            family_counts[family] = family_counts.get(family, 0) + 1
            added = True
        if not added:
            if family_cap > 20:
                break
            family_cap += 1

    if len(selected) < count:
        raise SystemExit(
            f"Only selected {len(selected)} benchmarks; increase --max-loc or lower --count."
        )
    return selected[:count]


def select_benchmarks(args: argparse.Namespace) -> List[BenchmarkMeta]:
    metas = collect_benchmark_meta(args.benchmark_root)
    if args.targets:
        selected = validate_targets(parse_targets(args.targets), metas)
    elif args.selection_mode == "curated":
        curated = validate_targets(CURATED_VIS_CHISEL_TARGETS, metas)
        seeds = [meta for meta in curated if meta.scala_loc <= args.max_loc]
        selected = automatic_selection(
            metas=metas,
            count=args.count,
            max_loc=args.max_loc,
            max_per_family=args.max_per_family,
            seeds=seeds[: args.count],
        )
    else:
        selected = automatic_selection(
            metas=metas,
            count=args.count,
            max_loc=args.max_loc,
            max_per_family=args.max_per_family,
        )

    if len(selected) != args.count:
        raise SystemExit(f"Expected {args.count} benchmarks, selected {len(selected)}.")
    return selected


def normalize_stage_range(args: argparse.Namespace) -> None:
    if args.stage and (args.start_stage or args.end_stage):
        raise SystemExit("--stage cannot be combined with --start-stage or --end-stage")

    start_stage = args.stage or args.start_stage or FORMAL_STAGES[0]
    end_stage = args.stage or args.end_stage or FORMAL_STAGES[-1]
    start_idx = FORMAL_STAGES.index(start_stage)
    end_idx = FORMAL_STAGES.index(end_stage)
    if start_idx > end_idx:
        raise SystemExit(
            f"--start-stage {start_stage} comes after --end-stage {end_stage}"
        )

    args.effective_start_stage = start_stage
    args.effective_end_stage = end_stage
    args.stage_range = FORMAL_STAGES[start_idx : end_idx + 1]


def read_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def load_existing_selection(run_dir: Path, benchmark_root: Path) -> List[BenchmarkMeta]:
    json_path = run_dir / "selected_benchmarks.json"
    if json_path.exists():
        rows = read_json(json_path)
        return [BenchmarkMeta(**row) for row in rows]

    txt_path = run_dir / "selected_benchmarks.txt"
    if txt_path.exists():
        names = [
            line.strip()
            for line in txt_path.read_text(encoding="utf-8").splitlines()
            if line.strip()
        ]
        metas = collect_benchmark_meta(benchmark_root)
        return validate_targets(names, metas)

    raise SystemExit(
        f"Cannot resume {run_dir}: selected_benchmarks.json/txt is missing"
    )


def load_existing_workspace(run_dir: Path) -> Path:
    config_path = run_dir / "experiment_config.json"
    workspace_dir = run_dir / "workspace"
    if config_path.exists():
        config = read_json(config_path)
        configured_workspace = config.get("workspace_dir")
        if configured_workspace:
            workspace_dir = Path(configured_workspace)
    workspace_dir = workspace_dir.resolve()
    if not workspace_dir.exists():
        raise SystemExit(f"Cannot resume {run_dir}: workspace not found: {workspace_dir}")
    return workspace_dir


def should_resume_run(run_dir: Path, args: argparse.Namespace) -> bool:
    if args.resume:
        return True
    return run_dir.exists() and (
        args.effective_start_stage != FORMAL_STAGES[0] or bool(args.start_target)
    )


def validate_start_target(args: argparse.Namespace, selected: Sequence[BenchmarkMeta]) -> None:
    if not args.start_target:
        args.start_target_index = None
        return

    target_names = [meta.name for meta in selected]
    if args.start_target not in target_names:
        raise SystemExit(
            f"--start-target {args.start_target!r} is not in selected benchmarks"
        )
    args.start_target_index = target_names.index(args.start_target)


def ensure_fresh_run_dir(run_dir: Path, force: bool) -> None:
    if run_dir.exists():
        if not force:
            raise SystemExit(f"Run directory already exists: {run_dir} (use --force)")
        shutil.rmtree(run_dir)
    run_dir.mkdir(parents=True)


def ignore_source_artifacts(_: str, names: Sequence[str]) -> set[str]:
    return {name for name in names if name in SOURCE_IGNORE_NAMES}


def ignore_heavy_artifacts(_: str, names: Sequence[str]) -> set[str]:
    ignored = set()
    for name in names:
        if name in ARTIFACT_IGNORE_NAMES or name.endswith(".class"):
            ignored.add(name)
    return ignored


def copy_or_link_dir(src: Path, dst: Path, copy: bool) -> None:
    if not src.exists():
        return
    if dst.exists() or dst.is_symlink():
        if dst.is_dir() and not dst.is_symlink():
            shutil.rmtree(dst)
        else:
            dst.unlink()
    if copy:
        shutil.copytree(src, dst, symlinks=True, ignore=ignore_heavy_artifacts)
    else:
        os.symlink(src.resolve(), dst, target_is_directory=True)


def prepare_workspace(
    run_dir: Path,
    selected: Sequence[BenchmarkMeta],
    copy_support_dirs: bool,
) -> Path:
    workspace_dir = run_dir / "workspace"
    chisel_dir = workspace_dir / "chisel"
    extra_bench_dir = chisel_dir / "extra_bench"
    verilog_extra_dir = workspace_dir / "verilog" / "extra_bench"
    extra_bench_dir.mkdir(parents=True)
    verilog_extra_dir.mkdir(parents=True)

    base_extra = REPO_ROOT / "chisel" / "extra_bench"
    for filename in ("build.sbt", "Makefile"):
        shutil.copy2(base_extra / filename, extra_bench_dir / filename)

    copy_or_link_dir(
        REPO_ROOT / "chisel" / "chiselfv",
        chisel_dir / "chiselfv",
        copy=copy_support_dirs,
    )
    copy_or_link_dir(
        REPO_ROOT / "VerilogCausalAnalysis",
        workspace_dir / "VerilogCausalAnalysis",
        copy=copy_support_dirs,
    )

    base_verilog = REPO_ROOT / "verilog" / "extra_bench"
    for filename in ("set_testtop.py", "setup.sh", "ResetCounter.sv"):
        shutil.copy2(base_verilog / filename, verilog_extra_dir / filename)

    for meta in selected:
        src = Path(meta.path)
        dst = extra_bench_dir / meta.name
        shutil.copytree(src, dst, ignore=ignore_source_artifacts)
        shutil.copy2(base_extra / "build.sbt", dst / "build.sbt")
        shutil.copy2(base_extra / "Makefile", dst / "Makefile")

    return workspace_dir


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def tree_inventory(root: Path) -> List[Dict[str, Any]]:
    if not root.exists():
        return []
    rows: List[Dict[str, Any]] = []
    for path in sorted(p for p in root.rglob("*") if p.is_file()):
        rel = path.relative_to(root).as_posix()
        rows.append(
            {
                "path": rel,
                "size": path.stat().st_size,
                "sha256": sha256_file(path),
            }
        )
    return rows


def copy_artifact_tree(src: Path, dst: Path) -> bool:
    if not src.exists():
        return False
    if dst.exists():
        shutil.rmtree(dst)
    shutil.copytree(src, dst, symlinks=True, ignore=ignore_heavy_artifacts)
    return True


def write_json(path: Path, data: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(data, indent=2, sort_keys=True, ensure_ascii=False, default=str)
        + "\n",
        encoding="utf-8",
    )


def append_jsonl(path: Path, data: Dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("a", encoding="utf-8") as handle:
        handle.write(json.dumps(data, ensure_ascii=False, default=str) + "\n")


def write_manifests(
    run_dir: Path,
    workspace_dir: Path,
    selected: Sequence[BenchmarkMeta],
    args: argparse.Namespace,
) -> None:
    targets = [meta.name for meta in selected]
    (run_dir / "selected_benchmarks.txt").write_text(
        "\n".join(targets) + "\n",
        encoding="utf-8",
    )
    write_json(run_dir / "selected_benchmarks.json", [asdict(meta) for meta in selected])
    write_json(
        run_dir / "experiment_config.json",
        {
            "created_at": _dt.datetime.now().isoformat(timespec="seconds"),
            "repo_root": str(REPO_ROOT),
            "workspace_dir": str(workspace_dir),
            "benchmark_root": str(args.benchmark_root),
            "selection_mode": args.selection_mode,
            "count": args.count,
            "max_loc": args.max_loc,
            "max_per_family": args.max_per_family,
            "start_stage": args.effective_start_stage,
            "end_stage": args.effective_end_stage,
            "stage_range": args.stage_range,
            "start_target": args.start_target,
            "max_tokens": args.max_tokens,
            "targets": targets,
        },
    )
    (run_dir / "README.md").write_text(
        "\n".join(
            [
                "# vis-chisel formal experiment",
                "",
                "This run directory was prepared by scripts/run_vis_chisel_formal_experiment.py.",
                "",
                "- `workspace/`: isolated ChiselLMFV workspace used by the run.",
                "- `selected_benchmarks.txt`: ordered target list passed to the batched workflow.",
                "- `results/by_stage/<stage>/<target>/`: per-stage artifact snapshots.",
                "- `log/`: process log files emitted by ChiselLMFV.",
                "",
            ]
        ),
        encoding="utf-8",
    )


def record_invocation(
    run_dir: Path,
    selected: Sequence[BenchmarkMeta],
    args: argparse.Namespace,
    resumed: bool,
) -> None:
    append_jsonl(
        run_dir / "results" / "run_invocations.jsonl",
        {
            "time": _dt.datetime.now().isoformat(timespec="seconds"),
            "resumed": resumed,
            "run_name": run_dir.name,
            "start_stage": args.effective_start_stage,
            "end_stage": args.effective_end_stage,
            "stage_range": args.stage_range,
            "start_target": args.start_target,
            "target_count": len(selected),
            "targets": [meta.name for meta in selected],
            "prepare_only": args.prepare_only,
            "max_tokens": args.max_tokens,
        },
    )


class StageArtifactRecorder:
    def __init__(self, run_dir: Path, workspace_dir: Path, stages: Sequence[str]):
        self.run_dir = run_dir
        self.workspace_dir = workspace_dir
        self.results_dir = run_dir / "results"
        self.stage_order = {stage: idx + 1 for idx, stage in enumerate(stages)}
        self.events_path = self.results_dir / "stage_events.jsonl"
        self.events_path.parent.mkdir(parents=True, exist_ok=True)

    def stage_dir(self, stage: str) -> Path:
        idx = self.stage_order.get(stage, 0)
        return self.results_dir / "by_stage" / f"{idx:02d}_{stage}"

    def record(self, workflow: Any, result: Dict[str, Any]) -> None:
        target = workflow.target
        stage = workflow.current_stage
        target_stage_dir = self.stage_dir(stage) / target
        if target_stage_dir.exists():
            shutil.rmtree(target_stage_dir)
        target_stage_dir.mkdir(parents=True)

        work_dir = Path(workflow.work_dir)
        verilog_dir = Path(workflow.verilog_dir)
        causal_dir = self.workspace_dir / "log" / "causal_analysis" / target

        copied = {
            "chisel_workdir": copy_artifact_tree(work_dir, target_stage_dir / "chisel_workdir"),
            "verilog": copy_artifact_tree(verilog_dir, target_stage_dir / "verilog"),
            "causal_analysis": copy_artifact_tree(
                causal_dir, target_stage_dir / "causal_analysis"
            ),
        }

        inventory = {
            "chisel_workdir": tree_inventory(target_stage_dir / "chisel_workdir"),
            "verilog": tree_inventory(target_stage_dir / "verilog"),
            "causal_analysis": tree_inventory(target_stage_dir / "causal_analysis"),
        }
        write_json(target_stage_dir / "stage_result.json", result)
        write_json(
            target_stage_dir / "artifact_manifest.json",
            {
                "target": target,
                "stage": stage,
                "copied": copied,
                "inventory": inventory,
            },
        )

        detail = result.get("stage_result", {}) if isinstance(result, dict) else {}
        event = {
            "time": _dt.datetime.now().isoformat(timespec="seconds"),
            "target": target,
            "stage": stage,
            "success": result.get("success") if isinstance(result, dict) else None,
            "summary": detail.get("summary"),
            "verification_passed": detail.get("verification_passed"),
            "counterexample_path": detail.get("counterexample_path"),
            "artifact_dir": str(target_stage_dir),
        }
        with self.events_path.open("a", encoding="utf-8") as handle:
            handle.write(json.dumps(event, ensure_ascii=False, default=str) + "\n")


def print_selection(selected: Sequence[BenchmarkMeta]) -> None:
    for idx, meta in enumerate(selected, start=1):
        print(
            f"{idx:02d} {meta.name:32} "
            f"family={meta.family:18} loc={meta.scala_loc:3} score={meta.score:3}"
        )


def stage_artifact_dir(run_dir: Path, formal_stages: Sequence[str], stage: str) -> Path:
    idx = formal_stages.index(stage) + 1
    return run_dir / "results" / "by_stage" / f"{idx:02d}_{stage}"


def load_recorded_stage_result(
    run_dir: Path,
    formal_stages: Sequence[str],
    stage: str,
    target: str,
) -> Optional[Dict[str, Any]]:
    result_path = stage_artifact_dir(run_dir, formal_stages, stage) / target / "stage_result.json"
    if not result_path.exists():
        return None
    return read_json(result_path)


def initialize_stage_state(
    run_dir: Path,
    targets: Sequence[str],
    formal_stages: Sequence[str],
    start_stage: str,
    start_target: Optional[str],
    logger: Any,
) -> tuple[Dict[str, bool], Dict[str, Dict[str, Any]], Dict[str, str]]:
    active: Dict[str, bool] = {target: True for target in targets}
    failed: Dict[str, str] = {}
    state: Dict[str, Dict[str, Any]] = {
        target: {"waveform_path": None, "verification_passed": False}
        for target in targets
    }

    start_idx = formal_stages.index(start_stage)
    cursor_idx = targets.index(start_target) if start_target else 0
    if start_idx == 0 and cursor_idx == 0:
        return active, state, failed

    prior_stages = formal_stages[:start_idx]
    logger.info(
        "Resume preflight: checking prior stage artifacts: "
        + (", ".join(prior_stages) if prior_stages else "(none)")
    )
    for target_idx, target in enumerate(targets):
        stages_to_check = list(prior_stages)
        if target_idx < cursor_idx:
            stages_to_check.append(start_stage)

        for stage in stages_to_check:
            result = load_recorded_stage_result(run_dir, formal_stages, stage, target)
            if result is None:
                failed[target] = f"missing_{stage}_result"
                active[target] = False
                logger.error(f"Target {target}: missing prior result for {stage}")
                break

            if not result.get("success", False):
                failed[target] = stage
                active[target] = False
                logger.error(f"Target {target}: prior stage {stage} was unsuccessful")
                break

            if stage == "invoke_verification":
                detail = result.get("stage_result", {})
                if detail.get("verification_passed", False):
                    state[target]["verification_passed"] = True
                    active[target] = False
                    logger.info(
                        f"Target {target}: already proven; skipping CEX stages"
                    )
                    break

                cex_path = detail.get("counterexample_path")
                if cex_path:
                    state[target]["waveform_path"] = cex_path
                    logger.info(f"Target {target}: resumed waveform path {cex_path}")
                else:
                    failed[target] = "missing_counterexample_waveform"
                    active[target] = False
                    logger.error(
                        f"Target {target}: prior verification failed without waveform"
                    )
                    break

    return active, state, failed


def run_stage_batched_formal_range(
    run_dir: Path,
    workflow_args: argparse.Namespace,
    targets: Sequence[str],
    llm_client: Any,
    logger: Any,
    workflow_cls: Any,
    formal_stages: Sequence[str],
    stage_range: Sequence[str],
    start_target: Optional[str],
) -> bool:
    active, state, failed = initialize_stage_state(
        run_dir=run_dir,
        targets=targets,
        formal_stages=formal_stages,
        start_stage=stage_range[0],
        start_target=start_target,
        logger=logger,
    )
    cursor_idx = targets.index(start_target) if start_target else 0

    logger.info(
        "Starting stage-batched workflow range: "
        f"{stage_range[0]} -> {stage_range[-1]}"
    )
    if start_target:
        logger.info(f"Start target cursor for first stage: {start_target}")
    logger.info(f"Targets: {', '.join(targets)}")

    for stage_idx, stage in enumerate(stage_range):
        runnable = [target for target in targets if active[target]]
        if stage_idx == 0 and cursor_idx:
            runnable = [
                target
                for target in runnable
                if targets.index(target) >= cursor_idx
            ]
        if not runnable:
            logger.info("No active targets remain for this stage range")
            break

        logger.info("=" * 80)
        logger.info(f"Stage-batched run: {stage} for {len(runnable)} active targets")
        logger.info("=" * 80)

        for target in runnable:
            waveform_path = (
                state[target].get("waveform_path")
                if stage == "waveform_explanation"
                else None
            )
            if stage == "waveform_explanation" and not waveform_path:
                failed[target] = "missing_counterexample_waveform"
                active[target] = False
                logger.error(f"Target {target} has no waveform path for waveform_explanation")
                continue

            logger.info(f"Running stage {stage} for target: {target}")
            workflow = workflow_cls(
                llm_client=llm_client,
                chisel_dir=workflow_args.chisel_dir,
                workspace_dir=workflow_args.workspace_dir,
                logger=logger,
                target=target,
                waveform_path=waveform_path,
                stage=stage,
            )
            result = workflow.process_task(
                user_query=workflow_args.get_default_query(stage=stage, target=target),
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
        logger.info("Stage-batched workflow range completed successfully")
    return not failed


def run_formal_experiment(
    run_dir: Path,
    workspace_dir: Path,
    selected: Sequence[BenchmarkMeta],
    args: argparse.Namespace,
    resumed: bool,
) -> bool:
    sys.path.insert(0, str(REPO_ROOT))
    sys.path.insert(0, str(REPO_ROOT / "src"))

    previous_cwd = Path.cwd()
    os.chdir(run_dir)
    try:
        # src.utils.logger binds LOG_PATH at import time. Importing from the run
        # directory keeps ChiselLMFV logs under <run_dir>/log.
        logger_module = importlib.import_module("src.utils.logger")

        from main import get_default_query
        from src.core.llm_client import LLMClient, TokenBudgetExceeded
        from src.core.workflow import FormalWorkflow
        from src.core.tool_schemas import FORMAL_STAGES as PROJECT_FORMAL_STAGES

        missing_stages = [stage for stage in args.stage_range if stage not in PROJECT_FORMAL_STAGES]
        if missing_stages:
            raise SystemExit(
                "Script stages are not supported by src.core.tool_schemas: "
                + ", ".join(missing_stages)
            )

        logger = logger_module.get_logger(
            __name__,
            console_output=True,
            clear_log=True,
            base_name=f"application-formal-full-vis-chisel-{run_dir.name}.log",
        )
        recorder = StageArtifactRecorder(
            run_dir=run_dir,
            workspace_dir=workspace_dir,
            stages=PROJECT_FORMAL_STAGES,
        )

        class RecordingFormalWorkflow(FormalWorkflow):
            def process_task(self, user_query: str) -> Dict[str, Any]:
                try:
                    result = super().process_task(user_query)
                except TokenBudgetExceeded:
                    raise
                except Exception as exc:
                    self.logger.error(
                        "Unhandled exception in stage %s for target %s: %s",
                        self.current_stage,
                        self.target,
                        exc,
                    )
                    result = {
                        "original_query": user_query,
                        "stage": self.current_stage,
                        "success": False,
                        "error": str(exc),
                        "traceback": traceback.format_exc(),
                        "stage_result": {
                            "success": False,
                            "summary": f"Unhandled exception: {exc}",
                            "error": str(exc),
                            "verification_passed": None,
                            "counterexample_path": None,
                        },
                    }
                recorder.record(self, result)
                return result

        llm_client = LLMClient(logger=logger, max_token_budget=args.max_tokens)
        run_args = SimpleNamespace(
            chisel_dir="chisel",
            workspace_dir=str(workspace_dir),
            waveform=None,
            get_default_query=get_default_query,
        )
        targets = [meta.name for meta in selected]

        try:
            success = run_stage_batched_formal_range(
                run_dir=run_dir,
                workflow_args=run_args,
                targets=targets,
                llm_client=llm_client,
                logger=logger,
                workflow_cls=RecordingFormalWorkflow,
                formal_stages=PROJECT_FORMAL_STAGES,
                stage_range=args.stage_range,
                start_target=args.start_target,
            )
        except TokenBudgetExceeded as exc:
            logger.error(f"Token budget exceeded during formal workflow: {exc}")
            success = False
        finally:
            llm_client.print_token_usage(logger)

        raw_log_file = getattr(logger_module, "log_file", None)
        log_file = None
        if raw_log_file:
            log_path = Path(raw_log_file)
            log_file = str(log_path if log_path.is_absolute() else run_dir / log_path)
    finally:
        os.chdir(previous_cwd)

    write_json(
        run_dir / "results" / "run_result.json",
        {
            "success": success,
            "resumed": resumed,
            "targets": targets,
            "stages": PROJECT_FORMAL_STAGES,
            "stages_run": args.stage_range,
            "start_stage": args.effective_start_stage,
            "end_stage": args.effective_end_stage,
            "log_file": log_file,
        },
    )
    return success


def main() -> int:
    args = parse_args()
    args.benchmark_root = args.benchmark_root.resolve()
    args.experiment_root = args.experiment_root.resolve()
    normalize_stage_range(args)

    if args.resume and not args.run_name:
        raise SystemExit("--resume requires --run-name")

    run_name = args.run_name or _dt.datetime.now().strftime("%Y%m%d-%H%M%S")
    run_dir = args.experiment_root / run_name
    resumed = should_resume_run(run_dir, args)

    if resumed:
        if not run_dir.exists():
            raise SystemExit(f"Cannot resume missing run directory: {run_dir}")
        if args.force:
            print(
                "Resuming existing run; --force is ignored so the previous "
                f"{run_dir.name} workspace and results are preserved."
            )
        selected = load_existing_selection(run_dir, args.benchmark_root)
        workspace_dir = load_existing_workspace(run_dir)
    else:
        selected = select_benchmarks(args)
        workspace_dir = run_dir / "workspace"

    validate_start_target(args, selected)
    print_selection(selected)
    print(
        "Stage range: "
        f"{args.effective_start_stage} -> {args.effective_end_stage}"
        + (" (resume)" if resumed else "")
    )
    if args.start_target:
        print(f"Start target: {args.start_target}")

    if args.dry_run:
        return 0

    if not resumed:
        ensure_fresh_run_dir(run_dir, force=args.force)
        workspace_dir = prepare_workspace(
            run_dir=run_dir,
            selected=selected,
            copy_support_dirs=args.copy_support_dirs,
        )
        write_manifests(
            run_dir=run_dir,
            workspace_dir=workspace_dir,
            selected=selected,
            args=args,
        )

    record_invocation(run_dir=run_dir, selected=selected, args=args, resumed=resumed)

    if args.prepare_only:
        print(f"Workspace: {workspace_dir}")
        print(f"Selected targets: {run_dir / 'selected_benchmarks.txt'}")
        return 0

    success = run_formal_experiment(
        run_dir=run_dir,
        workspace_dir=workspace_dir,
        selected=selected,
        args=args,
        resumed=resumed,
    )
    print(f"Run directory: {run_dir}")
    return 0 if success else 1


if __name__ == "__main__":
    raise SystemExit(main())
