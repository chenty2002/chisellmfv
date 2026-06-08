# Counterexample Analysis Report

## 1. Verification Environment

- **Top module**: `Jam` (in `jam.scala`)
- **Module type**: Chisel `Module with Formal`
- **Design under test**: A sliding-puzzle "Traffic Jam" game solver with 7 slots (3 RIGHT, 1 EMPTY, 3 LEFT) and move logic
- **Key components**:
  - `slots`: Vector of 7 registers (2-bit each), initialized to [RIGHT, RIGHT, RIGHT, EMPTY, LEFT, LEFT, LEFT]
  - `empty`: Combinational wire identifying the position of the empty slot
  - `valid`: Combinational wire checking move legality (slide right, slide left, jump right, jump left)
  - `done`: Combinational wire checking if puzzle is solved
  - RegNext registers for formal verification: `prev_valid`, `prev_move`, `prev_empty`, `prev_slots_at_move`

## 2. Violated Assertion

- **Assertion name**: `valid_move_source_becomes_empty`
- **Waveform filename**: `Jam.valid_move_source_becomes_empty.fst`
- **Code location**: `jam.scala`, lines 139-141

### Code snippet

```scala
val prev_valid         = RegNext(valid)
val prev_move          = RegNext(io.move)
val prev_empty         = RegNext(empty)
val prev_slots_at_move = RegNext(slots(io.move))

fvAssert(
    !prev_valid || slots(prev_move) === CellState.EMPTY,
    "valid_move_source_becomes_empty"
)
```

### Property description

The assertion checks that **after a valid move, the source position of the moved piece becomes empty**. Specifically:
- If there was a valid move in the previous cycle (`prev_valid` is true),
- Then the slot at the move position (`slots(prev_move)`) must now (in the current cycle) be `CellState.EMPTY`.

This is an invariant that should hold after the first cycle: when a piece moves from position P to the empty slot, position P becomes the new empty slot.

## 3. Waveform Information

- **Full waveform path**: `verilog/extra_bench/jam/Jam.valid_move_source_becomes_empty.fst`
- **Duration**: 1 cycle (0 ns → 10 ns)
- **Time range**: 0 ns to 10 ns
- **Key time point**: Time 0 ns (positive clock edge, assertion evaluation point)

### Critical signal values at time 0

| Signal | Value | Interpretation |
|--------|-------|---------------|
| `Jam.clock` | 1 | Positive edge, assertion evaluates here |
| `Jam.reset` | 0 | Not in reset |
| `Jam.hasBeenReset` | 1 | Reset has occurred |
| `Jam.io_move [2:0]` | 111 (7) | Input move is 7 (out-of-range index for 7 slots 0-6) |
| `Jam.valid` | 0 | No valid move this cycle |
| `Jam.prev_valid` | **1** | Uninitialized `RegNext` — claims a valid move happened |
| `Jam.prev_move [2:0]` | **110 (6)** | Uninitialized `RegNext` — claims move was at position 6 |
| `Jam.prev_empty [2:0]` | 011 (3) | Uninitialized (coincidentally equals actual empty position) |
| `Jam.prev_slots_at_move [1:0]` | **11 (3)** | Uninitialized — **invalid CellState encoding** (valid: 0=EMPTY, 1=LEFT, 2=RIGHT) |
| `Jam.slots_6 [1:0]` | 01 (1 = LEFT) | Slot 6 contains a LEFT piece, NOT EMPTY |
| `Jam.valid_move_source_becomes_empty` | 1 | Assertion monitor signal (assertion is evaluating) |

## 4. Root Cause Analysis

### Bug classification: **setup_error** — Uninitialized RegNext registers cause spurious assertion failure

### Root cause

The registers `prev_valid`, `prev_move`, `prev_empty`, and `prev_slots_at_move` are created using **`RegNext(x)`** (lines 131-134 in `jam.scala`), which generates registers **without reset values**:

```scala
val prev_valid         = RegNext(valid)           // No reset value!
val prev_move          = RegNext(io.move)         // No reset value!
val prev_empty         = RegNext(empty)           // No reset value!
val prev_slots_at_move = RegNext(slots(io.move))  // No reset value!
```

In formal verification, registers without reset values are **unconstrained in the initial state**. The formal solver can freely choose any value for them at time 0 (before any clock edge has occurred).

### Evidence from waveform

The `prev_slots_at_move` register provides the clearest evidence:

1. **Invalid CellState encoding**: At time 0, `prev_slots_at_move` has value **3 (binary `11`)**. The valid CellState encodings are 0 (EMPTY), 1 (LEFT), and 2 (RIGHT). Value 3 is not a valid state, confirming this register is uninitialized.

2. **Arbitrary values chosen by solver**: The solver chooses:
   - `prev_valid = 1` (true) — indicating a valid move in the "previous" cycle
   - `prev_move = 6` — indicating the move was at slot position 6
   - `slots(6) = LEFT` (not EMPTY)

3. **Assertion failure**: With these values, the assertion evaluates to:
   - `!prev_valid || slots(prev_move) === CellState.EMPTY`
   - `!1 || slots(6) === 0`
   - `false || (LEFT === EMPTY)`
   - `false || false`
   - **`false`** → **Assertion FAILS**

### Why the design logic is correct

In normal operation (after reset and at least one clock edge):
- `prev_valid` would follow `valid` (which is 0 at initialization since `io.move=7` is out of range for all conditions)
- `prev_move` would follow `io.move`
- After the first valid move, `slots(prev_move)` would indeed be `CellState.EMPTY` because the move logic sets `slots(io.move) := CellState.EMPTY`
- The invariant holds under real circuit behavior

### Fix

The registers should be initialized with proper reset values:

```scala
// Option 1: Initialize RegNext with reset values
val prev_valid         = RegNext(valid, false.B)
val prev_move          = RegNext(io.move, 0.U(3.W))
val prev_empty         = RegNext(empty, 0.U(3.W))
val prev_slots_at_move = RegNext(slots(io.move), CellState.EMPTY)
```

Or equivalently:

```scala
// Option 2: Use RegInit equivalent
val prev_valid         = RegNext(valid, 0.U(1.W))
val prev_move          = RegNext(io.move, 0.U(3.W))
val prev_empty         = RegNext(empty, 0.U(3.W))
val prev_slots_at_move = RegNext(slots(io.move), 0.U(2.W))
```

### Note on other assertions

The same uninitialized `RegNext` issue would also affect the companion assertion `"valid_move_old_empty_gets_piece"` (line 145), since it also uses `prev_valid`, `prev_empty`, and `prev_slots_at_move`. A similar spurious failure may occur for that assertion as well.
