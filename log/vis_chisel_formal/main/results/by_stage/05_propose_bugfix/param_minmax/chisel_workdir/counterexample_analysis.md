# Counterexample Analysis Report: minMax.min_leq_max

## 1. Verification Environment

- **Top Module**: `minMax` (package `llmverify`)
- **Generated Verilog Path**: Not found in workspace (Verilog generation may have failed or been stored elsewhere)
- **Waveform File**: `verilog/extra_bench/param_minmax/minMax.min_leq_max.fst`
- **Structure**:
  - `minMax` class extends `Module with Formal`
  - I/O: `clear`, `enable`, `reset`, `in` (128-bit), `out` (128-bit)
  - Internal registers: `min` (128-bit), `max` (128-bit), `last` (128-bit)
  - Formal assertions: 5 `fvAssert` calls and 1 `astRelaxedLiveness`
  - Contains a `resetCounter` submodule (for formal reset sequencing)

## 2. Violated Assertion

- **Assertion Name**: `min_leq_max`
- **Code Snippet** (from `minMax.scala`, lines 68-73):
  ```scala
  // Invariant 1: Minimum must never exceed maximum (most critical property)
  // Only checked during active tracking mode because min and max use opposite
  // extreme initial values (all_1s and 0) which legitimately violate min <= max
  // when the module is disabled, cleared, or reset.
  val trackingMode = io.enable && !io.clear && !io.reset
  fvAssert(!trackingMode || min <= max, "min_leq_max")
  ```
- **Natural Language Description**: When the module is in "tracking mode" (i.e., `io.enable` is asserted AND `io.clear` is deasserted AND `io.reset` is deasserted), the tracked minimum value (`min`) must always be less than or equal to the tracked maximum value (`max`). This is the most critical invariant of a min/max tracker.
- **File Location**: `minMax.scala`, lines 68-73

## 3. Waveform Information

- **Full Waveform Path**: `verilog/extra_bench/param_minmax/minMax.min_leq_max.fst`
- **Time Range**: 0 ns → 10 ns (1 clock cycle)
- **Key Time Point**: Time 0 ns (initial/power-on state)

### Critical Signal Values at Time 0 ns

| Signal | Value |
|--------|-------|
| `minMax.io_enable` | 1 |
| `minMax.io_clear` | 0 |
| `minMax.io_reset` | 0 |
| `minMax.trackingMode` | 1 (computed from enable & !clear & !reset) |
| `minMax.min [127:0]` | `0xFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF` (all-ones, initial value) |
| `minMax.max [127:0]` | `0x00000000000000000000000000000000` (zero, initial value) |
| `minMax.io_in [127:0]` | `0x00000000000000000000000000000000` (zero) |
| `minMax.hasBeenReset` | 1 |
| `minMax.min_leq_max` (signal) | 1 (formal assertion property indicator) |
| `minMax.clock` | 1 (posedge at time 0) |

All signals remain constant throughout the single-cycle waveform (0–10 ns). There are no transitions.

## 4. Root Cause Analysis

### Categorization: **Assertion Error** (with contributing Setup Error)

The counterexample shows the assertion failing at the **very first clock cycle** (time 0) due to an incomplete guard condition in the assertion definition.

### Detailed Explanation

#### The Assertion Logic

```scala
val trackingMode = io.enable && !io.clear && !io.reset
fvAssert(!trackingMode || min <= max, "min_leq_max")
```

The assertion checks: `!trackingMode || (min <= max)`.

At time 0:
- `io.enable = 1`, `io.clear = 0`, `io.reset = 0` → `trackingMode = 1`
- `min = 0xFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF` (initial value = all ones)
- `max = 0x00000000000000000000000000000000` (initial value = zero)
- `min <= max` evaluates to `0xFFFF...FFFF <= 0x0000...0000` → **FALSE** (since all-ones > zero in unsigned comparison)

Thus the assertion condition `!trackingMode || min <= max` = `0 || 0` = **FALSE** → **Assertion Violation**

#### The Sequential Logic

Looking at the register update logic (lines 39-50):

```scala
when(io.clear) {
    last := 0.U
    max := 0.U
    min := "hFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF".U
}.elsewhen(!io.enable) {
    max := 0.U
    min := "hFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF".U
}.otherwise {
    last := io.in
    when(io.reset) {
      max := 0.U
      min := "hFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF".U
    }.otherwise {
      max := sup     // sup = Mux(io.in > max, io.in, max) = Mux(0>0, 0, 0) = 0
      min := inf     // inf = Mux(io.in < min, io.in, min) = Mux(0<all_ones, 0, all_ones) = 0
    }
}
```

At time 0 with `io_in=0`:
- `sup = Mux(0 > 0, 0, 0) = 0`
- `inf = Mux(0 < 0xFFFF...FFFF, 0, 0xFFFF...FFFF) = 0`

**On the posedge of the clock**, `max` and `min` would be updated to `sup=0` and `inf=0` respectively. After the first clock edge, `min=0` and `max=0`, satisfying `min <= max`.

**However**, the assertion is evaluated **combinatorially** (not sequentially). At time 0 (before any clock edge), the registers still hold their initial values, and the assertion fails.

#### Why the Existing Guard is Insufficient

The code's comment explains the guard's intent:
```
// Only checked during active tracking mode because min and max use opposite
// extreme initial values (all_1s and 0) which legitimately violate min <= max
// when the module is disabled, cleared, or reset.
```

The `trackingMode` guard (`io.enable && !io.clear && !io.reset`) correctly excludes cases where:
1. `io.clear` is asserted (sets min=all_ones, max=0)
2. `io.enable` is deasserted (sets min=all_ones, max=0)
3. `io.reset` is asserted (sets min=all_ones, max=0)

**But it does NOT exclude the initial power-on state** where:
- `min` still holds its `RegInit` value of all-ones
- `max` still holds its `RegInit` value of zero
- `io.enable = 1` (immediately active), `io.clear = 0`, `io.reset = 0`

This creates a scenario where `trackingMode` is asserted while the registers still have their conflicting initial values.

#### Root Cause Summary

The assertion guard `trackingMode` is **incomplete** — it fails to account for the initial/power-on state of the registers. The guard should also exclude the first clock cycle, or the formal environment should constrain `io.reset` to be asserted at power-on, or the `trackingMode` definition should incorporate the `hasBeenReset` signal or use a `past()` initialization check.

### Evidence Summary

| Evidence | Detail |
|----------|--------|
| **Waveform signal** `minMax.min [127:0]` at time 0 | `0xFFFF...FFFF` (all ones — initial value) |
| **Waveform signal** `minMax.max [127:0]` at time 0 | `0x0000...0000` (zero — initial value) |
| **Waveform signal** `minMax.trackingMode` at time 0 | 1 (since enable=1, clear=0, reset=0) |
| **Computed assertion** `!trackingMode \|\| min <= max` | `0 \|\| 0` = **0 (FALSE)** |
| **Computed correct values** `sup`, `inf` | Both 0 (so after first clock edge, min=max=0 and assertion would pass) |

### Possible Fixes

**Option A — Fix the Assertion (recommended):** Add a `past()` guard to skip the first cycle:
```scala
val trackingMode = io.enable && !io.clear && !io.reset
val stable = past(io.enable, 1, init=false) // false in first cycle
fvAssert(!stable || !trackingMode || min <= max, "min_leq_max")
```

**Option B — Fix the Setup:** Constrain the formal environment to assert `io.reset` at power-on so that `trackingMode` is deasserted during the first cycle when registers still have extreme initial values.

**Option C — Use hasBeenReset:** Incorporate the existing `hasBeenReset` signal into the guard:
```scala
val trackingMode = io.enable && !io.clear && !io.reset && hasBeenReset
fvAssert(!trackingMode || min <= max, "min_leq_max")
```
