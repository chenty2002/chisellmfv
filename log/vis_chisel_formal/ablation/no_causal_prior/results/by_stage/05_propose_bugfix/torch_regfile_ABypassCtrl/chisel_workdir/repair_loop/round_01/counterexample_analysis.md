# Counterexample Analysis: A_pipeline_completes_within_8

## 1. Verification Environment

- **Top Module**: `ABypassCtrl` (package `llmverify`)
- **Source File**: `ABypassCtrl.scala`
- **Design Under Test**: A 4-stage A-side pipeline (s2e → s1m → s2m → s1w) with bypass control logic, operating on a 2-phase clock (Phi1/Phi2). The pipeline tracks valid instructions as they propagate through stages, with kill, ignore, and exception signals at various stages.

### Key Pipeline Stages
| Stage | Register | Update Clock | Update Logic |
|-------|----------|-------------|-------------|
| s2e   | AValid_s2e | Phi1 & ~Stall_s1 | `~(AKill_s1e \| ANoDest_s1e \| Except_s1w)` |
| s1m   | AValid_s1m | Phi2 | `AValid_s2e & ~AIgnore_s2e` |
| s2m   | AValid_s2m | Phi1 & ~Stall_s1 | `AValid_s1m & ~Except_s1w` |
| s1w   | AValid_s1w | Phi2 | `AValid_s2m` |

## 2. Violated Assertion

- **Assertion Name**: `A_pipeline_completes_within_8`
- **Waveform File**: `verilog/extra_bench/torch_regfile_ABypassCtrl/ABypassCtrl.A_pipeline_completes_within_8.fst`

### Code Snippet (ABypassCtrl.scala, lines ~186-192)
```scala
astRelaxedLiveness(
  io.Phi1 && !io.Stall_s1 && AValid_s2e && !io.AIgnore_s2e && !io.Except_s1w,
  AValid_s1w,
  8,
  "A_pipeline_completes_within_8")
```

### Natural Language Property
> When a valid A-side instruction enters the s2e stage (at Phi1 rising edge, no stall, not killed, not ignored, no exception at the current cycle), it must propagate to the writeback stage (AValid_s1w) within 8 clock cycles.

### File Location
- **File**: `ABypassCtrl.scala`
- **Line**: ~188-192

## 3. Waveform Information

- **Full Path**: `verilog/extra_bench/torch_regfile_ABypassCtrl/ABypassCtrl.A_pipeline_completes_within_8.fst`
- **Duration**: 0 ns → 110 ns (11 cycles)
- **Failure Point**: The assertion signal `ABypassCtrl.A_pipeline_completes_within_8` transitions from 1 to 0 at **t=100 ns** (8 cycles after trigger at t=10 ns)

### Critical Time Points

| Time (ns) | Event |
|-----------|-------|
| 0         | Initial state; Phi1=1, all pipeline registers=0 |
| 10        | **Trigger fires**: Phi1=1, Stall_s1=0, AValid_s2e=1, AIgnore_s2e=0, Except_s1w=0 |
| 40        | Except_s1w rises to 1; Phi1 falls (Phi2 rises) |
| 50        | **Pipeline blocked**: Phi1=1, AValid_s1m=1, but AValid_s2m stays 0 because Except_s1w=1 gates the update |
| 60        | AValid_s2e falls to 0; pipeline never recovers |
| 100       | **Assertion fails**: AValid_s1w never becomes 1 within 8 cycles |

### Key Signal Values at Failure Points

| Signal | t=10 (trigger) | t=50 (blocked) | t=60 | t=100 |
|--------|---------------|----------------|------|-------|
| io.Phi1 | 1 | 1 | 0 | 0 |
| io.Stall_s1 | 0 | 0 | - | - |
| io.Except_s1w | 0 | **1** | **1** | 0 |
| AValid_s2e | 1 | 1 | 0 | 0 |
| AValid_s1m | 0 | **1** | **1** | 0 |
| AValid_s2m | 0 | **0** | **0** | 0 |
| AValid_s1w | 0 | 0 | 0 | **0** |
| io.AIgnore_s2e | 0 | 0 | - | - |

## 4. Root Cause Analysis

### Bug Location
- **File**: `ABypassCtrl.scala`
- **Lines**: ~65-66 (around line 66)
- **Module**: `ABypassCtrl`

### Bug Description

**The bug is in the pipeline's s1m→s2m transition logic.** In `ABypassCtrl.scala` at approximately line 66:

```scala
AValid_s2m := AValid_s1m & ~io.Except_s1w
```

This update occurs on `when(io.Phi1 & ~io.Stall_s1)`. The `~io.Except_s1w` gating means that if an exception signal (`io.Except_s1w`) arrives at the writeback stage **after** a valid instruction has already entered the pipeline, the in-flight instruction is killed at the s2m stage and never reaches writeback.

### Evidence from Waveform

1. **t=10**: The assertion trigger fires correctly — `io.Phi1=1`, `io.Stall_s1=0`, `AValid_s2e=1` (indicating a valid instruction just entered s2e), `io.AIgnore_s2e=0`, `io.Except_s1w=0`.

2. **t=40-50**: The first pipeline transition (s2e→s1m) succeeds on Phi2: `AValid_s1m := AValid_s2e & ~io.AIgnore_s2e = 1 & ~0 = 1`. AValid_s1m becomes 1 at t=50.

3. **t=50**: The second pipeline transition (s1m→s2m) is attempted on Phi1: `AValid_s2m := AValid_s1m & ~io.Except_s1w`. At this exact time, `io.Except_s1w = 1`, so the computation yields `1 & ~1 = 0`. **AValid_s2m remains 0.**

4. **t=50-100**: Since AValid_s2m never becomes 1, the subsequent Phi2 update `AValid_s1w := AValid_s2m` never sets AValid_s1w to 1. The pipeline is deadlocked for this instruction.

5. **t=100**: 8 cycles after the trigger fired at t=10, AValid_s1w is still 0, so the assertion `A_pipeline_completes_within_8` fails.

### Why This Is a Design Bug

The assertion's trigger correctly checks `!io.Except_s1w` at the **moment of pipeline entry** — ensuring no exception is present when the instruction enters. The intent is clear: once a valid instruction enters the pipeline without exceptions, it should complete to writeback. However, the design permits `io.Except_s1w` to **kill in-flight instructions** at the s2m stage, even though those instructions entered when there was no exception.

The pipeline logic has **two distinct uses** of `io.Except_s1w`:
1. **At entry** (`AValid_s2e := ~(AKill_s1e | ANoDest_s1e | Except_s1w)`): Prevents new entries during exceptions. ✓ Correct.
2. **At s2m update** (`AValid_s2m := AValid_s1m & ~io.Except_s1w`): Kills in-flight instructions when an exception arrives later. ✗ **Bug** — violates the pipeline completion guarantee.

The fix is to remove the `& ~io.Except_s1w` gating from the s2m update:
```scala
// Buggy:
AValid_s2m := AValid_s1m & ~io.Except_s1w

// Fixed:
AValid_s2m := AValid_s1m
```

### Categorization

This is a **design bug** (`dut_bug`). The assertion correctly specifies the desired pipeline behavior (once entered, an instruction should complete), but the design incorrectly allows a late-arriving exception signal to kill in-flight instructions mid-pipeline.
