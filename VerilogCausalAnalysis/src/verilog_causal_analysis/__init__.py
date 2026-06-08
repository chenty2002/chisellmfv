"""
Verilog Causal Analysis - 因果分析模块

This module provides tools for building causal DAGs from FST waveforms
and Verilog RTL code to identify root causes of assertion failures.

Main components:
- CausalGraphBuilder: High-level API for building causal graphs
- VerilogParser: Parse Verilog/SystemVerilog to extract signal dependencies
- CycleAlignedWaveform: Parse FST waveforms aligned to clock cycles
- BackwardSlicer: Perform backward slicing with counterfactual evaluation

Usage:
    from verilog_causal_analysis import CausalGraphBuilder
    
    builder = CausalGraphBuilder(
        fst_path="counterexample.fst",
        verilog_paths=["design.v"],
        clock_signal="TestTop.clock"
    )
    
    result = builder.build(
        endpoint_signal="assertion_fail",
        endpoint_cycle=100
    )
    
    builder.export_json("output.json")
    builder.export_dot("output.dot")
    builder.export_graph("output.png")  # Direct image generation
"""

from .causal_graph import (
    CausalGraphBuilder,
    CausalGraphResult,
    CausalGraphMeta,
    build_causal_graph,
    __version__
)

from .verilog_parser import (
    VerilogParser,
    DependencyType,
    Dependency,
    SignalInfo,
    ModuleInfo
)

from .cycle_waveform import (
    CycleAlignedWaveform,
    SignalTransition,
    CycleSnapshot,
    parse_binary_value,
    invert_value,
    values_differ
)

from .causal_slicer import (
    BackwardSlicer,
    CausalNode,
    CausalEdge,
    ContributionType,
    ExpressionEvaluator
)

from .auto_detect import (
    build,
    extract_assertion_from_filename,
    detect_assertion_trigger_cycle,
    detect_clock_signal,
    extract_sva_assertions_from_verilog,
    get_assertion_signals_from_waveform
)

__all__ = [
    # Main API
    'CausalGraphBuilder',
    'CausalGraphResult',
    'CausalGraphMeta',
    'build_causal_graph',
    
    # Verilog parsing
    'VerilogParser',
    'DependencyType',
    'Dependency',
    'SignalInfo',
    'ModuleInfo',
    
    # Waveform parsing
    'CycleAlignedWaveform',
    'SignalTransition',
    'CycleSnapshot',
    'parse_binary_value',
    'invert_value',
    'values_differ',
    
    # Causal slicing
    'BackwardSlicer',
    'CausalNode',
    'CausalEdge',
    'ContributionType',
    'ExpressionEvaluator',
    
    # Auto-detection
    'build',
    'extract_assertion_from_filename',
    'detect_assertion_trigger_cycle',
    'detect_clock_signal',
    'extract_sva_assertions_from_verilog',
    'get_assertion_signals_from_waveform',
    
    # Version
    '__version__'
]
