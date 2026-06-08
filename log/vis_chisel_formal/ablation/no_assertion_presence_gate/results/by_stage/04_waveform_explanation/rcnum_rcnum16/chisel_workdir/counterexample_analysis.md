# Counterexample Analysis Report: `even_num_halved` Assertion Failure

## 1. Verification Environment

- **Top Module**: `rollercoasterNumbers` (defined in `rcnum16.scala`)
- **Module Type**: Chisel module with `Formal` mixin, compiled to Verilog
- **Benchmark**: `rcnum_rcnum16`
- **Waveform File**: `rollercoasterNumbers.even_num_halved.fst`
- **Key Design Elements**:
  - `numReg` (`Reg(UInt(16.W))`): A plain register without initialization (non-deterministic initial value)
  - `prevNumReg` (`RegNext(numReg)`): A register that captures the previous cycle's `numReg` (initializes to 0 on reset)
  - Next-state logic: For odd numbers → `3n+1` (with overflow check), for even numbers → `n/2`
  - `io_numOut`: Output equal to current `numReg`

## 2. Violated Assertion

- **Assertion Name**: `even_num_halved`
- **Code Snippet** (Line 45 of `rcnum16.scala`):

```scala
fvAssert(!(prevNumReg(0) === 0.U) || numReg === (prevNumReg >> 1), "even_num_halved")
```

- **Natural Language Property**: If the previous value (`prevNumReg`) is an even number, then the current value (`numReg`) must equal `prevNumReg / 2` (right-shifted by 1 bit).
- **File Location**: `rcnum16.scala`, line 45

## 3. Waveform Information

- **Full Waveform Path**: `verilog/extra_bench/rcnum_rcnum16/rollercoasterNumbers.even_num_halved.fst`
- **Time Range**: 0 ns → 10 ns (1 clock cycle; clock high at 0ns, low at 5ns)
- **Critical Signal Values at Failure Point (time 0)**:

| Signal | Value | Description |
|--------|-------|-------------|
| `rollercoasterNumbers.numReg [15:0]` | `1000000000000000` (0x8000 = 32768) | Current `numReg` value |
| `rollercoasterNumbers.prevNumReg [15:0]` | `0000000000000000` (0 = 0) | Previous `numReg` value |
| `rollercoasterNumbers.io_numOut [15:0]` | `1000000000000000` (0x8000) | Module output |
| `rollercoasterNumbers._prevTmp_T_2 [17:0]` | `000000000000000001` (1) | Intermediate tmp computation |
| `rollercoasterNumbers.even_num_halved` | `1` | Assertion failure flag (active high) |
| `rollercoasterNumbers.hasBeenReset` | `1` | Reset indicator |
| `rollercoasterNumbers.reset` | `0` | Reset signal (inactive) |
| `rollercoasterNumbers.:jasper_formal_clock` | `1` | Formal clock (high) |
| `rollercoasterNumbers.:jasper_formal_reset` | `0` | Formal reset (inactive) |

## 4. Root Cause Analysis

### Root Cause Classification

**Type**: `assertion_error` — The assertion is incorrectly specified; it fails in valid initial/reset states where `prevNumReg` and `numReg` have inconsistent initial values by design.

### Detailed Analysis

#### The Violation Mechanism

The assertion at line 45 performs the following logical check:

```
!(prevNumReg(0) === 0.U) || numReg === (prevNumReg >> 1)
```

This is an implication: if `prevNumReg` is even (bit 0 == 0), then `numReg` must equal `prevNumReg / 2`.

In the counterexample at time 0:
- **`prevNumReg` = 0** (even, bit 0 = 0) → premise is true
- **`numReg` = 0x8000** (32768)
- **`prevNumReg >> 1` = 0** (0 >> 1 = 0)
- **`numReg === (prevNumReg >> 1)`** → `0x8000 === 0` → **false**
- **Overall assertion**: `false || false` → **false** → **ASSERTION VIOLATED**

#### Why This Happens

The root cause lies in the **asymmetric initialization** of the two registers:

1. **`numReg = Reg(UInt(16.W))`** (line 13): A plain `Reg` **without** a reset value. In Chisel formal verification, such registers are treated as having **non-deterministic initial values** — they can be any 16-bit value at time 0. In this counterexample, the formal solver chose `0x8000`.

2. **`prevNumReg = RegNext(numReg)`** (line 36): A `RegNext` register in Chisel **has a default reset value of 0** for `UInt` types. Therefore, at time 0, `prevNumReg` is **deterministically 0**, regardless of what `numReg` contains.

3. **Result**: At time 0, there is no relationship between `prevNumReg` (which is 0) and `numReg` (which is any non-deterministic value). The assertion assumes they satisfy `numReg === prevNumReg >> 1`, but this relationship only holds **after the first clock edge** when `prevNumReg` has been updated with the actual previous `numReg` value.

### Why It's Not a Design Bug

The underlying hardware design (the Collatz-like next-state logic) is correct:
- Even numbers correctly divide by 2 via `Cat(0.U(1.W), numReg(15,1))`
- Odd numbers correctly compute `3n+1` with overflow detection
- The state machine would operate correctly starting from the first clock edge

The bug is purely in the **assertion** — it checks a cross-cycle invariant that doesn't hold in the **initial state** because `prevNumReg` (initialized to 0 by `RegNext`) and `numReg` (non-deterministic initial value) are not yet synchronized.

### How to Fix

The assertion needs to be gated to avoid checking in the initial/reset state. Possible fixes include:

**Option 1**: Use `past()` with `past_valid` instead of `RegNext`:
```scala
fvAssert(!past(numReg)(0) || numReg === (past(numReg) >> 1), "even_num_halved")
```
This would use JasperGold's native `past()` operator which returns the previous-cycle value and is typically vacuously true in the first cycle.

**Option 2**: Gate with a reset-already-occurred signal:
```scala
val hasBeenReset = RegNext(true.B, false.B)
fvAssert(!hasBeenReset || !(prevNumReg(0) === 0.U) || numReg === (prevNumReg >> 1), "even_num_halved")
```

**Option 3**: Gate the assertion to only check after first clock:
```scala
// Skip initial state check where prevNumReg hasn't tracked numReg yet
when (RegNext(true.B)) {
  fvAssert(!(prevNumReg(0) === 0.U) || numReg === (prevNumReg >> 1), "even_num_halved")
}
```

The same issue applies to the other three assertions (`odd_num_3x_plus_1`, `odd_num_overflow_to_zero`, `zero_is_absorbing`) which all depend on `prevNumReg` and would also fail under similar initial-state counterexamples.
