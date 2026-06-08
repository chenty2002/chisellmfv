# Counterexample Analysis Report: cgw_safety_invariant

## 1. Verification Environment

- **Top Module**: `cgw` (in `cgw.scala`, package `llmverify`)
- **Design Under Test**: A Chisel formal model of the classic "cabbage, goat, wolf" river-crossing puzzle
- **Key State Registers**: `boat`, `cabbage`, `goat`, `wolf` — each tracking which side (left=0/right=1) the entity is on
- **Inputs**: `io.select` (2-bit UInt) — 0: passengerNone, 1: passengerCabbage, 2: passengerGoat, 3: passengerWolf
- **Outputs**: `io.safe` (Bool) — safety condition, `io.finalState` (Bool) — all entities on right side
- **Formal Framework**: Chisel Formal (`chiselFv`) with `fvAssert`, `assertOneHot0`, `astRelaxedLiveness` constructs

## 2. Violated Assertion

- **Assertion Name**: `cgw_safety_invariant`
- **Waveform File**: `verilog/extra_bench/cgw/cgw.cgw_safety_invariant.fst`
- **Code Snippet** (cgw.scala, lines 92-95):
  ```scala
  // SAFETY INVARIANT: The system must never enter an unsafe state where
  // the goat is left alone with the wolf or the cabbage without the boat.
  fvAssert(io.safe, "cgw_safety_invariant")
  ```
- **Property Description**: The assertion checks that `io.safe` must be **true at all times**. The safety condition `io.safe` is defined as `(boat === goat) || (goat =/= wolf && goat =/= cabbage)` — meaning either the boat (man) is with the goat, OR the goat is separated from both the wolf and the cabbage.
- **File Location**: `chisel/extra_bench/cgw/cgw.scala`, line 95

## 3. Waveform Information

- **Waveform File**: `verilog/extra_bench/cgw/cgw.cgw_safety_invariant.fst`
- **Time Range**: 0 ns → 20 ns (2 clock cycles)
- **Key Time Points**:

### Time 0 ns (initial posedge, assertion passes)
| Signal | Value | Meaning |
|--------|-------|---------|
| `cgw.io_select [1:0]` | `01` | passengerCabbage selected |
| `cgw.boat` | `0` | Left side |
| `cgw.cabbage` | `0` | Left side |
| `cgw.goat` | `0` | Left side |
| `cgw.wolf` | `0` | Left side |
| `cgw.io_safe` | `1` | Safe (boat is with goat) |
| `cgw.cgw_safety_invariant` | `1` | Assertion holds |

### Time 10 ns (next posedge, assertion fails)
| Signal | Value | Meaning |
|--------|-------|---------|
| `cgw.io_select [1:0]` | `01` | passengerCabbage (unchanged) |
| `cgw.boat` | `1` | **Right side** — boat moved! |
| `cgw.cabbage` | `1` | **Right side** — cabbage moved! |
| `cgw.goat` | `0` | Left side (unchanged) |
| `cgw.wolf` | `0` | Left side (unchanged) |
| `cgw.io_safe` | `0` | **Unsafe** — goat and wolf alone together |
| `cgw.cgw_safety_invariant` | `0` | **Assertion FAILS** |

## 4. Root Cause Analysis

### Classification: **assertion_error**

### Root Cause

The assertion `fvAssert(io.safe, "cgw_safety_invariant")` on line 95 of `cgw.scala` checks that `io.safe` is **always true**. However, the design deliberately models a puzzle where **the environment (user) can freely choose which passenger to move**. When the user picks the wrong passenger, the system correctly transitions to an unsafe state.

#### What Happens in the Counterexample

1. **Cycle 0 (time 0)**: All entities on the left side (`boat=0, cabbage=0, goat=0, wolf=0`). The environment selects `passengerCabbage` (`io_select=01`). The safety condition holds because `boat === goat` (both on left).

2. **State Update at Cycle 0**: The `when` block (line 48) checks `io.select === passengerCabbage && boat === cabbage`. Both conditions are true (both on left side), so:
   - `cabbage` toggles from `0` (left) to `1` (right) — the cabbage moves with the boat
   - The `elsewhen` branches for goat and wolf are skipped
   - The boat update (line 68) checks `(io.select === passengerCabbage && cabbage === boat)` → true, so boat toggles from `0` (left) to `1` (right)

3. **Cycle 1 (time 10)**: After the state update:
   - **Right side**: boat and cabbage
   - **Left side**: goat and wolf (left alone, no man present!)
   - The safety formula evaluates to: `(1 === 0) || (0 =/= 0 && 0 =/= 1)` = `false || (false && true)` = **false**
   - `io.safe` becomes `0`, and the assertion `fvAssert(io.safe, ...)` **fails**

#### Why This Is an Assertion Error (Not a DUT Bug)

The DUT **correctly models the puzzle behavior**:
- The state transition logic correctly moves the selected passenger when it's on the same side as the boat
- The safety output formula `(boat === goat) || (goat =/= wolf && goat =/= cabbage)` is correctly implemented and matches the puzzle's safety constraints
- The DUT is performing exactly as designed: it computes whether the current puzzle state is safe

The problem is that the assertion **assumes the system should never be unsafe**, but the system is designed to **model a puzzle where the user makes choices, and wrong choices lead to unsafe states**. With unconstrained inputs, the formal tool will always find a counterexample where the user picks the wrong passenger.

#### Evidence from the Code

The code's own comment on lines 92-94 acknowledges this:

```
// Counterexamples to this assertion demonstrate the dangerous moves
// that violate the puzzle constraints.
```

This confirms the assertion is **intentionally falsifiable** — it's being used as a trace-generating device rather than a correctness check. Without input constraints (assumptions) that restrict inputs to "puzzle-valid" moves (i.e., only moving passengers on the same side as the boat, and only picking passengers that maintain the safety invariant), the assertion `io.safe` can never be proven true, because any wrong move creates an unsafe state.

#### How This Differs From DUT Bugs and Setup Errors

- **Not a DUT Bug (category 1)**: The state update logic and safety computation are implemented correctly. The DUT correctly flags the state as unsafe when goat and wolf are left alone.
- **Not a Setup Error (category 3)**: The top module correctly instantiates the design. The issue is not about parameterization, clocking, or structural connections.
- **This is an Assertion Error (category 2)**: The assertion `fvAssert(io.safe, ...)` makes an unconditional claim that cannot hold for this design without input constraints. The assertion should either:
  - (a) Be removed/changed to a different property (e.g., a bounded liveness check that from a safe state, a safe final state can be reached)
  - (b) Have corresponding `assume()` constraints added to the environment to only allow puzzle-valid moves
  - (c) Be kept as-is if the intent is to generate counterexample traces (as the comment suggests), in which case this is the expected behavior and not an error at all
