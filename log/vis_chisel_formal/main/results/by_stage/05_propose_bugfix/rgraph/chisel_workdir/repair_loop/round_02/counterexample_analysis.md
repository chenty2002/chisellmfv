# Counterexample Analysis Report: `cnt_decrements_in_mode1`

## 1. Verification Environment

- **Benchmark**: `rgraph`
- **Top Module**: `rgraph` (Chisel module with `Formal` trait)
- **Key Components**:
  - `cnt`: 12-bit register, initialized to 0
  - `mode`: 1-bit register, initialized to 0
  - `io_i`: Input boolean signal
  - `io_o`: Output boolean (`cnt === 0`)
  - `notFirstCycle`: Guard register (false on first cycle, true thereafter)
- **Design Behavior**:
  - In mode 0: `cnt` increments by 1 each cycle
  - In mode 1: `cnt` decrements by 1 each cycle when `io_i && cnt ≠ 0`, otherwise stays unchanged
  - Mode transitions from 0 to 1 when `mode === 0.U && io_i`

## 2. Violated Assertion

- **Assertion Name**: `cnt_decrements_in_mode1` (from waveform filename `rgraph.cnt_decrements_in_mode1.fst`)
- **Source File**: `rgraph.scala`, line 44
- **Code Snippet**:
  ```scala
  fvAssert(
    !notFirstCycle || !(mode === 1.U && io.i && cnt =/= 0.U) ||
    cnt === RegNext(cnt, 0.U) - 1.U,
    "cnt_decrements_in_mode1"
  )
  ```
- **Natural Language Property**: "In mode 1, when `io_i` is asserted and `cnt > 0`, `cnt` decrements by exactly 1 compared to its previous cycle value."

## 3. Waveform Information

- **Waveform File**: `verilog/extra_bench/rgraph/rgraph.cnt_decrements_in_mode1.fst`
- **Key Time Points**:

| Signal | Time 0 (initial) | Time 10 (failure) |
|--------|------------------|-------------------|
| `rgraph.cnt [11:0]` | `000000000000` (0) | `000000000001` (1) |
| `rgraph.mode` | `0` | `1` |
| `rgraph.io_i` | `1` | `1` |
| `rgraph.notFirstCycle` | `0` | `1` |
| `rgraph.REG` (RegNext(cnt)) | `0` | `0` |
| `rgraph._GEN` | `0` | `1` |
| `rgraph.cnt_decrements_in_mode1` (assert) | `1` (pass) | `0` (fail) |

- **Failed evaluation at time 10**:
  - `notFirstCycle` = 1 (not first cycle)
  - `mode === 1.U && io.i && cnt =/= 0.U` = true (all conditions satisfied)
  - So the property requires: `cnt === RegNext(cnt, 0.U) - 1.U`
  - This means: `1 === 0 - 1` → `1 === 4095` → **false** (for 12-bit unsigned wrap-around)

## 4. Root Cause Analysis

**Error Type: assertion_error (Incorrect Assertion)**

### The Bug

The assertion `cnt_decrements_in_mode1` is **incorrectly written** because it does not account for the **transition cycle** when `mode` changes from 0 to 1.

### Detailed Reasoning

The DUT logic is:

```scala
when(mode === 0.U) {
  cnt := cnt + 1.U          // increment in mode 0
}.otherwise {
  when(io.i && (cnt =/= 0.U)) {
    cnt := cnt - 1.U        // decrement in mode 1
  }
}

when(mode === 0.U && io.i) {
  mode := 1.U               // transition to mode 1
}
```

On the **transition cycle** (time 0 → time 10):

1. **At time 0** (before posedge): `mode=0`, `cnt=0`, `io_i=1`
2. The combinational logic evaluates under `mode === 0.U`:
   - `cnt := cnt + 1.U` → `next_cnt = 1` (increment, because mode was 0)
   - `mode := 1.U` (because `mode === 0.U && io_i`)
3. **At time 10** (after posedge): `cnt=1`, `mode=1`, `notFirstCycle=1`

The assertion `cnt_decrements_in_mode1` fires at time 10 and checks:
- `cnt (1) === RegNext(cnt, 0.U) (0) - 1.U (which wraps to 4095)` → **1 ≠ 4095**

But this is **wrong** because on this cycle, `cnt` was computed under **mode 0's rule** (increment), not mode 1's rule (decrement). The DUT correctly incremented `cnt` from 0 to 1 at the same time that `mode` transitioned from 0 to 1. There is no bug in the DUT.

### Fix

The assertion needs an additional guard that checks whether `mode` was already 1 in the **previous cycle**. The corrected assertion would be:

```scala
// Safety 4 corrected: In mode 1 (and was already in mode 1), when io.i is asserted
// and cnt > 0, cnt decrements by exactly 1
fvAssert(
  !notFirstCycle ||
  RegNext(mode, 0.U) =/= 1.U ||      // skip the transition cycle
  !(mode === 1.U && io.i && cnt =/= 0.U) ||
  cnt === RegNext(cnt, 0.U) - 1.U,
  "cnt_decrements_in_mode1"
)
```

By adding `RegNext(mode, 0.U) =/= 1.U` (guard: skip if mode was NOT already 1 last cycle), the assertion correctly skips the transition cycle when mode just changed from 0 to 1.

**Note**: The same issue likely affects the `cnt_never_increases_in_mode1` assertion on line 41 for the same reason — it would also fail on the transition cycle because `cnt` went from 0 to 1 while mode went from 0 to 1.

### Evidence Summary

| Signal at time 10 | Value |
|--------------------|-------|
| `cnt` | 1 (was 0) |
| `mode` | 1 (was 0) — **just transitioned** |
| `RegNext(cnt)` | 0 |
| `RegNext(mode)` | 0 (mode was 0 last cycle) |

The root cause is clear: the assertion expects decrement behavior on the exact cycle where mode transitions from increment mode to decrement mode, but `cnt` was computed using the old mode's logic.
