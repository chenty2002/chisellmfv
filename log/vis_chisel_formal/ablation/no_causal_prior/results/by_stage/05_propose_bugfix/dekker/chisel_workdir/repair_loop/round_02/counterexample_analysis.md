# Counterexample Analysis: `turn_flip_on_exit` Assertion Failure

## 1. Verification Environment

- **Top Module**: `dekker`
- **Benchmark**: `dekker`
- **Waveform File**: `verilog/extra_bench/dekker/dekker.turn_flip_on_exit.fst`
- **Design**: Dekker's mutual exclusion algorithm for two processes (process 0 and process 1). Each process has a program counter (`pc`), a flag (`c`), and a shared `turn` variable. The `self` register selects which process is currently active (driven by the `io_select` input). The design implements a 7-state state machine (L0–L6) for each process.

## 2. Violated Assertion

- **Assertion Name**: `turn_flip_on_exit` (from waveform filename)
- **File**: `dekker.scala`, lines 113–114

**Code Snippet (line 113–114)**:
```scala
val was_in_l6 = RegNext(pc(self) === L6, false.B)
AssertProperty(!was_in_l6 || (turn === ~self), None, None, Some("turn_flip_on_exit"))
```

**Natural Language Description**:
If on the previous cycle, the active process (selected by `self`) was in location L6 (the exit-from-critical-section state), then on the current cycle the `turn` must equal the bitwise complement of `self` (`~self`). In other words, when a process exits the critical section via L6, it must flip the `turn` to indicate the other process's turn.

## 3. Waveform Information

- **Full Waveform Path**: `verilog/extra_bench/dekker/dekker.turn_flip_on_exit.fst`
- **Time Range**: 0 ns → 60 ns (6 cycles at 10 ns/cycle)
- **Failure Time**: t = 50 ns

### Critical Signal Values at Failure Point (t = 50 ns)

| Signal     | Value at t=40 | Value at t=50 |
|------------|--------------|--------------|
| `self`     | 1            | **0**        |
| `turn`     | 0            | 0            |
| `was_in_l6`| 0            | **1**        |
| `pc_0`     | L0 (000)     | L0 (000)     |
| `pc_1`     | L6 (110)     | L0 (000)     |
| `c_0`      | 1            | 1            |
| `c_1`      | 0            | **1**        |
| `io_select`| **0**        | 0            |
| `io_pause` | 0            | 0            |

### Execution Trace

| Cycle | Time (ns) | Description |
|-------|-----------|-------------|
| 0     | 0         | Reset: `self=1`, `turn=0`, both `pc`s at L0, `io_select=1` |
| 1     | 10        | Process 1 advances from L0→L1 (`pc_1=001`), `io_pause=1` |
| 2     | 20        | Process 1 advances L1→L2 (`pc_1=010`), `c_1` set to `false` (0), `io_pause=0` |
| 3     | 30        | Process 1 in L2: `c(0)=true` so enters CS → L5 (`pc_1=101`) |
| 4     | 40        | Process 1 in L5: exits to L6 (`pc_1=110`), `io_select` changes to **0** |
| 5     | **50**    | **ASSERTION FAILURE**: `self=0`, `turn=0`, `was_in_l6=1` |

### Assertion Evaluation at t=50

```
!was_in_l6 || (turn === ~self)
===  !1     || (0    === ~0)
===  false  || (0    === 1)
===  false
```

## 4. Root Cause Analysis

### Category: **Incorrect Assertion** (assertion error)

The assertion `!was_in_l6 || (turn === ~self)` has a **timing/race-condition bug** when `self` changes in the same cycle that process 1 exits L6.

### Root Cause Explanation

The state machine code for L6 (lines 83–86) does:
```scala
is(L6) {
  c(self) := true.B
  turn := ~self
  pc(self) := L0
}
```

And the self-update (line 91) does:
```scala
self := selfNext   // where selfNext := io.select
```

In Chisel, all register assignments (`:=`) are non-blocking—they all take effect simultaneously at the next clock edge.

**The causal sequence:**

1. **At t=40** (start of cycle 4): `self=1`, `turn=0`, `io_select=0`. The active process is process 1, and `pc(1)=L6`.

2. **During cycle 4** (t=40→50): The L6 handler computes:
   - `turn := ~self` = `~1` = `0`
   - `selfNext := io_select = 0`
   
3. **At t=50** (clock edge): Both `turn` and `self` update simultaneously:
   - `turn` becomes `0` (computed from old `self=1`)
   - `self` becomes `0` (computed from `io_select=0`)

4. **At t=50** (same cycle, post-update): The assertion checks `turn === ~self`:
   - `turn` = `0`
   - `~self` = `~0` = `1`
   - `0 === 1` → **false**

### Why This Is an Assertion Bug, Not a Design Bug

The design correctly implements Dekker's algorithm:
- When process 1 exits the critical section, it sets `turn := ~self` using the **old** value of `self` (which is `1`), giving `turn = 0`.
- This correctly assigns the turn to the other process (process 0).
- The behavior is correct regardless of whether `self` changes.

The problem is the assertion's **timing assumption**:
- The assertion checks `turn === ~self` in the same cycle that both `turn` and `self` are updated.
- When `self` also changes in this cycle (due to `io_select` changing), the `~self` term evaluates to the **new** `self` value, not the **old** one that was used to compute `turn`.
- The correct check should compare `turn` against `~old_self` (the value of `self` at the start of the cycle, when L6 was evaluated).

### How to Fix

The assertion should use a delayed version of `self` to avoid the race condition:

**Option 1**: Compare `turn` with `~RegNext(self)` to get the old `self` value:
```scala
AssertProperty(!was_in_l6 || (turn === ~RegNext(self)), None, None, Some("turn_flip_on_exit"))
```

**Option 2**: Delay the entire property:
```scala
AssertProperty(!was_in_l6 || (RegNext(turn) === ~RegNext(self)), None, None, Some("turn_flip_on_exit"))
```

Either fix ensures the comparison uses the same `self` value that was used when computing `turn := ~self` in the L6 handler.

### Buggy Code Location

- **File**: `dekker.scala`
- **Lines**: 113–114
- **Function**: Assertion `turn_flip_on_exit`
- **Type**: Incorrect assertion (assertion does not account for simultaneous `self` update)
