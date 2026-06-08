# Counterexample Analysis Report: `active_thread_progress`

## 1. Verification Environment

- **Top module**: `barrier` (a 2-thread barrier synchronization unit)
- **Source file**: `barrier.scala` (lines 12-112)
- **Design structure**:
  - `barrier` is a Chisel `Module` with a state machine implementing a 2-thread barrier
  - It has registers: `rel` (release flag), `self` (latched thread selector), `pc` (2-element vector of program counters for threads 0 and 1), `count` (barrier participant counter)
  - Inputs: `io_select` (selects which thread runs), `io_pause` (pauses thread execution)
  - Outputs: `io_rel_out`, `io_self_out`, `io_pc0_out`, `io_pc1_out`, `io_count_out`
- **Key components**: The state machine supports 7 states (L0–L6) defined in the `Loc` ChiselEnum. Threads move through L0→L1→L2→L3→L4/L6→L5→L0. The barrier releases when `count` reaches 2 (both threads have passed through L2).

## 2. Violated Assertion

- **Full assertion name**: `active_thread_progress`
- **Assertion source** (`barrier.scala`, lines 85–95):

```scala
// Bounded liveness: When a thread is selected and not paused and not stuck
// waiting for the barrier (L6), it returns to L0 within 10 cycles.
AssertProperty(
  Sequence(!io.pause && pc(self) =/= Loc.L6.asUInt) |->
    Sequence(pc(self) === Loc.L0.asUInt).delayRange(0, 10),
  None, None, Some("active_thread_progress")
)
```

- **Generated Verilog equivalent** (`generated/barrier.sv`, line ∼56):
```verilog
active_thread_progress: assert property (
  ~io_pause & _GEN != 3'h6 |-> ##[0:10] _GEN_0
);
```
where `_GEN = self ? pc_1 : pc_0` and `_GEN_0 = _GEN == 3'h0`.

- **Natural language description**: Whenever the selected thread (identified by the latched `self` signal) is NOT paused and NOT stuck at the barrier (L6), that thread's program counter must reach L0 (the idle state) within 0 to 10 clock cycles.

## 3. Waveform Information

- **Waveform file**: `verilog/extra_bench/barrier/barrier.active_thread_progress.fst`
- **Key time points**:

| Time (ns) | Cycle | pc0 | pc1 | count | self | io_select | io_pause | rel | Event |
|-----------|-------|-----|-----|-------|------|-----------|----------|-----|-------|
| 0 | 0 | L0(000) | L0(000) | 00 | 0 | 0 | 0 | 0 | Reset, assertion fires (antecedent holds), consequent matches immediately |
| 10 | 1 | L1(001) | L0(000) | 00 | 0 | 0 | 0 | 0 | **Antecedent fires**: !pause & pc0≠L6. Window opens: [10, 110] |
| 20 | 2 | L2(010) | L0(000) | 00 | 0 | 0 | 1 | 0 | L2: count incremented to 01 |
| 30 | 3 | L3(011) | L0(000) | 01 | 0 | 0 | 1 | 0 | L3: count=01≠2 → goes to L6 |
| 40 | 4 | L6(110) | L0(000) | 01 | 0 | 0 | 0 | 0 | L6: rel=0 → stuck forever |
| ... | ... | L6 | L0 | 01 | 0 | 0 | 0 | 0 | No progress |
| 100 | 10 | L6 | L0 | 01 | 0 | 0 | 0 | 0 | Still stuck at L6 |
| **110** | **11** | **L6** | **L0** | **01** | **0** | **0** | **0** | **0** | **Assertion failure: window [10,110] expires, pc never reached L0** |

- **Failure signal**: `barrier.active_thread_progress` transitions from 1→0 at time 110 ns.

## 4. Root Cause Analysis

### Bug Classification: **Setup Error (Incorrect Top Module Configuration)**

### Root Cause: `io_select` is perpetually 0

The `io_select` input is never constrained by the formal verification testbench. The waveform shows `barrier.io_select` remains `0` at every time point (0–110 ns). Since `self := io_select` latches the selection on every cycle, `self` is also always `0`, meaning **only thread 0 ever executes**.

### Detailed Failure Mechanism

1. **Thread 0 progresses** through L0→L1→L2 (cycles 0→1→2). At L2, `count` increments from 0 to 1.
2. **Thread 1 never executes** because `io_select` is always 0. Thread 1 stays at L0 forever.
3. **At L3** (cycle 3), `count` is 1, which does not equal 2, so thread 0 takes the `otherwise` branch and transitions to L6 (barrier wait state).
4. **At L6**, thread 0 waits for `rel` to become true. However, `rel` can only be asserted via L4 (which requires `count === 2.U`), and `count` can only reach 2 if both threads pass through L2. Since thread 1 is never selected, `count` stays at 1 forever.
5. **The assertion window** opened at cycle 1 (time 10) when `pc(self)=L1 ≠ L6` and `!io_pause`. The window extends 10 cycles to cycle 11 (time 110), by which point thread 0 is stuck at L6 and can never reach L0.
6. **Assertion failure** at time 110 ns when the 10-cycle delay window expires.

### Why the Fix is a Setup Change

The barrier design itself is functionally correct for its intended use case (alternating threads). The state machine correctly implements a 2-thread barrier synchronization primitive. The liveness assertion `active_thread_progress` is also reasonable — a thread not at L6 should return to L0 within 10 cycles.

The problem is that the **formal verification environment** does not constrain `io_select` to alternate between threads. Without such a constraint, only one thread executes, and the barrier can never complete because it requires participation from both threads to reach `count=2` and assert `rel`.

**Recommended fix**: Add a formal constraint (assumption) that `io_select` eventually selects both threads, for example:
- `assume property (s_eventually io_select)` — thread 1 eventually gets selected
- Or an alternating constraint ensuring fair scheduling between threads

Without fair scheduling of `io_select`, the barrier is provably deadlocked for single-thread execution, making the liveness assertion impossible to satisfy.
