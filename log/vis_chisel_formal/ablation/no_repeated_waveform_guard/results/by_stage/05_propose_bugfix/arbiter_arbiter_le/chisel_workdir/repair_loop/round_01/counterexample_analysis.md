# Counterexample Analysis Report: ArbiterLE.ackA_requires_reqA

## 1. Verification Environment

- **Top module**: `ArbiterLE` (line 161 of `arbiter_le.scala`)
- **Structure**:
  - 3 `Controller` instances: `controllerA`, `controllerB`, `controllerC` — each manages the req/ack handshake for one client
  - 1 `Arbiter` instance: implements round-robin selection among controllers
  - 3 `Client` instances: `clientA`, `clientB`, `clientC` — generate random requests using LFSR
  - 1 `Observer` instance: monitors req/ack for correctness
- **Connections**:
  - `clientX.io.req` → `controllerX.io.req` (combinational)
  - `controllerX.io.ack` → `clientX.io.ack` (combinational)
  - `arbiter.io.sel` → `controllerX.io.sel` (shared selection bus)
  - `controllerX.io.pass_token` → `arbiter.io.active` (OR'd)
  - `controllerX.io.id` set to `Selection.A/B/C` (0/1/2)

## 2. Violated Assertion

- **Assertion name**: `ackA_requires_reqA` (from waveform filename `ArbiterLE.ackA_requires_reqA.fst`)
- **Code location**: `arbiter_le.scala`, line 244
- **Code snippet**:
  ```scala
  fvAssert(!io.ackA || io.reqA, "ackA requires reqA")
  ```
- **Property**: Client A should never receive an acknowledgement (`io.ackA = 1`) without having an active request (`io.reqA = 1`). In other words, `ackA ⇒ reqA` must hold at every clock cycle.

## 3. Waveform Information

- **Waveform file**: `verilog/extra_bench/arbiter_arbiter_le/ArbiterLE.ackA_requires_reqA.fst`
- **Failure time**: **190 ns** (posedge of clock cycle 19)
- **Key signal values at failure**:

| Signal | Time 180 (cycle 18) | Time 190 (cycle 19) |
|---|---|---|
| `io_ackA` | 1 | **1** |
| `io_reqA` | 1 | **0** ← dropped |
| `controllerA.io_req` | 1 | 0 (dropped) |
| `controllerA.ackReg` | 1 | **1** (not cleared!) |
| `controllerA.state [1:0]` | BUSY (10) | **BUSY (10)** (not exited!) |
| `clientA.reqReg` | 1 | 0 (dropped) |
| `clientA.state [1:0]` | HAVE_TOKEN (10) | NO_REQ (00) |

At **cycle 19 (190 ns)**, `io_ackA` is still **1** while `io_reqA` has transitioned to **0**, violating the assertion.

## 4. Root Cause Analysis

### Buggy Code Location

- **File**: `arbiter_le.scala`
- **Module**: `Controller` (lines 25–70)
- **Affected Logic**: The controller's BUSY state transition at lines 62–68, combined with the registered `ackReg` output at line 37 and 42

### Bug Description

The root cause is a **one-cycle delay** between the client dropping its request and the controller clearing its acknowledgement. This occurs because both the `Client` and `Controller` update their registers on the **same clock edge**, and the `Controller` evaluates `io.req` using the **old** value before the client's register update takes effect.

**Detailed timing at posedge 190 ns:**

1. **Before the clock edge**: Client A is in `HAVE_TOKEN` state with `reqReg=1`. The LFSR's random bit triggers a release.

2. **At the clock edge (cycle evaluation)**: In Verilog semantics, all register right-hand sides are evaluated using **old values**:
   - **Client A** (`ClientState.HAVE_TOKEN`): evaluates `rand_choice=1` → decides to set `reqReg=0`, `state=NO_REQ`
   - **Controller A** (`ControllerState.BUSY`): evaluates `when(!io.req)` — but `io.req` is **combinationally connected** to `clientA.reqReg`, whose **old value is 1**. So `!io.req = 0`, and the `when` branch is **NOT taken**.
   - Result: Controller stays in `BUSY`, `ackReg` remains **1**.

3. **After register update**: `clientA.reqReg` becomes 0, making `io_reqA=0`. But this is too late — the controller has already evaluated its condition and will not clear `ackReg` until the **next** clock edge.

**Code path that causes the issue:**

In `Controller` (lines 62–68):
```scala
is(ControllerState.BUSY) {
  when(!io.req) {
    state := ControllerState.IDLE
    ackReg := false.B
    pass_tokenReg := true.B
  }
}
```

And line 42:
```scala
io.ack := ackReg   // purely registered output
```

The `io.req` signal drops at the same time that the controller would need to react to it. Due to Verilog's non-blocking assignment semantics, the controller evaluates `io.req` at its old value (1), misses the transition, and keeps `ackReg=1` for one extra cycle.

### Category

This is a **Bug in the Original Design** (DUT bug). The assertion is correct — ack should never be high without a request. The top-level setup is correct. The `Controller` module's registered ack output creates a one-cycle inconsistency.

### Recommended Fix

Make the ack output **combinational** so it responds immediately when the request drops. The simplest fix is to gate `ackReg` with `io.req`:

```scala
io.ack := ackReg && io.req   // ack drops immediately when req drops
```

This ensures that even if `ackReg` stays high for one more cycle (due to the BUSY state not clearing it), the combinational AND with `io.req` prevents a spurious ack from appearing at the output when the request is already gone.

Alternatively, restructure the BUSY state transition to use a combinational check that anticipates the req drop:
```scala
io.ack := (state === ControllerState.READY) || (state === ControllerState.BUSY && io.req)
```
