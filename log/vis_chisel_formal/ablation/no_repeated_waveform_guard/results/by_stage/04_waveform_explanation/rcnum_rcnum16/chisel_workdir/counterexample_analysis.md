# Counterexample Analysis Report: `even_next_is_half` Assertion Failure

## 1. Verification Environment

- **Top Module**: `rollercoasterNumbers` (class in `rcnum16.scala`)
- **Waveform File**: `verilog/extra_bench/rcnum_rcnum16/rollercoasterNumbers.even_next_is_half.fst`
- **Design Under Test**: A 16-bit Collatz (rollercoaster) number generator that applies:
  - If the current number is **odd**: compute `3n + 1`, check for overflow, and wrap to 0 on overflow
  - If the current number is **even**: divide by 2 (`n >> 1`)
- **Key Signals**:
  - `numReg [15:0]` — the internal 16-bit register holding the current number
  - `io_numOut [15:0]` — output, directly connected to `numReg`
  - `tmp [17:0]` — 18-bit intermediate for `3n + 1` computation
  - `overflow` — flag set when `tmp(17) | tmp(16)` is true (overflow beyond 16 bits)
  - `livCounter [15:0]` — counter incremented while `numReg != 0`, for liveness checking

## 2. Violated Assertion

**Assertion Name**: `even_next_is_half`

**Code snippet** (rcnum16.scala, lines 36–42):

```scala
// Property 1: Even-number step correctness
// When numReg is even, the next-cycle value must equal numReg / 2.
val isEven = !numReg(0)
val halfNum = Cat(0.U(1.W), numReg(15, 1))
AssertProperty(
    isEven |-> Sequence(numReg === halfNum).delay(1),
    None, None, Some("even_next_is_half"))
```

**Natural Language Description**: If `numReg` is even at the current cycle, then in the *next* cycle `numReg` must equal `numReg / 2` (i.e., `Cat(0.U(1.W), numReg(15, 1))`).

**File Location**: `rcnum16.scala`, lines 40–42

## 3. Waveform Information

- **Full Waveform Path**: `verilog/extra_bench/rcnum_rcnum16/rollercoasterNumbers.even_next_is_half.fst`
- **Duration**: 2 cycles (0 ns → 20 ns)
- **Clock Edge Times**: posedge at 0 ns, 10 ns (and presumably 20 ns)

| Time (ns) | Signal | Value | Notes |
|-----------|--------|-------|-------|
| 0 | `numReg [15:0]` | `0010101010101010` (0x2AAA) | Initial value after reset; even (bit 0 = 0) |
| 0 | `halfNum` (combinational) | `0001010101010101` (0x1555) | Computed as `Cat(0, numReg[15:1])` = 0x2AAA >> 1 |
| 0 | `isEven` | `1` (true) | Antecedent fires |
| 0 | `_tmp_T_2 [17:0]` | `000111111111111111` | `tmp(17:0)` for the odd-path computation |
| 0 | `overflow` | `0` | No overflow |
| 0 | `even_next_is_half` (assertion) | `1` (high) | Assertion is active |
| 5 | (all signals stable) | — | Half-cycle between edges |
| 10 | `numReg [15:0]` | `0001010101010101` (0x1555) | **New value = 0x2AAA >> 1 = correct!** |
| 10 | `halfNum` (combinational) | `0000101010101010` (0x0AAA) | Now computed from **new** numReg: `0x1555 >> 1 = 0x0AAA` |
| 10 | `even_next_is_half` (assertion) | **`0` (FAIL)** | Assertion fires: `0x1555 ≠ 0x0AAA` |
| 10 | `livCounter [15:0]` | `0000000000000001` | Counter incremented |
| 20 | `numReg [15:0]` | `0001010101010101` (0x1555) | Stable (value is now odd, next step uses odd path) |

## 4. Root Cause Analysis

### Classification: **Incorrect Assertion** (assertion_error)

The assertion is **incorrectly written**. The design under test (DUT) is **correct** — an explanation follows.

### The Bug: Incorrect Use of Combinational Signals Inside `delay()`

#### What the assertion intends to check:

At cycle **t**: `isEven` is true ⟹ at cycle **t+1**: `numReg[t+1] === halfNum[t]`

Where `halfNum[t]` = `Cat(0.U, numReg[t][15:1])` = `numReg[t] >> 1`.

#### What the assertion actually checks:

```scala
isEven |-> Sequence(numReg === halfNum).delay(1)
```

The Chisel LTL `delay(1)` operator shifts the **entire** sequence evaluation forward by one cycle. Since `halfNum` is a **combinational wire** (not a register), it is re-evaluated at the delayed time **t+1** using the **new** `numReg[t+1]`, not the old `numReg[t]`.

So at time **t+1**, the assertion evaluates:

```
numReg[t+1] === Cat(0.U, numReg[t+1][15:1])
```

i.e., `numReg[t+1] === numReg[t+1] >> 1`

This equation is **only true** when `numReg[t+1]` is 0 or 1. For any other value, it fails — even though the DUT correctly performed `numReg[t+1] = numReg[t] >> 1`.

#### Concrete Trace from Waveform:

| Cycle | Time | numReg | halfNum (combinational) | isEven | Check |
|-------|------|--------|------------------------|--------|-------|
| t=0 (posedge 0) | 0 ns | `0x2AAA` | `0x1555` (= 0x2AAA >> 1) | true | — |
| t=1 (posedge 10) | 10 ns | `0x1555` | `0x0AAA` (= 0x1555 >> 1) | false | Assertion checks `0x1555 === 0x0AAA` → **FAIL** |

The DUT correctly transitioned from `0x2AAA` to `0x1555` (= `0x2AAA / 2`). But the assertion compared `0x1555` against `0x0AAA` (= `0x1555 / 2`), which is the wrong reference value.

#### Why Other Assertions May Have Similar Bugs

The same pattern appears in Property 2 (`odd_no_overflow_next_is_3x_plus_1`, line 50):

```scala
(isOdd && noOverflow) |-> Sequence(numReg === tmp(15, 0)).delay(1)
```

Here `tmp(15, 0)` is also a combinational signal (`Cat(0.U(2.W), numReg) + Cat(0.U(1.W), numReg, 1.U(1.W))`). The `delay(1)` will evaluate `tmp` based on the **new** `numReg`, not the one at the time the antecedent fired. This property likely also fails for the same reason.

Property 3 (`odd_overflow_next_is_zero`, line 57) uses a constant `0.U` instead of a combinational signal, so it may be correct.

### Fix

The correct way to write the assertion is to capture the past value of `halfNum` using a register:

```scala
val halfNum = Cat(0.U(1.W), numReg(15, 1))
val pastHalfNum = RegNext(halfNum, 0.U)  // Register to capture value at antecedent time
AssertProperty(
    isEven |-> Sequence(numReg === pastHalfNum).delay(1),
    None, None, Some("even_next_is_half"))
```

With this fix:
- At time **t**: `isEven[t]` true → register `pastHalfNum` captures `halfNum[t]`
- At time **t+1**: `pastHalfNum[t+1] = halfNum[t]` (from the register)
- Assertion checks: `numReg[t+1] === pastHalfNum[t+1]` = `numReg[t+1] === halfNum[t]` ✓

This correctly verifies that the DUT's even-number step produces `numReg[t] >> 1` at the next cycle.

---

### Summary

| Aspect | Detail |
|--------|--------|
| **Root Cause Type** | `assertion_error` — the assertion is incorrectly written |
| **Buggy Code** | rcnum16.scala, lines 40–42: `halfNum` is combinational but used inside `.delay(1)` |
| **DUT Correctness** | The design correctly implements numReg := Cat(0.U(1.W), numReg(15,1)) for even numbers |
| **What to Fix** | Capture `halfNum` into a register (e.g., `RegNext`) before using it in the delayed sequence |
