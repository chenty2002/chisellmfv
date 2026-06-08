# Counterexample Analysis: `interested_cleared_at_L5_p0`

## 1. Verification Environment

- **Top Module**: `peterson` (in package `llmverify`)
- **Key Components**:
  - `pc`: Vec(2, Loc()) — program counters for two processes (0 and 1)
  - `interested`: Vec(2, Bool()) — interest flags for each process
  - `turn`: Bool() — turn indicator for tie-breaking
  - `self`: Bool() — currently scheduled process ID (driven by `io.select`)
  - `io.pause`: Bool() — pause input to stall process transitions
- **Description**: This is a Chisel implementation of Peterson's mutual exclusion algorithm. Each process goes through states L0→L1→L2→L3→L4→L5. L4 is the critical section. The L5 state is the cleanup state where the process should clear its interest flag and return to L0.

## 2. Violated Assertion

- **Full Assertion Name**: `interested_cleared_at_L5_p0` (from waveform filename: `peterson.interested_cleared_at_L5_p0.fst`)
- **Code Snippet** (peterson.scala, line 97):
  ```scala
  assertImplies(pc(0) === Loc.L5, !interested(0), "interested_cleared_at_L5_p0")
  ```
- **Property**: If process 0's program counter is at L5, then process 0's interested flag must be false (already cleared).
- **File Location**: `peterson.scala`, line 97

## 3. Waveform Information

- **Waveform File**: `verilog/extra_bench/peterson/peterson.interested_cleared_at_L5_p0.fst`
- **Time Range**: 0 ns → 60 ns (6 cycles at 10 ns/cycle)
- **Failure Point**: **Time = 50 ns** (cycle 5)

**Critical Signal Values at Failure Time (50 ns):**

| Signal | Value | Meaning |
|--------|-------|---------|
| `peterson.pc_0 [2:0]` | `101` (L5) | Process 0 is at L5 |
| `peterson.interested_0` | `1` | Process 0's interested flag is HIGH |
| `peterson.self` | `1` | Self just switched to 1 |
| `peterson.io_self` | `1` | Output showing self=1 |
| `peterson.io_pause` | `1` | Pause is active |
| `peterson.io_select` | `1` | Select is 1 |
| `peterson.interested_cleared_at_L5_p0` | `0` | Assertion FAILED |

## 4. Root Cause Analysis

### Buggy Code Location

**File**: `peterson.scala`, lines 58–72, specifically the `is(Loc.L4)` case at line 71–73.

### Description of the Bug

The bug is a **one-cycle delay between entering L5 and clearing the interested flag**.

The assertion states: whenever `pc(0) === L5`, then `interested(0)` must be false. However, the design enters L5 via the switch statement's `L4` case (lines 71–73) without clearing `interested`:

```scala
is(Loc.L4) {
    when(!io.pause) {
        pc(selfIdx) := Loc.L5     // <-- transitions to L5, but interested is NOT cleared
    }
}
```

The L5 cleanup code (lines 78–84) is unconditional and runs on every cycle, but it only takes effect **one cycle after** `pc` becomes L5:

```scala
when(pc(0) === Loc.L5) {
    interested(0) := false.B   // <-- fires in the cycle AFTER pc enters L5
    pc(0) := Loc.L0
}
```

This creates a one-cycle window where `pc(0) = L5` but `interested(0)` is still `true`, violating the assertion.

### Waveform Evidence: Full Sequence Trace

| Time (ns) | pc(0) | interested(0) | self | io_select | io_pause | turn | Event |
|-----------|-------|---------------|------|-----------|----------|------|-------|
| 0 | L0 | 0 | 0 | 0 | 0 | 0 | Initial state |
| 10 | L1 | 0 | 0 | 0 | 1 | 0 | pc(0) advances from L0→L1 (io_pause=0 at time 0) |
| 20 | L2 | **1** | 0 | 0 | 0 | 0 | L1: interested(0) set to true, pc→L2 |
| 30 | L3 | 1 | 0 | 0 | 0 | **1** | L2: turn set to ~0=1, pc→L3 |
| 40 | **L4** | 1 | 0 | **1** | 0 | 1 | L3: !interested(1)=true, pc→L4 (enters CS) |
| **50** | **L5** | **1 (BUG!)** | **1** | 1 | **1** | 1 | L4: !io_pause=true → pc→L5, **interested NOT cleared**. self updated to 1. Assertion FAILS. |
| 60 | L0 | 0 | 1 | 1 | 1 | 1 | L5 cleanup fires (one cycle late): interested cleared, pc→L0 |

### Why the Fix in the L5 Cleanup Is Insufficient

The comment in the code says:
> "The original design only handled L5 for the process selected by selfIdx, which caused interested_cleared_at_L5 violations when self switched while a process was at L5."

The fix made the L5 cleanup unconditional (checking both pc(0) and pc(1) explicitly). However, this still has a one-cycle gap: when pc enters L5 via the L4→L5 transition, the cleanup code doesn't fire until the *next* cycle because at the start of the cycle, pc was still at L4.

### Classification: **dut_bug**

This is a genuine bug in the design. The `interested` flag is cleared one cycle after entering L5 instead of being cleared concurrently with the L5 entry.

### Proposed Fix

Add `interested(selfIdx) := false.B` in the L4 case when transitioning to L5, so that interested is cleared in the **same cycle** pc enters L5:

```scala
is(Loc.L4) {
    when(!io.pause) {
        interested(selfIdx) := false.B   // <-- ADD THIS
        pc(selfIdx) := Loc.L5
    }
}
```

This ensures that when pc transitions from L4 to L5, interested is cleared in the same cycle, making the assertion `pc(0) === L5 ⇒ !interested(0)` hold immediately.
