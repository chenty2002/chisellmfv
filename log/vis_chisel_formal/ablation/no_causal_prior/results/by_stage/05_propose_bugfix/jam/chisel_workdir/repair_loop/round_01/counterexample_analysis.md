# Counterexample Analysis Report: `exactly_three_left_cars`

## 1. Verification Environment

- **Top Module**: `Jam` (jam.scala:14:7)
- **Generated Verilog**: `generated/Jam.sv`
- **Key Components**: 
  - `slots_0` through `slots_6`: Seven 2-bit registers holding cell states (EMPTY=00, LEFT=01, RIGHT=10)
  - `valid`: Combinational logic determining if a move is legal
  - `_empty_T_19`: The computed position of the empty slot (3-bit)
  - `_leftCount_T_17`: The sum of LEFT cars across all slots
  - `hasBeenResetReg`: Reset tracking register for formal assertions
- **Design Description**: A Chisel implementation of the "Traffic Jam" puzzle. The game board has 7 slots. Starting configuration has [RIGHT, RIGHT, RIGHT, EMPTY, LEFT, LEFT, LEFT]. Cars (LEFT/RIGHT) can slide or jump into the empty slot. The goal is to reach [LEFT, LEFT, LEFT, EMPTY, RIGHT, RIGHT, RIGHT].

## 2. Violated Assertion

- **Assertion Name**: `exactly_three_left_cars` (from waveform filename `Jam.exactly_three_left_cars.fst`)
- **Chisel Source** (jam.scala, line ~103):
  ```scala
  val leftCount = slots.map(s => (s === CellState.LEFT).asUInt).reduce(_ + _)
  ...
  fvAssert(leftCount === 3.U, "exactly_three_left_cars")
  ```
- **Generated Verilog** (Jam.sv, line ~170):
  ```verilog
  exactly_three_left_cars:
      assert property (@(posedge clock) disable iff (~hasBeenReset) 1'h0);
  ```
- **Natural Language Description**: The assertion checks that the total number of LEFT cars across all 7 slots is always exactly 3. This is a fundamental invariant of the Traffic Jam puzzle—cars are conserved during valid moves.

## 3. Waveform Information

- **Waveform File**: `verilog/extra_bench/jam/Jam.exactly_three_left_cars.fst`
- **Time Range**: 0 ns → 10 ns (1 full clock cycle)
- **Key Time Points and Signal Values**:

| Signal | Time 0 | Time 10 | Description |
|--------|--------|---------|-------------|
| `Jam.:jasper_formal_reset` | 0 | (unchanged) | Formal reset deasserted (assertions active) |
| `Jam.hasBeenReset` | 1 | 1 | System has been reset |
| `Jam.io_move [2:0]` | 000 | 000 | Move input = 0 (no move) |
| `Jam.valid` | 0 | 0 | Move is invalid (io_move=0 cannot be valid) |
| `Jam.slots_0 [1:0]` | 10 (RIGHT) | 10 | Slot 0 |
| `Jam.slots_1 [1:0]` | 10 (RIGHT) | 10 | Slot 1 |
| `Jam.slots_2 [1:0]` | 10 (RIGHT) | 10 | Slot 2 |
| `Jam.slots_3 [1:0]` | 00 (EMPTY) | 00 | Slot 3 |
| `Jam.slots_4 [1:0]` | 01 (LEFT) | 01 | Slot 4 |
| `Jam.slots_5 [1:0]` | 01 (LEFT) | 01 | Slot 5 |
| `Jam.slots_6 [1:0]` | 01 (LEFT) | 01 | Slot 6 |
| **`Jam._leftCount_T_17`** | **1** | **1** | **LEFT car count (truncated to 1 bit!)** |
| `Jam.r` | 1 | 1 | Delayed copy of `_leftCount_T_17` |

## 4. Root Cause Analysis

### Root Cause Category: **dut_bug** — Width truncation in `leftCount` computation

### Bug Location
- **File**: `jam.scala`, lines ~100-103
- **Buggy Code**:
  ```scala
  val leftCount = slots.map(s => (s === CellState.LEFT).asUInt).reduce(_ + _)
  ```
- **Generated Verilog** (Jam.sv, lines ~97-99):
  ```verilog
  wire            _leftCount_T_17 =
      _leftCount_T + _leftCount_T_1 + _leftCount_T_2 + (slots_3 == 2'h1) + (slots_4 == 2'h1)
      + (slots_5 == 2'h1) + (slots_6 == 2'h1);
  ```

### Description of the Bug

The `leftCount` variable is computed as the sum of 7 boolean (1-bit) values. The sum of 7 values has a maximum of 7 (3'b111), requiring at least **3 bits** to represent correctly. However, the generated Verilog declares `_leftCount_T_17` as a bare `wire` (1 bit), which truncates the sum to its least significant bit.

**How the truncation manifests:**

In the initial state, there are exactly 3 LEFT cars (slots 4, 5, 6), so the true sum is 3 (binary `011`). Due to the 1-bit width of `_leftCount_T_17`, the value is truncated to `1` (binary `1`).

With `_leftCount_T_17 = 1`, the comparison `leftCount === 3.U` (i.e., `1'b1 === 3'h3`) is always false. The FIRRTL/CIRCT compiler optimizes this constant-false comparison to `1'h0`:

```verilog
exactly_three_left_cars:
    assert property (@(posedge clock) disable iff (~hasBeenReset) 1'h0);
```

### Evidence from Waveform

1. **Initial state has 3 LEFT cars**: `Jam.slots_4=01`, `Jam.slots_5=01`, `Jam.slots_6=01` (all LEFT). True leftCount = 3.

2. **`_leftCount_T_17 = 1`** at all time points (truncated from 3 to 1 due to 1-bit width).

3. **The assertion in Verilog is `1'h0`** (constant false), meaning it fails on every clock cycle regardless of system state.

4. **No valid moves occur** (`Jam.valid = 0`, `Jam.io_move = 000`), so the slots never change. The assertion fails immediately at time 0 purely due to the width truncation issue.

### Why the Bug Causes the Assertion to Fail

The assertion `fvAssert(leftCount === 3.U, "exactly_three_left_cars")` is meant to verify the invariant that exactly 3 LEFT cars exist at all times. In the initial state, this invariant holds (3 LEFT cars exist). However, because `_leftCount_T_17` is incorrectly narrowed to 1 bit, the value `3` is truncated to `1`, making the comparison `1 === 3` always false. The compiler optimizes this to a constant-false assertion, which trivially fails.

### Recommended Fix

The `leftCount` computation using `.reduce(_ + _)` relies on Chisel's width inference, which should produce a properly-wide result. The issue may be a FIRRTL/CIRCT compilation pipeline bug. To work around it, use an explicit width specification or a different counting approach:

**Option A**: Explicitly specify the width:
```scala
val leftCount = Wire(UInt(3.W))
leftCount := slots.map(s => (s === CellState.LEFT).asUInt).reduce(_ + _)
```

**Option B**: Use a PopCount-style approach:
```scala
val leftCount = slots.count(_ === CellState.LEFT).asUInt
```

**Option C**: Explicit bit-width annotation:
```scala
val leftCount = slots.map(s => (s === CellState.LEFT).asUInt).reduce(_ + _)(2, 0)
```

Similarly, the same width issue affects `rightCount` and `emptyCount` (which are computed identically), so the assertions `exactly_three_right_cars` and `exactly_one_empty_slot` may also be affected by the same Verilog generation bug.
