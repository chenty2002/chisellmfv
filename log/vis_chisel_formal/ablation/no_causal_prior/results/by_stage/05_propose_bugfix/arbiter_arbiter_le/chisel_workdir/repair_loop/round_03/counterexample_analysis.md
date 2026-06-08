# Counterexample Analysis Report: `ArbiterLE.mutex_pass_token`

## 1. Verification Environment

### Top Module
- **Module**: `ArbiterLE` (In package `llmverify`, file `arbiter_le.scala`)
- **Structure**: The `ArbiterLE` module instantiates:
  - 3 **Controller** modules (`controllerA`, `controllerB`, `controllerC`) — each manages the arbitration handshake for one client
  - 1 **Arbiter** module (`arbiter`) — implements round-robin selection
  - 3 **Client** modules (`clientA`, `clientB`, `clientC`) — generate random requests
  - 1 **Observer** module (`observer`) — monitors req/ack

### Key Connections
- Each `Controller` receives `io.req` (from its `Client`), `io.sel` (from the `Arbiter`), and `io.any_busy` (global OR of all controllers' `io.busy`)
- Each `Controller` outputs `io.ack` (to its `Client`), `io.pass_token`, and `io.busy`
- The `Arbiter` receives `io.active` = OR of all `io.pass_token` signals, and outputs `io.sel`
- The global `any_busy` signal = `controllerA.io.busy || controllerB.io.busy || controllerC.io.busy`

### Design Under Test
A round-robin arbiter with 3 clients (A, B, C). Each client requests service, the arbiter selects one, the controller grants the request, and upon completion (client drops request), the token is passed to rotate the arbiter to the next client.

---

## 2. Violated Assertion

- **Assertion Name**: `mutex_pass_token`
- **Property**: At most one controller can assert `pass_token` at any time
- **Chisel Source** (`arbiter_le.scala`, line 264):
  ```scala
  fvAssert(PopCount(Seq(io.pass_tokenA, io.pass_tokenB, io.pass_tokenC)) <= 1.U, "mutex_pass_token")
  ```
- **Natural Language**: The `pass_token` signals (indicating a controller is releasing the token) must be mutually exclusive — no two controllers should be passing the token simultaneously.

---

## 3. Waveform Information

- **Waveform File**: `verilog/extra_bench/arbiter_arbiter_le/ArbiterLE.mutex_pass_token.fst`
- **Time Range**: 0 ns → 90 ns (9 clock cycles, period ≈ 10 ns)
- **Failure Point**: **80 ns** — `mutex_pass_token` signal drops from 1 to 0 (assertion violated)

### Critical Signal Values at Time 80 ns

| Signal | Value |
|--------|-------|
| `io.pass_tokenA` | **1** |
| `io.pass_tokenB` | **1** |
| `io.pass_tokenC` | **0** |
| `PopCount` | **2** (violation: must be ≤ 1) |
| `controllerA.pass_tokenReg` | 1 |
| `controllerB.pass_tokenReg` | 1 |
| `controllerC.pass_tokenReg` | 0 |
| `controllerA.state` | IDLE (00) |
| `controllerB.state` | IDLE (00) |
| `controllerC.state` | IDLE (00) |

---

## 4. Root Cause Analysis

### Bug Category: **DUT Bug** — Incorrect Controller Design

### Bug Location
- **File**: `arbiter_le.scala`
- **Line**: 48
- **Module**: `Controller`
- **Buggy Code**:
  ```scala
  io.busy := state === ControllerState.BUSY
  ```

### Description of the Bug

The `Controller.io.busy` signal is defined as `true` only when the state is `BUSY`. However, the controller also passes through a **READY** state (one cycle between IDLE and BUSY). During the READY state, `io.busy` is `false`, which means the global `any_busy` signal does not reflect that a controller is already in the pipeline.

This allows a **second controller to enter the pipeline** before the first controller has fully transitioned to BUSY, resulting in **two controllers being in BUSY simultaneously**. When both complete (drop their `io.req`) in the same cycle, both set `pass_tokenReg := true.B`, violating the mutual-exclusion property.

### Detailed Scenario Trace

1. **Time 0–10**: ControllerA is IDLE and selected (sel=00 = id=00). With `io_req=0`, it sets `pass_tokenReg=1` under the `otherwise` clause (`!io.req && is_selected`).

2. **Time 10 (posedge)**: ControllerA is IDLE, `io_req` goes high. Condition `io_req && !io.any_busy` = true (nobody else is busy), so ControllerA transitions **IDLE → READY**. `pass_tokenReg` is cleared.

3. **Time 20 (posedge)**: 
   - ControllerA transitions **READY → BUSY** (unconditional), setting `ackReg=true`.
   - The Arbiter sees `active=true` (from pass_tokenA=1 at time 10) and rotates `sel` from 00 (A) to 01 (B).
   - ControllerB is IDLE and now selected (sel=01 = id=01). `io_req=1`. Crucially, `any_busy` is **still false** because ControllerA's state at this clock edge evaluation was READY (not BUSY), and `io.busy = (state === BUSY)` is false for READY.
   - So ControllerB also transitions **IDLE → READY**, entering the pipeline.

4. **Time 30 (posedge)**: ControllerB transitions **READY → BUSY** (unconditional). Now **both ControllerA and ControllerB are in BUSY state** simultaneously.

5. **Times 30–70**: Both controllers are BUSY, both receiving `io_ack=1` from their ackReg.

6. **Time 70 (posedge)**: Both clients A and B drop `io_req` simultaneously.

7. **Time 80 (posedge)**: Both controllers are in BUSY with `!io_req=true`:
   - Both transition **BUSY → IDLE**
   - Both set `pass_tokenReg := true.B`
   - **Result**: `pass_tokenA=1` AND `pass_tokenB=1` simultaneously → **PopCount = 2 > 1** → **ASSERTION VIOLATION**

### Why This Happens

The root cause is the **one-cycle pipeline delay** between READY and BUSY states combined with the narrow definition of `io.busy`. When controllerA enters READY at time 10, `io.busy` remains false, so `any_busy` stays false at time 20. This allows controllerB to also start its pipeline. Once both are in BUSY, any simultaneous completion will produce concurrent `pass_token` assertions.

### Proposed Fix

Change `io.busy` in the `Controller` class (line 48 of `arbiter_le.scala`) from:

```scala
io.busy := state === ControllerState.BUSY
```

to:

```scala
io.busy := state =/= ControllerState.IDLE
```

This makes the READY state also contribute to `any_busy`, preventing a new controller from entering the pipeline while another controller is in READY or BUSY. The pipeline is effectively single-entry, ensuring at most one controller can be servicing at any time.
