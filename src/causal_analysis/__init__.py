"""
Causal analysis adapter: bridges the formal verification workflow with the
VerilogCausalAnalysis submodule.

The VerilogCausalAnalysis project builds a causal DAG from an FST waveform
and Verilog RTL sources, providing a prior root-cause report which is
fed into the LLM during the `waveform_explanation` stage as extra evidence.

Integration strategy:
1. Resolve verilog source files from `verilog/extra_bench/<target>/`
   (these are emitted by `invoke_verification` through `set_testtop.py`).
2. Invoke `VerilogCausalAnalysis/analyze.py` as a subprocess (it has its own
   dependencies and Python path setup), pointing it at the counterexample FST
   and the per-benchmark verilog sources.
3. Parse the produced JSON/DOT/summary report and return it as plain text so
   the workflow can embed it in the waveform_explanation user prompt.

This keeps VerilogCausalAnalysis cleanly decoupled as a git submodule with its
own virtualenv-style environment (see VerilogCausalAnalysis/init.sh).
"""

from __future__ import annotations

import json
import logging
import os
import shutil
import subprocess
import sys
from dataclasses import dataclass
from typing import Any, Dict, List, Optional


@dataclass
class CausalAnalysisResult:
    """Structured result of a causal analysis run."""

    success: bool
    summary: str = ""
    json_report: Optional[Dict[str, Any]] = None
    dot_path: Optional[str] = None
    image_path: Optional[str] = None
    output_dir: Optional[str] = None
    error: Optional[str] = None


def _find_verilog_sources(verilog_dir: str) -> List[str]:
    """Return all `.v`/`.sv` files under `verilog_dir` (non-recursive)."""
    if not os.path.isdir(verilog_dir):
        return []
    candidates: List[str] = []
    for name in sorted(os.listdir(verilog_dir)):
        if name.endswith(('.v', '.sv')):
            full = os.path.join(verilog_dir, name)
            if os.path.isfile(full):
                candidates.append(full)
    return candidates


def _read_json(path: str) -> Optional[Dict[str, Any]]:
    try:
        with open(path, 'r', encoding='utf-8') as f:
            return json.load(f)
    except Exception:
        return None


class CausalAnalyzer:
    """Thin wrapper around the VerilogCausalAnalysis CLI entry point."""

    def __init__(
        self,
        workspace_dir: str,
        logger: logging.Logger,
        analyzer_dir: Optional[str] = None,
        timeout: int = 1800,
    ):
        """
        Args:
            workspace_dir: Root of this project (used to locate the submodule
                and the per-benchmark verilog directory).
            logger: Logger instance.
            analyzer_dir: Override the submodule path. Defaults to
                `<workspace>/VerilogCausalAnalysis`.
            timeout: Subprocess timeout for analyze.py in seconds.
        """
        self.workspace_dir = os.path.abspath(workspace_dir)
        self.logger = logger
        self.analyzer_dir = (
            os.path.abspath(analyzer_dir)
            if analyzer_dir
            else os.path.join(self.workspace_dir, 'VerilogCausalAnalysis')
        )
        self.timeout = timeout

    # ------------------------------------------------------------------
    # public API
    # ------------------------------------------------------------------
    def is_available(self) -> bool:
        """Check whether analyze.py is reachable inside the submodule."""
        return os.path.isfile(os.path.join(self.analyzer_dir, 'analyze.py'))

    def analyze(
        self,
        fst_path: str,
        target: str,
        output_dir: Optional[str] = None,
        clock: Optional[str] = None,
        endpoint: Optional[str] = None,
        cycle: Optional[int] = None,
        max_depth: int = 20,
        max_nodes: int = 200,
    ) -> CausalAnalysisResult:
        """
        Run causal analysis on a counterexample FST and return a report.

        Args:
            fst_path: Path to the counterexample FST file.
            target: Benchmark name; used to look up verilog sources in
                `<workspace>/verilog/extra_bench/<target>/`.
            output_dir: Where to place the generated reports. Defaults to
                `<workspace>/log/causal_analysis/<target>/`.
            clock, endpoint, cycle: Optional manual overrides (all auto-detected
                by analyze.py if omitted).
            max_depth, max_nodes: Backward-slicer limits.

        Returns:
            CausalAnalysisResult.
        """
        if not self.is_available():
            msg = (
                f"VerilogCausalAnalysis submodule not found at {self.analyzer_dir}. "
                "Run `git submodule update --init --recursive` and `bash VerilogCausalAnalysis/init.sh`."
            )
            self.logger.warning(msg)
            return CausalAnalysisResult(success=False, error=msg)

        if not fst_path or not os.path.isfile(fst_path):
            msg = f"FST file not found: {fst_path}"
            self.logger.warning(msg)
            return CausalAnalysisResult(success=False, error=msg)

        verilog_dir = os.path.join(self.workspace_dir, 'verilog', 'extra_bench', target)
        verilog_files = _find_verilog_sources(verilog_dir)
        if not verilog_files:
            msg = (
                f"No Verilog sources found in {verilog_dir}. "
                "Did the `invoke_verification` stage run successfully?"
            )
            self.logger.warning(msg)
            return CausalAnalysisResult(success=False, error=msg)

        if output_dir is None:
            output_dir = os.path.join(self.workspace_dir, 'log', 'causal_analysis', target)
        os.makedirs(output_dir, exist_ok=True)

        analyze_script = os.path.join(self.analyzer_dir, 'analyze.py')
        cmd: List[str] = [
            sys.executable, analyze_script,
            '--fst', os.path.abspath(fst_path),
            '--verilog', *[os.path.abspath(p) for p in verilog_files],
            '--output', os.path.abspath(output_dir),
            '--max-depth', str(max_depth),
            '--max-nodes', str(max_nodes),
        ]
        if clock:
            cmd.extend(['--clock', clock])
        if endpoint:
            cmd.extend(['--endpoint', endpoint])
        if cycle is not None:
            cmd.extend(['--cycle', str(cycle)])

        self.logger.info(f"Running causal analysis: {' '.join(cmd)}")

        try:
            proc = subprocess.run(
                cmd,
                cwd=self.analyzer_dir,
                capture_output=True,
                text=True,
                timeout=self.timeout,
            )
        except subprocess.TimeoutExpired:
            msg = f"Causal analysis timed out after {self.timeout}s"
            self.logger.warning(msg)
            return CausalAnalysisResult(success=False, output_dir=output_dir, error=msg)
        except Exception as e:
            msg = f"Failed to launch causal analysis: {e}"
            self.logger.error(msg)
            return CausalAnalysisResult(success=False, output_dir=output_dir, error=msg)

        stdout = proc.stdout or ""
        stderr = proc.stderr or ""

        # Collect artifacts. analyze.py typically emits `causal_graph.json`,
        # `causal_graph.dot` and an image inside the output directory.
        json_path = self._find_artifact(output_dir, ['.json'])
        dot_path = self._find_artifact(output_dir, ['.dot'])
        image_path = self._find_artifact(output_dir, ['.png', '.svg', '.pdf'])
        json_report = _read_json(json_path) if json_path else None

        success = proc.returncode == 0
        if not success:
            err_tail = (stderr or stdout).strip().splitlines()[-20:]
            error_msg = "\n".join(err_tail) or f"analyze.py exited with code {proc.returncode}"
            return CausalAnalysisResult(
                success=False,
                summary=stdout,
                json_report=json_report,
                dot_path=dot_path,
                image_path=image_path,
                output_dir=output_dir,
                error=error_msg,
            )

        summary = self._format_summary(stdout, json_report, image_path, dot_path)
        return CausalAnalysisResult(
            success=True,
            summary=summary,
            json_report=json_report,
            dot_path=dot_path,
            image_path=image_path,
            output_dir=output_dir,
        )

    # ------------------------------------------------------------------
    # helpers
    # ------------------------------------------------------------------
    @staticmethod
    def _find_artifact(output_dir: str, extensions: List[str]) -> Optional[str]:
        if not os.path.isdir(output_dir):
            return None
        for name in sorted(os.listdir(output_dir)):
            for ext in extensions:
                if name.endswith(ext):
                    return os.path.join(output_dir, name)
        return None

    @staticmethod
    def _format_summary(
        stdout: str,
        json_report: Optional[Dict[str, Any]],
        image_path: Optional[str],
        dot_path: Optional[str],
    ) -> str:
        """Build a concise natural-language summary of the causal analysis."""
        lines: List[str] = ["## Causal Analysis Report (prior evidence)", ""]

        if json_report:
            endpoint = json_report.get('endpoint') or json_report.get('endpoint_signal')
            cycle = json_report.get('endpoint_cycle') or json_report.get('cycle')
            nodes = json_report.get('nodes') or json_report.get('graph', {}).get('nodes')
            edges = json_report.get('edges') or json_report.get('graph', {}).get('edges')
            root_causes = (
                json_report.get('root_causes')
                or json_report.get('root_cause_candidates')
                or []
            )

            if endpoint:
                lines.append(f"- Endpoint signal: `{endpoint}`")
            if cycle is not None:
                lines.append(f"- Endpoint cycle: {cycle}")
            if isinstance(nodes, list):
                lines.append(f"- Causal DAG nodes: {len(nodes)}")
            if isinstance(edges, list):
                lines.append(f"- Causal DAG edges: {len(edges)}")

            if root_causes:
                lines.append("")
                lines.append("### Candidate Root Causes")
                for i, rc in enumerate(root_causes[:10], 1):
                    if isinstance(rc, dict):
                        sig = rc.get('signal') or rc.get('name') or '<unknown>'
                        loc = rc.get('location') or rc.get('file') or ''
                        line_no = rc.get('line') or rc.get('line_number')
                        expr = rc.get('expression') or rc.get('expr') or ''
                        entry = f"{i}. `{sig}`"
                        if loc:
                            entry += f" (at {loc}" + (f":{line_no}" if line_no else "") + ")"
                        if expr:
                            entry += f" — {expr}"
                        lines.append(entry)
                    else:
                        lines.append(f"{i}. {rc}")

        lines.append("")
        lines.append("### Artifact Paths")
        if image_path:
            lines.append(f"- Causal DAG image: `{image_path}`")
        if dot_path:
            lines.append(f"- Causal DAG DOT:   `{dot_path}`")

        if stdout.strip():
            # Include the last ~40 lines of the tool's own natural-language summary.
            tail = stdout.strip().splitlines()[-40:]
            lines.append("")
            lines.append("### Tool Output (tail)")
            lines.append("```")
            lines.extend(tail)
            lines.append("```")

        return "\n".join(lines)


def run_causal_analysis_if_available(
    workspace_dir: str,
    logger: logging.Logger,
    fst_path: str,
    target: str,
) -> Optional[str]:
    """
    Convenience helper used by the workflow.

    Returns a human-readable markdown summary of the causal analysis, or
    `None` if the submodule is not available / the analysis failed. The
    workflow uses this text as additional prior evidence in the
    `waveform_explanation` stage.
    """
    analyzer = CausalAnalyzer(workspace_dir=workspace_dir, logger=logger)
    if not analyzer.is_available():
        logger.info("VerilogCausalAnalysis not available; skipping causal analysis.")
        return None

    result = analyzer.analyze(fst_path=fst_path, target=target)
    if not result.success:
        logger.warning(f"Causal analysis did not complete cleanly: {result.error}")
        # Still surface whatever partial summary we managed to collect.
        return result.summary or None
    return result.summary


__all__ = [
    "CausalAnalyzer",
    "CausalAnalysisResult",
    "run_causal_analysis_if_available",
]
