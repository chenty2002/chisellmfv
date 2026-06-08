# Counterexample Analysis Report: `stall_preserves_prediction`

## 1. Verification Environment

- **Top Module**: `branchPredictionBuffer` (Chisel)
- **File**: `bpbs.scala` (package `llmverify`)
- **Writable Directory**: `chisel/extra_bench/bpb/`
- **Waveform File**: `verilog/extra_bench/bpb/branchPredictionBuffer.stall_preserves_prediction.fst`
- **Design Description**: A branch prediction buffer with 4 state banks (bank0–bank3), each with 4 entries of 2-bit saturating counters (PRED_BUFFER_SIZE=4). It provides a 4-bit prediction output (one bit per bank) and supports updates via `io.update`, `io.branch_result`, `io.buffer_addr`, and `io.buffer_offset`. The pipeline can be stalled via `io.stall`.

## 2. Violated Assertion

- **Assertion Name**: `stall_preserves_prediction`
- **File**: `bpbs.scala`, line 181
- **Code Snippet**:
  ```scala
  assertStableWhen(io.stall, prediction.asUInt, "stall_preserves_prediction")
  ```
- **Natural Language Description**: When the pipeline stall signal (`io.stall`) is asserted high, the `prediction` register must remain stable (not change value). This ensures that during a pipeline stall, the branch predictor's output stays consistent and the pipeline does not use incorrect branch speculation.

## 3. Waveform Information

- **Waveform File**: `verilog/extra_bench/bpb/branchPredictionBuffer.stall_preserves_prediction.fst`
- **Clock Period**: 10 ns (rising edges at 0, 10, 20, 30 ns)
- **Time Range**: 0 ns → 30 ns (3 clock cycles)
- **Key Time Points**:
  - **t=0 ns** (cycle 0, posedge): Reset state. `io_stall=0`, `prediction=0000`, `io_inst_addr=00`, `state_bank0(0)=01` (weak not taken). Update occurs on bank0, offset=0, addr=0.
  - **t=10 ns** (cycle 1, posedge): `io_stall=0`. `state_bank0(0)` updated to `10` (weak taken). Prediction computation yields `0001`, scheduled for next cycle. Update on bank3, offset=3, addr=3.
  - **t=20 ns** (cycle 2, posedge): **Failure point!** `io_stall` transitions **0→1**, `prediction` transitions **0000→0001**. The assertion `stall_preserves_prediction` drops from 1 to 0.
  - **t=30 ns** (cycle 3): `io_stall=1`, `prediction=0001` (stable after the failure).

## 4. Root Cause Analysis

### Bug Location

- **File**: `bpbs.scala`
- **Module**: `branchPredictionBuffer` (class declaration line 7)
- **Buggy Code**: Lines 34–42
  ```scala
  val prediction = RegInit(0.U(4.W))

  // Prediction logic - read from all 4 banks when not stalled
  when (!io.stall) {
    val pred3 = Mux(state_bank3(io.inst_addr) > 1.U, 1.U, 0.U)
    val pred2 = Mux(state_bank2(io.inst_addr) > 1.U, 1.U, 0.U)
    val pred1 = Mux(state_bank1(io.inst_addr) > 1.U, 1.U, 0.U)
    val pred0 = Mux(state_bank0(io.inst_addr) > 1.U, 1.U, 0.U)
    prediction := Cat(pred3, pred2, pred1, pred0)
  }
  ```

### Bug Description

The `prediction` register is guarded by `when (!io.stall)`, which should prevent updates when the pipeline is stalled. However, due to the register's pipeline timing, there is a **one-cycle bubble**: a prediction value computed in a non-stalled cycle (when `!io.stall` was true) takes effect on the *next* clock edge — the very same edge where `io.stall` may be asserted for the first time.

**Sequence of events leading to the failure:**

| Cycle | Posedge | `io_stall` | `prediction` | State Bank Update | What Happens |
|-------|---------|------------|--------------|-------------------|--------------|
| 0     | 0 ns    | 0          | 0000         | bank0[0]: 01→10  | Prediction computed as 0000 (all banks at inst_addr=00 are `01`, none >1) |
| 1     | 10 ns   | 0          | 0000         | bank3[3]: 01→10  | Prediction recomputed: bank0[0]=10 (2) > 1 → pred0=1, others=0 → **Cat(0001)** |
| 2     | 20 ns   | **1**      | **0001**     | —                | **Assertion violated!** prediction=0001 appears despite io_stall=1 |
| 3     | 30 ns   | 1          | 0001         | —                | prediction holds stable |

**The critical timing mismatch:**
1. At t=10 ns (cycle 1), `io_stall=0`, so the `when` block executes and `prediction` is **scheduled** to update to `0001` on the next clock edge. The register's clock enable was sampled as high.
2. At t=20 ns (cycle 2), the register latches the new value `0001`, BUT `io_stall` also becomes `1` at this same clock edge.
3. The assertion `assertStableWhen(io.stall, prediction.asUInt)` checks that when `io_stall=1`, `prediction` at cycle 2 equals `prediction` at cycle 1. They differ (`0001 ≠ 0000`), so the assertion fires.

**Root cause category: Bug in the Original Design (DUT bug)**

The `when (!io.stall)` guard prevents the prediction register from being *enabled* when stall is active, but it does not prevent a previously-computed value (from the last non-stalled cycle) from appearing at the register output on the exact same cycle that stall is first asserted. In other words, there is a **pipeline bubble** where the prediction register updates one cycle after computation, regardless of whether stall went high in the meantime.

### Proposed Fix

To properly gate the prediction output during stalls, the prediction should either be computed speculatively and masked at the output, or the register should use an explicit enable that is also qualified by stall:

**Option 1 (recommended): Separate computation from output gating**
```scala
// Always compute prediction
val pred3 = Mux(state_bank3(io.inst_addr) > 1.U, 1.U, 0.U)
val pred2 = Mux(state_bank2(io.inst_addr) > 1.U, 1.U, 0.U)
val pred1 = Mux(state_bank1(io.inst_addr) > 1.U, 1.U, 0.U)
val pred0 = Mux(state_bank0(io.inst_addr) > 1.U, 1.U, 0.U)
val next_prediction = Cat(pred3, pred2, pred1, pred0)

// Only update register when not stalled
when (!io.stall) {
  prediction := next_prediction
}
```

**Option 2: Use RegEnable with explicit enable**
```scala
val prediction = RegEnable(0.U(4.W), !io.stall)
```

Either approach ensures that the `prediction` register only updates on clock edges where `!io.stall` is true, preventing the stale computed value from appearing when stall is first asserted.
