# Counterexample Analysis Report

## 1. Verification Environment

- **Top Module**: `Jam` (from `jam.scala`)
- **Design**: Traffic Jam puzzle solver - a 7-slot sliding puzzle with 3 LEFT cars, 3 RIGHT cars, and 1 EMPTY slot. Cars can slide or jump over an adjacent car into the empty slot.
- **Starting State**: `RIGHT(10), RIGHT(10), RIGHT(10), EMPTY(00), LEFT(01), LEFT(01), LEFT(01)`
- **Goal State**: `LEFT(01), LEFT(01), LEFT(01), EMPTY(00), RIGHT(10), RIGHT(10), RIGHT(10)`
- **External Input**: `io.move` (3-bit) selects which slot to attempt to move
- **Key Signals**: `valid` (computed internally), `empty` (position of empty slot), `slots` (7x2-bit register array)

## 2. Violated Assertion

- **Assertion Name**: `no_move_no_empty_change` (from waveform filename `Jam.no_move_no_empty_change.fst`)
- **File**: `jam.scala`, line ~144

```scala
// INVARIANT 8: When valid is false and move is in range, slots do not change
// (no spurious updates)
assertStableWhen(!valid, empty.asUInt, "no_move_no_empty_change")
// Note: this checks that empty doesn't change when no move is made, 
// implying slots are stable
```

- **Natural Language Description**: When `!valid` is true (i.e., no valid move is being executed in the current cycle), the `empty` slot position should remain stable (not change between consecutive clock cycles). This is intended to verify that slots don't change unpredictably when no valid move is made.

## 3. Waveform Information

- **Waveform File**: `verilog/extra_bench/jam/Jam.no_move_no_empty_change.fst`
- **Time Range**: 0 ns → 20 ns (2 clock cycles)
- **Failure Time**: Assertion `no_move_no_empty_change` drops from 1 to 0 at time 10 ns (the positive clock edge between cycle 0 and cycle 1)

### Key Signal Values

| Time (ns) | valid | io_move | empty_debug | slots_0 | slots_1 | slots_2 | slots_3 | slots_4 | slots_5 | slots_6 |
|-----------|-------|---------|-------------|---------|---------|---------|---------|---------|---------|---------|
| 0 (cycle 0) | 1 | 010 (2) | 011 (3) | RIGHT | RIGHT | RIGHT | EMPTY | LEFT | LEFT | LEFT |
| 5 | 1 | 010 (2) | 011 (3) | RIGHT | RIGHT | RIGHT | EMPTY | LEFT | LEFT | LEFT |
| 10 (cycle 1) | 0 | 101 (5) | 010 (2) | RIGHT | RIGHT | EMPTY | RIGHT | LEFT | LEFT | LEFT |
| 15 | 0 | 101 (5) | 010 (2) | RIGHT | RIGHT | EMPTY | RIGHT | LEFT | LEFT | LEFT |
| 20 | 0 | 101 (5) | 010 (2) | RIGHT | RIGHT | EMPTY | RIGHT | LEFT | LEFT | LEFT |

### Sequence of Events

**Cycle 0 (time 0-10 ns):**
- `io_move = 2` (binary 010), `valid = 1`
- The move at position 2 (RIGHT) is a **slide right**: `mp1 = 2 + 1 = 3`, and `empty = 3`, so condition `mp1 === empty` holds
- This is a valid move: the RIGHT car at position 2 slides into the EMPTY slot at position 3
- After the move (observed at time 10): `slots(2)` becomes EMPTY, `slots(3)` becomes RIGHT, `empty` moves to position 2

**Cycle 1 (time 10-20 ns):**
- `io_move = 5` (binary 101), `valid = 0`
- The move at position 5 (LEFT) is NOT valid (slide right requires move < 6 and mp1==empty, but mp1=6 and empty=2; jump right requires move<5, but move=5)
- `empty` stays at position 2 throughout this cycle → slots are genuinely stable

## 4. Root Cause Analysis

### Error Classification: **Incorrect Assertion** (Category 2)

The assertion `assertStableWhen(!valid, empty.asUInt, "no_move_no_empty_change")` is **incorrectly formulated** and produces a false positive counterexample.

### Root Cause

The `assertStableWhen(cond, value, name)` primitive in ChiselFv checks that `value` is **stable relative to the previous clock cycle** whenever `cond` is true at the current clock edge. Formally, it translates to:

```
@(posedge clk) disable iff (reset) cond |=> $stable(value)
```

Meaning: on every clock edge where `cond` is true, check that `value` has the same value as at the previous clock edge.

**The failure occurs because:**

1. At **cycle 0** (time 0): `valid = 1` → `!valid = false` → the assertion is **not checked** (as expected)
2. At **cycle 1** (time 10): `valid = 0` → `!valid = true` → the assertion **is checked**
3. The assertion compares `empty` at cycle 1 (time 10) with `empty` at cycle 0 (time 0): **2 ≠ 3** → **ASSERTION FAILS**

**But this is legitimate behavior!** The `empty` value changed from 3 to 2 because a valid move was executed in cycle 0. The empty slot **correctly** moved from position 3 to position 2. In cycle 1, `valid=0` and `empty` genuinely remains stable at position 2. The assertion is only failing because it checks stability relative to the previous cycle, and the previous cycle had a different `empty` value due to a valid move.

### Why the Assertion Is Wrong

The intent (per the code comment) is to verify that "when no move is made, slots don't change." The correct interpretation should be: **during a period where `valid` is continuously false, the empty position should not change**. But the current assertion checks stability across the transition from `valid=1` to `valid=0`, which is invalid because `empty` legitimately changed during the period when `valid=1`.

### Evidence from Waveform

- At **time 0-10**: `valid = 1` (a valid move is occurring), `empty` transitions from 3 to 2
- At **time 10-20**: `valid = 0` (no valid move), `empty` is stable at 2 throughout
- The assertion should only fire when `valid` was also false in the **previous** cycle, not when `valid` just became false

### Suggested Fix

The assertion should be changed to only check stability during periods where `!valid` holds for **two consecutive cycles**. A corrected version would be:

```scala
// When valid is false and was also false in the previous cycle,
// empty should be stable (no spurious slot changes)
val prevValid = RegNext(valid)
assertStableWhen(!valid && !prevValid, empty.asUInt, "no_move_no_empty_change")
```

Or alternatively, the assertion should be reformulated to check that the slots themselves don't change:

```scala
// Direct check: when no move happens, slots should remain unchanged
// This avoids the transition issue with assertStableWhen
val slotsConcat = slots.asUInt
assertStableWhen(!valid, slotsConcat, "no_move_no_slot_change")
```

This way, on the first cycle where `valid` becomes false, the assertion is skipped, and it only starts checking once we've confirmed that `valid` has been false for at least two cycles, guaranteeing that any empty change is spurious.
