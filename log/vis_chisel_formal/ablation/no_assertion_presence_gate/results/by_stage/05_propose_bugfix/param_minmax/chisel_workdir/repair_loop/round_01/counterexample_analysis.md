# Counterexample Analysis Report: `last_stores_previous_in`

## 1. Verification Environment

- **Top Module**: `minMax`
- **Package**: `llmverify`
- **Source File**: `minMax.scala` (87 lines)
- **Design**: A hardware min/max tracker with average calculation. The module tracks the running minimum and maximum of input values (`io.in`) and computes their average (`avg`). It supports `clear`, `enable`, and `reset` control signals.
- **Key Components**:
  - `min` (UInt, 128-bit) — running minimum, initialized to all 1s
  - `max` (UInt, 128-bit) — running maximum, initialized to 0
  - `last` (UInt, 128-bit) — captures `io.in` when enabled and not reset
  - `sup` — unsigned max of `io.in` and `max` (combinational)
  - `inf` — unsigned min of `io.in` and `min` (combinational)
  - `avg` — average of `sup` and `inf` with carry
  - `REG` — register created by `RegNext(io.in)` inside the assertion

## 2. Violated Assertion

- **Assertion Name**: `last_stores_previous_in` (from waveform filename `minMax.last_stores_previous_in.fst`)
- **Full Assertion Code** (line 80-84 of `minMax.scala`):

```scala
assertNextStepWhen(
    io.enable && !io.reset && !io.clear,
    last === RegNext(io.in),
    "last_stores_previous_in")
```

- **Natural Language Description**: Whenever the enable signal is asserted and reset and clear are de-asserted in a given cycle, then in the **next** cycle, the `last` register should equal the value of `io.in` from the previous cycle (captured by `RegNext(io.in)`).

- **File Location**: `minMax.scala`, lines 80–84.

## 3. Waveform Information

- **Waveform File**: `verilog/extra_bench/param_minmax/minMax.last_stores_previous_in.fst`
- **Time Range**: 0 ns to 10 ns (single clock cycle)
- **Clock**: Rising edge at 0 ns, falling edge at 5 ns

### Critical Signal Values at the Failure Point (time 0 ns)

| Signal | Value |
|--------|-------|
| `minMax.last_stores_previous_in` (assertion violation) | **1** (FAILURE) |
| `minMax._GEN` (past_cond / enable signal for assertion) | **1** |
| `minMax.last [127:0]` | `0x000...0` (all zeros) |
| `minMax.REG [127:0]` (RegNext(io.in)) | `0x1000...0` (only bit 127 set) |
| `minMax.io_in [127:0]` | `0xFFF...F` (all ones) |
| `minMax.io_clear` | 1 |
| `minMax.io_enable` | 1 |
| `minMax.io_reset` | 1 |
| `minMax.hasBeenReset` | 1 |
| `minMax.hasBeenResetReg` | 1 |

### Key Observations

All signals remain constant throughout the single-cycle trace (time 0 to time 10). The assertion violation signal `last_stores_previous_in` is asserted (value 1) at both time points.

## 4. Root Cause Analysis

### Type of Error: **Assertion Error**

The assertion `last_stores_previous_in` is **incorrectly specified** — it lacks a guard against the initial state (cycle 0) where `$past()` / `RegNext()` values are non-deterministic.

### Root Cause Explanation

The `assertNextStepWhen` construct generates hardware equivalent to:

```systemverilog
// Pseudo-code for the assertion hardware
assert property (@(posedge clk) $past(cond) |-> (last === RegNext(io.in)));
```

At **cycle 0** (the very first positive clock edge), the `$past()` operator returns an **unknown/non-deterministic value** because there is no prior clock cycle. The formal verification solver can freely choose this value. In this counterexample:

1. **`$past(cond)` is set to 1 by the solver** — this enables the assertion check at cycle 0, even though the condition `io.enable && !io.reset && !io.clear` evaluates to `1 && !1 && !1 = 0` at cycle 0 itself. This is the critical exploitation point.

2. **`RegNext(io.in)` (the `REG` register) is non-deterministic at cycle 0** — the solver chooses `0x1000...0` (only bit 127 set).

3. **`last` has a deterministic initial value of `0x000...0`** — from `RegInit(0.U(128.W))`.

4. Since `last (0x000...0) !== REG (0x1000...0)`, the property `last === RegNext(io.in)` evaluates to **false**, and the assertion **fails**.

### Why This Is NOT a DUT Bug

The DUT logic is correct. When the condition `io.enable && !io.reset && !io.clear` is true in a normal (non-initial) cycle:
- `last := io.in` is executed on the positive edge
- In the next cycle, `last` holds the value from the prior cycle's `io.in`
- `RegNext(io.in)` in that next cycle also holds the prior cycle's `io.in`
- Therefore `last === RegNext(io.in)` would be true in any non-initial cycle

The failure occurs **exclusively** because the formal solver exploits the non-deterministic initial values of `$past()` and `RegNext()` at cycle 0.

### Why Not a Setup Error

The test harness correctly provides `hasBeenReset = 1`. The issue is that the assertion itself does not check for `hasBeenReset`.

### Fix

The assertion must be guarded by `hasBeenReset` to exclude the initial (pre-reset) cycle where `$past()` values are undefined:

```scala
assertNextStepWhen(
    hasBeenReset && io.enable && !io.reset && !io.clear,
    last === RegNext(io.in),
    "last_stores_previous_in")
```

Adding `hasBeenReset` ensures the assertion only fires after the design has been properly initialized and at least one clock cycle has elapsed, making `$past()` values well-defined.

### Summary Table

| Aspect | Detail |
|--------|--------|
| **Error type** | `assertion_error` |
| **Bug location** | `minMax.scala`, line 80-84 |
| **Root cause** | `assertNextStepWhen` fired at cycle 0 where `$past()` is non-deterministic; missing `hasBeenReset` guard |
| **Fix** | Add `hasBeenReset &&` before the condition in the assertion |
| **DUT bug?** | No — the design logic is correct |
