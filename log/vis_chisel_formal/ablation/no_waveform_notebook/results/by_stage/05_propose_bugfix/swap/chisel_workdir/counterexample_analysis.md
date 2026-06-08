# Counterexample Analysis Report

## 1. Verification Environment

- **Top Module**: `swap` (from `swap.scala`)
- **Key Parameters**: `K = 3`, `Nm1 = 7` (default values)
- **Design Under Test**: A register-swap module that performs a single-cycle swap between array elements `x(p)` and `x(m)` where:
  - `p = Mux(io.i >= Nm1, Nm1, io.i)` (clamped input)
  - `m = Mux(p === 0, Nm1, p - 1)` (predecessor index, wrapping around)
  - `x` is an 8-element register array initialized to `[0,1,2,3,4,5,6,7]`
- **Verification Framework**: ChiselFv `fvAssert` generating SVA `assert property` statements

## 2. Violated Assertion

- **Assertion Name**: `swap_m_gets_old_p`
- **Waveform File**: `swap.swap_m_gets_old_p.fst`
- **Source Code** (swap.scala, lines 63–67):
  ```scala
  when (!first_cycle) {
    fvAssert(x(prev_p) === prev_x(prev_m), "swap_p_gets_old_m")
    fvAssert(x(prev_m) === prev_x(prev_p), "swap_m_gets_old_p")
  }
  ```
- **Natural Language Description**: After a swap operation (deferred by one cycle via `RegNext`), the value now stored at `x(prev_m)` should equal the value that was previously stored at `x(prev_p)` — i.e., the two elements should have swapped places correctly.
- **Generated Verilog** (swap.sv, around line 79):
  ```verilog
  swap_m_gets_old_p:
      assert property (@(posedge clock) disable iff (~hasBeenReset)
                       _GEN[prev_m] == _GEN_0[prev_p]);
  ```

## 3. Waveform Information

- **Waveform File**: `verilog/extra_bench/swap/swap.swap_m_gets_old_p.fst`
- **Time Range**: 0 ns → 10 ns (1 clock cycle)
- **Key Time Points**: The assertion fails at time 0 (posedge clock)

### Critical Signal Values at Time 0

| Signal | Value (Decimal) | Description |
|--------|-------|-------------|
| `clock` | 1 | Rising edge |
| `reset` | 0 | Not in reset |
| `hasBeenReset` | 1 | Assertion enabled |
| `io_i` | 7 | Input index |
| `p` | 7 | Current p = clamped(7) = 7 |
| `m` | 6 | Current m = 7-1 = 6 |
| `prev_p` | **1** | Previous-cycle p value |
| `prev_m` | **3** | Previous-cycle m value |
| `x[0..7]` | [0,1,2,3,4,5,6,7] | Initial/reset values |
| `prev_x[0..7]` | [0,1,2,3,4,5,6,7] | Initial/reset values |

### Assertion Failure Calculation

The assertion checks: `x(prev_m) === prev_x(prev_p)`, i.e., `_GEN[prev_m] == _GEN_0[prev_p]`

At time 0:
- `prev_m = 3` → `x(3) = 3`
- `prev_p = 1` → `prev_x(1) = 1`
- **Check**: `3 === 1` → **FALSE** → Assertion violated

## 4. Root Cause Analysis

### Bug Location

**File**: `swap.scala`, **Lines 63–67**
**Bug Type**: **Assertion Error** — the `when (!first_cycle)` guard fails to prevent the assertion from firing on the first cycle after reset.

### Description of the Bug

The Chisel source code wraps the `fvAssert` calls inside a `when (!first_cycle)` block, intending to suppress assertion checking on the very first cycle after reset when `prev_x`, `prev_p`, and `prev_m` registers hold initial/reset-state values that do not correspond to a real swap operation.

However, the generated Verilog reveals that **the `first_cycle` guard is completely absent** — neither `first_cycle` nor any equivalent conditional appears in the generated SVA assertions:

```verilog
swap_m_gets_old_p:
    assert property (@(posedge clock) disable iff (~hasBeenReset)
                     _GEN[prev_m] == _GEN_0[prev_p]);
```

The only guard is `disable iff (~hasBeenReset)`, which enables the assertion when `hasBeenReset = 1`. Since `hasBeenReset` becomes true immediately after reset deasserts, the assertion fires on the very first clock cycle.

### Why This Causes the Assertion to Fail

On the first cycle after reset:

1. **`hasBeenReset = 1`** — The assertion is enabled (the `disable iff (~hasBeenReset)` condition is false).

2. **`prev_p = 1`, `prev_m = 3`** — These `RegNext` registers captured random/initial values. In this counterexample, they happen to hold `prev_p=1` and `prev_m=3` (values from the reset cycle's combinatorial logic or register initialization).

3. **`x = [0,1,2,3,4,5,6,7]` and `prev_x = [0,1,2,3,4,5,6,7]`** — Both hold their reset-initialized values because no actual swap has occurred yet.

4. **The assertion check**: `x(prev_m) === prev_x(prev_p)` becomes `x(3) === prev_x(1)` = `3 === 1` = **false**.

The assertion fails spuriously because `prev_p` and `prev_m` are uninitialized/arbitrary from the formal tool's perspective (they are `RegNext` of combinatorial signals `p` and `m` that depend on `io.i`). The formal tool can choose any valid initial values for these registers, and the combination `prev_p=1, prev_m=3` happens to make the assertion evaluate incorrectly.

### Underlying Mechanism

The Chisel `when` block around `fvAssert` does **not** generate conditional assertion logic in the Verilog output. The `fvAssert` construct (from ChiselFv/Chisel LTL) is treated as a module-level assertion directive rather than conditional logic, so the `when (!first_cycle)` guard is silently ignored during compilation.

### Summary

| Aspect | Detail |
|--------|--------|
| **Error Type** | `assertion_error` — the assertion is incorrectly written |
| **Root Cause** | The `when (!first_cycle)` guard around `fvAssert` does not produce conditional assertions in the generated Verilog |
| **Fix Needed** | The assertion must include the first-cycle guard directly in its property, either by adding it to the `disable iff` condition or by modifying the assertion expression to account for the first cycle |
| **Evidence** | `prev_m=3, prev_p=1` at time 0 causes `x(3)=3` to be compared to `prev_x(1)=1`, which fails (3 ≠ 1) even though no swap has occurred yet |
