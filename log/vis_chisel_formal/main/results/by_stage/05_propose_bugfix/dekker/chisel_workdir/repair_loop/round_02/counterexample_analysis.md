# Counterexample Analysis: dekker.LA1_liveness_L2_to_CS

## 1. Verification Environment

- **Top Module**: `dekker` (package `llmverify`)
- **Module Type**: Chisel `Module with Formal`
- **Design Under Test**: Dekker's mutual exclusion algorithm for two processes. The design implements a simple two-process mutual exclusion protocol where each process runs through states L0→L1→L2→L3→L4→L2/L5→L6→L0.
- **Key Components**:
  - `pc(0), pc(1)`: Program counters for the two processes (3-bit values)
  - `c(0), c(1)`: Flag registers indicating interest in entering critical section
  - `turn`: Whose turn it is (1-bit)
  - `self`: Selects which process the state machine currently executes
  - `io.select`: Nondeterministic input that determines `self`
  - `io.pause`: Pause input that can stall a process at L0 or L5
- **State Machine Structure**: A single state machine that acts on process `self` based on `switch(pc(self))`. The `self` register is updated each cycle to follow `io.select`.

## 2. Violated Assertion

- **Assertion Name**: `LA1_liveness_L2_to_CS`
- **Waveform File**: `dekker.LA1_liveness_L2_to_CS.fst`
- **Source Location**: `dekker.scala`, lines 116–121
- **Code Snippet**:
  ```scala
  val liveness_req_l2 = (pc(self) === L2) && (c(~self) === true.B) && !io.pause
  val liveness_resp_l6 = (pc(self) === L6)
  astRelaxedLiveness(liveness_req_l2, liveness_resp_l6, 10, "LA1_liveness_L2_to_CS")
  ```
- **Property Description**: When the currently-selected process (indexed by `self`) is at location L2, the other process's flag `c(~self)` is true (indicating it is not interested), and the pause input is deasserted, then the selected process should reach L6 (critical section) within 10 clock cycles.

## 3. Waveform Information

- **Waveform File Path**: `verilog/extra_bench/dekker/dekker.LA1_liveness_L2_to_CS.fst`
- **Time Range**: 0 ns → 140 ns (14 cycles)
- **Failure Time**: 130 ns (assertion signal `dekker.LA1_liveness_L2_to_CS` goes from 1 to 0)

### Critical Timeline (all times in nanoseconds)

| Time | pc(0) | pc(1) | self | io_select | io_pause | c0 | c1 | pending | timer | Event |
|------|-------|-------|------|-----------|----------|----|----|---------|-------|-------|
| 0    | 000   | 000   | 0    | 0         | 0        | 1  | 1  | 0       | 0000  | Initial state |
| 10   | 001   | 000   | 0    | 0         | 0        | 1  | 1  | 0       | 0000  | pc(0)→L1 |
| **20**   | **010**   | **000**   | **0**    | **0**         | **0**        | **0**  | **1**  | **0**       | **0000**  | **Request FIRES: pc(self=0)=L2, c(~self=1)=1, !pause** |
| 30   | 101   | 000   | 0    | 1         | 1        | 0  | 1  | 1       | 0000  | pc(0)→L5, pending→1, io_pause=1 blocks L5 |
| 40   | 101   | 000   | 1    | 0         | 0        | 0  | 1  | 1       | 0001  | self→1, state machine now drives process 1 |
| 50   | 101   | 001   | 0    | 1         | 1        | 0  | 1  | 1       | 0010  | self→0, but io_pause=1 blocks L5→L6 |
| 60   | 101   | 001   | 1    | 1         | 1        | 0  | 1  | 1       | 0011  | self→1 |
| 70   | 101   | 010   | 1    | 1         | 0        | 0  | 0  | 1       | 0100  | c(1)→0, pc(1)→L2 |
| 80   | 101   | 011   | 1    | 1         | 1        | 0  | 0  | 1       | 0101  | pc(1)→L3 |
| 90   | 101   | 100   | 1    | 1         | 0        | 0  | 1  | 1       | 0110  | c(1)→1, pc(1)→L4 |
| 100  | 101   | 100   | 1    | 0         | 0        | 0  | 1  | 1       | 0111  | L4: turn≠self, stuck |
| 110  | 101   | 100   | 0    | 0         | 1        | 0  | 1  | 1       | 1000  | self→0, io_pause=1 blocks |
| 120  | 101   | 100   | 0    | 1         | 0        | 0  | 1  | 1       | 1001  | self=0, io_pause=0, L5→L6 on next edge |
| **130**   | **110**   | **100**   | **1**    | **1**         | **0**        | **0**  | **1**  | **1**       | **1010**  | **pc(0)=L6 BUT self=1 → assertion FAILS** |

## 4. Root Cause Analysis

### Classification: **Incorrect Assertion** (assertion_error)

The bug is in the **assertion definition itself**, not in the design logic.

### Detailed Explanation

The assertion `LA1_liveness_L2_to_CS` uses `self` (the register that selects which process is being executed) in **both** the request and response conditions:

```scala
val liveness_req_l2 = (pc(self) === L2) && (c(~self) === true.B) && !io.pause
val liveness_resp_l6 = (pc(self) === L6)
```

`self` is a register that follows `io.select` with one cycle of delay (`self := selfNext; selfNext := io.select`). Since `io.select` is a **nondeterministic formal input**, it can change arbitrarily at any time. This means `self` value during request evaluation may differ from the `self` value during response evaluation.

### The Failure Sequence

1. **At time 20** (request fires): `self=0`, so `pc(self)=pc(0)=L2`, `c(~self)=c(1)=1`, `!io.pause=1`. The assertion commits: *"process 0 must reach L6 within 10 cycles."*

2. **At time 30**: Process 0 moves from L2→L5. Process 0 is now waiting at L5 for `!io.pause`.

3. **Between time 30 and 120**: `self` changes multiple times (0→1→0→1→0) due to nondeterministic `io_select` changes. Each time `self` changes, the state machine switches which process it executes.

4. **At time 120**: `self=0` (back to process 0), `io_pause=0`. Process 0 at L5 can now proceed.

5. **At time 130**: `pc(0)` transitions to `110` (L6). **Process 0 has reached the critical section.** However, at this exact time, `self` has changed to `1` due to `io_select` toggling. Therefore `pc(self)` evaluates to `pc(1)=100` (L4), not `pc(0)=110` (L6). The assertion sees `pc(self)=L4 ≠ L6` and reports a failure.

### Why the Design is Actually Correct

The design correctly implements Dekker's mutual exclusion algorithm:
- Process 0 reaches L6 at time 130 (12 cycles after the request, slightly exceeding the 10-cycle bound)
- The extra delay is caused by two factors:
  1. `io_pause` being asserted at time 30, 50, 60, 80, 110 (stalling process 0 at L5)
  2. `io_select` toggling `self` away from process 0 (times 40, 60-100), preventing the state machine from advancing process 0
- Notably, the worst-case path from L2→L6 goes through L5, which explicitly waits for `!io.pause`. The 10-cycle bound was chosen assuming `io.pause` stays low, but in formal verification, `io.pause` is also nondeterministic and can stall.

### Root Cause Summary

The assertion is **incorrectly specified** because:
1. It uses `self` as a **dynamic selector** to identify which process to monitor, but `self` can change between the request and response evaluation due to nondeterministic `io_select` input.
2. The assertion should **capture the process identity at request time** and use the captured value for the response check, or alternatively, check `pc(0) === L6 || pc(1) === L6` without using `self`.

### Buggy Code Location

- **File**: `dekker.scala`
- **Lines**: 116–121
- **Buggy Assertion**:
  ```scala
  val liveness_req_l2 = (pc(self) === L2) && (c(~self) === true.B) && !io.pause
  val liveness_resp_l6 = (pc(self) === L6)
  astRelaxedLiveness(liveness_req_l2, liveness_resp_l6, 10, "LA1_liveness_L2_to_CS")
  ```
- **Fix**: The response condition `(pc(self) === L6)` should be replaced with a condition that checks the same process that was selected at request time, not wherever `self` happens to be pointing at response time. For example, using a dedicated register to latch the request-time `self` value, or checking `(pc(0) === L6) || (pc(1) === L6)`.

### Waveform Evidence Summary

| Key Evidence | Value |
|---|---|
| Process 0 reaches L6 at time 130 | `dekker.pc_0 [2:0]` = `110` (L6) at 130 ns |
| But `self` at time 130 | `dekker.self` = `1` (pointing to process 1) |
| Process 1's PC at time 130 | `dekker.pc_1 [2:0]` = `100` (L4), not L6 |
| Failure reason | `pc(self)=pc(1)=L4 ≠ L6`, even though `pc(0)=L6` |
