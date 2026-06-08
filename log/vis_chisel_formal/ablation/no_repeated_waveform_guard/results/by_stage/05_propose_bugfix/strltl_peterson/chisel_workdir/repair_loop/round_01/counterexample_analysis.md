# Counterexample Analysis: `pc0_entry_to_cs_progress` Assertion Failure

## 1. Verification Environment

- **Top Module**: `Peterson` (from `mppLTLM1.scala`)
- **Key Components**:
  - `Peterson` — Main module implementing the Marelly-Pnueli (MPP) N-process Peterson mutual exclusion algorithm with 3 processes (IDs 0, 1, 2) and a "hi-process" HIPROC (ID 7)
  - `Buechi` — Helper automaton for fairness/SCC tracking
- **Design Under Test**: A multi-process mutual exclusion protocol where processes compete for a critical section (L6). Each process has a program counter (`pc(i)`), an `interested` flag, a `j` register for contention level, and a shared `turn` variable.
- **Selector**: `self` is driven by `io.select` input. The protocol only advances `pc(self)` each cycle.

## 2. Violated Assertion

- **Assertion Name**: `pc0_entry_to_cs_progress`
- **Full Path**: `Peterson.pc0_entry_to_cs_progress`
- **Waveform File**: `Peterson.pc0_entry_to_cs_progress.fst`
- **File Location**: `mppLTLM1.scala`, lines 281–286

### Code Snippet
```scala
astRelaxedLiveness(
    (self === 0.U) && (pc(0) === Loc.L1),
    (pc(0) === Loc.L6) || (pc(0) === Loc.L7),
    40,
    "pc0_entry_to_cs_progress"
)
```

### Property Description
When process 0 is selected (`self === 0`) and enters the entry protocol (L1 → interested is set to true), it should reach either the critical section (L6) or the exit state (L7) within 40 clock cycles.

## 3. Waveform Information

- **Waveform File**: `verilog/extra_bench/strltl_peterson/Peterson.pc0_entry_to_cs_progress.fst`
- **Time Range**: 0 ns → 430 ns (43 cycles at 10 ns/cycle)
- **Trigger Point**: time = 10 ns (cycle 1), `self=0` and `pc(0)=L1`
- **Failure Point**: 40 cycles later (~410 ns), `pc(0)` is still at L5 (101), never having reached L6 or L7

### Critical Signal Timeline

| Time (ns) | self | pc(0) | pc(1) | j(0) | interested(1) | turn | Event |
|-----------|------|-------|-------|------|---------------|------|-------|
| 0         | 0    | L0    | L0    | 0    | 0             | 0    | Initial state |
| 10        | 0    | **L1**| L0   | 0    | 0             | 0    | **Trigger**: self=0, pc(0)=L1 |
| 20        | 7    | L2    | L0    | 0    | 0             | 7    | interested(0) ← 1, turn ← HIPROC |
| 50        | 1    | L3    | L0    | 0    | 0             | 7    | — |
| **60**    | **0**| **L3**| L0   | —    | 0             | 7    | **Bug Site**: self=0, pc(0)=L3 ⇒ **j(0) := 0+1 = 1** |
| 70        | 1    | L4    | L1    | **1**| 0             | 7    | j(0)=1 confirmed, pc(0)←L4 |
| 80        | 7    | L4    | L2    | 1    | **1**         | 7    | interested(1) ← 1 |
| 90        | 4    | L4    | L2    | 1    | 1             | 6    | turn ← 6 |
| 130       | 7    | L5    | L2    | 1    | 1             | 6    | L4 ⇒ L5 because j(0)=1 ≠ 0 |
| 150–390   | —    | L4↔L5 | L2–L4| 1    | 1             | 0–4  | **Oscillation**: pc(0) cycles L4→L5→L4 forever |

Process 0 is still at L5 at the final observation (time = 390 ns), well past the 40-cycle bound.

## 4. Root Cause Analysis

### Bug Location

- **File**: `mppLTLM1.scala`
- **Lines**: 223–230 (state `Loc.L3`)
- **Module**: `Peterson`
- **Bug Type**: **DUT Bug** — Algorithm implementation error

### Bug Description

In state L3, the Peterson protocol sets `j(self)` to determine the victim/contention level:

```scala
is(Loc.L3) {
    when(self === HIPROC.U) {
        j(self) := 0.U       // Only works when self=7 (HIPROC)
    }.otherwise {
        j(self) := self + 1.U  // j(0) = 1, j(1) = 2, j(2) = 3, etc.
    }
    pc(self) := Loc.L4
}
```

When `self = 0` (process 0 is selected), the code executes `j(0) := 0 + 1 = 1`. This value is **permanently latched** — j(0) is set to 1 and **can never be changed back to 0** because:

1. **The HIPROC path** (`self === HIPROC.U`) sets `j(self) := 0.U`, but self must be 7 (HIPROC) for this to fire, and in that case it writes `j(7) := 0` — **not** `j(0) := 0`.
2. **No other path** in the state machine overwrites `j(0)`.
3. Process 0 can never return to L3 (the only state that writes `j`) without first going through L6→L7→L0→L1→L2, but it **cannot reach L6** because `j(0) = 1 ≠ 0`.

### Why the Assertion Fails

In state L4, the transition to critical section requires:

```scala
is(Loc.L4) {
    when(j(self) === self) {    // For self=0: j(0) === 0 ?
        pc(self) := Loc.L6      // Enter critical section
    }.otherwise {
        pc(self) := Loc.L5      // Wait
    }
}
```

Since `j(0) = 1` and `self = 0`, the condition `j(0) === 0` is **always false**. Process 0 is permanently diverted to L5. At L5, it oscillates back to L4 when no contention exists (`interested(k) && turn === k` is false), but this infinite loop never produces `j(0) === 0`.

### Evidence from Waveform

| Time | Signal | Value | Meaning |
|------|--------|-------|---------|
| 60 ns | `Peterson.self [2:0]` | `000` | self=0 |
| 60 ns | `Peterson.pc_0 [2:0]` | `011` | pc(0)=L3 |
| 70 ns | `Peterson.j_0 [2:0]` | `001` | j(0)=1 (written in previous cycle) |
| 70–130 ns | `Peterson.pc_0 [2:0]` | `100` | pc(0)=L4, j(0)=1 ≠ 0, goes to L5 |
| 130–390 ns | `Peterson.pc_0 [2:0]` | `101→100→101…` | Oscillating L4↔L5 forever |

**Process 0 never reaches L6 (critical section) or L7 (exit)** throughout the entire 430 ns trace.

### Root Cause Classification

✅ **DUT Bug** — The Peterson algorithm implementation has a flaw in the L3 state logic: `j(self) := self + 1.U` prevents self-identity checks at L4 from ever succeeding for process 0, causing a permanent livelock.

### Suggested Fix

The L3 state should allow `j(0)` to eventually equal 0. In the classic Peterson algorithm, the victim selection should be relative or modulo-based. A possible fix:

```scala
is(Loc.L3) {
    j(self) := (self + 1.U) % NR.U  // Wrap around: j(0)=1, but let other mechanisms clear it
    pc(self) := Loc.L4
}
```

However, the deeper issue is that `j(0)` must eventually **equal 0** for process 0 to enter L6. This requires the algorithm to provide a mechanism — perhaps through the HIPROC path — to reset each process's `j` to 0 when appropriate. The current HIPROC path (`j(7) := 0`) targets the wrong register.
