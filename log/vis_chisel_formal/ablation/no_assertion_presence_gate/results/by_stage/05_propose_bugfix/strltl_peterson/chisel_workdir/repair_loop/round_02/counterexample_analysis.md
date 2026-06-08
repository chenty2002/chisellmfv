# Counterexample Analysis: `entering_p0_interested`

## 1. Verification Environment

- **Top module:** `Peterson` (from `mppLTLM1.scala`)
- **Design under test:** Peterson's mutual exclusion algorithm with L0–L7 state machine for 8 processes (0–7), simplified for formal verification
- **Key components:**
  - `Peterson`: Main module with FSM controlling 8 processes via a rotating `self` pointer
  - `Buechi`: LTL property monitor (Büchi automaton) for fairness/liveness verification
- **Inputs:** `io_select` (3-bit, selects current process to execute), `io_pause` (stalls process 0/1/2 at L6)
- **Assertion type:** Safety property checking protocol consistency using `past()` operator

## 2. Violated Assertion

- **Assertion name:** `entering_p0_interested` (from waveform filename `Peterson.entering_p0_interested.fst`)
- **Code snippet (lines 259–261):**

```scala
past(pc(0), 1) { prevPc0 =>
    fvAssert(!(prevPc0 === Loc.L1) || interested(0), "entering_p0_interested")
}
```

- **Natural language description:** If process 0's program counter was at location L1 (the "entry" state) in the previous cycle, then its `interested` flag must be asserted in the current cycle. This verifies that when process 0 starts the entry protocol at L1, it sets `interested(0) = true` by the next cycle when it transitions to L2.
- **File location:** `mppLTLM1.scala`, lines 259–261

## 3. Waveform Information

- **Waveform file:** `verilog/extra_bench/strltl_peterson/Peterson.entering_p0_interested.fst`
- **Duration:** 3 clock cycles (0 ns → 30 ns)
- **Clock period:** 10 ns (rising edges at 0, 10, 20 ns)

### Key signal values at critical time points:

| Signal | t=0 (cycle 0) | t=10 (cycle 1) | t=20 (cycle 2) | t=25 (cycle 2 half) |
|--------|:-------------:|:--------------:|:--------------:|:-------------------:|
| `io_select [2:0]` | 100 (4) | 111 (7) | 111 (7) | 111 (7) |
| `io_pause` | 0 | 1 | 1 | 1 |
| `self [2:0]` | 000 (0) | 100 (4) | 111 (7) | 111 (7) |
| `pc_0 [2:0]` | 000 (L0) | 001 (L1) | 001 (L1) | 001 (L1) |
| `interested_0` | 0 | 0 | 0 | 0 |
| `pc_4 [2:0]` | 000 (L0) | 000 (L0) | 000 (L0) | — |
| `pc_7 [2:0]` | 000 (L0) | 000 (L0) | 000 (L0) | — |
| **`entering_p0_interested`** | **1** | **1** | **0 (FAIL)** | **0 (FAIL)** |

### Failure timing:

- At **t=10** (cycle 1 rising edge): `pc(0)` = L1 (001), but `interested(0)` = 0. The assertion passes at this point because it checks `prev(pc(0))` — i.e., the value of `pc(0)` at the *previous* cycle (t=0), which was L0, so the implication `!(prevPc0 === L1)` is true (vacuously satisfied).
- At **t=20** (cycle 2 rising edge): `prevPc0` = pc(0) at t=10 = L1 (001). So the implication requires `interested(0) = true`. But `interested(0) = 0`. **Assertion fails.**

## 4. Root Cause Analysis

### Bug location

**File:** `mppLTLM1.scala`, **lines 231–234** (FSM handler for `Loc.L1`):

```scala
is(Loc.L1) {
    interested(self) := true.B
    pc(self) := Loc.L2
}
```

Combined with the L0 handler (lines 225–230):

```scala
is(Loc.L0) {
    when(io.pause) {
        pc(self) := Loc.L0
    }.otherwise {
        pc(self) := Loc.L1
    }
}
```

### Description of the bug

The FSM separates the **entry decision** (L0→L1 transition) from the **interest assertion** (L1→L2 transition with `interested(self) := true.B`) into two different clock cycles. This creates a **race hazard**: if the `self` pointer changes between the L0→L1 transition and the L1→L2 transition, the process is left "orphaned" at L1 with its `interested` flag still false.

### Execution trace showing the bug

1. **Cycle 0 (t=0, rising edge):** `self` = 0, `io_pause` = 0
   - `pc(0)` = L0 → the L0 handler fires
   - Since `io_pause` = 0, `pc(0)` transitions from L0 to L1
   - `interested(0)` remains `false` (it is NOT set during L0→L1)

2. **Cycle 1 (t=10, rising edge):** `self` = 4 (changed!), `io_pause` = 1
   - `pc(0)` = L1, but since `self` = 4, the FSM handles `pc(4)`, NOT `pc(0)`
   - `pc(4)` = L0, and since `io_pause` = 1, `pc(4)` stays at L0
   - `pc(0)` remains stuck at L1, `interested(0)` remains `false`

3. **Cycle 2 (t=20, rising edge):** `self` = 7, `io_pause` = 1
   - `pc(0)` = L1, but again `self` ≠ 0, so pc(0) is never handled
   - `prevPc0` (pc(0) at t=10) = L1, but `interested(0)` = false
   - **Assertion fails**

### Why this is a design bug (not an assertion or setup error)

- **The assertion is correct:** The property `!(prevPc0 === L1) || interested(0)` correctly captures the Peterson protocol invariant: any process that has entered the protocol (pc=L1) must have its interested flag set. The comment in the code even explains the one-cycle delay correctly.
- **The setup is not the issue:** `io_select` and `io_pause` are intended to be unconstrained inputs for formal verification. The FSM should be robust against arbitrary `self` changes.
- **The design is buggy:** The L0→L1 transition should atomically set `interested(self) := true.B`. In the current code, the interested flag is set one cycle too late (during L1→L2 instead of during L0→L1).

### Fix

Move the `interested(self) := true.B` assignment from the L1 handler to the L0 handler, or add it to the L0 handler's non-paused transition:

```scala
is(Loc.L0) {
    when(io.pause) {
        pc(self) := Loc.L0
    }.otherwise {
        interested(self) := true.B   // ADD THIS: assert interest atomically with entry
        pc(self) := Loc.L1
    }
}
```

**Error classification: `dut_bug`**
