# Counterexample Analysis Report: `cnt_decrements_in_mode1`

## 1. Verification Environment

- **Top module**: `rgraph` (package: `llmverify`)
- **Source file**: `rgraph.scala` (57 lines)
- **Design under test**: A simple state machine with:
  - `cnt[11:0]`: A counter that increments in mode 0 and decrements in mode 1 when `io.i` is asserted
  - `mode`: A sticky register that transitions from 0 to 1 when `io.i` is asserted in mode 0
  - `io.o`: Output that is high when `cnt === 0.U`
- **Formal framework**: Chisel formal (`chiselFv`) with `fvAssert` and `assertImplies`

## 2. Violated Assertion

- **Assertion name**: `cnt_decrements_in_mode1` (from filename `rgraph.cnt_decrements_in_mode1.fst`)
- **File**: `rgraph.scala`, line 47
- **Code snippet**:
  ```scala
  // Safety 4: In mode 1, when io.i is asserted and cnt > 0, cnt decrements by exactly 1
  // Guard: skip the transition cycle when mode just became 1 (cnt was computed under mode 0)
  fvAssert(!notFirstCycle || RegNext(mode, 0.U) =/= 1.U || !(mode === 1.U && io.i && cnt =/= 0.U) || cnt === RegNext(cnt, 0.U) - 1.U, "cnt_decrements_in_mode1")
  ```
- **Property description**: If it is not the first cycle, and mode was 1 in the previous cycle, and mode is 1 now, and `io.i` is asserted now, and `cnt !== 0`, then `cnt` must equal the previous `cnt` minus 1.

## 3. Waveform Information

- **Waveform file**: `verilog/extra_bench/rgraph/rgraph.cnt_decrements_in_mode1.fst`
- **Duration**: 3 cycles (0–30 ns)
- **Key time points** (all values at positive clock edges):

| Time (ns) | `cnt` | `mode` | `io.i` | `notFirstCycle` | `io.o` |
|-----------|-------|--------|--------|-----------------|--------|
| 0         | 0     | 0      | 1      | 0               | 1      |
| 10        | 1     | 1      | 0      | 1               | 0      |
| **20**    | **1** | **1**  | **1**  | **1**           | **0**  |

- **Assertion fails at**: Time 20 ns (second posedge after first cycle)
- **At failure point**:
  - `cnt = 1`, `RegNext(cnt) = 1`
  - Assertion requires: `cnt === RegNext(cnt) - 1` → `1 === 0` → **FALSE**

## 4. Root Cause Analysis

### Root Cause Type: **Assertion Error** (incorrect assertion)

The assertion is missing a guard condition to check that `io.i` was asserted in the **previous cycle**.

### Detailed Explanation

The DUT logic for counter decrement is:

```scala
when(mode === 0.U) {
    cnt := cnt + 1.U
}.otherwise {
    when(io.i && (cnt =/= 0.U)) {
        cnt := cnt - 1.U
    }
}
```

This means `cnt` is decremented **one cycle after** `io.i` is sampled high. The decrement happens when the clock edge samples `io.i=1` during the current cycle, and `cnt` updates at the **next** clock edge.

### Sequence of Events

1. **Cycle 0–10** (time 0 to time 10): `io.i=1`, `mode=0`
   - `cnt` increments from 0 to 1 at time 10
   - `mode` transitions from 0 to 1 at time 10
   - The guard `RegNext(mode, 0.U) =/= 1.U` correctly skips this transition cycle at time 10

2. **Cycle 10–20** (time 10 to time 20): `mode=1`, `io.i=0`
   - Since `io.i=0`, `cnt` is NOT decremented; `cnt` stays at 1
   - At time 20: `cnt=1`, `mode=1`, but `io.i` transitions from 0 to 1 AT the clock edge

3. **Time 20 (failure)**: The assertion checks:
   - `notFirstCycle` = 1 ✓
   - `RegNext(mode) = 1` (mode was 1 at time 10) → guard `RegNext(mode) =/= 1` is **FALSE**
   - `mode=1 && io.i=1 && cnt=1 != 0` → guard `!(...)` is **FALSE**
   - Consequent: `cnt (1) === RegNext(cnt) (1) - 1 = 0` → **FALSE**

### Why the Assertion is Wrong

The assertion expects that when `io.i=1` at the current cycle, `cnt` should already reflect the decrement. However, `cnt` was computed during the **previous cycle** (cycle 10–20), when `io.i` was **0**. Therefore `cnt` couldn't have decremented yet.

The guard `RegNext(mode, 0.U) =/= 1.U` only skips the mode-transition cycle, but does NOT account for the case where `io.i` was deasserted during the previous cycle.

### Fix

The assertion guard must also check that `io.i` was asserted in the **previous cycle**:

```scala
// Fixed version: add RegNext(io.i, 0.U) guard
fvAssert(!notFirstCycle || RegNext(mode, 0.U) =/= 1.U || !RegNext(io.i, 0.U) || !(mode === 1.U && io.i && cnt =/= 0.U) || cnt === RegNext(cnt, 0.U) - 1.U, "cnt_decrements_in_mode1")
```

The added term `!RegNext(io.i, 0.U)` makes the assertion vacuously true when `io.i` was deasserted in the previous cycle, since `cnt` could not have decremented. This correctly handles the case at time 20 where `io.i` transitions from 0 to 1 at the clock edge.

### Verification of Fix

With the fix applied to the counterexample:
- **Time 10**: `RegNext(mode)=0` → guard `RegNext(mode) =/= 1` is TRUE → vacuously true ✓
- **Time 20**: `RegNext(io.i)=0` (io.i at time 10 was 0) → guard `!RegNext(io.i)` is TRUE → vacuously true ✓
- **Time 30** (hypothetical): If `io.i=1` during cycle 20–30, then `RegNext(io.i)=1`, and `cnt=0` should equal `RegNext(cnt)=1 - 1 = 0` → TRUE ✓

### Alternative Interpretation

The assertion could also be re-written to use `RegNext` on `io.i` in the condition rather than the guard, which would check that the decrement happened when `io.i` was asserted in the previous cycle. However, adding `RegNext(io.i, 0.U)` to the guard is the minimal and clearest fix.
