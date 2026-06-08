# Counterexample Analysis Report: `Main.reqA_eventually_ackA`

## 1. Verification Environment

### Top Module Structure
- **Top Module**: `Main` in `llmverify` package (arbiter.scala, line 153)
- **Components**:
  - `clientA`, `clientB`, `clientC`: `Client` modules that generate random requests using an LSB counter as a random bit
  - `controllerA`, `controllerB`, `controllerC`: `Controller` modules that manage the handshake between clients and the arbiter; each holds a token (`passTokenReg`) indicating ownership of the shared resource
  - `arbiterModule`: `Arbiter` module that round-robins through A→B→C→A selection when `io.active` is high
- **Key Connections**:
  - Client `io.req` → Controller `io.req`
  - Controller `io.ack` → Client `io.ack`
  - Arbiter `io.sel` → all Controller `io.sel` inputs
  - `activeWire = controllerA.io.passToken || controllerB.io.passToken || controllerC.io.passToken` → arbiter `io.active`

### Protocol
Only one controller holds the `passToken` (token of bus ownership) at any time. The arbiter cycles round-robin through A→B→C→A, selecting the controller whose turn it is. If the selected controller has the token **and** a pending request, it consumes the token and services the request. If it has the token but no request, it retains the token. When a controller finishes servicing (client drops req), it passes the token back. If no controller has the token (`active=0`), the arbiter freezes at `Selection.X`.

## 2. Violated Assertion

- **Assertion Name**: `reqA_eventually_ackA` (from waveform filename `Main.reqA_eventually_ackA.fst`)
- **Location**: arbiter.scala, lines 255–256
- **Code Snippet**:
  ```scala
  astRelaxedLiveness(io.reqA, io.ackA, 15, "reqA_eventually_ackA")
  ```
- **Property Description**: This is a **bounded liveness** assertion. Whenever `io.reqA` is asserted (client A requests service), `io.ackA` must be asserted within 15 clock cycles. The bound of 15 provides margin for the round-robin arbiter to cycle through all 3 states plus controller processing delays.

## 3. Waveform Information

- **Waveform File**: `verilog/extra_bench/arbiter_arbiter/Main.reqA_eventually_ackA.fst`
- **Duration**: 27 cycles (0 ns → 270 ns)
- **Assertion Failure Time**: The assertion signal `Main.reqA_eventually_ackA` transitions from `1` → `0` at **time 260 ns**

### Critical Timeline (all times in ns):

| Time | Event | Signals |
|------|-------|---------|
| 0 | Reset; token held by controllerA | passTokenA=1, passTokenB=0, passTokenC=0, active=1, sel=00(A) |
| 20 | **First rise of reqA**; passTokenA cleared by non-selection bug | reqA=1, passTokenA→0 (lost!), passTokenB→1 |
| 30 | Arbiter freezes: all passTokens=0, active=0, sel=11(X) | passTokenA=0, passTokenB=0, passTokenC=0, active=0 |
| 70 | ControllerC returns from BUSY→IDLE, sets passTokenC=1 | passTokenC→1, active→1 |
| 80 | passTokenC cleared by non-selection bug; deadlock resumes | passTokenC→0, active→0 |
| 90–100 | ControllerA services first request (from reqA@20) | ackA=1 at time 90–100 ✓ |
| 130–140 | ControllerA returns token; **second rise of reqA@140** | passTokenA→1@130, then cleared@140, reqA→1@140 |
| 140–260 | **Deadlock**: all passTokens=0, active=0, arbiter stuck at X | ackA never rises again |
| 260 | **Assertion fails**: 12 cycles after reqA rose at 140, ackA still 0 | reqA_eventually_ackA→0 |

## 4. Root Cause Analysis

### Bug Location
- **File**: `arbiter.scala`
- **Lines**: 86–89 (within `class Controller`, in the `ControllerState.IDLE` case of the state machine)
- **Buggy Code**:
  ```scala
  is(ControllerState.IDLE) {
    when(isSelected) {
      when(io.req) {
        state := ControllerState.READY
        passTokenReg := false.B   // line 83-84: correct, consume token for servicing
      }.otherwise {
        passTokenReg := true.B    // line 86: correct, keep token if no request
      }
    }.otherwise {
      passTokenReg := false.B     // line 88: **BUG** — clears token when not selected!
    }
  }
  ```

### Description of the Bug
The `.otherwise` clause on **line 88** unconditionally clears `passTokenReg` (`passTokenReg := false.B`) whenever the controller is in `IDLE` state and **not selected** by the arbiter. This is incorrect: the token (indicating bus ownership) should persist in the controller regardless of whether it is currently selected by the arbiter. The controller only gives up the token when it either:
1. **Consumes it for servicing** (line 84): `when(io.req)` → `passTokenReg := false.B` alongside `state := READY`
2. **Passes it after servicing** (in BUSY state): `when(!io.req)` → `passTokenReg := true.B` alongside `state := IDLE`

### Why It Causes the Assertion to Fail
The token-passing protocol uses `active = passTokenA || passTokenB || passTokenC` to enable the arbiter. When the arbiter cycles through its round-robin sequence (A→B→C→A), each controller is selected for only **one cycle out of three**. If the token-holding controller is not the currently selected one, the `.otherwise` clause destroys the token, causing all three `passToken` signals to be zero simultaneously. This sets `active=0`, which causes the arbiter to freeze at `Selection.X`, making it impossible for **any** controller to ever be selected again. The system deadlocks permanently.

### Waveform Evidence (Repeated from Section 3)
The bug manifests repeatedly in the waveform:

1. **Time 10–19**: passTokenA=1 (token held by A), but io_sel=01 (B selected). ControllerA is not selected → `.otherwise` fires at clock edge time 20 → **passTokenA→0**. Token lost!

2. **Time 70–79**: passTokenC=1 (token returned by controllerC after servicing), but io_sel=00 (A selected). ControllerC is not selected → `.otherwise` fires at clock edge time 80 → **passTokenC→0**. Token lost again!

3. **Time 130–139**: passTokenA=1 (controllerA returns token after first request completes), but io_sel=01 (B selected). ControllerA is not selected → `.otherwise` fires at clock edge time 140 → **passTokenA→0**. Token lost permanently!

After time 140, **all three passTokens are zero**, `active=0`, the arbiter is stuck at `Selection.X` (11), and reqA=1 (client A has been requesting since time 140) **never** gets ackA. The assertion `reqA_eventually_ackA` fails at time 260 when the bounded-liveness checker detects that ackA has not been asserted within 15 cycles of the last reqA rising edge.

### Fix
Remove the `.otherwise { passTokenReg := false.B }` clause (line 88). The corrected code should be:

```scala
is(ControllerState.IDLE) {
  when(isSelected) {
    when(io.req) {
      state := ControllerState.READY
      passTokenReg := false.B
    }.otherwise {
      passTokenReg := true.B
    }
  }
  // When not selected: passTokenReg retains its previous value (do not clear it)
}
```

This ensures the token persists in the controller until it is intentionally consumed (for servicing) or passed (after servicing completes).

### Error Classification
This is a **dut_bug** — a genuine bug in the original design. The `passTokenReg` is incorrectly cleared when a controller is not selected, breaking the token-passing protocol and causing a system deadlock.
