# Counterexample Analysis Report: rotate_by_zero

## 1. Verification Environment

- **Top Module**: `rotate` (from `rotate4.scala`)
- **Structure**: The design implements a 4-bit barrel shifter that rotates the input `din` right by `amount` positions, producing `dout`. It uses the Chisel Formal Verification library (`chiselFv`).
- **Key Components**:
  - `inr`: Input register (4-bit), stores `io.din` on each clock cycle
  - `dout`: Output register (4-bit), stores the barrel shifter result
  - `tmp1`: Intermediate signal implementing rotate-right-by-1 using `amount[0]`
  - `tmp2`: Final barrel shifter output, rotate-right-by-2 using `amount[1]`
  - `refRotate` / `_GEN`: Reference rotation table for verification
- **Assertions**: Five formal assertions checking various rotation properties.

## 2. Violated Assertion

- **Assertion Name**: `rotate_by_zero` (from waveform filename `rotate.rotate_by_zero.fst`)
- **Code Snippet** (from `rotate4.scala` line 41):
  ```scala
  fvAssert((io.amount === 0.U) === (tmp2 === inr), "rotate_by_zero")
  ```
- **Generated Verilog** (from `rotate.sv` line 49–52):
  ```verilog
  rotate_by_zero:
      assert property (@(posedge clock) disable iff (~hasBeenReset)
                       io_amount == 2'h0 == (tmp2 == inr));
  ```
- **Natural Language Description**: The property claims that **"amount is zero if and only if tmp2 equals inr"** — using logical equivalence (`===` in Scala/Chisel, `==` in SystemVerilog with left-associative binding).
- **File Location**: `rotate4.scala`, line 41.

## 3. Waveform Information

- **Waveform File**: `verilog/extra_bench/rotate_rotate4/rotate.rotate_by_zero.fst`
- **Duration**: 1 cycle (0 ns → 10 ns)
- **Key Time Points**:
  - **t = 0 ns** (posedge clock): All signals are sampled
  - **t = 5 ns** (negedge clock): No state changes
  - **t = 10 ns** (posedge clock): Next clock edge
- **Critical Signal Values at t = 0 ns** (the assertion evaluation point):

| Signal | Value | Meaning |
|--------|-------|---------|
| `rotate.clock` | 1 | Posedge of clock |
| `rotate.reset` | 0 | Reset inactive |
| `rotate.hasBeenReset` | 1 | Reset sequence completed (assertion enabled) |
| `rotate.io_amount [1:0]` | `10` (binary) = **2** | Rotate amount = 2 |
| `rotate.io_din [3:0]` | `0000` (hex 0) | Input data = 0 |
| `rotate.inr [3:0]` | `0000` (hex 0) | Registered input = 0 |
| `rotate.tmp2 [3:0]` | `0000` (hex 0) | Barrel shifter output = 0 |
| `rotate.io_dout [3:0]` | `0000` (hex 0) | Module output = 0 |

All `_GEN[i]` signals (reference rotation table) are also `0000` because `inr = 0`:
```
_GEN[0] = inr          = 0000
_GEN[1] = {inr[0], inr[3:1]} = 0000
_GEN[2] = {inr[1:0], inr[3:2]} = 0000
_GEN[3] = {inr[2:0], inr[3]} = 0000
```

## 4. Root Cause Analysis

### Bug Location
- **File**: `rotate4.scala`, line 41
- **Module**: `rotate`
- **Bug Type**: **Incorrect Assertion** (`assertion_error`)

### Description of the Bug

The assertion at line 41 uses **logical equivalence** (`===`) instead of **logical implication** (`==>`):

```scala
// BUG: Uses equivalence instead of implication
fvAssert((io.amount === 0.U) === (tmp2 === inr), "rotate_by_zero")
```

The operator `===` in Chisel's `Bool` type is logical equivalence: both sides must have the same truth value. The assertion checks:

```
(amount == 0)  ==  (tmp2 == inr)
```

This requires that `tmp2 == inr` be **true when amount=0** AND **false when amount≠0**. The first condition is correct (when amount=0, no rotation occurs, so tmp2 should equal inr). However, the second condition is **incorrect** — when `inr = 0`, rotating zero by any non-zero amount still yields zero, so `tmp2 == inr` can be true even when amount≠0.

The correct assertion should use logical implication (one-directional):

```scala
// FIX: Use implication instead of equivalence
fvAssert((io.amount === 0.U) ==> (tmp2 === inr), "rotate_by_zero")
```

### Evidence from Waveform

At t = 0 ns (posedge clock):

1. **`io_amount = 2`** (binary `10`) → `io_amount == 0` is **false** (0)
2. **`inr = 0`**, **`tmp2 = 0`** → `tmp2 == inr` is **true** (1)
3. Assertion evaluates: **`0 == 1` → false (0)** → assertion FAILS

### Why the DUT is Correct

The barrel shifter correctly implements right rotation:
- `tmp1 = Mux(amount[0]=0, inr, ...) = inr = 0000`
- `tmp2 = Mux(amount[1]=1, Cat(tmp1[1:0], tmp1[3:2]), ...) = Cat(00, 00) = 0000`

The reference `_GEN[2] = Cat(inr[1:0], inr[3:2]) = 0000` matches `tmp2 = 0000`, and the `barrel_shifter_correct` assertion (line 38) passes for this input. The DUT logic is flawless; the bug is solely in the assertion formulation.

### Why the Assertion Fails for This Input

| Condition | Evaluation | Expected |
|-----------|-----------|----------|
| `amount == 0` | false (amount=2) | — |
| `tmp2 == inr` | true (0 == 0) | — |
| `(amount==0) == (tmp2==inr)` | **false** | **true** (should not fail) |

The assertion fails because rotating a zero-valued input by any non-zero amount still produces zero, so `tmp2 == inr` holds despite `amount ≠ 0`. The equivalence check is too strict.

### Recommended Fix

```scala
// Line 41 of rotate4.scala: Change === to ==>
fvAssert((io.amount === 0.U) ==> (tmp2 === inr), "rotate_by_zero")
```

This change correctly asserts that **when amount is zero**, tmp2 must equal inr (no rotation), without asserting the reverse direction which is not guaranteed by the design.
