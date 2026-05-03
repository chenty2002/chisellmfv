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


class CausalAnalysisActions:
    """Structured query interface over a VerilogCausalAnalysis JSON report."""

    def __init__(
        self,
        json_report: Dict[str, Any],
        summary: str = "",
        output_dir: Optional[str] = None,
    ):
        self.json_report = json_report
        self.summary = summary
        self.output_dir = output_dir
        self.nodes: List[Dict[str, Any]] = list(json_report.get("nodes") or [])
        self.edges: List[Dict[str, Any]] = list(json_report.get("edges") or [])
        self.meta: Dict[str, Any] = dict(json_report.get("meta") or {})
        self.node_by_id: Dict[str, Dict[str, Any]] = {
            str(node.get("id")): node
            for node in self.nodes
            if node.get("id") is not None
        }
        self.edges_by_src: Dict[str, List[Dict[str, Any]]] = {}
        self.edges_by_dst: Dict[str, List[Dict[str, Any]]] = {}
        for edge in self.edges:
            src = str(edge.get("src_node_id", ""))
            dst = str(edge.get("dst_node_id", ""))
            self.edges_by_src.setdefault(src, []).append(edge)
            self.edges_by_dst.setdefault(dst, []).append(edge)

    def execute(self, action: Dict[str, Any]) -> Dict[str, Any]:
        """Dispatch a causal_* workflow action."""
        action_type = action.get("type", "")
        try:
            if action_type == "causal_get_roots":
                return self.get_roots(
                    limit=int(action.get("limit", 10)),
                    min_score=action.get("min_score"),
                )
            if action_type == "causal_trace_path":
                return self.trace_path(
                    source_node_id=action.get("source_node_id"),
                    target_node_id=action.get("target_node_id"),
                    signal=action.get("signal"),
                    max_depth=int(action.get("max_depth", 12)),
                    max_paths=int(action.get("max_paths", 5)),
                )
            if action_type == "causal_get_node_evidence":
                return self.get_node_evidence(
                    node_id=action.get("node_id"),
                    signal=action.get("signal"),
                    cycle=action.get("cycle"),
                )
            return {"type": action_type, "success": False, "error": f"Unknown causal action: {action_type}"}
        except Exception as exc:
            return {"type": action_type, "success": False, "error": str(exc)}

    def get_index(self) -> Dict[str, Any]:
        """Return a compact DAG index suitable for prompt context."""
        endpoint = next((n for n in self.nodes if n.get("is_endpoint")), None)
        roots = self._rank_roots()[:8]
        return {
            "endpoint": self._node_brief(endpoint) if endpoint else None,
            "root_count": len([n for n in self.nodes if n.get("is_root")]),
            "node_count": len(self.nodes),
            "edge_count": len(self.edges),
            "top_roots": [self._node_brief(n) for n in roots],
            "output_dir": self.output_dir,
        }

    def get_roots(self, limit: int = 10, min_score: Optional[float] = None) -> Dict[str, Any]:
        roots = self._rank_roots()
        if min_score is not None:
            roots = [n for n in roots if float(n.get("suspect_score") or 0.0) >= float(min_score)]
        roots = roots[: max(1, limit)]
        return {
            "type": "causal_get_roots",
            "success": True,
            "roots": [self._node_with_edge_counts(n) for n in roots],
            "total_roots": len([n for n in self.nodes if n.get("is_root")]),
            "meta": self._meta_brief(),
        }

    def trace_path(
        self,
        source_node_id: Optional[str] = None,
        target_node_id: Optional[str] = None,
        signal: Optional[str] = None,
        max_depth: int = 12,
        max_paths: int = 5,
    ) -> Dict[str, Any]:
        target = self._resolve_target_node(target_node_id)
        if target is None:
            return {
                "type": "causal_trace_path",
                "success": False,
                "error": "No target node found. Provide target_node_id or ensure the graph has an endpoint node.",
            }

        candidate_sources = self._candidate_source_ids(source_node_id, signal)
        paths = self._paths_to_target(
            target_id=str(target["id"]),
            source_ids=candidate_sources,
            max_depth=max_depth,
            max_paths=max_paths,
        )
        return {
            "type": "causal_trace_path",
            "success": True,
            "target": self._node_brief(target),
            "source_filter": {
                "source_node_id": source_node_id,
                "signal": signal,
            },
            "paths": paths,
            "path_count": len(paths),
        }

    def get_node_evidence(
        self,
        node_id: Optional[str] = None,
        signal: Optional[str] = None,
        cycle: Optional[int] = None,
    ) -> Dict[str, Any]:
        node = self._find_node(node_id=node_id, signal=signal, cycle=cycle)
        if node is None:
            return {
                "type": "causal_get_node_evidence",
                "success": False,
                "error": "Node not found",
                "candidates": [
                    self._node_brief(n)
                    for n in self._find_nodes_by_signal(signal or "")[:8]
                ] if signal else [],
            }

        node_id_str = str(node["id"])
        incoming = self.edges_by_dst.get(node_id_str, [])
        outgoing = self.edges_by_src.get(node_id_str, [])
        return {
            "type": "causal_get_node_evidence",
            "success": True,
            "node": node,
            "incoming_edges": incoming,
            "outgoing_edges": outgoing,
            "rtl_refs": node.get("rtl_refs", []),
            "meta": self._meta_brief(),
        }

    def _rank_roots(self) -> List[Dict[str, Any]]:
        roots = [n for n in self.nodes if n.get("is_root")]
        return sorted(
            roots,
            key=lambda n: (
                -float(n.get("suspect_score") or 0.0),
                int(n.get("depth") or 0),
                str(n.get("signal", "")),
            ),
        )

    def _resolve_target_node(self, target_node_id: Optional[str]) -> Optional[Dict[str, Any]]:
        if target_node_id:
            return self.node_by_id.get(str(target_node_id))
        return next((n for n in self.nodes if n.get("is_endpoint")), None)

    def _candidate_source_ids(
        self,
        source_node_id: Optional[str],
        signal: Optional[str],
    ) -> Optional[set[str]]:
        if source_node_id:
            return {str(source_node_id)}
        if signal:
            return {str(n["id"]) for n in self._find_nodes_by_signal(signal) if n.get("id") is not None}
        roots = self._rank_roots()
        return {str(n["id"]) for n in roots if n.get("id") is not None} or None

    def _paths_to_target(
        self,
        target_id: str,
        source_ids: Optional[set[str]],
        max_depth: int,
        max_paths: int,
    ) -> List[Dict[str, Any]]:
        paths: List[Dict[str, Any]] = []

        def dfs(current_id: str, node_path: List[str], edge_path: List[Dict[str, Any]]) -> None:
            if len(paths) >= max_paths:
                return
            if len(node_path) > max_depth + 1:
                return
            if source_ids is not None and current_id in source_ids:
                paths.append(self._format_path(list(reversed(node_path)), list(reversed(edge_path))))
                return

            incoming = sorted(
                self.edges_by_dst.get(current_id, []),
                key=lambda e: -float(e.get("contribution_score") or 0.0),
            )
            if not incoming:
                if source_ids is None:
                    paths.append(self._format_path(list(reversed(node_path)), list(reversed(edge_path))))
                return

            for edge in incoming:
                src_id = str(edge.get("src_node_id"))
                if src_id in node_path:
                    continue
                dfs(src_id, node_path + [src_id], edge_path + [edge])

        dfs(target_id, [target_id], [])
        paths.sort(key=lambda p: -float(p.get("score") or 0.0))
        return paths[:max_paths]

    def _format_path(self, node_ids: List[str], edges: List[Dict[str, Any]]) -> Dict[str, Any]:
        nodes = [
            self._node_brief(self.node_by_id[node_id])
            for node_id in node_ids
            if node_id in self.node_by_id
        ]
        score = sum(float(e.get("contribution_score") or 0.0) for e in edges)
        return {
            "nodes": nodes,
            "edges": edges,
            "score": round(score, 3),
        }

    def _find_node(
        self,
        node_id: Optional[str],
        signal: Optional[str],
        cycle: Optional[int],
    ) -> Optional[Dict[str, Any]]:
        if node_id:
            return self.node_by_id.get(str(node_id))
        candidates = self._find_nodes_by_signal(signal or "")
        if cycle is not None:
            candidates = [n for n in candidates if int(n.get("cycle", -1)) == int(cycle)]
        if not candidates:
            return None
        candidates.sort(
            key=lambda n: (
                not n.get("is_endpoint"),
                not n.get("is_root"),
                -float(n.get("suspect_score") or 0.0),
            )
        )
        return candidates[0]

    def _find_nodes_by_signal(self, signal: str) -> List[Dict[str, Any]]:
        if not signal:
            return []
        needle = signal.lower()
        return [
            n for n in self.nodes
            if needle in str(n.get("signal", "")).lower()
        ]

    def _node_brief(self, node: Optional[Dict[str, Any]]) -> Dict[str, Any]:
        if not node:
            return {}
        return {
            "id": node.get("id"),
            "signal": node.get("signal"),
            "cycle": node.get("cycle"),
            "value": node.get("value"),
            "suspect_score": node.get("suspect_score", 0.0),
            "is_root": node.get("is_root", False),
            "is_endpoint": node.get("is_endpoint", False),
            "depth": node.get("depth"),
        }

    def _node_with_edge_counts(self, node: Dict[str, Any]) -> Dict[str, Any]:
        brief = self._node_brief(node)
        node_id = str(node.get("id"))
        brief.update({
            "incoming_edges": len(self.edges_by_dst.get(node_id, [])),
            "outgoing_edges": len(self.edges_by_src.get(node_id, [])),
            "rtl_refs": node.get("rtl_refs", [])[:5],
        })
        return brief

    def _meta_brief(self) -> Dict[str, Any]:
        keep = [
            "endpoint_signal",
            "endpoint_cycle",
            "clock_signal",
            "total_nodes",
            "total_edges",
            "root_nodes",
            "sva_trigger_cycle",
            "sva_time_window",
            "sva_window_end_cycle",
        ]
        return {key: self.meta.get(key) for key in keep if key in self.meta}


def run_causal_analysis_result_if_available(
    workspace_dir: str,
    logger: logging.Logger,
    fst_path: str,
    target: str,
) -> Optional[CausalAnalysisResult]:
    """
    Run causal analysis and return the structured result when available.

    Unlike run_causal_analysis_if_available(), this keeps the JSON report so the
    workflow can expose DAG query tools to the LLM.
    """
    analyzer = CausalAnalyzer(workspace_dir=workspace_dir, logger=logger)
    if not analyzer.is_available():
        logger.info("VerilogCausalAnalysis not available; skipping causal analysis.")
        return None

    result = analyzer.analyze(fst_path=fst_path, target=target)
    if not result.success:
        logger.warning(f"Causal analysis did not complete cleanly: {result.error}")
    return result


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
    result = run_causal_analysis_result_if_available(
        workspace_dir=workspace_dir,
        logger=logger,
        fst_path=fst_path,
        target=target,
    )
    if result is None:
        return None
    if not result.success:
        return result.summary or None
    return result.summary


__all__ = [
    "CausalAnalyzer",
    "CausalAnalysisActions",
    "CausalAnalysisResult",
    "run_causal_analysis_if_available",
    "run_causal_analysis_result_if_available",
]
