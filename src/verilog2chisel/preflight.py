"""Preflight and artifact setup for Verilog2Chisel v2."""

import hashlib
import json
import re
from datetime import datetime
from pathlib import Path
from typing import Any, Dict, List, Optional


class V2CPreflight:
    """Scan one VIS Verilog target and write deterministic run artifacts."""

    def __init__(
        self,
        workspace_dir: str,
        target: str,
        max_iterations: int,
        run_dir: Optional[str] = None,
    ) -> None:
        self.workspace_dir = Path(workspace_dir).resolve()
        self.target = target
        self.verilog2chisel_dir = self.workspace_dir / "verilog2chisel"
        self.input_dir = self.verilog2chisel_dir / "verilog" / target
        self.chisel_dir = self.verilog2chisel_dir / "chisel" / target
        self.generated_dir = self.verilog2chisel_dir / "generated" / target
        self.publish_dir = self.workspace_dir / "chisel" / "extra_bench" / target
        self.run_dir = Path(run_dir).resolve() if run_dir else self._new_run_dir()
        self.max_iterations = max_iterations

    def run(self) -> Dict[str, Any]:
        self.run_dir.mkdir(parents=True, exist_ok=True)
        self.chisel_dir.mkdir(parents=True, exist_ok=True)
        self.generated_dir.mkdir(parents=True, exist_ok=True)

        if not self.input_dir.is_dir():
            result = {
                "schema_version": "v2c_preflight_result.v1",
                "target": self.target,
                "success": False,
                "error_kind": "missing_input_dir",
                "input_dir": self._rel(self.input_dir),
            }
            self._write_json("preflight_result.json", result)
            return result

        verilog_paths = sorted(
            path for path in self.input_dir.iterdir() if path.suffix in {".v", ".sv"}
        )
        excluded = sorted(
            path.name for path in self.input_dir.iterdir() if path.name not in {p.name for p in verilog_paths}
        )

        if len(verilog_paths) != 1:
            result = {
                "schema_version": "v2c_preflight_result.v1",
                "target": self.target,
                "success": False,
                "error_kind": "unsupported_multiple_input_files"
                if len(verilog_paths) > 1
                else "missing_verilog_input",
                "files": [path.name for path in verilog_paths],
                "excluded_files": excluded,
            }
            self._write_json("preflight_result.json", result)
            self._write_manifest(input_hash=None)
            return result

        source_path = verilog_paths[0]
        text = source_path.read_text(encoding="utf-8", errors="replace")
        file_summary = self._summarize_source(source_path, text)
        input_hash = "sha256:" + file_summary["sha256"]
        manifest = self._write_manifest(input_hash=input_hash)
        input_summary = {
            "schema_version": "v2c_input_summary.v1",
            "target": self.target,
            "files": [file_summary],
            "excluded_files": excluded,
        }
        self._write_json("input_summary.json", input_summary)

        prompt_bundle = self.build_prompt_bundle(input_summary)
        self._write_json("prompt_bundle.json", prompt_bundle)

        result = {
            "schema_version": "v2c_preflight_result.v1",
            "target": self.target,
            "success": True,
            "files": input_summary["files"],
            "excluded_files": excluded,
            "manifest": manifest,
            "run_dir": str(self.run_dir),
            "input_hash": input_hash,
        }
        self._write_json("preflight_result.json", result)
        return result

    def build_prompt_bundle(self, input_summary: Dict[str, Any]) -> Dict[str, Any]:
        source_files = [
            str(Path("verilog2chisel") / "verilog" / self.target / item["path"])
            for item in input_summary.get("files", [])
        ]
        excluded_inputs = [
            str(Path("verilog2chisel") / "verilog" / self.target / name)
            for name in input_summary.get("excluded_files", [])
        ]
        return {
            "schema_version": "v2c_prompt_bundle.v1",
            "visible_inputs": [
                "src/verilog2chisel/context_assets/vis_conversion_rules.md",
                "input_summary.json",
                *source_files,
            ],
            "excluded_inputs": excluded_inputs,
            "benchmark_specific_rules": False,
        }

    def _new_run_dir(self) -> Path:
        stamp = datetime.now().strftime("%Y-%m-%dT%H-%M-%S")
        return self.verilog2chisel_dir / "runs" / f"{stamp}-{self.target}"

    def _summarize_source(self, path: Path, text: str) -> Dict[str, Any]:
        logic_text = self._strip_verilog_comments(text)
        return {
            "path": path.name,
            "sha256": hashlib.sha256(text.encode("utf-8")).hexdigest(),
            "line_count": len(text.splitlines()),
            "modules": re.findall(r"\bmodule\s+([A-Za-z_][A-Za-z0-9_$]*)", logic_text),
            "typedef_enums": re.findall(
                r"\btypedef\s+enum(?:\s+\w+)?\s*\{[^}]*\}\s*([A-Za-z_][A-Za-z0-9_$]*)\s*;",
                logic_text,
                flags=re.DOTALL,
            ),
            "nd_call_count": len(re.findall(r"\$ND\s*\(", logic_text)),
            "has_initial_blocks": bool(re.search(r"\binitial\b", logic_text)),
            "array_ranges": sorted(set(re.findall(r"\[[^\]\n]+\]", logic_text))),
        }

    def _strip_verilog_comments(self, text: str) -> str:
        without_block = re.sub(r"/\*.*?\*/", "", text, flags=re.DOTALL)
        return re.sub(r"//.*", "", without_block)

    def _write_manifest(self, input_hash: Optional[str]) -> Dict[str, Any]:
        manifest = {
            "schema_version": "v2c_manifest.v1",
            "target": self.target,
            "run_dir": str(self.run_dir),
            "input_dir": self._rel(self.input_dir),
            "chisel_dir": self._rel(self.chisel_dir),
            "generated_dir": self._rel(self.generated_dir),
            "publish_dir": self._rel(self.publish_dir),
            "max_iterations": self.max_iterations,
            "input_hash": input_hash,
        }
        self._write_json("manifest.json", manifest)
        return manifest

    def _write_json(self, filename: str, data: Dict[str, Any]) -> None:
        (self.run_dir / filename).write_text(
            json.dumps(data, indent=2, ensure_ascii=False) + "\n",
            encoding="utf-8",
        )

    def _rel(self, path: Path) -> str:
        try:
            return str(path.resolve().relative_to(self.workspace_dir))
        except ValueError:
            return str(path)
