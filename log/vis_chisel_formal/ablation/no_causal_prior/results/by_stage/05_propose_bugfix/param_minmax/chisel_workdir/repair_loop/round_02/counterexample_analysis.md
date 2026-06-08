# Counterexample Analysis Report: `param_minmax`

## 1. Verification Environment

- **Top Module**: `minMax` (in `package llmverify`)
- **Source File**: `chisel/extra_bench/param_minmax/minMax.scala`
- **Design Under Test**: A min/max tracker that monitors a stream of 128-bit unsigned values (`io.in`), tracking the minimum and maximum values seen so far, and computing the average of the current sup (max of old max vs input) and inf (min of old min vs input). It supports `clear`, `reset`, and `enable` control signals.
- **Key Components**:
  - `min`, `max`: Registers tracking min/max values
  - `last`: Register tracking the last input value
  - `sup`, `inf`: Combinational logic computing candidates for new max/min
  - `prev_sup`, `prev_inf`: Shadow registers (`RegNext`) capturing previous cycle's sup/inf for next-cycle assertions
  - `avg`: Average of `sup` and `inf` (sum >> 1)

## 2. Violated Assertion

- **Assertion Name**: `liveness_max_min_update_next_cycle` (extracted from waveform filename `minMax.liveness_max_min_update_next_cycle.fst`)
- **Code Location**: `minMax.scala`, lines 115–117

```scala
// Liveness 12: bounded liveness - when enable is high and not clearing/resetting,
// after exactly 1 cycle the registers update to reflect the new min/max
assertNextStepWhen(normal_update,
    max === prev_sup && min === prev_inf,
    "liveness_max_min_update_next_cycle")
```

- **Natural Language Property**: Whenever there is a normal update (enable is active, and neither clear nor reset is asserted), then in the *next* cycle, `max` and `min` registers should have updated to match `prev_sup` and `prev_inf` (which are the `RegNext` copies of `sup` and `inf` from the update cycle).

## 3. Waveform Information

- **Full Path**: `verilog/extra_bench/param_minmax/minMax.liveness_max_min_update_next_cycle.fst`
- **Time Range**: 0 ns → 10 ns (1 full clock cycle, period = 10 ns)
- **Key Signals at Time 0 (initial state, clock = 1)**:

| Signal | Value |
|--------|-------|
| `minMax.clock` | 1 |
| `minMax.normal_update` | 1 |
| `minMax.liveness_max_min_update_next_cycle` | 1 |
| `minMax.io_enable` | 1 |
| `minMax.io_clear` | 0 |
| `minMax.io_reset` | 0 |
| `minMax.io_in [127:0]` | 0x0000...0000 |
| `minMax.max [127:0]` | 0x0000...0000 |
| `minMax.min [127:0]` | 0xFFFF...FFFF |
| `minMax.sup [127:0]` | 0x0000...0000 |
| `minMax.inf [127:0]` | 0x0000...0000 |
| `minMax.prev_sup [127:0]` | 0x0000...0000 |
| `minMax.prev_inf [127:0]` | 0x0000...0000 |
| `minMax.hasBeenResetReg` | 1 |

- **Key Observations**:
  - At all sampled times (0, 5, 10 ns), `normal_update = 1` and `liveness_max_min_update_next_cycle = 1`.
  - `min` is initialized to all 1s (0xFFFF...FFFF, the maximum unsigned 128-bit value, a sentinel).
  - `prev_inf` (`RegNext(inf)`) is initialized to 0.
  - Therefore, at cycle 0: `min === prev_inf` evaluates to **FALSE** (0xFFFF...FFFF ≠ 0).

## 4. Root Cause Analysis

### Root Cause Category: **Incorrect Assertion**

The assertion is incorrectly formulated. The failure is due to a **mismatch between the initialization values of `min` and `prev_inf`** in the initial cycle, combined with the semantics of `assertNextStepWhen`.

#### How `assertNextStepWhen` Works

In the ChiselFv library, `assertNextStepWhen(cond, expr, msg)` is implemented approximately as:

```scala
val rCond = RegNext(cond)
val rExpr = RegNext(expr)
fvAssert(rCond ? rExpr : true.B, msg)
```

That is, **both** `cond` and `expr` are registered (`RegNext`), and in cycle N+1 the property checks whether `expr` was true in cycle N, **given** that `cond` was true in cycle N.

#### The Failure Mechanism

At cycle 0 (initial state, before any posedge):

| Signal | Value | Source |
|--------|-------|--------|
| `normal_update` | 1 | `io.enable && !io.clear && !io.reset` (all inputs are 1, 0, 0) |
| `max` | 0 | `RegInit(0.U(128.W))` |
| `prev_sup` | 0 | `RegNext(sup)` initialized to 0 |
| `min` | 0xFFFF...FFFF | `RegInit("hFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF".U(128.W))` |
| `prev_inf` | 0 | `RegNext(inf)` initialized to 0 |
| `max === prev_sup` | **TRUE** | 0 === 0 ✓ |
| `min === prev_inf` | **FALSE** | 0xFFFF...FFFF !== 0 ✗ |

1. At cycle 0: `normal_update = 1`, but the expression `max === prev_sup && min === prev_inf = FALSE`.
2. `RegNext(expr)` captures `FALSE` at the cycle-0 posedge.
3. At cycle 1 (the next posedge at ~10 ns): `RegNext(normal_update) = 1` (premise true), `RegNext(expr) = FALSE` → **assertion fails**.

#### Why This Is Not a Design Bug

The **same property** is correctly checked at line 96 using `assertImplies`:

```scala
assertImplies(RegNext(normal_update),
    max === prev_sup && min === prev_inf,
    "normal_update_tracks_sup_inf")
```

This works because `assertImplies(rCond, expr)` evaluates `expr` in the **current** cycle (after registers have updated), whereas `assertNextStepWhen(cond, expr)` registers the expression and evaluates it in the **next** cycle (which is one cycle too late). The line-96 assertion passes because it correctly checks the updated register values one cycle after `normal_update` was asserted.

#### Verification

The assertion at line 96 (`normal_update_tracks_sup_inf`) is active in the same waveform (`normal_update = 1` at all times, same inputs), and it does **not** fail because:
- At cycle 1: `RegNext(normal_update) = 1`, `max` = sup from cycle 0, `prev_sup` = sup from cycle 0 → `max === prev_sup` holds. `min` = inf from cycle 0, `prev_inf` = inf from cycle 0 → `min === prev_inf` holds. The assertion passes.

The redundant liveness assertion at line 115–117 fails solely because `assertNextStepWhen` registers the expression, causing it to be checked against the **previous** cycle's expression values rather than the **current** cycle's.

### Recommended Fix

**Option A**: Replace `assertNextStepWhen` with the same `assertImplies(RegNext(...), ...)` pattern used at line 96, since the two assertions are semantically identical:

```scala
assertImplies(RegNext(normal_update),
    max === prev_sup && min === prev_inf,
    "liveness_max_min_update_next_cycle")
```

**Option B**: Gate the `assertNextStepWhen` to skip the first cycle, avoiding the initialization mismatch:

```scala
val notFirstCycle = RegNext(true.B, false.B)
assertNextStepWhen(normal_update && notFirstCycle,
    max === prev_sup && min === prev_inf,
    "liveness_max_min_update_next_cycle")
```

**Option C**: Use a direct `fvAssert` with proper next-cycle semantics:

```scala
fvAssert(RegNext(normal_update) ? (max === prev_sup && min === prev_inf) : true.B,
    "liveness_max_min_update_next_cycle")
```

All three options eliminate the spurious failure from the initialization mismatch while preserving the intended property check.

### Buggy Code Location and Fix

- **File**: `chisel/extra_bench/param_minmax/minMax.scala`
- **Lines**: 115–117
- **Bug**: The `assertNextStepWhen` assertion registers both condition and expression, causing the expression to be checked one cycle too late, and the initial cycle provides a FALSE expression value that triggers a false failure.
- **Fix**: Replace with `assertImplies(RegNext(normal_update), max === prev_sup && min === prev_inf, "liveness_max_min_update_next_cycle")` (Option A is recommended as simplest and most consistent with the existing pattern at line 96).
