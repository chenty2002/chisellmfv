# Counterexample Analysis: minMax.min_leq_max

## 1. Verification Environment

- **Top module**: `minMax` (from `minMax.scala`)
- **Module structure**: A min/max tracking and averaging module with 128-bit unsigned arithmetic
  - `min`: Register tracking minimum observed input value (initialized to all 1s = max unsigned value)
  - `max`: Register tracking maximum observed input value (initialized to all 0s)
  - `sup` / `inf`: Combinational wires tracking `max(io.in, max)` and `min(io.in, min)` respectively
  - `avg`: Average of sup and inf
  - `io_out`: Output selection (clear→0, disabled→last, reset→io.in, tracking→avg)
  - `last`: Register storing last input in tracking mode
  - `trackingMode`: Defined as `io.enable && !io.clear && !io.reset`
- **Formal framework**: Chisel Formal (`chiselFv`) with JasperGold backend
- **Key signals**: `min`, `max`, `sup`, `inf`, `io_in`, `io_enable`, `io_clear`, `io_reset`

## 2. Violated Assertion

- **Assertion name**: `min_leq_max`
- **Waveform file**: `minMax.min_leq_max.fst`
- **Code snippet** (from `minMax.scala`, line 56):
  ```scala
  fvAssert(min <= max, "min_leq_max")
  ```
- **Generated Verilog** (from `generated/minMax.sv`, lines 56-57):
  ```verilog
  min_leq_max: assert property (@(posedge clock) disable iff (~hasBeenReset) min <= max);
  ```
- **Property description**: At every positive edge of the clock (after reset has occurred), the assertion checks that `min <= max` as unsigned 128-bit values. This is intended as an invariant that the minimum value stored should never exceed the maximum value stored.
- **File location**: `minMax.scala`, line 56

## 3. Waveform Information

- **Waveform file**: `verilog/extra_bench/param_minmax/minMax.min_leq_max.fst`
- **Time range**: 0 ns to 10 ns (1 full clock cycle)
- **Key time points**:
  - **t=0 ns** (posedge clock): All signals are stable at their initial/reset values
  - **t=5 ns** (negedge clock): Signal values unchanged
  - **t=10 ns** (end of cycle): Signal values unchanged

- **Critical signal values at t=0 (posedge clock)**:

| Signal | Value | Interpretation |
|--------|-------|----------------|
| `min` | 0xFFFF...FF (all 128 bits = 1) | Maximum unsigned 128-bit value |
| `max` | 0x0 (all 128 bits = 0) | Minimum unsigned 128-bit value |
| `min_leq_max` | 1 (assertion signal) | Assertion status at this cycle |
| `io_in` | 0x0 | Input data |
| `io_enable` | 0 | Module disabled |
| `io_clear` | 0 | Not clearing |
| `io_reset` | 0 | Not resetting |
| `hasBeenResetReg` | 1 | Module has been reset |
| `trackingMode` | 0 | Not in tracking mode (io_enable=0) |
| `_inf_T` (io_in < min) | 1 | 0 < all_1s is true |
| `_sup_T` (io_in > max) | 0 | 0 > 0 is false |
| `inf` | 0x0 | min(io_in, min) = min(0, all_1s) = 0 |
| `sup` | 0x0 | max(io_in, max) = max(0, 0) = 0 |

- **Assertion evaluation at t=0**: The condition `min <= max` evaluates to `0xFFFF...FF <= 0x0` = **false (0)** in unsigned comparison. The assertion `disable iff (~hasBeenReset)` is not triggered (hasBeenReset=1), so the assertion fires and **fails**.

## 4. Root Cause Analysis

### Error Classification: **Assertion Bug**

### Location
- **File**: `minMax.scala`
- **Line**: 56
- **Assertion**: `fvAssert(min <= max, "min_leq_max")`

### The Bug

The assertion `min <= max` is **unqualified** — it checks the invariant at every clock cycle unconditionally (after initial reset). However, the design legitimately holds `min = all_1s` (0xFFFF...FF) and `max = 0` in the following valid states:

1. **io_enable = false** (disabled mode — line 37-39): the module resets min and max to their initialization values
2. **io_clear = true** (clear mode — line 30-34): same reset behavior
3. **io_reset = true** with enable (line 42-44): same reset behavior
4. **Initial state** after hardware reset (line 29-33 of Verilog): same initialization

This initialization pattern is **correct and standard** for a min/max tracking algorithm:
- `min` is initialized to the **maximum possible value** so that any observed input will be ≤ min, triggering an update
- `max` is initialized to the **minimum possible value** (0) so that any observed input will be ≥ max, triggering an update

Without this initialization pattern, the algorithm would not correctly track min/max values. For example, if `min` started at 0 and the first input was 5, `min` would incorrectly stay at 0 (since 5 > 0), missing the true minimum.

### Why the Assertion Fails

In the counterexample trace:
- `io_enable = 0` throughout (module is disabled)
- `io_in = 0` throughout (input data)
- Sequential logic executes: `elsewhen(!io.enable)` → `max := 0.U; min := "hFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF".U`
- At the posedge clock, register values are: `min = all_1s` (0xFFFF...FF) and `max = 0`
- The assertion `min <= max` evaluates to `0xFFFF...FF <= 0x0` = **false**
- **The assertion fails because the design correctly uses opposite extreme values for min and max during initialization/disabled states**

### Evidence from Waveform

The waveform shows that at all time points (0, 5, 10 ns):
- `min` = 0xFFFF...FF (all 128 bits high)
- `max` = 0x0 (all 128 bits low)  
- `io_enable = 0` and `io_in = 0` (no inputs being tracked)
- This is a stable state where min > max, which violates the unconditional assertion

### The Other Assertions Pass Correctly

The other four combinatorial assertions in the same counterexample trace are satisfied:
- `sup_geq_max`: sup=0, max=0 → 0 ≥ 0 ✓
- `inf_leq_min`: inf=0, min=all_1s → 0 ≤ all_1s ✓
- `sup_geq_in`: sup=0, io_in=0 → 0 ≥ 0 ✓
- `inf_leq_in`: inf=0, io_in=0 → 0 ≤ 0 ✓

This confirms that only the `min_leq_max` assertion has the unqualified condition problem.

### Recommended Fix

The assertion should be qualified so it only checks the invariant when the module is actively tracking min/max values (i.e., during normal operation when `io.enable` is true and the module is not being cleared or reset):

```scala
// Fix: Only check min <= max during active tracking
val trackingMode = io.enable && !io.clear && !io.reset
fvAssert(!trackingMode || min <= max, "min_leq_max")
```

This fix follows the same pattern already used in the design for the `output_is_avg_in_tracking_mode` assertion (line 73), which correctly qualifies the invariant with `!trackingMode || ...`.
