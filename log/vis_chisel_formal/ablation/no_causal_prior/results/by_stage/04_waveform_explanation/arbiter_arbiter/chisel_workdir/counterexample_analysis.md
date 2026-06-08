# Counterexample Analysis Report: `ackA_implies_reqA`

## 1. Verification Environment

### Top Module Structure
The top module is `Main`, which instantiates:
- **3 Clients** (`clientA`, `clientB`, `clientC`) — generate random requests
- **3 Controllers** (`controllerA`, `controllerB`, `controllerC`) — handshake between clients and arbiter, each with a unique id (`Selection.A`, `Selection.B`, `Selection.C`)
- **1 Arbiter** (`arbiterModule`) — round-robins through clients when active

### Connections
- Each client's `req` output feeds its corresponding controller's `req` input
- Each controller's `ack` output feeds its corresponding client's `ack` input
- All controllers share the arbiter's `sel` output, selecting which client gets serviced
- The active signal is the OR of all `passToken` outputs from controllers

### Design Under Test
The DUT implements a token-passing arbitration protocol. Clients randomly assert requests. The arbiter cycles through clients (A→B→C→A). When selected, a controller with a pending request consumes the token and acknowledges the client. The client then holds the token and randomly releases it.

## 2. Violated Assertion

### Assertion Name
`ackA_implies_reqA`

### Code Snippet (arbiter.scala, line 117)
```scala
fvAssert(!io.ackA || io.reqA, "ackA_implies_reqA")
```

### Property Description
The assertion states: **If controller A acknowledges client A (io.ackA is true), then client A must be actively requesting (io.reqA must be true)**. Equivalently, `io.ackA ⇒ io.reqA`. An acknowledgment without a corresponding active request indicates a spurious or mistimed grant.

### File Location
`arbiter.scala`, line 117 (inside the `Main` class)

## 3. Waveform Information

### Waveform File
`verilog/extra_bench/arbiter_arbiter/Main.ackA_implies_reqA.fst`

### Time Range
0 ns — 130 ns (13 clock cycles, each 10 ns)

### Key Time Points and Signal Values

| Time (ns) | io_ackA | io_reqA | controllerA.state | controllerA.ackReg | clientA.state | clientA.reqReg | controllerA.io_req |
|-----------|---------|---------|-------------------|-------------------|---------------|----------------|-------------------|
| 0         | 0       | 0       | 00 (IDLE)         | 0                 | 00 (NO_REQ)   | 0              | 0                 |
| 20        | 0       | 1       | 00 (IDLE)         | 0                 | 01 (REQ)      | 1              | 1                 |
| 70        | 0       | 1       | 00 (IDLE)         | 0                 | 01 (REQ)      | 1              | 1                 |
| 80        | 0       | 1       | 01 (READY)        | 0                 | 01 (REQ)      | 1              | 1                 |
| 90        | 1       | 1       | 10 (BUSY)         | 1                 | 01 (REQ)      | 1              | 1                 |
| 100       | 1       | 1       | 10 (BUSY)         | 1                 | 10 (HAVE_TOKEN)| 1             | 1                 |
| 110       | 1       | 1       | 10 (BUSY)         | 1                 | 10 (HAVE_TOKEN)| 1             | 1                 |
| **120**   | **1**   | **0**   | **10 (BUSY)**     | **1**             | **00 (NO_REQ)**| **0**         | **0**             |
| **130**   | **1**   | **0**   | **10 (BUSY)**     | **1**             | **00 (NO_REQ)**| **0**         | **0**             |

The assertion violation occurs at **time 120 ns** and persists through **time 130 ns**.

## 4. Root Cause Analysis

### Buggy Code Location
**File**: `arbiter.scala`  
**Line**: ~176-181 (inside the `Controller` class, `ControllerState.BUSY` case)

```scala
is(ControllerState.BUSY) {
  when(!io.req) {
    state := ControllerState.IDLE
    ackReg := false.B
    passTokenReg := true.B
  }
}
```

### Description of the Bug

The **Controller's BUSY-state transition logic has a one-cycle latency bug**. When the client drops its request (`io.req` goes low) while the controller is in the BUSY state, the controller fails to detect this transition on the same clock cycle. It instead keeps `ackReg` asserted for an extra cycle, creating a window where `io.ackA=1` but `io.reqA=0`.

### Detailed Trace of the Failure (Waveform Evidence)

**Phase 1: Normal Operation (time 70–100)**

1. **Time 70**: Arbiter reactivates (`io_active=1`), selects client A.
2. **Time 80**: ControllerA is selected (`isSelected=1`) and sees `io.req=1`. Transitions `IDLE → READY`.
3. **Time 90**: ControllerA transitions `READY → BUSY`, asserts `ackReg=1`. ClientA remains in `REQ` state. `io_ackA=1, io_reqA=1` — assertion holds.
4. **Time 100**: ClientA receives `ack=1`, transitions `REQ → HAVE_TOKEN`. `reqReg` stays 1. ControllerA remains in BUSY with `ackReg=1`.

**Phase 2: Client Drops Request (time 110–120)**

5. **Time 110**: ClientA is in `HAVE_TOKEN` state. The random decision (`randChoice = randCounter[0]`) is determined by the LSB of the counter. At this time, `randCounter = 00001011` (LSB=1).
6. **At the positive clock edge of time 120**: The `when(randChoice)` condition in the client's `HAVE_TOKEN` state triggers (since LSB was 1). The client executes:
   ```scala
   reqReg := false.B
   state := ClientState.NO_REQ
   ```
   Simultaneously, the **controller** in the `BUSY` state evaluates:
   ```scala
   when(!io.req) {  // io.req reads the OLD value (still 1) due to non-blocking assignment semantics
     state := ControllerState.IDLE
     ackReg := false.B
     passTokenReg := true.B
   }
   ```

**Phase 3: Assertion Violation (time 120–130)**

7. Because both `clientA.reqReg` and `controllerA.ackReg` update on the same positive clock edge, the controller's `io.req` input (a combinational wire from `clientA.io.req` which is `reqReg`) **still reads the old value (1)** during the controller's evaluation. The condition `when(!io.req)` evaluates to false, so the controller **does not transition out of BUSY** and **does not clear ackReg**.

8. **At time 120**: `io_ackA=1` (controller still asserting ack) BUT `io_reqA=0` (client already dropped request). **The assertion `!io.ackA || io.reqA` is violated.**

9. **At time 130**: The condition persists — `io_ackA=1` and `io_reqA=0` — because the controller needs another cycle to respond to the now-visible `io.req=0` value.

### Root Cause Category: **Bug in the Original Design (dut_bug)**

This is a genuine design bug in the Controller's state machine. The `BUSY → IDLE` transition uses `when(!io.req)` which reacts one cycle too late when the client simultaneously drops its request. The controller must be redesigned to handle the case where `req` and `ack` change on the same clock edge.

### Why This Is Not an Assertion or Setup Error

- **Not an assertion error**: The property `ack ⇒ req` is a basic handshake safety property. It correctly captures the invariant that an acknowledgment should only be sent while the request is active.
- **Not a setup error**: The client and controller are correctly connected, and the scenario triggered (client releases the token when in `HAVE_TOKEN` state) is a normal part of the expected behavior. The design should handle this corner case.

### Potential Fix

The Controller's BUSY state needs to use a **registered version of req** or add a **combinational bypass** to detect the simultaneous transition. One approach is to sample `io.req` on entry to BUSY and use that sampled value for the duration of the handshake, or to restructure the state machine to allow a direct transition from BUSY to IDLE in the same cycle the client drops req.
