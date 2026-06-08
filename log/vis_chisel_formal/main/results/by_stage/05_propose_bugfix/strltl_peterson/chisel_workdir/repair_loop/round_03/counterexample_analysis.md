# Counterexample Analysis Report: Peterson.p0_active_interest_progress

## 1. Verification Environment

### Top Module
- **Module**: `Peterson` (in `mppLTLM1.scala`)
- **Module Hierarchy**: `Peterson` instantiates `Buechi` for temporal monitoring

### Key Components
- **8 Processes** (indices 0–7), each with a program counter `pc(i)` and an `interested(i)` flag
- **Turn variable**: shared, controls which competing process has priority
- **Self register**: the currently scheduled process (driven by `io_select`)
- **J register**: per-process pointer for iterating through competitors
- **K register**: temporary for reading `j(self)` during L5 evaluation
- **Buechi automaton**: temporal monitor for fairness/SCC properties

### Connections
- `io_select` (3-bit) → `self` (clamped to range 0–7)
- `io_pause` → gates entry (L0→L1) and CS exit (L6→L7) for low-numbered processes
- `buechi.io.*` ← wires mapping `pc(0)`, `pc(1)`, `pc(2)`, `interested(0)` states

## 2. Violated Assertion

### Assertion Name
`p0_active_interest_progress` (from `Peterson.p0_active_interest_progress.fst`)

### Code Snippet (lines 344–349 of `mppLTLM1.scala`)
```scala
astRelaxedLiveness(
    interested(0) && pc(0) =/= Loc.L0 && pc(0) =/= Loc.L7 && !io.pause,
    pc(0) === Loc.L7,
    80,
    "p0_active_interest_progress"
)
```

### Property Description
When process 0 is interested (interested(0)=true), active in the protocol (not at L0 idle or L7 done), and the system is not paused (!io.pause), then process 0 must reach L7 (critical section exit) within 80 clock cycles.

### File Location
- **File**: `chisel/extra_bench/strltl_peterson/mppLTLM1.scala`
- **Lines**: 344–349

## 3. Waveform Information

### Waveform File
`verilog/extra_bench/strltl_peterson/Peterson.p0_active_interest_progress.fst`

### Time Range
0 ns – 830 ns (83 clock cycles at 10 ns period)

### Key Time Points and Observations

| Time (ns) | Event |
|-----------|-------|
| 10 | Antecedent fires: pc(0)=L1, interested(0)=1, !io_pause. 80-cycle countdown starts. |
| 10–60 | Process 0: L1→L2→L3 (needs self=0 at right times) |
| 140 | Process 0 enters L4 (loop checking competitors) |
| 150–350 | Process 0 iterates through competitors at L4↔L5, advancing j from 1→2→3→4→5→6→7 |
| 370 | Process 0 at L5, j=6, io_select=000, io_pause=0 — **last self=0 opportunity** |
| 380–390 | self=0 but io_pause=1 — process 0 at L4 (j=7), trapped by pause at L4→L5 transition |
| 390 | Process 0 at L4, j=7. **Needs 2 more self=0 cycles** to reach L6. |
| 390–830 | self alternates between 6 and 7 only; **process 0 never scheduled again** |
| 810 | 80-cycle bound from antecedent expires; assertion fails |

### Critical Signal Values at Failure Point (t=830 ns)
| Signal | Value |
|--------|-------|
| `pc(0)` | L4 (100) |
| `interested(0)` | 1 |
| `self` | 6 (110) |
| `io_select` | 6 (110) |
| `io_pause` | 1 |
| `j(0)` | 7 (111) |
| `turn` | 6 (110) |
| `interested(7)` | 1 |

## 4. Root Cause Analysis

### Buggy Location
**File**: `mppLTLM1.scala`  
**Line**: 254  
**Module**: `Peterson`, inside the `pc(self)` switch statement, case `Loc.L6`

### Buggy Code
```scala
is(Loc.L6) {
    when(io.pause && (self === 0.U || self === 1.U || self === 2.U)) {
        pc(self) := Loc.L6    // ← BUG: traps processes 0,1,2 in CS during pause
    }.otherwise {
        pc(self) := Loc.L7
    }
}
```

### Description of the Bug
The L6 state is the critical section (CS). When `io_pause` is asserted, the code **specially traps processes 0, 1, and 2** in the critical section, preventing them from exiting to L7. The condition `self === 0.U || self === 1.U || self === 2.U` creates an unreachable situation for process 0:

1. Process 0 can only execute the L6→L7 transition when `self=0` (it is the running process).
2. When `self=0` at L6, the condition `self === 0.U` is **always true**.
3. If `io_pause` is also true, the combined condition `io_pause && (self === 0.U)` holds, freezing process 0 at L6 **forever**.

This means that any time `io_pause` is asserted while process 0 is in the critical section (or would reach it), the process deadlocks and can never satisfy the progress assertion.

### Evidence from Waveform

**Timeline showing the deadlock scenario:**

1. **t=370**: Process 0 is at L5 (101) with j=6 (110), io_select=000, io_pause=0. One more L5→L4→L5→L4 loop would advance j to 7.

2. **t=380**: self becomes 0 (000), but io_pause also becomes 1. Process 0 is at L5, j=6.  
   - L5: k=6, interested(6)=0 → false → j=7, L4.
   - pc(0)→L4 at t=390.

3. **t=390**: Process 0 at L4, j=7. self=6, so process 0 is **not scheduled**. pc(0) stays L4.

4. **t=390–830 (44 cycles)**: self alternates between 6 and 7. Process 0 **never runs again**. It needs only 2 more self=0 cycles to reach L6, and 1 more at L6 to reach L7.

5. **Even if process 0 reached L6 during pause**: The condition `io.pause && (self === 0.U)` would hold, and process 0 would be **trapped at L6 forever**, never reaching L7.

### Why This Causes the Assertion to Fail
The `astRelaxedLiveness` assertion requires process 0 to reach L7 within 80 cycles of the antecedent firing. The antecedent fires at t=10 with process 0 at L1, interested(0)=1, and !io_pause.

Process 0 progresses through the protocol, but due to:
- The scheduling pattern (io_select does not return to process 0 frequently enough)
- **The L6 pause trap** (which would block process 0 even if it reached L6 during a paused period)

Process 0 can never complete the protocol to reach L7 within the 80-cycle bound.

### Root Cause Category
**DUT Bug** — The condition `when(io.pause && (self === 0.U || self === 1.U || self === 2.U))` at line 254 incorrectly prevents processes 0, 1, and 2 from exiting the critical section during pause. The special case for low-numbered processes is a design error that violates the progress property.

### Suggested Fix
Remove the special-case trapping of processes 0,1,2 at L6 during pause. The corrected code should be:

```scala
is(Loc.L6) {
    when(io.pause) {
        pc(self) := Loc.L6    // All processes stay in CS during pause
    }.otherwise {
        pc(self) := Loc.L7
    }
}
```

Or, if pause should not block any process from exiting CS:
```scala
is(Loc.L6) {
    pc(self) := Loc.L7        // Always exit CS; pause at L0 prevents new entries
}
```

The pause mechanism at L0 (line 203) already prevents idle processes from entering the protocol during pause. There is no need to also trap processes already in the critical section.
