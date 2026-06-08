# Counterexample Analysis: `prediction_updates_after_update`

## 1. Verification Environment

- **Top Module**: `branchPredictionBuffer` (package `llmverify`)
- **Source File**: `bpbs.scala` (138 lines)
- **Design Under Test**: A branch prediction buffer with 4 state banks (bank0–bank3), each containing 4 entries implemented as 2-bit saturating counters. The design supports:
  - **Prediction generation**: Each bank produces one prediction bit (`Mux(state_bank_X(inst_addr) > 1.U, 1.U, 0.U)`), concatenated into a 4-bit prediction.
  - **Stall mechanism**: When `io.stall` is asserted, the `prediction` register freezes (no update), and `io.prediction` outputs the frozen register value instead of the combinational result.
  - **Update logic**: When `io.update` is asserted, the selected state bank (by `io.buffer_offset`) increments/decrements the counter at `io.buffer_addr`.

## 2. Violated Assertion

- **Assertion Name**: `prediction_updates_after_update`
- **Source Location**: `bpbs.scala`, lines 126–133
- **Code Snippet**:
  ```scala
  astRelaxedLiveness(
    io.update && !io.stall,
    io.prediction =/= RegNext(io.prediction),
    2,
    "prediction_updates_after_update"
  )
  ```
- **Property Description**: When an update is requested (`io.update && !io.stall`), the prediction output (`io.prediction`) must change value (compared to its previous clock-cycle value) within 2 cycles. This ensures that update requests are serviced in a bounded time.

## 3. Waveform Information

- **Waveform File**: `verilog/extra_bench/bpb/branchPredictionBuffer.prediction_updates_after_update.fst`
- **Time Range**: 0 ns → 40 ns (4 clock cycles)
- **Key Events**:

| Time (ns) | Cycle | `io.stall` | `io.update` | `io.prediction` | `next_prediction` | `prediction` (reg) | State Change |
|-----------|-------|------------|-------------|-----------------|-------------------|-------------------|-------------|
| 0         | 0     | 0          | 1           | 0000            | 0000              | 0000              | **Trigger fires**; `state_bank0(2)` incremented 01→10 |
| 10        | 1     | 1          | 1           | 0000            | 0001              | 0000              | Stall active; prediction frozen; `state_bank0(3)` inc 01→10 |
| 20        | 2     | 1          | 0           | 0000            | 0001              | 0000              | Still stalled; prediction still frozen |
| 30        | 3     | 0          | 0           | 0000            | 0000              | 0000              | Stall released; bound expired; **assertion fails** |

## 4. Root Cause Analysis

### Classification: Incorrect Assertion (Assertion Error)

### Description

The assertion `prediction_updates_after_update` uses a **bound of 2 cycles**, but this bound is insufficient when the `io.stall` signal remains high for multiple consecutive cycles after the trigger fires.

**Timeline of the failure**:

1. **Cycle 0 (time 0)**: The trigger `io.update && !io.stall` fires. The state bank update takes effect (state_bank0_2 increments from 01 to 10). However, the new prediction value (which depends on `state_bank0(2)` being > 1) does not appear at `io.prediction` yet because the `prediction` register was clocked with the pre-update state at this edge. `io.prediction` = 0000.

2. **Cycles 1–2 (times 10–20)**: `io.stall` = 1. The stall mechanism freezes the `prediction` register and forces `io.prediction` to output the frozen register value (0000). Although `next_prediction` has updated to `0001` (reflecting the incremented state bank), the stall mux selects the frozen register. Since `io.prediction` stays constant at 0000, `io.prediction =/= RegNext(io.prediction)` evaluates to false on both cycles.

3. **Cycle 3 (time 30)**: `io.stall` clears to 0, but the bound of 2 has already expired. The assertion fails.

**Why this is an assertion error, not a DUT bug**:

- The DUT's stall logic functions correctly: it intentionally freezes prediction output when stalled, which is a documented feature.
- The state bank updates are correct (counters saturate properly and update at the right addresses).
- The prediction computation (`next_prediction`) is correct — it does reflect the updated state.
- The failure is solely because the bound of 2 does not account for the scenario where `io.stall` holds for 2+ cycles after the trigger fires.

**The assertion's own comment** (lines 119–125) acknowledges the need for stall slack: *"An extra cycle of slack handles a stall that clears on the following cycle."* This accounts for at most 1 stall cycle after the update. However, the formal verification tool can schedule the `io.stall` input to remain high for **2 or more** consecutive cycles after the trigger, which exceeds the 2-cycle bound.

### Recommended Fix

The bound should be increased to accommodate longer stall durations. Since `io.stall` is an unconstrained input, there is no theoretical upper bound — a more robust fix would be to restructure the assertion to directly tie the prediction update to `!io.stall` being asserted:

**Option A (pragmatic)**: Increase bound from 2 to a larger number (e.g., 4 or 5) and optionally add an assumption that `io.stall` cannot remain high indefinitely:

```scala
// Assumption: stall releases within reasonable time
fvAssume(Past(!io.stall, 4), "stall_releases_within_4")
astRelaxedLiveness(
  io.update && !io.stall,
  io.prediction =/= RegNext(io.prediction),
  4,
  "prediction_updates_after_update"
)
```

**Option B (principled)**: Change the trigger to require that stall has cleared after the update:

```scala
astRelaxedLiveness(
  io.update,
  io.prediction =/= RegNext(io.prediction),
  4,
  "prediction_updates_after_update"
)
```
This removes `!io.stall` from the trigger (weakening the trigger but making it vacuously true when stalled), and increases the bound so there are enough non-stalled cycles for the update to propagate.
