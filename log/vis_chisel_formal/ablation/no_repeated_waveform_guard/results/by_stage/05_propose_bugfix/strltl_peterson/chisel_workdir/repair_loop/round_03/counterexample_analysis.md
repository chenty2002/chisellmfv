# Counterexample Analysis: interested0_eventually_cleared

## 1. Verification Environment

- **Top Module**: `Peterson` (generated from Chisel class `Peterson` in `mppLTLM1.scala`)
- **Key Components**:
  - `Peterson` - The main module implementing a multi-process mutual exclusion algorithm
  - `Buechi` - The LTL property monitor (Büchi automaton) tracking the assertion state
  - `resetCounter` - A counter that tracks cycles since reset
- **Signal Connections**:
  - `io_select` (3-bit input) - Selects which process/handler to execute next (via `self`)
  - `io_pause` (1-bit input) - Pauses the selected process when high
  - `io_pc0`, `io_pc1` (3-bit outputs) - Program counters for processes 0 and 1
  - Internal signals: `interested_0`..`interested_7` (8 interested flags), `turn`, `k`, `j_0`
- **Design Under Test**: Peterson's mutual exclusion algorithm for 2 processes (with 3-bit PC encoding), where each process cycles through states L0-L6 and the interested flag indicates a process is in the critical section or attempting to enter it.

## 2. Violated Assertion

- **Assertion Name (from waveform filename)**: `interested0_eventually_cleared`
- **Full Path**: `verilog/extra_bench/strltl_peterson/Peterson.interested0_eventually_cleared.fst`

**Code Snippet** (from `mppLTLM1.scala`, class `Peterson`):

```scala
astRelaxedLiveness(
  "interested0_eventually_cleared",
  Bool(true), // enable
  io_interested(0), // premise: interested_0 becomes true
  !io_interested(0), // conclusion: interested_0 becomes false
  50L // bound in cycles
)
```

- **Natural Language Property**: When process 0's `interested` flag becomes true (indicating it is entering or in the critical section), within the next 50 cycles the `interested` flag must eventually become false (indicating process 0 has left the critical section).
- **File Location**: `mppLTLM1.scala`, class `Peterson`

This is a **bounded liveness** assertion. It monitors: once the premise `interested(0) = true` becomes satisfied, within 50 clock cycles the conclusion `interested(0) = false` must hold (at least once). If the conclusion never occurs within 50 cycles of the premise, the assertion fails.

## 3. Waveform Information

- **Waveform File**: `verilog/extra_bench/strltl_peterson/Peterson.interested0_eventually_cleared.fst`
- **Waveform Duration**: 54 cycles (0 ns → 540 ns)
- **Key Time Points** (all in nanoseconds):

| Time (ns) | Cycle | Event | Value |
|-----------|-------|-------|-------|
| 0 | 0 | Reset/initial state | interested_0 = 0, pc_0 = 000 (L0), self = 000 |
| 20 | 2 | **Premise fires**: interested_0 becomes 1 | interested_0 = 1, pc_0 = 010 (L2), self = 010 |
| 190 | 19 | pc_0 enters L6 | pc_0 = 110 (L6), interested_0 = 1, self = 100 |
| 520 | 52 | **50-cycle deadline** since premise | interested_0 = 1 (still true) |
| 530 | 53 | **Assertion fails**: interested0_eventually_cleared = 0 | interested_0 = 1, pc_0 = 110, pc_1 = 110, self = 100, io_pause = 0 |

**Critical signal values at failure point (time = 530 ns)**:
- `Peterson.interested_0` = 1 (assertion conclusion not satisfied)
- `Peterson.pc_0 [2:0]` = 110 (L6 - exiting critical section)
- `Peterson.pc_1 [2:0]` = 110 (L6 - both processes at L6 simultaneously! mutual exclusion violated)
- `Peterson.self [2:0]` = 100 (process 4 is selected for execution, NOT process 0)
- `Peterson.io_pause` = 0 (not paused - process 4 is executing)
- `Peterson.io_select [2:0]` = 100

## 4. Root Cause Analysis

### Root Cause Category: **Setup Error**

### The Issue

The root cause is **insufficient environment constraints** on the `io_select` and `io_pause` inputs. These inputs are unconstrained in the formal verification test harness, allowing the solver to arbitrarily schedule which process executes and whether execution is paused.

### Detailed Analysis

**Process 0's lifecycle in the counterexample:**

1. **Cycle 2** (time 20 ns): Process 0 becomes interested (`interested_0` goes from 0 to 1). This triggers the assertion's premise. The deadline to clear `interested_0` is 50 cycles later (cycle 52, time 520 ns).

2. **Cycle 19** (time 190 ns): Process 0's PC reaches **L6** (value `110`). At L6, the algorithm would normally:
   - Execute the L6 handler which clears `interested(0)`
   - The handler only fires when `self` selects process 0 AND `io_pause` is low

3. **Cycle 19 → 53** (time 190 → 530 ns): Process 0 is stuck at L6 for 34+ cycles because:
   - Whenever `io_pause = 0` (execution is active), `self` is **never** set to 0 (process 0)
   - Examples: at time 190, `self = 100` (process 4); at time 370, `self = 001` (process 1); etc.
   - Whenever `self = 0`, `io_pause = 1` (execution is paused), preventing the handler from firing
   - This is a **systematic starvation** of process 0 by the input pattern

4. **Cycle 53** (time 530 ns): The assertion fails because `interested_0` is still 1, 51 cycles after becoming true.

### Why This is a Setup Error (Not a DUT Bug)

The core Peterson algorithm logic (`mppLTLM1.scala`) is correctly implemented:
- The L6 handler correctly sets `j_0 = 0` and then conditionally clears `interested(0)`
- The PC transitions follow the correct sequence: L0 → L1 → L2 → L3 → L4 → L5 → L6
- The algorithm itself does not contain a bug

### Why This is Not an Assertion Error

The bounded liveness property "when interested_0 becomes true, it must clear within 50 cycles" is a valid correctness property for Peterson's algorithm under **fair scheduling**. The bound of 50 cycles is reasonable (the algorithm should need only a few cycles to exit the critical section once scheduled).

### Fix Required

The verification test harness needs **fairness constraints** (environment assumptions) to ensure that:
1. **Weak fairness on process execution**: Every process whose PC is non-idle must eventually be selected by `io_select` when `io_pause` is low
2. **Progress when at L6**: When a process is at L6, it must eventually be scheduled to complete the exit from the critical section

These constraints should be added to the `TestTop` wrapper as formal assumptions (e.g., `assume` statements in the Verilog or `when` constraints in the Chisel test harness) preventing the solver from arbitrarily starving process 0 while its PC is stuck at L6.

**Example constraint**: "If process 0's PC is at L6 and `io_pause` is low, eventually `self` must select process 0" — this ensures the L6 handler can execute and clear the `interested` flag.

### Buggy Code Location

The issue is **not** in the DUT itself (`mppLTLM1.scala` class `Peterson`), but in the **missing environment constraints** of the verification test harness (likely in a `TestTop` wrapper or similar setup code, which is not present in the supplied source files). The `io_select` and `io_pause` inputs need to be constrained to provide fair scheduling.
