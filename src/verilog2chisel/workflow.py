"""Auditable Verilog to Chisel conversion workflow."""

import json
import logging
import os
import re
import shutil
import subprocess
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

from ..core.llm_client import LLMClient
from ..core.prompt_builder import build_assistant_tool_call_message
from ..utils.file_utils import write_file as utils_write_file
from ..utils.llm_logging import LLMLogger
from .actions import execute_action
from .gates import check_generated_verilog, check_prompt_leak, lint_scala_sources
from .preflight import V2CPreflight
from .prompt_builder import (
    build_v2c_conversion_prompt,
    build_v2c_repair_prompt,
    load_vis_rules,
)
from .tool_schemas import convert_tool_call_to_action, get_verilog2chisel_tool_schemas


class Verilog2ChiselWorkflow:
    """Stage-based v2c workflow with deterministic artifacts and local gates."""

    def __init__(
        self,
        llm_client: LLMClient,
        workspace_dir: str,
        benchmark: str,
        logger: Optional[logging.Logger],
        max_iterations: int = 5,
        preflight_only: bool = False,
        publish: bool = False,
    ):
        self.llm = llm_client
        self.workspace_dir = Path(workspace_dir).resolve()
        self.benchmark = benchmark
        self.logger = logger or logging.getLogger(__name__)
        self.max_iterations = max_iterations
        self.preflight_only = preflight_only
        self.publish = publish

        self.verilog2chisel_dir = self.workspace_dir / "verilog2chisel"
        self.verilog_dir = self.verilog2chisel_dir / "verilog" / benchmark
        self.chisel_dir = self.verilog2chisel_dir / "chisel" / benchmark
        self.generated_dir = self.verilog2chisel_dir / "generated" / benchmark
        self.extra_bench_dir = self.workspace_dir / "chisel" / "extra_bench" / benchmark
        self.run_dir: Optional[Path] = None
        self.input_summary: Dict[str, Any] = {}
        self.compile_attempts_path: Optional[Path] = None

        self.system_prompt = (
            "You are an expert in VIS Verilog and Chisel formal translation. "
            "Use only the provided tool."
        )

    def convert(self) -> Dict[str, Any]:
        self._info(f"Starting Verilog2Chisel v2 workflow for {self.benchmark}")
        preflight = V2CPreflight(
            str(self.workspace_dir),
            self.benchmark,
            max_iterations=self.max_iterations,
        )
        preflight_result = preflight.run()
        self.run_dir = preflight.run_dir
        self.compile_attempts_path = self.run_dir / "compile_attempts.jsonl"
        self._touch_run_logs()

        if not preflight_result.get("success"):
            return self._finish(
                success=False,
                iterations=0,
                error_kind=preflight_result.get("error_kind", "preflight_failed"),
                extra={"preflight_result": preflight_result},
            )

        self.input_summary = self._read_json(self.run_dir / "input_summary.json")
        source_file = self.verilog_dir / self.input_summary["files"][0]["path"]
        verilog_text = source_file.read_text(encoding="utf-8", errors="replace")

        if self.preflight_only:
            return self._finish(
                success=True,
                iterations=0,
                extra={
                    "preflight_only": True,
                    "verilog_files": [source_file.name],
                    "run_dir": str(self.run_dir),
                },
            )

        self._clean_output_dirs()

        prompt = build_v2c_conversion_prompt(
            input_summary=self.input_summary,
            verilog_text=verilog_text,
            rules_text=load_vis_rules(),
        )
        leak = check_prompt_leak(prompt)
        if not leak.success:
            return self._finish(
                success=False,
                iterations=0,
                error_kind="benchmark_specific_prompt_leak",
                lint=leak,
            )

        tool_schemas = get_verilog2chisel_tool_schemas()
        messages: List[Dict[str, Any]] = [
            {"role": "system", "content": self.system_prompt},
            {"role": "user", "content": prompt},
        ]
        protocol_failures = 0

        for iteration in range(1, self.max_iterations + 1):
            self._info(f"=== v2c iteration {iteration}/{self.max_iterations} ===")
            self._log_llm_request(iteration, messages, tool_schemas)
            response = self._call_llm(messages, tool_schemas, iteration)
            self._log_llm_response(iteration, response)

            if response.get("type") != "function_calls":
                protocol_failures += 1
                if protocol_failures > 1:
                    return self._finish(
                        success=False,
                        iterations=iteration,
                        error_kind="tool_protocol_violation",
                    )
                messages.append({
                    "role": "user",
                    "content": "ERROR: respond only with the named write_files tool call.",
                })
                continue

            function_calls = response.get("function_calls", [])
            raw_message = response.get("raw_message", {})
            messages.append(build_assistant_tool_call_message(raw_message, function_calls))

            action_ok, stage_complete, tool_messages = self._execute_tool_calls(function_calls)
            messages.extend(tool_messages)
            if not action_ok:
                if iteration == self.max_iterations:
                    return self._finish(
                        success=False,
                        iterations=iteration,
                        error_kind="incomplete_tool_output",
                    )
                messages.append({
                    "role": "user",
                    "content": (
                        "ERROR: the previous write_files call did not complete the stage. "
                        "Write 1-3 safe .scala files, combine modules into fewer files if needed, "
                        "and set stage_complete=true only when the complete translation is present."
                    ),
                })
                continue
            if not stage_complete:
                self._info("write_files did not set stage_complete=true; continuing to local gates")

            scala_files = sorted(self.chisel_dir.glob("*.scala"))
            lint = lint_scala_sources(scala_files, self.input_summary)
            if not lint.success:
                if iteration == self.max_iterations:
                    return self._finish(
                        success=False,
                        iterations=iteration,
                        error_kind="local_lint_failed",
                        lint=lint,
                    )
                messages.append({
                    "role": "user",
                    "content": self._build_repair_prompt([], lint.errors),
                })
                continue

            compile_success, compile_output, returncode = self.run_make(iteration)
            self._append_compile_attempt(iteration, compile_success, compile_output, returncode)
            if not compile_success:
                if iteration == self.max_iterations:
                    return self._finish(
                        success=False,
                        iterations=iteration,
                        error_kind="compile_failed",
                        compile_output=compile_output,
                        lint=lint,
                    )
                messages.append({
                    "role": "user",
                    "content": self._build_repair_prompt(
                        self._extract_error_lines(compile_output),
                        lint.errors,
                    ),
                })
                continue

            top_module = self._top_module_name()
            generated = check_generated_verilog(self.generated_dir, top_module)
            if not generated.success:
                if iteration == self.max_iterations:
                    return self._finish(
                        success=False,
                        iterations=iteration,
                        error_kind="generated_verilog_failed",
                        lint=lint,
                        generated=generated,
                    )
                messages.append({
                    "role": "user",
                    "content": self._build_repair_prompt(generated.errors, lint.errors),
                })
                continue

            publish_result = None
            if self.publish:
                publish_result = self._copy_to_extra_bench()
                if not publish_result[0]:
                    return self._finish(
                        success=False,
                        iterations=iteration,
                        error_kind="publish_failed",
                        lint=lint,
                        generated=generated,
                        extra={"publish_error": publish_result[1]},
                    )

            self._persist_success_artifacts()
            return self._finish(
                success=True,
                iterations=iteration,
                lint=lint,
                generated=generated,
                extra={
                    "verilog_files": [source_file.name],
                    "output_files": [path.name for path in scala_files],
                    "published": bool(self.publish),
                    "publish_message": publish_result[1] if publish_result else None,
                },
            )

        return self._finish(
            success=False,
            iterations=self.max_iterations,
            error_kind="max_iterations_reached",
        )

    def write_chisel_file(self, file_path: str, content: str) -> Tuple[bool, str]:
        if os.path.isabs(file_path) or ".." in Path(file_path).parts:
            return False, f"Invalid file path: {file_path}"
        full_path = self.chisel_dir / file_path
        full_path.parent.mkdir(parents=True, exist_ok=True)
        utils_write_file(str(full_path), content)
        return True, f"Successfully wrote {file_path}"

    def run_make(self, attempt: int) -> Tuple[bool, str, int]:
        ok, msg = self._prepare_benchmark_build_files()
        if not ok:
            return False, msg, 1
        self.generated_dir.mkdir(parents=True, exist_ok=True)
        result = subprocess.run(
            ["make", f"BUILD_DIR={self.generated_dir}"],
            cwd=str(self.chisel_dir),
            capture_output=True,
            text=True,
            timeout=300,
        )
        output = result.stdout + "\n" + result.stderr
        return result.returncode == 0, output, result.returncode

    def _prepare_benchmark_build_files(self) -> Tuple[bool, str]:
        try:
            for filename in ["build.sbt", "Makefile"]:
                src_file = self.verilog2chisel_dir / filename
                dest_file = self.chisel_dir / filename
                if src_file.exists():
                    shutil.copy2(src_file, dest_file)
            return True, "Build files copied successfully"
        except Exception as exc:
            return False, f"Error copying build files: {exc}"

    def _copy_to_extra_bench(self) -> Tuple[bool, str]:
        try:
            scala_files = sorted(self.chisel_dir.glob("*.scala"))
            if not scala_files:
                return False, f"No Scala files found in {self.chisel_dir}"
            if self.extra_bench_dir.exists():
                shutil.rmtree(self.extra_bench_dir)
            self.extra_bench_dir.mkdir(parents=True, exist_ok=True)
            for src_file in scala_files:
                shutil.copy2(src_file, self.extra_bench_dir / src_file.name)
            return True, f"Copied {len(scala_files)} files to {self.extra_bench_dir}"
        except Exception as exc:
            return False, f"Failed to publish: {exc}"

    def _clean_output_dirs(self) -> None:
        self.chisel_dir.mkdir(parents=True, exist_ok=True)
        for path in self.chisel_dir.glob("*.scala"):
            path.unlink()
        if self.generated_dir.exists():
            shutil.rmtree(self.generated_dir)
        self.generated_dir.mkdir(parents=True, exist_ok=True)

    def _persist_success_artifacts(self) -> None:
        run_chisel_dir = self.run_dir / "chisel"
        run_generated_dir = self.run_dir / "generated"
        if run_chisel_dir.exists():
            shutil.rmtree(run_chisel_dir)
        if run_generated_dir.exists():
            shutil.rmtree(run_generated_dir)
        run_chisel_dir.mkdir(parents=True, exist_ok=True)
        run_generated_dir.mkdir(parents=True, exist_ok=True)
        for path in sorted(self.chisel_dir.glob("*.scala")):
            shutil.copy2(path, run_chisel_dir / path.name)
        for path in sorted(self.generated_dir.glob("*")):
            if path.is_file():
                shutil.copy2(path, run_generated_dir / path.name)

    def _call_llm(
        self,
        messages: List[Dict[str, Any]],
        tool_schemas: List[Dict[str, Any]],
        iteration: int,
    ) -> Dict[str, Any]:
        request = {
            "iteration": iteration,
            "temperature": 0,
            "tool_choice": {"type": "function", "function": {"name": "write_files"}},
            "enable_thinking": False,
            "parallel_tool_calls": False,
            "max_tokens": 8192,
        }
        self._append_jsonl("model_requests.jsonl", request)
        return self.llm.chat_with_tools(
            messages=messages,
            tools=tool_schemas,
            max_tokens=8192,
            temperature=0,
            tool_choice={"type": "function", "function": {"name": "write_files"}},
            enable_thinking=False,
            parallel_tool_calls=False,
        )

    def _execute_tool_calls(
        self,
        function_calls: List[Dict[str, Any]],
    ) -> Tuple[bool, bool, List[Dict[str, Any]]]:
        action_ok = True
        stage_complete = False
        tool_messages: List[Dict[str, Any]] = []
        for fc in function_calls:
            action = convert_tool_call_to_action(fc)
            result = execute_action(action, self.write_chisel_file)
            action_ok = action_ok and bool(result.get("success"))
            stage_complete = stage_complete or bool(result.get("stage_complete"))
            self._append_jsonl("operations.jsonl", result)
            tool_messages.append({
                "role": "tool",
                "tool_call_id": fc["id"],
                "name": fc["name"],
                "content": json.dumps(result, ensure_ascii=False),
            })
        return action_ok, stage_complete, tool_messages

    def _build_repair_prompt(self, error_lines: List[str], lint_errors: List[str]) -> str:
        return build_v2c_repair_prompt(
            error_lines=error_lines,
            lint_errors=lint_errors,
            scala_windows=self._scala_windows(),
        )

    def _scala_windows(self) -> Dict[str, str]:
        windows: Dict[str, str] = {}
        for path in sorted(self.chisel_dir.glob("*.scala")):
            lines = path.read_text(encoding="utf-8", errors="replace").splitlines()
            selected = set(range(min(40, len(lines))))
            patterns = [
                r"\b[A-Za-z_][A-Za-z0-9_]*\s*\([0-9]+\)\s*:=",
                r"::\s*Nil\s*=\s*Enum\s*\(",
                r"\b(?:LFSR|scala\.util\.Random|random\.LFSR)\b",
                r"chisel3\.experimental\.verification",
            ]
            for index, line in enumerate(lines):
                if any(re.search(pattern, line) for pattern in patterns):
                    start = max(0, index - 8)
                    end = min(len(lines), index + 9)
                    selected.update(range(start, end))
            numbered = [
                f"{index + 1}: {lines[index]}"
                for index in sorted(selected)[:140]
            ]
            windows[path.name] = "\n".join(numbered)
        return windows

    def _extract_error_lines(self, output: str) -> List[str]:
        lines = [line for line in output.splitlines() if "[error]" in line.lower()]
        return lines[:40] if lines else output.splitlines()[:40]

    def _append_compile_attempt(
        self,
        attempt: int,
        success: bool,
        output: str,
        returncode: int,
    ) -> None:
        self._append_jsonl(
            "compile_attempts.jsonl",
            {
                "attempt": attempt,
                "success": success,
                "returncode": returncode,
                "error_lines": self._extract_error_lines(output),
                "stdout_chars": len(output),
                "stderr_chars": 0,
            },
        )

    def _finish(
        self,
        *,
        success: bool,
        iterations: int,
        error_kind: Optional[str] = None,
        compile_output: Optional[str] = None,
        lint: Optional[Any] = None,
        generated: Optional[Any] = None,
        extra: Optional[Dict[str, Any]] = None,
    ) -> Dict[str, Any]:
        run_dir = self.run_dir or Path("")
        scala_files = sorted(self.chisel_dir.glob("*.scala"))
        input_files = [
            item.get("path")
            for item in self.input_summary.get("files", [])
            if isinstance(item, dict)
        ]
        result = {
            "schema_version": "v2c_stage_result.v1",
            "target": self.benchmark,
            "success": success,
            "iterations": iterations,
            "input_files": input_files,
            "output_files": [path.name for path in scala_files],
            "compile_success": bool(success and generated is not None),
            "generated_verilog": getattr(generated, "generated_files", []),
            "lint": getattr(lint, "counts", {}),
            "artifacts": {
                "manifest": "manifest.json",
                "input_summary": "input_summary.json",
                "prompt_bundle": "prompt_bundle.json",
                "compile_attempts": "compile_attempts.jsonl",
                "generated": "generated/",
            },
            "run_dir": str(run_dir),
        }
        if error_kind:
            result["error_kind"] = error_kind
        if compile_output:
            result["compile_output"] = compile_output
        if lint is not None:
            result["lint_errors"] = getattr(lint, "errors", [])
        if generated is not None:
            result["generated_errors"] = getattr(generated, "errors", [])
        if extra:
            result.update(extra)

        if self.run_dir:
            lint_success = success if lint is None else not getattr(lint, "errors", [])
            self._write_json(
                "lint_result.json",
                {
                    "schema_version": "v2c_lint_result.v1",
                    "success": lint_success,
                    "errors": getattr(lint, "errors", []),
                    "counts": getattr(lint, "counts", {}),
                },
            )
            self._write_json("stage_result.json", result)
            self._write_json(
                "run_cost_summary.json",
                {
                    "schema_version": "v2c_run_cost_summary.v1",
                    "target": self.benchmark,
                    "iterations": iterations,
                    "model_requests": self._count_jsonl("model_requests.jsonl"),
                },
            )
        return result

    def _top_module_name(self) -> str:
        files = self.input_summary.get("files", [])
        if files and files[0].get("modules"):
            return files[0]["modules"][0]
        return "Main"

    def _touch_run_logs(self) -> None:
        for filename in [
            "operations.jsonl",
            "model_requests.jsonl",
            "model_responses.jsonl",
            "compile_attempts.jsonl",
        ]:
            (self.run_dir / filename).touch()

    def _append_jsonl(self, filename: str, data: Dict[str, Any]) -> None:
        path = self.run_dir / filename
        with path.open("a", encoding="utf-8") as handle:
            handle.write(json.dumps(data, ensure_ascii=False, default=str) + "\n")

    def _write_json(self, filename: str, data: Dict[str, Any]) -> None:
        (self.run_dir / filename).write_text(
            json.dumps(data, indent=2, ensure_ascii=False, default=str) + "\n",
            encoding="utf-8",
        )

    def _read_json(self, path: Path) -> Dict[str, Any]:
        return json.loads(path.read_text(encoding="utf-8"))

    def _count_jsonl(self, filename: str) -> int:
        path = self.run_dir / filename
        if not path.exists():
            return 0
        return sum(1 for line in path.read_text(encoding="utf-8").splitlines() if line.strip())

    def _log_llm_request(
        self,
        iteration: int,
        messages: List[Dict[str, Any]],
        tool_schemas: List[Dict[str, Any]],
    ) -> None:
        try:
            log_msg = LLMLogger.format_request(
                messages[-1].get("content", ""),
                tool_schemas,
                stage="Verilog2Chisel",
                iteration=iteration,
                include_details=(iteration == 1),
            )
            self.logger.info(log_msg)
        except Exception:
            self.logger.info("Failed to format v2c LLM request log")

    def _log_llm_response(self, iteration: int, response: Dict[str, Any]) -> None:
        self._append_jsonl("model_responses.jsonl", {"iteration": iteration, "response": response})
        try:
            log_msg = LLMLogger.format_response(
                response,
                stage="Verilog2Chisel",
                iteration=iteration,
                truncate_content=False,
            )
            self.logger.info(log_msg)
        except Exception:
            self.logger.info("Failed to format v2c LLM response log")

    def _info(self, message: str) -> None:
        self.logger.info(message)
        print(message)
