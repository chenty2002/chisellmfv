# ChiseLLMFV

ChiseLLMFV is the open-source artifact for the ICCD paper:

**ChiseLLMFV: Artifact-Grounded Source-Level Formal Verification for Chisel with LLMs**

The project implements an LLM-assisted, artifact-grounded workflow for
source-level formal verification of Chisel designs. The workflow edits and
repairs maintained Chisel source, while deterministic tools check the emitted
SystemVerilog, JasperGold proof outcomes, counterexample traces, causal-analysis
evidence, and quality-audit reports.

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
|-- scripts/                    # setup and experiment runners
|-- src/
|   |-- core/                   # formal workflow, LLM client, JasperGold runners
|   |-- causal_analysis/        # bridge to VerilogCausalAnalysis
|   |-- utils/                  # config, file helpers, runtime utilities
|   `-- verilog2chisel/         # Verilog-to-Chisel workflow
|-- selected_benchmarks.txt     # reproducible 50-target benchmark selection
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
| Harness construction | Emit the intended device under verification | Chisel build output and emitted SystemVerilog |
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

### Reproduce the 50-target benchmark run

The benchmark selection used for the paper is recorded in
`selected_benchmarks.txt`. To run the same target set with the full workflow:

```bash
TARGETS="$(paste -sd, selected_benchmarks.txt)"
.venv/bin/python main.py formal --full --targets "$TARGETS"
```

To run one stage over the same target set:

```bash
TARGETS="$(paste -sd, selected_benchmarks.txt)"
.venv/bin/python main.py formal \
  --stage write_assertions \
  --targets "$TARGETS"
```

## JasperGold Quality Evaluation

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

The quality report is written under:

```text
reports/jg/<case-id>/quality_record.json
```

## Verilog-to-Chisel Conversion

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

## Cleaning Generated State

Remove generated runtime artifacts:

```bash
bash scripts/reset_data.sh
```
