# Counterexample Analysis Report: `ackA_implies_reqA`

## 1. Verification Environment

- **Benchmark**: `arbiter_arbiter_le`
- **Source File**: `arbiter_le.scala` (293 lines)
- **Top Module**: `ArbiterLE` (extends `Module with Formal`)

### Key Components and Connections

| Component | Module | Role |
|-----------|--------|------|
| Controller A/B/C | `Controller` | Per-client handshake controller (state machine: IDLE→READY→BUSY) |
| Arbiter | `Arbiter` | Round-robin selector (cycles A→B→C) |
| Client A/B/C | `Client` | Stimulus generators with LFSR-based random behavior |
| Observer | `Observer` | Monitors client behavior |

**Signal Flow**:
- Each `Controller.io.req` ← connected to corresponding `Client.io.req`
- Each `Client.io.ack` ← connected to corresponding `Controller.io.ack`
- `Arbiter.io.sel` → `Controller[ABC].io.sel` (shared selection line)
- `Controller[ABC].io.pass_token` → combined into `active` → `Arbiter.io.active`

---

## 2. Violated Assertion

- **Assertion Name**: `ackA_implies_reqA`
- **Waveform File**: `ArbiterLE.ackA_implies_reqA.fst`
- **Source Location**: `arbiter_le.scala`, line 259–260

```scala
// ----- Safety: No phantom acknowledgments -----
// An ack must only be issued when the corresponding client's request is active
fvAssert(!io.ackA || io.reqA, "ackA_implies_reqA")
```

**Natural Language Description**:  
The property states that at any cycle, if the acknowledgment signal for client A (`io.ackA`) is high, then the corresponding request signal (`io.reqA`) must also be high. In other words, the controller must never issue an acknowledgment to a client that is not currently requesting service—no "phantom" acknowledgments.

---

## 3. Waveform Information

- **Full Path**: `verilog/extra_bench/arbiter_arbiter_le/ArbiterLE.ackA_implies_reqA.fst`
- **Duration**: 20 cycles (200 ns), clock period = 10 ns
- **Key Time Point**: **190 ns** (rising clock edge) — the assertion transitions from 1→0 (fails)

### Critical Signal Values at Failure (time = 190 ns)

| Signal | Value | Meaning |
|--------|-------|---------|
| `ArbiterLE.ackA_implies_reqA` | **0** | Assertion FAILS |
| `ArbiterLE.io_ackA` | **1** | Controller A asserts ack |
| `ArbiterLE.io_reqA` | **0** | Client A has dropped its request |
| `ArbiterLE.controllerA.ackReg` | **1** | Controller's ack register is still high |
| `ArbiterLE.controllerA.state [1:0]` | **10 (BUSY)** | Controller is in BUSY state |
| `ArbiterLE.clientA.state [1:0]` | **00 (NO_REQ)** | Client has returned to NO_REQ state |
| `ArbiterLE.controllerA.pass_tokenReg` | **0** | Token not being passed |
| `ArbiterLE.controllerA.is_selected` | **1** | Controller A is selected by arbiter |

### Critical Signal Values at time 170 ns (one cycle before client drops req)

| Signal | Value | Meaning |
|--------|-------|---------|
| `ArbiterLE.io_ackA` | **1** | Controller A asserts ack |
| `ArbiterLE.io_reqA` | **1** | Client A has request active |
| `ArbiterLE.controllerA.state [1:0]` | **10 (BUSY)** | Controller A is in BUSY |
| `ArbiterLE.clientA.state [1:0]` | **10 (HAVE_TOKEN)** | Client A has received ack |

### Critical Signal Values at time 200 ns

| Signal | Value | Meaning |
|--------|-------|---------|
| `ArbiterLE.io_ackA` | **1** | Controller A still asserts ack (will clear at next edge) |
| `ArbiterLE.io_reqA` | **0** | Client A request still low |
| `ArbiterLE.controllerA.state [1:0]` | **10 (BUSY)** | Still BUSY (will transition at next edge) |

---

## 4. Root Cause Analysis

### Categorization: **DUT Bug (dut_bug)**

The bug is in the **`Controller`** module's BUSY state handler, which fails to clear the acknowledgment signal (`ackReg`) in the same cycle that the client drops its request.

### Buggy Code Location

**File**: `arbiter_le.scala`  
**Lines**: 86–91 (Controller's BUSY state case within the `switch(state)` block)

```scala
is(ControllerState.BUSY) {
  when(!io.req) {
    state := ControllerState.IDLE
    ackReg := false.B
    pass_tokenReg := true.B
  }
}
```

### Bug Description

The `Controller` module implements a three-state handshake protocol:
1. **IDLE**: Wait for `is_selected && io.req`
2. **READY**: One-cycle setup, then unconditionally transitions to BUSY and asserts `ackReg := true.B`
3. **BUSY**: Hold `ackReg` high until `!io.req` is detected, then transition back to IDLE

The bug manifests as a **one-cycle timing gap**: when the `Client` drops its request (`io.req` transitions from 1→0) while the `Controller` is in BUSY state with `ackReg` still asserted, the controller does NOT see the request drop at that same clock edge because both the controller's `ackReg` and the client's `reqReg` are registered and update simultaneously at the clock edge.

### Detailed Timing Sequence

| Time (ns) | Clock Edge | Event | Controller State | Controller ackReg | Client State | Client reqReg |
|-----------|-----------|-------|-----------------|-------------------|-------------|--------------|
| 10 | Rising | ClientA asserts req | IDLE (00) | 0 | REQ (01) | 1 |
| 150 | Rising | Arbiter selects A, Controller enters READY | READY (01) | 0 | REQ (01) | 1 |
| 160 | Rising | Controller enters BUSY, asserts ack | BUSY (10) | 1 | REQ (01) | 1 |
| 170 | Rising | ClientA sees ack, enters HAVE_TOKEN | BUSY (10) | 1 | HAVE_TOKEN (10) | 1 |
| **190** | **Rising** | **ClientA randomly drops req (LFSR rand_choice=1)** | **BUSY (10)** | **1** | **NO_REQ (00)** | **0** |
| | | Controller reads io_req=1 (old value before edge), stays BUSY | | | | |
| | | **→ ASSERTION FAILS: io_ackA=1, io_reqA=0** | | | | |
| 200 | Rising | Controller finally reads io_req=0 → transitions to IDLE | IDLE (00) | 0→ | NO_REQ (00) | 0 |

### Root Mechanism

The root mechanism is a **synchronous register update race**:

1. At the rising edge of time 190, both the `Client` and `Controller` registers update simultaneously.
2. The `Client` computes its next state based on HAVE_TOKEN logic: `when(rand_choice) { reqReg := false.B; state := ClientState.NO_REQ }`. Since `rand_choice` (LFSR bit 0) is true at this cycle, `reqReg` is set to 0.
3. The `Controller` computes its next state based on the **old** value of `io.req` (which is the client's `reqReg` before the edge, value = 1). Since `io.req` was 1 before the edge, the condition `when(!io.req)` evaluates to false, and the controller remains in BUSY with `ackReg` unchanged (=1).
4. After the edge, `io.reqA = client.reqReg = 0` (new value) while `io.ackA = controller.ackReg = 1` (still the old value).
5. The assertion `!io.ackA || io.reqA` evaluates to `!1 || 0 = 0`, and the assertion fails.

### Why This is a DUT Bug

The comment in the source code explicitly states: **"An ack must only be issued when the corresponding client's request is active"**. This is a fundamental safety property for any handshake protocol. The controller's current implementation cannot guarantee this property because:

- When the client drops its request (which is valid client behavior driven by the LFSR), the controller retains `ackReg=1` for one extra cycle.
- The controller should be designed to handle this by ensuring `ackReg` is cleared in the same cycle `io.req` goes low.

### Possible Fix

The `Controller`'s BUSY state handler should be modified to directly track `io.req` in the ack register update:

```scala
is(ControllerState.BUSY) {
  when(!io.req) {
    state := ControllerState.IDLE
    pass_tokenReg := true.B
  }
  ackReg := io.req  // ack follows req combinational in BUSY state (handles simultaneous req drop)
}
```

This ensures that when `io.req` transitions from 1→0 at the same clock edge, `ackReg` also transitions from 1→0 simultaneously, maintaining the invariant `ackA → reqA` at every cycle.
