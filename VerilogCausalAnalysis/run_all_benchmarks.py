#!/usr/bin/env python3
"""
Batch run causal analysis on all benchmarks in tests/ directory.
For each benchmark, pick one FST waveform and generate causal graph.
"""

import os
import sys
import subprocess
import glob

# Add src to path
sys.path.insert(0, os.path.join(os.path.dirname(__file__), 'src'))

# Benchmark configurations: (benchmark_name, fst_file_pattern, verilog_file)
# We'll pick the first available FST for each benchmark
BENCHMARKS = [
    ("abp", "tests/abp/*.fst", "tests/abp/TestTop.sv"),
    ("counter", "tests/counter/*.fst", "tests/counter/TestTop.sv"),
    ("crc", "tests/crc/*.fst", "tests/crc/TestTop.sv"),
    ("gcd", "tests/gcd/*.fst", "tests/gcd/TestTop.sv"),
    ("gigamax", "tests/gigamax/*.fst", "tests/gigamax/TestTop.sv"),
    ("gray", "tests/gray/*.fst", "tests/gray/TestTop.sv"),
    ("itc99_b01", "tests/itc99_b01/*.fst", "tests/itc99_b01/TestTop.sv"),
    ("itc99_b02", "tests/itc99_b02/*.fst", "tests/itc99_b02/TestTop.sv"),
    ("lock", "tests/lock/*.fst", "tests/lock/TestTop.sv"),
    ("philo4", "tests/philo4/*.fst", "tests/philo4/TestTop.sv"),
    ("reset", "tests/reset/*.fst", "tests/reset/TestTop.sv"),
    ("short", "tests/short/*.fst", "tests/short/TestTop.sv"),
    ("swap", "tests/swap/*.fst", "tests/swap/TestTop.sv"),
]


def find_cycle_from_waveform(fst_path: str, endpoint_signal: str, clock_signal: str) -> int:
    """
    Find the cycle when assertion value is 0.
    First tries to find 1->0 transition, then looks for first 0 value.
    """
    import pylibfst
    from verilog_causal_analysis.cycle_waveform import CycleAlignedWaveform
    
    waveform = CycleAlignedWaveform(fst_path, clock_signal)
    try:
        fst = pylibfst.lib.fstReaderOpen(fst_path.encode("UTF-8"))
        if fst == pylibfst.ffi.NULL:
            raise RuntimeError(f"Failed to open FST file: {fst_path}")
        
        try:
            _, signals = pylibfst.get_scopes_signals2(fst)
            sig = signals.by_name.get(endpoint_signal)
            if not sig:
                raise RuntimeError(f"Signal not found: {endpoint_signal}")
            
            pylibfst.lib.fstReaderClrFacProcessMaskAll(fst)
            pylibfst.lib.fstReaderSetFacProcessMask(fst, sig.handle)
            
            timestamps = pylibfst.lib.fstReaderGetTimestamps(fst)
            if timestamps.nvals == 0:
                raise RuntimeError("No timestamps found")
            
            buf = pylibfst.ffi.new("char[256]")
            prev_value = None
            first_zero_time = None
            trigger_time = None
            
            for ts in range(timestamps.nvals):
                time = timestamps.val[ts]
                value = pylibfst.helpers.string(
                    pylibfst.lib.fstReaderGetValueFromHandleAtTime(
                        fst, time, sig.handle, buf
                    )
                )
                
                # Track first zero value
                if value == '0' and first_zero_time is None:
                    first_zero_time = int(time)
                
                # Detect falling edge (1 -> 0)
                if prev_value == '1' and value == '0':
                    trigger_time = int(time)
                    break
                    
                prev_value = value
            
            pylibfst.lib.fstReaderFreeTimestamps(timestamps)
            
            # Prefer 1->0 transition, fall back to first 0
            if trigger_time is not None:
                return waveform.time_to_cycle(trigger_time)
            elif first_zero_time is not None:
                return waveform.time_to_cycle(first_zero_time)
            else:
                # Default to cycle 0
                return 0
            
        finally:
            pylibfst.lib.fstReaderClose(fst)
    finally:
        waveform.close()


def run_benchmark(name: str, fst_pattern: str, verilog_path: str, output_dir: str):
    """Run analysis for a single benchmark."""
    
    # Find first available FST file
    fst_files = sorted(glob.glob(fst_pattern))
    if not fst_files:
        print(f"  [!] No FST files found for {name}, skipping...")
        return False, "No FST files"
    
    fst_path = fst_files[0]
    
    # Check Verilog exists
    if not os.path.exists(verilog_path):
        print(f"  [!] Verilog file not found: {verilog_path}")
        return False, "Verilog not found"
    
    print(f"  [*] FST: {os.path.basename(fst_path)}")
    print(f"  [*] Verilog: {verilog_path}")
    
    # Try to run with auto-detection first
    cmd = [
        sys.executable, "analyze.py",
        "--fst", fst_path,
        "--verilog", verilog_path,
        "--output", output_dir,
        "--quiet"
    ]
    
    result = subprocess.run(cmd, capture_output=True, text=True)
    
    if result.returncode == 0:
        print(f"  [+] Success with auto-detection")
        return True, None
    
    # Auto-detection failed, try manual detection
    print(f"  [*] Auto-detection failed, trying manual cycle detection...")
    
    try:
        from verilog_causal_analysis.auto_detect import (
            detect_clock_signal, 
            extract_assertion_from_filename
        )
        
        clock = detect_clock_signal(fst_path)
        endpoint = extract_assertion_from_filename(fst_path)
        cycle = find_cycle_from_waveform(fst_path, endpoint, clock)
        
        print(f"  [*] Detected: endpoint={endpoint}, cycle={cycle}")
        
        cmd = [
            sys.executable, "analyze.py",
            "--fst", fst_path,
            "--verilog", verilog_path,
            "--output", output_dir,
            "--endpoint", endpoint,
            "--cycle", str(cycle),
            "--quiet"
        ]
        
        result = subprocess.run(cmd, capture_output=True, text=True)
        
        if result.returncode == 0:
            print(f"  [+] Success with manual cycle={cycle}")
            return True, None
        else:
            print(f"  [!] Failed: {result.stderr[-500:] if result.stderr else 'Unknown error'}")
            return False, result.stderr
            
    except Exception as e:
        print(f"  [!] Exception: {e}")
        return False, str(e)


def main():
    print("=" * 60)
    print("Batch Causal Analysis for All Benchmarks")
    print("=" * 60)
    
    base_dir = os.path.dirname(os.path.abspath(__file__))
    os.chdir(base_dir)
    
    results = {}
    
    for name, fst_pattern, verilog in BENCHMARKS:
        print(f"\n[{name}]")
        output_dir = f"results/{name}"
        os.makedirs(output_dir, exist_ok=True)
        
        success, error = run_benchmark(name, fst_pattern, verilog, output_dir)
        results[name] = {"success": success, "error": error}
    
    print("\n" + "=" * 60)
    print("Summary")
    print("=" * 60)
    
    success_count = sum(1 for r in results.values() if r["success"])
    print(f"Successful: {success_count}/{len(BENCHMARKS)}")
    
    for name, result in results.items():
        status = "✓" if result["success"] else "✗"
        print(f"  {status} {name}")
    
    return 0 if success_count == len(BENCHMARKS) else 1


if __name__ == "__main__":
    sys.exit(main())
