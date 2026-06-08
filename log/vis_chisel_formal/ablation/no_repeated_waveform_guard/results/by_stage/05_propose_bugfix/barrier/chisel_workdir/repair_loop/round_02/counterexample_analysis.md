# Counterexample Analysis Report: `barrier_release_on_count2`

## 1. Verification Environment

- **Top module:** `barrier` (from `barrier.scala`)
- **Key components:**
  - Two-local-memory threads (`pc(0)`, `pc(1)`) implementing a 7-location state machine (L0–L6)
  - A shared 2-bit counter (`count`) tracking how many threads have reached the barrier
  - A shared release signal (`rel`)
  - A registered selector (`self`) that follows `io.select` with a 1-cycle delay
  - Inputs: `io.select` (selects active thread), `io.pause` (pauses thread at L0)
  - Outputs: `io.rel_out`, `io.self_out`, `io.pc0_out`, `io.pc1_out`, `io.count_out`
- **Barrier protocol:** When both threads arrive (count=2), the barrier releases (`rel=1`), then the thread in L4 resets `count` to 0 and sets `rel` back to `true`. Threads waiting in L6 exit to L5 when `rel` is true and then go back to L0.

## 2. Violated Assertion

- **Full assertion name (from waveform filename):** `barrier_release_on_count2`
- **Source file:** `barrier.scala`, line ~115
- **Code snippet:**

```scala
AssertProperty((count === 2.U) |-> Sequence(rel).delayRange(1, 3), None, None, Some("barrier_release_on_count2"))
```

- **Natural-language description:** Whenever `count` equals 2 (meaning both threads have arrived at the barrier), the `rel` signal must become true within 1 to 3 clock cycles.
- **Intent:** When the second thread arrives, the barrier should release promptly.

## 3. Waveform Information

- **Waveform file:** `verilog/extra_bench/barrier/barrier.barrier_release_on_count2.fst`
- **Time range:** 0 ns to 140 ns (14 cycles at 10 ns period)
- **Key time points:**

| Time (ns) | count | rel | self | pc(0) | pc(1) | select | pause |
|-----------|-------|-----|------|-------|-------|--------|-------|
| 70        | 10(2) | 1   | 1    | L3    | L6    | 1      | 0     |
| 100       | 10(2) | 1   | 1    | L3    | L1    | 1      | 0     |
| **110**   | 10(2) | **0** | 1   | L3    | L2    | 1      | 1     |
| 120       | 10(2) | 0   | 1    | L3    | L3    | 1      | 0     |
| 130       | 10(2) | 0   | 1    | L3    | L4    | 1      | 0     |
| 140       | 10(2) | 0   | 1    | L3    | L4    | 1      | 0     |

- **Critical observation at t=110:** `rel` drops from 1 to 0 while `count` is still 2. The assertion re-fires at t=110 because `count===2`, but `rel` stays 0 for the remaining trace.

## 4. Root Cause Analysis

### Bug location: `barrier.scala`, L1 state handler (around line 90–100)

```scala
}.elsewhen(pc(self) === Loc.L1.asUInt) {
    rel := false.B          // <--- BUG: unconditionally clears rel
    pc(self) := Loc.L2.asUInt
}
```

### Bug description

The **L1 state unconditionally clears `rel`** (`rel := false.B`), even when `count === 2` (meaning both threads have arrived at the barrier and the barrier release is in progress but not yet fully completed).

### Root cause category: **DUT bug**

The waveform trace shows the following sequence:

1. **Thread 0 reaches L2 at t=50:** self=0, pc(0)=L2, count=01. Thread 0 increments count to 2, sets rel=1, and advances to L3.

2. **Thread 0 stuck at L3 from t=70 onwards:** After t=60, `io_select` stays at 1, so `self` (registered version) stays at 1 from t=70 onwards. Thread 0 (selected when self=0) is **never active again**. It remains stuck in L3, where it would have transitioned to L4 (since count===2) and reset count to 0.

3. **Thread 1 cycles through L0→L1→L2→L3→L6→L5→L0 repeatedly.** At t=100, thread 1 is in L1. The L1 state executes `rel := false.B`, clearing the release signal at t=110.

4. **Assertion violation at t=110:** `count===2` is still true (never reset), `rel` has become 0, and the assertion fires again expecting `rel` to be true within 1–3 cycles. But `rel` stays 0 for the remainder of the trace.

### Why this is a DUT bug

The barrier design has a **fundamental flaw in the L1 state**: it unconditionally clears `rel`, but it should only do so after the barrier has been **fully released** (i.e., `count` has been reset to 0 in L4). When `count === 2`, the barrier release is active and `rel` should stay asserted until the releasing thread (in L4) resets count.

The L1 state should guard its `rel := false.B` assignment with a condition:

```scala
}.elsewhen(pc(self) === Loc.L1.asUInt) {
    when(count =/= 2.U) {       // Only clear rel if barrier is not active
      rel := false.B
    }
    pc(self) := Loc.L2.asUInt
}
```

This would prevent a thread from spuriously clearing the release signal while the barrier is still in the process of releasing.

### Root cause chain

1. Thread 0 arrives (L2→count=2→rel=1→L3)
2. `io_select` stays at 1 → thread 0 is never selected again → stuck in L3
3. Thread 1 cycles through L1, which unconditionally clears `rel`
4. `rel` becomes 0 while `count` is still 2
5. Assertion `barrier_release_on_count2` fires every cycle `count===2`, expects `rel` within 1–3 cycles, but `rel` stays 0

## Summary

| Category | Finding |
|----------|---------|
| Error type | **DUT bug** |
| Bug location | `barrier.scala`, L1 state handler (`rel := false.B`) |
| Bug nature | Unconditional `rel` clear in L1 even when barrier release is active (count=2) |
| Fix | Guard `rel := false.B` with `when(count =/= 2.U)` in L1 |
