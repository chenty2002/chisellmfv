# Counterexample Analysis Report: `cubeAbs.posReg_visited_when_initDone`

## 1. Verification Environment

- **Top Module**: `cubeAbs` (from `cubeAbs.scala`)
- **Module Type**: A cube traversal state machine with 27 valid positions (0–26)
- **Key Components**:
  - `posReg` (5-bit register): Current position, initialized to 0, also drives output `io.pos`
  - `visited` (Vec of 27 bool registers): Tracks which positions have been visited
  - `next` (5-bit wire): Computes `Cat(io.start(4, 1), ~posReg(0))` — next candidate position
  - `dest` (5-bit wire): Mux — if `next < 27` then `next` else `posReg`
  - `initDone` (1-bit register): Initialization flag, set to true after first cycle
- **Formal Verification Assertions**: 5 assertions checking safety and liveness properties

## 2. Violated Assertion

- **Full Assertion Name**: `posReg_visited_when_initDone`
- **Waveform file**: `cubeAbs.posReg_visited_when_initDone.fst`
- **Source location**: `cubeAbs.scala`, lines 71–72
- **Assertion code**:
  ```scala
  // Safety: after initialization completes, the current position must always
  // be marked as visited — this guards against missed state tracking
  fvAssert(!initDone || visited(posReg), "posReg_visited_when_initDone")
  ```
- **Natural language**: After initialization (`initDone` is true), the current position `posReg` must always have its corresponding bit set in the `visited` array.

## 3. Waveform Information

- **Waveform file path**: `verilog/extra_bench/cube_cubeAbs/cubeAbs.posReg_visited_when_initDone.fst`
- **Time range**: 0 ns → 20 ns (2 clock cycles)
- **Key time points**:

| Time (ns) | Event | `initDone` | `posReg` | `visited_0` | `visited_26` | Assertion |
|-----------|-------|-----------|----------|------------|-------------|-----------|
| 0 | Reset + posedge | 0 | 0 (0x00) | 0 | 0 | 1 (pass) |
| 5 | Half-cycle | 0 | 0 (0x00) | 0 | 0 | 1 (pass) |
| 10 | Posedge (cycle 1) | **1** | **26 (0x11010)** | **1** | **0** | **0 (FAIL)** |
| 15 | Half-cycle | 1 | 26 (0x11010) | 1 | 0 | 0 (FAIL) |
| 20 | Posedge (cycle 2) | 1 | 26 (0x11010) | 1 | 0 | 0 (FAIL) |

**Critical observation at time 10**: `initDone` = 1, `posReg` = 26, but `visited_26` = 0. The only visited bit set is `visited_0` = 1. The assertion fails because `visited(posReg)` = `visited(26)` = 0.

## 4. Root Cause Analysis

### Buggy Code Location

- **File**: `cubeAbs.scala`
- **Line**: **53** (inside the `when (!initDone)` block)
- **Code**:
  ```scala
  when (!initDone) {
    // Initialize position with bounds checking
    posReg := Mux((io.start > 26.U) || (io.start === 13.U), 0.U, io.start)
    // Initialize visited array to all 0
    for (i <- 0 until 27) {
      visited(i) := false.B
    }
    // Mark initial position as visited
    visited(posReg) := true.B    // <-- BUG: reads OLD posReg value
    initDone := true.B
  }
  ```

### Bug Description

**Category**: **Bug in the Original Design (DUT Bug)**

The bug is a **read-after-write hazard** — a classic mistake in Chisel hardware design. When the `when (!initDone)` block executes:

1. **Line 47**: `posReg := Mux(...)` — This schedules `posReg` to be updated to the new value `io.start` (26 in this counterexample) **after** the clock edge. The current read value of `posReg` is still 0 (from `RegInit`).

2. **Line 50–52**: All 27 `visited` bits are reset to `false.B`.

3. **Line 53**: `visited(posReg) := true.B` — **Here is the bug**. The expression `posReg` reads the **current** register value, which is still **0** (the old value from `RegInit`). It has NOT yet been updated to the new value of 26 because register assignments take effect only after the clock edge in the next cycle.

   Therefore, `visited(0) := true.B` is executed instead of the intended `visited(26) := true.B`.

4. **Line 54**: `initDone := true.B` — Sets the flag.

**After the clock edge (time 10)**:
- `posReg` updates to 26 (correct)
- `visited(0)` = 1 (set because posReg read as 0 when the block executed)
- `visited(26)` = 0 (should have been set, but wasn't)
- `initDone` = 1

The assertion `!initDone || visited(posReg)` evaluates to `0 || visited(26)` = `0 || 0` = **0 (FAIL)**.

### Why This Is a Bug

The designer's intent is clear: mark the initial position as visited. However, because Chisel uses a **non-blocking assignment model** (like Verilog `<=`), the register read in `visited(posReg)` returns the **old value** of `posReg`, not the newly assigned one. This is a fundamental hardware design pitfall.

### Evidence from Waveform

| Signal | Value at time 10 (after clock edge) | Expected | Reason |
|--------|--------------------------------------|----------|--------|
| `posReg` | 26 | 26 | Correctly updated |
| `visited_0` | 1 | 1 (for different position) | Wrong — should be 0 |
| `visited_26` | 0 | 1 | Wrong — should be 1 |
| `initDone` | 1 | 1 | Correct |

The failure is triggered specifically because `io.start` = 26 (0x11010), which is different from the initial `posReg` value of 0. Any `io.start` value other than 0 would trigger the same bug.

### Suggested Fix

Use a **wire** to precompute the initial position, then use it both for the register assignment and the visited indexing:

```scala
when (!initDone) {
  // Compute initial position in a wire to avoid read-after-write hazard
  val initPos = Mux((io.start > 26.U) || (io.start === 13.U), 0.U, io.start)
  posReg := initPos
  // Initialize visited array to all 0
  for (i <- 0 until 27) {
    visited(i) := false.B
  }
  // Mark initial position as visited — use the wire, not the register
  visited(initPos) := true.B
  initDone := true.B
}
```

This ensures `visited` is indexed by the same value that `posReg` is being set to, eliminating the read-after-write hazard.
