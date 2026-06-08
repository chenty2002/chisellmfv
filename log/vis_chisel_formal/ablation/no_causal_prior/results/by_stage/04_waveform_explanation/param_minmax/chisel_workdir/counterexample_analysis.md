# Counterexample Analysis Report: `min_leq_max`

## 1. Verification Environment

- **Top module**: `minMax` (from `minMax.scala`)
- **Module type**: A min/max/avg tracker with 128-bit unsigned values, built with Chisel and the `chiselFv` formal verification library
- **Key components**:
  - `min` (RegInit, 128-bit): tracks minimum value encountered, initialized to 0xFFFF...FF
  - `max` (RegInit, 128-bit): tracks maximum value encountered, initialized to 0x000...00
  - `last` (RegInit, 128-bit): last input value seen
  - `sup = Mux(io.in > max, io.in, max)`: combinatorial upper bound
  - `inf = Mux(io.in < min, io.in, min)`: combinatorial lower bound
  - `avg = (sup + inf) / 2`: computed average
  - `io.enable`, `io.clear`, `io.reset`: control inputs
  - `io.in` (128-bit): data input
  - `io.out` (128-bit): data output
- **Connections**: The module takes control signals and a data input, tracks min/max values, and outputs the average (or last/input/zero depending on control state)

## 2. Violated Assertion

- **Full assertion name**: `min_leq_max` (from waveform filename `minMax.min_leq_max.fst`)
- **Code snippet** (line 62 of `minMax.scala`):

```scala
// Safety 1: min must never exceed max (core invariant of the tracker)
fvAssert(min <= max, "min_leq_max")
```

- **Natural language description**: The assertion checks that the 128-bit unsigned register `min` is always less than or equal to the 128-bit unsigned register `max` at every clock cycle.
- **File location**: `minMax.scala`, line 62

## 3. Waveform Information

- **Full waveform path**: `verilog/extra_bench/param_minmax/minMax.min_leq_max.fst`
- **Waveform duration**: 1 cycle (0 ns → 10 ns)
- **Key time points and signal values**:

At **time 0 ns, 5 ns, and 10 ns** (all points identical — no signal transitions):

| Signal | Value |
|--------|-------|
| `minMax.io_clear` | 0 |
| `minMax.io_enable` | 0 |
| `minMax.io_reset` | 0 |
| `minMax.io_in [127:0]` | 0x000...00 (all zeros) |
| `minMax.min [127:0]` | 0xFFFF...FF (all ones = 2^128-1) |
| `minMax.max [127:0]` | 0x000...00 (all zeros) |
| `minMax.normal_update` | 0 |
| `minMax.min_leq_max` | 1 (assertion **violated**) |
| `minMax.sup [127:0]` | 0x000...00 |
| `minMax.inf [127:0]` | 0x000...00 |
| `minMax._GEN` | 1 |
| `minMax._GEN_0` | 0 |
| `minMax._GEN_1` | 0 |

## 4. Root Cause Analysis

### Category: **Assertion error** (Incorrect Assertion)

The assertion `fvAssert(min <= max, "min_leq_max")` is checked **unconditionally at every cycle**, but the design intentionally violates `min <= max` during certain operational states.

### Why the assertion fails

When `io.enable = 0` (idle state), the design enters the `elsewhen(!io.enable)` branch (line 71):

```scala
.elsewhen(!io.enable) {
    max := 0.U
    min := "hFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF".U
}
```

This explicitly sets `max = 0` and `min = 0xFFFF...FF`, making `min > max`. This is the **intended idle behavior** — the tracker resets its min and max to extreme sentinel values so that when tracking resumes, any input will properly update both registers.

Additionally, the same violation pattern occurs in:
- **Initial state** (`RegInit` values): `min = 0xFFFF...FF`, `max = 0x000...00` (line 56-58)
- **io.clear branch**: also sets `min = 0xFFFF...FF`, `max = 0` (line 66-67)
- **io.reset branch**: also sets `min = 0xFFFF...FF`, `max = 0` (line 75-76)

The assertion only holds during `normal_update` (`io.enable && !io.clear && !io.reset`), when `max := sup` and `min := inf`. Since `sup >= io.in >= inf` (Safeties 5 & 6), we have `sup >= inf`, so `min <= max` holds.

### Why the assertion is incorrect

The intended invariant — "min must never exceed max" — is only meaningful **when the tracker is actively tracking values**. During idle, clear, and reset states, the design intentionally uses sentinel values (min=max possible value, max=0) to prepare for the next tracking session.

Other assertions in the same file correctly use gating conditions. For example:
- **Safety 4** (line 91-93): `assertImplies(normal_update, max === sup && min === inf, ...)`
- **Safety 5** (line 95-97): `assertImplies(normal_update, sup >= max && sup >= io.in, ...)`
- **Safety 6** (line 99-101): `assertImplies(normal_update, inf <= min && inf <= io.in, ...)`

Safety 1 (`min_leq_max`) is the **only** unconditional `fvAssert` that should actually be gated.

### Recommended Fix

Change the unconditional assertion to a gated one, e.g.:

```scala
// Option A: gate by io.enable (check only when tracking is active)
assertImplies(io.enable, min <= max, "min_leq_max")

// Option B: gate by normal_update (even more precise - exclude clear/reset cycles)
assertImplies(normal_update, min <= max, "min_leq_max")
```

Option A is slightly more conservative (checks during reset cycles too, where min/max are set to sentinels but that's also the reset initialization). Option B only checks during the actual normal update cycles. Either fix resolves the counterexample.
