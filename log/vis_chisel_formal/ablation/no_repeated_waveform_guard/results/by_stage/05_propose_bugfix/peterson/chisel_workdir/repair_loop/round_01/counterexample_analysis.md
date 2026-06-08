# Counterexample Analysis Report: peterson.interested_cleared_at_L5_p0

## 1. Verification Environment

- **Top Module**: `peterson` (from `peterson.scala`)
- **Module Structure**: A single-module Chisel design implementing Peterson's mutual-exclusion algorithm for two processes. The module contains:
  - **State machine**: A `switch` statement operating on `pc(selfIdx)`, where `selfIdx` is the currently selected process (`0` or `1`).
  - **Internal registers**: `pc` (program counter for each process), `interested` (interest flags for each process), `turn` (turn indicator), `self` (currently scheduled process index).
  - **Inputs**: `io_select` (selects which process runs next cycle), `io_pause` (pauses process transitions at L0 and L4).
  - **Outputs**: Exposes all internal state for verification.
- **Verification Tool**: JasperGold with Chisel formal verification library (`chiselFv`).

## 2. Violated Assertion

- **Assertion Name**: `interested_cleared_at_L5_p0`
- **Waveform File**: `peterson.interested_cleared_at_L5_p0.fst`
- **Source Code** (line 78, `peterson.scala`):
  ```scala
  assertImplies(pc(0) === Loc.L5, !interested(0), "interested_cleared_at_L5_p0")
  ```
- **Natural Language**: "When process 0's program counter is at location L5, process 0's `interested` flag must be cleared (set to `false`)."
- **File Location**: `peterson.scala`, line 78.

This property captures a key invariant of Peterson's algorithm: when a process exits the critical section and enters L5 (the cleanup state), it must clear its interest flag so that the other process can enter the critical section.

## 3. Waveform Information

- **Full Waveform Path**: `verilog/extra_bench/peterson/peterson.interested_cleared_at_L5_p0.fst`
- **Waveform Duration**: 60 ns (6 clock cycles)
- **Failure Time**: 50 ns (cycle 5)
- **Key Timeline**:

| Time (ns) | pc_0 | pc_1 | self | interested_0 | interested_1 | io_select | io_pause | turn |
|-----------|------|------|------|-------------|-------------|-----------|----------|------|
| 0         | L0   | L0   | 0    | 0           | 0           | 0         | 0        | 0    |
| 10        | L1   | L0   | 0    | 0           | 0           | 0         | 1        | 0    |
| 20        | L2   | L0   | 0    | 1           | 0           | 0         | 1        | 0    |
| 30        | L3   | L0   | 0    | 1           | 0           | 0         | 1        | 1    |
| 40        | L4   | L0   | 0    | 1           | 0           | 1         | 0        | 1    |
| **50**    | **L5** | **L0** | **1** | **1** (❌) | 0           | 1         | 1        | 1    |

## 4. Root Cause Analysis

### Bug Type: **DUT Bug** — The state machine design has a fundamental flaw in handling process scheduling.

### Buggy Code Location

**File**: `peterson.scala`, lines 53–76  
**Module**: `peterson` class  
**Root Cause**: The state machine operates on `pc(selfIdx)`, where `selfIdx = self`. When `self` switches from process 0 to process 1 mid-stream, the L5 cleanup handler for process 0 never executes.

### Description of the Bug

The design implements a **single shared state machine** that uses `selfIdx` (derived from the `self` register) to select which process's program counter to advance each cycle:

```scala
val selfIdx = self       // line 52
val otherIdx = ~self     // line 53

switch(pc(selfIdx)) {    // line 55 — only advances the selected process
  // ...
  is(Loc.L5) {           // line 72
    interested(selfIdx) := false.B   // line 73
    pc(selfIdx) := Loc.L0            // line 74
  }
}
```

The critical flaw is that **L5 cleanup (clearing `interested` and returning to L0) only happens for the process currently selected by `selfIdx`**. When `self` changes between cycles, the previously scheduled process may be left stuck at L5 with `interested` still set — a violation of the assertion.

### Step-by-Step Failure Trace

1. **Cycle 0→1 (time 0→10)**: With `self=0`, process 0 transitions from L0 to L1 (not paused).
2. **Cycle 1→2 (time 10→20)**: Process 0 at L1 sets `interested(0)=1` and moves to L2.
3. **Cycle 2→3 (time 20→30)**: Process 0 at L2 sets `turn = ~self = 1` and moves to L3.
4. **Cycle 3→4 (time 30→40)**: Process 0 at L3 checks the entry condition (`!interested(otherIdx)`, i.e., `!interested(1)=true`) and enters L4 (critical section). Also, `io_select` is asserted to 1.
5. **Cycle 4→5 (time 40→50)**: 
   - Process 0 at L4 transitions to L5 (cleanup) because `!io_pause` (pause was 0 at time 40).
   - **Crucially**, the `self` register captures `io_select=1` from time 40, so `self` becomes **1** at time 50.
   - Now `selfIdx=1`, meaning the state machine will operate on **pc(1)**, not **pc(0)**.
6. **Time 50 (failure point)**:
   - `pc_0 = 101 (L5)` — process 0 entered L5
   - `interested_0 = 1` — **NOT CLEARED** because the L5 handler never fired for process 0
   - `self = 1` — the state machine now processes process 1 (pc_1 = L0)
   - The assertion `pc(0) === L5 → !interested(0)` is **violated**: `interested_0` is still 1 when pc(0) is at L5.

### Why This Is a DUT Bug

The design incorrectly assumes that once a process starts executing through the state machine, it will complete its full cycle (L0→L1→L2→L3→L4→L5→L0) without interruption. However, the `io_select` input can change the `self` register mid-stream, causing the state machine to abandon the current process at L5 without performing the necessary cleanup (clearing `interested` and resetting `pc` to L0).

**Fundamental design issue**: The state machine should either:
- (a) Ensure both processes' state machines run independently (e.g., separate `switch` statements for each process), or
- (b) Ensure that when `self` changes, the previous process's L5 state (if applicable) is properly completed before switching, or
- (c) Not allow `self` to change when a process is at L5.

### Evidence from Waveform

| Signal | Time 40 | Time 50 | Explanation |
|--------|---------|---------|-------------|
| `pc_0` | 100 (L4) | 101 (L5) | Process 0 entered L5 |
| `self` | 0 | 1 | Scheduling switched to process 1 |
| `interested_0` | 1 | 1 | **Never cleared** — L5 handler didn't fire for p0 |
| `pc_1` | 000 (L0) | 000 (L0) | Process 1 is at L0, idle |
| `interested_cleared_at_L5_p0` | 1 | **0** (FAIL) | Assertion fires at time 50 |

The root cause is the single shared state machine design at lines 53–76 of `peterson.scala`. The L5 handler at lines 72–74 only executes for the process indexed by `selfIdx`, and since `self` changed from 0 to 1 in the same cycle process 0 entered L5, the cleanup never runs for process 0.
