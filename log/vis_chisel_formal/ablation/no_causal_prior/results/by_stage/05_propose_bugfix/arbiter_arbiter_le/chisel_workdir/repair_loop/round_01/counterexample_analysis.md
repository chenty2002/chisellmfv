# Counterexample Analysis Report: `ackA_implies_reqA`

## 1. Verification Environment

### Top Module
- **Module**: `ArbiterLE` (in `arbiter_le.scala` line 161)
- **Formal framework**: Chisel FV (`with Formal`)

### Structure and Connections

```
ArbiterLE
├── controllerA (Controller, id=Selection.A)
│   ├── io.req ←── clientA.io.req
│   ├── io.ack ──→ clientA.io.ack, io.ackA
│   ├── io.sel ←── arbiter.io.sel
│   └── io.pass_token ──→ arbiter.io.active (via OR-tree)
├── controllerB (Controller, id=Selection.B)
│   └── ... (same pattern)
├── controllerC (Controller, id=Selection.C)
│   └── ... (same pattern)
├── arbiter (Arbiter, round-robin)
│   ├── io.active ←── OR of all pass_token signals
│   └── io.sel ──→ all controllers
├── clientA (Client, random stimulus generator)
│   ├── io.req ──→ controllerA.io.req, io.reqA
│   └── io.ack ←── controllerA.io.ack
├── clientB (Client)
├── clientC (Client)
└── observer (Observer)
```

### Key Components

| Component | State Machine | Behavior |
|-----------|--------------|----------|
| **Controller** | IDLE → READY → BUSY → IDLE | Manages token passing: asserts ack when granted, clears when req drops |
| **Client** | NO_REQ → REQ → HAVE_TOKEN → NO_REQ | Generates random requests via LFSR; drops req randomly in HAVE_TOKEN |
| **Arbiter** | A → B → C → A (round-robin) | Cycles through clients when active |
| **Observer** | IDLE → BAD → GOOD | Monitors protocol compliance |

---

## 2. Violated Assertion

### Full Assertion Name
`ackA_implies_reqA` (from waveform filename: `ArbiterLE.ackA_implies_reqA.fst`)

### Code Snippet
From `arbiter_le.scala`, line 240:
```scala
fvAssert(!io.ackA || io.reqA, "ackA_implies_reqA")
```

### Property Description
**Safety property**: Whenever the acknowledgment signal for client A (`io.ackA`) is asserted, the corresponding request signal (`io.reqA`) must also be asserted. In other words, `ackA ⇒ reqA` (ack implies req).

This is a **combinational** assertion, evaluated at all times (not just at clock edges), ensuring that an acknowledgment is never given when no request is pending.

### File Location
- **File**: `arbiter_le.scala`, line 240

---

## 3. Waveform Information

### Waveform File
`verilog/extra_bench/arbiter_arbiter_le/ArbiterLE.ackA_implies_reqA.fst`

### Time Range
0 ns → 200 ns (20 clock cycles, period = 10 ns)

### Key Time Points and Signal Values

| Time (ns) | io_ackA | io_reqA | ControllerA.state | ControllerA.ackReg | ClientA.state | ClientA.reqReg | Event Description |
|-----------|---------|---------|-------------------|-------------------|--------------|---------------|-------------------|
| 10        | 0       | 1       | 00 (IDLE)         | 0                 | 01 (REQ)     | 1             | ClientA asserts req (rand_choice) |
| 150       | 0       | 1       | 01 (READY)        | 0                 | 01 (REQ)     | 1             | ControllerA selected, enters READY |
| 160       | **1**   | 1       | 10 (BUSY)         | **1**             | 01 (REQ)     | 1             | ControllerA enters BUSY, asserts ack |
| 170       | 1       | 1       | 10 (BUSY)         | 1                 | 10 (HAVE_TOKEN) | 1         | ClientA receives ack, enters HAVE_TOKEN |
| 190       | **1**   | **0**   | 10 (BUSY)         | **1**             | 00 (NO_REQ)  | **0**         | **ASSERTION FAILS**: ack=1, req=0 |
| 200       | 1       | 0       | 10 (BUSY)         | 1                 | 00 (NO_REQ)  | 0             | (too late, next edge) |

### Failure Point
- **Time**: 190 ns
- **io_ackA** = 1
- **io_reqA** = 0
- **Assertion** `!io_ackA || io_reqA` = `!1 || 0` = `0` (violated)

---

## 4. Root Cause Analysis

### Buggy Code Location
**File**: `arbiter_le.scala`
**Module**: `Controller` (lines 25–70)
**Bug location**: Lines 42, 62–68

### Bug Description

The bug is a **timing mismatch between the registered acknowledgment output and the combinational assertion**.

#### How `io.ackA` is driven (line 42):
```scala
io.ack := ackReg    // registered, updates only at clock edges
```

#### How `io.reqA` is driven (client, line 104):
```scala
io.req := reqReg    // also registered
```

#### How the controller clears ack (lines 62–68, BUSY state):
```scala
is(ControllerState.BUSY) {
  when(!io.req) {
    state := ControllerState.IDLE
    ackReg := false.B    // ← cleared on NEXT clock edge
    pass_tokenReg := true.B
  }
}
```

When the client (in `HAVE_TOKEN` state, lines 123–128) randomly drops `reqReg` at time 190:
```scala
is(ClientState.HAVE_TOKEN) {
  when(rand_choice) {
    reqReg := false.B     // ← drops at time 190
    state := ClientState.NO_REQ
  }
}
```

The following sequence occurs:

1. **Time 190 (posedge)**: ClientA's `rand_choice` is true → `reqReg` transitions from 1→0, `io_reqA` drops to 0.
2. **Same cycle (time 190)**: ControllerA is in BUSY state with `ackReg=1` → `io_ackA = ackReg = 1`. Although the BUSY state's `when(!io.req)` condition is now met, the transition to IDLE and the clearing of `ackReg` only happens at the **next clock edge** (time 200).
3. **Time 190 (combinational)**: `io_ackA = 1` but `io_reqA = 0` → **assertion violation**.

### Root Cause Category
**Bug in the Original Design (DUT Bug)**

### Evidence from Waveform

| Signal | Trace | Implication |
|--------|-------|-------------|
| `ArbiterLE.io_ackA` | 0→**1** at time 160 (stays 1 through 190) | ack is registered, persists after req drops |
| `ArbiterLE.io_reqA` | 1→**0** at time 190 | req drops in same cycle assertion fails |
| `ArbiterLE.clientA.reqReg` | 1→0 at time 190 | Client drops req (rand_choice in HAVE_TOKEN) |
| `ArbiterLE.clientA.state` | 10(HAVE_TOKEN)→00(NO_REQ) at 190 | Client exits HAVE_TOKEN by dropping req |
| `ArbiterLE.controllerA.state` | 10(BUSY) through time 190–200 | Controller hasn't yet transitioned to IDLE |
| `ArbiterLE.controllerA.ackReg` | 1 through time 160–200 | ackReg persists for 1 extra cycle after req drops |

### Why This Is a Design Bug

The controller's `io.ack` is purely registered (`io.ack := ackReg`). When the client drops `io.req`, the controller sees `!io.req` and schedules the transition to IDLE (with `ackReg := false.B`) for the **next clock cycle**. However, the assertion `!io_ackA || io_reqA` is a **combinational** check that must hold at all times. This creates a one-cycle window where `req` is low but `ack` is still high.

### Fix Suggestion

The controller's `io.ack` output should be a **combinational** function that ensures `ack` goes low immediately when `req` goes low. For example, change line 42 from:

```scala
io.ack := ackReg
```

to:

```scala
io.ack := ackReg && io.req
```

This ensures that `ack` de-asserts combinatorially (in the same delta cycle) when `req` drops, satisfying the assertion `!io.ack || io.req` at all times. The state machine can still transition from BUSY→IDLE on the next clock edge for proper internal bookkeeping.

Alternatively, the client should be constrained to only drop `req` when `ack` is low, but since the client is a stimulus generator, the design should be robust to this scenario.
