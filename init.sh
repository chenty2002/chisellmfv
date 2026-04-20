#!/usr/bin/env bash
# ChiselLMFV -- one-shot environment bootstrap.
#
# This script is self-contained and does NOT rely on conda. Everything lands
# in the project-local `.venv/` managed by `uv`. Every step is idempotent.
#
# Installation strategy:
#   * Python deps (incl. pylibfst, hdlConvertor) are installed from PyPI
#     wheels. We do NOT recursively pull the inner submodules needed for
#     building those native extensions from source — they are large.
#   * If a PyPI wheel is unavailable for your platform, you can fall back
#     to source builds:
#       - pylibfst      : see `chisel/pylibfst-cache/README.md`
#                         (`cd chisel/pylibfst-cache && make install`)
#       - hdlConvertor  : run `bash scripts/install_hdlConvertor.sh` (a
#                         thin wrapper that materialises, overwrites and
#                         runs `VerilogCausalAnalysis/install_hdlConvertor.sh`
#                         — this writes the installer into the submodule
#                         worktree before dispatching to it).
#
# Usage: bash init.sh [--skip-causal]
#
#   --skip-causal   Skip installing `hdlConvertor` / VerilogCausalAnalysis deps.

set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$PROJECT_ROOT"

SKIP_CAUSAL=0
for arg in "$@"; do
    case "$arg" in
        --skip-causal) SKIP_CAUSAL=1 ;;
        -h|--help)
            sed -n '1,20p' "$0"; exit 0 ;;
        *) ;;
    esac
done

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------
log()  { printf '\033[1;34m==>\033[0m %s\n' "$*"; }
info() { printf '\033[0;36m[info]\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m[warn]\033[0m %s\n' "$*" >&2; }
die()  { printf '\033[1;31m[error]\033[0m %s\n' "$*" >&2; exit 1; }

# Report what's available on the host so users see upfront what they need.
preflight_check() {
    local name path
    for name in git uv java sbt jg; do
        if command -v "$name" >/dev/null 2>&1; then
            path="$(command -v "$name")"
            # `java` on macOS ships as a shim that forwards to a real JRE; it
            # exists even when no JDK is installed. Probe it for real.
            if [[ "$name" == "java" ]] && ! java -version >/dev/null 2>&1; then
                warn "  java shim present at ${path} but NO JRE/JDK is installed."
                warn "      (only needed if you later need to build hdlConvertor from source)"
                continue
            fi
            info "  found ${name}: ${path}"
        else
            case "$name" in
                git|uv)
                    die "'${name}' is required. Install it and re-run."
                    ;;
                java|sbt)
                    warn "  ${name}: not on PATH -- required for Chisel compilation (sbt) and for the hdlConvertor source-install fallback."
                    warn "      macOS  :  brew install openjdk@17 sbt"
                    warn "      Debian :  sudo apt install openjdk-17-jdk sbt"
                    ;;
                jg)
                    warn "  ${name}: not on PATH -- JasperGold is required for 'invoke_verification'."
                    ;;
            esac
        fi
    done
}

# ---------------------------------------------------------------------------
# 0) Preflight — discover what's already on the host.
# ---------------------------------------------------------------------------
log "[0/4] Preflight checks"
preflight_check

# ---------------------------------------------------------------------------
# 1) Git submodules (top-level only — no --recursive).
#
# We deliberately skip the inner submodules used only for source builds
# (hdlConvertor's ANTLR4 grammar tests, etc.). Users who need a source
# build run VerilogCausalAnalysis/install_hdlConvertor.sh which does its
# own recursive init.
# ---------------------------------------------------------------------------
log "[1/4] Initialising git submodules (top-level)"
git submodule update --init

# ---------------------------------------------------------------------------
# 2) uv-managed venv
# ---------------------------------------------------------------------------
log "[2/4] Setting up uv-managed Python environment"
if ! command -v uv >/dev/null 2>&1; then
    die "'uv' is not installed. Install it with:
        curl -LsSf https://astral.sh/uv/install.sh | sh
      (or: pip install uv)"
fi

uv python install 3.11 >/dev/null 2>&1 || true
if [[ ! -d .venv ]]; then
    uv venv --python 3.11 .venv
else
    info "  reusing existing .venv"
fi
# shellcheck disable=SC1091
source .venv/bin/activate
VENV_PY="$PROJECT_ROOT/.venv/bin/python"
export VIRTUAL_ENV="$PROJECT_ROOT/.venv"

# ---------------------------------------------------------------------------
# 3) Install Python dependencies from PyPI.
#
# All runtime deps (including pylibfst) are listed in requirements.txt.
# hdlConvertor is installed on top when --skip-causal is NOT passed.
# ---------------------------------------------------------------------------
log "[3/4] Installing Python dependencies from PyPI"
uv pip install --upgrade pip setuptools wheel
uv pip install -r requirements.txt || die "
Failed to install runtime dependencies from requirements.txt.
If the failure concerns 'pylibfst' (no wheel for your platform), fall back to a
source install:
    cd chisel/pylibfst-cache && make install
"

# Optional smoke test for pylibfst (it is used by the waveform tools).
if "$VENV_PY" -c "import pylibfst" >/dev/null 2>&1; then
    info "  pylibfst import OK"
else
    warn "pylibfst not importable after requirements install."
    warn "Fallback (source build):"
    warn "    cd chisel/pylibfst-cache && make install"
fi

# --- hdlConvertor (causal analysis) ----------------------------------------
if [[ $SKIP_CAUSAL -eq 0 ]]; then
    log "[4/4] Installing hdlConvertor from PyPI (causal analysis backend)"
    if uv pip install "hdlConvertor>=2.3"; then
        if "$VENV_PY" -c "from hdlConvertor import HdlConvertor" >/dev/null 2>&1; then
            info "  hdlConvertor import OK"
        else
            warn "hdlConvertor installed but not importable -- try the source install:"
            warn "    bash scripts/install_hdlConvertor.sh"
        fi
    else
        warn "pip install hdlConvertor failed (no compatible wheel?)."
        warn "Fall back to the source install:"
        warn "    bash scripts/install_hdlConvertor.sh"
    fi

    # Install VerilogCausalAnalysis's own runtime deps (graphviz, pylibfst)
    # from its requirements.txt. These are lightweight and PyPI-resolvable.
    if [[ -f VerilogCausalAnalysis/requirements.txt ]]; then
        uv pip install -r VerilogCausalAnalysis/requirements.txt || \
            warn "VerilogCausalAnalysis/requirements.txt install failed; re-check later."
    fi
else
    log "[4/4] Skipping hdlConvertor install (--skip-causal)"
fi

# ---------------------------------------------------------------------------
# Runtime directories + .env bootstrap
# ---------------------------------------------------------------------------
mkdir -p log log/causal_analysis
mkdir -p chisel/extra_bench
mkdir -p verilog/extra_bench
mkdir -p verilog2chisel/verilog
mkdir -p verilog2chisel/chisel
mkdir -p verilog2chisel/generated

if [[ ! -f .env && -f .env.example ]]; then
    cp .env.example .env
    warn ".env was missing -- created from .env.example. Fill in your API keys before running."
fi

log "Setup complete!"
cat <<EOF

Activate the environment:
    source .venv/bin/activate

Or invoke through uv without activation:
    uv run python main.py formal --help

Configure credentials (if not done yet):
    edit .env  and set CHISELLMFV_LLM_API_KEY=<your-key>

If any of the native extensions (pylibfst / hdlConvertor) failed to install
from PyPI, fall back to source builds:
    chisel/pylibfst-cache      : cd chisel/pylibfst-cache && make install
    VerilogCausalAnalysis      : bash scripts/install_hdlConvertor.sh

EOF
