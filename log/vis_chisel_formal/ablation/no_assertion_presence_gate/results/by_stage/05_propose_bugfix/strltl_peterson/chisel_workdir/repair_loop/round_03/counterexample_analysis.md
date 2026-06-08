# Counterexample Analysis Report: Peterson Liveness Violation

## 1. Verification Environment

- **Top Module**: `Peterson` (defined in `mppLTLM1.scala`, line 129)
- **Assertion Module**: `Peterson` extends `Module with Formal`
- **Key Components**:
  - **Peterson**: Implements a multi-process Peterson mutual exclusion protocol with 8 processes (0-7)
  - **Buechi**: A Büchi automaton module tracking liveness properties (instantiated as `buechi` in Peterson)
  - **ResetCounter**: Resets the system for a fixed number of cycles before enabling formal verification
- **Connections**:
  - `buechi` receives `pc0L6`, `pc1L6`, `pc2L6`, `pc1L0`, `pc2L0`, `interested0is1` from Peterson
  - `buechi` outputs `fair0`, `fair1`, `fair2`, `fair3`, `scc` back to Peterson
- **Design Under Test**: A 8-process Peterson mutual exclusion algorithm with processes 0-7. Process 7 is designated as HIPROC (high priority). State machine has locations L0-L7 (L6 = critical section).

## 2. Violated Assertion

- **Full Assertion Name**: `liveness_p0_L4_to_L6`
- **Code Snippet** (line 291 of `mppLTLM1.scala`):
  ```scala
  astRelaxedLiveness(self === 0.U && pc(0) === Loc.L4, pc(0) === Loc.L6, 30, "liveness_p0_L4_to_L6")
  ```
- **Natural Language Description**: If process 0 is selected (`self === 0`) and is at location L4 (trying to enter the critical section via the `j == self` check), it must reach L6 (critical section) within 30 cycles.
- **File Location**: `mppLTLM1.scala`, line 291

## 3. Waveform Information

- **Full Waveform Path**: `verilog/extra_bench/strltl_peterson/Peterson.liveness_p0_L4_to_L6.fst`
- **Time Range**: 0 ns → 360 ns (36 cycles at 10 ns per cycle)
- **Key Time Points**:

| Time (ns) | pc_0 | Event |
|-----------|------|-------|
| 0 | L0 (000) | Initial state, io_pause=0 |
| 10 | L1 (001) | Process 0 enters protocol, interested_0=1, io_pause=1 |
| 20 | L2 (010) | |
| 30 | L3 (011) | turn becomes 111 (0x7/HIPROC) |
| 40 | L4 (100) | j_0=001 (self+1), enters L5 next |
| 50 | L5 (101) | k=j_0=001, interested(1)=0, not blocked → goes to L4 |
| 60 | L4 (100) | j_0=001 !== 000(self), so goes to L5 again |
| 70 | L5 (101) | Same as before, back to L4 |
| 80-350 | L4 ↔ L5 | **Infinite oscillation** between L4 and L5 for the full 36 cycles |

- **Critical Signal Values (at time 350 ns, last cycle)**:
  - `pc_0` = L5 (101)
  - `j_0` = 001
  - `k` = 001
  - `interested_0` = 1
  - `interested_1` = 0
  - `turn` = 111 (0x7)
  - `io_pause` = 1 (stays high after time 10)

- **Buechi State**: `01011` (= 11 = States.n32) — never changes from initial state, meaning the Büchi automaton was designed to detect pc(0) reaching L6 but never observes it.

## 4. Root Cause Analysis

### Buggy Code Location

- **File**: `mppLTLM1.scala`
- **Lines**: 237-244
- **Function/Module**: `class Peterson`, state machine `switch(pc(self))`, case `is(Loc.L5)`

### Bug Description

The bug is in the **L5 state handler** (lines 237-244). The Peterson algorithm's L5 state is the busy-wait loop where a process checks if it is blocked by another process. When the process is **not** blocked (i.e., the condition `interested(k) && (turn === k)` is false), it should proceed directly to **L6 (critical section)**. However, the code erroneously sends it back to **L4**.

```scala
is(Loc.L5) {
  k := j(self)
  when(interested(k) && (turn === k)) {
    pc(self) := Loc.L5   // Stay in L5 (busy-wait) - CORRECT
  }.otherwise {
    pc(self) := Loc.L4   // BUG: should be Loc.L6 (proceed to critical section)
  }
}
```

### Evidence from Waveform

The trace shows an infinite cycle between L4 and L5:

1. **At L4** (lines 230-236): The code checks `j(self) === self`:
   ```scala
   is(Loc.L4) {
     when(j(self) === self) {
       pc(self) := Loc.L6
     }.otherwise {
       pc(self) := Loc.L5
     }
   }
   ```
   - self=0, j(0)=1 (set at L3: `j(self) := self + 1.U` for non-HIPROC)
   - `1 === 0` is false → goes to L5 (this is part of the tournament logic)

2. **At L5** (lines 237-244):
   - `k := j(0)` = 1
   - `interested(1)` = 0, so `interested(k) && (turn === k)` is `0 && (7 === 1)` = false
   - Since not blocked, the `.otherwise` branch sends the process back to **L4** instead of **L6**

3. **Infinite Loop**: L4 → L5 → L4 → L5 → ... for the entire 36-cycle trace, never reaching L6.

### Why the Assertion Fails

The assertion `astRelaxedLiveness(self === 0.U && pc(0) === Loc.L4, pc(0) === Loc.L6, 30, ...)` requires that when process 0 enters L4, it must reach L6 within 30 cycles. Due to the wrong transition at L5 (going back to L4 instead of going to L6), the process is trapped in an infinite L4↔L5 loop, never reaching the critical section. This is a **genuine DUT bug** (category 1).

### Fix

Change line 242 in `mppLTLM1.scala`:
```scala
// Before (buggy):
pc(self) := Loc.L4

// After (fix):
pc(self) := Loc.L6
```

This ensures that when a process at L5 is not blocked by another process, it proceeds directly into the critical section (L6) rather than bouncing back to L4 for another unnecessary check.
