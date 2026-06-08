# Counterexample Analysis Report: `ackA_implies_sel_A`

## 1. Verification Environment

### Top Module: `Main`
The `Main` module (defined in `arbiter3.scala`) instantiates:
- **3 Controller modules** (`Controller`): One for each client (A, B, C)
- **1 Arbiter module** (`Arbiter`): Round-robin arbiter cycling A→B→C→A
- **3 Client modules** (`Client`): Stochastic request generators using LFSR

### Key Connections
- `arbiter.io.sel` → `controller*.io.sel` → `Main.io.sel`
- `client*.io.req` → `controller*.io.req` → `Main.io.req*`
- `controller*.io.ack` → `Main.io.ack*`
- `controller*.io.pass_token` → OR'd together → `arbiter.io.active`
- `controller*.io.id` set to `Selection.A`, `Selection.B`, `Selection.C` respectively

### Design Under Test
The system is a round-robin arbiter with three clients. Each `Controller` manages a client's access to a shared resource via a 3-state FSM (IDLE→READY→BUSY), and the `Arbiter` cycles through selections when `active` is true.

## 2. Violated Assertion

### Assertion Name (from waveform filename)
`ackA_implies_sel_A`

### Code Snippet
```scala
// File: arbiter3.scala, line ~198
assertImplies(io.ackA, io.sel === Selection.A, "ackA_implies_sel_A")
```

### Property Description
When `io.ackA` (acknowledge for client A) is asserted high, the arbiter's selection (`io.sel`) must equal `Selection.A`. In other words, the arbiter should only grant the ack signal to client A when client A is currently selected.

### File Location
`arbiter3.scala`, approximately line 198 (in the `Main` class)

## 3. Waveform Information

### Waveform File
`verilog/extra_bench/param_arbiter3/Main.ackA_implies_sel_A.fst`

### Time Range and Key Time Points
| Time (ns) | Event |
|-----------|-------|
| 0 | System reset, arbiter state = A (00), active = 1 |
| 10 | Client A asserts req; io_sel transitions from A (00) to B (01) |
| 20 | pass_tokenA goes low (controllerA detects sel≠A); active → 0; io_sel → X (11) |
| 80 | pass_tokenB goes high; active → 1; io_sel → C (10) |
| 90 | pass_tokenB → 0; active → 0; io_sel → X (11) |
| 140 | pass_tokenC → 1; active → 1; io_sel → A (00) |
| **150** | **ControllerA: IDLE→READY** (isSelected goes high, sampled from cycle 140-149); active → 0; io_sel → X (11) |
| **160** | **ControllerA: READY→BUSY, ackReg → 1, io_ackA → 1; io_sel = X (11) — ASSERTION FAILS** |

### Critical Signal Values at Failure Point (t=160ns)
| Signal | Value | Meaning |
|--------|-------|---------|
| `Main.io_ackA` | 1 | Client A's ack is asserted |
| `Main.io_sel [1:0]` | 11 (X) | Arbiter outputs invalid state (not A, B, or C) |
| `Main.active` | 0 | No client has pass_token active |
| `Main.controllerA.state [1:0]` | 10 (BUSY) | ControllerA is in BUSY state |
| `Main.controllerA.ackReg` | 1 | ControllerA's ack register is set |
| `Main.controllerA.isSelected` | 1 | ControllerA thinks it's selected |
| `Main.controllerA.io_sel [1:0]` | 11 (X) | ControllerA sees sel = X |

## 4. Root Cause Analysis

### Buggy Code Location
**File**: `arbiter3.scala`
**Module**: `Controller` (lines 20-90)
**Bug Type**: Design bug (DUT bug) — the controller's ack pipeline is not synchronized with the arbiter's selection

### Description of the Bug

The `Controller` module has a 3-state FSM (IDLE → READY → BUSY) that generates the acknowledge signal:

```
IDLE:  when selected AND client has req → go to READY (set passTokenReg=0)
READY: unconditionally → go to BUSY (set ackReg=1)
BUSY:  when client drops req → go to IDLE (clear ackReg, set passTokenReg=1)
```

The controller **commits** to granting the ack when it transitions from IDLE to READY (one cycle after detecting it's selected). However, the ack is actually asserted one cycle later, when the state transitions from READY to BUSY. Between the commitment cycle (IDLE→READY) and the ack assertion cycle (READY→BUSY), the arbiter's selection can change.

### Evidence from Waveform

The failure follows this exact sequence:

1. **Cycle 140-149** (t=140ns to t=149ns): `io_sel = A (00)`, `io_id = A (00)` → equality holds → `isSelected` will go high at next posedge.

2. **Posedge at t=150ns**: `isSelected` goes high (registered from previous cycle). In IDLE state: `isSelected & io_req → state := READY, passTokenReg := false`. State transitions from IDLE(00) to READY(01). Also, `passTokenReg` was already 0 since time 20, so `active = pass_tokenA | pass_tokenB | pass_tokenC = 0 | 0 | 0 = 0`. With `active=0`, `arbiter.io_sel = Selection.X = 11`.

3. **Cycle 150-159**: ControllerA is in READY state, but `io_sel = X (11)`, NOT A.

4. **Posedge at t=160ns**: In READY state (unconditional): `state := BUSY, ackReg := true`. `io_ackA` goes high. But `io_sel` is still `X (11)`.

5. **Assertion violation**: `io_ackA = 1` but `io_sel = X ≠ A (00)`.

The root cause is a **2-cycle pipeline mismatch**: the controller uses the selection value from 2 cycles ago to assert ack, but the arbiter may have changed its selection in the meantime.

### Why This Is a DUT Bug

The property `ackA_implies_sel_A` is a reasonable safety invariant: it should never be possible to receive a grant for client A when client A is not currently selected. The design flaw is in the `Controller` module's FSM: it fails to re-verify that the selection is still valid when it transitions from READY to BUSY (where ack is actually asserted). 

A correct design would either:
- **(Option 1)** Check `isSelected` again when transitioning READY→BUSY (only assert ack if still selected)
- **(Option 2)** Hold the arbiter's selection stable while a grant is pending (the arbiter should not change sel while a controller is in the READY→BUSY pipeline)
- **(Option 3)** Assert ack directly in the READY state (one cycle earlier), before the arbiter has a chance to change its selection
