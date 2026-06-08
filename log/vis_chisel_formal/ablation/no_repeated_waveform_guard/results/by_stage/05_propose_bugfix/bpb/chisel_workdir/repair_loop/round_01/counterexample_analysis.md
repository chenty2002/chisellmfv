# Counterexample Analysis Report: `prediction_stable_when_stalled`

## 1. Verification Environment

- **Top Module**: `branchPredictionBuffer` (Chisel, in package `llmverify`)
- **Parameters**: `PRED_BUFFER_SIZE = 4`
- **Components**:
  - **State Banks**: Four 2-bit saturating counter banks (`state_bank0`–`state_bank3`), each with 4 entries, initialized to `01` (weak not-taken)
  - **Prediction Register**: A 4-bit register (`prediction`) that stores the combined prediction output
  - **Update Logic**: When `io.update` is asserted, the state bank selected by `io.buffer_offset` is incremented (if `io.branch_result=1`) or decremented (if `io.branch_result=0`)
  - **Formal Assertions**: Multiple assertions for safety (saturation bounds, prediction stability, mutual exclusion, no-wrap, liveness)
- **Design Under Test**: A branch prediction buffer with 4 parallel 2-bit saturating counters, providing a 4-bit prediction vector.

## 2. Violated Assertion

- **Assertion Name**: `prediction_stable_when_stalled`
- **Assertion Type**: `assertStableWhen` from the chiselFv library
- **Code Snippet** (from `bpbs.scala`, lines 131–132):
  ```scala
  // ---- Safety: Prediction register stability when stalled ----
  assertStableWhen(io.stall, prediction, "prediction_stable_when_stalled")
  ```
- **Natural Language Property**: "When the stall signal is asserted, the prediction register must retain its value from the previous clock cycle. The prediction output should not change while the pipeline is stalled."
- **File Location**: `bpbs.scala`, line 132

## 3. Waveform Information

- **Waveform File**: `verilog/extra_bench/bpb/branchPredictionBuffer.prediction_stable_when_stalled.fst`
- **Time Range**: 0 ns → 30 ns (3 clock cycles, clock period = 10 ns)
- **Key Time Points**:

| Time | Event | Key Values |
|------|-------|------------|
| 0 ns | **Cycle 0 posedge** | `io_stall=0`, `io_update=1`, `branch_result=1`, `prediction=0000`, `state_bank0_0=01` |
| 5 ns | Clock falling edge | |
| 10 ns | **Cycle 1 posedge** | `io_stall=0`, `prediction=0000→0001` (loaded from register enable), `state_bank0_0=10` (incremented from 01→10) |
| 15 ns | Clock falling edge | |
| 20 ns | **Cycle 2 posedge — FAILURE** | `io_stall=1`, `prediction=0001`, `assertion fires: 0` |
| 25 ns | Clock falling edge | |

**Critical Signal Values at Failure Point (time 20 ns):**
- `io_stall` = 1
- `prediction [3:0]` = 0001
- `io_prediction [3:0]` = 0001
- `prediction_stable_when_stalled` = 0 (assertion violated)

## 4. Root Cause Analysis

### Bug Location
- **File**: `bpbs.scala`
- **Line**: 70–82 (the `when (!io.stall)` block)
- **Module**: `branchPredictionBuffer`

### Description of the Bug

The design uses a `when (!io.stall)` block to gate the prediction register updates:

```scala
when (!io.stall) {
    val pred3 = Mux(state_bank3(io.inst_addr) > 1.U, 1.U, 0.U)
    val pred2 = Mux(state_bank2(io.inst_addr) > 1.U, 1.U, 0.U)
    val pred1 = Mux(state_bank1(io.inst_addr) > 1.U, 1.U, 0.U)
    val pred0 = Mux(state_bank0(io.inst_addr) > 1.U, 1.U, 0.U)
    prediction := Cat(pred3, pred2, pred1, pred0)
}
```

### Mechanism of Failure

The assertion `assertStableWhen(io.stall, prediction)` is translated internally to:

```
!io.stall || (prediction === RegNext(prediction))
```

(equivalently: `io.stall |-> $stable(prediction)`)

**Step-by-step trace:**

1. **Cycle 0 (time 0 ns, posedge)**: `io_stall=0`, so the prediction register's enable is high. The register evaluates:
   - `state_bank0(0)=01` (1), all other banks also `01`
   - All four predictors produce `0` (`1 > 1` is false)
   - `prediction` gets loaded with `0000` (which was already its initial value)

   Simultaneously, `io_update=1` and `io_branch_result=1` cause `state_bank0(0)` to increment from `01` to `10` (2).

2. **Cycle 1 (time 10 ns, posedge)**: `io_stall=0`, so the prediction register's enable is still high. The register evaluates:
   - `state_bank0(0)` is now `10` (2, was incremented in cycle 0)
   - `pred0 = Mux(2 > 1, 1, 0) = 1` (bank0 indicates taken)
   - `pred1 = Mux(1 > 1, 1, 0) = 0`
   - `pred2 = Mux(1 > 1, 1, 0) = 0`
   - `pred3 = Mux(1 > 1, 1, 0) = 0`
   - `prediction` gets loaded with `0001` (changed from `0000`)

3. **Cycle 2 (time 20 ns, posedge)**: `io_stall=1`, so the prediction register's enable is low (holds its value at `0001`). The assertion evaluates:
   - `io_stall` = 1 (antecedent true)
   - `prediction` = `0001` (current value)
   - `RegNext(prediction)` = `0000` (value from time 10 ns BEFORE the register update)
   - Check: `0001 === 0000` → **false**
   - **Assertion VIOLATED**

### Why This Happens

The root cause is that the `when (!io.stall)` block creates a *combinational* enable for the prediction register. The register is updated on **every** cycle where stall is low. When stall transitions from 0 to 1:

- In the **last unstalled cycle** (time 10), the register loads a new prediction value (`0000` → `0001`) because state has changed (the counter was incremented in cycle 0).
- In the **first stalled cycle** (time 20), the register holds the new value (`0001`), but the assertion compares it against the previous cycle's pre-update value (`0000`), finding them different.

The issue is a **genuine design bug**: the prediction can change in the cycle immediately *before* stall goes high, and this changed value persists through the stall, breaking the stability guarantee expected by the downstream pipeline. The register enable correctly prevents updates *during* the stall, but it does not prevent the update that occurred on the very last unstalled cycle.

### Evidence from Waveform

| Signal | Cycle 0 (0 ns) | Cycle 1 (10 ns) | Cycle 2 (20 ns) |
|--------|---------------|----------------|----------------|
| `io_stall` | 0 | 0 | **1** |
| `prediction [3:0]` | 0000 | **0001** (changed) | 0001 (held) |
| `state_bank0_0 [1:0]` | 01 (1) | 10 (2) | 10 (2) |
| `prediction_stable_when_stalled` | 1 (ok) | 1 (ok) | **0 (FAIL)** |

### Classification

**Bug type**: `dut_bug` — The design correctly gates the prediction register during stalls, but fails to prevent the register from being updated on the cycle immediately before stall is asserted, causing the register value to differ from its value in the previous cycle.

### Proposed Fix

The prediction register should be gated such that it retains its value from the cycle *before* stall went high. This can be achieved by ensuring the register does not update on the same cycle that stall is transitioning from 0 to 1:

```scala
// Option A: Use a delayed stall signal to gate the prediction update
val stall_delayed = RegNext(io.stall, false.B)
when (!io.stall && !stall_delayed) {
    val pred3 = Mux(state_bank3(io.inst_addr) > 1.U, 1.U, 0.U)
    val pred2 = Mux(state_bank2(io.inst_addr) > 1.U, 1.U, 0.U)
    val pred1 = Mux(state_bank1(io.inst_addr) > 1.U, 1.U, 0.U)
    val pred0 = Mux(state_bank0(io.inst_addr) > 1.U, 1.U, 0.U)
    prediction := Cat(pred3, pred2, pred1, pred0)
}
```

This ensures prediction only updates when stall has been low for **two consecutive cycles**, preventing updates on the cycle transitioning into stall, and thus guaranteeing stability when stall is asserted.
