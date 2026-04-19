#!/usr/bin/env bash
#
# Project-tracked wrapper that installs hdlConvertor from source.
#
# The VerilogCausalAnalysis git submodule ships its own installer at
# `VerilogCausalAnalysis/install_hdlConvertor.sh`. Because that file lives
# inside a submodule it is NOT guaranteed to be the project-customised
# version on a fresh clone — the submodule's HEAD may revert it to the
# upstream (conda-flavoured) variant. This wrapper therefore materialises
# the correct, uv-native installer every time, overwrites the submodule
# worktree copy in-place, and then dispatches to it.
#
# Run this script from an activated project venv:
#   source .venv/bin/activate
#   bash scripts/install_hdlConvertor.sh [--force-rebuild]

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
TARGET="${PROJECT_ROOT}/VerilogCausalAnalysis/install_hdlConvertor.sh"

if [[ ! -d "${PROJECT_ROOT}/VerilogCausalAnalysis" ]]; then
    echo "[error] VerilogCausalAnalysis submodule is missing. Run 'git submodule update --init' first." >&2
    exit 1
fi

# Materialise the project-customised installer into the submodule worktree.
# Writing the full script here (not just a copy) keeps the main project in
# full control of the install logic even if the submodule is reset.
cat > "$TARGET" <<'INSTALLER_EOF'
#!/usr/bin/env bash
#
# VerilogCausalAnalysis — source installer for hdlConvertor.
#
# This is the fallback path when `pip install hdlConvertor` is not viable
# (e.g. no compatible wheel for your platform, or you are developing
# hdlConvertor itself). The main project's init.sh does NOT call this
# script by default — invoke it explicitly via:
#
#     source .venv/bin/activate
#     bash scripts/install_hdlConvertor.sh
#
# which writes this file and then runs it.
#
# Flags:
#   --force-rebuild   Rebuild even if hdlConvertor is already importable.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

FORCE_REBUILD=0
for arg in "$@"; do
    case "$arg" in
        --force-rebuild) FORCE_REBUILD=1 ;;
        -h|--help)
            sed -n '1,30p' "$0"; exit 0 ;;
        *) ;;
    esac
done

log()  { printf '\033[1;34m==>\033[0m %s\n' "$*"; }
info() { printf '\033[0;36m[info]\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m[warn]\033[0m %s\n' "$*" >&2; }
die()  { printf '\033[1;31m[error]\033[0m %s\n' "$*" >&2; exit 1; }

# --- Sanity: active venv ---------------------------------------------------
if [[ -z "${VIRTUAL_ENV:-}" ]]; then
    die "No active Python virtualenv detected.
  Activate the project venv first:
      source \"$(cd "$SCRIPT_DIR/.." && pwd)/.venv/bin/activate\""
fi
VENV_PY="${VIRTUAL_ENV}/bin/python"
[[ -x "$VENV_PY" ]] || die "Venv python not found at $VENV_PY"

if command -v uv >/dev/null 2>&1; then
    PIP_INSTALL=(uv pip install)
else
    PIP_INSTALL=("$VENV_PY" -m pip install)
fi

ensure_pypkgs() {
    local to_install=()
    local item import_name pip_spec
    for item in "$@"; do
        if [[ "$item" == *":"* ]]; then
            import_name="${item%%:*}"; pip_spec="${item#*:}"
        else
            pip_spec="$item"
            import_name="${item%%[<>=!~ ]*}"
            import_name="${import_name//-/_}"
        fi
        if "$VENV_PY" -c "import ${import_name}" >/dev/null 2>&1; then
            info "  already present in venv: ${import_name}"
        else
            to_install+=("$pip_spec")
        fi
    done
    if ((${#to_install[@]})); then
        info "  installing: ${to_install[*]}"
        "${PIP_INSTALL[@]}" "${to_install[@]}"
    fi
}

# Patch upstream's universal_fs.h for modern Apple clang / libc++:
# upstream selects <experimental/filesystem> when __GNUC__ < 8, which fires
# wrongly on clang (defines __GNUC__ == 4). Rewrite to use __has_include.
patch_universal_fs_header() {
    local header="${SCRIPT_DIR}/hdlConvertor/include/hdlConvertor/universal_fs.h"
    [[ -f "$header" ]] || { warn "universal_fs.h not found; nothing to patch."; return 0; }
    if grep -q "CHISELLMFV_PATCHED" "$header" 2>/dev/null; then
        info "  universal_fs.h already patched; skipping"; return 0
    fi
    info "  patching hdlConvertor/universal_fs.h for modern toolchains"
    cat > "$header" <<'HDR_EOF'
#pragma once
// CHISELLMFV_PATCHED: portable <filesystem> selection using __has_include.
#include <string>
#if defined(__has_include)
#  if __has_include(<filesystem>)
#    include <filesystem>
#  elif __has_include(<experimental/filesystem>)
#    include <experimental/filesystem>
namespace std { namespace filesystem = experimental::filesystem; }
#  else
#    error "No <filesystem> support found."
#  endif
#else
#  if (defined(__GNUC__) && __GNUC__ < 8)
#    include <experimental/filesystem>
namespace std { namespace filesystem = experimental::filesystem; }
#  else
#    include <filesystem>
#  endif
#endif
extern const std::string STRING_FILENAME;
HDR_EOF
}

# --- 1) Build prerequisites ------------------------------------------------
log "[1/4] Checking build prerequisites"
if ! command -v java >/dev/null 2>&1 || ! java -version >/dev/null 2>&1; then
    die "Java runtime not detected -- hdlConvertor needs JDK 11+ for ANTLR code generation.
  macOS  :  brew install openjdk@17
  Debian :  sudo apt install openjdk-17-jdk"
fi
info "  java:  $(java -version 2>&1 | head -1)"

if ! command -v c++ >/dev/null 2>&1 && ! command -v clang++ >/dev/null 2>&1 && ! command -v g++ >/dev/null 2>&1; then
    die "No C++ compiler found on PATH. Install one (clang / gcc)."
fi

ensure_pypkgs "cython>=3" "pybind11" "mesonpy:meson-python"
for tool in cmake ninja meson; do
    if command -v "$tool" >/dev/null 2>&1; then
        info "  using system ${tool}: $(command -v "$tool")"
    else
        case "$tool" in
            cmake) ensure_pypkgs "cmake" ;;
            ninja) ensure_pypkgs "ninja" ;;
            meson) ensure_pypkgs "meson>=1.2.3,<1.6" ;;
        esac
    fi
done

if [[ -f "${SCRIPT_DIR}/requirements.txt" ]]; then
    info "  installing VerilogCausalAnalysis runtime deps"
    "${PIP_INSTALL[@]}" -r "${SCRIPT_DIR}/requirements.txt"
fi

# --- 2) Fetch hdlConvertor source submodule -------------------------------
log "[2/4] Fetching hdlConvertor sources (recursive submodule update)"
git submodule update --init --recursive
[[ -d "${SCRIPT_DIR}/hdlConvertor" ]] || die "hdlConvertor directory missing after submodule update."

# --- 3) Patch + build ------------------------------------------------------
log "[3/4] Patching and building hdlConvertor"
if [[ $FORCE_REBUILD -eq 0 ]] && \
   "$VENV_PY" -c "from hdlConvertor import HdlConvertor" >/dev/null 2>&1; then
    info "  hdlConvertor already importable; skipping build (use --force-rebuild to override)"
else
    patch_universal_fs_header
    pushd "${SCRIPT_DIR}/hdlConvertor" >/dev/null
    rm -rf build .mesonpy-* subprojects/antlr4-runtime
    info "  running: pip install --no-build-isolation ."
    if ! "${PIP_INSTALL[@]}" --no-build-isolation .; then
        popd >/dev/null
        die "hdlConvertor build failed. See the output above."
    fi
    popd >/dev/null
fi

# --- 4) Smoke test ---------------------------------------------------------
log "[4/4] Verifying installation"
"$VENV_PY" -c "
import sys, os
sys.path.insert(0, os.path.join('${SCRIPT_DIR}', 'src'))
from hdlConvertor import HdlConvertor
import verilog_causal_analysis
print('hdlConvertor and verilog_causal_analysis import OK')
"

log "hdlConvertor source install complete."
INSTALLER_EOF

chmod +x "$TARGET"

echo "[info] Wrote ${TARGET}"
echo "[info] Dispatching to it..."
exec bash "$TARGET" "$@"
