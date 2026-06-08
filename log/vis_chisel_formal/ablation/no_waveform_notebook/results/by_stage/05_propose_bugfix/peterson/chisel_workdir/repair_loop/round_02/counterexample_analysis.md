# Counterexample Analysis Report: peterson.liveness_process0

## 1. Verification Environment

- **Top Module**: `peterson` (from `peterson.scala`)
- **Design**: Peterson's mutual exclusion algorithm for two processes
- **Key Components**:
  - Two processes (process 0 and process 1) with independent program counters (`pc_0`, `pc_1`) using a 6-state enum (`L0`–`L5`)
  - Shared state: `interested` (Vector of 2 bools), `turn` (1-bit), `self` (1-bit)
  - Control inputs: `io_select` (selects active process), `io_pause` (pauses process at entry/exit points)
- **Formal Assertions**: Mutual exclusion, critical-section interest implication, and two bounded-liveness properties (`liveness_process0`, `liveness_process1`)

## 2. Violated Assertion

- **Assertion Name**: `liveness_process0` (from waveform filename `peterson.liveness_process0.fst`)
- **Chisel Source**: `peterson.scala`, line 99:
  ```scala
  astRelaxedLiveness(pc(0) === Loc.L3, pc(0) === Loc.L4, 20, "liveness_process0")
  ```
- **Verilog Implementation** (lines 120-122):
  ```verilog
  wire       nextPending = _resetCounter_notChaos & ~_GEN_0 & (pending | _GEN);
  // _GEN = pc_0 == 3'h3 (L3), _GEN_0 = pc_0 == 3'h4 (L4)
  liveness_process0:
    assert property (@(posedge clock) disable iff (~hasBeenReset)
                     ~nextPending | (_nextTimer_T_1 ? _nextTimer_T_2 : 5'h0) < 5'h15);
  ```
- **Description**: Whenever process 0's program counter enters state L3 (the waiting/spinning state in Peterson's algorithm), it must reach state L4 (the critical section) within 20 clock cycles. This bounded liveness property verifies that Peterson's algorithm provides starvation-freedom with a bounded wait.

## 3. Waveform Information

- **Waveform File**: `verilog/extra_bench/peterson/peterson.liveness_process0.fst`
- **Time Range**: 0 ns → 310 ns (31 cycles at 100 MHz / 10 ns period)
- **Failure Time**: The assertion fires at approximately **t = 300–310 ns** when the timer reaches its bound of 20 cycles without process 0 reaching L4.
- **Critical Time Points**:

| Time (ns) | pc_0 | pc_1 | turn | interested_0 | interested_1 | io_pause | Event |
|-----------|------|------|------|-------------|-------------|----------|-------|
| 0 | L0 | L0 | 0 | 0 | 0 | 0 | Reset state |
| 20 | L2 | L2 | 0 | 1 | 1 | 0 | Both set interested flags |
| 30 | L3 | L3 | 0 | 1 | 1 | 1 | Both enter waiting state |
| 40 | **L4** | L3 | 0 | 1 | 1 | 0 | Process 0 enters CS (turn=0 gives it priority) |
| 50 | L5 | L3 | 0 | 1 | 1 | 1 | Process 0 exits CS |
| 60 | L0 | L3 | 0 | 0 | 1 | 0 | Process 0 resets, process 1 can now enter CS |
| 70 | L1 | **L4** | 0 | 0 | 1 | 1 | Process 1 enters CS but io_pause=1 blocks exit |
| 80 | L2 | L4 | **1** | 1 | 1 | 1 | Process 0 sets turn=1 (hands priority to process 1) |
| 90 | **L3** | L4 | 1 | 1 | 1 | 1 | **Process 0 enters L3, stuck because interested_1=1 AND turn=1** |
| 100–270 | L3 | L4 | 1 | 1 | 1 | 1 | **Both processes stalled: process 1 by io_pause, process 0 by condition** |
| 280 | L3 | L4 | 1 | 1 | 1 | **0** | io_pause releases |
| 290 | L3 | **L5** | 1 | 1 | 1 | 0 | Process 1 exits CS |
| 300 | L3 | L0 | 1 | 1 | **0** | 0 | Process 1 resets, clears interest — but **timer reached 20 → assertion fails** |

## 4. Root Cause Analysis

### Root Cause Classification: **Setup Error** (Incorrect Top Module Configuration)

### Detailed Explanation

**The Problem**: The `io_pause` signal is held high for 22 consecutive cycles (t=70 to t=280), which causes a chain of stalls that prevents process 0 from reaching the critical section within the 20-cycle bound.

**Mechanism** (step-by-step):

1. **Process 1 enters CS and gets stuck** (t=70): At t=70, process 1 enters the critical section (L4). The L4 state transition logic is:
   ```scala
   is(Loc.L4) {
     when(!io.pause) { pc(myIdx) := Loc.L5 }  // only exits when pause is LOW
   }
   ```
   Since `io_pause=1` from t=70 to t=280, process 1 is stuck at L4 for 22 cycles.

2. **Process 0 gives priority to process 1** (t=80): When process 0 passes through L2 at t=80, it sets `turn := otherIdx = 1`, giving priority to process 1.

3. **Process 0 enters L3 and can't leave** (t=90): Process 0 reaches the waiting state L3. The condition to leave L3 is:
   ```scala
   is(Loc.L3) {
     when(!interested(otherIdx) || (turn === myIdx)) { pc(myIdx) := Loc.L4 }
   }
   ```
   For process 0 (myIdx=0, otherIdx=1), this becomes: `!interested(1) || (turn === 0)`.
   - `interested(1) = 1` → `!interested(1) = false`
   - `turn = 1` → `turn === 0` is false
   - **Both conditions are false** → process 0 is blocked.

4. **Cascade effect**: Process 1 is stuck in CS (due to `io_pause`), so it never clears `interested(1)`. Process 0 set `turn=1` (giving priority to process 1). Therefore process 0 can't enter L4.

5. **Bound exceeded**: The liveness timer starts counting when process 0 enters L3 (t=90 → pending asserted at t=100). It counts for 20 cycles (t=100 to t=300). Meanwhile, `io_pause` stays high until t=280. Even after pause releases (t=280), it takes 2 more cycles for process 1 to exit CS and clear `interested(1)` (t=290: L4→L5; t=300: L5→L0 with interested_1→0). By t=300, the timer has already reached 20, and the assertion fails.

### Evidence from Waveform

- `peterson.io_pause`: Held at `1` from t=70 to t=280 (22 cycles)
- `peterson.pc_1 [2:0]`: Stuck at `100` (L4) from t=70 to t=290
- `peterson.pc_0 [2:0]`: Stuck at `011` (L3) from t=90 onwards
- `peterson.interested_1`: Remains `1` from t=20 until t=300
- `peterson.turn`: Set to `1` at t=80, stays `1` throughout
- `peterson.pending`: Asserted at t=100, stays high, timer counts to `10100` (20) by t=300

### Why This Is a Setup Error

In a real hardware implementation of Peterson's algorithm, the `io_pause` signal is intended for short-duration stalls (e.g., pipeline stalls for a few cycles). Holding `io_pause` high for 22 consecutive cycles is an **unrealistically pessimistic stimulus** that the formal solver can exploit because there are no constraints on `io_pause` in the test harness.

The liveness bound of 20 cycles is reasonable under normal operation where `io_pause` is either low or asserted only briefly. However, the unbounded pause duration creates an artificially long critical-section residency for process 1, which blocks process 0 beyond the 20-cycle bound.

### Possible Fixes

1. **Constrain `io_pause`**: Add an assumption/constraint that `io_pause` cannot be held high for more than a small number (e.g., 5) consecutive cycles. This would reflect realistic hardware behavior.

2. **Increase the liveness bound**: Raise the bound from 20 to a larger value that accounts for worst-case pause duration.

3. **Add an `astLiveness` (unbounded) assertion**: Use unbounded liveness `astLiveness` instead of the bounded `astRelaxedLiveness` with 20 cycles, which would check the true starvation-freedom property of Peterson's algorithm without a fixed cycle bound.
