# Counterexample Analysis: `liveness_p0_no_conflict` Violation

## 1. Verification Environment

### Top Module
- **Module**: `peterson` (from `peterson.scala`, line 12)
- **Waveform File**: `verilog/extra_bench/peterson/peterson.liveness_p0_no_conflict.fst`
- **Duration**: 170 ns (17 clock cycles at 10 ns period)

### Design Structure
The `peterson` module implements Peterson's mutual exclusion algorithm using a **single-threaded state machine** where only one process executes at a time, determined by an external `io_select` signal:

| Component | Type | Description |
|---|---|---|
| `pc(0)`, `pc(1)` | `Reg(Vec(2, Loc()))` | Program counters for each process |
| `interested(0/1)` | `Reg(Vec(2, Bool()))` | Interest flags per process |
| `turn` | `Reg(UInt(1.W))` | Turn indicator for tie-breaking |
| `self` | `Reg(UInt(1.W))` | Selects which process runs (`self := io.select`) |

### Inputs
| Input | Type | Description |
|---|---|---|
| `io_select` | `Bool()` | Selects which process runs (0→process 0, 1→process 1) |
| `io_pause` | `Bool()` | Pauses process at L0 transition (L0→L1) |

### Key Design Decision
The design uses a **single-threaded execution model**: `switch(pc(selfIdx))` only updates the program counter of the currently selected process (`selfIdx`). The other process is stalled regardless of whether its entry condition is met. This is the root cause of the liveness violation.

---

## 2. Violated Assertion

### Assertion Name
`liveness_p0_no_conflict` (from waveform filename `peterson.liveness_p0_no_conflict.fst`)

### Code Location
**File**: `peterson.scala`, lines 111-115

### Code Snippet
```scala
astRelaxedLiveness(
    pc(0) === Loc.L3 && pc(1) =/= Loc.L4,   // trigger: process 0 waiting, no conflict in CS
    pc(0) === Loc.L4,                         // target: process 0 enters critical section
    12,                                        // bound: within 12 cycles
    "liveness_p0_no_conflict"
)
```

### Natural Language Description
When process 0 is waiting at the entry protocol (L3, the `while` loop in Peterson) and process 1 is **not** in the critical section (L4), process 0 must enter the critical section within 12 clock cycles.

---

## 3. Waveform Information

### Full Path
```
verilog/extra_bench/peterson/peterson.liveness_p0_no_conflict.fst
```

### Key Time Points (nanoseconds)

| Time | pc_0 | pc_1 | self | turn | interested | io_select | io_pause | pending | timer | Event |
|------|------|------|------|------|-----------|-----------|----------|---------|-------|-------|
| 0 | L0(000) | L0(000) | 0 | 0 | [0,0] | 0 | 0 | 0 | 0 | Reset |
| 10 | L1(001) | L0(000) | 0 | 0 | [0,0] | 0 | 0 | 0 | 0 | Process 0: L0→L1 |
| 20 | L2(010) | L0(000) | 0 | 0 | [1,0] | 1 | 0 | 0 | 0 | Process 0: L1→L2, interested[0]=1 |
| **30** | **L3(011)** | L0(000) | 1 | 1 | [1,0] | 1 | 1 | 0 | 0 | Process 0: L2→L3, turn=1. **Assertion triggered!** |
| 40 | L3(011) | L0(000) | 1 | 1 | [1,0] | 1 | 1 | 1 | 0 | pending=1, timer=0 |
| 50 | L3(011) | L1(001) | 1 | 1 | [1,0] | 0 | 0 | 1 | 1 | Process 1: L0→L1 |
| 60 | L3(011) | L2(010) | 0 | 1 | [1,1] | 0 | 0 | 1 | 2 | Process 0: self=0 but condition fails |
| 70 | L3(011) | L2(010) | 0 | 1 | [1,1] | 1 | 0 | 1 | 3 | self=0, condition: `!int[1](1)\|turn(1)==0` = **false** |
| 80 | L3(011) | L3(011) | 1 | 0 | [1,1] | 1 | 1 | 1 | 4 | Process 1: L2→L3, turn=0 |
| 90 | L3(011) | L3(011) | 1 | 0 | [1,1] | 1 | 1 | 1 | 5 | Both at L3, self=1 → process 0 stalled |
| ... | L3(011) | L3(011) | 1 | 0 | [1,1] | 1 | 1 | 1 | 6-11 | Timer increments: 100-150 |
| 150 | L3(011) | L3(011) | 1 | 0 | [1,1] | 0 | 1 | 1 | 11 | io_select=0 → next self=0 |
| **160** | **L3(011)** | **L3(011)** | **0** | **0** | [1,1] | 0 | 1 | 1 | **12** | self=0, condition: `!int[1](1)\|turn(0)==0` = **true!** But timer+1=13≥13 → **ASSERTION FAILS** |

### Failure Point
- **Time**: 160 ns
- **Signal Values**:
  - `peterson.pc_0`: L3 (011) — stuck at waiting state
  - `peterson.pc_1`: L3 (011) — also waiting
  - `peterson.self`: 0 — process 0 finally selected
  - `peterson.turn`: 0 — condition `!interested(1) || turn==0` = `0 || 1` = **true**
  - `peterson.pending`: 1
  - `peterson.timer`: 12 (1100)
  - `peterson.liveness_p0_no_conflict`: 0 (failing)

---

## 4. Root Cause Analysis

### Classification: **DUT Bug** (design architecture flaw)

### Buggy Code Location
**File**: `peterson.scala`, lines 43-69  
**Function**: State machine in the `peterson` class

### Root Cause: Single-Threaded State Machine Architecture

The fundamental problem is that the design uses a **single-threaded execution model** where only the currently selected process can advance:

```scala
// Line 37-38
val selfIdx = self
val otherIdx = ~self

// Line 43-69
switch(pc(selfIdx)) {   // <--- ONLY the selected process runs
    // ...
    is(Loc.L3) {
        when(!interested(otherIdx) || (turn === self)) {
            pc(selfIdx) := Loc.L4
        }
    }
    // ...
}
```

When `self = 1` (process 1 selected), the switch statement processes `pc(1)`. Process 0's program counter is frozen regardless of whether its entry condition (`!interested(1) || turn == 0`) is satisfied. Only when `self = 0` can process 0 advance.

### Why the Assertion Fails

The liveness counterexample proceeds as follows:

1. **Time 30**: Process 0 enters L3 (waiting state). The liveness trigger `pc(0)==L3 && pc(1)!=L4` fires. **However, `self` switches to 1 at this same cycle**, immediately pausing process 0.

2. **Time 30-70**: Process 0 is stalled because `self=1`. Process 1 runs and eventually reaches L2-L3. During this interval, the liveness timer counts from 0 to 3.

3. **Time 70**: `self=0` (process 0 can run again). But now `interested(1)=1` and `turn=1`, so `!interested(1) || turn==0 = 0 || 0 = 0`. The mutual exclusion condition **fails** because process 1 set `turn=1` earlier and is also waiting.

4. **Time 80**: `self` switches back to 1 at time 80, before process 0 can make another attempt. Process 1 runs and sets `turn=0` at time 80-90.

5. **Time 80-150**: Both processes are stuck at L3, `self=1`, timer keeps incrementing (4 through 11).

6. **Time 160**: `self=0`. Now `turn=0` and `interested(1)=1`, so `!interested(1) || turn==0 = 0 || 1 = 1`. The condition is **finally satisfied!** But the timer has reached 12, and `timer+1 = 13` which violates the assertion bound of `< 13`.

7. At **time 170**, process 0 would enter L4 (one cycle too late).

### Evidence from Waveform

The trace of `peterson.self` and the entry condition shows the problem clearly:

| Time | self | Condition for pc(0):L3→L4 | Result |
|------|------|---------------------------|--------|
| 30 | 1 | N/A (process 0 paused) | Stalled |
| 40 | 1 | N/A | Stalled |
| 50 | 1 | N/A | Stalled |
| 60 | 0→1 | `!int[1](0)\|turn(1)==0` = 1\|0 = **1** | **Would pass**, but self just switched to 1 |
| 70 | 0 | `!int[1](1)\|turn(1)==0` = 0\|0 = **0** | Fails |
| 80 | 1 | N/A (process 0 paused) | Stalled |
| 90 | 1 | N/A | Stalled |
| ... | 1 | N/A | Stalled |
| 150 | 1 | N/A | Stalled |
| 160 | 0 | `!int[1](1)\|turn(0)==0` = 0\|1 = **1** | **Passes, but too late!** |

### Why This is a DUT Bug

The liveness assertion is a **correct and reasonable property** for a Peterson's algorithm implementation: when a process is waiting for entry and no other process is in the critical section, it should eventually enter within a bounded time. The standard Peterson algorithm guarantees this.

However, this DUT's architectural decision to serialize both processes through a single state machine with external scheduling (`self := io_select`) breaks the fundamental concurrency assumption of Peterson's algorithm. In a proper implementation, each process should independently evaluate its entry condition and advance.

### Possible Fixes

1. **Architectural fix**: Implement separate state machines for each process so both can independently evaluate their conditions and advance, as the Peterson algorithm requires.

2. **Fair scheduler**: Add a fairness constraint to `io_select` that ensures both processes get regular execution turns (e.g., round-robin toggling).

3. **Relax the assertion bound**: Increase the bound from 12 to a larger value to account for scheduling delays, though this is a workaround, not a fix.
