# Counterexample Analysis Report: `cnt_stays_zero_in_mode1`

## 1. Verification Environment

- **Top Module**: `rgraph` (from package `llmverify`)
- **Source File**: `rgraph.scala` (47 lines)
- **Design Under Test**: A 12-bit counter (`cnt`) with two modes of operation:
  - **Mode 0**: Counter increments by 1 each cycle.
  - **Mode 1**: Counter decrements by 1 each cycle when `io.i` is high and `cnt > 0`; otherwise stays unchanged.
  - The mode transitions **once** from 0 to 1 when `mode === 0 && io.i` is true (monotonic).
  - Output `io.o` reflects whether `cnt === 0`.

## 2. Violated Assertion

- **Assertion Name**: `cnt_stays_zero_in_mode1` (from waveform filename `rgraph.cnt_stays_zero_in_mode1.fst`)
- **Location**: `rgraph.scala`, line 41
- **Code**:
  ```scala
  fvAssert(!(mode === 1.U && cnt === 0.U) || RegNext(cnt) === 0.U, "cnt_stays_zero_in_mode1")
  ```
- **Natural Language Description**: The assertion states that whenever `mode === 1` and `cnt === 0` in the current cycle, the *previous* cycle's value of `cnt` must also have been 0. In other words, it asserts that `cnt` cannot transition *into* 0 while in mode 1 — it must have already been 0 in the prior cycle.

## 3. Waveform Information

- **Waveform File**: `verilog/extra_bench/rgraph/rgraph.cnt_stays_zero_in_mode1.fst`
- **Duration**: 3 cycles (0 ns → 30 ns)

### Key Signal Timeline

| Time (ns) | `cnt [11:0]` | `mode` | `io_i` | `io_o` | `REG_1` (RegNext(cnt)) | Assertion Status |
|-----------|-------------|--------|--------|--------|----------------------|-----------------|
| **0**     | 0           | 0      | 1      | 1      | 0                    | Pass (condition false, mode=0) |
| **10**    | 1           | 1      | 1      | 0      | 0                    | Pass (condition false, cnt=1) |
| **20**    | **0**       | **1**  | 1      | 1      | **1**                | **FAIL** |

### Failure Point (time = 20 ns)

- `mode = 1`, `cnt = 0` → antecedent `(mode === 1.U && cnt === 0.U)` is **TRUE**
- `REG_1 = 1` (the value of `cnt` at time 10, i.e., the previous cycle) → consequent `RegNext(cnt) === 0.U` is **FALSE**
- Assertion evaluates to `!(TRUE) || FALSE` = `FALSE || FALSE` = **FALSE** → **Assertion fires**

## 4. Root Cause Analysis

### Root Cause Category: **Incorrect Assertion**

### Nature of the Bug

The assertion **checks the wrong temporal direction**. It uses `RegNext(cnt) === 0.U`, which refers to the *previous* cycle's value of `cnt`. However, the intended property (as described by the assertion name "cnt stays zero in mode 1") should be a *forward-looking* property: once `cnt` reaches 0 in mode 1, it should remain 0 in *future* cycles.

### Buggy Code

**File**: `rgraph.scala`, **Line 41**
```scala
fvAssert(!(mode === 1.U && cnt === 0.U) || RegNext(cnt) === 0.U, "cnt_stays_zero_in_mode1")
```

The assertion `(mode === 1.U && cnt === 0.U) → RegNext(cnt) === 0.U` translates to:
> "If mode=1 and cnt=0 now, then cnt was 0 in the **previous** cycle."

This is a **past-looking** property. It will fail the very first time `cnt` reaches 0 in mode 1 because `cnt` must have been non-zero in the prior cycle in order to decrement *into* 0.

### Why the DUT is Correct

The actual design behavior at each cycle is:

| Cycle | `mode` | `cnt` | Logic | Next `cnt` |
|-------|--------|-------|-------|------------|
| 0 (time 0) | 0 → 1 | 0 | Mode 0: `cnt := cnt + 1` | 1 |
| 1 (time 10) | 1 | 1 | Mode 1, io_i=1, cnt>0: `cnt := cnt - 1` | 0 |
| 2 (time 20) | 1 | **0** | Mode 1, io_i=1, cnt=0: **no decrement** | **0** ✅ |

At cycle 2 (time 20), `cnt=0` in mode 1. The design's combinational logic evaluates `when(io.i && (cnt =/= 0.U))` — since `cnt === 0`, the condition is false, and `cnt` retains its value (0). The DUT correctly keeps `cnt` at 0 from this point forward.

### The First-Encounter Problem

The assertion fails only at the *first* cycle where `cnt` becomes 0 in mode 1. In the prior cycle (time 10), `cnt` was 1 (having just decremented from... well, having been incremented from 0 to 1 in mode 0, then decremented from 1 to 0 in mode 1). So `RegNext(cnt)` = 1, which violates the assertion's consequent.

### Correct Fix

The assertion should use a **forward-looking** check. Two equivalent correct formulations:

**Option A** (using `next`):
```scala
fvAssert(!(mode === 1.U && cnt === 0.U) || next(cnt === 0.U), "cnt_stays_zero_in_mode1")
```

**Option B** (using `RegNext` on the antecedent):
```scala
fvAssert(RegNext(mode === 1.U && cnt === 0.U) implies (cnt === 0.U), "cnt_stays_zero_in_mode1")
```

Both express: "If in the *previous* cycle mode was 1 and cnt was 0, then in the *current* cycle cnt is still 0" — i.e., once cnt reaches 0 in mode 1, it stays 0.

### Evidence Summary

| Evidence | Detail |
|----------|--------|
| DUT behavior at time 20 | `mode=1`, `cnt=0`, `io_i=1` — no decrement because `cnt=/=0` is false; `cnt` correctly stays 0 |
| Assertion failure at time 20 | `RegNext(cnt)` = 1 (from time 10) ≠ 0, causing `(mode=1 && cnt=0) → RegNext(cnt)=0` to evaluate to false |
| The transition at time 10→20 | `cnt` went from 1 → 0 while mode=1, which is the **first** arrival at 0 — RegNext is necessarily non-zero |
| DUT correctness check | After time 20, `cnt` remains 0 (io_i=1 but cnt=0 prevents decrement). The future property holds; only the past property fails due to the first-encounter transition. |

## Conclusion

The assertion `cnt_stays_zero_in_mode1` is **incorrectly specified**. It checks a past-temporal property (`RegNext(cnt) === 0.U`) when it should check a future-temporal property (`next(cnt) === 0.U`). The design under test (`rgraph`) is bug-free with respect to the intended behavior: once `cnt` reaches 0 in mode 1, it correctly stays at 0.
