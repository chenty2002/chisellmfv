# Counterexample Analysis: minmax_minMax

## 1. Verification Environment

- **Benchmark**: `minmax_minMax`
- **Work Directory**: `chisel/extra_bench/minmax_minMax`
- **Top Module**: `minMax` (from `minMax.scala`)
- **Key Components**:
  - `min` register (9-bit): tracks minimum value seen, initialized to all-ones (511)
  - `max` register (9-bit): tracks maximum value seen, initialized to 0
  - `last` register (9-bit): captures the last input value
  - `init_done` register (1-bit): flag intended to prevent tracking assertions from firing before registers have been updated
  - Combinational logic: `sup` (max of `io.in` and `max`), `inf` (min of `io.in` and `min`), `avg` (average of `sup` and `inf`)
- **Design Description**: A min/max tracking module that computes running minimum, maximum, and average of input values.

## 2. Violated Assertion

- **Full Assertion Name**: `min_less_eq_max_during_tracking`
- **Waveform File**: `minMax.min_less_eq_max_during_tracking.fst`
- **Code Snippet** (from `minMax.scala`, lines 96–97):
  ```scala
  val tracking = io.enable && !io.clear && !io.reset && init_done
  fvAssert(!tracking || (min <= max), "min_less_eq_max_during_tracking")
  ```
- **Natural Language Description**: During active tracking mode (enable=1, clear=0, reset=0, and init_done=1), the minimum value tracked (`min`) must always be less than or equal to the maximum value tracked (`max`).
- **File Location**: `minMax.scala`, line 97

## 3. Waveform Information

- **Waveform File Path**: `verilog/extra_bench/minmax_minMax/minMax.min_less_eq_max_during_tracking.fst`
- **Time Range**: 0 ns → 20 ns (2 cycles)

### Key Time Points and Signal Values

| Signal | Time 0 (Cycle 0) | Time 10 (Cycle 1) |
|--------|:---:|:---:|
| `io_enable` | 0 | 1 |
| `io_clear` | 0 | 0 |
| `io_reset` | 0 | 0 |
| `io_in` | 256 | 256 |
| `min` | 511 (`0b111111111`) | 511 (`0b111111111`) |
| `max` | 0 (`0b000000000`) | 0 (`0b000000000`) |
| `init_done` | 0 | 1 |
| `tracking` | 0 | 1 |
| `min_less_eq_max_during_tracking` | 1 (pass, vacuously true) | **0 (FAIL)** |

### Failure Point

The assertion fails at **time = 10 ns** (after the first clock edge, at the start of cycle 1).

## 4. Root Cause Analysis

### Bug Location

- **File**: `minMax.scala`
- **Line**: 90 (the unconditional assignment `init_done := true.B`)
- **Module**: `minMax`

### Bug Description

The `init_done` register is set to `true.B` **unconditionally** at every clock cycle. This means it becomes true after the very first clock edge, regardless of whether the `min` and `max` registers have been updated with valid tracking values.

The design intent (stated in comments on lines 66–68) is that `init_done` should prevent tracking assertions from firing before registers have been properly updated on the first clock edge. However, because `init_done := true.B` is unconditional, it fails when `enable` is low during cycle 0.

### Sequence of Events Leading to the Failure

1. **Cycle 0 (time 0–10 ns)**: Input `enable` is `0`. Due to the `elsewhen(!io.enable)` branch (lines 79–80), both `min` and `max` retain their initialization values:
   - `min` = 511 (all ones, the reset value)
   - `max` = 0 (the reset value)
   - `init_done` is still `0` (initial value)

2. **At the rising clock edge (time 10 ns)**: All registers are updated:
   - `min` ← 511 (from `elsewhen(!io.enable)` branch: `min := Fill(MSB+1, 1.U)`)
   - `max` ← 0 (from `elsewhen(!io.enable)` branch: `max := 0.U`)
   - `init_done` ← `true.B` (unconditional assignment on line 90)

3. **Cycle 1 (time 10–20 ns)**: Input `enable` becomes `1`. Now:
   - `tracking` = `1 && !0 && !0 && 1` = **1** (active)
   - The assertion checks `!tracking || (min <= max)` = `0 || (511 <= 0)` = **0 (FAIL)**

### Why This Is a DUT Bug (Category 1)

The bug is in the design's `init_done` logic. The assertion itself is correct — in proper tracking mode, `min` should always be ≤ `max`. The testbench stimulus (enable low in cycle 0, then high in cycle 1) is a realistic operating scenario. The design incorrectly allows `init_done` to become `true` even when the `min` and `max` registers are in an inconsistent state (min > max).

### Root Causal Evidence

- **`init_done`** changes from 0→1 at time 10 (clock edge), confirming that it becomes true unconditionally
- **`min`** = 511, **`max`** = 0 at both time 0 and time 10 (no change), confirming that the `elsewhen(!io.enable)` branch re-assigns the same initialization values
- **`tracking`** changes from 0→1 at time 10, confirming that all four conditions (enable=1, clear=0, reset=0, init_done=1) are met

### Proposed Fix

The `init_done` assignment should be made conditional so that it only becomes `true` when the circuit is in a valid tracking state — i.e., when `enable` is high and not in `clear` or `reset`. For example:

```scala
when(io.clear || !io.enable || io.reset) {
  init_done := false.B
}.otherwise {
  init_done := true.B
}
```

This way:
- When `enable=0`, `clear=1`, or `reset=1`: `init_done` is reset to `false`, preventing tracking assertions from firing
- When `enable=1`, `clear=0`, and `reset=0`: `init_done` becomes `true` after the registers have been updated with valid `sup` and `inf` values (guaranteeing `min ≤ max`)
