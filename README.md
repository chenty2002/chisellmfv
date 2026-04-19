# ChiselLMFV

**LLM-driven five-stage formal verification workflow for Chisel hardware designs.**

ChiselLMFV is a lightweight, modular tool that orchestrates a Large Language
Model through a fixed five-stage formal verification pipeline on Chisel
designs. Counterexamples are root-caused with the help of an independent
Verilog causal-analysis engine, and the whole flow can also ingest plain
Verilog by first converting it to Chisel.

---

## Features

- **Five-stage formal verification workflow** with tool-use agent loop
  1. `build_top_module` — generate / confirm `VerilogGenerator` entry
  2. `write_assertions` — inject ChiselFV / Chisel-LTL assertions
  3. `invoke_verification` — `make verilog` + JasperGold `prove -all`
  4. `waveform_explanation` — analyse FST counterexample; receives a **prior
     causal-analysis report** from `VerilogCausalAnalysis` as auxiliary evidence
  5. `propose_bugfix` — emit a minimal, compilable fix
- **Verilog → Chisel conversion workflow** driven by compile-error feedback
- **Benchmarks out-of-the-box**
  - `RTLLM`, `verilog-eval` — registered as git submodules
- **Chisel build stack as submodules** — `chiselfv`, `pylibfst-cache`
- **Causal root-cause analysis** via the `VerilogCausalAnalysis` submodule,
  automatically invoked during the `waveform_explanation` stage

---

## Project layout

```
chisellmfv/
├── benchmark/
│   ├── RTLLM/                     ← submodule
│   └── verilog-eval/              ← submodule
├── chisel/
│   ├── build.sbt                  ← top-level sbt (aggregates chiselfv)
│   ├── Makefile
│   ├── chiselfv/                  ← submodule (ChiselFV assertion library)
│   ├── pylibfst-cache/            ← submodule (FST waveform reader)
│   └── extra_bench/
│       ├── build.sbt              ← per-benchmark sbt config
│       ├── Makefile
│       └── <benchmark>/           ← generated at runtime (.gitignored)
├── verilog/
│   ├── ClockGate.v, LogPerfHelper.v, ResetCounter.sv, ...
│   ├── setup.sh                   ← JasperGold driver
│   ├── set_testtop.py             ← post-process emitted SystemVerilog
│   └── extra_bench/<benchmark>/   ← generated at runtime
├── verilog2chisel/
│   ├── build.sbt                  ← sbt for LLM-emitted Chisel
│   ├── Makefile
│   ├── verilog/<benchmark>/       ← drop input Verilog here
│   └── chisel/<benchmark>/        ← LLM output lands here
├── VerilogCausalAnalysis/         ← submodule (causal-DAG root-cause engine)
├── src/
│   ├── core/                      ← formal-verification workflow
│   ├── causal_analysis/           ← adapter to VerilogCausalAnalysis
│   ├── utils/
│   └── verilog2chisel/
├── scripts/
│   ├── install_hdlConvertor.sh    ← source-install fallback for hdlConvertor
│   └── reset_data.sh
├── main.py                        ← unified CLI entry point
├── init.sh                        ← environment bootstrap (uv-based)
├── requirements.txt
├── pyproject.toml                 ← uv project manifest
├── .env.example                   ← template for API keys (copy → .env)
├── LICENSE                        ← MIT
└── README.md
```

---

## Prerequisites

Required at runtime (install via your OS package manager, **not** through uv):

- **Git** with submodule support
- **Java 11+ (JDK)** — used by Chisel/sbt. *Also* required if you need to
  build `hdlConvertor` from source (see the fallback section below).
  - macOS : `brew install openjdk@17`
  - Debian: `sudo apt install openjdk-17-jdk`
- **sbt** — Scala build tool, for Chisel compilation
- **JasperGold** (Cadence), accessible as `jg` in `$PATH`, for the
  `invoke_verification` stage
- **GTKWave suite** providing `vcd2fst` (used by the JasperGold runner)
- **[uv](https://docs.astral.sh/uv/)** for Python environment management
  - `curl -LsSf https://astral.sh/uv/install.sh | sh`

**Optional** (only needed for *source* builds of the native Python
extensions — the default pip-wheel path requires none of these):

- **make**, **cmake (≥ 3.20)**, **ninja**, **meson**, **C/C++ compiler**

`init.sh` starts with a **preflight step** that probes each of the above
and reports what's missing before any installation is attempted.

---

## Quick start

```bash
# 1. Clone + initialise
git clone <this-repo> chisellmfv
cd chisellmfv
bash init.sh          # submodules + uv venv + PyPI deps (pip-first)

# 2. Activate venv (optional — `uv run` also works without activation)
source .venv/bin/activate

# 3. Configure LLM credentials
#    Secrets live in a project-root `.env` file (git-ignored).
#    `init.sh` creates one from `.env.example` on first run; edit it and
#    set CHISELLMFV_LLM_API_KEY (and optionally embedding/reranker keys).
#    Alternatively, export the variables in your shell:
#
#      export CHISELLMFV_LLM_API_KEY=sk-xxxxxx
#
#    See `.env.example` for all supported variables (incl. optional URL /
#    model overrides like CHISELLMFV_LLM_URL, CHISELLMFV_LLM_MODEL).

# 4. Run the full five-stage formal verification workflow
python main.py formal --full --chisel-dir chisel/ --target <benchmark_name>
```

### Installation policy

`init.sh` uses a **pip-first, source-fallback** strategy:

1. **Submodules** are initialised only one level deep (`git submodule update --init`).
   Inner submodules used *only* for source builds (e.g. hdlConvertor's large
   ANTLR4 test corpora) are **not** fetched by default.
2. **Python packages** — including the two native extensions `pylibfst`
   (FST waveform reader) and `hdlConvertor` (causal-analysis backend) — are
   installed from PyPI wheels via `uv pip install -r requirements.txt`
   (+ `hdlConvertor>=2.3` for the optional causal extra).
3. If a wheel is unavailable for your platform and the pip install fails,
   `init.sh` emits a **clear warning** pointing at the source-install
   fallback; it does not abort.

#### Source-install fallbacks (opt-in)

Only run these if the default pip path failed or you are developing the
respective library:

```bash
# Activate the project venv first:
source .venv/bin/activate

# Fallback 1: build pylibfst from the vendored submodule
cd chisel/pylibfst-cache && make install && cd -

# Fallback 2: build hdlConvertor from source
#   - fetches hdlConvertor's inner submodules
#   - applies a portable <filesystem> header patch for modern Apple clang
#   - builds with meson + ninja
#
# This wrapper is tracked by the main project; it materialises the
# installer at VerilogCausalAnalysis/install_hdlConvertor.sh (inside the
# submodule worktree) and dispatches to it.
bash scripts/install_hdlConvertor.sh
```

Both source-install paths require **Java 11+**, a **C/C++ compiler**, and
the **cmake / ninja / meson** CLI tools on `$PATH`.

### Typical invocations

```bash
# Full workflow from scratch
python main.py formal --full --target gigamax

# Resume from a specific stage (e.g., after manually tweaking assertions)
python main.py formal --full --start-stage write_assertions --target gigamax

# Single stage
python main.py formal --stage build_top_module   --target gigamax
python main.py formal --stage write_assertions   --target gigamax
python main.py formal --stage invoke_verification --target gigamax

# Waveform analysis with causal-analysis prior (requires FST counterexample)
python main.py formal --stage waveform_explanation \
    --waveform chisel/extra_bench/gigamax/generated/Assertion_X.fst \
    --target gigamax

# Bug-fix proposal driven by the waveform_explanation report
python main.py formal --stage propose_bugfix --target gigamax

# Budget the total tokens across all API calls
python main.py formal --full --target gigamax --max-tokens 20000000
```

### Verilog → Chisel conversion

```bash
# 1. Drop source files into verilog2chisel/verilog/<benchmark>/
cp my_design.v verilog2chisel/verilog/mydesign/

# 2. Convert (with up to 5 compile-error feedback iterations)
python main.py v2c --target mydesign

# 3. Output:
#    - Chisel source : verilog2chisel/chisel/mydesign/*.scala
#    - Emitted Verilog: verilog2chisel/generated/mydesign/*.v
#    - Auto-mirrored to chisel/extra_bench/mydesign/ on success,
#      ready for the formal workflow above.
```

---

## Five-stage workflow details

| Stage | Purpose | LLM tools | Output |
|-------|---------|-----------|--------|
| 1. build_top_module | Generate / confirm `object VerilogGenerator` | `confirm_existing_harness`, `write_file`, `read_files`, `reset_stage` | Compilable harness |
| 2. write_assertions | Add safety/liveness properties | `write_assertions`, `read_files` | Source with assertions |
| 3. invoke_verification | `make verilog` → JasperGold `prove -all -bg` | *(automatic, no LLM)* | FST counterexamples |
| 4. waveform_explanation | Trace FST + **consult causal-analysis report** + produce markdown analysis | 6× `waveform_*`, `read_files`, `write_report` | `counterexample_analysis.md` |
| 5. propose_bugfix | Apply minimal patch | `write_fix`, `read_files` | Fixed Chisel source + `bugfix_report.md` |

### Causal analysis integration

When the `waveform_explanation` stage starts, the workflow automatically
invokes `VerilogCausalAnalysis/analyze.py` (as a subprocess) on the
counterexample FST plus the per-benchmark Verilog sources. The resulting
causal-DAG summary and candidate root-cause list are injected into the LLM
user prompt as **prior evidence**. The LLM is instructed to verify — not
blindly trust — that evidence against the waveform and source code.

Causal-analysis artefacts are persisted under:

```
log/causal_analysis/<benchmark>/
├── causal_graph.json
├── causal_graph.dot
└── causal_graph.png      # or .svg / .pdf
```

---

## Environment reset

```bash
bash scripts/reset_data.sh        # clears logs / analysis artefacts
```

---

## License

MIT — see [`LICENSE`](./LICENSE).
