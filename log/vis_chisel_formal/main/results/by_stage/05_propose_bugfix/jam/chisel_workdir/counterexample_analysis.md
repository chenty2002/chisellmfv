# Counterexample Analysis Report: `Jam.slots_stable_when_invalid`

## 1. Verification Environment

- **Top Module**: `Jam` (from `jam.scala`)
- **Structure**: A single module implementing a "Traffic Jam" puzzle game with 7 slots and one empty cell.
- **Key Components**:
  - `slots` (Vec(7, UInt(2.W))) — register holding the state of each slot (EMPTY=00, LEFT=01, RIGHT=10)
  - `empty` — combinatorial wire computing the position of the empty slot via one-hot mux
  - `valid` — combinatorial wire checking if the current move (`io.move`) is legal
  - `done` — combinatorial wire checking if the puzzle is solved (LEFT,LEFT,LEFT,EMPTY,RIGHT,RIGHT,RIGHT)
- **Design Behavior**: When `valid` is true, the piece at `io.move` swaps with the empty slot at `empty`. Initial configuration is RIGHT,RIGHT,RIGHT,EMPTY,LEFT,LEFT,LEFT.

## 2. Violated Assertion

- **Assertion Name**: `slots_stable_when_invalid`
- **Full Path**: `jam.scala`, line 117
- **Code**:
  ```scala
  fvAssert(valid || slots === RegNext(slots), "slots_stable_when_invalid")
  ```
- **Property Description**: The assertion requires that at every cycle, either the current move is valid (`valid === true`), OR the slots have not changed since the previous cycle (`slots === RegNext(slots)`). In other words, slots should only change when a valid move is being applied.

## 3. Waveform Information

- **Waveform File**: `verilog/extra_bench/jam/Jam.slots_stable_when_invalid.fst`
- **Time Range**: 0 ns → 20 ns (2 clock cycles)
- **Key Time Points**:

### Cycle 0 (t = 0 ns, posedge)
| Signal | Value |
|---|---|
| `io_move [2:0]` | `100` (4) |
| `slots_0..slots_2 [1:0]` | `10` (RIGHT) |
| `slots_3 [1:0]` | `00` (EMPTY) |
| `slots_4..slots_6 [1:0]` | `01` (LEFT) |
| `empty` = `io_empty_debug [2:0]` | `011` (3) |
| `valid` = `io_valid_debug` | `1` |
| `slots_stable_when_invalid` | `1` (passes) |

**Move**: Slide left: position 4 (LEFT) moves into position 3 (EMPTY). Valid because `mm1 = 4-1 = 3 === empty(3)`.

### Cycle 1 (t = 10 ns, posedge)
| Signal | Value |
|---|---|
| `io_move [2:0]` | `100` (4) — unchanged input |
| `slots_0..slots_2 [1:0]` | `10` (RIGHT) |
| `slots_3 [1:0]` | `01` (LEFT) — changed from EMPTY |
| `slots_4 [1:0]` | `00` (EMPTY) — changed from LEFT |
| `slots_5..slots_6 [1:0]` | `01` (LEFT) |
| `REG_0..REG_6 [1:0]` | Same as initial (RegNext captured old slots) |
| `empty` = `io_empty_debug [2:0]` | `100` (4) |
| `valid` = `io_valid_debug` | `0` |
| `slots_stable_when_invalid` | `0` (FAILS) |

**State**: After the valid move of cycle 0, slots at position 3 is LEFT and position 4 is EMPTY. With `io_move=4` and `empty=4`, there is no valid move (slide right: position 5 is LEFT not EMPTY; slide left: position 3 is LEFT not EMPTY? Actually position 3 is LEFT, and mm1=3, but we need mm1===empty=4 which is false). So valid=0.

## 4. Root Cause Analysis

### Category: Incorrect Assertion (assertion_error)

### Bug Location

**File**: `jam.scala`, line 117
**Buggy Code**:
```scala
fvAssert(valid || slots === RegNext(slots), "slots_stable_when_invalid")
```

### Description

The assertion has a **timing mismatch** between the `valid` signal and the `slots` comparison.

- `valid` is a **combinational** signal computed from `slots` and `io.move` in the **current** cycle.
- `slots` is a **register** that updates on the clock edge, reflecting the state **after** the previous cycle's update.
- `RegNext(slots)` captures `slots` from the **previous** cycle.

When a valid move was applied in cycle 0 (t=0), the slots change at the posedge. In cycle 1 (t=10), the new slot values are already visible, but `valid` in cycle 1 is computed from these new values and may be false. Meanwhile `RegNext(slots)` still holds the pre-update slot values from cycle 0.

**The assertion checks**: "Is the current move valid, OR have the slots not changed?"

But the correct property should be: "If the **previous** move was valid, slots may change; if the **previous** move was **invalid**, slots must stay stable."

### Evidence from Waveform

| Time | `valid` | `slots[3]` | `slots[4]` | `RegNext(slots)` | Assertion |
|---|---|---|---|---|---|
| 0 | 1 | EMPTY (00) | LEFT (01) | (initial) | PASS (valid=1) |
| 10 | 0 | LEFT (01) | EMPTY (00) | [...,EMPTY,LEFT,...] | **FAIL** (0 || false) |

At t=10, the slots changed because of a **valid** move in the previous cycle. The assertion incorrectly blames the current cycle's `valid` for this change.

### Why This Causes the Assertion to Fail

The formal solver chose `io_move=4` for both cycles 0 and 1. This is a legal input — there is no constraint requiring `io_move` to change. The sequence is:

1. **Cycle 0**: `io_move=4`, `valid=1` (slide left from 4→3). Slots update: slot[3]←LEFT, slot[4]←EMPTY.
2. **Cycle 1**: `io_move=4`, `valid=0` (no valid move now). But slots are **already** changed from cycle 0's update.
3. The assertion sees `valid=0` AND `slots ≠ RegNext(slots)`, so it fails.

### Fix

The assertion should use `RegNext(valid)` to check whether the **previous** cycle's move was valid:

```scala
fvAssert(RegNext(valid) || slots === RegNext(slots), "slots_stable_when_invalid")
```

This correctly expresses: "Either the previous cycle had a valid move (explaining why slots may have changed), or the slots are unchanged."

An alternative fix would be to constrain `io.move` to change every cycle, but this is unnecessary and would weaken the verification.

### Summary

| Aspect | Detail |
|---|---|
| Error type | **assertion_error** |
| File | `jam.scala` line 117 |
| Root cause | Assertion uses `valid` (current cycle) instead of `RegNext(valid)` (previous cycle) |
| Fix | `fvAssert(RegNext(valid) \|\| slots === RegNext(slots), "slots_stable_when_invalid")` |
