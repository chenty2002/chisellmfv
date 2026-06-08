# Counterexample Analysis Report: Dekker's Algorithm

## 1. Verification Environment

- **Top Module**: `dekker` (from `dekker.scala`)
- **Structure**: The design implements Dekker's mutual exclusion algorithm for two processes using a shared state machine. A `self` register selects which process (0 or 1) executes in each cycle, controlled by the external `io_select` input. The `io_pause` input controls idling behavior.
- **Key Components**:
  - `c(0)`, `c(1)`: "interested" flags (inverted logic: true = not interested, false = wants CS)
  - `turn`: turn indicator (0 or 1)
  - `self`: currently executing process ID (captures `io_select`)
  - `pc(0)`, `pc(1)`: program counters for processes 0 and 1 (states L0–L6)
  - `io_select`: external input selecting which process to execute next
  - `io_pause`: external input causing processes to wait in L0 and L5
- **Verification Type**: Bounded formal verification with Chisel-FV (`astRelaxedLiveness`)

## 2. Violated Assertion

- **Assertion Name**: `process0_progress_l1_to_cs`
- **Waveform File**: `dekker.process0_progress_l1_to_cs.fst`
- **Code Snippet** (from `dekker.scala`, lines 121–122):

```scala
// Bounded Liveness: Process 0 progress
// When process 0 expresses interest (reaches L1), it should eventually
// enter the critical section (L5) within a reasonable bound.
astRelaxedLiveness(pc(0) === L1, pc(0) === L5, 50, "process0_progress_l1_to_cs")
```

- **Property Description**: Whenever process 0 reaches state L1 (indicating it wants to enter the critical section), it must reach state L5 (the critical section) within 50 clock cycles.
- **File Location**: `dekker.scala`, line 122

## 3. Waveform Information

- **Waveform File**: `verilog/extra_bench/dekker/dekker.process0_progress_l1_to_cs.fst`
- **Time Range**: 0 ns → 530 ns (53 cycles)
- **Key Time Points**:
  - **t=10 ns (cycle 1)**: pc(0) transitions from L0 (000) → L1 (001) — assertion trigger fires
  - **t=20 ns (cycle 2)**: pc(0) transitions from L1 (001) → L2 (010); `pending=1` (assertion monitoring starts)
  - **t=90 ns (cycle 9)**: pc(0) transitions from L2 (010) → L3 (011)
  - **t=120 ns (cycle 12)**: pc(0) transitions from L3 (011) → L2 (010) — goes back to spinning
  - **t=110 ns (cycle 11)**: pc(1) reaches L4 (100) and gets stuck
  - **t=520 ns (cycle 52)**: Assertion fails (timer exceeds bound of 50 cycles; `process0_progress_l1_to_cs` goes from 1→0)
- **Critical Signal Values at Failure (t=520 ns)**:
  - `pc_0 [2:0]` = 010 (L2) — Process 0 stuck at L2
  - `pc_1 [2:0]` = 100 (L4) — Process 1 stuck at L4
  - `c_0` = 0 (wants critical section)
  - `c_1` = 1 (does not want CS)
  - `turn` = 0
  - `self` = 1 (always selects process 1)
  - `io_select` = 1 (scheduler permanently selects process 1)
  - `io_pause` = 1
  - `pending` = 1 (assertion still triggered)
  - `timer [5:0]` = 110010 (50 — bound exhausted)

## 4. Root Cause Analysis

### Error Classification: **setup_error** (insufficient input fairness constraints)

### Description of the Bug

The counterexample demonstrates that the bounded liveness assertion fails because the environment (`io_select` input) can permanently select process 1 after a certain point, starving process 0 indefinitely.

### Detailed Execution Trace

**Phase 1 — Process 0 enters L1 (t=10 ns):**
- At rising edge t=10, `self=0`, `pc(0)=L0`. Since `io_pause=0`, the L0→L1 transition fires.
- `pc(0)` becomes L1 (001), triggering the assertion.

**Phase 2 — Process 0 enters L2 (t=20 ns):**
- At rising edge t=20, `self=0`, process 0 executes L1: `c(0)=false` (wants CS), `pc(0)=L2`.
- At the same edge, `self` captures `io_select=1`, so subsequent cycles execute process 1.

**Phase 3 — Process 1 progresses (t=70–110 ns):**
- Process 1 goes: L0→L1→L2→L3→L4
- At L3 (t=100), since `turn(0) != self(1)`, process 1 sets `c(1)=true` (doesn't want CS) and moves to L4.
- At L4 (t=110 onward), since `turn(0) != self(1)`, process 1 is stuck — there is no escape from L4 when `turn != self`.

**Phase 4 — Deadlock (t=120–520 ns):**
- After t=110, `io_select=1` permanently, so `self=1` always.
- Process 1 (selected) is at L4: `when(turn === self)` → `turn(0) != self(1)` → no state change.
- Process 0 (not selected) is frozen at L2.
- Process 0 at L2 would transition to L5 (critical section) if executed, because `c(1)=true` (process 1 doesn't want CS), but it never gets a turn.

**Why the assertion fails:**
Process 0 reaches L1 at t=10 and needs to reach L5 within 50 cycles (by t=510). However, process 0 is never scheduled again after t=110 (io_select stays at 1). Process 1 is stuck at L4 because `turn=0 != self=1`. This creates a deadlock where neither process can advance, and process 0 never reaches L5.

### Root Cause Detail

The root cause is that the input constraint on `io_select` does not guarantee the minimum fairness required for the liveness property. The `astRelaxedLiveness` assertion with a 50-cycle bound assumes that process 0 will get enough turns to progress from L1 to L5. However:

1. **The `self`-based scheduling model**: Only one process advances per cycle. If the scheduler (`io_select`) permanently selects process 1, process 0 cannot advance at all.

2. **The L4 deadlock**: When process 1 is at L4 with `turn != self`, it stays there indefinitely because `turn` can only change via L6, which requires process 0 to enter the critical section (L5) — but process 0 is never scheduled.

3. **No fairness guarantee**: There is no constraint that `io_select` must toggle or allow each process to run periodically.

### Potential Fixes

**Option A — Add fairness constraints on io_select (setup fix):**
Add environment constraints ensuring that `io_select` does not permanently select one process. For example, constrain `io_select` to change value at least every N cycles.

**Option B — Add timeout/escape in L4 (design fix):**
Modify the L4 state to allow yielding or re-checking more frequently, e.g., by adding a `when(!io.pause)` escape similar to L0:

```scala
is(L4) {
  when(turn === self) {
    c(self) := false.B
    pc(self) := L2
  }.elsewhen(!io.pause) {
    pc(self) := L5  // yield execution
  }
}
```

**Option C — Make each process an independent state machine (design fix):**
Instead of using a shared state machine with `self` to select which process runs, implement two independent state machines that execute in parallel, as would be the case in real hardware.

### Evidence Summary

| Signal | Value @ t=520 | Significance |
|--------|---------------|--------------|
| `pc_0 [2:0]` | 010 (L2) | Stuck waiting to enter CS |
| `pc_1 [2:0]` | 100 (L4) | Stuck waiting for turn to change |
| `self` | 1 | Only process 1 gets scheduled |
| `io_select` | 1 | Scheduler permanently selects process 1 |
| `turn` | 0 | Never updated (stuck at initial value) |
| `pending` | 1 | Trigger still active |
| `timer [5:0]` | 50 | Bound exhausted, assertion fires |
