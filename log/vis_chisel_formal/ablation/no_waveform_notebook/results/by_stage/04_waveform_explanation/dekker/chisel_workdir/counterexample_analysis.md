# Counterexample Analysis Report: `dekker.c0_true_when_idle_or_exit`

## 1. Verification Environment

- **Top Module**: `dekker` (a Chisel model of Dekker's mutual exclusion algorithm for two processes)
- **Key Components**:
  - `c` (Vec(2)) — "want flags" for each process; `c(i) = false` means process i intends to enter the critical section
  - `turn` (1-bit) — turn indicator for tie-breaking
  - `self` (1-bit) — which process is currently executing (selected via `io.select`)
  - `pc` (Vec(2) of 3-bit) — program counters for each process, values L0–L6
  - `io.pause` — pause input that stalls the selected process
- **Design Under Test**: State machine implementing Dekker's algorithm with 7 locations per process

## 2. Violated Assertion

- **Assertion Name**: `c0_true_when_idle_or_exit`
- **Waveform Filename**: `dekker.c0_true_when_idle_or_exit.fst`
- **Code Location**: `dekker.scala`, **line 118**

```scala
// Safety 4: c-flag consistency when process is not contending.
// When a process is at L0 (idle) or L6 (just exited CS), its c flag must be true
// (indicating it does NOT want to enter the critical section).
fvAssert(!(pc(0) === L0 || pc(0) === L6) || c(0), "c0_true_when_idle_or_exit")
```

- **Natural Language Description**: If process 0's program counter is at L0 (idle) or L6 (just exited the critical section), then process 0's c-flag must be true (i.e., the process is not contending for the critical section).

## 3. Waveform Information

- **Waveform File**: `verilog/extra_bench/dekker/dekker.c0_true_when_idle_or_exit.fst`
- **Time Range**: 0 ns → 50 ns (5 cycles, 10 ns per cycle)
- **Clock**: positive edges at 0, 10, 20, 30, 40 ns

### Key Time Points

| Time (ns) | pc(0) | pc(1) | c(0) | c(1) | self | turn | io_pause | Assertion |
|-----------|-------|-------|------|------|------|------|----------|-----------|
| 0         | L0(0) | L0(0) | 1    | 1    | 0    | 0    | 0        | 1 (pass)  |
| 10        | L1(1) | L0(0) | 1    | 1    | 0    | 0    | 1        | 1 (pass)  |
| 20        | L2(2) | L0(0) | 0    | 1    | 0    | 0    | 0        | 1 (pass)  |
| 30        | L5(5) | L0(0) | 0    | 1    | 0    | 0    | 0        | 1 (pass)  |
| **40**    | **L6(6)** | L0(0) | **0** | 1    | 0    | 0    | 0        | **0 (FAIL)** |

- **Failure Point**: Time = **40 ns** — when the assertion signal `c0_true_when_idle_or_exit` transitions from 1 to 0.

## 4. Root Cause Analysis

### Bug Location

- **File**: `dekker.scala`
- **Lines 75–78** (L5 state handler) and **Lines 80–84** (L6 state handler)

### Buggy Code

```scala
is(L5) {                               // Line 75: Critical section
  when(!io.pause) {                     // Line 76
    pc(self) := L6                      // Line 77: Advance to exit state
  }                                     // Line 78: ❌ c(self) is NOT restored to true here
}                                       // Line 79
is(L6) {                               // Line 80: Exit state
  c(self) := true.B                     // Line 81: Restore c-flag — but this takes effect NEXT cycle
  turn := ~self                         // Line 82
  pc(self) := L0                        // Line 83: Return to idle
}                                       // Line 84
```

### Description of the Bug

The bug is a **design bug (DUT bug)** in the state machine transition logic.

**The Problem**: When process 0 leaves the critical section (L5) and enters the exit state (L6), its c-flag `c(0)` is still `false` (set earlier at L1 when the process declared intent to enter the CS). The c-flag is only restored to `true` in the L6 state handler, but since `c(self) := true.B` is a **next-state assignment**, the actual register update happens on the *next* clock edge — **one cycle after** pc has already transitioned to L6.

**Timeline of Events**:

1. **Time 10–20**: At L1, `c(0) := false.B` is issued (process 0 declares intent to enter CS).
2. **Time 30**: pc(0) reaches L5 (critical section) with `c(0) = 0`.
3. **Time 30–40**: At L5, since `!io.pause` is true, the next-state logic sets `pc(0) := L6`. However, **`c(0)` is not set to `true.B` in the L5 handler** — it remains `false`.
4. **Time 40**: pc(0) becomes L6, but `c(0)` is still `0` (the L6 handler's `c(self) := true.B` will only take effect at the *next* clock edge at time 50).
5. **Time 40 (assertion check)**: The assertion sees `pc(0) = L6` and `c(0) = 0`, which violates the property `!(pc(0) === L0 || pc(0) === L6) || c(0)`.

### Evidence from Waveform

- At time **40 ns**: `pc_0 = 110` (L6 = 6), `c_0 = 0`, and `c0_true_when_idle_or_exit = 0` (assertion fails).
- The signal `c_0` was set to `0` at time **20 ns** (when pc(0) was at L1) and was never restored to `1` before entering L6.
- `c_0` only becomes `1` at the next clock edge (expected at time 50, beyond the 5-cycle trace window).

### Why This Causes the Assertion Failure

The assertion requires `c(0) = true` whenever `pc(0) = L6` (just exited CS). In a properly designed Dekker implementation, the process should declare itself non-contending (`c = true`) **before or simultaneously with** arriving at the exit state. The current design defers the `c := true` assignment until the L6 handler, which creates a one-cycle window where `pc(0) = L6` but `c(0)` is still `false`.

### Proposed Fix

Add `c(self) := true.B` in the L5 handler, **before** transitioning to L6:

```scala
is(L5) {
  when(!io.pause) {
    c(self) := true.B    // ← Restore c-flag before exiting CS
    pc(self) := L6
  }
}
```

This ensures that when the process reaches L6 at the next clock edge, `c(0)` is already `true`, satisfying the assertion.
