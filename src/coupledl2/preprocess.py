"""Workspace-local preprocessing for CoupledL2 cases."""

from __future__ import annotations

import re
from pathlib import Path
from typing import Any, Dict, List


AUTO_VERIFY_GENERATED_DIR = "generated"


def preprocess_coupledl2_workspace(case_workspace: Path) -> Dict[str, Any]:
    """Patch copied CoupledL2 sources so ChiselLMFV consumes cleaned Verilog."""
    patched_autoverify = patch_autoverify_outputs(case_workspace)
    return {
        "schema_version": "coupledl2_preprocess.v1",
        "patched_autoverify": patched_autoverify,
        "generated_output_dir": f"workspace/case/Chisel/{AUTO_VERIFY_GENERATED_DIR}",
    }


def patch_autoverify_outputs(case_workspace: Path) -> List[str]:
    """Route AutoVerify post-processed Verilog into Chisel/generated."""
    chisel_dir = case_workspace / "Chisel"
    patched: List[str] = []
    for path in sorted(chisel_dir.rglob("AutoVerify.scala")):
        original = path.read_text(encoding="utf-8", errors="ignore")
        updated = _patch_autoverify_text(original)
        if updated == original:
            continue
        path.write_text(updated, encoding="utf-8")
        patched.append(_rel(path, case_workspace))
    return patched


def _patch_autoverify_text(text: str) -> str:
    updated = re.sub(
        r'val\s+path\s*=\s*"\.\./Verilog"',
        f'val path = "{AUTO_VERIFY_GENERATED_DIR}"',
        text,
    )
    if f'val path = "{AUTO_VERIFY_GENERATED_DIR}"' not in updated or "mkdirGenerated" in updated:
        return updated

    return re.sub(
        r'(?m)^(\s*)val rm = s"rm -f \$\{path\}/\$\{filename\}".!',
        rf'\1val mkdirGenerated = s"mkdir -p ${{path}}".!' "\n" r'\g<0>',
        updated,
        count=1,
    )


def _rel(path: Path, case_workspace: Path) -> str:
    return "workspace/case/" + path.relative_to(case_workspace).as_posix()
