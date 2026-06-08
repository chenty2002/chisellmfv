# Counterexample Analysis Report: cgw.cgw_reach_final_state

## 1. Verification Environment

### Top Module Structure
- **Top module**: `cgw` (Chisel module with `Formal` mixin)
- **Generated Verilog**: `generated/cgw.v`
- **Key components**:
  - **State registers**: `boat`, `cabbage`, `goat`, `wolf` (each 1-bit, encoding `sideLeft`=0 or `sideRight`=1)
  - **Input**: `io.select` (2-bit UInt, encoding passenger choice: 0=NONE, 1=CABBAGE, 2=GOAT, 3=WOLF)
  - **Output**: `io.safe` (boolean), `io.finalState` (boolean)
  - **Timer**: `timer` (5-bit counter, increments each cycle after reset)
  - **Reset counter**: `resetCounter` (module tracking cycles since reset)

### Design Description
This design encodes the classic "cabbage, goat, and wolf" river-crossing puzzle. A farmer must transport a cabbage, a goat, and a wolf across a river using a boat that can carry only one item besides the farmer. The goat cannot be left alone with the wolf, and the cabbage cannot be left alone with the goat. The design enforces these safety constraints by checking `moveIsSafe` before updating any state.

## 2. Violated Assertion

### Assertion Name
`cgw_reach_final_state` (from waveform filename: `cgw.cgw_reach_final_state.fst`)

### Code Snippet
From `cgw.scala`, lines 85-86:
```scala
astRelaxedLiveness(true.B, io.finalState, 20, "cgw_reach_final_state")
```

### Property Description
The assertion checks a **bounded liveness** property: under all possible execution paths (for all possible `io.select` input sequences), the system must reach `io.finalState` (all four entities on the right side of the river) within 20 clock cycles. The enable condition `true.B` means this check is active at every cycle.

### File Location
- **File**: `cgw.scala`
- **Line**: 86

## 3. Waveform Information

### Waveform File
`verilog/extra_bench/cgw/cgw.cgw_reach_final_state.fst`

### Time Range and Key Points
- **Total duration**: 0 ns – 220 ns (22 cycles at 10 ns/cycle)
- **Assertion failure time**: 210 ns (cycle 21)
- **Timer at failure**: `10100` (binary) = 20 decimal

### Critical Signal Values at Failure Point (t=210 ns)

| Signal | Value | Interpretation |
|---|---|---|
| `cgw.boat` | 0 | Boat is on the **left** side |
| `cgw.cabbage` | 1 | Cabbage is on the **right** side |
| `cgw.goat` | 0 | Goat is on the **left** side |
| `cgw.wolf` | 0 | Wolf is on the **left** side |
| `cgw.io_select [1:0]` | `00` (NONE) | No passenger selected |
| `cgw.io_finalState` | 0 | Final state NOT reached (need ALL on right) |
| `cgw.io_safe` | 1 | Safety invariant holds |
| `cgw.cgw_reach_final_state` | 0 | **Assertion failed** |

## 4. Root Cause Analysis

### Root Cause: Incorrect Assertion (assertion_error)

**The assertion `astRelaxedLiveness(true.B, io.finalState, 20, ...)` is fundamentally incorrect for this design.** The property is too strong — it requires that **all possible** input sequences reach the final state within 20 cycles, but the design depends on a *specific* input sequence (the puzzle solution) to make progress.

### Detailed Explanation

#### The Liveness Property's Semantics
`astRelaxedLiveness(true.B, io.finalState, 20, ...)` translates to a universal assertion:
> "For **ALL** possible execution traces (for any sequence of `io.select` values), the final state must be reached within 20 cycles from any starting point."

This is an unbounded-for-all quantification. The formal solver is required to find a counterexample trace where the property fails — and since the solver controls `io.select`, it can trivially pick adversarial inputs that prevent progress.

#### The Actual Execution Trace
The counterexample trace shows a sequence that partially solves the puzzle but stalls at the critical moment:

| Time | Boat | Goat | Wolf | Cabbage | Select | Action |
|---|---|---|---|---|---|---|
| 0 | L | L | L | L | WOLF | Reset state |
| 10 | L | L | L | L | NONE | No move |
| **20** | L | L | L | L | **GOAT** | **Move goat → right** |
| 30 | R | R | L | L | WOLF | Goat + boat on right |
| 40 | R | R | L | L | CABBAGE | Invalid (cabbage on left) |
| **50** | R | R | L | L | **NONE** | **Boat goes back alone** |
| 60 | L | R | L | L | WOLF | Boat left, goat right |
| **70** | R | R | R | L | **WOLF** | **Wolf moves right** |
| 80 | R | R | R | L | CABBAGE | Invalid (cabbage on left) |
| **90** | R | R | R | L | **GOAT** | **Goat goes back left** |
| 100 | L | L | R | L | GOAT | Oscillating... |
| 110 | R | R | R | L | GOAT | ... |
| 120 | L | L | R | L | GOAT | ... |
| 130 | R | R | R | L | CABBAGE | Invalid |
| 140 | R | R | R | L | CABBAGE | Invalid |
| 150 | R | R | R | L | NONE | moveIsSafe=false, stuck |
| 160 | R | R | R | L | WOLF | Wolf goes back left |
| **170** | L | R | L | L | **CABBAGE** | **Cabbage moves right** |
| 180 | R | R | L | R | NONE | moveIsSafe=false, stuck |
| **190** | R | R | L | R | **GOAT** | **Goat goes back left** |
| 200 | L | L | L | R | NONE | **moveIsSafe=false, STUCK** |
| 210 | L | L | L | R | NONE | **Assertion fails** |

#### Why the System Gets Stuck
At t=200 (cycle 20), the system is in state:
- **boat=0 (left)**, **goat=0 (left)**, **wolf=0 (left)**, **cabbage=1 (right)**

From this state, selecting `NONE` (select=00) gives:
- `moveNone` = true
- `moveIsSafe` = `(nextBoat===nextGoat) || (nextGoat=/=nextWolf && nextGoat=/=nextCabbage)`
- = `(1===0) || (0=/=0 && 0=/=1)` = `false || (false && true)` = **false**

Since `moveIsSafe` is false, the state does **not** update. The solver keeps selecting `NONE`, and the system remains stuck indefinitely.

**However**, if the solver had selected `GOAT` (select=10) at t=200, the system would progress:
- `moveGoat` = true (boat===goat on left side)
- `moveIsSafe` = true (nextBoat=1, nextGoat=1, nextWolf=0, nextCabbage=1 → boat is with goat)
- At t=210: boat=1, goat=1, wolf=0, cabbage=1
- Then at t=210, selecting NONE gives: moveIsSafe=true (boat===goat=1===1), boat goes back alone
- At t=220: boat=0, goat=1, wolf=0, cabbage=1
- Then select WOLF: moveWolf=true, moveIsSafe=true
- At t=230: all on right → **final state reached in 23 cycles**

But with the 20-cycle bound, even this sequence is too long — the classic solution requires 7 actual moves, and the bound of 20 cycles is theoretically sufficient, but the wasted cycles from adversarial or suboptimal input choices exceed the bound.

#### The Fundamental Problem
The puzzle inherently requires a **specific sequence** of 7 well-chosen moves. The design correctly checks safety at each step. However, `astRelaxedLiveness` with an always-true enable imposes a **universal quantification** over all input sequences. Since `io.select` is an unconstrained input, the formal solver can always generate an adversarial (or simply suboptimal) sequence that delays progress beyond the 20-cycle bound, causing the assertion to fail.

### Error Classification

| Category | Decision | Explanation |
|---|---|---|
| **Bug in DUT?** | ❌ No | The design logic correctly implements the puzzle rules and safety constraints |
| **Incorrect Assertion?** | ✅ **Yes** | `astRelaxedLiveness(true.B, ...)` universally quantifies over all input sequences; adversarial inputs can always prevent reaching finalState |
| **Setup Issue?** | ❌ No | The top module structure is correct; the issue is in the assertion semantics |

### Recommended Fix
The assertion should be changed to an **existential reachability check** rather than a universal liveness check. Options:

1. **Use `cover` instead of `assert`**: Change to a cover property (if supported by the tool) to check that there EXISTS a path to the final state within the bound.

2. **Add input constraints**: Add assumptions (`assume(...)`) that constrain `io.select` to follow a valid puzzle-solving strategy, preventing adversarial stalls.

3. **Use `astRelaxedLiveness` with a constrained enable**: Gate the enable signal with a condition like `io.safe` and some progress indicator, so the liveness check only applies during "productive" execution phases.

The most appropriate fix depends on the verification intent. If the goal is to prove that from any reachable state, there exists a winning strategy within 20 moves, then a cover/reachability property is correct. If the goal is to prove that the system *will* reach the final state within 20 cycles under specific input constraints, then assumptions need to be added.
