# ChiseLLMFV

ChiseLLMFV is the open-source artifact for the ICCD paper:

**ChiseLLMFV: Artifact-Grounded Source-Level Formal Verification for Chisel with LLMs**

The project implements an LLM-assisted, artifact-grounded workflow for
source-level formal verification of Chisel designs. The workflow edits and
repairs the maintained Chisel source, while deterministic tools check the
emitted SystemVerilog, JasperGold proof results, counterexample traces,
causal-analysis evidence, and quality-audit records.

## Public Artifact Scope

The open-source experiment data used by the paper is organized only under the
following directories:

```text
log/vis_chisel_formal/main
log/vis_chisel_formal/ablation
log/vis_chisel_formal/quality-main
log/vis_chisel_formal/quality-ablation
```

These directories contain the paper-facing results. For the main system and
for each ablation, the reported quality records are the best available records
selected from five attempts for each benchmark, using the same best-of
reporting rule as the paper. The tables in this README are computed from those
public records.

The demonstrating example mentioned in the paper is `ArbiterLE`, and its results
by stage are located at 
`log/vis_chisel_formal/main/results/by_stage/01_build_top_module/arbiter_arbiter_le`
`log/vis_chisel_formal/main/results/by_stage/02_write_assertions/arbiter_arbiter_le`
`log/vis_chisel_formal/main/results/by_stage/03_invoke_verification/arbiter_arbiter_le`
`log/vis_chisel_formal/main/results/by_stage/04_waveform_explanation/arbiter_arbiter_le`
`log/vis_chisel_formal/main/results/by_stage/05_propose_bugfix/arbiter_arbiter_le`.

## Repository Layout

```text
chisellmfv/
|-- benchmark/
|   |-- vis-chisel/             # VIS-derived Chisel benchmark suite
|   |-- RTLLM/                  # RTLLM benchmark submodule
|   `-- verilog-eval/           # VerilogEval benchmark submodule
|-- chisel/
|   |-- build.sbt               # Chisel/sbt project used by the workflow
|   |-- Makefile                # Chisel elaboration entry
|   |-- chiselfv/               # ChiselFV assertion-library submodule
|   |-- pylibfst-cache/         # optional FST reader source fallback
|   `-- extra_bench/            # runtime benchmark workspace
|-- verilog/
|   |-- setup.sh                # JasperGold driver
|   |-- set_testtop.py          # emitted SystemVerilog post-processing
|   `-- extra_bench/            # generated formal backend workspace
|-- verilog2chisel/
|   |-- verilog/<target>/       # input Verilog for conversion
|   |-- chisel/<target>/        # generated Chisel
|   `-- generated/<target>/     # emitted Verilog from converted Chisel
|-- VerilogCausalAnalysis/      # causal-DAG counterexample analyzer
|-- src/
|   |-- core/                   # formal workflow, LLM client, JasperGold runners
|   |-- causal_analysis/        # bridge to VerilogCausalAnalysis
|   |-- utils/                  # config, logging, file helpers
|   `-- verilog2chisel/         # Verilog-to-Chisel workflow
|-- log/vis_chisel_formal/
|   |-- main/                   # public main-run five-stage artifacts
|   |-- ablation/               # public ablation five-stage artifacts
|   |-- quality-main/           # public best-of main quality records
|   `-- quality-ablation/       # public best-of ablation quality records
|-- main.py                     # CLI entry point: formal, quality, v2c
|-- init.sh                     # environment bootstrap
|-- pyproject.toml
|-- requirements.txt
|-- .env.example
`-- README.md
```

## Workflow Summary

ChiseLLMFV uses five stages:

| Stage | Purpose | Acceptance evidence |
|---|---|---|
| Harness construction | Emit the intended device under verification | Chisel build log and emitted SystemVerilog |
| Assertion insertion | Add source-level properties | Emitted SystemVerilog with executable assertions |
| Formal verification | Prove or refute properties | JasperGold report and counterexample trace |
| Counterexample diagnosis | Classify and localize failures | Source, waveform, and causal-analysis evidence |
| Repair regression | Patch and re-run target failures | Repair-regression summary |

The deterministic quality audit checks build validity, assertion status,
assumption health, non-vacuity, mutation sensitivity, repair regression,
sequential equivalence, and X-propagation.

## Requirements

The Python package requires Python 3.10 or newer. The paper experiments used
Ubuntu 20.04, OpenJDK 11, sbt 1.11.2, Chisel 6.7.0, Python 3.13, JasperGold
Apps 2020.03, and GTKWave 3.4.0.

Install these tools before running the full workflow:

- Git with submodule support.
- OpenJDK 11 or newer.
- sbt.
- Cadence JasperGold, available as `jg` on `PATH`.
- GTKWave utilities, especially `vcd2fst`.
- `uv` for Python environment management.

Optional source-build tools are needed only when native Python wheels are not
available:

- `make`, `cmake`, `ninja`, `meson`, and a C/C++ compiler.
- Graphviz and `hdlConvertor` for causal analysis.

## Setup

```bash
git clone <repo-url> chisellmfv
cd chisellmfv

bash init.sh

cp -n .env.example .env
$EDITOR .env
```

Set at least:

```bash
CHISELLMFV_LLM_API_KEY=<your-api-key>
```

Optional endpoint and model overrides:

```bash
CHISELLMFV_LLM_BASE_URL=<optional-api-base-url>
CHISELLMFV_LLM_MODEL=<optional-model-name>
CHISELLMFV_LLM_EXTRA_BODY=<optional-json-extra-body>
```

If a native wheel is unavailable, activate the environment and use the source
fallbacks:

```bash
source .venv/bin/activate

cd chisel/pylibfst-cache
make install
cd -

bash scripts/install_hdlConvertor.sh
```

## Running ChiseLLMFV

Use `.venv/bin/python` after setup, or activate the virtual environment and
use `python`.

### Single-target formal verification

Run the full five-stage workflow:

```bash
.venv/bin/python main.py formal --full --target gigamax
```

Run one stage:

```bash
.venv/bin/python main.py formal --stage build_top_module --target gigamax
.venv/bin/python main.py formal --stage write_assertions --target gigamax
.venv/bin/python main.py formal --stage invoke_verification --target gigamax
```

Resume a full workflow from a later stage:

```bash
.venv/bin/python main.py formal \
  --full \
  --start-stage write_assertions \
  --target gigamax
```

Run counterexample diagnosis from an existing FST trace:

```bash
.venv/bin/python main.py formal \
  --stage waveform_explanation \
  --target gigamax \
  --waveform chisel/extra_bench/gigamax/generated/<counterexample>.fst
```

Run the repair stage:

```bash
.venv/bin/python main.py formal \
  --stage propose_bugfix \
  --target gigamax \
  --max-repair-rounds 3
```

### Run the public 50-target benchmark list

The public benchmark selection is recorded in
`log/vis_chisel_formal/main/selected_benchmarks.txt`. To run the same target
set with the full workflow:

```bash
TARGETS="$(paste -sd, log/vis_chisel_formal/main/selected_benchmarks.txt)"
.venv/bin/python main.py formal --full --targets "$TARGETS"
```

To run one stage over the same target set:

```bash
TARGETS="$(paste -sd, log/vis_chisel_formal/main/selected_benchmarks.txt)"
.venv/bin/python main.py formal \
  --stage write_assertions \
  --targets "$TARGETS"
```

The four public ablation directories use the same 50-target benchmark list:

```text
log/vis_chisel_formal/ablation/no_assertion_presence_gate
log/vis_chisel_formal/ablation/no_causal_prior
log/vis_chisel_formal/ablation/no_repeated_waveform_guard
log/vis_chisel_formal/ablation/no_waveform_notebook
```

These ablation directories store the paper-facing artifacts for the four
control-removal variants. The quality records in
`log/vis_chisel_formal/quality-ablation/<variant>/reports/*/quality_record.json`
are the selected best-of-five records used in the paper.

### JasperGold quality evaluation

Run the deterministic quality evaluator on the checked-in counter smoke case:

```bash
.venv/bin/python main.py quality --counter --stages build,assertions
```

Run all quality dimensions on the counter smoke case:

```bash
.venv/bin/python main.py quality \
  --counter \
  --all \
  --max-mutants 1
```

Evaluate a custom emitted SystemVerilog candidate:

```bash
.venv/bin/python main.py quality \
  --case-id my_case \
  --candidate-id run_001 \
  --workdir verilog/extra_bench/my_case \
  --dut-sv TestTop.sv \
  --extra-sv ResetCounter.sv \
  --top MyTop \
  --clock clock \
  --reset reset \
  --expected-inputs clock,reset \
  --expected-outputs io_out \
  --trace-signals io_out \
  --stages build,assertions,assumptions,non_vacuity,mutation,repair_regression,sec,xprop \
  --max-mutants 3 \
  --jg-timeout 240
```

The output record is written under:

```text
reports/jg/<case-id>/quality_record.json
```

### Verilog-to-Chisel conversion

Place Verilog inputs under `verilog2chisel/verilog/<target>/`:

```bash
mkdir -p verilog2chisel/verilog/mydesign
cp my_design.v verilog2chisel/verilog/mydesign/
```

Convert with compile-error feedback:

```bash
.venv/bin/python main.py v2c \
  --target mydesign \
  --max-iterations 5
```

Successful conversion writes:

```text
verilog2chisel/chisel/mydesign/
verilog2chisel/generated/mydesign/
chisel/extra_bench/mydesign/
```

The converted target can then be checked by the formal workflow:

```bash
.venv/bin/python main.py formal --full --target mydesign
```

## Public Experiment Data

The public experiment data is intentionally compact and paper-facing:

| Directory | Contents |
|---|---|
| `log/vis_chisel_formal/main` | Main-run benchmark list, configuration, and five-stage artifacts |
| `log/vis_chisel_formal/ablation` | Four ablation benchmark lists, configurations, and five-stage artifacts |
| `log/vis_chisel_formal/quality-main` | Best-of-five quality records for the full system |
| `log/vis_chisel_formal/quality-ablation` | Best-of-five quality records for the four ablations |

The main benchmark suite has 50 targets from 45 families. Each target has one
Scala source file; the average size is 77.68 LOC, the median is 61.5 LOC, and
the range is 19-222 LOC.

### Main result

The main quality table is computed from:

```text
log/vis_chisel_formal/quality-main/reports/*/quality_record.json
```

| Metric | Result |
|---|---:|
| Quality records | 50 / 50 |
| Average overall score | 0.7310 |
| Median overall score | 0.7773 |
| Mean build score | 1.0000 |
| Mean assertion score | 0.9674 |
| Mean bug-detection score | 0.1705 |
| Mean mutation score | 0.8067 |
| Mean repair score | 0.6893 |
| Mean non-vacuity rate | 0.9764 |
| Mean SEC proven rate | 1.0000 |
| Mean X-prop non-X rate | 0.9543 |
| Generated assertions | 531 |
| Proven assertions | 455 |
| Assertions with counterexamples | 65 |
| Benchmarks with at least one counterexample | 21 / 50 |
| Benchmarks with no assertion counterexamples | 29 / 50 |
| Repair-regression target assertions | 167 across 40 benchmarks |
| Repair-target benchmarks with persistent CEX | 21 / 40 |
| Repair-target benchmarks without persistent CEX | 19 / 40 |

The overall score in each quality record is:

```text
overall = 0.15 * build_score
        + 0.25 * assertion_score
        + 0.20 * bug_detection_score
        + 0.25 * mutation_score
        + 0.15 * repair_score
```

Non-vacuity, sequential equivalence, and X-propagation are reported separately
because they expose distinct artifact-quality risks.

### Ablation result

The ablation table is computed from:

```text
log/vis_chisel_formal/quality-ablation/<variant>/reports/*/quality_record.json
```

| Variant | Records | Avg. overall | Relative overall | Assertions | Bug detection | Mutation | Repair | Non-vacuity |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| Full system | 50 / 50 | 0.7310 | 100.00% | 531 | 0.1705 | 0.8067 | 0.6893 | 0.9764 |
| Without emitted-property validation | 50 / 50 | 0.6822 | 93.32% | 454 | 0.1789 | 0.7333 | 0.6495 | 0.8200 |
| Without causal analysis | 50 / 50 | 0.6919 | 94.65% | 574 | 0.1448 | 0.7267 | 0.6892 | 0.8800 |
| Without query-progress control | 50 / 50 | 0.7209 | 98.62% | 494 | 0.1204 | 0.8133 | 0.7464 | 0.8400 |
| Without trace-evidence memory | 50 / 50 | 0.6749 | 92.33% | 551 | 0.1327 | 0.7467 | 0.6239 | 0.8800 |

All four ablations have complete 50-target quality records and lower aggregate
quality than the full system under the paper's best-of-five reporting rule.

### Recompute the README summary from public records

The following command recomputes the aggregate scores from the public quality
records only:

```bash
.venv/bin/python - <<'PY'
import json
import statistics
from pathlib import Path

root = Path("log/vis_chisel_formal")

def records(path):
    return [
        json.loads(p.read_text())
        for p in sorted((root / path / "reports").glob("*/quality_record.json"))
    ]

def summarize(path):
    rows = records(path)
    scores = [r["scores"] for r in rows]
    assertions = [r["assertions"] for r in rows]
    return {
        "records": len(rows),
        "avg_overall": sum(s["overall"] for s in scores) / len(scores),
        "median_overall": statistics.median(s["overall"] for s in scores),
        "assertions": sum(a["count"] for a in assertions),
        "proven": sum(a["proven"] for a in assertions),
        "cex": sum(a["cex"] for a in assertions),
    }

for name, path in [
    ("main", "quality-main"),
    ("no_assertion_presence_gate", "quality-ablation/no_assertion_presence_gate"),
    ("no_causal_prior", "quality-ablation/no_causal_prior"),
    ("no_repeated_waveform_guard", "quality-ablation/no_repeated_waveform_guard"),
    ("no_waveform_notebook", "quality-ablation/no_waveform_notebook"),
]:
    print(name, summarize(path))
PY
```

## Cleaning Generated State

Remove generated runtime artifacts:

```bash
bash scripts/reset_data.sh
```

This deletes `log/`. Do not run it if you need to preserve the public
experiment artifacts in this checkout.
