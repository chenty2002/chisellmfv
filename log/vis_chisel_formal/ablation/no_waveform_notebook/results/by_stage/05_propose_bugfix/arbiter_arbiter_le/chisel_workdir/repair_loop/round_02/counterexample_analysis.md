# Counterexample Analysis Report: `ArbiterLE.mutex_ack`

## 1. Verification Environment

- **Top Module**: `ArbiterLE` (from `arbiter_le.scala`)
- **Design Structure**:
  - 3 `Controller` instances (controllerA, controllerB, controllerC) — each handles selection/acknowledgment for one client
  - 1 `Arbiter` instance — cycles through selections (A → B → C) using round-robin
  - 3 `Client` instances (clientA, clientB, clientC) — generate requests using LFSR-based random behavior
  - 1 `Observer` instance — monitors req/ack handshake
- **Key Connections**:
  - `arbiter.io.sel` → `controllerX.io.sel` (which client is selected)
  - `controllerX.io.ack` → `clientX.io.ack` (acknowledgment to client)
  - `clientX.io.req` → `controllerX.io.req` (request from client)
  - `arbiter.io.active := active || anyReq`, where `active` = OR of all pass_tokens and `anyReq` = OR of all client requests

## 2. Violated Assertion

- **Full Assertion Name**: `ArbiterLE.mutex_ack`
- **Code Snippet** (lines 173-174):
  ```scala
  // Safety: At most one acknowledgment can be active at a time
  assertMutex(Seq(io.ackA, io.ackB, io.ackC), "mutex_ack")
  ```
- **Natural Language Description**: At any given time, at most one of the three acknowledgment signals (`io.ackA`, `io.ackB`, `io.ackC`) may be asserted (high). This is a mutual-exclusion safety property ensuring that only one client receives an acknowledgment at a time.
- **File Location**: `arbiter_le.scala`, lines 173-174

## 3. Waveform Information

- **Waveform File**: `verilog/extra_bench/arbiter_arbiter_le/ArbiterLE.mutex_ack.fst`
- **Time Range**: 0 ns → 50 ns (5 clock cycles, period = 10 ns)
- **Key Time Point**: 40 ns — the assertion fails
- **Critical Signal Values at Failure (time = 40 ns)**:

| Signal | Value | Description |
|--------|-------|-------------|
| `ArbiterLE.mutex_ack` | 0 | **Assertion failed** (transitioned 1→0 at 40 ns) |
| `ArbiterLE.io_ackB` | 1 | Controller B's acknowledgment is high |
| `ArbiterLE.io_ackC` | 1 | Controller C's acknowledgment is high |
| `ArbiterLE.io_ackA` | 0 | Controller A's acknowledgment is low |
| `ArbiterLE.controllerB.state` | 10 (BUSY) | Controller B is in BUSY state |
| `ArbiterLE.controllerC.state` | 10 (BUSY) | Controller C is in BUSY state |
| `ArbiterLE.controllerB.ackReg` | 1 | Controller B's ack register is set |
| `ArbiterLE.controllerC.ackReg` | 1 | Controller C's ack register is set |
| `ArbiterLE.arbiter.io_sel` | 01 (B) | Arbiter selects client B |
| `ArbiterLE.io_reqB` | 1 | Client B's request is still high |
| `ArbiterLE.io_reqC` | 1 | Client C's request is still high |
| `ArbiterLE.io_reqA` | 1 | Client A's request is still high |

## 4. Root Cause Analysis

### Buggy Code Location

**File**: `arbiter_le.scala`, lines 140-141 (inside class `ArbiterLE`)

```scala
val anyReq = clientA.io.req || clientB.io.req || clientC.io.req
arbiter.io.active := active || anyReq
```

### Description of the Bug

The design adds `anyReq` (the OR of all client requests) to the arbiter's active signal to prevent the arbiter from stalling when all pass_tokens momentarily go low during a controller transaction. However, this override creates a critical flaw: **the arbiter continues cycling through clients even while a controller is actively acknowledging another client**, allowing multiple controllers to enter the BUSY state simultaneously and assert their acknowledgment signals concurrently.

### Detailed Causal Chain (from waveform evidence)

1. **Cycle 0 (time 0)**: All controllers are IDLE, all pass_tokens = 1, sel = A. All clients are in NO_REQ state.

2. **Cycle 1 (time 10)**: All three clients assert their requests (io_reqA = io_reqB = io_reqC = 1). The arbiter selects B (sel = B = 01). ControllerB is selected, sees req = 1, and enters READY state. ControllerA and ControllerC (not selected) set pass_token = 0.

3. **Cycle 2 (time 20)**: ControllerA's pass_token finally goes to 0, making `active = 0`. However, `anyReq = 1` (all clients still requesting), so `arbiter.io.active = 1` — the arbiter keeps cycling. ControllerB transitions from READY → BUSY and asserts ackB = 1 (controllerB.ackReg transitions 0→1 at time 30). The arbiter selects C (sel = C = 10), and controllerC (selected, sees req=1) enters READY.

4. **Cycle 3 (time 30)**: ControllerB is BUSY with ackB = 1. ControllerB's req is still high (clientB received ack at time 30 but the LFSR rand_choice = 0, so clientB stays in HAVE_TOKEN without dropping req). ControllerC transitions from READY → BUSY (takes effect at time 40). The arbiter selects A (sel = A = 00).

5. **Cycle 4 (time 40) — ASSERTION FAILURE**: ControllerB is still BUSY (reqB still high, so the BUSY→IDLE transition never triggered). ControllerC enters BUSY and asserts ackC = 1. **Both ackB and ackC are now high simultaneously**, violating the `assertMutex` property.

### Root Cause Summary

The `anyReq` override on line 141 allows the arbiter to keep selecting new clients while a prior client's controller is still in BUSY state with its acknowledgment asserted. The controller design (lines 77-86) only exits BUSY when `!io.req`, but the client may not drop its request immediately after receiving ack (the client's LFSR-based random choice may be 0 for multiple cycles). During this window, the arbiter can select a different client whose controller also enters BUSY and asserts ack, creating the mutual-exclusion violation.

### Why This Is a Design Bug (Not an Assertion or Setup Error)

- The assertion `assertMutex(io.ackA, io.ackB, io.ackC)` is a **valid and necessary safety property** — no real arbiter should deliver acknowledgments to multiple clients simultaneously.
- The test setup is valid — clients legitimately keep requests high after receiving ack (the LFSR rand_choice randomness models realistic timing).
- The design intent (per the comment on line 139) was to prevent stalling, but the implementation has a **genuine design flaw**: there is no mechanism to prevent a new controller from entering BUSY while another is already in BUSY.

### Potential Fix

The fix should prevent the arbiter from selecting a new client while any controller is actively servicing a request (in BUSY state). Options include:
1. **Remove the `anyReq` override** from `arbiter.io.active` (line 141), relying solely on pass_tokens to drive the arbiter. The arbiter would stop cycling (output X) during a transaction and resume when the current controller completes and re-asserts its pass_token.
2. **Add a `busy` signal** that tracks whether any controller is in BUSY and gates the arbiter's active input: `arbiter.io.active := active || anyReq && !anyBusy`.
3. **Modify the controller** to de-assert ack when it is no longer selected, so that even if the arbiter cycles, the previous controller releases its ack.

Option 1 is the simplest and most aligned with the original token-passing design intent.

### Error Classification

**Error Type**: `dut_bug` — The design has a genuine bug where the `anyReq` override in the arbiter activation allows multiple controllers to enter BUSY state simultaneously, violating the mutual-exclusion safety property on acknowledgments.
