# Counterexample Analysis Report: prediction_stable_during_stall

## 1. Verification Environment

- **Top module**: `branchPredictionBuffer` (package `llmverify`)
- **Source file**: `bpbs.scala` (192 lines)
- **Design under test**: A 4-bank branch prediction buffer with 2-bit saturating counters
  - 4 state banks (bank0-bank3), each with 4 entries (PRED_BUFFER_SIZE=4)
  - Each entry is a 2-bit saturating counter initialized to 01 (weak not taken)
  - A 4-bit prediction register that combines predictions from all 4 banks
  - Update logic that increments/decrements the selected bank entry on taken/not-taken branches
  - Stall input to freeze prediction updates
- **Input stimulus**:
  - `io_update` = 1 (constant, update every cycle)
  - `io_branch_result` = 1 (constant, branch always taken)
  - `io_inst_addr` = 3 (11 binary)
  - `io_buffer_addr` = 3 (11 binary)
  - `io_buffer_offset` = 1 (01 binary, selects bank 1)
  - `io_stall` = 0 at time 0–19, transitions to 1 at time 20

## 2. Violated Assertion

- **Assertion name**: `prediction_stable_during_stall` (from waveform filename)
- **Source code** (bpbs.scala, line 86):
  ```scala
  assertStableWhen(io.stall, io.prediction.asUInt, "prediction_stable_during_stall")
  ```
- **Natural language property**: When the stall signal is asserted (`io.stall` = true), the prediction output (`io.prediction`) must remain stable (i.e., not change from its previous value).
- **File location**: `bpbs.scala`, line 86

## 3. Waveform Information

- **Waveform file**: `branchPredictionBuffer.prediction_stable_during_stall.fst`
- **Waveform duration**: 30 ns (3 clock cycles at 10 ns period)
- **Clock posedges**: at time 0 ns, 10 ns, 20 ns

### Key Timing (all times in nanoseconds)

| Time | Clock | Stall | Prediction | state_bank1_3 | Event |
|------|-------|-------|------------|---------------|-------|
| 0    | 1     | 0     | 0000       | 01            | Initial reset state |
| 5    | 0     | 0     | 0000       | 01            | — |
| 9    | 0     | 0     | 0000       | 01            | Pre-posedge (cycle 1→2) |
| 10   | 1     | 0     | 0000       | **10**        | Posedge: state_bank1_3 updates (01→10), prediction remains 0000 |
| 15   | 0     | 0     | 0000       | 10            | — |
| 19   | 0     | 0     | 0000       | 10            | Pre-posedge (cycle 2→3) |
| **20** | **1** | **0→1** | **0010** | **11** | **Posedge: FAILURE — stall→1, prediction→0010, state_bank1_3→11** |

### Critical Signal Values at Failure Point (time=20 ns)

| Signal | Value |
|--------|-------|
| `branchPredictionBuffer.io_stall` | **1** (just transitioned from 0) |
| `branchPredictionBuffer.io_prediction [3:0]` | **0010** (changed from 0000) |
| `branchPredictionBuffer.prediction [3:0]` | **0010** (internal register) |
| `branchPredictionBuffer.state_bank1_3 [1:0]` | **11** (just updated from 10) |
| `branchPredictionBuffer.clock` | **1** (posedge) |

## 4. Root Cause Analysis

### Buggy Code Location

**File**: `bpbs.scala`, lines 29–36 (prediction update logic) and line 80 (output connection):

```scala
// Lines 29-36: Prediction update gated by !io.stall
when (!io.stall) {
    val pred3 = Mux(state_bank3(io.inst_addr) > 1.U, 1.U, 0.U)
    val pred2 = Mux(state_bank2(io.inst_addr) > 1.U, 1.U, 0.U)
    val pred1 = Mux(state_bank1(io.inst_addr) > 1.U, 1.U, 0.U)
    val pred0 = Mux(state_bank0(io.inst_addr) > 1.U, 1.U, 0.U)
    prediction := Cat(pred3, pred2, pred1, pred0)
}

// Line 80: Output always connected to prediction register (no stall gating)
io.prediction := prediction
```

### Bug Type: Design Bug (dut_bug)

### Description

The root cause is a **race condition between the prediction register update and the stall signal assertion**. The design uses `when (!io.stall)` to gate the prediction register's write enable, but this creates a one-cycle race window: the prediction register captures its input **at the clock edge**, using the **pre-transition** value of `io.stall`.

### Detailed Explanation

The counterexample demonstrates a three-cycle sequence:

**Cycle 1 (time 0–10, initial state after reset)**:
- All state banks initialized to 01 (weak not-taken)
- `io_stall` = 0, so prediction update is enabled
- `io_update` = 1, `io_branch_result` = 1 (taken), `buffer_offset` = 1 (bank 1), `buffer_addr` = 3
- State bank 1, entry 3 (`state_bank1_3`) increments: 01 → **10** (weak not-taken → weak taken)
- Prediction computed from all banks at 01: `01 > 1 = 0` → prediction stays **0000**

**Cycle 2 (time 10–20, stall still low)**:
- `io_stall` = 0 throughout the cycle
- `state_bank1_3` = **10** (updated at time 10 posedge)
- Prediction combinational logic: `state_bank1_3 = 10 > 1 = true` → pred1 = 1 → result = **0010**
- This value is staged to be loaded into the `prediction` register at the next clock edge

**Cycle 3 (time 20, failure)**:
- At the time 20 posedge, **two things happen simultaneously**:
  1. `io_stall` transitions from 0 → 1
  2. The `prediction` register loads the value **0010** that was computed during cycle 2 (when stall was still 0)
- The `when (!io.stall)` condition samples the **pre-transition** value of stall (= 0), so the register update proceeds
- After the clock edge, `io_stall` = 1, `io_prediction` = 0010
- **The assertion `assertStableWhen` checks at the clock edge: stall=1, but prediction changed from 0000 to 0010 → FAIL**

### Why This is a Design Bug

The fundamental issue is that the **prediction output is never explicitly held stable during stall**. The `when (!io.stall)` block only prevents the register from accepting a *new* value when stall is asserted, but it does not prevent the register from loading a value computed when stall was *previously* deasserted. Specifically:

1. The prediction computation (`pred3, pred2, pred1, pred0`) is **combinational** and runs continuously
2. The `when (!io.stall)` block gates the **register write**, not the output
3. The output `io.prediction` is directly wired to the register (`io.prediction := prediction`)
4. When stall transitions from 0→1 at the same edge as a prediction update, the register captures the pre-transition computation

### Suggested Fix

To correctly implement stability during stall, the design should either:

1. **Gate the prediction output with stall**: Replace `io.prediction := prediction` with:
   ```scala
   io.prediction := Mux(io.stall, RegNext(io.prediction), prediction)
   ```
   This holds the output constant whenever stall is asserted.

2. **Use a registered stall signal** to delay the effect of stall by one cycle, so the prediction update and stall assertion never conflict.

3. **Add an explicit hold register**: Latch the prediction value when stall is first asserted and hold it until stall is deasserted.

The cleanest solution is option (1): gate the output so `io.prediction` cannot change when `io.stall` is high, regardless of what the internal prediction register does.
