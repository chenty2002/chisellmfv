# Counterexample Analysis: `Main.ackA_implies_reqA`

## 1. Verification Environment

### Top Module
- **Module**: `Main` (in `arbiter.scala`, line 127)
- **Structure**: The `Main` module instantiates three `Client` modules (clientA, clientB, clientC), three `Controller` modules (controllerA, controllerB, controllerC), and one `Arbiter` module.
- **Connections**:
  - Each `Client.io.req` is connected to its corresponding `Controller.io.req`
  - Each `Controller.io.ack` is connected to its corresponding `Client.io.ack`
  - All three `Controller.io.sel` inputs are driven by `arbiterModule.io.sel`
  - `arbiterModule.io.active` is driven by `activeWire` = `controllerA.io.passToken || controllerB.io.passToken || controllerC.io.passToken`
  - `arbiterModule.io.sel` = `Mux(io.active, state, Selection.X)`

### Key Components
- **Client**: Generates random requests (`reqReg`) using an 8-bit counter LSB. Has states: NO_REQ (00), REQ (01), HAVE_TOKEN (10).
- **Controller**: Handles handshake. Has states: IDLE (00), READY (01), BUSY (10). Outputs `ackReg` and `passTokenReg`.
- **Arbiter**: Cycles through Selection states (A→B→C→A...) when `io.active` is high; outputs `sel=Selection.X` when inactive.

## 2. Violated Assertion

- **Assertion Name**: `ackA implies reqA` (extracted from waveform filename `Main.ackA_implies_reqA.fst`)
- **Code** (line 208 of `arbiter.scala`):
  ```scala
  fvAssert(!io.ackA || io.reqA, "ackA implies reqA")
  ```
- **Natural Language Description**: If an acknowledgment is sent to Client A (`io.ackA` is true), then Client A must have a pending request (`io.reqA` must also be true). This ensures the controller never issues an acknowledgment without a corresponding request.
- **File Location**: `arbiter.scala`, line 208

## 3. Waveform Information

- **Waveform File**: `verilog/extra_bench/arbiter_arbiter/Main.ackA_implies_reqA.fst`
- **Duration**: 13 cycles (0 ns – 130 ns)
- **Key Time Points**:

| Time (ns) | Event |
|-----------|-------|
| 0 | Reset; system starts active; io_active=1, io_sel=A(00) |
| 20 | ClientA.io_req goes high (reqReg=1, client enters REQ state) |
| 70 | Arbiter cycles through A→B→C; controllerC.passToken=1 |
| 80 | **ControllerA enters READY state (01)**; io_active goes low; io_sel goes to X(11) |
| 90 | **ControllerA transitions READY→BUSY (10); ackReg=1; io_ackA=1**; io_sel still X |
| 100 | ClientA receives ack (io_ack=1), enters HAVE_TOKEN state |
| 120 | ClientA drops request (HAVE_TOKEN→NO_REQ, reqReg=0); **io_reqA=0 while io_ackA=1** |
| 130 | Assertion violation persists; end of simulation trace |

**Critical Values at Failure Point (time 120-130)**:
- `Main.io_ackA` = 1
- `Main.io_reqA` = 0
- `Main.controllerA.state [1:0]` = 10 (BUSY)
- `Main.controllerA.ackReg` = 1
- `Main.controllerA.io_req` = 0

## 4. Root Cause Analysis

### Root Cause: Unconditional READY→BUSY Transition in Controller

**Bug Location**: `arbiter.scala`, lines 89–92, `class Controller`, `is(ControllerState.READY)` block:

```scala
is(ControllerState.READY) {
  state := ControllerState.BUSY      // line 90
  ackReg := true.B                   // line 91
}
```

**Description of the Bug**:
The transition from the `READY` state to the `BUSY` state (where `ackReg` is asserted) is **unconditional**. It does not check whether the controller is still selected (`isSelected`). This means that even if the arbiter has moved on, the selection has changed, or the system has become inactive, the controller will still assert `ackReg` and transition to `BUSY` state on the next clock cycle.

**How the Bug is Triggered (Step-by-Step)**:

1. **Cycle 7 (time 70)**: ControllerA is in IDLE state. At the clock edge, it evaluates the IDLE state logic. The controller transitions to READY (shown at time 80 in the waveform).

2. **Cycle 8 (time 80)**: ControllerA enters READY state. **At this same time, the arbiter's `io_active` goes low and `io_sel` switches to `Selection.X`**, meaning ControllerA is no longer selected (`isSelected` should be false).

3. **Cycle 9 (time 90)**: Despite not being selected, the unconditional READY→BUSY transition fires:
   - `state` becomes BUSY (10)
   - `ackReg` becomes 1 (asserting `io.ackA`)
   - This is the **spurious acknowledgment** — the controller acknowledges Client A even though the arbiter has already moved on

4. **Cycles 10-11 (time 100-110)**: ClientA receives the ack and transitions to HAVE_TOKEN state. The controller remains in BUSY with `ackReg=1`.

5. **Cycle 12 (time 120)**: ClientA randomly drops its request (HAVE_TOKEN→NO_REQ, `reqReg=0`). This makes `io_reqA=0`.

6. **At time 120-130**: `io_ackA=1` while `io_reqA=0`, violating `!io.ackA || io.reqA`.

**Why the Bug is a DUT Bug (not an assertion or setup issue)**:
- The assertion is correct: a controller should never send an acknowledgment when there's no request.
- The setup is valid: the scenario of the arbiter moving on between READY and BUSY is realistic.
- The fault lies squarely in the controller's FSM: the READY state should condition its transition to BUSY on still being selected.

**Evidence from Waveform**:
- At time 80: `controllerA.state = 01` (READY), while `io_sel = 11` (X, inactive)
- At time 90: `controllerA.state = 10` (BUSY), `ackReg = 1` — transition occurs despite sel=X
- At time 120: `io_reqA` drops to 0 while `io_ackA` is still 1

### Fix Recommendation

The `is(ControllerState.READY)` block should conditionally transition based on `isSelected`:

```scala
is(ControllerState.READY) {
  when(isSelected) {
    state := ControllerState.BUSY
    ackReg := true.B
  }.otherwise {
    state := ControllerState.IDLE
    ackReg := false.B
  }
}
```

This ensures the controller only asserts `ack` when it is still selected by the arbiter. If the selection changes or the system becomes inactive while the controller is in READY, it will safely return to IDLE without issuing a spurious acknowledgment.

### Error Classification

- **Error Type**: `dut_bug` — The Controller's unconditional READY→BUSY transition is a genuine design bug that causes a spurious acknowledgment.
