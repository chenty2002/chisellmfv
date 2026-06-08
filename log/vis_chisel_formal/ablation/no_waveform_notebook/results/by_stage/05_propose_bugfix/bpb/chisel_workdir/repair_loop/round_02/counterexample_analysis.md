# Counterexample Analysis Report: `bank0_stable_except_when_selected`

## 1. Verification Environment

### Top Module
- **Design**: `branchPredictionBuffer` (from `bpbs.scala`)
- **Parameters**: `PRED_BUFFER_SIZE = 4` (default)
- **Structure**:
  - Four state banks (`state_bank0` through `state_bank3`), each containing 4 entries of 2-bit saturating counters
  - Prediction register that samples state banks indexed by `io.inst_addr`
  - Update logic that increments/decrements state banks based on `io.branch_result` and `io.buffer_offset`

### Key Signals and Connections
| Signal | Width | Description |
|--------|-------|-------------|
| `io.stall` | 1 | Stall signal (active-high) |
| `io.update` | 1 | Update enable |
| `io.branch_result` | 1 | Branch outcome (1=taken, 0=not taken) |
| `io.buffer_offset` | 2 | Selects which bank to update (0-3) |
| `io.buffer_addr` | 2 | Selects which entry within a bank to update |
| `io.inst_addr` | 2 | Selects which entry to read for prediction |
| `state_bank0(i)` | 2 | State of bank0, entry i |

## 2. Violated Assertion

- **Assertion Name**: `bank0_stable_except_when_selected`
- **Waveform File**: `branchPredictionBuffer.bank0_stable_except_when_selected.fst`

### Code Snippet (bpbs.scala, lines ~144-148)

```scala
assertStableWhen(
  !RegNext(io.update && io.buffer_offset === 0.U, false.B),
  state_bank0(io.buffer_addr).asUInt,
  "bank0_stable_except_when_selected"
)
```

### Property Description

The assertion states: **"state_bank0 at the address indicated by io.buffer_addr should remain stable (unchanged) when bank0 was NOT selected for update in the previous cycle."**

The condition `!RegNext(io.update && io.buffer_offset === 0.U, false.B)` is true when bank0 was NOT selected in the previous cycle. When this condition is true, `state_bank0(io.buffer_addr)` is expected to remain constant.

## 3. Waveform Information

- **Full waveform path**: `verilog/extra_bench/bpb/branchPredictionBuffer.bank0_stable_except_when_selected.fst`
- **Duration**: 3 cycles (30 ns)
- **Key time points**:
  - **Time 0 ns (Cycle 1 start)**: `io_update=1`, `io_buffer_addr=01`, `io_buffer_offset=00`, `io_stall=1`, `io_branch_result=1`
  - **Time 10 ns (Cycle 2 start)**: `io_update=0`, `io_buffer_addr=00`, `REG=1`, `state_bank0_1=01→10`
  - **Time 20 ns (Cycle 3 start)**: `io_buffer_addr=01`, `REG=0`, **assertion fails** (`bank0_stable_except_when_selected` → 0)

### Critical Signal Values at Failure (time 20 ns)

| Signal | Value |
|--------|-------|
| `bank0_stable_except_when_selected` | **0** (FAIL) |
| `REG` (= RegNext(update && offset==0)) | 0 |
| `io_buffer_addr` | 01 |
| `io_update` | 0 |
| `io_stall` | 1 |
| `r_1 [1:0]` (reference register) | 01 |
| `state_bank0_0` | 01 |
| `state_bank0_1` | 10 |
| `state_bank0_2` | 01 |
| `state_bank0_3` | 01 |

## 4. Root Cause Analysis

### Classification: **Incorrect Assertion** (Assertion Error)

### Detailed Explanation

The assertion uses `assertStableWhen(condition, signal, ...)` which works by:
1. Capturing a reference value of `signal` when `condition` first becomes true
2. Asserting that `signal` remains equal to this reference for all subsequent cycles where `condition` is true

#### Sequence of Events Leading to Failure

**Phase 1 — Cycle 1 (time 0–10 ns): Initial Sampling**
- `io_update=1`, `io_buffer_offset=00` → bank0 is being selected for update
- `REG = RegNext(update && offset==0)` = 0 (initial value from `false.B`)
- `!REG = 1` → **stable condition is TRUE**
- The assertion samples `state_bank0(io.buffer_addr)` = `state_bank0(1)` = **01** (weak not taken)
- Reference register `r_1` is set to **01**
- Simultaneously, the update logic begins computing the new state: `state_bank0(1)` will be incremented from 01→10 (since `io_branch_result=1`, branch was taken)

**Phase 2 — Cycle 2 (time 10–20 ns): Update Applies, Selection Masked**
- On the clock edge at time 10: `state_bank0(1)` updates from **01→10** (legitimate update)
- `REG` becomes 1 (captured from cycle 1 where bank0 was selected)
- `!REG = 0` → **stable condition is FALSE** (assertion checking disabled)
- The reference register `r_1` **remains 01** (not updated while condition is false)
- `io_buffer_addr` changes to 00

**Phase 3 — Cycle 3 (time 20–30 ns): Failure**
- `REG` becomes 0 (bank0 was NOT selected in cycle 2)
- `!REG = 1` → **stable condition is TRUE again**
- The assertion compares: `state_bank0(io.buffer_addr)` = `state_bank0(1)` = **10** against `r_1` = **01**
- **10 ≠ 01 → ASSERTION FAILS**

### Why This is an Incorrect Assertion

The assertion fails because:

1. **The reference `r_1` is never re-sampled after de-selection**: When `assertStableWhen`'s condition goes false (bank selected) and then true again (bank de-selected), the implementation does NOT re-capture the signal's current value. It retains the stale reference from the initial sample (pre-update value `01`).

2. **State legitimately changed during the exception period**: The bank was selected for update in cycle 1, and `state_bank0(1)` was legitimately incremented from 01→10. The assertion is designed to allow this by using `RegNext` to skip the cycle after selection. However, after de-selection, it incorrectly compares against the pre-update value rather than accepting the post-update value as the new baseline.

### Evidence from Waveform

| Time | `!REG` | `io_buffer_addr` | `state_bank0(addr)` | `r_1` | Assertion |
|------|--------|-------------------|---------------------|-------|-----------|
| 5 ns | 1 | 01 | state_bank0(1)=01 | 01 | ✅ (pass) |
| 10 ns | 0 | 00 | state_bank0(0)=01 | 01 | ✅ (disabled) |
| 20 ns | 1 | 01 | state_bank0(1)=10 | 01 | ❌ (FAIL: 10≠01) |

The assertion is triggered again at time 20 after being disabled for one cycle. The reference `r_1` still holds `01` (sampled at time 0), but `state_bank0(1)` has been legitimately updated to `10` during the selection window.

### Location

- **File**: `bpbs.scala`, lines 144–148 (approximately)
- **Bug**: The `assertStableWhen` pattern with `!RegNext(...)` does not re-sample the signal value after a bank de-selection event. The assertion incorrectly assumes that the pre-selection reference value is the expected stable value after de-selection.

### Proposed Fix

The assertion should be restructured to check stability of individual bank entries between consecutive cycles when the bank is not currently being selected, rather than using `assertStableWhen` which maintains a persistent reference across non-consecutive stable periods.

One correct approach is to check each individual entry of `state_bank0` separately using per-entry `assertStableWhen` or using a `when`-based assertion:
```scala
// Check each entry individually for stability when bank0 is not being updated THIS cycle
val bank0_selected = io.update && io.buffer_offset === 0.U
for (i <- 0 until PRED_BUFFER_SIZE) {
  when (!bank0_selected) {
    fvAssert(state_bank0(i) === RegNext(state_bank0(i)),
      s"bank0_entry_${i}_stable_when_not_selected")
  }
}
```

Alternatively, if `assertStableWhen` is preferred, the fix would need to ensure the reference is re-captured after de-selection, possibly by gating the condition with a separate reset mechanism.
