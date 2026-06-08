# Counterexample Analysis Report: `cubeAbs.liveness_visit_unvisited`

## 1. Verification Environment

- **Top module**: `cubeAbs` (defined in `cubeAbs.scala` line 6)
- **Structure**: A 3×3×3 cube exploration module that starts at an initial position and moves to adjacent positions by flipping the LSB and substituting the upper bits from `io.start`.
  - **posReg** (5-bit register): Current position on the cube [0–26]
  - **visited** (27× 1-bit register array): Tracks which positions have been visited
  - **next** (combinational wire): `Cat(io.start[4:1], ~posReg[0])` -- computes the next position candidate
  - **dest** (combinational wire): `Mux(next < 27, next, posReg)` -- valid destination, clamped to posReg if out of range
  - **initDone** (1-bit register): Goes high after the first cycle to mark initialization complete
- **Key logic**: During normal operation (`initDone == 1`), when `!visited(dest)`, the module moves to `dest` and marks it as visited.

## 2. Violated Assertion

- **Assertion name** (from waveform filename): `liveness_visit_unvisited`
- **Source location**: `cubeAbs.scala`, line 86
- **Code snippet**:
  ```scala
  astRelaxedLiveness(initDone && !visited(dest), visited(dest), 5, "liveness_visit_unvisited")
  ```
- **Natural language description**: This relaxed liveness property asserts that whenever `initDone` is true AND the current computed destination `dest` has not been visited yet, then `visited(dest)` must become true within the next 5 clock cycles.

## 3. Waveform Information

- **Waveform file**: `verilog/extra_bench/cube_cubeAbs/cubeAbs.liveness_visit_unvisited.fst`
- **Time range**: 0 ns – 80 ns (8 cycles at 10 ns/cycle)
- **Assertion failure point**: 70 ns (cycle 7)

### Key signal values at each cycle

| Time (ns) | posReg | dest | io_start | initDone | pending | timer | Assertion |
|-----------|--------|------|----------|----------|---------|-------|-----------|
| 0         | 0      | 23   | 22       | 0        | 0       | 0     | 1 (pass)  |
| 10        | 22     | 23   | 22       | 1        | 0       | 0     | 1 (pass)  |
| 20        | 23     | 20   | 20       | 1        | 1       | 0     | 1 (pass)  |
| 30        | 20     | 19   | 18       | 1        | 1       | 1     | 1 (pass)  |
| 40        | 19     | 26   | 27       | 1        | 1       | 2     | 1 (pass)  |
| 50        | 26     | 3    | 3        | 1        | 1       | 3     | 1 (pass)  |
| 60        | 3      | 10   | 10       | 1        | 1       | 4     | 1 (pass)  |
| **70**    | **10** | **11** | **10**   | **1**    | **1**   | **5** | **0 (FAIL)** |

## 4. Root Cause Analysis

### Error classification: **incorrect assertion (assertion_error)**

The assertion is flawed because it uses `dest`, a **combinational wire** that changes every cycle, as an argument to both the trigger condition and the eventual condition.

### Detailed explanation

The assertion is:
```scala
astRelaxedLiveness(initDone && !visited(dest), visited(dest), 5, "liveness_visit_unvisited")
```

The generated Verilog checker implements this as follows (from `cubeAbs.sv`):

```verilog
wire nextPending = ~_GEN[dest] & (pending | initDone & ~_GEN[dest]);
wire _nextTimer_T_1 = pending & ~_GEN[dest];
// When nextPending & _nextTimer_T_1: timer increments
// Else: timer resets to 0
// Assertion fails when timer reaches 5 (overflow check)
```

The sequence of events:

1. **Time 20** (cycle 2): `initDone=1`, `dest=20`, `visited_20=0`. The trigger fires: `nextPending=1`, `pending` latches to 1.

2. **Time 30** (cycle 3): `posReg=20`, `dest=19`, `visited_19=0`. The design correctly marks `visited_20=1`. However, the checker evaluates `~_GEN[dest]` = `~_GEN[19]` = `~visited_19` = **1** (19 is unvisited). So `_nextTimer_T_1` remains 1, and the timer increments to 1.

3. **Times 30–70**: At each subsequent cycle, `dest` changes to a new unvisited position (19 → 26 → 3 → 10 → 11). Since the checker evaluates `~_GEN[dest]` with the **current** value of `dest`, which is always a new unvisited position, `_nextTimer_T_1` stays 1 continuously and the timer keeps incrementing: 1 → 2 → 3 → 4 → 5.

4. **Time 70**: Timer reaches 5 (binary `101`), `_nextTimer_T_2[2:1]` = `(5+1)[2:1]` = `110[2:1]` = `11` = 3. The assertion checks `... != 3`, which fails.

### Why the design is actually correct

The design itself works as intended: at each cycle, when `dest` is unvisited, the design moves `posReg` to `dest` and marks `visited(dest)=1` in the following cycle. The visited positions correctly increase: 22→23→20→19→26→3→10. Each new position gets visited in the very next cycle after becoming `dest`.

### The root cause bug

The problem is that the assertion evaluates `visited(dest)` with the **current** combinational value of `dest`, not the value of `dest` at the time the trigger fired. Since `dest` changes every cycle (to the next unvisited position), the checker's consequent condition `visited(dest)` is always checking a **different** position than the one that triggered it. The checker therefore never observes the consequent being satisfied, and the timer monotonically increments until it overflows 5 cycles later.

**Fix suggestion**: The liveness assertion should use a latched/sampled value of `dest` at the trigger time, or the property should be rewritten to check that the count of visited positions steadily increases, or that `posReg` never gets stuck on an already-visited position. For example:
```scala
// Option 1: Check that posReg always moves to a new unvisited position
fvAssert(!initDone || !visited(posReg), "always_move_to_unvisited")

// Option 2: Check that visited count increases monotonically
// (requires a visited-count register)
```
