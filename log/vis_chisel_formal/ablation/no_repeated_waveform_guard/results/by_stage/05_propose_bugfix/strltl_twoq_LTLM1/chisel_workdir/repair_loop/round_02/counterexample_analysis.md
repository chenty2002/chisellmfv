# Counterexample Analysis: buechi_progress_on_match

## 1. Verification Environment

- **Top Module**: `twoQ` (class in `twoqLTLM1.scala`)
- **Key Components**:
  - `q0` / `q1`: Two instances of `sampleq` (dual FIFO queues with read/write match detection)
  - `buechi`: A Buechi automaton instance that tracks progress of match events
  - **Arbiter**: Combinatorial priority arbiter within `twoQ` that grants the bus based on `io.select` and request signals
- **Connections**:
  - `q0.io.match_out` → `buechi.io.q0match`
  - `(q0.io.storeaddr === q0.io.readhead)` → `buechi.io.q0storeaddrNEQq0readhead` (see bug note about misleading name)
  - `bus_gnt(0)` → `buechi.io.busgnt0`
  - `io.select` controls arbitration priority: select=0 → q0 gets priority, select=1 → q1 gets priority
- **Design Under Test**: A two-queue system supporting read/write operations with a shared bus and a Buechi progress monitor

## 2. Violated Assertion

- **Full Assertion Name**: `buechi_progress_on_match`
- **Waveform File**: `twoQ.buechi_progress_on_match.fst`
- **Code Snippet** (`twoqLTLM1.scala`, line 287):
  ```scala
  astRelaxedLiveness(q0.io.match_out, io.scc || !q0.io.match_out, 8, "buechi_progress_on_match")
  ```
- **Property Description**: 
  Whenever `q0.io.match_out` is asserted (a match between read queue and write queue entries is detected), the system must either:
  1. Reach `io.scc` (Buechi state s_n3 or s_n4) within 8 cycles, OR
  2. `q0.io.match_out` must be deasserted within 8 cycles
  
  The `io.scc` signal is defined as `(state === s_n3) || (state === s_n4)` in the Buechi automaton. The Buechi progresses from `s_n2` → `s_n1` → `s_n3` → `s_n4` on successful match events where `storeaddr != readhead`.

- **File Location**: `twoqLTLM1.scala`, line ~287

## 3. Waveform Information

- **Full Path**: `verilog/extra_bench/strltl_twoq_LTLM1/twoQ.buechi_progress_on_match.fst`
- **Time Range**: 0 ns to 120 ns (12 cycles, clock period = 10 ns)
- **Critical Time Points**:
  - **t=20 ns** (posedge 2): q0 detects a match (`q0.matchReg` goes from 0→1, `q0.io_match_out` goes from 0→1 at this same time). `q0.storeaddrReg=00`, `q0.readheadReg=01` (storeaddr != readhead).
  - **t=29 ns** (just before posedge 3): Buechi in s_n2, sees `q0match=1`, `q0storeaddrNEQq0readhead=0` (meaning `storeaddr!=readhead` since the signal is actually EQUALITY despite the name). Inputs = {1,0} = "b10" → triggers `ND_n1_n2` transition to s_n1.
  - **t=30 ns** (posedge 3): Buechi transitions to s_n1. On the same posedge, q0's `when(io.bus_gnt)` block fires, unconditionally setting `storeaddrReg := readheadReg` (= 01), making storeaddr == readhead.
  - **t=39 ns** (just before posedge 4): Buechi in s_n1, sees `q0storeaddrNEQq0readhead=1` (meaning `storeaddr==readhead`). Since the value is NOT false.B, the `otherwise` branch fires.
  - **t=40 ns** (posedge 4): Buechi transitions to s_Trap (100).
  - **t=40-120 ns**: Buechi remains stuck in s_Trap forever; `io.scc` stays 0; `io.fair` stays 0; `q0.match_out` stays 1.
  - **t=110 ns**: Assertion signal `buechi_progress_on_match` goes from 1→0 (assertion failure detected).

### Key Signal Values at Failure Point

| Signal | t=29 | t=30 | t=31 | t=39 | t=40 | t=49 | t=100 |
|--------|------|------|------|------|------|------|-------|
| `buechi.state` | s_n2 (001) | s_n1 (000) | s_n1 (000) | s_n1 (000) | s_Trap (100) | s_Trap (100) | s_Trap (100) |
| `q0.storeaddrReg` | 00 | — | 01 | 01 | 01 | — | — |
| `q0.readheadReg` | 01 | — | 01 | 01 | 01 | — | — |
| `q0storeaddrNEQq0readhead` | 0 (diff) | — | 1 (equal) | 1 (equal) | 1 (equal) | 1 (equal) | 1 (equal) |
| `q0.io_bus_gnt` | 1 | 0 | 0 | 0 | 1 | 1 | — |
| `q0.io_match_out` | 1 | — | 1 | 1 | 1 | 1 | 1 |
| `io.scc` | 0 | 0 | 0 | 0 | 0 | 0 | 0 |

## 4. Root Cause Analysis

### Bug Location

**File**: `twoqLTLM1.scala`  
**Class**: `sampleq` (lines 60-62)  
**Buggy Line**: Line 63 — `storeaddrReg := readheadReg` (unconditional assignment) within the `when(io.bus_gnt)` block

### Description of the Bug

The `sampleq` module has an unconditional assignment on every bus grant cycle:

```scala
// Line 62-64 in twoqLTLM1.scala
when(io.bus_gnt) {
    matchReg := false.B
    storeaddrReg := readheadReg    // <-- BUG: UNCONDITIONAL overwrite on every bus_gnt
    // ...
    for (i <- 0 until LENGTH) {
      when(writeEntryValid && !readempty && (readfifo(readheadReg) === writefifo(i.U))) {
        matchReg := true.B
        storeaddrReg := readheadReg  // <-- This is the conditional assignment (same value)
      }
    }
```

Line 63 unconditionally sets `storeaddrReg := readheadReg` **on every** bus grant cycle, regardless of whether a new match is found. This overwrites the stored match address (`storeaddrReg`) with the current `readheadReg` value, destroying the very information that the Buechi automaton relies on to detect progress.

### How the Bug Manifests (Step-by-step)

1. **t=20 ns (posedge 2)**: q0 receives bus_gnt. Input writes a read entry. A match is found between `readfifo(0)==00` and `writefifo(0)==00`. The match loop correctly sets:
   - `matchReg := true.B`
   - `storeaddrReg := readheadReg` (= 0, the readhead at the time of match)
   - `readheadReg` advances to 1 (output is dequeued from read queue)
   
   After this cycle: `storeaddrReg=00`, `readheadReg=01` → they **differ**. `buechi.io_q0storeaddrNEQq0readhead` = 0.

2. **t=30 ns (posedge 3)**: q0 receives another bus_gnt. The when block fires.
   - **Line 63**: `storeaddrReg := readheadReg` (= 01) ← **This is the bug!** storeaddr is unconditionally overwritten.
   - The for loop finds another match (the same write entry still matches the read entry at position 1), but assigns the same value (01).
   
   After this cycle: `storeaddrReg=01`, `readheadReg=01` → they are now **equal**. `buechi.io_q0storeaddrNEQq0readhead` = 1.

3. **Buechi Transitions**:
   - At posedge 2 (t=20): match detected, but Buechi only reacts at next edge.
   - **At posedge 3 (t=30)**: Buechi sees `q0match=1, q0storeaddrNEQq0readhead=0` (storeaddr ≠ readhead). This is the "b10" case in s_n2 transition → goes to s_n1 (progress!).
   - **At posedge 4 (t=40)**: Buechi is in s_n1, sees `q0storeaddrNEQq0readhead=1` (meaning storeaddr == readhead, i.e., `(storeaddr===readhead) !== false.B`). The s_n1 transition code is:
     ```scala
     when(io.q0storeaddrNEQq0readhead === false.B) {
       state := s_n3    // Expected progress path
     }.otherwise {
       state := s_Trap  // Trap!
     }
     ```
     Since `q0storeaddrNEQq0readhead=1` (NOT false), the Buechi takes the **Trap path** instead of the progress path to s_n3.

4. **After t=40**: The Buechi is stuck in s_Trap forever. `io.scc` stays 0, `io.fair` stays 0, and the assertion `buechi_progress_on_match` remains unsatisfied. After 8 cycles (by t=100), the liveness bound expires, and the assertion fails at t=110.

### Why This Classification is a **DUT Bug**

The root cause is a genuine design bug in the `sampleq` module, not an assertion error or test setup issue:

- The **assertion** correctly captures the intended liveness property: when a match occurs, the Buechi should eventually make progress.
- The **test setup** is correct: it provides realistic stimulus patterns.
- The **DUT logic** is buggy: the unconditional `storeaddrReg := readheadReg` on line 63 destroys the evidence of progress that the Buechi needs. The `storeaddrReg` should only be updated when a new match is found (i.e., inside the for loop's `when` condition), not unconditionally on every bus grant.

### Suggested Fix

Remove the unconditional `storeaddrReg := readheadReg` on line 63, so that `storeaddrReg` is only updated when a match is actually found inside the for loop (line 74). This preserves the old `storeaddrReg` value when no new match occurs, allowing the Buechi to correctly detect that `storeaddr != readhead` has occurred and continue toward s_n3/s_n4.
