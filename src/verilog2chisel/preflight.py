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
        module_spans = self._module_spans(logic_text)
        initial_assignments = self._initial_assignments(logic_text, module_spans)
        nd_occurrences = self._nd_occurrences(logic_text, module_spans)
        clocked_always = self._clocked_always(logic_text, module_spans)
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
            "nd_call_count": len(nd_occurrences),
            "nd_occurrences": nd_occurrences,
            "has_initial_blocks": bool(initial_assignments or re.search(r"\binitial\b", logic_text)),
            "initial_assignments": initial_assignments,
            "clocked_always": clocked_always,
            "array_ranges": sorted(set(re.findall(r"\[[^\]\n]+\]", logic_text))),
        }

    def _strip_verilog_comments(self, text: str) -> str:
        without_block = re.sub(
            r"/\*.*?\*/",
            lambda match: "\n" * match.group(0).count("\n"),
            text,
            flags=re.DOTALL,
        )
        return re.sub(r"//.*", "", without_block)

    def _module_spans(self, text: str) -> List[Dict[str, Any]]:
        starts = list(
            re.finditer(r"\bmodule\s+([A-Za-z_][A-Za-z0-9_$]*)\b", text)
        )
        spans: List[Dict[str, Any]] = []
        for index, match in enumerate(starts):
            next_start = starts[index + 1].start() if index + 1 < len(starts) else len(text)
            end_match = re.search(r"\bendmodule\b", text[match.end():next_start])
            end = match.end() + end_match.end() if end_match else next_start
            spans.append({"name": match.group(1), "start": match.start(), "end": end})
        return spans

    def _module_for_offset(self, offset: int, module_spans: List[Dict[str, Any]]) -> Optional[str]:
        for module in module_spans:
            if module["start"] <= offset < module["end"]:
                return module["name"]
        return None

    def _clocked_always(self, text: str, module_spans: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
        records: List[Dict[str, Any]] = []
        pattern = re.compile(
            r"\balways\s*@\s*\((?P<sensitivity>[^)]*\bposedge\s+(?P<clock>[A-Za-z_][A-Za-z0-9_$]*)[^)]*)\)",
            re.IGNORECASE,
        )
        for match in pattern.finditer(text):
            records.append(
                {
                    "module": self._module_for_offset(match.start(), module_spans),
                    "clock": match.group("clock"),
                    "line": self._line_number(text, match.start()),
                    "sensitivity": " ".join(match.group("sensitivity").split()),
                }
            )
        return records

    def _initial_assignments(self, text: str, module_spans: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
        records: List[Dict[str, Any]] = []
        pattern = re.compile(
            r"\binitial\b(?P<body>\s+begin(?P<block>.*?)\bend\b|(?P<stmt>[^;]+;))",
            re.IGNORECASE | re.DOTALL,
        )
        assign_pattern = re.compile(
            r"(?P<lhs>[A-Za-z_][A-Za-z0-9_$]*(?:\s*\[[^\]]+\])?)\s*=\s*(?P<rhs>[^;]+);"
        )
        for match in pattern.finditer(text):
            body = match.group("block") if match.group("block") is not None else match.group("stmt")
            body_start = match.start("block") if match.group("block") is not None else match.start("stmt")
            for assignment in assign_pattern.finditer(body):
                lhs = re.sub(r"\s+", "", assignment.group("lhs"))
                rhs = " ".join(assignment.group("rhs").split())
                if lhs in {"i", "j", "k"}:
                    continue
                if ") begin" in rhs:
                    continue
                records.append(
                    {
                        "module": self._module_for_offset(match.start(), module_spans),
                        "line": self._line_number(text, body_start + assignment.start()),
                        "lhs": lhs,
                        "rhs": rhs,
                    }
                )
        return records

    def _nd_occurrences(self, text: str, module_spans: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
        records: List[Dict[str, Any]] = []
        pattern = re.compile(r"\$ND\s*\((?P<values>.*?)\)", re.DOTALL)
        for match in pattern.finditer(text):
            values = [
                " ".join(value.split())
                for value in match.group("values").split(",")
                if value.strip()
            ]
            records.append(
                {
                    "module": self._module_for_offset(match.start(), module_spans),
                    "line": self._line_number(text, match.start()),
                    "assigned_signal": self._nd_assigned_signal(text, match.start()),
                    "legal_values": values,
                    "text": " ".join(match.group(0).split()),
                }
            )
        return records

    def _nd_assigned_signal(self, text: str, offset: int) -> Optional[str]:
        start = text.rfind(";", 0, offset) + 1
        prefix = text[start:offset]
        match = re.search(
            r"(?:\bassign\s+)?(?P<lhs>[A-Za-z_][A-Za-z0-9_$]*(?:\s*\[[^\]]+\])?)\s*=\s*$",
            prefix,
            flags=re.DOTALL,
        )
        if not match:
            return None
        return re.sub(r"\s+", "", match.group("lhs"))

    def _line_number(self, text: str, offset: int) -> int:
        return text.count("\n", 0, offset) + 1

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
