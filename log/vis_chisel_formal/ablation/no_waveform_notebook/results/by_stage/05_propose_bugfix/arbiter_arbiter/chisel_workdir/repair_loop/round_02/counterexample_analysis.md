# Counterexample Analysis: `reqA_eventually_gets_ack_when_active`

## 1. Verification Environment

- **Benchmark**: `arbiter_arbiter`
- **Top Module**: `Main` (from `arbiter.scala`)
- **Work Directory**: `chisel/extra_bench/arbiter_arbiter`
- **Waveform File**: `verilog/extra_bench/arbiter_arbiter/Main.reqA_eventually_gets_ack_when_active.fst`

### Structure

The top module `Main` instantiates:
- **3 Clients** (`Client`): Generate pseudo-random requests using a counter's LSB
- **3 Controllers** (`Controller`): Handle handshake per client; each has state machine IDLE→READY→BUSY→IDLE
- **1 Arbiter** (`Arbiter`): Cycles through selections A→B→C when active
- **Interconnect logic**: Controllers' `passToken` signals drive `active`, which drives the arbiter

### Key Connections
- `clientX.io.req → controllerX.io.req`
- `controllerX.io.ack → clientX.io.ack`
- `arbiterModule.io.sel → controllerX.io.sel` (shared)
- `activeWire = controllerA.io.passToken || controllerB.io.passToken || controllerC.io.passToken` → `arbiterModule.io.active`
- `arbiterModule.io.sel := Mux(active, state, Selection.X)`

---

## 2. Violated Assertion

- **Assertion Name**: `reqA_eventually_gets_ack_when_active`
- **Assertion Signal**: `Main.reqA_eventually_gets_ack_when_active` (goes from 1→0 at time 130ns)

### Code Snippet (arbiter.scala, line ~233)

```scala
astRelaxedLiveness(io.active && io.reqA, io.ackA, 10, "reqA eventually gets ack when active")
```

### Description

This is a **bounded liveness** property: whenever the precondition `io.active && io.reqA` is true (the system is active, and client A has a pending request), the postcondition `io.ackA` (client A receives an acknowledgment) must become true within **10 clock cycles**.

The assertion is located in the `Main` class at the end of `arbiter.scala`.

---

## 3. Waveform Information

- **Full Waveform Path**: `verilog/extra_bench/arbiter_arbiter/Main.reqA_eventually_gets_ack_when_active.fst`
- **Duration**: 14 cycles (0–140 ns, clock period = 10 ns)
- **Assertion fails at**: time **130 ns** (130 clock cycles after reset)

### Critical Signal Evolution

| Time (ns) | active | reqA | ackA | sel [1:0] | passTokenA | passTokenB | passTokenC | Notes |
|-----------|--------|------|------|-----------|------------|------------|------------|-------|
| 0 | 1 | 0 | 0 | 00 (A) | 1 | 1 | 1 | After reset; all passTokens=1 |
| 10 | 1 | 0 | 0 | 01 (B) | 1 | 0 | 0 | Active=1, controllerA selected but reqA=0 |
| 20 | 1 | 1 | 0 | 10 (C) | 0 | 1 | 0 | **reqA=1, active=1** but sel=C, controllerA not selected |
| 30 | 0 | 1 | 0 | 11 (X) | 0 | 0 | 0 | **active drops to 0** - all passTokens=0 |
| 40 | 0 | 1 | 0 | 11 (X) | 0 | 0 | 0 | Stuck - ackA never fires |
| 130 | 0 | 1 | 0 | 11 (X) | 0 | 0 | 0 | **Assertion fails here** |

### Controller A State Machine

| Time (ns) | controllerA.state | isSelected | passTokenReg | ackReg |
|-----------|-------------------|------------|-------------|--------|
| 0 | IDLE (00) | 0 | 1 | 0 |
| 10 | IDLE (00) | 1 | 1 | 0 |
| 20 | IDLE (00) | 0 | 0 | 0 |
| 30 | IDLE (00) | 0 | 0 | 0 |
| 40+ | IDLE (00) | 0 | 0 | 0 |

Controller A **never leaves IDLE** for the entire simulation.

---

## 4. Root Cause Analysis

### Bug Classification: **DUT Bug** (Design Liveness Bug)

### Buggy Code Location

**File**: `arbiter.scala`
**Module**: `Controller` 
**Lines**: ~85–99 (the IDLE state logic in the Controller's switch statement)

### Description of the Bug

The system contains a **liveness/deadlock bug** in the interaction between the Arbiter, Controller, and active-signal generation. The specific sequence that triggers the bug is:

1. **Arbiter cycles too fast**: The Arbiter cycles through A→B→C→A every clock cycle (lines 145–157) regardless of whether each client actually has a pending request.
   
2. **Mismatch between arbiter selection and client request timing**: Controller A is selected (isSelected=1) from time 0–10, but Client A's request doesn't arrive until time 20. By the time reqA=1, the arbiter has already moved to select C, and controller A is no longer selected.

3. **PassToken drops to zero, killing activity**: When controller A is in IDLE but NOT selected (`.otherwise` branch at line 99), it sets `passTokenReg := false.B`. Combined with other controllers also not selected, **all passTokens become 0**, causing `active = 0`.

4. **Arbiter stalls permanently**: Once `active = 0`, the arbiter stops cycling and outputs `sel = X` (null selection). With `sel = X`, no controller can ever be selected (isSelected=0 for all), making it impossible for any controller to transition out of IDLE.

5. **Request is never serviced**: Client A has `reqA=1` from time 20 onward, but since the arbiter is stalled (sel=X), controller A can never transition IDLE→READY→BUSY to generate `ackA`.

### Specific Bug Mechanism

The root cause is in the **Controller's IDLE state** (lines 85–99):

```scala
is(ControllerState.IDLE) {
  when(isSelected) {
    when(io.req) {
      state := ControllerState.READY
      passTokenReg := false.B  // safety: drop token when servicing
    }.otherwise {
      passTokenReg := true.B   // keep token alive while selected but idle
    }
  }.otherwise {
    passTokenReg := false.B    // ← BUG: drops token when not selected,
                               //   even if client has a pending request
  }
}
```

When a controller is NOT selected but its client HAS a pending request, the controller still sets `passTokenReg = false`. This can cause the entire system to become inactive (all passTokens=0), which stalls the arbiter permanently.

### Evidence from Waveform

1. **Time 10**: `controllerA.isSelected = 1`, `reqA = 0` → controllerA stays in IDLE with `passTokenReg = 1` (correct behavior)

2. **Time 20**: `controllerA.isSelected = 0` (arbiter moved to C), but `reqA = 1` (client just made request) → controllerA sets `passTokenReg = 0` because `!isSelected`, even though reqA is pending

3. **Time 30**: All passTokens = 0 (passTokenA=0, passTokenB=0, passTokenC=0), so `active = 0`, `sel = X` → system enters deadlock

4. **Time 20–130**: `active=0` and `reqA=1` continuously, but no ackA ever fires → assertion violation detected at time 130

### Why the Fix is Needed

The design needs to ensure that the `active` signal (and consequently, the arbiter cycling) continues as long as any client has a pending request. The current logic allows `active` to drop to zero while clients still have outstanding requests, creating a deadlock scenario.

A correct fix would either:
- Keep `passToken` high (or have another mechanism to keep `active` high) when a client has a pending request but is not currently selected, so the arbiter continues cycling
- Or modify the arbiter to wait at each client until the request is serviced
- Or modify the active signal logic to account for pending requests, not just token passing state
