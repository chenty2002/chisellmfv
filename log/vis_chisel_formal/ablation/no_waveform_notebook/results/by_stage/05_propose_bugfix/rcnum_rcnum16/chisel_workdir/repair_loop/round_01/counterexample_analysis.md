# Counterexample Analysis Report: `rcnum_rcnum16`

## 1. Verification Environment

- **Top Module**: `rollercoasterNumbers`
- **Source File**: `rcnum16.scala` (67 lines)
- **Design Under Test**: A Collatz (3n+1) sequence generator. The design implements the state transition `n → n/2` (if n even) or `n → 3n+1` (if n odd, with overflow wrapping to 0). The state is stored in `numReg` (an uninitialized 16-bit register). The output `io.numOut` mirrors `numReg`.
- **Key Components**:
  - `numReg` (16-bit): Current state register, intentionally uninitialized (`Reg(UInt(16.W))`)
  - `prev` (16-bit): `RegNext(numReg)`, captures previous cycle's state
  - `tmp`: Combinational signal computing `numReg * 3 + 1`

## 2. Violated Assertion

- **Assertion Name**: `state_transition_correct` (from waveform filename `rollercoasterNumbers.state_transition_correct.fst`)
- **Source Location**: `rcnum16.scala`, lines 46-49
- **Code**:
  ```scala
  AssertProperty(
    !RegNext(reset.asBool) | (numReg === expected_next),
    None, None, Some("state_transition_correct")
  )
  ```
- **Intended Property (per line 44-45 comment)**: "After reset deasserts (pipeline filled), numReg must equal the expected value computed from the previous cycle's numReg."

## 3. Waveform Information

- **Waveform File**: `verilog/extra_bench/rcnum_rcnum16/rollercoasterNumbers.state_transition_correct.fst`
- **Time Range**: 0 ns → 10 ns (1 clock cycle)
- **Clock**: 1 at 0 ns, 0 at 10 ns
- **Reset**: 0 throughout (always deasserted)

### Critical Signal Values at Time 0 ns

| Signal | Value | Interpretation |
|--------|-------|----------------|
| `numReg [15:0]` | `0x0000` | Current state (initial value) |
| `prev [15:0]` | `0x3FFE` | `RegNext(numReg)` — previous state (initial value) |
| `REG` (1-bit) | `1` | Likely `RegNext(reset.asBool)` — reset was asserted last cycle |
| `state_transition_correct` | **`0` (FAIL)** | Assertion fires immediately |

## 4. Root Cause Analysis

### Type of Error: **Incorrect Assertion** (`assertion_error`)

### Root Cause: The assertion condition has an inverted guard — the `!` (logical NOT) on `RegNext(reset.asBool)` is incorrect.

#### Detailed Explanation

The assertion is written as:
```scala
!RegNext(reset.asBool) | (numReg === expected_next)
```

In boolean logic, this is equivalent to:
```
RegNext(reset.asBool) → (numReg === expected_next)
```

Which means: **IF reset was asserted last cycle, THEN numReg must equal expected_next**.

This is **backwards** from the intended behavior (line 44-45 comment: "After reset deasserts (pipeline filled)").

#### What the assertion should be

The correct property should check the state transition **only when the pipeline is filled**, i.e., when reset was **NOT** asserted last cycle:

```
RegNext(reset.asBool) | (numReg === expected_next)
```

Which means: **IF reset was NOT asserted last cycle (pipeline filled), THEN numReg must equal expected_next**. Or equivalently: skip checking when reset was asserted last cycle.

#### What went wrong in the counterexample

At the initial state (time 0 ns):
- **`numReg = 0x0000`** and **`prev = 0x3FFE`** are independently initialized (since both are uninitialized registers with `Reg(...)`)
- **`REG = 1`** means `RegNext(reset.asBool) = 1` (reset was asserted last cycle)
- The **incorrect assertion** evaluates: `!1 | (0x0000 === 0x1FFF)` = `0 | 0` = **`0` (false)** → assertion fails

With the **correct assertion**: `1 | (0x0000 === 0x1FFF)` = `1 | 0` = **`1` (true)** → assertion passes (vacuously true because reset was asserted last cycle).

#### Evidence Summary

1. The assertion signal `state_transition_correct` is 0 (failing) at time 0.
2. Register `REG` (the probable `RegNext(reset.asBool)`) is 1, indicating reset was asserted in the previous cycle.
3. The incorrect assertion requires `numReg === expected_next` when `RegNext(reset.asBool) = 1`, but the initial independent initialization of `numReg` and `prev` makes this impossible.
4. After the first cycle (once `numReg` and `prev` have established their relationship through actual state transitions), the assertion with the `!` negation will **never check anything** (because `!RegNext(reset.asBool)` will be `!0 = 1`, making the property always true regardless of correctness).

### Fix

In `rcnum16.scala`, line 47, change:
```scala
!RegNext(reset.asBool) | (numReg === expected_next),
```
to:
```scala
RegNext(reset.asBool) | (numReg === expected_next),
```

This makes the assertion correctly skip checking when reset was asserted last cycle (vacuously true), and only verify the state transition when the pipeline is properly filled.
