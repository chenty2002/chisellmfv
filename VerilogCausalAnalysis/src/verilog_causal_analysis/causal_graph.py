"""
Causal Graph Builder for Counterexample Analysis.

Main module that orchestrates the construction of a causal DAG from
FST waveforms and Verilog RTL code. The DAG represents causality relationships
where nodes are (signal, cycle, value) tuples and edges represent direct
causal influence under the counterexample execution.

Usage:
    from verilog_causal_analysis import CausalGraphBuilder
    
    builder = CausalGraphBuilder(
        fst_path="counterexample.fst",
        verilog_paths=["design.v", "testbench.v"],
        clock_signal="TestTop.clock"
    )
    
    result = builder.build(
        endpoint_signal="assertion_fail",
        endpoint_cycle=100  # or None to use last cycle
    )
    
    # Export to different formats
    builder.export_json("causal_graph.json")
    builder.export_dot("causal_graph.dot")
    builder.export_networkx("causal_graph_edges.csv")
"""

import os
import json
import time
import random
from datetime import datetime
from dataclasses import dataclass, field, asdict
from typing import Dict, List, Set, Tuple, Optional, Any

import graphviz

from .verilog_parser import VerilogParser, DependencyType
from .cycle_waveform import CycleAlignedWaveform
from .causal_slicer import BackwardSlicer, CausalNode, CausalEdge, ContributionType


# Version info for reproducibility
__version__ = "1.0.0"


@dataclass
class CausalGraphMeta:
    """Metadata for the causal graph."""
    fst_path: str
    verilog_paths: List[str]
    clock_signal: str
    endpoint_signal: str
    endpoint_cycle: int
    max_depth: int
    max_nodes: int
    generation_time: str
    runtime_seconds: float
    tool_version: str
    random_seed: int
    cycle_count: int
    timescale: int
    total_nodes: int
    total_edges: int
    root_nodes: int
    max_depth_reached: bool
    max_nodes_reached: bool
    undetermined_nodes: int
    # SVA time window analysis fields
    sva_trigger_cycle: Optional[int] = None
    sva_time_window: Optional[Tuple[int, int]] = None
    sva_window_end_cycle: Optional[int] = None
    sva_consequent_signals: Optional[List[str]] = None
    
    def to_dict(self) -> Dict[str, Any]: return asdict(self)


@dataclass
class CausalGraphResult:
    """Result of causal graph construction."""
    nodes: List[Dict[str, Any]]
    edges: List[Dict[str, Any]]
    meta: CausalGraphMeta
    
    def to_dict(self) -> Dict[str, Any]:
        return {"nodes": self.nodes, "edges": self.edges, "meta": self.meta.to_dict()}


# Edge color mapping by contribution type
_EDGE_COLORS = {
    "expr_eval": "blue", "toggle": "green", "state": "purple",
    "conditional": "orange", "direct": "darkgreen", "unknown": "black"
}


class CausalGraphBuilder:
    """Constructs causal DAGs from FST waveforms and Verilog RTL."""
    
    DEFAULT_MAX_DEPTH = 20
    DEFAULT_MAX_NODES = 200
    
    def __init__(self,
                 fst_path: str,
                 verilog_paths: List[str],
                 clock_signal: str = "clock",
                 max_depth: int = DEFAULT_MAX_DEPTH,
                 max_nodes: int = DEFAULT_MAX_NODES,
                 random_seed: Optional[int] = None):
        """Initialize with FST waveform, Verilog sources, and clock signal."""
        self.fst_path = os.path.abspath(fst_path)
        self.verilog_paths = [os.path.abspath(p) for p in verilog_paths]
        self.clock_signal = clock_signal
        self.max_depth = max_depth
        self.max_nodes = max_nodes
        self.random_seed = random_seed if random_seed is not None else int(time.time())
        random.seed(self.random_seed)
        
        self._verilog_parser = VerilogParser()
        for vpath in self.verilog_paths:
            if os.path.exists(vpath):
                try:
                    self._verilog_parser.parse_file(vpath)
                except Exception as e:
                    print(f"Warning: Failed to parse {vpath}: {e}")
                    
        self._waveform = CycleAlignedWaveform(self.fst_path, self.clock_signal)
        self._slicer = BackwardSlicer(
            self._verilog_parser,
            self._waveform,
            max_depth=self.max_depth,
            max_nodes=self.max_nodes
        )
        self._result: Optional[CausalGraphResult] = None
        self._nodes: Dict[str, CausalNode] = {}
        self._edges: List[CausalEdge] = []
    
    
    def find_endpoint_signal(self, pattern: str = "assert") -> List[str]:
        """Find signals matching pattern (e.g., assertion failures)."""
        if self._waveform is None:
            raise ValueError("Waveform not available. Builder may have been closed.")
        return self._waveform.find_signal(pattern, max_results=20)
    
    def get_last_cycle(self) -> int:
        """Get the last cycle number in the waveform."""
        if self._waveform is None:
            raise ValueError("Waveform not available. Builder may have been closed.")
        return self._waveform.get_cycle_count() - 1
    
    def build(self, endpoint_signal: str, endpoint_cycle: Optional[int] = None) -> CausalGraphResult:
        """Build causal graph from endpoint signal at given cycle."""
        start_time = time.time()
        
        if endpoint_cycle is None:
            endpoint_cycle = self.get_last_cycle()
        if self._waveform is None:
            raise ValueError("Waveform not available. Builder may have been closed.")
        
        self._nodes, self._edges = self._slicer.slice_from_endpoint(endpoint_signal, endpoint_cycle)
        
        runtime = time.time() - start_time
        stats = self._slicer.get_statistics()
        
        nodes_list = [n.to_dict() for n in self._nodes.values()]
        edges_list = [e.to_dict() for e in self._edges]

        # Sort nodes by depth (endpoint first) then by cycle
        nodes_list.sort(key=lambda n: (n["depth"], -n["cycle"]))
        
        # Count root nodes
        root_count = sum(1 for n in nodes_list if n.get("is_root", False))
        
        # Build metadata
        sva_time_window = stats.get("sva_time_window")
        sva_consequent = stats.get("sva_consequent_signals")
        
        meta = CausalGraphMeta(
            fst_path=self.fst_path,
            verilog_paths=self.verilog_paths,
            clock_signal=self.clock_signal,
            endpoint_signal=endpoint_signal,
            endpoint_cycle=endpoint_cycle,
            max_depth=self.max_depth,
            max_nodes=self.max_nodes,
            generation_time=datetime.now().isoformat(),
            runtime_seconds=round(runtime, 3),
            tool_version=__version__,
            random_seed=self.random_seed,
            cycle_count=self._waveform.get_cycle_count(),
            timescale=self._waveform.timescale,
            total_nodes=len(nodes_list),
            total_edges=len(edges_list),
            root_nodes=root_count,
            max_depth_reached=stats["max_depth_reached"],
            max_nodes_reached=stats["max_nodes_reached"],
            undetermined_nodes=stats["undetermined_nodes"],
            sva_trigger_cycle=stats.get("sva_trigger_cycle"),
            sva_time_window=sva_time_window,
            sva_window_end_cycle=stats.get("sva_window_end_cycle"),
            sva_consequent_signals=list(sva_consequent) if sva_consequent else None
        )
        
        self._result = CausalGraphResult(
            nodes=nodes_list,
            edges=edges_list,
            meta=meta
        )
        return self._result
    
    def _check_result(self):
        """Ensure result exists for export."""
        if self._result is None:
            raise ValueError("No result to export. Call build() first.")

    def _require_result(self) -> CausalGraphResult:
        """Return result with a non-None guarantee."""
        self._check_result()
        assert self._result is not None
        return self._result
    
    def _ensure_dir(self, path: str) -> str:
        """Create directory for output path and return absolute path."""
        path = os.path.abspath(path)
        os.makedirs(os.path.dirname(path) or '.', exist_ok=True)
        return path
    
    def export_json(self, output_path: str, indent: int = 2) -> str:
        """Export causal graph to JSON."""
        result = self._require_result()
        output_path = self._ensure_dir(output_path)
        with open(output_path, 'w', encoding='utf-8') as f:
            json.dump(result.to_dict(), f, indent=indent, ensure_ascii=False)
        return output_path
    
    def _get_node_style(self, node: Dict) -> Dict[str, str]:
        """Get node styling attributes based on type and score."""
        if node.get("is_endpoint"):
            return {'style': 'filled', 'fillcolor': 'red', 'fontcolor': 'white'}
        if node.get("is_root"):
            return {'style': 'filled', 'fillcolor': 'green'}
        if node.get("rtl_context_missing"):
            return {'style': 'dashed', 'color': 'gray'}
        score = node.get("suspect_score", 0)
        if score > 0.7:
            return {'style': 'filled', 'fillcolor': 'orange'}
        if score > 0.4:
            return {'style': 'filled', 'fillcolor': 'yellow'}
        return {}
    
    def export_dot(self, output_path: str) -> str:
        """Export causal graph to GraphViz DOT format."""
        result = self._require_result()
        output_path = self._ensure_dir(output_path)
        
        lines = [
            'digraph CausalGraph {',
            '    rankdir=TB;',
            '    node [shape=box, fontsize=10];',
            '    edge [fontsize=8];',
            '',
            '    // Legend subgraph',
            '    subgraph cluster_legend {',
            '        label=\"Legend\";',
            '        fontsize=12;',
            '        style=dashed;',
            '        color=gray;',
            '        rank=same;',
            '',
        '        legend_endpoint [label="Endpoint\\n(assertion fail)", style=filled, fillcolor=red, fontcolor=white];',
            '        legend_root [label="Root Cause\\nCandidate", style=filled, fillcolor=green];',
            '        legend_high_suspect [label="High Suspicion\\n(score > 0.7)", style=filled, fillcolor=orange];',
            '        legend_medium_suspect [label="Medium Suspicion\\n(score > 0.4)", style=filled, fillcolor=yellow];',
            '        legend_rtl_missing [label="RTL Context\\nMissing", style=dashed, color=gray];',
            '        legend_normal [label="Normal Node"];',
            '    }',
            ''
        ]
        
        # Add nodes
        for node in result.nodes:
            label = f'{node["signal"]}\\n@{node["cycle"]}={node["value"]}'
            style_dict = self._get_node_style(node)
            style = ', '.join(f'{k}={v}' for k, v in style_dict.items())
            if style:
                lines.append(f'    "{node["id"]}" [label="{label}", {style}];')
            else:
                lines.append(f'    "{node["id"]}" [label="{label}"];')

        if result.nodes:
            anchor_id = next((n["id"] for n in result.nodes if n.get("is_endpoint")), result.nodes[0]["id"])
            lines.append(f'    "{anchor_id}" -> legend_endpoint [style=invis, constraint=false];')
        
        lines.append('')
        
        # Add edges
        for edge in result.edges:
            color = _EDGE_COLORS.get(edge.get("contribution_type", ""), "black")
            score = edge.get("contribution_score", 0)
            label = f'{edge.get("contribution_type", "")}\\n{score:.2f}'
            lines.append(f'    "{edge["src_node_id"]}" -> "{edge["dst_node_id"]}" '
                        f'[label="{label}", color={color}, penwidth={1 + score * 2}];')
        
        lines.append('}')
        
        with open(output_path, 'w', encoding='utf-8') as f:
            f.write('\n'.join(lines))
        return output_path
    
    def export_graph(self, output_path: str, format: str = 'png', dpi: int = 300) -> str:
        """Export causal graph to image (PNG, PDF, SVG, etc.) using Graphviz."""
        result = self._require_result()
        base_path = os.path.splitext(self._ensure_dir(output_path))[0]
        
        dot = graphviz.Digraph('CausalGraph', format=format, engine='dot')
        dot.attr(rankdir='BT', dpi=str(dpi))
        dot.attr('node', shape='box', fontsize='10')
        dot.attr('edge', fontsize='8')
        
        for node in result.nodes:
            label = f'{node["signal"]}\\n@{node["cycle"]}={node["value"]}'
            dot.node(node["id"], label=label, **self._get_node_style(node))
        
        for edge in result.edges:
            color = _EDGE_COLORS.get(edge.get("contribution_type", ""), "black")
            score = edge.get("contribution_score", 0)
            label = f'{edge.get("contribution_type", "")}\\n{score:.2f}'
            dot.edge(edge["src_node_id"], edge["dst_node_id"], 
                    label=label, color=color, penwidth=str(1 + score * 2))
        
        try:
            return dot.render(base_path, cleanup=True)
        except Exception as e:
            raise RuntimeError(f"Failed to render graph: {e}")
    
    def export_networkx(self, output_path: str) -> str:
        """Export edges in CSV format for NetworkX loading."""
        result = self._require_result()
        output_path = self._ensure_dir(output_path)
        lines = ['source,target,weight,type,reason']
        
        for edge in result.edges:
            src = edge["src_node_id"]
            dst = edge["dst_node_id"]
            weight = edge.get("contribution_score", 0)
            etype = edge.get("contribution_type", "unknown")
            reason = edge.get("reason", "").replace(',', ';').replace('\n', ' ')
            
            lines.append(f'{src},{dst},{weight},{etype},"{reason}"')
        
        with open(output_path, 'w', encoding='utf-8') as f:
            f.write('\n'.join(lines))
        
        return output_path
    
    def export_node_attributes(self, output_path: str) -> str:
        """Export node attributes in CSV format for NetworkX."""
        result = self._require_result()
        output_path = self._ensure_dir(output_path)
        lines = ['id,signal,cycle,value,suspect_score,is_root,is_endpoint,depth,rtl_missing']
        
        for n in result.nodes:
            lines.append(','.join(map(str, [
                n["id"], n["signal"].replace(',', ';'), n["cycle"], n["value"],
                n.get("suspect_score", 0), int(n.get("is_root", False)),
                int(n.get("is_endpoint", False)), n.get("depth", 0),
                int(n.get("rtl_context_missing", False))
            ])))
        
        with open(output_path, 'w', encoding='utf-8') as f:
            f.write('\n'.join(lines))
        return output_path
    
    def get_natural_language_summary(self) -> str:
        """Generate a natural language summary of the causal graph."""
        result = self._require_result()
        meta = result.meta
        
        # Helper to find node by id
        node_map = {n["id"]: n for n in result.nodes}
        
        summary = [
            "# Causal Graph Analysis Summary", "",
            "## Overview",
            f"- Endpoint: `{meta.endpoint_signal}` at cycle {meta.endpoint_cycle}",
            f"- Total nodes: {meta.total_nodes}",
            f"- Total edges: {meta.total_edges}",
            f"- Root cause candidates: {meta.root_nodes}",
            f"- Analysis depth: {meta.max_depth} (reached: {meta.max_depth_reached})",
            f"- Undetermined nodes (missing RTL): {meta.undetermined_nodes}", "",
            "## Root Cause Candidates",
        ]
        
        roots = sorted([n for n in result.nodes if n.get("is_root")],
                      key=lambda n: -n.get("suspect_score", 0))[:10]
        for i, r in enumerate(roots, 1):
            summary.append(f"{i}. `{r['signal']}` @ cycle {r['cycle']} = {r['value']} "
                          f"(score: {r.get('suspect_score', 0):.2f})")
        if len([n for n in result.nodes if n.get("is_root")]) > 10:
            summary.append(f"   ... and more")
        
        summary.extend(["", "## High-Suspicion Paths"])
        high_edges = sorted([e for e in result.edges if e.get("contribution_score", 0) > 0.7],
                           key=lambda e: -e.get("contribution_score", 0))[:5]
        for e in high_edges:
            src, dst = node_map.get(e["src_node_id"]), node_map.get(e["dst_node_id"])
            if src and dst:
                summary.append(f"- `{src['signal']}@{src['cycle']}` → `{dst['signal']}@{dst['cycle']}` "
                             f"(score: {e.get('contribution_score', 0):.2f}, type: {e.get('contribution_type')})")
                snippet = e.get("evidence", {}).get("code_snippet", "").strip().split('\n')[0][:60]
                if snippet:
                    summary.append(f"  RTL: `{snippet}...`")
        
        summary.extend(["", "## Generation Info",
            f"- Runtime: {meta.runtime_seconds:.3f}s",
            f"- Tool version: {meta.tool_version}",
            f"- Random seed: {meta.random_seed}",
            f"- Generated: {meta.generation_time}"])
        
        return '\n'.join(summary)
    
    def get_evidence_for_node(self, node_id: str) -> Dict[str, Any]:
        """Get detailed evidence for a specific node."""
        result = self._require_result()
        node = next((n for n in result.nodes if n["id"] == node_id), None)
        if not node:
            return {"error": f"Node not found: {node_id}"}
        
        return {
            "node": node,
            "incoming_edges": [e for e in result.edges if e["dst_node_id"] == node_id],
            "outgoing_edges": [e for e in result.edges if e["src_node_id"] == node_id],
            "rtl_refs": node.get("rtl_refs", []),
            "is_root": node.get("is_root", False),
            "is_endpoint": node.get("is_endpoint", False)
        }
    
    def close(self):
        """Clean up resources."""
        if self._waveform is not None:
            self._waveform.close()
            self._waveform = None
    
    def __enter__(self):
        return self
    
    def __exit__(self, exc_type, exc_val, exc_tb):
        self.close()
        return False


def build_causal_graph(fst_path: str,
                       verilog_paths: List[str],
                       clock_signal: str,
                       endpoint_signal: Optional[str] = None,
                       endpoint_cycle: Optional[int] = None,
                       max_depth: int = CausalGraphBuilder.DEFAULT_MAX_DEPTH,
                       max_nodes: int = CausalGraphBuilder.DEFAULT_MAX_NODES,
                       output_dir: Optional[str] = None) -> CausalGraphResult:
    """
    Convenience function to build a causal graph.
    
    Args:
        fst_path: Path to FST waveform file
        verilog_paths: List of Verilog source files
        clock_signal: Clock signal name
        endpoint_signal: Signal that triggered counterexample (auto-detect if None)
        endpoint_cycle: Cycle of trigger (last cycle if None)
        max_depth: Maximum traversal depth
        max_nodes: Maximum nodes in DAG
        output_dir: Directory to write output files (None = no file output)
        
    Returns:
        CausalGraphResult
    """
    with CausalGraphBuilder(
        fst_path=fst_path,
        verilog_paths=verilog_paths,
        clock_signal=clock_signal,
        max_depth=max_depth,
        max_nodes=max_nodes
    ) as builder:
        
        # Auto-detect endpoint if not specified
        if endpoint_signal is None:
            candidates = builder.find_endpoint_signal("assert")
            if not candidates:
                candidates = builder.find_endpoint_signal("fail")
            if not candidates:
                candidates = builder.find_endpoint_signal("error")
            if candidates:
                endpoint_signal = candidates[0]
            else:
                raise ValueError("Could not auto-detect endpoint signal. Please specify endpoint_signal.")
        
        # Build the graph
        result = builder.build(endpoint_signal, endpoint_cycle)
        
        # Export files if output directory specified
        if output_dir:
            os.makedirs(output_dir, exist_ok=True)
            builder.export_json(os.path.join(output_dir, "causal_graph.json"))
            builder.export_dot(os.path.join(output_dir, "causal_graph.dot"))
            builder.export_networkx(os.path.join(output_dir, "causal_edges.csv"))
            builder.export_node_attributes(os.path.join(output_dir, "causal_nodes.csv"))
            
            # Write summary
            summary = builder.get_natural_language_summary()
            with open(os.path.join(output_dir, "summary.md"), 'w') as f:
                f.write(summary)
        
        return result
