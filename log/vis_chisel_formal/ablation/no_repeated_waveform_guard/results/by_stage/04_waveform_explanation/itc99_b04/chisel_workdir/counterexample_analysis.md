# Counterexample Analysis Report: `b04.trans_sA_to_sB`

## 1. Verification Environment

- **Top Module**: `b04` (from `b04.scala`)
- **Module Type**: Chisel Module with Formal verification mixin
- **Design Under Test**: ITC99 b04 benchmark — an FSM-based data processing circuit with signed arithmetic (average, min/max tracking)
- **Key Components**:
  - 3-state FSM (`sA`=0, `sB`=1, `sC`=2) with unconditional transitions `sA -> sB -> sC -> sC`
  - Data registers: `RMAX`, `RMIN`, `RLAST`, `REG1`-`REG4`, `DATA_OUT`
  - Signed arithmetic: `avg()`, `signGt()`, `tc()` (two's complement)
- **Inputs**: `RESTART`, `AVERAGE`, `ENABLE`, `DATA_IN` (8-bit)
- **Outputs**: `DATA_OUT`, `stato`, `RMAX`, `RMIN`, `RLAST`, `REG1`-`REG4`

## 2. Violated Assertion

- **Assertion Name**: `trans_sA_to_sB` (from waveform filename `b04.trans_sA_to_sB.fst`)
- **File Location**: `b04.scala`, line 138
- **Code Snippet** (from `b04.scala`, lines 136–138):
  ```scala
  // 2. FSM transition determinism
  //    sA -> sB -> sC -> sC  (unconditional every cycle)
  fvAssert(stato =/= sA || RegNext(stato) === sB, "trans_sA_to_sB")
  ```
- **Property Description**: The assertion is intended to verify that when the FSM is in state `sA`, the previous state was `sB` (i.e., a backward-looking check of the `sB -> sA` transition). However, the FSM comment states the forward transition is `sA -> sB`.

## 3. Waveform Information

- **Waveform File**: `verilog/extra_bench/itc99_b04/b04.trans_sA_to_sB.fst`
- **Time Range**: 0 ns to 10 ns (1 clock cycle)
- **Key Time Points**:
  - **Time 0 ns**: All signals stable after reset
  - **Time 5 ns**: Clock falling edge
  - **Time 10 ns**: End of trace

### Critical Signal Values at Time 0 (failure point)

| Signal | Value | Meaning |
|--------|-------|---------|
| `b04.stato [1:0]` | `00` | `sA` (state 0) |
| `b04.REG [1:0]` (RegNext(stato)) | `00` | `sA` (state 0) |
| `b04.trans_sA_to_sB` | `1` | Assertion firing (evaluating to false) |
| `b04.reset` | `0` | Not in reset |
| `b04.io_ENABLE` | `0` | Disabled |
| `b04.io_RESTART` | `0` | Not restarting |

### Assertion Evaluation at Time 0:
```
stato =/= sA || RegNext(stato) === sB
  0   =/=  0  ||     0     ===  1
    false     ||       false
            false        →  ASSERTION VIOLATION
```

## 4. Root Cause Analysis

### Category: **Incorrect Assertion (assertion_error)**

### Bug Location
- **File**: `b04.scala`
- **Lines**: 137–139
- **Assertions**: `trans_sA_to_sB`, `trans_sB_to_sC`, `trans_sC_stays_sC`

### Description of the Bug

The three FSM transition assertions are **incorrectly specified**. The antecedent and consequent are logically swapped relative to the actual FSM transitions.

**FSM Transitions** (from code and comments, lines 64–90):
```
sA(0) → sB(1) → sC(2) → sC(2)  [unconditional every cycle]
```

**What the assertion checks** (`stato =/= sA || RegNext(stato) === sB`):
- If current state `stato` is `sA`, then the **previous** state (`RegNext(stato)`) must be `sB`
- This describes a **backward** transition `sB → sA`, which **does not exist** in the FSM

**What should be checked** for the forward transition `sA → sB`:
- `RegNext(stato) =/= sA || stato === sB` — "if previous was sA, current must be sB"
- OR equivalently: `stato =/= sB || RegNext(stato) === sA` — "if current is sB, previous was sA"

### Evidence from Waveform

At time 0 (immediately after reset):
1. `stato` = `sA` (0) — initialized by `RegInit(sA)` on line 32
2. `RegNext(stato)` (signal `b04.REG [1:0]`) = `sA` (0) — initialized to UInt default value 0
3. Both `stato` and `RegNext(stato)` equal `sA` (0), not `sB` (1)
4. The assertion condition `stato =/= sA || RegNext(stato) === sB` evaluates to `false || false` = `false`

The assertion fails because:
- **Primary cause**: The logical condition is inverted. The assertion checks for a non-existent `sB → sA` transition instead of the correct `sA → sB` transition.
- **Secondary cause**: Even if the logic were corrected, at time 0 both `stato` and `RegNext(stato)` are initialized to `sA`, which would cause any backward-looking `sA` transition assertion to fail on the first cycle unless it accounts for reset.

### The Same Bug Affects All Three Transition Assertions

| Assertion | Wrong Check | Should Check |
|-----------|------------|--------------|
| `trans_sA_to_sB` (line 138) | `stato =/= sA \|\| RegNext(stato) === sB` | `RegNext(stato) =/= sA \|\| stato === sB` |
| `trans_sB_to_sC` (line 139) | `stato =/= sB \|\| RegNext(stato) === sC` | `RegNext(stato) =/= sB \|\| stato === sC` |
| `trans_sC_stays_sC` (line 140) | `stato =/= sC \|\| RegNext(stato) === sC` | Correct by coincidence (sC→sC means prev=sC when curr=sC, but fails on first entry from sB) |

### Proposed Fix

Change lines 138–140 from:
```scala
fvAssert(stato =/= sA || RegNext(stato) === sB, "trans_sA_to_sB")
fvAssert(stato =/= sB || RegNext(stato) === sC, "trans_sB_to_sC")
fvAssert(stato =/= sC || RegNext(stato) === sC, "trans_sC_stays_sC")
```

To (correct forward-transition semantics):
```scala
fvAssert(RegNext(stato) =/= sA || stato === sB, "trans_sA_to_sB")
fvAssert(RegNext(stato) =/= sB || stato === sC, "trans_sB_to_sC")
fvAssert(RegNext(stato) =/= sC || stato === sC, "trans_sC_stays_sC")
```

This corrects the antecedent to check the **previous** state (`RegNext(stato)`) and the consequent to check the **current** state (`stato`), properly describing the forward transition.
