# Counterexample Analysis Report: Peterson.p0_active_interest_progress

## 1. Verification Environment

### Top Module
- **Module**: `Peterson` (Chisel class extending `Module with Formal`)
- **Generated Verilog**: `chisel/extra_bench/strltl_peterson/generated/`
- **Source**: `mppLTLM1.scala`

### Key Components and Connections
| Component | Role |
|-----------|------|
| `Peterson` | Main DUT — N-process Peterson mutual exclusion protocol (8 processes) |
| `Buechi` | Monitor module — LTL property checker with Büchi automaton |
| `resetCounter` | Reset counter for formal verification |

### Interface Signals
| Signal | Width | Description |
|--------|-------|-------------|
| `io.select` | 3 bits | Process selector (0–7), values >7 map to 0 |
| `io.pause` | 1 bit | Pause signal for progress assertions |
| `io.pc0/1/2` | 3 bits | Exposed program counter for processes 0,1,2 |
| `io.interested0` | 1 bit | Exposed interested flag for process 0 |
| `io.turn` | 3 bits | Shared turn variable |
| `io.fair0–3, io.scc` | 1 bit each | Büchi automaton outputs |

### Design Under Test Description
The design implements an N-process Peterson mutual exclusion algorithm for 8 processes (0–7). Each process follows a state machine through locations L0–L7:
- **L0**: Idle, waiting to enter
- **L1**: Entry point, sets interested flag
- **L2**: Sets the turn variable
- **L3**: Computes j(self) — the process to compete against
- **L4**: Checks if j(self) === self (bypass check)
- **L5**: Waiting loop — waits until interested(j) is false or turn != j
- **L6**: Critical section
- **L7**: Exit, clears interested flag

---

## 2. Violated Assertion

### Assertion Name
`p0_active_interest_progress` (from waveform filename `Peterson.p0_active_interest_progress.fst`)

### Code Snippet
```scala
// File: mppLTLM1.scala, Lines 336–341
astRelaxedLiveness(
  interested(0) && pc(0) =/= Loc.L0 && pc(0) =/= Loc.L7 && !io.pause,
  pc(0) === Loc.L7,
  80,
  "p0_active_interest_progress"
)
```

### Property Description
**Trigger condition**: Process 0 is interested (active in the protocol), not in idle (L0) or exit (L7) state, and the pause signal is not asserted.

**Goal condition**: Process 0 eventually reaches L7 (critical section exit).

**Time bound**: The goal must be achieved within 80 clock cycles after the trigger condition becomes true.

In natural language: *"If process 0 is interested and actively participating in the protocol (not idle, not finished), it should reach the critical section exit within 80 cycles."*

### File Location
- **File**: `mppLTLM1.scala`
- **Lines**: 336–341

---

## 3. Waveform Information

### Waveform File
- **Full Path**: `verilog/extra_bench/strltl_peterson/Peterson.p0_active_interest_progress.fst`
- **Duration**: 83 cycles (0 ns to 830 ns)
- **Clock Period**: 10 ns

### Key Time Points
| Time (ns) | Event |
|-----------|-------|
| 0 | Initial state. All processes at L0, interested(0)=0, j(0)=0 |
| 10 | **pc(0) → L1**, interested(0) → 1. Process 0 enters the protocol. |
| 20 | **pc(0) → L2**. Assertion trigger condition becomes true (interested(0)=1, pc(0)!=L0/L7, !pause). `pending` goes to 1. |
| 30 | pc(0) → L3, self=5 (nondeterministic) |
| 40 | self → 0 (returns to process 0) |
| 50 | **pc(0) → L4**, **j(0) → 1** (set at L3: `j(self) := self + 1`). |
| 60 | pc(0) → L5 (L4: `j(0) === 0`? No → go to L5) |
| 80 | pc(0) → L4 (L5: condition false → back to L4) |
| 80–820 | **pc(0) oscillates between L4→L5→L4→L5... stuck forever** |
| 820 | **Assertion fires**: timer reaches 80 cycles since trigger (20ns + 80×10ns = 820ns). pc(0)=L5, never reached L7. |

### Critical Signal Values at Failure Point (820 ns)
| Signal | Value | Meaning |
|--------|-------|---------|
| `pc(0)` | L5 (101) | Stuck in L4↔L5 loop |
| `pc(1)` | L4 (100) | Process 1 also stuck |
| `pc(2)` | L4 (100) | Process 2 also stuck |
| `interested(0)` | 1 | Still interested |
| `interested(1)` | 1 | Still interested |
| `j(0)` | 1 | Never changes, stuck at 1 |
| `turn` | 6 (110) | Turn is 6 |
| `k` | 1 | k = j(0) = 1 |
| `pending` | 1 | Assertion pending |
| `timer` | 50 (010000) | More than 80 cycles elapsed |

---

## 4. Root Cause Analysis

### Bug Category: **Bug in the Original Design**

### Buggy Code Location
- **File**: `mppLTLM1.scala`
- **Lines**: 222–236
- **Module**: `Peterson` class

### Code Excerpt
```scala
// Lines 222-228: L3 — Computes j(self)
is(Loc.L3) {
  when(self === HIPROC.U) {     // HIPROC = 7
    j(self) := 0.U
  }.otherwise {
    j(self) := self + 1.U       // For process 0: j(0) = 1
  }
  pc(self) := Loc.L4
}

// Lines 230-236: L4 — Bypass check
is(Loc.L4) {
  when(j(self) === self) {      // j(0) === 0? → j(0)=1, so NEVER TRUE
    pc(self) := Loc.L6          // Critical section — never reached by process 0
  }.otherwise {
    pc(self) := Loc.L5          // Wait — always taken by process 0
  }
}
```

### Description of the Bug

The Peterson implementation has a **fundamental design flaw** in the L4 state transition logic. The `j` register is set at L3 to point to the next process in the chain:

- For process 0: `j(0) = 1`
- For process 1: `j(1) = 2`
- ...
- For process 7: `j(7) = 0`

The condition at L4, `j(self) === self`, checks whether `j` points to the process itself — which would mean the process has no one to compete against and can enter the critical section directly.

**However, for process 0 (and in fact ALL processes), `j(self) === self` is NEVER true** because:
- `j(0) = 1 ≠ 0` → `1 === 0` is always false
- `j(1) = 2 ≠ 1` → `2 === 1` is always false
- ...and so on for all processes

### Evidence from Waveform

**Trace of pc(0):**
```
Time  | pc(0) | Event
0     | L0    | Initial idle
10    | L1    | Enter protocol
20    | L2    | Set turn
30    | L3    | Compute j(0) = 1
50    | L4    | Check: j(0) === 0? NO (1 ≠ 0) → go to L5
60    | L5    | Check: interested(1) && turn===1? → go back to L4
80    | L4    | Check: j(0) === 0? NO → go to L5
...   | L4↔L5 | **Infinite oscillation between L4 and L5 forever**
```

**Trace of j(0):**
```
Time  | j(0)
0     | 0       (initial)
50    | 1       (set at L3, self=0 → j(0)=0+1=1)
50+   | 1       (never changes again)
```

**Trace of pending (assertion monitor):**
- Time 20: pending goes to 1 (trigger condition met)
- Time 20–820: pending stays 1 for 80 cycles
- Time 820: assertion fires — L7 never reached

### Why the Assertion Fails

The assertion `p0_active_interest_progress` requires process 0 to reach L7 within 80 cycles of being interested and active. However:

1. **Process 0 gets stuck at L4**: The condition `j(0) === 0` (line 231) is always false because `j(0) = 1`.
2. **Process 0 enters L4→L5 loop**: Every cycle moves between L4 and L5, never reaching L6 or L7.
3. **No mechanism to break the loop**: The `j(0)` register is set once at L3 and never modified again. There is no way for process 0 to satisfy the `j(self) === self` condition.

### Impact on All Processes
This bug affects **all 8 processes**, not just process 0. Since each process has `j(i) = i+1 (mod 8)` and none satisfies `j(i) === i`, **no process can ever reach the critical section (L6)** through the normal L4 path. The algorithm as implemented is fundamentally deadlocked for all participants, making mutual exclusion trivially satisfied but liveness impossible.

### Required Fix
The `j(self) === self` condition at L4 is incorrect for this chain-based Peterson design. The algorithm needs either:
1. A multi-level tournament structure where j points to competition levels, not processes, OR
2. A different progression condition at L4 that doesn't require `j(self) === self`, OR
3. A different j computation scheme that eventually wraps back to self for some processes in certain conditions.

---

## 5. Summary

| Aspect | Detail |
|--------|--------|
| **Category** | **Bug in the Original Design (dut_bug)** |
| **Root Cause** | L4 transition condition `j(self) === self` is never true because `j(i) = i+1` for all i |
| **Effect** | Process 0 (and all processes) are stuck in an infinite L4↔L5 loop |
| **Assertion Violation** | Process 0 never reaches L7 within 80 cycles of becoming interested |
| **Fix Location** | `mppLTLM1.scala`, lines 230–236 (L4 state) and/or lines 222–228 (L3 j computation) |
| **Severity** | Critical — blocks all progress for all processes in the protocol |
