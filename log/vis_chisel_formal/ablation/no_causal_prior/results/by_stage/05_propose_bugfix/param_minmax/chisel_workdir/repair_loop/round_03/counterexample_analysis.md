# Counterexample Analysis Report

## 1. Verification Environment

- **Top Module**: `minMax` (package `llmverify`)
- **Design Under Test**: A parametric min/max tracker that tracks the running minimum and maximum of input values over time. It also computes the average of the current min and max values.
- **Key Components**:
  - `min` (UInt(128.W)): Register tracking the running minimum
  - `max` (UInt(128.W)): Register tracking the running maximum
  - `last` (UInt(128.W)): Register tracking the last input value
  - `sup` (combinational): `Mux(io.in > max, io.in, max)` — the upper bound candidate
  - `inf` (combinational): `Mux(io.in < min, io.in, min)` — the lower bound candidate
  - `prev_sup`, `prev_inf`: Shadow registers for next-cycle assertion checks
  - `normal_update`: `io.enable && !io.clear && !io.reset` — gate for active tracking mode
  - `notFirstCycle`: Guard register to skip cycle 0 where sentinel values are present
- **Inputs**: `io.in` (128-bit data), `io.enable`, `io.clear`, `io.reset` (control signals)

## 2. Violated Assertion

- **Full Assertion Name**: `min_leq_max` (from waveform filename `minMax.min_leq_max.fst`)
- **Source Location**: `minMax.scala`, line 70

### Code Snippet

```scala
// Safety 1: min must never exceed max (core invariant of the tracker)
// Gated by normal_update because min > max during clear, idle, and reset states
// where sentinel values are intentionally used.
// Also gated by notFirstCycle to skip cycle 0 where sentinel values violate.
assertImplies(normal_update && notFirstCycle, min <= max, "min_leq_max")
```

### Property Description

The assertion checks: **If the module is in normal update mode (enable=1, clear=0, reset=0) and it is not the first cycle, then the running minimum should be less than or equal to the running maximum.**

## 3. Waveform Information

- **Waveform File**: `verilog/extra_bench/param_minmax/minMax.min_leq_max.fst`
- **Duration**: 0 ns → 20 ns (2 clock cycles)
- **Key Time Points**:

### Time 0 ns (First Posedge)
| Signal | Value | Notes |
|--------|-------|-------|
| `min` | `0xFFFF...FFFF` (all 1s) | RegInit sentinel value |
| `max` | `0x0000...0000` (all 0s) | RegInit sentinel value |
| `io_in` | `0x7FFF...FF7F` | Input data |
| `io_enable` | `0` | Idle cycle |
| `io_clear` | `0` | |
| `io_reset` | `0` | |
| `normal_update` | `0` | Gated off, assertion does not fire |
| `sup` | `0x7FFF...FF7F` | Combinational: `io_in > max` → true |
| `inf` | `0x7FFF...FF7F` | Combinational: `io_in < min` → true |

**Sequential action at time 0**: Since `!io.enable` is true, the `elsewhen(!io.enable)` branch fires:
- `max := 0.U` (stays at 0)
- `min := 0xFFFF...FFFF.U` (stays at sentinel)

### Time 10 ns (Second Posedge — ASSERTION FAILS)
| Signal | Value | Notes |
|--------|-------|-------|
| `min` | `0xFFFF...FFFF` (all 1s) | Still sentinel from previous cycle |
| `max` | `0x0000...0000` (all 0s) | Still sentinel from previous cycle |
| `io_in` | `0x7FFF...FF7F` | Same input data |
| `io_enable` | `1` | Now active! |
| `normal_update` | `1` | Condition met for assertion |
| `notFirstCycle` | `1` | Guard met |
| `sup` | `0x7FFF...FF7F` | Combinational: `io_in > max` → true |
| `inf` | `0x7FFF...FF7F` | Combinational: `io_in < min` → true |
| `min_leq_max` | `0` | **ASSERTION FAILS**: `min <= max` is `0xFFFF...FFFF <= 0` = FALSE |

**Sequential action at time 10**: Since `normal_update` is true and not reset/clear, the `.otherwise` branch fires:
- `max := sup = 0x7FFF...FF7F`
- `min := inf = 0x7FFF...FF7F`

If the waveform continued to time 20, min and max would both equal `0x7FFF...FF7F`, and `min <= max` would be TRUE.

## 4. Root Cause Analysis

### Classification: **Incorrect Assertion** (assertion_error)

### Bug Location
- **File**: `minMax.scala`, Line 70
- **Assertion**: `assertImplies(normal_update && notFirstCycle, min <= max, "min_leq_max")`

### Description of the Bug

The assertion's antecedent uses `normal_update` (a combinational signal) to guard the check `min <= max`. However, the `min` and `max` registers are updated via non-blocking assignments (`:=`) inside the `when` block. In Chisel (like Verilog), register assignments take effect **after** the clock edge, not during the combinational evaluation phase.

The problem manifests specifically when **transitioning from an idle cycle (io_enable=0) to an active cycle (io_enable=1)**:

1. **Cycle 0** (idle): `!io.enable` is true → `max := 0.U` and `min := 0xFFFF...FFFF.U` are scheduled
2. **Cycle 1** (active, time 10): The registers still hold their **old** values (`max=0`, `min=0xFFFF...FFFF`), even though `normal_update=1`. The assertion fires on these stale sentinel values and fails (`0xFFFF...FFFF <= 0` is false).

The fix at the sequential level does update correctly (`max := sup = 0x7FFF...FF7F`, `min := inf = 0x7FFF...FF7F`), but that takes effect only **at the next clock edge** (time 20).

### Evidence from Waveform

- At time 0: `io_enable=0`, registers scheduled to be written with sentinel values via the `!io.enable` branch
- At time 10: `io_enable=1`, `normal_update=1`, `notFirstCycle=1`, but `min=0xFFFF...FFFF` and `max=0` (pre-update values from the previous cycle)
- The assertion fires immediately and sees `min=0xFFFF...FFFF > max=0`

### Why Other Assertions Got It Right

Note that Safety 4 (line 90-92) uses the correct timing guard:
```scala
assertImplies(RegNext(normal_update) && notFirstCycle,
    max === prev_sup && min === prev_inf,
    "normal_update_tracks_sup_inf")
```

This uses `RegNext(normal_update)` to check **one cycle after** the update, when the registers have actually taken their new values. The failing assertion should follow the same pattern.

### Proposed Fix

Change line 70 from:
```scala
assertImplies(normal_update && notFirstCycle, min <= max, "min_leq_max")
```
to:
```scala
assertImplies(RegNext(normal_update) && notFirstCycle, min <= max, "min_leq_max")
```

This delays the check by one cycle so that the assertion evaluates `min` and `max` **after** the non-blocking register assignments have taken effect, which is when the invariant `min <= max` truly holds.
