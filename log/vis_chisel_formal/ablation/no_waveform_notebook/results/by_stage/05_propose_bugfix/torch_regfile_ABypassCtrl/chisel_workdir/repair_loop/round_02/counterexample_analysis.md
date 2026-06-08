# Counterexample Analysis Report: ABypassCtrl.A_valid_pipeline_progress

## 1. Verification Environment

- **Top module**: `ABypassCtrl` (in package `llmverify`)
- **Source file**: `ABypassCtrl.scala` (244 lines)
- **Generated Verilog**: `ABypassCtrl.sv` (SystemVerilog)
- **Module type**: Pipeline bypass control for a two-stage A-side/B-side register file with stall, kill, exception, and boost logic
- **Key pipeline stages**: s2e → s1m → s2m → s1w
  - s2e → s1m transition on Phi2 (`~io.Phi1`)
  - s1m → s2m transition on `io.Phi1 & ~io.Stall_s1`
  - s2m → s1w transition on Phi2 (`~io.Phi1`)

## 2. Violated Assertion

- **Assertion name**: `A_valid_pipeline_progress` (from `ABypassCtrl.A_valid_pipeline_progress.fst`)
- **File**: `ABypassCtrl.scala`, lines 233-238
- **Code**:
  ```scala
  astRelaxedLiveness(
    io.Phi1 & ~io.Stall_s1 & AValid_s2e & ~io.AIgnore_s2e,
    AValid_s1w,
    20,
    "A_valid_pipeline_progress"
  )
  ```
- **Property**: When the pipeline advances (`io.Phi1 & ~io.Stall_s1`) with a valid A-side instruction in the s2e stage that is not being ignored (`AValid_s2e & ~io.AIgnore_s2e`), then `AValid_s1w` must become true within 20 clock cycles.
- **Liveness implementation** (`assertBoundedResponse` in `Formal.scala`):
  - A `pending` register is set when the trigger fires and stays set until `AValid_s1w` becomes true.
  - A `timer` register increments each cycle while `pending` is true and `AValid_s1w` is false.
  - The assertion checks `timer + 1 <= 20` (i.e., timer must stay ≤ 19).
  - When `timer = 20`, `timer + 1 = 21 > 20`, and the assertion fails.

## 3. Waveform Information

- **Waveform file**: `verilog/extra_bench/torch_regfile_ABypassCtrl/ABypassCtrl.A_valid_pipeline_progress.fst`
- **Duration**: 0–230 ns (23 clock cycles at 10 ns/cycle)
- **Key time points**:

| Time | Event | Key Signals |
|------|-------|-------------|
| 0 ns | Initial state | Phi1=1, Stall=0, AValid_s2e=0, AIgnore=1, pending=0, timer=0 |
| 10 ns | **Trigger fires** | AValid_s2e→1, AIgnore→0, nextPending→1 |
| 20 ns | pending becomes 1 | Timer starts incrementing |
| 30 ns | Phi1→0 | AValid_s1m←AValid_s2e&~AIgnore=1 (Phi2 update) |
| 40 ns | AValid_s1m→1 | Visible after Phi2 update at time 30 |
| 50 ns | AValid_s2m→1 | Phi1 update propagates AValid_s1m to s2m |
| 60–70 ns | Stall_s1=1 | Pipeline stalled on Phi1 phase |
| 70 ns | Except_s1w→1 | Kills AValid_s2e and AValid_s2m at _GEN update |
| 80 ns | AValid_s2e=0, AValid_s2m=0 | Exception took effect; AValid_s1w gets 0 at Phi2 |
| 90–100 ns | AValid_s2e→1 again | New instruction arrives at s2e |
| 110–120 ns | Stall_s1=1 again | Pipeline stalled |
| 130 ns | AIgnore_s2e→1 | Kills AValid_s1m at Phi2 update (AValid_s1m=0) |
| 140 ns | AIgnore_s2e→0 | AValid_s1m starts recovering |
| 150 ns | AValid_s1m→1 | Phi2 update recovers from ignore |
| 160 ns | _GEN=1 | AValid_s2m←AValid_s1m=1 |
| 170 ns | AValid_s2m→1 | Finally propagated to s2m |
| 180 ns | Except_s1w→1 again | Kills AValid_s2m again |
| 190 ns | Except_s1w→0 | AValid_s2m recovers |
| 200 ns | AValid_s2m→1 | Back to s2m valid |
| **220 ns** | **Assertion FAILS** | timer=20, nextTimer=21 > 20; AValid_s1w=0 (Phi2 update will set it to 1 after the edge) |

- **Failure point**: At posedge clock time 220 ns:
  - `pending = 1`, `AValid_s1w = 0`, `timer = 20` (binary 10100)
  - `_nextTimer_T_1 = 1` (pending & ~AValid_s1w)
  - `_nextTimer_T_2 = 21` (timer + 1)
  - Assertion check: `~1 | (1 ? 21 : 0) < 21` = `0 | 0` = **0** → FAIL
  - `AValid_s1w` becomes 1 **after** this posedge (via non-blocking `AValid_s1w <= AValid_s2m` in the Phi2 block), but one cycle too late for the 20-cycle bound.

## 4. Root Cause Analysis

### Root Cause Category: **Assertion Error** – the 20-cycle liveness bound is insufficient for worst-case pipeline scenarios involving exceptions, ignores, and stalls.

### Detailed Explanation

The liveness assertion `astRelaxedLiveness` monitors forward progress from s2e to s1w. The trigger fires at **cycle 1** (time 10 ns) when a valid instruction is present in AValid_s2e during a pipeline advance. The timer starts ticking at **cycle 2** and counts every cycle until `AValid_s1w` becomes true.

**The pipeline does eventually make progress** — `AValid_s1w` becomes 1 at cycle 22 (time 220 ns, after the posedge non-blocking assignment). However, the 20-cycle bound is exceeded by 1 cycle.

### What delays the pipeline?

The pipeline path s2e→s1m→s2m→s1w takes at most 3 transitions in the ideal case. But in this counterexample, four separate hazards occur in sequence:

1. **Cycle 7 (time 70 ns)**: `io.Except_s1w = 1` kills `AValid_s2m` (which had just been set at cycle 5) via the logic `AValid_s2m := AValid_s1m & ~io.Except_s1w`. This prevents AValid_s2m from propagating to AValid_s1w at the next Phi2 edge (cycle 8).

2. **Cycle 11 (time 110 ns)**: `io.Stall_s1 = 1` prevents the `_GEN` block from executing, delaying the s1m→s2m transition of the new instruction that arrived at s2e at cycle 10.

3. **Cycle 13 (time 130 ns)**: `io.AIgnore_s2e = 1` kills `AValid_s1m` at the Phi2 update via `AValid_s1m := AValid_s2e & ~io.AIgnore_s2e`. This forces AValid_s1m to 0, requiring a recovery cycle.

4. **Cycle 18 (time 180 ns)**: `io.Except_s1w = 1` again kills `AValid_s2m` for the second time, just after it had finally propagated through at cycle 17.

**Total delay**: 21 cycles from trigger (cycle 1) to target (after posedge cycle 21), exceeding the 20-cycle bound by 1 cycle.

### Key Insight: The timer doesn't reset when the instruction is killed

The liveness timer's `pending` register, once set by the trigger, cannot be cleared except by `AValid_s1w` becoming true. When an exception kills the instruction at s2m (cycle 7), `AValid_s1w` will never become 1 for that original instruction. A new instruction arrives at cycle 10 and eventually makes progress, but the timer is still counting from the original trigger — there is no mechanism to reset the timer when the tracked instruction is killed.

### Why this is an assertion error, not a DUT bug

1. The pipeline logic correctly implements exception handling (killing valid bits) — this is intentional architectural behavior.
2. The AIgnore mechanism is correctly implemented — it prevents ignored instructions from propagating.
3. The stall mechanism is correctly implemented.
4. The pipeline **does make forward progress** (AValid_s1w becomes 1), just not within the 20-cycle bound due to the cumulative delay from multiple hazards.

The assertion bound of 20 cycles is too tight. The comment in the source code says *"we allow up to 20 cycles to account for stalls and phase alignment"* but does not account for worst-case scenarios combining **exceptions + stalls + ignores**. The bound should be increased (e.g., to 30 cycles) or the trigger condition should additionally guard against `io.Except_s1w`.

### Suggested Fix

Increase the liveness bound from 20 to a more generous value (e.g., 30 or 40) to accommodate worst-case combinations of pipeline hazards:

```scala
astRelaxedLiveness(
  io.Phi1 & ~io.Stall_s1 & AValid_s2e & ~io.AIgnore_s2e,
  AValid_s1w,
  30,  // increased from 20
  "A_valid_pipeline_progress"
)
```

Alternatively, guard the trigger condition against exceptions:

```scala
astRelaxedLiveness(
  io.Phi1 & ~io.Stall_s1 & AValid_s2e & ~io.AIgnore_s2e & ~io.Except_s1w,
  AValid_s1w,
  20,
  "A_valid_pipeline_progress"
)
```

This second approach would cause the timer to reset and restart when exceptions clear the pipeline, which better reflects the intended property that a non-killed instruction should make forward progress.
