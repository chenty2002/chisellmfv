
# Counterexample Analysis Report: ABypassCtrl

## 1. Verification Environment

- **Top Module**: `ABypassCtrl` (Chisel class in package `llmverify`)
- **Pipeline**: Dual-issue (A-side and B-side) 2-phase clocking pipeline with stages s1e → s2e → s1m → s2m → s1w
- **Clock Phases**: 
  - `io.Phi1` (Phase 1): Advances odd-to-even pipeline stages (s1m → s2m, s2e updates)
  - `Phi2 = ~io.Phi1` (Phase 2): Advances even-to-odd stages (s2e → s1m, s2m → s1w)
- **Key Pipeline Valid Registers (A-side)**:
  - `AValid_s2e`: Instruction valid in s2e stage (updated on Phi1 when not stalled)
  - `AValid_s1m`: Instruction valid in s1m stage (updated on Phi2)
  - `AValid_s2m`: Instruction valid in s2m stage (updated on Phi1 when not stalled)
  - `AValid_s1w`: Instruction valid in s1w stage (updated on Phi2) — final output
- **Stall Mechanism**: `io.Stall_s1` blocks Phi1-stage updates (freezes s2e and s2m registers), but **does not** block Phi2-stage updates (s1m and s1w registers)

## 2. Violated Assertion

- **Assertion Name**: `A_pipeline_completes_within_8`
- **Waveform File**: `ABypassCtrl.A_pipeline_completes_within_8.fst`
- **Property** (lines 239-246 of `ABypassCtrl.scala`):
  ```scala
  astRelaxedLiveness(
    io.Phi1 && !io.Stall_s1 && AValid_s2e && !io.AIgnore_s2e && !io.Except_s1w,
    AValid_s1w,
    8,
    "A_pipeline_completes_within_8")
  ```
- **Natural Language**: Whenever a valid A-side instruction enters the s2e stage (Phi1=1, no stall, not killed via AIgnore, no exception), it must propagate to AValid_s1w (final write-back stage) within 8 cycles.
- **File Location**: `log/vis_chisel_formal/vis_chisel_v2-ablation/no_causal_prior/workspace/chisel/extra_bench/torch_regfile_ABypassCtrl/ABypassCtrl.scala`, lines 239-246

## 3. Waveform Information

- **Waveform File**: `verilog/extra_bench/torch_regfile_ABypassCtrl/ABypassCtrl.A_pipeline_completes_within_8.fst`
- **Time Range**: 0 ns → 110 ns (11 cycles at 10 ns per half-cycle)
- **Clock Period**: 20 ns per full cycle (10 ns per half-cycle: Phi1 for 10 ns, Phi2 for 10 ns)

### Critical Time Points and Signal Values

| Time (ns) | Phase | Phi1 | Stall | AValid_s2e | AIgnore | AValid_s1m | AValid_s2m | AValid_s1w | Event |
|-----------|-------|------|-------|------------|---------|------------|------------|------------|-------|
| 0 | Phi1 | 1 | 0 | 0 | 0 | 0 | 0 | 0 | Initial state |
| 10 | Phi1 | 1 | 0 | **1** | 0 | 0 | 0 | 0 | **Trigger fires!** |
| 20 | Phi2 | 0 | 0 | 1 | 0 | 0 | 0 | 0 | Phi2 update about to execute |
| 30 | Phi1 | 1 | **1** | 1 | **1** | **1** | 0 | 0 | Stall+AIgnore assert; s1m gets valid |
| 40 | Phi1 | 1 | 1 | 1 | 1 | 1 | 0 | 0 | Stall holds s1m→s2m blocked |
| 50 | Phi1 | 1 | 1 | 1 | 1 | 1 | 0 | 0 | Still stalled |
| 60 | **Phi2** | 0 | **0** | 1 | 1 | **1→0** | 0 | 0 | **BUG: Phi2 overwrites AValid_s1m=0** |
| 70 | Phi1 | 1 | 0 | 1 | 0 | **0** | **0** | 0 | s1m=0 → s2m stays 0 |
| 80 | Phi2 | 0 | 0 | **0** | 0 | 0 | 0 | 0 | Pipeline drains, AValid_s1w never reaches 1 |

## 4. Root Cause Analysis

### Error Type: **Design Bug (dut_bug)**

### Location of Bug

**File**: `ABypassCtrl.scala`, lines 130-134  
```scala
when(Phi2) {
    AValid_s1m := AValid_s2e & ~io.AIgnore_s2e   // ← BUG: overwrites during stall
    AValid_s1w := AValid_s2m
    ...
}
```

### Description of the Bug

The design uses a 2-phase clocking scheme where:
1. **Phi1** (when not stalled): Advances s1m→s2m and updates s2e
2. **Phi2** (unconditionally): Advances s2e→s1m and s2m→s1w

**The bug**: The Phi2 update `AValid_s1m := AValid_s2e & ~io.AIgnore_s2e` is **unconditional** — it executes every Phi2 cycle regardless of whether the pipeline is stalled. When the pipeline is stalled (`Stall_s1=1`), the Phi1 update of `AValid_s2m := AValid_s1m` is correctly blocked, but the Phi2 update still fires, **overwriting** AValid_s1m with the current value from s2e.

### Detailed Failure Trace

1. **t=10 (Phi1, not stalled)**: Trigger condition is true — a valid instruction arrives at s2e (`AValid_s2e=1`, `AIgnore=0`, `Except=0`, no stall).
2. **t=20 (Phi2)**: `AValid_s1m := AValid_s2e & ~AIgnore = 1 & ~0 = 1` (visible at t=30).
3. **t=30 (Phi1)**: `Stall_s1` goes high AND `AIgnore_s2e` goes high. The stall blocks `AValid_s2m := AValid_s1m`, so the instruction is stuck in s1m.
4. **t=30-50 (stalled)**: Pipeline is stalled. The instruction at AValid_s1m=1 is waiting to advance to s2m.
5. **t=60 (Phi2, Stall deasserted but AIgnore still high)**: The Phi2 block executes: `AValid_s1m := AValid_s2e & ~AIgnore = 1 & ~1 = 0`. **This clears AValid_s1m!** The instruction's valid bit is destroyed.
6. **t=70 (Phi1, not stalled)**: Now that Stall is 0 and AIgnore is 0, the pipeline tries to advance: `AValid_s2m := AValid_s1m = 0`. The instruction is lost.
7. **t=80 onward**: AValid_s1w stays 0 forever. The assertion fails because the trigger was at t=10 and AValid_s1w never becomes 1.

### Why This Is a Design Bug

In a proper pipeline with stalls:
- Pipeline registers holding valid bits should **preserve their state** during stalls
- The Phi2 update of s1m from s2e should only happen when the pipeline is advancing, not unconditionally

The current logic:
```scala
when(Phi2) {
    AValid_s1m := AValid_s2e & ~io.AIgnore_s2e   // OVERWRITES s1m every Phi2
}
```

This creates a violation where a change in `AIgnore_s2e` (which is an upstream s2e signal) **retroactively kills** an instruction that has already propagated to s1m. During a stall, the instruction in s1m should be independent of changes in s2e.

### Suggested Fix

The Phi2 update of `AValid_s1m` should be gated to prevent overwriting a stalled instruction. For example:

```scala
when(Phi2) {
    // Only update s1m from s2e when s1m is empty or not stalled
    // If stalled with a valid instruction, preserve the existing value
    AValid_s1m := Mux(io.Stall_s1 && AValid_s1m, AValid_s1m, AValid_s2e & ~io.AIgnore_s2e)
    AValid_s1w := AValid_s2m
    ...
}
```

Alternatively, gate the entire Phi2 update for s1m by the stall condition:
```scala
when(Phi2 && !io.Stall_s1) {
    AValid_s1m := AValid_s2e & ~io.AIgnore_s2e
    ...
}
```

Both approaches ensure that `AValid_s1m` retains its value during a stall, preventing the loss of a valid instruction that is waiting to propagate to s2m.
