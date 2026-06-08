# Counterexample Analysis Report: A_valid_pipeline_progress

## 1. Verification Environment

- **Top Module**: `ABypassCtrl` (extends `Module with Formal`)
- **Design**: A two-phase pipeline bypass control unit for a register file with A-side and B-side instruction tracking
- **Key Components**:
  - Pipeline registers: `AValid_s2e`, `AValid_s1m`, `AValid_s2m`, `AValid_s1w` (4-stage A-side pipeline)
  - Clock phases: `io.Phi1` (phase 1) and `Phi2 = ~io.Phi1` (phase 2)
  - Stall inputs: `io.Stall_s1`, `io.IStall_s1`, `io.MemStall_s1`
  - Kill/ignore signals: `io.AKill_s1e`, `io.AIgnore_s2e`
  - Exception: `io.Except_s1w`
- **Pipeline Flow**:
  - `when(io.Phi1 & ~io.Stall_s1)`: `AValid_s2e` and `AValid_s2m` updated
  - `when(Phi2)`: `AValid_s1m` and `AValid_s1w` updated
  - Path: `s2e → (Phi2) → s1m → (Phi1 & ~Stall) → s2m → (Phi2) → s1w`

## 2. Violated Assertion

- **Assertion Name**: `A_valid_pipeline_progress` (from waveform filename `ABypassCtrl.A_valid_pipeline_progress.fst`)
- **File Location**: `ABypassCtrl.scala`, lines 217-221 (end of file)
- **Code Snippet**:
  ```scala
  astRelaxedLiveness(
      io.Phi1 & ~io.Stall_s1 & AValid_s2e & ~io.AIgnore_s2e,
      AValid_s1w,
      30,
      "A_valid_pipeline_progress"
  )
  ```
- **Description**: This is a **liveness** property. When the pipeline advances on Phi1 (not stalled) with a valid instruction at the s2e stage that is not being ignored, the valid signal must propagate to the writeback stage (`AValid_s1w`) within 30 clock cycles.

## 3. Waveform Information

- **Waveform File**: `verilog/extra_bench/torch_regfile_ABypassCtrl/ABypassCtrl.A_valid_pipeline_progress.fst`
- **Time Range**: 0 ns → 330 ns
- **Failure Time**: 320 ns (timer reaches the bound of 30)
- **Key Time Points**:

| Time (ns) | Event |
|-----------|-------|
| 0 | Formal reset released; Phi1=1, Stall=0, AIgnore=1 |
| 10 | `AValid_s2e` becomes 1; AIgnore falls to 0. **Trigger fires**: Phi1=1, Stall=0, AValid_s2e=1, AIgnore=0 |
| 20 | `nextPending` → `pending` becomes 1 (liveness tracking starts) |
| 30 | Timer begins counting up (0→1→2→...) |
| 40 | Phi1=1 but Stall=1 → pipeline advance blocked for s2m update |
| 50 | Phi1=0, AIgnore=1 → kills `AValid_s1m` on Phi2 update |
| 60 | AValid_s1m=0, trigger fires again (but pending already set) |
| 90-110 | Pipeline advances: AValid_s1m becomes 1... but AIgnore=1 kills it again |
| 140-170 | Pipeline advances: AValid_s1m=1... but Stall=1 blocks s2m update |
| 280-300 | Phi2 active: AValid_s1m becomes 1 (value appears at time 290) |
| **300** | **Phi1=1, Stall=0: `AValid_s2m` is written with `AValid_s1m & ~Except = 1`** |
| **310** | **`AValid_s2m` becomes 1. But Phi1 stays 1, so Phi2=0 → `AValid_s1w` cannot be updated** |
| **320** | **Timer reaches 30 (11110 binary = 30 decimal). Assertion fails.** |

- **Critical Signal Values at Failure (time 320 ns)**:
  - `AValid_s2e` = 1
  - `AValid_s1m` = 1
  - `AValid_s2m` = 1
  - `AValid_s1w` = **0** ← target never reached
  - `io_Phi1` = 1 (stuck high)
  - `io_Stall_s1` = 0
  - `io_AIgnore_s2e` = 0
  - `pending` = 1 (never cleared)
  - `timer` = 30 (11110 binary)

## 4. Root Cause Analysis

### Root Cause Category: **Setup Error** (Insufficient Input Constraints)

### Detailed Explanation

The liveness assertion fails because the formal verification tool found a scenario where the input signal **`io.Phi1` gets stuck at logic 1 after time 300 ns**, preventing the pipeline's final stage transition from completing.

#### Mechanism of Failure

The pipeline has a two-phase structure:
1. **Phase 1** (`when(io.Phi1 & ~io.Stall_s1)`): Updates `AValid_s2e` and `AValid_s2m`
2. **Phase 2** (`when(Phi2)` where `Phi2 = ~io.Phi1`): Updates `AValid_s1m` and `AValid_s1w`

For a valid instruction at s2e to reach writeback (s1w), the pipeline must progress through:
```
s2e → [on Phi2] → s1m → [on Phi1&~Stall] → s2m → [on Phi2] → s1w
```

In the counterexample:
1. The trigger fires at time 10 (Phi1=1, Stall=0, AValid_s2e=1, AIgnore=0), setting `pending=1`.
2. The pipeline experiences multiple stalls (`io.Stall_s1=1` at times 40-50, 140-170), AIgnore events (AIgnore=1 at times 50-60, 100-110, 260-270), and long Phi1=0 periods (170-270), all of which delay forward progress but are legitimate pipeline hazards.
3. **At time 300**: The conditions are finally right — `Phi1=1, Stall=0, AValid_s1m=1`. The `when(Phi1 & ~Stall)` block updates `AValid_s2m := AValid_s1m & ~Except = 1`.
4. **At time 310**: `AValid_s2m` becomes 1. The pipeline now needs a Phi2 edge (`Phi1=0`) to copy `AValid_s2m` to `AValid_s1w`.
5. **However, `io.Phi1` remains at 1 from time 300 until at least the end of the trace (330 ns)**, which means `Phi2 = ~Phi1 = 0` the entire time.
6. Since `when(Phi2)` never fires, `AValid_s1w := AValid_s2m` never executes, and `AValid_s1w` stays 0 forever.
7. The liveness timer reaches 30 at time 320 ns, and the assertion fails.

#### Why this is a Setup Error

`io.Phi1` is a **primary input** to the module with no constraints or assumptions restricting its behavior. In a real hardware design, `io.Phi1` and `Phi2 = ~io.Phi1` are non-overlapping clock phases that **must alternate** for the pipeline to function. The formal tool is free to set `io.Phi1=1` permanently because no fairness constraint or assumption prevents it.

The assertion itself is correctly specified: it checks that once a valid instruction enters the pipeline (trigger), it eventually reaches writeback (target). The problem is that the **verification environment lacks constraints** to ensure realistic clock phase behavior.

**Evidence from Waveform**:
- The formal clock (`ABypassCtrl.:jasper_formal_clock`) toggles normally throughout the trace (period = 10 ns, from 0 to 330 ns)
- But `io.Phi1` has its last transition at time 300 (0→1) with no further toggling
- `AValid_s2m` becomes 1 at time 310 — one cycle before the assertion fails — but `AValid_s1w` cannot receive it because `Phi2` is never active

#### What Would Fix This

The test harness/environment should add **assumptions** about `io.Phi1` behavior, such as:
1. **Toggle assumption**: `io.Phi1` should alternate regularly (e.g., `assume(past(io.Phi1) =/= io.Phi1)` every N cycles)
2. **Fairness constraint**: `io.Phi1` should be 0 infinitely often

With such constraints, the formal tool would not be able to keep `io.Phi1=1` permanently, and the counterexample would be blocked. Under realistic Phi1 toggling, `AValid_s1w` would become 1 within 1-2 cycles after `AValid_s2m` becomes 1 (the next Phi2 edge), which is well within the 30-cycle bound.

### Summary

| Category | Assessment |
|----------|-----------|
| **Design Bug?** | No. The pipeline logic correctly implements the bypass control functionality. |
| **Assertion Error?** | No. The assertion correctly captures the desired liveness property. |
| **Setup Error?** | **Yes.** Missing constraints on `io.Phi1` allow the formal tool to create an unrealistic scenario where the clock phase gets stuck high, preventing pipeline completion. |
