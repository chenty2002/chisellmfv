# Counterexample Analysis Report: Peterson.interested0_eventually_cleared

## 1. Verification Environment

### Top Module
- **Name**: `Peterson` (package `llmverify`)
- **File**: `mppLTLM1.scala` (lines 131–347)
- **Key Parameters**: `SELMSB = 2`, `HIPROC = 7` (8 processes, indices 0–7)

### Key Components and Connections
1. **Peterson (DUT)**: Implements Peterson's mutual exclusion algorithm for 8 processes (0–7). Contains:
   - `interested[8]`: per-process interested flags (RegInit Vec of Bool)
   - `pc[8]`: per-process program counters (RegInit Vec of Loc enum)
   - `turn`: shared turn variable
   - `self`: currently scheduled process (driven by `io.select`)
   - `j[8]`: per-process loop counters
   - `k`: temporary storage for the "other" process index
2. **Buechi** (line 17–129): LTL monitor automaton that tracks system states for fairness/liveness properties.

### Ports
- `io.select` (UInt(3.W)): selects which process runs next (`self := io.select`)
- `io.pause` (Bool): when asserted, certain transitions are blocked
- Various output ports exposing internal state for verification

### Design Under Test
The Peterson module implements a token-based mutual exclusion algorithm for 8 processes. Each process cycles through 8 locations (L0–L7):
- **L0**: Idle
- **L1**: Raise interested flag
- **L2**: Set turn to predecessor
- **L3**: Initialize j(self) to successor
- **L4**: Check if j(self) == self → enter CS; otherwise go to L5
- **L5**: Wait while interested(k) && turn == k, decrement j on contention
- **L6**: Critical section
- **L7**: Clear interested flag, return to L0

## 2. Violated Assertion

### Assertion Name
`interested0_eventually_cleared` (from waveform filename `Peterson.interested0_eventually_cleared.fst`)

### Code Snippet (mppLTLM1.scala, lines 333–342)
```scala
// ---------- LIVENESS: Interested Flag Reset ----------
// Once interested(0) becomes true, it should eventually become false again
// (process completes its protocol cycle through L7).
// This captures system-level forward progress for process 0.
astRelaxedLiveness(
  interested(0) === true.B,
  interested(0) === false.B,
  50,
  "interested0_eventually_cleared"
)
```

### Property Description
This is a bounded liveness property using Chisel's `astRelaxedLiveness` construct. The property asserts:
- **Antecedent**: `interested(0) === true.B` (process 0 is interested in entering CS)
- **Consequent**: `interested(0) === false.B` (process 0's interested flag is cleared)
- **Bound**: 50 clock cycles
- **Meaning**: Once process 0 has raised its interested flag, it should complete its protocol cycle (exit critical section via L7 and clear the flag) within 50 cycles.

## 3. Waveform Information

### Waveform File
`verilog/extra_bench/strltl_peterson/Peterson.interested0_eventually_cleared.fst`

### Time Range
0 ns to 540 ns (54 cycles, 10 ns per cycle)

### Key Time Points (all values at rising clock edges)

| Time (ns) | Cycle | Key Events |
|-----------|-------|------------|
| 0         | 0     | Reset, all signals initialized to 0 |
| 20        | 2     | **`interested(0)` becomes 1** (process 0 enters L1→L2) |
| 170       | 17    | `pc(0)` becomes L6 (110) — process 0 enters critical section |
| 500       | 50    | `self=0`, `pc(0)=L6`, `io_pause=0`, `io_select=6` |
| 510       | 51    | `pc(0)` transitions to L7 (111) but `self` changes to 6; L7 handler fires for process 6, NOT process 0. **`interested(0)` remains 1**. |
| 520–540   | 52–54 | `self` continues to change (5, then others). `pc(0)` stays at L7 (stuck). `interested(0)` stays 1 indefinitely. |

### Critical Signal Values

**At t=500 ns (rising edge, before update)**:
- `Peterson.interested_0` = 1
- `Peterson.pc_0 [2:0]` = 110 (L6)
- `Peterson.self [2:0]` = 000 (process 0)
- `Peterson.io_pause` = 0
- `Peterson.io_select [2:0]` = 110 (process 6)

**At t=510 ns (rising edge, after update)**:
- `Peterson.interested_0` = 1 (NOT cleared!)
- `Peterson.pc_0 [2:0]` = 111 (L7)
- `Peterson.self [2:0]` = 110 (process 6 — has already moved on)
- `Peterson.pc_6 [2:0]` = 101 (L5)

**At t=520 ns (rising edge)**:
- `Peterson.interested_0` = 1 (still not cleared!)
- `Peterson.pc_0 [2:0]` = 111 (L7 — stuck, never executed for process 0)
- `Peterson.self [2:0]` = 101 (process 5)

## 4. Root Cause Analysis

### Category
**Bug in the Original Design (DUT Bug)**

### Bug Location
**File**: `mppLTLM1.scala`  
**Lines**: 251–257 (the `is(Loc.L6)` handler within the Peterson class)  
**Function**: The L6 critical-section exit handler

### Description of the Bug

The bug is a **missing `interested(self) := false.B` assignment** in the L6 handler when exiting the critical section.

#### Current (buggy) code:
```scala
is(Loc.L6) {
  when(io.pause && (self === 0.U || self === 1.U || self === 2.U)) {
    pc(self) := Loc.L6
  }.otherwise {
    pc(self) := Loc.L7
  }
}
```

#### What happens at runtime:

1. Process 0 enters the critical section L6 at t=170 (cycle 17).
2. At t=500 (cycle 50), the scheduler selects process 0 (`self=0`), `pc(0)=L6`, `io_pause=0`.
3. The L6 handler fires: since `!io_pause`, it takes the `.otherwise` branch and sets `pc(0) := L7`. **But `interested(0)` is NOT cleared here.**
4. At the same clock edge (t=510), `self` is also updated to 6 because `io_select=6`.
5. On the next cycle (t=520), the switch statement evaluates `pc(self)` = `pc(6)` = L5. The L7 handler (which would clear `interested(0)` and set `pc(0)` back to L0) **never executes for process 0**.
6. Process 0 is stuck at L7 with `interested(0)` permanently set to 1.

**Root cause**: The `interested(self)` flag clearing is deferred to the L7 handler, but by the time L7 is reached for process 0, `self` may have already changed to a different process. The L7 handler clears `interested(self)` using the current `self` value, not the process that actually exited L6.

### Why This Causes the Assertion to Fail

The assertion `interested0_eventually_cleared` requires `interested(0)` to become false within 50 cycles of becoming true. `interested(0)` becomes true at t=20 (cycle 2). After the bound of 50 cycles (t=520, cycle 52), `interested(0)` is still 1 and never clears:

- `interested(0) = true` at t=20 (cycle 2)
- Expected clearing by: t=20 + 50×10 = t=520 (cycle 52)
- At t=520: `interested(0) = 1` (still true)
- At t=540 (end of trace): `interested(0) = 1` (still true)

The assertion checks that `interested(0) === false.B` eventually holds within 50 cycles. Since it never holds, the assertion fails.

### Fix Recommendation

Add `interested(self) := false.B` in the `.otherwise` branch of the L6 handler, so the interested flag is cleared when exiting the critical section, before `self` can change:

```scala
is(Loc.L6) {
  when(io.pause && (self === 0.U || self === 1.U || self === 2.U)) {
    pc(self) := Loc.L6
  }.otherwise {
    interested(self) := false.B   // <-- FIX: clear interested before self changes
    pc(self) := Loc.L7
  }
}
```

This ensures `interested(0)` is cleared at the same time `pc(0)` transitions from L6 to L7, regardless of what `self` is on subsequent cycles.

### Alternative Fix

Alternatively, keep the clearing in L7 but modify the L7 handler to clear the process that left L6 rather than using the current `self`:

```scala
is(Loc.L7) {
  // Would need to track which process exited L6
  // More complex, the L6 fix is cleaner
}
```

The L6 fix is preferred because it is minimal, correct, and follows the principle that cleanup of a resource should happen before the scheduling context changes.
