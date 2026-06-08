# Counterexample Analysis Report: `bakery.mutual_exclusion_critical_section`

## 1. Verification Environment

- **Top module**: `bakery` (from `good_bakery.scala`)
- **Design**: A 3-process Lamport-style bakery algorithm implementation for mutual exclusion, implemented in Chisel with `FvUtils` formal verification.
- **State machine**: Each of the 3 processes (0, 1, 2) runs through states L1–L11. The entry protocol (L1–L9) acquires a ticket and checks against other processes. The critical section spans L10 and L11.
- **Key components**:
  - `ticket(i)`: whether process i holds a ticket
  - `choosing(i)`: whether process i is in the ticket-acquisition phase
  - `pc(i)`: program counter for process i (state in the bakery algorithm)
  - `j(i)`: the index of the process being checked against in the waiting loop
  - `defer(i)`: saved ticket vector snapshot (captures which processes had tickets when process i took its number)
  - `selReg`: which process is currently selected to advance
  - `io.pause`: external pause signal that gates critical section entry/exit

## 2. Violated Assertion

- **Assertion name**: `mutual_exclusion_critical_section` (from waveform filename `bakery.mutual_exclusion_critical_section.fst`)
- **Source file**: `good_bakery.scala`
- **Location**: Lines 192–197

```scala
// --- SAFETY: Mutual Exclusion ---
// The fundamental correctness property: no two processes may
// simultaneously occupy the critical section (L10 or L11).
assertMutex(Seq(
  io.pc(0) === Loc.L10 || io.pc(0) === Loc.L11,
  io.pc(1) === Loc.L10 || io.pc(1) === Loc.L11,
  io.pc(2) === Loc.L10 || io.pc(2) === Loc.L11
), "mutual_exclusion_critical_section")
```

- **Natural language**: At any time, at most one of the three processes may be in the critical section (states L10 or L11).

## 3. Waveform Information

- **Waveform File**: `verilog/extra_bench/bakery_good_bakery/bakery.mutual_exclusion_critical_section.fst`
- **Failure Time**: 370 ns (cycle 37)
- **Critical Time Points**:

| Time | pc_0 | pc_2 | ticket_0 | ticket_2 | j_0 | mutex |
|------|------|------|----------|----------|-----|-------|
| 280  | L6   | L10  | 1        | 1        | 1   | 1     |
| 290  | L6   | L11  | 1        | **0**    | 1   | 1     |
| 330  | L7   | L11  | 1        | **0**    | 2   | 1     |
| 340  | L8   | L11  | 1        | 0        | 2   | 1     |
| 350  | L5   | L11  | 1        | 0        | **3**| 1     |
| 360  | L9   | L11  | 1        | 0        | 3   | 1     |
| 370  | **L10**| L11 | 1       | 0        | 3   | **0** |

## 4. Root Cause Analysis

### Buggy Code Location

**File**: `good_bakery.scala`, lines 124–131 (the `Loc.L10` state handler)

```scala
is(Loc.L10) {
  when(selReg === selUInt) {
    ticket(selUInt) := false.B  // ← BUG: ticket cleared too early!
    // Clear defer bits for all processes
    defer(0) := clearBit(defer(0), selUInt)
    defer(1) := clearBit(defer(1), selUInt)
    defer(2) := clearBit(defer(2), selUInt)
    pc(selUInt) := Loc.L11      // process then transitions to L11
  }
}
```

### Description of the Bug

The **ticket is cleared at L10**, but the critical section (as defined by the assertion) includes **both L10 and L11**. This creates a window of vulnerability:

1. **Process 2** enters the critical section at time 280 (L10) and clears its ticket `ticket(2) := false.B` while transitioning to L11.
2. **Process 2** remains in the critical section at L11 from time 290 onward, but its ticket is now **false**.
3. **Process 0**, in the waiting loop at time 330 (L7 with j=2), checks whether it must wait for Process 2. The condition at L7 is:

```scala
when(ticket(k) && (defSelK || (!defKSel && (k < selUInt)))) {
  pc(selUInt) := Loc.L7  // wait
}.otherwise {
  pc(selUInt) := Loc.L8  // proceed
}
```

Since `ticket(2) = false` (cleared at L10), the entire condition evaluates to **false**, and process 0 **incorrectly proceeds** past the check.

4. **Process 0** then completes the remaining checks (j_0 = 3 now exceeds HIPROC = 2, so it bypasses L6/L7 and goes directly from L5 to L9), enters L10 at time 370, while **Process 2 is still in L11**.

This violates mutual exclusion: both processes 0 and 2 are simultaneously in the critical section (L10 and L11 respectively).

### Evidence from Waveform

- **Time 290**: `ticket_2` drops from 1 to 0 — this is when Process 2 executes L10 and clears its ticket.
- **Time 290–370**: `pc_2` stays at L11 (still in critical section) while `ticket_2` remains 0.
- **Time 330**: `pc_0 = L7`, `j_0 = 2`, `ticket_2 = 0`. The L7 gate check finds `ticket(2) = false` and incorrectly allows Process 0 to proceed.
- **Time 370**: `pc_0 = L10` and `pc_2 = L11` simultaneously → `mutual_exclusion_critical_section = 0` (assertion failure).

### Why This Causes the Assertion to Fail

The bakery algorithm's mutual exclusion guarantee relies on the ticket acting as a **lock indicator**: a process in the critical section should still have its ticket set so that other processes check against it. By clearing the ticket at L10 (before L11), the design creates a window where a process that has **entered** the critical section appears to have **left** it (no ticket), allowing another process to also enter.

### Root Cause Category: **Design Bug (dut_bug)**

This is a genuine bug in the design logic: the ticket should be cleared only when the process **fully exits** the critical section (at L11 when transitioning back to L1), not at L10 when it first enters.

### Proposed Fix

Move the `ticket(selUInt) := false.B` assignment from `Loc.L10` to `Loc.L11`, i.e., clear the ticket only when the process leaves the critical section entirely:

```scala
is(Loc.L10) {
  when(selReg === selUInt) {
    // Clear defer bits for all processes
    defer(0) := clearBit(defer(0), selUInt)
    defer(1) := clearBit(defer(1), selUInt)
    defer(2) := clearBit(defer(2), selUInt)
    pc(selUInt) := Loc.L11
  }
}
is(Loc.L11) {
  when(selReg === selUInt) {
    ticket(selUInt) := false.B  // ← MOVE HERE: clear ticket when exiting CS
    when(io.pause) {
      pc(selUInt) := Loc.L11
    }.otherwise {
      pc(selUInt) := Loc.L1
    }
  }
}
```
