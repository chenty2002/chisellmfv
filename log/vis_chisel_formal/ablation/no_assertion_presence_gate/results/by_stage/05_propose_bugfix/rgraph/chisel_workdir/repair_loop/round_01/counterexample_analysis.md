# Counterexample Analysis Report

## 1. Verification Environment

- **Top Module**: `rgraph` (from `rgraph.scala`)
- **Module Structure**:
  - `rgraph` contains a 12-bit counter (`cnt`), a 1-bit mode register (`mode`), and a 1-bit delay register (`r`)
  - `io_i` (Bool input) and `io_o` (Bool output)
  - `ResetCounter` submodule (handles reset sequencing)
- **Design Under Test**: A state machine with two modes:
  - **Mode 0**: Counter increments by 1 each cycle
  - **Mode 1**: Counter decrements by 1 each cycle when `io_i` is asserted and `cnt != 0`
  - Transition: Mode 0 → Mode 1 when `mode === 0 && io_i`
  - Output `io_o` is true when `cnt === 0`

## 2. Violated Assertion

- **Assertion Name**: `mode_is_monotonic` (from waveform filename `rgraph.mode_is_monotonic.fst`)
- **Source Code (rgraph.scala, line 33)**:
  ```scala
  assertStableWhen(mode === 1.U, mode, "mode_is_monotonic")
  ```
- **Generated Verilog** (in `generated/rgraph.sv` and `verilog/extra_bench/rgraph/rgraph.sv`):
  ```verilog
  mode_is_monotonic:
    assert property (@(posedge clock) disable iff (~hasBeenReset) ~mode | mode == r);
  ```
  where `r` holds the previous-cycle value of `mode` (computed via `r <= mode` on every clock edge).
- **Property Description**: The assertion intends to check that mode is monotonic — once set to 1, it never goes back to 0. However, the generated check `~mode | mode == r` (equivalently `mode |-> mode == r`) checks that **when mode is 1, it must equal its previous-cycle value**. This means mode can NEVER transition from 0 to 1, because on the first cycle where mode=1, the previous value `r` is still 0.
- **File Location**: `rgraph.scala`, line 33

## 3. Waveform Information

- **Waveform File**: `verilog/extra_bench/rgraph/rgraph.mode_is_monotonic.fst`
- **Duration**: 20 ns (2 clock cycles, clock period = 10 ns)
- **Key Time Points**:

| Time (ns) | clock | reset | io_i | mode | r    | cnt[11:0] | io_o | hasBeenReset | mode_is_monotonic (assertion) |
|-----------|-------|-------|------|------|------|-----------|------|-------------|------------------------------|
| 0         | 1     | 0     | 1    | 0    | 0    | 0         | 1    | 1           | 1 (passes vacuously)         |
| 5         | 0     | 0     | 1    | 0    | 0    | 0         | 1    | 1           | 1 (passes vacuously)         |
| 10        | 1     | 0     | 1    | **1** | **0** | 1         | 0    | 1           | **0 (FAILS)**                |
| 15        | 0     | 0     | 1    | 1    | 0    | 1         | 0    | 1           | 0 (still failing)            |

- **Failure Point**: Time = 10 ns (second posedge of clock)
- **Root Cause Signal Values at Failure**:
  - `mode = 1` (just transitioned from 0)
  - `r = 0` (previous value of mode, latched at time 0)
  - Assertion evaluates: `~mode | mode == r` → `0 | (1 == 0)` → **0 (FAIL)**

## 4. Root Cause Analysis

### Classification: **Incorrect Assertion** (`assertion_error`)

### The Bug

The assertion `assertStableWhen(mode === 1.U, mode, "mode_is_monotonic")` generates a check that is **too strict**. The generated SVA property is:

```verilog
assert property (@(posedge clock) disable iff (~hasBeenReset) ~mode | mode == r);
```

This checks: **If mode is 1, it must equal `r` (the previous cycle's mode value).** This requires `mode` to be stable (unchanged) during every cycle where `mode === 1`, which prevents mode from ever transitioning from 0 to 1.

### Why It Fails

1. At time 0 (first posedge clock): `mode=0`, `io_i=1`, so the when-condition `mode === 0.U && io.i` fires, scheduling `mode := 1.U` for the next cycle.
2. At time 10 (second posedge clock): `mode` is updated to 1 (the transition from 0→1 takes effect).
3. At this same cycle, the assertion fires: since `mode=1` and `r=0` (the previous-cycle value of mode, latched at time 0), the condition `mode == r` evaluates to `1 == 0 = 0`, causing the assertion to fail.

### The Intent vs. Reality

- **Intended property**: "Once mode becomes 1, it should never return to 0" (monotonicity — allows 0→1, prevents 1→0)
- **Actual check**: "When mode is 1, it must have been 1 in the previous cycle too" (prevents 0→1, incorrectly)

### Evidence from Waveform

- `mode` transitions cleanly from 0→1 at time 10 (the correct design behavior)
- `r` stays 0 throughout the trace (latched mode=0 at time 0, never changes because mode has only been 1 since time 10)
- The counter `cnt` increments from 0→1 (correct for mode 0 behavior)
- `io_i` is 1 throughout (stimulus enabling the mode transition)
- The design correctly implements the mode transition — the DUT has no bug

### The Fix

The assertion should check the reverse implication: **If mode was 1 in the previous cycle, it must still be 1 now** (i.e., `r |-> mode`). The correct Chisel assertion would be:

```scala
// Check that mode, once 1, never goes back to 0
fvAssert(!(mode === 0.U && past(mode) === 1.U), "mode_is_monotonic")
```

or equivalently using `assertStableWhen` with the past value as the condition:

```scala
assertStableWhen(past(mode) === 1.U, mode, "mode_is_monotonic")
```

This would generate a property like `r |-> mode` (if mode was 1 in the previous cycle, it must be 1 now), which correctly allows the 0→1 transition while preventing any 1→0 transition.
