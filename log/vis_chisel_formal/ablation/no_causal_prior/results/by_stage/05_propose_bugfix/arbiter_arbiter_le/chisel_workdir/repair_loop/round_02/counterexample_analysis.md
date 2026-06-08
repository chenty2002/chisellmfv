# Counterexample Analysis Report: `ArbiterLE.mutex_pass_token`

## 1. Verification Environment

### Top Module and Structure
- **Top module**: `ArbiterLE` (file: `arbiter_le.scala`, line 185)
- **Hierarchy**:
  - `controllerA`, `controllerB`, `controllerC`: Instances of `Controller` class
  - `arbiter`: Instance of `Arbiter` class (round-robin selection)
  - `clientA`, `clientB`, `clientC`: Instances of `Client` class (request generation via LFSRs)
  - `observer`: Instance of `Observer` class

### Key Connections
- Each `Controller` receives `io.sel` from the arbiter and compares it against its `io.id` (A=00, B=01, C=10) to determine if it is selected
- Each `Controller` receives `io.req` from its corresponding `Client`
- Each `Client` sends `io.req` and receives `io.ack` from its `Controller`
- The Arbiter receives `io.active` = OR of all three controllers' `pass_token` signals
- The Arbiter cycles A → B → C → A when `active` is true

### Design Under Test
A round-robin arbiter with three clients. The `Controller` state machine has three states: IDLE, READY, BUSY. The token-passing scheme uses the `pass_token` signal to advance the round-robin selection.

---

## 2. Violated Assertion

- **Full assertion name**: `mutex_pass_token`
- **Waveform filename**: `ArbiterLE.mutex_pass_token.fst`
- **Assertion code** (line 253 in `arbiter_le.scala`):
  ```scala
  fvAssert(PopCount(Seq(io.pass_tokenA, io.pass_tokenB, io.pass_tokenC)) <= 1.U, "mutex_pass_token")
  ```
- **Natural language**: "At most one controller may assert the pass_token signal at any time (mutual exclusion for token passing)."
- **File location**: `arbiter_le.scala`, line 253

---

## 3. Waveform Information

- **Waveform file**: `verilog/extra_bench/arbiter_arbiter_le/ArbiterLE.mutex_pass_token.fst`
- **Time range**: 0 ns → 90 ns (9 clock cycles at 10 ns period)
- **Failure time point**: 80 ns

### Critical Signal Values at Failure Point (t=80 ns)

| Signal | Value at t=80 |
|--------|--------------|
| `ArbiterLE.io_pass_tokenA` | 1 |
| `ArbiterLE.io_pass_tokenB` | 1 |
| `ArbiterLE.io_pass_tokenC` | 0 |
| `ArbiterLE.mutex_pass_token` | 0 (FAILED) |
| `ArbiterLE.controllerA.state` | 00 (IDLE) |
| `ArbiterLE.controllerA.pass_tokenReg` | 1 |
| `ArbiterLE.controllerA.io_req` | 1 |
| `ArbiterLE.controllerB.state` | 00 (IDLE) |
| `ArbiterLE.controllerB.pass_tokenReg` | 1 |
| `ArbiterLE.controllerB.io_req` | 1 |
| `ArbiterLE.controllerC.state` | 00 (IDLE) |
| `ArbiterLE.controllerC.pass_tokenReg` | 0 |
| `ArbiterLE.io_sel` | 01 (B) |
| `ArbiterLE.io_active` | 1 |

### Key Time Points

| Time | Event |
|------|-------|
| 0 ns | Reset: all controllers IDLE, pass_token=0, sel=A |
| 10 ns | Client reqs go 0→1 (all three clients). ControllerA sets `pass_tokenReg=1` (IDLE, selected, no req) |
| 20 ns | Clock edge: A enters READY (pass_token cleared). Arbiter advances to B (due to active=1). |
| 30 ns | Clock edge: A enters BUSY (ackA=1). B enters READY. |
| 40 ns | Clock edge: B enters BUSY (ackB=1). **Both A and B are now BUSY simultaneously.** |
| 40-70 ns | Both A and B remain in BUSY with both req=1, both ack=1 |
| 70 ns | Both clientA and clientB drop req (LFSR rand_choice=1 at this cycle). Ack drops combinatorially. Both controllers still in BUSY with ackReg=1 but req=0. |
| **80 ns** | **Clock edge: Both A and B transition BUSY→IDLE, BOTH set pass_tokenReg=1. Assertion FAILS.** |

---

## 4. Root Cause Analysis

### Buggy Code Location

**File**: `arbiter_le.scala`
**Lines**: 64-69 (Controller BUSY state) and lines 20-32 (Controller IDLE state)

The specific buggy code:

```scala
// Lines 64-69 — BUSY state: unconditionally asserts pass_token when exiting
is(ControllerState.BUSY) {
  when(!io.req) {
    state := ControllerState.IDLE
    ackReg := false.B
    pass_tokenReg := true.B    // ← BUG: no mutual-exclusion guard
  }
}
```

```scala
// Lines 20-32 — IDLE state: allows entry to READY without checking if another controller is already BUSY
is(ControllerState.IDLE) {
  when(is_selected) {
    when(io.req) {
      state := ControllerState.READY   // ← BUG: no check if another controller is BUSY
      pass_tokenReg := false.B
    }.otherwise {
      pass_tokenReg := true.B
    }
  }.otherwise {
    pass_tokenReg := false.B
  }
}
```

### Description of the Bug

**Category: Bug in the Original Design (DUT Bug)**

The design has a fundamental flaw: there is no mechanism preventing multiple controllers from simultaneously being in the BUSY state. When the Arbiter advances its selection (from A to B) while controller A is still processing, controller B also enters the BUSY state. At that point, both A and B are BUSY simultaneously.

When both clients A and B eventually drop their requests (which happens at the same time because all three clients share the **same LFSR seed** `RegInit(1.U(8.W))` on line 140, making their random sequences identical), both controllers transition from BUSY to IDLE on the same clock edge, and both set `pass_tokenReg := true.B` (line 68), violating the mutual-exclusion assertion.

### The Causal Chain

```
1. Arbiter advances A→B (t=20) while A is still processing
     ↓
2. Controller B also enters READY→BUSY (t=30→40)
     ↓
3. Both A and B are in BUSY simultaneously (t=40 onward)
     ↓
4. Both clients A and B have identical LFSR sequences → both drop req at t=70
     ↓
5. Both controllers transition BUSY→IDLE at t=80, both set pass_tokenReg=1
     ↓
6. PopCount(pass_tokenA, pass_tokenB, pass_tokenC) = 2 > 1 → ASSERTION FAILS
```

### Evidence from Waveform

1. **t=20**: `arbiter.state` transitions from 00 (A) to 01 (B) — arbiter advances while controller A is still in IDLE/READY.
2. **t=30**: ControllerA enters BUSY (state=10). ControllerB enters READY (state=01).
3. **t=40**: ControllerB also enters BUSY (state=10). **Both A and B are now BUSY.**
4. **t=70**: `io_req` for both A and B drops from 1→0 simultaneously. `io_ack` for both drops from 1→0.
5. **t=80**: Both controllerA and controllerB `state` change from 10 (BUSY) → 00 (IDLE). Both `pass_tokenReg` go to 1. Both `io_pass_token` signals become 1.

### Why This Is a Design Bug (Not an Assertion Error or Setup Issue)

- The assertion `PopCount(pass_token) <= 1.U` is a **correct safety property**: mutual exclusion in token passing is essential for a round-robin arbiter.
- Even though the identical LFSR seeds expose the bug more easily (clients synchronize their req-drop timing), the underlying problem is the lack of mutual exclusion in the BUSY state. Even with different seeds, a scenario could exist where two clients happen to drop their requests simultaneously, triggering the same violation.
- The fix requires adding a guard so that a controller can only enter READY (and subsequently BUSY) when no other controller is already in the BUSY state, OR ensuring that the pass_token signal is only asserted by the controller that was actually selected when its request dropped.

### Recommended Fix

The Controller should only be allowed to transition from IDLE to READY when it is the selected controller AND no other controller is already BUSY. One approach:

- Add a global signal (e.g., `any_busy`) that indicates when any controller is in the BUSY state.
- In the Controller's IDLE state, add a condition: `when(is_selected && io.req && !any_busy)` to prevent multiple simultaneous BUSY entries.
- Alternatively, modify the BUSY→IDLE transition to only set `pass_tokenReg := true.B` when the controller was actually selected (i.e., `is_selected`), ensuring only one controller asserts pass_token at a time.

The simplest fix for the immediate assertion failure would be to add a guard on the BUSY→IDLE transition:

```scala
is(ControllerState.BUSY) {
  when(!io.req) {
    state := ControllerState.IDLE
    ackReg := false.B
    // Only assert pass_token if this controller is currently selected
    pass_tokenReg := is_selected
  }
}
```

However, this alone would not fix the underlying issue of multiple controllers being in BUSY simultaneously. A more comprehensive fix would also prevent the IDLE→READY transition when another controller is BUSY.
