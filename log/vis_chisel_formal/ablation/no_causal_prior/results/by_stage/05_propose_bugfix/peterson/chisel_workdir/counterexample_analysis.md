# Counterexample Analysis: progress_entry_cs_0

## 1. Verification Environment

- **Top Module**: `peterson` (from `peterson.scala`)
- **Generated Verilog**: `chisel/extra_bench/peterson/generated/`
- **Waveform File**: `verilog/extra_bench/peterson/peterson.progress_entry_cs_0.fst`
- **Structure**: The `peterson` module implements Peterson's mutual-exclusion algorithm for two processes.
  - **Key components**:
    - `pc(0)`, `pc(1)`: Program counters (L0–L5 enum states)
    - `interested(0)`, `interested(1)`: Flag registers indicating intent to enter CS
    - `turn`: Shared turn register for tie-breaking
    - `self`: Tracks which process is currently executing (clock-divided `io.select`)
    - `io.pause`: External pause signal (completely unconstrained)
    - `io.select`: External select signal (constrained to toggle every cycle)
  - **Assumptions**: `assume(io.select =/= RegNext(io.select))` — select toggles each cycle, ensuring fair scheduling
  - **No constraint on io.pause**: This is the critical omission

## 2. Violated Assertion

- **Full assertion name**: `progress_entry_cs_0` (from waveform filename `peterson.progress_entry_cs_0.fst`)
- **Code snippet** (from `peterson.scala`, lines 124–126):
  ```scala
  val inL3_0 = pc(0) === Loc.L3
  val inCS_0 = pc(0) === Loc.L4 || pc(0) === Loc.L5
  astRelaxedLiveness(interested(0) && inL3_0, inCS_0, 25, "progress_entry_cs_0")
  ```
- **Natural language description**: When process 0 is waiting at the entry protocol (L3) with its interested flag set, it should enter its critical section (L4 or L5) within 25 clock cycles.
- **File location**: `peterson.scala`, lines 124–126

## 3. Waveform Information

- **Full path to waveform file**: `verilog/extra_bench/peterson/peterson.progress_entry_cs_0.fst`
- **Waveform duration**: 0–340 ns (34 cycles, clock period = 10 ns)
- **Key time points**:

| Time (ns) | Cycle | Event |
|-----------|-------|-------|
| 50 | 5 | `interested(0)` rises to 1; `pc(0)` enters L2 |
| 60 | 6 | `self=0`; process 0 at L2 executes `turn := ~self = 1`; `io_pause=1` |
| 70 | 7 | `pc(0)` enters L3 — **assertion trigger fires**; `turn` becomes 1; `pc(1)` enters L4 |
| 80 | 8 | `pc(1)` stuck at L4 (`io_pause=1`); `pc(0)` stuck at L3 (`!interested(1)||(turn===0)` = false) |
| 80–260 | 8–25 | `io_pause=1` continuously, both processes stuck |
| 260 | 26 | `io_pause=0` briefly, but `self=0` so process 0 active — still stuck at L3 |
| 310 | 31 | `io_pause=0`, `self=1`; process 1 at L4: `!io_pause` → `pc(1) := L5` |
| 320 | 32 | **25-cycle bound expires**; `pc(0)` still at L3, `pc(1)` at L5 |
| 330 | 33 | `progress_entry_cs_0` transitions from 1 to 0 — **assertion failure** |

- **Critical signal values at failure point (time 330)**:
  - `pc_0` = 011 (L3) — still waiting at entry protocol
  - `pc_1` = 101 (L5) — just entered CS exit (too late to help process 0)
  - `interested_0` = 1, `interested_1` = 1
  - `turn` = 1
  - `self` = 1
  - `io_pause` = 0, `io_select` = 0

## 4. Root Cause Analysis

### Error Classification: **Setup Error** — Missing constraint on `io_pause`

### Description of the Problem

The assertion `progress_entry_cs_0` requires process 0 to reach its critical section (L4/L5) within 25 cycles of being at L3 with `interested(0)=1`. However, the formal environment places **no constraint on `io_pause`**, allowing the formal solver to hold `io_pause=1` for an arbitrarily long duration. This creates an artificial deadlock scenario:

### Detailed Execution Trace

**Cycle 5 (time 50)**: Process 0 sets `interested(0)=1` and enters L2.

**Cycle 6 (time 60)**: With `self=0`, process 0 executes L2: `turn := ~self = 1`, `pc(0) := L3`. Simultaneously, `io_pause` is asserted high.

**Cycle 7 (time 70)**: `pc(0)` enters L3. The assertion trigger `interested(0) && inL3_0` becomes true — **the 25-cycle timer starts**. Also:
- `turn` becomes 1 (updated from previous cycle's L2 computation)
- Process 1 (`self=1`) at L3 checks `!interested(0) || (turn === 1)` = `!1 || (1===1)` = **true** → `pc(1) := L4`

**Cycle 8 (time 80)**: Both processes are now stuck:
- **Process 1 at L4**: Checks `!io_pause` — but `io_pause=1`, so **stuck** at L4
- **Process 0 at L3**: Checks `!interested(1) || (turn === 0)` = `!1 || (1===0)` = **false** → **stuck** at L3

**Cycles 8–25 (times 80–260)**: `io_pause` remains high for 18 consecutive cycles. Process 1 cannot leave L4 because `io_pause` blocks the L4→L5 transition. Process 0 cannot enter CS because `interested(1)=1` and `turn=1 ≠ 0` — the very condition that Peterson's algorithm requires for the other process to go first.

**Cycle 26 (time 260)**: `io_pause` drops to 0, but `self=0` — so process 0 is selected. Process 0 at L3 still sees `!interested(1)||(turn===0)` = false.

**Cycle 31 (time 310)**: Finally `self=1` and `io_pause=0` coincide. Process 1 at L4: `!io_pause` → `pc(1) := L5`.

**Cycle 32 (time 320)**: `pc(1)=L5`. Process 1 enters the exit protocol. But the **25-cycle bound has now expired** — the assertion timer started at cycle 7 (time 70) and ended at cycle 32 (time 320), exactly 25 cycles later.

**Cycle 33 (time 330)**: The assertion fails (`progress_entry_cs_0` → 0).

### Why This Is a Setup Error

The DUT itself correctly implements Peterson's algorithm. The `io_pause` signal is an **external input** designed to model a "pause" or "halt" condition. In a realistic setting, pause would not be asserted continuously for 20+ cycles. However, the formal environment does not constrain it:

- `assume(io.select =/= RegNext(io.select))` — constrains `io.select` to toggle
- **No equivalent constraint on `io.pause`** — the formal solver can drive it arbitrarily

This allows the solver to construct an unrealistic scenario where `io_pause` blocks process 1 in L4 for 18 cycles, consuming most of the 25-cycle liveness budget without any progress.

### How to Fix

**Option A (Recommended)**: Add a fairness constraint on `io_pause` to bound its continuous assertion, e.g.:
```scala
// io_pause should not remain high for extended periods
assume(io.pause =/= RegNext(io.pause) || !io.pause)
```
Or more flexibly:
```scala
assume(RegNext(RegNext(io.pause)) === false.B)  // pause cannot be high for 3+ consecutive cycles
```

**Option B**: Increase the liveness bound to account for worst-case pause duration. However, this is less clean since an unconstrained `io_pause` can always defeat any finite bound.

**Option C**: Modify the assertion to exclude pause-affected intervals (e.g., use `past(io.pause)` conditions). This is more complex and less intuitive.

### Buggy Code Location

`peterson.scala`, lines 124–126 — the liveness assertion itself is correct for Peterson's algorithm, but it lacks the complementary environment constraint on `io_pause`. The missing constraint should be added to the formal verification setup around line 109 (alongside the existing `io.select` constraint).
