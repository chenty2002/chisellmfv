# Counterexample Analysis Report: `cgw_safe_invariant` Failure

## 1. Verification Environment

### Top Module
- **Module**: `cgw` (Chisel class, package `llmverify`)
- **Source File**: `chisel/extra_bench/cgw/cgw.scala` (76 lines)

### Key Components and Connections
- **State Registers**: `boat`, `cabbage`, `goat`, `wolf` — each 1-bit, initialized to `sideLeft` (0)
- **Input**: `io.select` (2-bit UInt) — encodes the passenger to carry: 0=NONE, 1=CABBAGE, 2=GOAT, 3=WOLF
- **Output**: `io.safe` (Bool) — combinational safety check of current state
- **Output**: `io.finalState` (Bool) — true when all entities are on the right side

### Design Description
This is a formal model of the classic "cabbage, goat, and wolf" river-crossing puzzle. A man must transport a cabbage, a goat, and a wolf across a river using a boat that can carry only one passenger at a time. The constraints are:
- The wolf will eat the goat if left alone together without the man (boat)
- The goat will eat the cabbage if left alone together without the man (boat)

The design transitions state based on `io.select`: if the selected passenger is on the same side as the boat, both the passenger and the boat move to the opposite side.

## 2. Violated Assertion

- **Assertion Name**: `cgw_safe_invariant`
- **Full Name** (from signal): `cgw.cgw_safe_invariant`
- **Waveform File**: `verilog/extra_bench/cgw/cgw.cgw_safe_invariant.fst`

### Code Snippet
```scala
// Safety condition: boat is with goat OR goat is not with wolf AND goat is not with cabbage
io.safe := (boat === goat) || (goat =/= wolf && goat =/= cabbage)

// ===== FORMAL ASSERTIONS =====

// === Safety Invariant ===
// The core puzzle constraint: the goat must never be left alone with the wolf
// or the cabbage without the man (boat) present. This must hold at every cycle.
fvAssert(io.safe, "cgw_safe_invariant")
```

### Property Description (Natural Language)
The safety invariant states: **At every cycle, either the goat is with the boat (the man watching it), OR the goat is separated from both the wolf and the cabbage (so nothing can eat or be eaten).** In other words, the goat can never be left alone with the wolf or with the cabbage.

### File Location
- **File**: `cgw.scala`
- **Line**: 48-49 (assertion at line 49)

## 3. Waveform Information

- **Waveform File Path**: `verilog/extra_bench/cgw/cgw.cgw_safe_invariant.fst`
- **Duration**: 2 cycles (0 ns → 20 ns)
- **Failure Time**: 10 ns (assertion `cgw_safe_invariant` transitions from 1→0)

### Critical Signal Values at Key Time Points

| Signal | Time 0 | Time 10 |
|--------|--------|---------|
| `io_select [1:0]` | `01` (passengerCabbage) | `01` (passengerCabbage) |
| `boat` | `0` (sideLeft) | `1` (sideRight) |
| `cabbage` | `0` (sideLeft) | `1` (sideRight) |
| `goat` | `0` (sideLeft) | `0` (sideLeft) |
| `wolf` | `0` (sideLeft) | `0` (sideLeft) |
| `io_safe` | `1` | `0` |
| `cgw.cgw_safe_invariant` | `1` | `0` |

### Sequence of Events
1. **Cycle 0 (time 0–10)**: All four entities (boat, cabbage, goat, wolf) are on the left side (`sideLeft=0`). Input `io_select` is `01` (passengerCabbage).
2. **Cycle boundary (time 10)**: Since both cabbage and boat are on the same side (both `0`), the transition fires:
   - `cabbage := Mux(cabbage === sideRight, sideLeft, sideRight)` → cabbage moves to `sideRight` (1)
   - `boat := Mux(boat === sideRight, sideLeft, sideRight)` → boat moves to `sideRight` (1)
   - goat and wolf remain on `sideLeft` (0)
3. **After transition (time 10)**: `io_safe` evaluates to `0` because:
   - `(boat === goat)` = `(1 === 0)` = **false**
   - `(goat =/= wolf && goat =/= cabbage)` = `(0 =/= 0 && 0 =/= 1)` = `(false && true)` = **false**
   - `io.safe = false || false = false` → assertion violation

## 4. Root Cause Analysis

### Classification: **Setup Error** (Missing Input Constraints)

#### Explanation
This is **not** a bug in the DUT logic and **not** an incorrect assertion. The design correctly models the puzzle mechanics: it faithfully transitions state based on which passenger the man selects.

**The core problem**: The formal verification setup has **no constraints (assumptions) on `io.select`**. The formal tool can therefore choose *any* input sequence, including one where the man makes an invalid first move (taking the cabbage before the goat). In the actual puzzle, the man would never do this because he knows the goat and wolf cannot be left alone.

#### The Buggy Move
The chosen input `io.select = passengerCabbage (1)` at time 0 selects the cabbage as the first passenger to cross. Since both the cabbage and the boat start on the left side, the transition moves them both to the right, leaving the **goat alone with the wolf** on the left side. This creates an unsafe state where the wolf can eat the goat.

#### Missing Assumption
In a proper formal verification setup for this puzzle, one would need to add assumptions that constrain the input to only valid (safe) moves. For example:

```scala
// Assumption: Only select a passenger on the same side as the boat
assume(io.select === passengerNone || 
       (io.select === passengerCabbage && boat === cabbage) ||
       (io.select === passengerGoat && boat === goat) ||
       (io.select === passengerWolf && boat === wolf))
```

However, this alone is insufficient — even with same-side selection, the formal tool could still choose `passengerCabbage` as the first move. The more fundamental missing assumption is that the **environment (the man) must only choose moves that preserve the safety invariant**. Typically, this is achieved by:

```scala
// The environment never makes an unsafe move - this is the puzzle constraint
assume(io.select === passengerNone || ... /* only safe moves */)
```

Since no such constraints exist, the counterexample is a valid falsification of `fvAssert(io.safe, ...)` — the formal tool correctly identifies that the unprotected input can drive the system into an unsafe state.

#### Why This Is a Setup Error
- ✅ **DUT logic is correct**: The transition logic correctly implements the puzzle rules.
- ✅ **Assertion is correct**: `io.safe` correctly encodes the puzzle's safety constraint.
- ❌ **Verification setup is incomplete**: No assumptions constrain the environment inputs (`io.select`) to valid puzzle moves.

#### Evidence from Waveform
The trace clearly shows the sequence:
1. Time 0: Initial state with all entities on left, `io_select=01 (cabbage)` is applied
2. Time 10: cabbage and boat move to right; goat and wolf remain together on left
3. `io_safe=0` because `goat=wolf=0` (both on left) while `boat=1` (on right)

This matches exactly the scenario forbidden by the puzzle: the goat and wolf are left alone without the man (boat) present.

### Suggested Fix
Add input constraints (assumptions) in the formal verification environment to restrict `io.select` to only moves that preserve the safety invariant, OR add a mechanism in the DUT to reject unsafe transitions. The classic approach is to use `assume(...)` formal constraints that mirror the puzzle's implicit rule that the man makes only safe choices.
