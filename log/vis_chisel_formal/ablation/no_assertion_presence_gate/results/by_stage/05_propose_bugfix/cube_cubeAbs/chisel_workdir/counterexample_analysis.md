# Counterexample Analysis: cubeAbs.progress_bounded

## 1. Verification Environment

- **Top module**: `cubeAbs` (package `llmverify`)
- **Design hierarchy**:
  - `cubeAbs` — main module with position register, visited array, and transition logic
  - `cubeAbs.resetCounter` — internal reset counter module
- **Key components**:
  - `posReg` (5-bit register) — current position in a 27-vertex cube [0..26]
  - `visited` (27-bit register array) — track which positions have been explored
  - `next` (wire) — next computed position via transition function
  - `dest` (wire) — clamped destination: `Mux(next < 27, next, posReg)`
  - `timer` (5-bit counter) — counts consecutive cycles without progress
  - `initDone` (1-bit register) — indicates initialization complete
- **Stimulus**: `io.start` (5-bit start position) applied at reset; `io.dir` (3-bit, unused)

## 2. Violated Assertion

- **Full assertion name**: `progress_bounded` (from waveform filename `cubeAbs.progress_bounded.fst`)
- **Code location**: `cubeAbs.scala`, lines 83-88
- **Code snippet**:
  ```scala
  assertLivenessTimer(
    cond = true.B,
    reset = (posReg =/= RegNext(posReg)) || !initDone,
    n = 28,
    msg = "progress_bounded"
  )
  ```
- **Property description**: After initialization completes, the position register should never remain unchanged for 28 or more consecutive cycles. This ensures the exploration makes progress through the cube (visiting new positions or detecting completion).

## 3. Waveform Information

- **Waveform file**: `verilog/extra_bench/cube_cubeAbs/cubeAbs.progress_bounded.fst`
- **Failure time**: 300 ns (cycle 30)
- **Key time points**:

| Time (ns) | Cycle | posReg | next | dest | timer | initDone | Event |
|-----------|-------|--------|------|------|-------|----------|-------|
| 0 | 0 | 00000 | — | — | 00000 | 0 | Initial state after reset |
| 10 | 1 | 11010 (26) | 11011 (27) | 11010 (26) | 00000 | 1 | Init: posReg←26, visited[26]=true |
| 20 | 2 | 11010 (26) | 11011 (27) | 11010 (26) | 00000 | 1 | No position change; timer reset deasserted |
| 30 | 3 | 11010 (26) | 11011 (27) | 11010 (26) | 00001 | 1 | Timer starts counting |
| … | … | 11010 (26) | 11011 (27) | 11010 (26) | … | 1 | Position stuck at 26, timer increments each cycle |
| 300 | 30 | 11010 (26) | 11011 (27) | 11010 (26) | 11100 (28) | 1 | **Assertion fails: timer = 28** |

- **Critical signal values at failure point** (300 ns):
  - `posReg` = 11010 (26)
  - `next` = 11011 (27)
  - `dest` = 11010 (26)
  - `timer` = 11100 (28)
  - `progress_bounded` = 0 (assertion violation)
  - `initDone` = 1
  - `visited_26` = 1

## 4. Root Cause Analysis

### Buggy Location

- **File**: `cubeAbs.scala`, line 46
- **Code**: `next := Cat(posReg(4, 1), ~posReg(0))`
- Also relevant: line 47 (`dest := Mux(next < 27.U, next, posReg)`) and lines 52-59 (init block setting `initPos = io.start` when within bounds)

### Description of the Bug

The design implements a cube-graph traversal where the next candidate position is computed by the transition function:
```scala
next := Cat(posReg(4, 1), ~posReg(0))
```

For **position 26** (binary `11010`):
- `posReg(4,1)` extracts bits [4:1] = `1101`
- `~posReg(0)` = `~0` = `1`
- `next` = `Cat(1101, 1)` = `11011` = **27**

Since 27 is not less than 27, the dest mux selects `posReg` (26) instead of `next`. This means **position 26 has no valid outgoing transition** — it is a sink. Once the design starts at position 26 (initialized from `io.start = 26`), its only neighbor (27) is out of bounds, so it remains stuck forever.

The visited-check logic (`!visited(dest)`) then prevents any progress because `dest` always equals the current position (which is already visited). The liveness timer counts up to 28 consecutive cycles without change, triggering the assertion failure.

### Why This Is a Design Bug (Not an Assertion Error)

1. The transition function `Cat(posReg(4,1), ~posReg(0))` is not surjective onto [0..26] — it can produce values ≥ 27 for some valid inputs (e.g., position 26 → 27, position 27 would map to something else but 27 is not a valid position).

2. The `dest` mux (`Mux(next < 27.U, next, posReg)`) is a safety net that clamps out-of-bounds values, but it turns the clamped position into a permanent sink. This is not a hardware error per se, but it creates a dead-end traversal path.

3. The problem is triggered by the **initial position selection** — `io.start = 26` passes the bounds check (`26 > 26` is false, `26 === 13` is false) and becomes `initPos = 26`. From this starting position, exploration immediately stalls.

### Evidence from Waveform

- At **10 ns**: `posReg` transitions from 0 → 26 (via init), `next` = 27, `dest` = 26, `visited_26` = 1
- At **10–300 ns**: `posReg` (26), `next` (27), and `dest` (26) remain **completely unchanged** for all 30 cycles
- The `timer` monotonically increments from 1 (at 30 ns) to 28 (at 300 ns), confirming 28 cycles without any position change
- `progress_bounded` drops to 0 at 300 ns when `timer` reaches 28

### Error Classification

**Category**: Bug in the Original Design (**dut_bug**)

The transition function produces an out-of-bounds result (27) from a valid position (26), creating a permanent sink. The design should ensure that every valid position [0..26] maps to another valid position [0..26] under the transition function, or alternatively, the initialization logic should exclude start positions that would lead to immediate dead ends.
