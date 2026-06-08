#!/usr/bin/env python3
"""
Basic tests for verilog_causal_analysis module.
"""

import sys
import os
import tempfile

# Add src to path
sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..', 'src'))

def test_imports():
    """Test that all main classes can be imported."""
    from verilog_causal_analysis import (
        CausalGraphBuilder,
        CausalGraphResult,
        CausalGraphMeta,
        build_causal_graph,
        VerilogParser,
        DependencyType,
        Dependency,
        SignalInfo,
        ModuleInfo,
        CycleAlignedWaveform,
        SignalTransition,
        CycleSnapshot,
        parse_binary_value,
        invert_value,
        values_differ,
        BackwardSlicer,
        CausalNode,
        CausalEdge,
        ContributionType,
        ExpressionEvaluator,
        __version__
    )
    
    print(f"✓ All imports successful")
    print(f"  Version: {__version__}")


def test_verilog_parser_basic():
    """Test VerilogParser basic functionality."""
    from verilog_causal_analysis import VerilogParser
    
    parser = VerilogParser()
    assert parser is not None
    print("✓ VerilogParser instantiation successful")


def test_verilog_parser_dependencies():
    """Test direction-aware ports, control deps, and full multiline SVA capture."""
    from verilog_causal_analysis import VerilogParser, DependencyType

    source = r'''
module Child(input logic in, output logic out);
  assign out = in;
endmodule

module Parent(input logic clk, input logic a, input logic en, output logic y, output logic q);
  Child u (.in(a), .out(y));
  always_ff @(posedge clk) begin
    if (en) q <= a;
    else q <= 1'b0;
  end
  my_assert: assert property (@(posedge clk) disable iff (en)
    a |-> ##[1:20] (y && q)
  );
endmodule
'''

    with tempfile.NamedTemporaryFile('w', suffix='.sv', delete=False) as tmp:
        tmp.write(source)
        path = tmp.name

    try:
        parser = VerilogParser()
        parser.parse_file(path)

        input_port_deps = parser.get_dependencies_for_signal('u.in', 'Parent')
        assert any(
            dep.source == 'a'
            and dep.target == 'u.in'
            and dep.dep_type == DependencyType.PORT_INPUT
            for dep in input_port_deps
        )

        output_port_deps = parser.get_dependencies_for_signal('y', 'Parent')
        assert any(
            dep.source == 'u.out'
            and dep.target == 'y'
            and dep.dep_type == DependencyType.PORT_OUTPUT
            for dep in output_port_deps
        )

        q_deps = parser.get_dependencies_for_signal('q', 'Parent')
        assert any(dep.source == 'en' and dep.condition for dep in q_deps)

        sva_deps = parser.get_dependencies_for_signal('my_assert', 'Parent')
        assert any(
            '##[1:20]' in dep.expression and 'y && q' in dep.expression
            for dep in sva_deps
        )
    finally:
        os.unlink(path)

    print("✓ VerilogParser dependency extraction tests passed")


def test_sva_label_auto_detect_split_line():
    """Test SVA label extraction when label and assert property are split."""
    from verilog_causal_analysis import extract_sva_assertions_from_verilog

    source = r'''
module M(input logic clk, input logic a);
  split_label:
    assert property (@(posedge clk) a);
endmodule
'''

    with tempfile.NamedTemporaryFile('w', suffix='.sv', delete=False) as tmp:
        tmp.write(source)
        path = tmp.name

    try:
        labels = extract_sva_assertions_from_verilog([path])
        assert labels == ['split_label']
    finally:
        os.unlink(path)

    print("✓ SVA label auto-detection tests passed")


def test_expression_evaluator():
    """Test ExpressionEvaluator."""
    from verilog_causal_analysis import ExpressionEvaluator
    
    env = {
        'a': '1010',
        'b': '0011',
        'sel': '1'
    }
    
    evaluator = ExpressionEvaluator(env)
    
    # Test basic AND
    result = evaluator.evaluate('a & b')
    assert result == '0010', f"Expected '0010', got '{result}'"
    
    # Test basic OR
    result = evaluator.evaluate('a | b')
    assert result == '1011', f"Expected '1011', got '{result}'"
    
    # Test ternary
    result = evaluator.evaluate('sel ? a : b')
    assert result == '1010', f"Expected '1010', got '{result}'"
    
    print("✓ ExpressionEvaluator tests passed")


def test_utility_functions():
    """Test utility functions."""
    from verilog_causal_analysis import parse_binary_value, invert_value, values_differ
    
    # Test parse_binary_value
    assert parse_binary_value('1010') == 10
    assert parse_binary_value('0011') == 3
    assert parse_binary_value('x') is None
    
    # Test invert_value
    assert invert_value('1010') == '0101'
    assert invert_value('0') == '1'
    
    # Test values_differ
    assert values_differ('1010', '1011') == True
    assert values_differ('1010', '1010') == False
    
    print("✓ Utility function tests passed")


if __name__ == '__main__':
    print("Running verilog_causal_analysis tests...\n")
    
    test_imports()
    test_verilog_parser_basic()
    test_verilog_parser_dependencies()
    test_sva_label_auto_detect_split_line()
    test_expression_evaluator()
    test_utility_functions()
    
    print("\n✓ All tests passed!")
