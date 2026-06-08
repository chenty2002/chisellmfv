# Counterexample Analysis Report: `cubeAbs.progress_bounded`

## 1. Verification Environment

- **Top Module**: `cubeAbs` (from `cubeAbs.scala`)
- **Module Type**: Chisel Module with `Formal` mixin
- **Key Components**:
  - `posReg (5-bit register)`: Current position in the cube (0–26)
  - `visited (27 x Bool register array)`: Tracks which positions have been visited
  - `next (5-bit wire)`: Computed next potential position
  - `dest (5-bit wire)`: Clamped next position (0–26)
  - `timer (5-bit register)`: Counts cycles since last position change (for liveness)
- **Design Purpose**: A cube-traversal machine that explores positions 0–26 of a 3×3×3 cube, starting from `io.start`.
- **Verification Environment**: Formal verification with free inputs (`io.start` and `io.dir` can change arbitrarily every cycle)

## 2. Violated Assertion

- **Full Assertion Name**: `cubeAbs.progress_bounded` (from waveform filename: `cubeAbs.progress_bounded.fst`)
- **Assertion Type**: Liveness timer assertion (`assertLivenessTimer`)
- **Code Snippet** (lines 78–82 of `cubeAbs.scala`):

```scala
assertLivenessTimer(
  cond = true.B,
  reset = (posReg =/= RegNext(posReg)) || !initDone,
  n = 28,
  msg = "progress_bounded"
)
```

- **Natural Language Description**: *"Once initialization is complete, the position register `posReg` must change at least once every 28 consecutive cycles (i.e., the exploration must make progress — either visiting a new position or at least moving). If `posReg` stays unchanged for 28 cycles, the assertion fires."*
- **File Location**: `cubeAbs.scala`, lines 78–82

## 3. Waveform Information

- **Waveform File**: `verilog/extra_bench/cube_cubeAbs/cubeAbs.progress_bounded.fst`
- **Time Range**: 0 ns → 300 ns (30 cycles at 10 ns/cycle)
- **Key Time Points**:

| Time (ns) | Cycle | Event |
|-----------|-------|-------|
| 0 | 0 | Reset deasserted. `io_start = 27`, `initDone = 0`, `posReg = 0`, `timer = 0`, `_nextTimer_T_1 = 1` (reset active due to `!initDone`), `visited_0 = 0` |
| 10 | 1 | `initDone → 1`. Init completes: `posReg = 0`, `visited(0) = 1`. `_nextTimer_T_1 → 0` (reset becomes inactive). Timer starts counting. |
| 20–280 | 2–28 | `posReg` stays `0`. `dest` stays `0`. `visited_0` stays `1`, all other `visited_i` stay `0`. Timer increments: 1, 2, ..., 27. |
| 290 | 29 | Timer reaches 28 (0x11100 = 28). **Assertion `progress_bounded` fires (value → 0).** |

- **Critical Signal Values at Failure Point (time = 290 ns)**:
  - `cubeAbs.posReg [4:0]` = `00000` (position stuck at 0)
  - `cubeAbs.dest [4:0]` = `00000` (destination always 0)
  - `cubeAbs.next [4:0]` = `11011` (27 — out of valid range, so dest clamped to posReg)
  - `cubeAbs.io_start [4:0]` = `11010` (26 — free input changing every cycle)
  - `cubeAbs.timer [4:0]` = `11100` (28 — has counted up uninterrupted)
  - `cubeAbs.initDone` = `1`
  - `cubeAbs.visited_0` = `1`, all others = `0`

## 4. Root Cause Analysis

### Bug Location

- **File**: `cubeAbs.scala`
- **Line**: 39
- **Buggy Statement**: `next := Cat(io.start(4, 1), ~posReg(0))`

### Description of the Bug

The `next` signal uses `io.start` (the free input port) in its computation instead of `posReg` (the registered current position):

```scala
next := Cat(io.start(4, 1), ~posReg(0))   // BUG: uses io.start
```

The correct computation should derive the next position from the **current position**:

```scala
next := Cat(posReg(4, 1), ~posReg(0))      // CORRECT: uses posReg
```

### Mechanism of Failure

The buggy computation `Cat(io.start(4, 1), ~posReg(0))` works as follows:

1. **Initialization** (cycle 0–1): `io_start = 27 (11011)`. Since 27 > 26, `initPos = 0`. `posReg` is set to 0, `visited(0) = true`, and `initDone` becomes true.

2. **Normal operation** (cycles 2–29): The `otherwise` branch executes. Every cycle:
   - `next = Cat(io_start(4,1), ~posReg(0))` — uses the **current** `io_start` value
   - Since `io_start` is a **free formal input** that can change arbitrarily each cycle, its upper 4 bits (`io_start(4,1)`) vary across the range 0–15
   - `posReg(0) = 0`, so `~posReg(0) = 1`
   - Therefore `next = Cat(io_start(4,1), 1)`, which ranges from 1 to 31
   - Any time `io_start(4,1) >= 13` (i.e., `io_start >= 26`), we get `next >= 27`
   - `dest = Mux(next < 27.U, next, posReg)` — since `next >= 27`, `dest = posReg = 0`
   - `!visited(dest)` → `!visited(0)` → `!1` → `false`
   - The `when (!visited(dest))` block is **never entered**, so `posReg` **never changes from 0**

3. **Timer behavior**: The liveness timer reset condition `(posReg =/= RegNext(posReg)) || !initDone` is only true at cycle 0 (when `!initDone`). After initialization, `posReg` never changes, so the reset is permanently false. The timer counts uninterrupted from 0 to 28 over 29 cycles.

4. **Assertion failure**: At time 290 ns (cycle 29), `timer = 28`, exceeding the bound `n = 28`, and the `progress_bounded` assertion fires.

### Evidence from Waveform

- `dest [4:0]` = `00000` (never changes from 0 — see `waveform_trace_signal` showing 1 change at time 0)
- `posReg [4:0]` = `00000` (never changes from 0 — see `waveform_trace_signal` showing 1 change at time 0)
- `next [4:0]` takes values `29 (11101)`, `27 (11011)`, `31 (11111)` etc., all ≥ 27 (because `io_start(4,1)` produces large values)
- `visited_0` = `1` at time 10, all other `visited_i` = `0` for all time points queried (10 and 290)
- `_nextTimer_T_1` (the posReg-change detection) transitions from `1` to `0` at time 10 and stays `0` forever

### Error Classification

**Bug type**: `dut_bug` — the design has a genuine logic error in the `next` computation.

### Suggested Fix

Change line 39 of `cubeAbs.scala` from:
```scala
next := Cat(io.start(4, 1), ~posReg(0))
```
to:
```scala
next := Cat(posReg(4, 1), ~posReg(0))
```

This makes the next-position computation depend on the **current registered position** rather than the free formal input `io.start`, allowing the cube traversal to actually progress through positions 0–26 instead of getting stuck at position 0.
