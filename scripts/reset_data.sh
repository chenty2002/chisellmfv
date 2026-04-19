#!/usr/bin/env bash
# Remove runtime artefacts produced by the workflow:
#  - log files
#  - causal-analysis outputs
# Submodules and benchmark sources are preserved.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"

cd "$PROJECT_ROOT"

rm -rf log
mkdir -p log log/causal_analysis

echo "Cleared log/ (re-created empty)."
