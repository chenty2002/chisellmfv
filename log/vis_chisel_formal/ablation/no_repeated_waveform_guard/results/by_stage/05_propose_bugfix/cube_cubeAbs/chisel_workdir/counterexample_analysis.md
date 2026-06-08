# Counterexample Analysis Report: cubeAbs Assertion Failure

## 1. Verification Environment

- **Top Module**: `cubeAbs` (Chisel `Module with Formal`)
- **Work Directory**: `chisel/extra_bench/cube_cubeAbs`
- **Generated Verilog**: `chisel/extra_bench/cube_cubeAbs/generated/`
- **Waveform File**: `verilog/extra_bench/cube_cubeAbs/cubeAbs.current_position_visited_after_init.fst`

**Design Description**: A "cube" traversal circuit that navigates a graph of up to 27 positions (indices 0-26). It maintains:
- `posReg`: Current position register (5-bit, initialized from `io.start`)
- `visited[27]`: Array of 27 booleans tracking which positions have been visited
- `initDone`: Flag indicating whether initialization is complete
- `dest`: Next destination computed from `io.start` via a bit manipulation

**Formal Properties Verified**:
- `posReg_in_range`: posReg must be a valid visited array index (< 27)
- `dest_in_range`: dest must be a valid visited array index (< 27)
- `current_position_visited_after_init`: After initDone, the current position must be marked visited
- `visited_count_non_decreasing`: The visited count is monotonically increasing
- `progress_when_unvisited_dest`: Liveness property for position changes

## 2. Violated Assertion

- **Full Assertion Name**: `current_position_visited_after_init`
- **Waveform Filename**: `cubeAbs.current_position_visited_after_init.fst`

### Code Snippet (cubeAbs.scala, lines 70-71):
```scala
// Safety: after initialization completes, the current position is always marked as visited
fvAssert(!initDone || visited(posReg), "current_position_visited_after_init")
```

### Natural Language Description:
After the initialization sequence completes (`initDone` is true), the current position (`posReg`) must always have its corresponding entry in the `visited` array set to `true`. In other words, the initialization logic must mark the starting position as visited so it is never seen as unvisited thereafter.

## 3. Waveform Information

- **Waveform Path**: `verilog/extra_bench/cube_cubeAbs/cubeAbs.current_position_visited_after_init.fst`
- **Time Range**: 0 ns → 20 ns (2 clock cycles)
- **Failure Time**: At time 10 ns (after first clock edge), `current_position_visited_after_init` evaluates to `0` (false).

### Key Signal Values at Failure Point (time 10 ns):

| Signal | Value | Meaning |
|--------|-------|---------|
| `cubeAbs.io_start [4:0]` | `11010` (26) | Input starting position |
| `cubeAbs.posReg [4:0]` | `11010` (26) | Current position = 26 |
| `cubeAbs.initDone` | `1` | Initialization completed |
| `cubeAbs.visited_0` | `1` | visited[0] = true (WRONG) |
| `cubeAbs.visited_26` | `0` | visited[26] = false (WRONG) |
| `cubeAbs.current_position_visited_after_init` | `0` | **Assertion FAILED** |

### Sequence of Events:

| Time (ns) | Event |
|-----------|-------|
| 0 | Reset; `io_start`=26, `posReg`=0, `initDone`=0, all `visited`[i]=0 |
| 10 | Positive clock edge; init block executes: `posReg` ← 26, `initDone` ← 1, `visited(0)` ← 1 (BUG), `visited(26)` ← 0 (BUG) |
| 10 | Assertion checks: `initDone && !visited(posReg)` → `1 && !0` → **FAIL** |

## 4. Root Cause Analysis

### Bug Location

- **File**: `cubeAbs.scala`
- **Lines**: 51-60 (the `when (!initDone)` initialization block)
- **Module**: `class cubeAbs`

### Buggy Code (cubeAbs.scala, lines 51-60):
```scala
when (!initDone) {
    // Initialize position with bounds checking
    posReg := Mux((io.start > 26.U) || (io.start === 13.U), 0.U, io.start)
    // Initialize visited array to all 0
    for (i <- 0 until 27) {
      visited(i) := false.B
    }
    // Mark initial position as visited
    visited(posReg) := true.B    // <--- BUG: reads OLD posReg value
    initDone := true.B
}
```

### Description of the Bug

This is a **classic Chisel register read-after-write timing issue** within the same `when` block.

In Chisel, registers are updated **synchronously** at the clock edge. Within a single `when` block, when you assign to a register (`:=`) and then **read** the same register, the read returns the **OLD (pre-clock-edge)** value, not the value being assigned.

Here is the detailed sequence:

1. **`posReg := Mux(...)`** — Schedules `posReg` to be updated to `26` (io.start) at the next clock edge.
2. **Loop** `visited(i) := false.B` — Schedules all visited entries to false.
3. **`visited(posReg) := true.B`** — Here, `posReg` is read to determine which index to set. But since `posReg` is a register that was just assigned in step 1, **the read yields the OLD value**, which is `0` (from `RegInit(0.U(5.W))`). So this schedules `visited(0) := true.B` instead of `visited(26) := true.B`.

### Effect on Circuit Behavior

After the first clock edge (time 10):
- `posReg` correctly gets the value `26` (the starting position)
- `visited(26)` remains `false` because the code erroneously set `visited(0)` to `true` instead
- The assertion `!initDone || visited(posReg)` evaluates to `!1 || visited(26)` = `0 || 0` = `0` → **Assertion violation**

### Root Cause Category

**Design Bug (DUT Bug)**: The design incorrectly uses `posReg` (a register) as an index into `visited` after assigning to `posReg` in the same `when` block. The read returns the old register value instead of the new value being assigned.

### Fix

Replace the direct read of the register `posReg` with a wire that holds the computed initial position:

```scala
// Compute initial position in a wire (combinational)
val initPos = Wire(UInt(5.W))
initPos := Mux((io.start > 26.U) || (io.start === 13.U), 0.U, io.start)

when (!initDone) {
    posReg := initPos
    for (i <- 0 until 27) {
      visited(i) := false.B
    }
    visited(initPos) := true.B   // Correct: reads the wire, not the register
    initDone := true.B
}
```

Using `initPos` (a `Wire`) instead of `posReg` (a `Reg`) ensures the correct computed value is used as the index for marking the initial position as visited. Wires are combinational and always reflect their current driver value, avoiding the register read-before-write pitfall.
