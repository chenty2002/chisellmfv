# Counterexample Analysis Report: `cubeAbs.current_position_visited`

## 1. Verification Environment

- **Top Module**: `cubeAbs` (from `cubeAbs.scala`)
- **Module Structure**: A 27-cell cube traversal design with position register, visited array, destination computation logic, and formal verification assertions
- **Key Components**:
  - `posReg` (5-bit register): Current position in the cube (0-26)
  - `visited` (27-bit array of registers): Tracks which cube positions have been visited
  - `next`/`dest` combinational logic: Computes next candidate position
  - `initDone` register: Initialization completion flag
- **Design Under Test**: A cube traversal system where movement starts from an initial position (`io.start`) and traverses through unvisited cells

## 2. Violated Assertion

- **Full Assertion Name**: `current_position_visited`
- **Waveform File**: `cubeAbs.current_position_visited.fst`
- **Assertion Code** (line 67):
  ```scala
  fvAssert(!initDone || visited(posReg), "current_position_visited")
  ```
- **Natural Language Description**: After initialization is complete (`initDone` is true), the current position (`posReg`) must always be marked as visited in the `visited` register array. This is a core invariant — the system should never be at a position it hasn't visited.
- **File Location**: `cubeAbs.scala`, line 67

## 3. Waveform Information

- **Full Waveform Path**: `verilog/extra_bench/cube_cubeAbs/cubeAbs.current_position_visited.fst`
- **Time Range**: 0 ns → 20 ns (2 cycles)
- **Key Time Points** and Signal Values:

| Signal | Time 0 | Time 10 |
|--------|--------|---------|
| `posReg` (5-bit) | `0` (00000) | `3` (00011) |
| `initDone` | `0` | `1` |
| `visited_0` | `0` | `1` |
| `visited_3` | `0` | `0` |
| `io_start` (5-bit) | `3` (00011) | `31` (11111) |
| `io_pos` (5-bit) | `0` (00000) | `3` (00011) |

- **Failure Point**: At time 10 ns (first clock edge after reset), `initDone` becomes 1, `posReg` becomes 3, but `visited(3)` is still 0, causing `!initDone || visited(posReg)` to evaluate to `!1 || 0 = 0` (false).

## 4. Root Cause Analysis

### Buggy Code Location

**File**: `cubeAbs.scala`, lines 42-51 (initialization block within `when (!initDone)`)

```scala
when (!initDone) {
    // Initialize position with bounds checking
    posReg := Mux((io.start > 26.U) || (io.start === 13.U), 0.U, io.start)
    // Initialize visited array to all 0
    for (i <- 0 until 27) {
      visited(i) := false.B
    }
    // Mark initial position as visited
    visited(posReg) := true.B    // <-- BUG: reads OLD value of posReg
    initDone := true.B
}
```

### Description of the Bug

**Classic Register Read-After-Write Problem in Chisel**: When `posReg` is written AND read in the same `when` block, the read operation sees the **old** value (before the clock edge), not the newly assigned value.

**Timing Breakdown**:

1. **At time 0** (before clock edge, with `!initDone` active):
   - `posReg` holds its reset value: **0**
   - `io.start` = **3** (00011)
   - The `posReg := Mux(...)` schedules `posReg` to become **3** at the next clock edge
   - The loop `for (i <- 0 until 27) { visited(i) := false.B }` clears all 27 visited registers
   - `visited(posReg) := true.B` reads **posReg = 0** (the old/reset value), so it marks `visited(0) := true.B`

2. **At time 10** (after clock edge):
   - `posReg` updates to **3** (the value of `io.start`)
   - `initDone` becomes **1**
   - `visited(0)` is **1** (correctly marked during init)
   - **`visited(3)` is still 0** (it was cleared but never set to true because `posReg` read 0, not 3)

3. **Assertion Failure**: `!initDone || visited(posReg)` = `!1 || visited(3)` = `0 || 0` = **false**

### Why This Happens

In Chisel, register assignments (`:=`) in a `when` block generate non-blocking assignments in the underlying Verilog. The read of `posReg` in `visited(posReg)` observes the value **before** the clock edge, not the value being assigned. The intent was to mark the initial position (`io.start`) as visited, but the code inadvertently reads the stale reset value of `posReg` instead.

### Evidence from Waveform

- `io_start` = 3 at time 0 → the initial position should be set to 3 (since 3 ≤ 26 and 3 ≠ 13)
- `posReg` at time 0 = 0 (reset value) → this is what was read for `visited(posReg)` during init
- `visited_0` = 1 at time 10 → confirms position 0 was incorrectly marked as visited
- `visited_3` = 0 at all times → position 3 was never marked as visited
- `posReg` = 3 at time 10 → the register did update correctly, but visited(3) was never set

### Fix

Replace `visited(posReg) := true.B` with a direct reference to the computed initial position:

```scala
val initPos = Mux((io.start > 26.U) || (io.start === 13.U), 0.U, io.start)
when (!initDone) {
    posReg := initPos
    for (i <- 0 until 27) {
      visited(i) := false.B
    }
    visited(initPos) := true.B    // Use the computed value directly
    initDone := true.B
}
```

This avoids the register read-after-write hazard by using the combinational value `initPos` directly, rather than reading back the register `posReg` which still holds its old value.

### Error Classification

**DUT Bug** (`dut_bug`): The design has a genuine hardware bug — a register read-after-write hazard during initialization that causes the wrong initial position to be marked as visited.
