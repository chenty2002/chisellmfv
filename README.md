# ChiseLLMFV

ChiseLLMFV is an LLM-assisted formal-verification workflow for Chisel
hardware. The current checkout is centered on the refactored CoupledL2 flow:
it creates an isolated run workspace, gives the model only workspace-scoped
tools, records every stage with machine-readable artifacts, and delegates
build/proof checks to deterministic EDA commands.

The repository also keeps the earlier VIS-style benchmark workflow, the
JasperGold quality evaluator, and the Verilog-to-Chisel converter. Treat
`python main.py run ...` as the primary entry point for new CoupledL2 work.

## What This Version Provides

- Preflight-gated four-stage formal workflow:
  `write_assertions -> invoke_verification -> waveform_explanation -> propose_bugfix`.
- CoupledL2 run isolation under `runs/<timestamp>-<case>-<id>/`.
- Stage-local context files, skills, rules, source snapshots, tool operation
  ledgers, stage results, handoff files, and run cost summaries.
- Workspace-scoped model tools: source reads, bounded `rg`, focused edits,
  deterministic build/proof calls, waveform queries, causal-analysis queries,
  and explicit `complete_stage` completion.
- Dual-model routing through `CHISELLMFV_LLM_MODEL_PRO` and
  `CHISELLMFV_LLM_MODEL_FLASH`, with `CHISELLMFV_LLM_MODEL` as a fallback.

## Repository Layout

```text
.
|-- main.py                         # CLI: run, formal, quality, v2c
|-- init.sh                         # uv-based environment bootstrap
|-- pyproject.toml
|-- requirements.txt
|-- .env.example
|-- optimization.md                 # CoupledL2 optimization plan
|-- refactor.md                     # CoupledL2 refactor plan
|-- src/
|   |-- coupledl2/                  # CoupledL2 config, workspace, indexing, backend
|   |-- core/                       # workflow loop, tools, records, prompts, LLM routing
|   |-- causal_analysis/            # bridge to VerilogCausalAnalysis
|   |-- utils/
|   `-- verilog2chisel/
|-- src/coupledl2/context_assets/
|   |-- skills/                     # stage-specific guidance installed into each run
|   `-- rules/
|-- tests/                          # repo-owned regression tests for the refactor
|-- CoupledL2-Verification/         # expected local source for CoupledL2 cases
|-- VerilogCausalAnalysis/          # waveform/causal-analysis backend
|-- chisel/                         # legacy Chisel benchmark workspace
|-- verilog/                        # legacy JasperGold workspace
|-- verilog2chisel/                 # Verilog-to-Chisel workspace
`-- log/vis_chisel_formal/          # legacy public experiment artifacts, if present
```

## Requirements

The Python package requires Python 3.10 or newer. The bootstrap script creates
a project-local `.venv` with Python 3.11 when possible.

For the full CoupledL2 flow, install or expose these tools on `PATH`:

- `git`
- `uv`
- Java and `sbt`
- CoupledL2's Scala/Mill build toolchain, as required by the selected case
- Cadence JasperGold as `jg`
- GTKWave utilities, especially `vcd2fst`, when counterexample traces are used
- optional: Graphviz and `hdlConvertor` for causal analysis

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

Optional LLM settings:

```bash
CHISELLMFV_LLM_BASE_URL=<optional-api-base-url>
CHISELLMFV_LLM_MODEL=<single-model-fallback>
CHISELLMFV_LLM_MODEL_PRO=<main-agent-model>
CHISELLMFV_LLM_MODEL_FLASH=<helper-model>
CHISELLMFV_LLM_EXTRA_BODY=<optional-json-extra-body>
```

When `CHISELLMFV_LLM_MODEL_PRO` and `CHISELLMFV_LLM_MODEL_FLASH` are set, PRO
is used for stage agent loops, source edits, completion decisions, diagnosis,
and repairs. FLASH is used for lower-risk helper work such as compaction,
retrieval assistance, summaries, lint-style checks, and failure
preclassification.

If native Python wheels are unavailable on your platform:

```bash
source .venv/bin/activate

cd chisel/pylibfst-cache
make install
cd -

bash scripts/install_hdlConvertor.sh
```

## CoupledL2 Workflow

### Case Layout

`main.py run` expects a CoupledL2 case directory. The case should contain at
least:

```text
<case>/
|-- Chisel/
|   |-- Makefile
|   `-- src/test/scala/coupledl2/VerifyTop.scala
`-- Verilog/
    `-- setup.sh
```

The workflow copies the case into a new run directory. The original case is
not edited by the model-facing tools.

### Preflight A New Run

Preflight copies and cleans the case, generates indexes, performs the baseline
build, and rejects residual source or generated assertions before any LLM call:

```bash
.venv/bin/python main.py run \
  --case CoupledL2-Verification/<case-name> \
  --property deadlock \
  --preflight-only
```

Useful options:

```text
--mode small
--input-mode coupledl2asl1
--property deadlock|write_read|copy_equality|peer_l2|custom
--run-root runs
```

The current checked-in configuration accepts `small` mode and
`coupledl2asl1` input mode.

### Run A New Workflow

```bash
.venv/bin/python main.py run \
  --case CoupledL2-Verification/<case-name> \
  --property deadlock \
  --full \
  --max-tokens 160000 \
  --max-repair-rounds 3
```

Fresh runs can execute only `write_assertions` as a single stage; later stages
must resume a run whose predecessor handoff succeeded:

```bash
.venv/bin/python main.py run \
  --case CoupledL2-Verification/<case-name> \
  --property deadlock \
  --stage write_assertions \
  --max-tokens 160000
```

If `invoke_verification` proves all assertions, the workflow stops without
running counterexample diagnosis or repair.

### Resume A Verified Handoff

Resume uses the existing workspace and validates its hash plus the predecessor
handoff before starting the requested stage:

```bash
.venv/bin/python main.py run \
  --resume-run runs/<timestamp>-<case-name>-<id> \
  --stage invoke_verification \
  --max-tokens 160000
```

Valid stages are `write_assertions`, `invoke_verification`,
`waveform_explanation`, and `propose_bugfix`. `waveform_explanation` additionally
requires a Stage 3 counterexample path.

## CoupledL2 Run Artifacts

Each run is written under:

```text
runs/<timestamp>-<case-name>-<id>/
```

Important files:

```text
manifest.json
indexes/project_tree.json
indexes/build_contract.json
indexes/formal_surface.json
logs/events.jsonl
results/preflight/preflight_result.json
results/preflight/baseline_build_result.json
results/run_cost_summary.json
results/final_result.json
results/by_stage/02_write_assertions/
results/by_stage/03_invoke_verification/
results/by_stage/04_waveform_explanation/
results/by_stage/05_propose_bugfix/
```

Each stage directory may contain:

```text
stage_inputs.json              # stable input contract for the stage
operations.jsonl               # model tool operations and edit evidence
stage_events.jsonl             # machine-readable stage events
stage_result.json              # normalized stage result
handoff.json                   # compact downstream-stage contract
source_snapshot/               # lazy source snapshots for edit stages
snapshot_manifest_before.json
snapshot_manifest_after.json
```

Stage-specific artifacts include:

| Stage | Typical artifacts |
|---|---|
| `write_assertions` | assertion scan, assertion map/plan, source snapshots |
| `invoke_verification` | `verify.tcl`, `formal_result.json`, `property_status.json`, traces |
| `waveform_explanation` | causal/waveform evidence, `diagnosis.json`, `counterexample_analysis.md` |
| `propose_bugfix` | repair result, repair history, final result |

Downstream stages read earlier `handoff.json` files through
`stage_inputs.json`; successful edit stages refresh the indexes and bind the
workspace/index hashes into their handoffs. `run_cost_summary.json` aggregates
PRO, FLASH, context compaction, token budget, and stage tool-budget use.

## Legacy Formal Workflow

The `formal` command remains available for the VIS-style Chisel benchmark
workspace under `chisel/extra_bench` and `benchmark/vis-chisel`.

Run the full workflow for one target:

```bash
.venv/bin/python main.py formal --full --target gigamax
```

Run one stage:

```bash
.venv/bin/python main.py formal \
  --stage write_assertions \
  --target gigamax
```

Run a comma-separated target list:

```bash
.venv/bin/python main.py formal \
  --full \
  --targets gigamax,s1269,arbiter_arbiter_le
```

Run counterexample diagnosis from an existing trace:

```bash
.venv/bin/python main.py formal \
  --stage waveform_explanation \
  --target gigamax \
  --waveform chisel/extra_bench/gigamax/generated/<counterexample>.fst
```

## JasperGold Quality Evaluation

Run the checked-in counter smoke configuration:

```bash
.venv/bin/python main.py quality --counter --stages build,assertions
```

Run all quality dimensions on the smoke case:

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

Quality records are written under:

```text
reports/jg/<case-id>/quality_record.json
```

## Verilog-To-Chisel Conversion

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

The converted target can then be checked through the legacy formal workflow:

```bash
.venv/bin/python main.py formal --full --target mydesign
```

## Development Checks

For repository-level Python checks, prefer the repo-owned tests:

```bash
pytest -q tests
```

For focused CoupledL2 checks:

```bash
pytest -q \
  tests/test_coupledl2_initialization.py \
  tests/test_coupledl2_backend.py \
  tests/test_coupledl2_commit3_context_tools.py \
  tests/test_coupledl2_commit4_acceptance.py \
  tests/test_coupledl2_runner.py \
  tests/test_optimization_p0.py \
  tests/test_optimization_p1.py \
  tests/test_optimization_p2.py
```

Syntax-check edited Python modules with:

```bash
python -m py_compile main.py src/core/*.py src/coupledl2/*.py
```

Avoid bare repo-root `pytest` if this checkout contains unrelated external
trees or submodules that are not part of the current feature signal.

## Cleaning Generated State

Remove generated runtime artifacts:

```bash
bash scripts/reset_data.sh
```

This deletes `log/`. Do not run it if you need to preserve checked-in public
experiment artifacts in this checkout.
