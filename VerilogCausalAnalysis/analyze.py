#!/usr/bin/env python3
"""
Verilog Causal Analysis - Command Line Tool

Builds a causal DAG from FST waveform and Verilog RTL sources,
then exports to JSON, DOT, and PNG formats.

Usage:
    python analyze.py --fst <waveform.fst> --verilog <design.sv> --output <output_dir>

Auto-detection features:
    - If --endpoint is not specified: extract from FST filename
      (e.g., "philo4.System_should_..." from "philo4.System_should_....fst")
    - If --cycle is not specified: detect assertion trigger cycle from waveform
    - If --clock is not specified: find clock/clk signal from top-level module

Example (using test files with auto-detection):
    python analyze.py \
        --fst tests/philo4.System_should_not_deadlock_when_all_philosophers_are_hungry.fst \
        --verilog tests/TestTop.sv \
        --output output/philo4
"""

import argparse
import sys
import os

# Add src to path
sys.path.insert(0, os.path.join(os.path.dirname(__file__), 'src'))

from verilog_causal_analysis import *


def main():
    parser = argparse.ArgumentParser(
        description="Verilog Causal Analysis - Build causal DAG from counterexample",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
Examples:
  # Full auto-detection (clock, endpoint, cycle all auto-detected)
  python analyze.py --fst trace.fst --verilog design.sv --output results/

  # Specify clock only (endpoint and cycle auto-detected from filename/waveform)
  python analyze.py --fst trace.fst --verilog design.sv --clock clk --output results/

  # Specify endpoint signal and cycle manually
  python analyze.py --fst trace.fst --verilog design.sv --clock clk \\
      --endpoint assertion_fail --cycle 100 --output results/

  # Using test files with full auto-detection
  python analyze.py \\
      --fst tests/philo4.System_should_not_deadlock_when_all_philosophers_are_hungry.fst \\
      --verilog tests/TestTop.sv \\
      --output output/philo4
        """
    )
    
    parser.add_argument("--fst", "-f", required=True,
                        help="Path to FST waveform file")
    parser.add_argument("--verilog", "-v", required=True, nargs="+",
                        help="Path(s) to Verilog/SystemVerilog source file(s)")
    
    parser.add_argument("--clock", "-c", default=None,
                        help="Clock signal name (auto-detect from top-level module if not specified)")
    parser.add_argument("--output", "-o", default="result",
                        help="Output directory for generated files")
    parser.add_argument("--endpoint", "-e", default=None,
                        help="Endpoint signal name (auto-detect from FST filename if not specified)")
    parser.add_argument("--cycle", "-n", type=int, default=None,
                        help="Endpoint cycle number (auto-detect from assertion trigger if not specified)")
    parser.add_argument("--max-depth", "-d", type=int, default=20,
                        help="Maximum traversal depth (default: 20)")
    parser.add_argument("--max-nodes", "-m", type=int, default=200,
                        help="Maximum nodes in DAG (default: 200)")
    parser.add_argument("--format", choices=["png", "svg", "pdf"], default="png",
                        help="Image output format (default: png)")
    parser.add_argument("--dpi", type=int, default=300,
                        help="Image DPI (default: 300)")
    parser.add_argument("--list-signals", action="store_true",
                        help="List available assertion signals and exit")
    parser.add_argument("--quiet", "-q", action="store_true",
                        help="Suppress progress messages")
    
    args = parser.parse_args()
    
    def log(msg):
        if not args.quiet:
            print(msg)
    
    # Validate inputs
    if not os.path.exists(args.fst):
        print(f"Error: FST file not found: {args.fst}", file=sys.stderr)
        sys.exit(1)
    
    for vpath in args.verilog:
        if not os.path.exists(vpath):
            print(f"Error: Verilog file not found: {vpath}", file=sys.stderr)
            sys.exit(1)
    
    # List signals mode (before building)
    if args.list_signals:
        
        log("\n[*] Available assertion signals:")
        
        # Extract SVA assertions from Verilog source files
        log("\n[1] SVA Assertions from source code:")
        sva_labels = extract_sva_assertions_from_verilog(args.verilog)
        if sva_labels:
            for label in sva_labels:
                print(f"    - {label} (from SVA)")
        else:
            log("    (No SVA assertions found in source files)")
        
        # Get assertion signals from waveform
        log("\n[2] Assertion signals in waveform:")
        assertion_signals = get_assertion_signals_from_waveform(args.fst, sva_labels)
        if assertion_signals:
            for sig in assertion_signals:
                print(f"    - {sig}")
        sys.exit(0)
    
    # === Build CausalGraphBuilder with auto-detection ===
    
    try:
        builder, endpoint_signal, endpoint_cycle = build(
            fst_path=args.fst,
            verilog_paths=args.verilog,
            clock_signal=args.clock,
            endpoint_signal=args.endpoint,
            endpoint_cycle=args.cycle,
            max_depth=args.max_depth,
            max_nodes=args.max_nodes,
            quiet=args.quiet
        )
    except Exception as e:
        print(f"Error: {e}", file=sys.stderr)
        sys.exit(1)
    
    # Build causal graph
    log(f"\n[*] Building causal graph from '{endpoint_signal}' @ cycle {endpoint_cycle}...")
    try:
        result = builder.build(endpoint_signal, endpoint_cycle)
    except Exception as e:
        print(f"Error building causal graph: {e}", file=sys.stderr)
        import traceback
        traceback.print_exc()
        builder.close()
        sys.exit(1)
    
    meta = result.meta
    log(f"[+] Built graph with {meta.total_nodes} nodes, {meta.total_edges} edges")
    log(f"[+] Found {meta.root_nodes} root cause candidates")
    log(f"[+] Analysis took {meta.runtime_seconds:.3f}s")
    
    # Create output directory
    os.makedirs(args.output, exist_ok=True)
    
    # Export files
    json_path = os.path.join(args.output, "causal_graph.json")
    dot_path = os.path.join(args.output, "causal_graph.dot")
    img_path = os.path.join(args.output, f"causal_graph.{args.format}")
    summary_path = os.path.join(args.output, "summary.md")
    
    log(f"\n[*] Exporting to {args.output}/")
    
    builder.export_json(json_path)
    log(f"    - {os.path.basename(json_path)}")
    
    builder.export_dot(dot_path)
    log(f"    - {os.path.basename(dot_path)}")
    
    try:
        builder.export_graph(img_path, format=args.format, dpi=args.dpi)
        log(f"    - {os.path.basename(img_path)}")
    except Exception as e:
        log(f"    - (skipped image: {e})")
    
    # Write summary
    summary = builder.get_natural_language_summary()
    with open(summary_path, 'w', encoding='utf-8') as f:
        f.write(summary)
    log(f"    - {os.path.basename(summary_path)}")
    
    # Print summary to console
    log("\n" + "=" * 60)
    print(summary)
    
    # Print root causes
    roots = sorted([n for n in result.nodes if n.get("is_root")],
                   key=lambda n: -n.get("suspect_score", 0))[:5]
    if roots:
        log("\n" + "=" * 60)
        log("Top Root Cause Candidates:")
        for i, r in enumerate(roots, 1):
            log(f"  {i}. {r['signal']} @ cycle {r['cycle']} = {r['value']} "
                f"(score: {r.get('suspect_score', 0):.2f})")
    
    builder.close()
    log(f"\n[+] Done! Output files in: {args.output}/")
    
    return 0


if __name__ == "__main__":
    sys.exit(main())
