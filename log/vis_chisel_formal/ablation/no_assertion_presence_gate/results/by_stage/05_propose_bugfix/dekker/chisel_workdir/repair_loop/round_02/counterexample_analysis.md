# Counterexample Analysis Report: Process 0 L1-to-L5 Bounded Liveness

## 1. Verification Environment

### Top Module
- **Module Name**: `dekker`
- **File**: `dekker.scala` (148 lines)
- **Type**: Chisel module with `Formal` verification mixin

### Structure and Key Components
- **Registers**:
  - `c` (Vec(2)): Flags signaling resource desire (initialized to true)
  - `turn` (1-bit): Turn indicator (initialized to 0)
  - `self` (1-bit): Currently scheduled process (follows io.select)
  - `pc` (Vec(2) of 3-bit): Program counters for each process
- **IO Pins**:
  - `io.select`: Which process to execute (0 or 1)
  - `io.pause`: Pause execution at L0 or L5
  - `io.c0`, `io.c1`, `io.turn`, `io.self`, `io.pc0`, `io.pc1`: Outputs for verification

### Connections
- `self` register is updated with `io.select` on each clock cycle
- The state machine evaluates `pc(self)` — i.e., only the currently selected process's PC advances
- The six states are: L0 (idle), L1 (want resource), L2 (check other flag), L3 (check turn), L4 (wait for turn), L5 (critical section), L6 (release)

### Fairness Constraints
```scala
assume(selectStableCnt < 4.U)  // io_select must change at least every 4 cycles
```
No fairness constraint is imposed on `io.pause`.

### Design Under Test
The module implements Dekker's mutual exclusion algorithm for two processes on a single-threaded processor. Each clock cycle, one process (selected by `self`) executes one state transition. The environment controls process scheduling via `io.select` and can pause processes via `io.pause`.

---

## 2. Violated Assertion

### Assertion Name (from waveform filename)
`dekker.process0_progress_l1_to_cs.fst`

### Code Snippet (dekker.scala, lines 134-138)
```scala
// Bounded Liveness: Process 0 progress
// When process 0 expresses interest (reaches L1), it should eventually
// enter the critical section (L5) within a reasonable bound.
// Bound of 50 cycles accounts for turn-taking and de-scheduling overhead.
astRelaxedLiveness(pc(0) === L1, pc(0) === L5, 50, "process0_progress_l1_to_cs")
```

### Property Description
Whenever process 0's program counter reaches L1 (signaling interest in the critical section), it must reach L5 (the critical section) within 50 clock cycles. This is a bounded-liveness property that verifies the algorithm's progress guarantee.

### File Location
- **File**: `dekker.scala`
- **Line**: 138
- **Module**: `dekker`

---

## 3. Waveform Information

### Waveform File
- **Full Path**: `verilog/extra_bench/dekker/dekker.process0_progress_l1_to_cs.fst`
- **Duration**: 53 cycles (0–530 ns)
- **Clock Period**: 10 ns (clock edges at times 10, 20, 30, ...)
- **Reset ends at**: time 0

### Key Time Points (all times in nanoseconds)

| Time | Cycle | Event |
|------|-------|-------|
| 0 | 0 | Reset. pc(0)=L0, pc(1)=L0. turn=0. c(0)=1, c(1)=1. self=0. |
| 10 | 1 | **pc(0) becomes L1** → Assertion triggers (50-cycle countdown starts) |
| 50 | 5 | pc(0) becomes L2; c(0) becomes false |
| 60 | 6 | pc(0) becomes L3 |
| 80 | 8 | pc(0) becomes L2 (L2-L3 loop begins) |
| 90 | 9 | pc(0) becomes L3 |
| ... | ... | pc(0) continues L2-L3 alternation |
| 40–220 | 4–22 | Process 1 in CS (L5), held by io_pause=1 |
| 350 | 35 | pc(1)=L6 (exits CS) |
| 370 | 37 | pc(1)=L0; c(1)=true |
| 390 | 39 | pc(1)=L1 |
| 400 | 40 | pc(1)=L2 |
| 430 | 43 | pc(1)=L3 |
| 470 | 47 | pc(1)=L4; c(1)=false→true again |
| 490 | 49 | pc(0)=L2 (last time at L2 before bound expires) |
| **510** | **51** | **50-cycle bound expires** (10 + 50×10 = 510) |
| 520 | 52 | self=0, pc(0)=L2, **c(1)=1** (conditions align for L2→L5) |
| 530 | 53 | pc(0) still L2 (trace ends) |

### Critical Signal Values at Failure Point

At time 520 (52 cycles, after bound expired):
| Signal | Value |
|--------|-------|
| `pc(0)` | `010` (L2) |
| `pc(1)` | `100` (L4) |
| `c(0)` | `0` |
| `c(1)` | `1` (true — process 1 is waiting at L4) |
| `self` | `0` (process 0 selected) |
| `turn` | `0` (unchanged since reset) |
| `io.pause` | `1` |

At this point, all conditions for the L2→L5 transition are met (self=0, pc(0)=L2, c(1)=true), but the 50-cycle bound has already expired 10 ns earlier at time 510.

---

## 4. Root Cause Analysis

### Classification: **Setup Error** — Insufficient environmental fairness constraints

### Location
- **File**: `dekker.scala`
- **Line**: 132 (select fairness constraint) and line 138 (assertion bound)

### Root Cause

The assertion fails because process 0 requires **52 cycles** to go from L1 to L5 under the environmental stimulus, but the assertion allows only **50 cycles**. The extra 2 cycles are caused by the combined effect of two environmental constraints:

**Factor 1: Rapid io_select switching (`selectStableCnt < 4.U`, line 132)**

The fairness constraint `assume(selectStableCnt < 4.U)` allows `io.select` (and thus `self`) to change every 1–3 cycles. In the counterexample trace, `self` oscillates frequently:

| Time Range | self Value | Duration |
|------------|-----------|----------|
| 480–490 | 0 | 1 cycle |
| 490–520 | 1 | 3 cycles |
| 520–... | 0 | ... |

This rapid switching prevents either process from getting enough consecutive cycles to make sequential progress. Process 0 in the L2-L3 loop must wait for:
- Process 1 to reach L4 (c(1)=true) — this takes until cycle 47 (time 470)
- Then for `self` to be 0 at L2 — this happens at cycle 52 (time 520), but it's past the deadline

**Factor 2: Unconstrained io_pause (no fairness assumption)**

`io.pause` is held high for 18 consecutive cycles (time 40–220), keeping process 1 in the critical section (L5). This delays process 1's exit from the CS, which in turn delays process 1 reaching L4 (where it sets c(1)=true, allowing process 0 to enter CS). There is no fairness constraint on `io.pause`.

**Factor 3: turn remains at 0**

The `turn` register is initialized to 0 and never changes throughout the 53-cycle trace. Since process 0 never reaches L6 (where `turn := ~self` would flip turn to 1), process 1 remains stuck at L4 waiting for `turn === self` to become true. This is a consequence of process 0 never entering CS, not a separate bug.

### Why the Design is Correct

The Dekker algorithm state machine at lines 44–85 correctly implements the mutual exclusion protocol:
- L2→L3 when c(~self)=false (other process wants resource)
- L3→L2 when turn===self (busy-wait loop)
- L2→L5 when c(~self)=true (other process doesn't want resource or is waiting)

All state transitions are logically correct. The design is **not buggy** — given enough cycles, process 0 would reach L5 at time 530 (cycle 53).

### Why This Is a Setup Error, Not a Design Bug

1. **Design is correct**: The state machine faithfully implements Dekker's algorithm
2. **Assertion is valid**: Checking bounded liveness from L1 to L5 is a meaningful property
3. **But the environment is too adversarial**: The `selectStableCnt < 4.U` constraint combined with arbitrary `io.pause` creates a worst-case scenario that needs more than 50 cycles

The root cause is that the **fairness constraints are insufficient** to bound the worst-case timing to 50 cycles. Possible fixes include:
1. Increase the bound (e.g., to 100 cycles): `astRelaxedLiveness(pc(0) === L1, pc(0) === L5, 100, "process0_progress_l1_to_cs")`
2. Add a fairness constraint on `io.pause` (e.g., limit consecutive pause cycles)
3. Relax the select switching constraint to allow more consecutive cycles per process (e.g., `selectStableCnt < 10.U`)

### Event Timeline (Evidence from Waveform)

```
Time   Event
 0     Reset. pc(0)=L0, pc(1)=L0, turn=0, c(0)=1, c(1)=1
10     pc(0)=L1 ← Assertion triggers (50-cycle countdown: deadline=10+500=510)
30     c(1)=0 (process 1 wants resource)
40     pc(1)=L5 (process 1 in CS)
40-220 io_pause=1 holds process 1 in CS for 18 cycles
50     pc(0)=L2, c(0)=0 (process 0 wants resource)
50-490 pc(0) alternates L2↔L3 (busy-wait loop)
       └─ L2→L3 because c(1)=0 (process 1 in CS or contending)
       └─ L3→L2 because turn(0)===self(0) when process 0 is selected
350    pc(1)=L6 (process 1 exits CS)
370    pc(1)=L0, c(1)=1
400    pc(1)=L2
430    pc(1)=L3
470    pc(1)=L4, c(1)=1 (process 1 waiting, c(1)=true)
       └─ Now process 0 at L2 could enter CS when self=0
490    pc(0)=L2, self→1 (process 0 at L2 but self switches to 1)

510    50-cycle bound EXPIRES (10 + 50×10 = 510)

520    self=0, pc(0)=L2, c(1)=1 → conditions OK but BOUND EXPIRED
530    Trace ends, pc(0) still L2 (would reach L5 at this clock edge)
```

### Summary

The assertion fails because the environment is allowed to create a worst-case scenario where process 0 requires 52 cycles to reach the critical section, but the assertion only allows 50 cycles. The rapid io_select switching (every 1–3 cycles) and the unconstrained io_pause (18 consecutive cycles in CS) combine to exceed the bound by just 2 cycles. This is a **setup error** — the fairness constraints should be strengthened or the bound increased to account for the actual worst-case timing of the Dekker algorithm under the given scheduling model.
