# Counterexample Analysis Report

## 1. Verification Environment

- **Top module**: `minMax` (from `minMax.scala`, package `llmverify`)
- **Key components**:
  - Three registers: `min` (tracks minimum, init to all-ones), `max` (tracks maximum, init to 0), `last` (last input, init nondeterministically)
  - Combinational wires: `sup` (max of `io.in` and `max`), `inf` (min of `io.in` and `min`), `avg` (average = (sup + inf) / 2, floor division)
- **Control inputs**: `io.clear` (synchronous reset), `io.enable` (enable tracking), `io.reset` (internal reset)
- **Verification tool**: Jasper Formal (Chisel formal verification with `fvAssert` and `astRelaxedLiveness`)

## 2. Violated Assertion

- **Assertion name**: `tracking_produces_avg`
- **Full waveform filename**: `minMax.tracking_produces_avg.fst`
- **Source file**: `minMax.scala`, line 113
- **Code snippet**:

```scala
astRelaxedLiveness(io.enable && !io.reset, io.out === avg, 1, "tracking_produces_avg")
```

- **Intended property**: Whenever the module is in "tracking" mode (enable is high AND reset is low), the output `io.out` must equal the computed average `avg` within 1 clock cycle.

## 3. Waveform Information

- **Waveform file**: `verilog/extra_bench/minmax_minMax/minMax.tracking_produces_avg.fst`
- **Duration**: 0–30 ns (3 clock cycles, period = 10 ns)
- **Key time points**:
  - `t=0 ns`: Rising clock edge, `io.clear=1`, `io.enable=1`, `io.reset=0`, `io.out=0`, `avg=255`
  - `t=10 ns`: Rising clock edge, `io.clear=1`, `io.enable=1`, `io.reset=1`, `io.out=0`, `avg=511`
  - `t=20 ns`: Rising clock edge, `io.clear=1`, `io.enable=1`, `io.reset=1`, `io.out=0`, `avg=7`

### Critical Signal Trace

| Time (ns) | `tracking_produces_avg` | `io.clear` | `io.enable` | `io.reset` | `io.out` | `avg_debug` | `sup_debug` | `inf_debug` | `io.in` |
|-----------|------------------------|------------|-------------|------------|----------|-------------|-------------|-------------|---------|
| 0         | 1 (passing)            | 1          | 1           | 0          | 0        | 255         | 255         | 255         | 255     |
| 5         | 1                      | 1          | 1           | 0          | 0        | 255         | 255         | 255         | 255     |
| 10        | 1                      | 1          | 1           | 1          | 0        | 511         | 511         | 511         | 511     |
| 20        | 0 (FAILED)             | 1          | 1           | 1          | 0        | 7           | 7           | 7           | 7       |

## 4. Root Cause Analysis

### Root Cause: Incorrect Assertion (Missing Constraint on `io.clear`)

**Classification**: `assertion_error` — the assertion's trigger condition is incorrect.

### Detailed Explanation

The assertion is:
```scala
astRelaxedLiveness(io.enable && !io.reset, io.out === avg, 1, "tracking_produces_avg")
```

**The trigger condition** `io.enable && !io.reset` is activated at time 0 (cycle 0) because:
- `io.enable = 1` ✅
- `io.reset = 0` ✅ (io.reset is `!io.reset`, so `!io.reset = 1`)

**The target condition** `io.out === avg` requires the output to equal the average.

**Why the target fails**: At time 0, `io.clear` is also high (1). Looking at the output logic (line 92):

```scala
io.out := Mux(io.clear, 0.U,
    Mux(!io.enable, last,
      Mux(io.reset, io.in, avg)))
```

The priority encoding for `io.out` is: **clear overrides everything**. When `io.clear = 1`, `io.out` is forced to `0.U` regardless of any other control signals. Since `io.clear` remains high at all times in the counterexample, `io.out` stays 0 forever, while `avg` keeps changing (255 → 511 → 7), so `io.out === avg` is never satisfied.

**Why this is an assertion bug, not a DUT bug**:

The DUT's output logic is intentionally designed with `io.clear` having the highest priority — when clear is asserted, the module resets its internal state and drives the output to 0. This is a legitimate design choice.

The other assertions in the same file correctly account for `io.clear`. For example, the sibling assertion `tracking_out_avg` (line 103) is written as:

```scala
fvAssert(io.clear || !io.enable || io.reset || io.out === avg, "tracking_out_avg")
```

This is logically equivalent to: **if (`!io.clear` AND `io.enable` AND `!io.reset`) THEN `io.out === avg`** — correctly excluding the clear case.

The `tracking_produces_avg` assertion uses `astRelaxedLiveness` with trigger `io.enable && !io.reset`, but it should **also** require `!io.clear` in the trigger condition.

### Correct Fix

The assertion should be changed from:

```scala
astRelaxedLiveness(io.enable && !io.reset, io.out === avg, 1, "tracking_produces_avg")
```

to:

```scala
astRelaxedLiveness(io.enable && !io.reset && !io.clear, io.out === avg, 1, "tracking_produces_avg")
```

This ensures the assertion only checks the tracking output when the module is truly in tracking mode (not being cleared, not being reset, and enabled).
