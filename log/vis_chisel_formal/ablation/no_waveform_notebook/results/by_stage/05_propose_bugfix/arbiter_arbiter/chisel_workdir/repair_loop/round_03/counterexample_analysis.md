# Counterexample Analysis: reqA_eventually_gets_ack_when_active

## 1. Verification Environment

- **Top Module**: `Main` (arbiter.scala:146)
- **Structure**: The top module instantiates:
  - **3 Clients** (Client A, B, C) - generate random requests to gain a token
  - **3 Controllers** (Controller A, B, C) - one per client, manage handshake with arbiter
  - **1 Arbiter** (`Arbiter`) - cycles through selections A→B→C→A→... while active
  - **Connection**: Client.io.req → Controller.io.req; Controller.io.ack → Client.io.ack; Arbiter.io.sel → all Controllers.io.sel; all Controllers.io.passToken OR'd together → Arbiter.io.active
- **Design Under Test**: A token-passing arbitration system where clients request a token, controllers mediate, and the arbiter selects which controller may serve its client.

## 2. Violated Assertion

- **Full Assertion Name**: `reqA_eventually_gets_ack_when_active`
- **Code Snippet** (arbiter.scala:237):
  ```scala
  astRelaxedLiveness(io.active && io.reqA, io.ackA, 10, "reqA eventually gets ack when active")
  ```
- **Property**: When the system is active (`io.active` is true) AND client A has a pending request (`io.reqA` is true), then client A should **receive an acknowledgment** (`io.ackA` becomes true) within **10 clock cycles**.
- **File Location**: `arbiter.scala`, line 237

## 3. Waveform Information

- **Waveform File**: `verilog/extra_bench/arbiter_arbiter/Main.reqA_eventually_gets_ack_when_active.fst`
- **Time Range**: 0 ns → 140 ns (14 clock cycles, clock period = 10 ns)
- **Key Time Points**:
  - **t=0**: System reset. All clients in NO_REQ state, all controllers in IDLE, passTokenReg=1 for all.
  - **t=10**: Rising edge 1. Arbiter transitions A→B.
  - **t=20**: Rising edge 2. Client A enters REQ state (`io_reqA` → 1). PassTokenA drops to 0 (because sel=B but no req yet).
  - **t=40**: Rising edge 4. Controller A enters READY state (was selected at t=30 when sel=A and io_reqA=1).
  - **t=50**: Rising edge 5. Controller A returns to IDLE (sel=B, not selected), **without asserting ack**.
  - **t=70, 100, 130**: Controller A enters READY again (same pattern repeats).
  - **t=80, 110**: Controller A returns to IDLE without ack.
- **Critical Signal Values (at t=130, last cycle)**:
  - `io_reqA` = 1 (pending since t=20, ~11 cycles)
  - `io_ackA` = 0 (never asserted once)
  - `io_active` = 1 (always active)
  - `io_sel` = 01 (Selection.B)
  - `io_passTokenA` = 0
  - `io_passTokenB` = 1
  - `io_passTokenC` = 0

## 4. Root Cause Analysis

### Buggy Code Location

- **File**: `arbiter.scala`
- **Module**: `Controller` (lines 59-114)
- **Specific FSM State**: `ControllerState.READY` (lines 97-105)

### Description of the Bug

The bug is a **fundamental timing mismatch** between the Arbiter and the Controller FSM:

1. **Arbiter behavior** (lines 126-136): The Arbiter changes its selection on EVERY clock cycle in a fixed pattern: A→B→C→A→B→C→... when `io.active` is true. Each selection lasts exactly one clock cycle.

2. **Controller FSM** (lines 76-113): The Controller has a three-state handshake:
   - **IDLE** → If `isSelected & io.req`, go to READY (and drop passToken)
   - **READY** → If `isSelected`, go to BUSY and assert ack; otherwise go back to IDLE
   - **BUSY** → Wait for `!io.req`, then return to IDLE

3. **The Mismatch**: The controller requires **two consecutive cycles** where it is selected:
   - Cycle 1: In IDLE, detect selection + request → transition to READY
   - Cycle 2: In READY, detect selection again → assert ack and transition to BUSY

   However, the arbiter only holds each selection for **one cycle**. By the time the controller is in READY (cycle 2), the arbiter has already advanced to the next client. Therefore, `isSelected` is always false when the controller checks it in READY, and the controller falls back to IDLE without ever asserting ack.

### Evidence from Waveform

The waveform trace clearly shows the repeating failure pattern for Controller A:

| Clock Edge | Arbiter State (sel) | ControllerA State | ControllerA isSelected | ControllerA io_ackA | Event |
|---|---|---|---|---|---|
| t=30 | A (00) | IDLE (00) | 1 | 0 | Arbiter selects A; ControllerA sees it |
| t=40 | B (01) | **READY** (01) | 0 | 0 | ControllerA entered READY but sel is now B! |
| t=50 | C (10) | IDLE (00) | 0 | 0 | ControllerA falls back to IDLE without ack |

The same pattern repeats every 30ns (3 cycles): ControllerA enters READY at t=40, t=70, t=100, t=130 — but always falls back to IDLE at t=50, t=80, t=110 without ever asserting ack.

**Evidence that no ack ever fires anywhere in the system**:
- `io_ackA`: 0 at all times (never transitions)
- `io_ackB`: 0 at all times (never transitions)
- `io_ackC`: 0 at all times (never transitions)

All three controllers exhibit the same READY→IDLE fallback pattern without ever reaching BUSY.

### Why This Causes the Assertion to Fail

The assertion `astRelaxedLiveness(io.active && io.reqA, io.ackA, 10, ...)` checks that whenever `io.active` (always true) and `io.reqA` (high from t=20 onward) are both true, `io.ackA` must become true within 10 cycles. Since `io.ackA` never fires at any point during the 14-cycle trace, the countdown timer exceeds 10 cycles and the assertion fails.

### Error Classification

This is a **genuine DUT bug (`dut_bug`)**.

The bug is in the Controller's READY-to-BUSY transition logic (lines 97-105 of `arbiter.scala`). The controller expects to be selected for two consecutive cycles to complete the handshake, but the arbiter only provides single-cycle selections. 

### Suggested Fix

The Controller should be modified so that when it transitions from IDLE to READY (upon being selected with a pending request), it should assert `ackReg` in that same transition (or the controller should not require a second selection cycle). For example, the IDLE state logic could be changed to:

```scala
is(ControllerState.IDLE) {
  when(isSelected) {
    when(io.req) {
      state := ControllerState.BUSY   // Skip READY, go directly to BUSY
      ackReg := true.B                 // Assert ack immediately
      passTokenReg := false.B
    }.otherwise {
      passTokenReg := true.B
    }
  }.otherwise {
    when(io.req) {
      passTokenReg := true.B
    }.otherwise {
      passTokenReg := false.B
    }
  }
}
```

Or alternatively, the Arbiter could be modified to hold a selection for multiple cycles until the controller has completed its handshake.
