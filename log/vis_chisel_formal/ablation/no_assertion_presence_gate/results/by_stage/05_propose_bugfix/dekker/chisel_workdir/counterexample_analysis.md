# Counterexample Analysis Report: dekker.process0_progress_l1_to_cs

## 1. Verification Environment

- **Top Module**: `dekker` (Chisel with `Formal` trait)
- **Source File**: `dekker.scala` (162 lines)
- **Waveform File**: `verilog/extra_bench/dekker/dekker.process0_progress_l1_to_cs.fst`
- **Key Components**:
  - `pc(0)`, `pc(1)` — 3-bit program counters for processes 0 and 1 (6 states: L0–L6)
  - `c(0)`, `c(1)` — boolean flags indicating process interest in the critical section
  - `turn` — 1-bit turn variable for tie-breaking
  - `self` — register holding the currently selected process ID (0 or 1)
  - `io_select` — input selecting which process runs in the next cycle
  - `io_pause` — input that stalls processes at L0 and L5
  - `selectStableCnt`, `pauseStableCnt` — fairness counters to constrain the environment

## 2. Violated Assertion

- **Assertion Name** (from waveform filename): `process0_progress_l1_to_cs`
- **Full Code Snippet** (line 134 of `dekker.scala`):
  ```scala
  astRelaxedLiveness(pc(0) === L1, pc(0) === L5, 50, "process0_progress_l1_to_cs")
  ```
- **Natural Language Description**: When process 0 reaches location L1 (expresses interest in entering the critical section), it must reach location L5 (the critical section) within at most 50 clock cycles.
- **File Location**: `dekker.scala`, line 134

## 3. Waveform Information

- **Full Waveform Path**: `verilog/extra_bench/dekker/dekker.process0_progress_l1_to_cs.fst`
- **Time Range**: 0 ns to 530 ns (53 cycles at 10 ns/cycle)
- **Failure Time**: 520 ns (cycle 52)
- **Key Signal Values at Failure Point (t=520 ns)**:
  - `pc_0 [2:0]` = `010` (L2)
  - `pc_1 [2:0]` = `100` (L4)
  - `c_0` = `0` (process 0 has set c(0)=false, expressing interest)
  - `c_1` = `1` (process 1 has released its resource)
  - `turn` = `0` (unchanged throughout the simulation)
  - `self` = `0` (just transitioned from 1→0 at time 520)
  - `process0_progress_l1_to_cs` = `0` (assertion fails)

### Critical Timeline

| Time | Event |
|------|-------|
| 0 | Reset. Both processes at L0. turn=0, c(0)=1, c(1)=1 |
| 10 | **pc(0)→L1** (process 0 expresses interest). self=1 at this time. **Assertion timer starts.** |
| 20 | pc(1)→L1 |
| 30 | pc(1)→L2, c(1)→0 (process 1 expresses interest first) |
| 40 | **pc(1)→L5** (process 1 enters critical section). self=0 |
| 50 | pc(0)→L2, c(0)→0 (process 0 also expresses interest) |
| 50–310 | **Process 0 stuck oscillating between L2 and L3** (because c(1)=0 — process 1 in CS) |
| 40–320 | **Process 1 stuck in L5** for 28 cycles (waiting for !io.pause AND self=1) |
| 320 | pc(1)→L6 (process 1 exits CS) |
| 330 | pc(1)→L0, c(1)→1 (process 1 releases resource). io_pause→1 |
| 340 | io_pause→0, io_select→1 |
| 350 | pc(0)→L2. **self=1** (process 1 runs next) |
| 360 | pc(1)→L1 (process 1 re-enters) |
| 370 | pc(1)→L2, **c(1)→0** again! (process 1 re-asserts interest, blocking process 0) |
| 370–430 | Process 0 again stuck at L2/L3 (c(1)=0) |
| 440 | pc(1)→L4, **c(1)→1** (process 1 at L4, waiting for turn) |
| 480 | pc(0)→L2. self=1 again (480–520, process 1 runs) |
| 520 | **Assertion fails**: process 0 has been at L1 for 51 cycles without reaching L5 |

## 4. Root Cause Analysis

### Classification: **Bug in the Original Design (DUT Bug)**

### Bug Location

**File**: `dekker.scala`, lines 36–87
**The Bug**: The state machine is written as:
```scala
switch(pc(self)) { ... }
```
This evaluates **only the process selected by `self`** — i.e., only one process's state machine runs per clock cycle. The other process is completely frozen.

### Description of the Bug

Dekker's algorithm requires **two independent, concurrently executing processes** that communicate through shared memory (`c(0)`, `c(1)`, `turn`). Each process independently evaluates its own program counter, sets its own `c` flag, checks the other process's `c` flag, and proceeds through its own state machine.

However, this Chisel implementation serializes both processes into a single state machine that only runs **one** process per cycle:
- When `self=0`, only process 0's state transitions are evaluated; process 1 is frozen
- When `self=1`, only process 1's state transitions are evaluated; process 0 is frozen

This creates three critical problems:

#### Problem 1: Artificial Process Coupling
A process cannot make progress unless `self` selects it. For example, process 0 at L2 (where it checks `c(~self)=c(1)`) can only evaluate that condition when `self=0`. If `self=1` for several consecutive cycles (allowed for up to 3 cycles by the fairness constraint), process 0 is unnecessarily delayed.

#### Problem 2: Lost Windows of Opportunity
After process 1 exits L5 at time 320 and sets `c(1)=1` at time 330, the window where process 0 can enter L5 is:
- Process 0 needs to be at L2 while `self=0` and `c(1)=1`
- pc(0) reaches L2 at time 350, but `self=1` at that time (process 1 runs)
- By the time `self=0` at time 370, process 1 has already set `c(1)=0` again

#### Problem 3: Delayed Critical Section Exit
Process 1 takes 28 cycles (time 40–320) to exit L5, not because of pause alone, but because it must wait for `self=1` AND `!io.pause` to be simultaneously true. The `switch(pc(self))` means a process in L5 can only advance when it's selected.

### Evidence from Waveform

| Time | Signal | Value | Significance |
|------|--------|-------|-------------|
| 10 | `pc_0` | L1 | Process 0 expresses interest, assertion timer starts |
| 40 | `pc_1` | L5 | Process 1 enters CS, c(1)=0 blocks process 0 |
| 50–310 | `pc_0` | L2↔L3 oscillation | Process 0 stuck; c(1)=0 prevents L5 entry |
| 320 | `pc_1` | L6 | Process 1 exits CS (took 28 cycles!) |
| 330 | `c_1` | 1 | Resource becomes available |
| 350 | `pc_0` | L2 | Process 0 poised to enter L5... but `self=1` |
| 370 | `c_1` | 0 | Process 1 reasserts interest, re-blocking process 0 |
| 440 | `c_1` | 1 | Process 1 at L4 waiting for turn |
| 480 | `pc_0` | L2 | Process 0 at L2, but `self=1` until 520 |
| 520 | assertion | 0 | Timer expired (51 cycles since L1 entry) |

### Why This Is Not an Assertion Error or Setup Error

1. **Not an assertion error**: The 50-cycle bound would be sufficient if both processes ran independently. With true concurrency, each process would complete L1→L5 in about 6–8 cycles (L1→L2→[wait]→L5). The 50-cycle bound allows for contention. The assertion itself is correctly specified.

2. **Not a setup error**: The fairness constraints on `io_select` (must change every 4 cycles) and `io_pause` (must not stay high for ≥5 cycles) are reasonable environment constraints. The bug manifests even within these constraints.

### Recommended Fix

The state machine logic must evaluate **both processes independently**, not just the one selected by `self`. The fix is to run the switch on both `pc(0)` and `pc(1)` each cycle:

```scala
// Evaluate both processes independently
for (i <- 0 until 2) {
  switch(pc(i)) {
    is(L0) { when(!io.pause) { pc(i) := L1 } }
    is(L1) { c(i) := false.B; pc(i) := L2 }
    is(L2) { when(c(1-i) === true.B) { pc(i) := L5 } .otherwise { pc(i) := L3 } }
    is(L3) { when(turn === i.U) { pc(i) := L2 } .otherwise { c(i) := true.B; pc(i) := L4 } }
    is(L4) { when(turn === i.U) { c(i) := false.B; pc(i) := L2 } }
    is(L5) { when(!io.pause) { pc(i) := L6 } }
    is(L6) { c(i) := true.B; turn := (1-i).U; pc(i) := L0 }
  }
}
```

This ensures both processes progress independently, synchronized only through the shared `c` and `turn` registers — which is the correct implementation of Dekker's mutual exclusion algorithm.
