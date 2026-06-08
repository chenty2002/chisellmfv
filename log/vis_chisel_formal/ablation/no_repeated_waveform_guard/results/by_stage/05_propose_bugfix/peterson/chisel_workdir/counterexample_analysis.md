# Counterexample Analysis Report: liveness_p0_L1_to_L4

## 1. Verification Environment

- **Top Module**: `peterson` (from `peterson.scala`)
- **Design Under Test**: A single-threaded Chisel implementation of Peterson's mutual exclusion algorithm. The design uses a single state machine that executes one of two processes per clock cycle, selected by the `self` register (which tracks `io_select`).
- **Key Components**:
  - `pc(0)`, `pc(1)`: Program counters (6 locations each: L0-L5)
  - `interested(0)`, `interested(1)`: Interest flags
  - `turn`: Shared turn variable
  - `self`: Selects which process runs this cycle (updated from `io_select`)
  - `timer`: 6-bit counter tracking elapsed cycles since assertion trigger
  - `pending`: Assertion trigger status flag
- **Input Signals**: `io_select` (selects active process), `io_pause` (pauses L0→L1 and L4→L5 transitions)

## 2. Violated Assertion

- **Full Assertion Name**: `liveness_p0_L1_to_L4`
- **Waveform File**: `peterson.liveness_p0_L1_to_L4.fst`
- **Code Location**: `peterson.scala`, line 106
- **Code Snippet**:
  ```scala
  astRelaxedLiveness(pc(0) === Loc.L1 && self === 0.U, pc(0) === Loc.L4, 50, "liveness_p0_L1_to_L4")
  ```
- **Property Description**: When process 0 is at location L1 (just set interested flag) **and** process 0 is the currently scheduled process (`self === 0.U`), then process 0 should reach the critical section (L4) within 50 clock cycles.

## 3. Waveform Information

- **Full Waveform Path**: `verilog/extra_bench/peterson/peterson.liveness_p0_L1_to_L4.fst`
- **Time Range**: 0 ns → 530 ns (53 cycles at 10 ns/cycle)
- **Key Time Points**:
  - **Time 10 ns** (cycle 1): Assertion triggers. `pc_0=L1`, `self=0`, `io_select=1`, `io_pause=0`
  - **Time 20 ns** (cycle 2): `pc_0=L2`, `interested_0=1`, `self=1` (process 1 takes over)
  - **Time 70 ns** (cycle 7): `pc_0=L3`, both processes at L3, `self=0`, `turn=1`, `interested_1=1`, `io_pause=1`
  - **Time 100 ns** (cycle 10): `pc_0=L3`, `self=0`, `turn=1`, `interested_1=1` — condition `!interested(1)||(turn===0)` = false
  - **Time 110 ns** (cycle 11): `self=1` — process 0 frozen indefinitely
  - **Time 120 ns** (cycle 12): Process 1 exits L5→L0, clears `interested_1=0`. Process 0 stuck at L3.
  - **Time 160 ns** (cycle 16): `turn=0` (set by process 1 at L2). Condition for p0: `!1 || (0===0)=true`, but `self=1` so p0 is frozen.
  - **From time 110→520 ns**: `self=1` continuously. Process 0 never gets selected.
  - **Time 520 ns** (cycle 52): `timer=50` (binary: 110010), assertion fails.
- **Signals at Failure Point** (time 520 ns):
  - `pc_0[2:0]` = `011` (L3), `pc_1[2:0]` = `011` (L3)
  - `self` = `1`, `io_select` = `1` (changes to 0 at time 510, self captures at 520)
  - `turn` = `0`, `interested_0` = `1`, `interested_1` = `1`
  - `io_pause` = `1`, `timer[5:0]` = `110010` (50 decimal)

## 4. Root Cause Analysis

### Error Classification: **Setup Error (setup_error)**

### Root Cause: The single-threaded architectural model fundamentally cannot guarantee liveness for Peterson's algorithm because the `io_select` (and thus `self`) signal is unconstrained and can starve a process indefinitely.

### Detailed Explanation

The `peterson` module implements Peterson's algorithm using a **single-threaded state machine** — only one process executes per clock cycle, determined by `self := io_select`. The state machine switch statement operates on `pc(selfIdx)`, so only the selected process updates each cycle.

The assertion `liveness_p0_L1_to_L4` requires that **process 0 reaches L4 within 50 cycles** after it enters L1 while selected (`self=0`). The counterexample path is:

1. **Time 10**: Trigger fires: `pc_0=L1 ∧ self=0`. Process 0 executes L1→L2 (sets `interested_0=1`).
2. **Time 20**: `self=1` (process 1 takes over). At L2: `turn=~self=~1=0`, `pc_0=L3`.
3. **Time 70**: Process 0 executes L2→L3 (sets `turn=~self=~0=1`) while briefly re-selected.
4. **Time 70–100**: Process 0 at L3, condition `!interested(1)||(turn===0)` is false (since `interested_1=1` and `turn=1`).
5. **Time 110–520**: `self=1` continuously. **Process 0 is completely frozen** — it cannot evaluate or advance from L3.

### The critical observation

At **time 160**: Process 1 sets `turn=0` at L2. Now process 0's L3 condition becomes true: `!interested(1)||(turn===0)` = `!1||(0===0)` = true. **But process 0 is frozen because `self=1`**. Even though the spinlock condition is satisfied, the single-threaded architecture prevents process 0 from advancing.

At **time 120**: Process 1 clears `interested_1=0` at L5→L0. Now p0's L3 condition `!interested(1)||(turn===0)` = `!0||...` = true. **But again, `self=1` and process 0 is frozen.**

### Why this is a setup error

In a proper concurrent implementation of Peterson's algorithm, **both processes execute independently**. Process 0 at L3 would continuously re-evaluate its entry condition every cycle. When `interested_1` clears (time 120) or `turn` flips to 0 (time 160), process 0 would immediately enter L4.

However, in this single-threaded Chisel model, process 0 can only execute when `self=0`. The verification environment (`io_select` signal) is free to hold `self=1` for arbitrarily long periods (here, from time 110 to time 520 = 41 cycles), completely starving process 0.

The resulting deadlock from time 160 onward:
- **Process 0**: Frozen at L3 because `self=1` (not selected)
- **Process 1**: Blocked at L3 because `interested_0=1 ∧ turn=0` → `!interested(0)||(turn===1)` = `!1||0` = false

Neither process can advance, causing the timer to expire at 50 cycles.

### Buggy Code Location

**File**: `peterson.scala`, lines 36–37 and 106

Line 36–37:
```scala
self := io_select
```
The `self` register is directly driven by `io_select` without any fairness constraint. The verification environment can drive `io_select=1` indefinitely, starving process 0.

Line 106:
```scala
astRelaxedLiveness(pc(0) === Loc.L1 && self === 0.U, pc(0) === Loc.L4, 50, "liveness_p0_L1_to_L4")
```
The assertion assumes that once triggered, process 0 will eventually be selected again to make progress. But the architecture and environment do not guarantee this.

### Recommended Fix

**Option A (Setup Fix)**: Add a fairness constraint on `io_select` so that if one process has been selected for multiple cycles without making progress, the other process gets selected. For example, constrain `io_select` to toggle within `N` cycles of a process entering L3.

**Option B (Design Fix)**: Refactor the design to use a truly concurrent model where both processes execute independently each cycle, with shared variables (`interested`, `turn`) being the only synchronization points. This would properly model Peterson's algorithm.

**Option C (Assertion Modification)**: Relax the assertion to check liveness only when both processes are actively participating, or increase the bound beyond 50 if the scheduling model makes progress inherently slower.

For this benchmark, Option A (adding a fairness constraint in the verification setup) is the most practical fix.
