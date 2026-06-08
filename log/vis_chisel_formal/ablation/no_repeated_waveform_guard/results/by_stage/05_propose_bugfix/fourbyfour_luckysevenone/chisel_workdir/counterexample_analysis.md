# Counterexample Analysis Report: source_emptied_after_move

## 1. Verification Environment

- **Top module**: `luckySeven` (package `llmverify`)
- **File**: `luckySevenONE.scala`
- **Structure**: The module implements a 4×4 puzzle (8-slide version "Lucky Seven One") where tiles are moved on an 8-element board
- **Key components**:
  - `b`: Vec(8, UInt(3.W)) — the board state, initialized to `[0,1,2,3,4,5,6,7]` via `RegInit`
  - `freg`, `treg`: input registers, latch `io.from` and `io.to` each cycle
  - `valid`: combinatorial signal indicating whether a proposed move (from→to) is legal
  - `freg_valid`, `treg_valid`, `old_b_freg`: registers enabled by `valid` that capture the source, destination, and source value at the time of a valid move
  - Formal verification assertions with `fvAssert` and `assertNextStepWhen`

## 2. Violated Assertion

- **Full assertion name**: `source_emptied_after_move`
- **Waveform filename**: `luckySeven.source_emptied_after_move.fst`
- **Code location**: `luckySevenONE.scala`, line 66

```scala
assertNextStepWhen(valid, b(freg_valid) === 0.U, "source_emptied_after_move")
```

- **Natural language description**: After every cycle where `valid` is true (a legal move occurs), at the **next** clock cycle, the board position `freg_valid` (the source position from the previous valid move) must contain value 0 (be empty).

## 3. Waveform Information

- **Waveform file path**: `verilog/extra_bench/fourbyfour_luckysevenone/luckySeven.source_emptied_after_move.fst`
- **Time range**: 0 ns – 10 ns (1 clock cycle)
- **Key time points**:
  - **Time 0 ns** (positive clock edge, start of cycle): All signals are at their initial/reset values
  - **Time 5 ns** (negative clock edge): Clock transitions to 0
  - **Time 10 ns** (end of cycle): All signals unchanged

### Critical Signal Values at Time 0 ns

| Signal | Value | Description |
|--------|-------|-------------|
| `b[0..7]` | `[0,1,2,3,4,5,6,7]` | Board in **initial** state — no moves ever applied |
| `valid` | `0` | No valid move in current cycle |
| `freg` | `0` | Current from-register value |
| `treg` | `0` | Current to-register value |
| `io_from` | `7` | Input from (will be latched at next clock) |
| `io_to` | `7` | Input to (will be latched at next clock) |
| **`freg_valid`** | **`1`** | **Arbitrary initial value — no valid move ever occurred** |
| **`treg_valid`** | **`7`** | **Arbitrary initial value** |
| **`old_b_freg`** | **`7`** | **Arbitrary initial value** |
| `hasBeenReset` | `1` | Design has seen reset |
| `source_emptied_after_move` | `1` | Assertion monitor active |

## 4. Root Cause Analysis

### Bug Type: **Incorrect Assertion** (assertion_error)

### Root Cause

The counterexample is **spurious** — it is caused by the assertion not properly handling the uninitialized state of `RegEnable` registers, not by an actual design bug.

#### The Causal Chain

1. **Uninitialized `RegEnable` registers (lines 49–51)**:
   ```scala
   val freg_valid = RegEnable(freg, valid)    // line 49
   val treg_valid = RegEnable(treg, valid)    // line 50
   val old_b_freg = RegEnable(b(freg), valid) // line 51
   ```
   `RegEnable` in Chisel uses `Reg.next(next)` internally without an explicit reset value. In formal verification, registers without reset values can take **arbitrary initial values**. The formal solver exploits this degrees of freedom.

2. **The formal solver picks adversarial initial values**:
   - `freg_valid = 1` (arbitrary)
   - `treg_valid = 7` (arbitrary)
   - `old_b_freg = 7` (arbitrary)

3. **The `assertNextStepWhen` internal register** also has no reset value, so the formal solver can set it to `true`, effectively "pretending" that `valid` was true in the cycle before time 0.

4. **The assertion check fails**:
   The assertion `assertNextStepWhen(valid, b(freg_valid) === 0.U)` evaluates:
   - Since the internal `prev_enable` register is set to `1` (arbitrarily), the tool checks the property: `b(freg_valid) === 0.U`
   - `b(1) === 0` evaluates to `1 === 0` = **false**
   - **Assertion violation!**

### Evidence from the Waveform

- **Board is pristine**: `b = [0,1,2,3,4,5,6,7]` — this is the exact initial `RegInit` state. **No move has ever been applied** to the board.
- **`valid = 0`**: The current cycle has no valid move.
- **`freg = 0`, `treg = 0`**: The current registered from/to values are 0, consistent with initial state.
- **Yet `freg_valid = 1`**: This is the smoking gun. `freg_valid` should only capture values from actual valid moves (via `RegEnable(freg, valid)`). Since `valid = 0` and no move has occurred, `freg_valid` should never have been updated. Its value of `1` is purely an artifact of undefined reset behavior in formal verification.
- **`b(1) = 1 ≠ 0`**: Since the board is unmodified, position 1 still contains its initial value of `1`, not `0`.

### Why This Is Not a Design Bug

The design logic is correct:
- When `valid = 1`, it correctly executes `b(treg) := b(freg)` and `b(freg) := 0.U` (lines 43–46)
- The `RegEnable` registers correctly capture the relevant values at the moment of the valid move
- After an actual valid move, `b(freg_valid)` would indeed be `0` in the next cycle

The failure only occurs because the formal tool can set uninitialized registers to adversarial values that don't correspond to any actual sequence of valid moves.

### How to Fix

The assertion on line 66 needs a **guard condition** ensuring that `freg_valid` has been properly set by at least one valid move before the property is checked:

```scala
// Option 1: Add a "has been valid" register
val hasBeenValid = RegInit(false.B)
when(valid) { hasBeenValid := true.B }

// Then guard the assertion
assertNextStepWhen(valid && hasBeenValid, b(freg_valid) === 0.U, "source_emptied_after_move")
// Or equivalently as a direct assertion:
fvAssert(!(valid && hasBeenValid) || b(freg_valid) === 0.U, "source_emptied_after_move")
```

Alternatively, initialize the `RegEnable` registers with proper reset values:

```scala
val freg_valid = RegEnable(freg, 0.U, valid)  // with reset value
val treg_valid = RegEnable(treg, 0.U, valid)
val old_b_freg = RegEnable(b(freg), 0.U, valid)
```

However, the proper reset values (`0.U`) would also fail the assertion `b(freg_valid) === 0.U` when `freg_valid` is 0 (since `b(0) = 0` initially, this would actually pass), but the real issue is that `assertNextStepWhen`'s internal enable register also needs initialization.
