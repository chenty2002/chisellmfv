# Counterexample Analysis Report

## 1. Verification Environment

### Top Module Structure
- **Top Module**: `ArbiterLE` (line 238 of `arbiter_le.scala`)
- **Sub-modules**:
  - `resetCounter` — Reset stabilization counter
  - `arbiter` — Round-robin arbiter (class `Arbiter`, 3 clients)
  - `controllerA`, `controllerB`, `controllerC` — Three client controllers (class `Controller`)
  - `observerA`, `observerB`, `observerC` — Three observer modules (class `Observer`, for formal checks)
- **Key Connections**:
  - All controllers share the arbiter's `io.sel` selection bus
  - Each controller's `io.pass_token` feeds into the top-level `active` computation (line 253: `io.active := io.pass_tokenA || io.pass_tokenB || io.pass_tokenC`)
  - The arbiter cycles through clients A→B→C→A→... when `io.active` is true; when false, outputs `Selection.X` (binary `11`)
- **Design Under Test (DUT)**: A token-ring arbiter with 3 clients, where clients randomly request and are acknowledged in round-robin order

## 2. Violated Assertion

- **Assertion Name**: `At_most_one_ack_per_cycle` (from waveform filename `ArbiterLE.At_most_one_ack_per_cycle.fst`)
- **Code Location**: `arbiter_le.scala`, line 252
- **Assertion Code**:
  ```scala
  fvAssert(assertMutex(Seq(io.ackA, io.ackB, io.ackC), "At most one ack per cycle"))
  ```
- **Property Description**: Mutual exclusion across all three acknowledge signals — at most one of `io.ackA`, `io.ackB`, `io.ackC` may be high at any time. This ensures the arbiter only grants the token to one client per cycle.

## 3. Waveform Information

- **Waveform File**: `verilog/extra_bench/arbiter_arbiter_le/ArbiterLE.At_most_one_ack_per_cycle.fst`
- **Duration**: 6 cycles (0 ns → 60 ns)

### Failure Point: Time = 50 ns
At time 50 ns, both `io.ackB` and `io.ackC` are high simultaneously:

| Signal | Value at 50 ns |
|--------|---------------|
| `ArbiterLE.io_ackA` | 0 |
| `ArbiterLE.io_ackB` | **1** |
| `ArbiterLE.io_ackC` | **1** |
| `ArbiterLE.io_sel [1:0]` | 01 (Selection.B) |
| `ArbiterLE.io_active` | 1 |
| `ArbiterLE.io_reqA` | 1 |
| `ArbiterLE.io_reqB` | 1 |
| `ArbiterLE.io_reqC` | 1 |
| `ArbiterLE.io_pass_tokenA` | 0 |
| `ArbiterLE.io_pass_tokenB` | **1** |
| `ArbiterLE.io_pass_tokenC` | **1** |

### Internal State at Time 50 ns

| Internal Signal | Value at 50 ns |
|----------------|---------------|
| `ArbiterLE.controllerB.state [1:0]` | 10 (BUSY) |
| `ArbiterLE.controllerB.ackReg` | 1 |
| `ArbiterLE.controllerB.io_req` | 1 |
| `ArbiterLE.controllerC.state [1:0]` | 10 (BUSY) |
| `ArbiterLE.controllerC.ackReg` | 1 |
| `ArbiterLE.controllerC.io_req` | 1 |
| `ArbiterLE.controllerA.state [1:0]` | 01 (READY) |
| `ArbiterLE.controllerA.ackReg` | 0 |
| `ArbiterLE.arbiter.state [1:0]` | 01 (Selection.B) |

**Both controllerB and controllerC are in BUSY state with ackReg=1 and io_req=1, producing simultaneous io_ack signals.**

## 4. Root Cause Analysis

### Bug Classification: **Bug in the Original Design (DUT Bug)**

### Buggy Location
- **File**: `arbiter_le.scala`
- **Line**: 212–213 (in class `Controller`, `BUSY` state)
- **Relevant mechanism**: Lines 204–220 (pass_token computation and BUSY state logic)

### Description of the Bug

The token-ring arbiter design has a **fundamental sequencing flaw**: the arbiter continues to rotate its selection (A→B→C→A→...) while a controller is in the BUSY state, rather than stalling the selection until the current controller finishes servicing.

**How the bug manifests:**

1. **Cycle 1 (time 10–20)**: `io_sel=01` (Selection.B). ControllerB is selected (`io_id_B=01`), enters READY state.

2. **Cycle 2 (time 20–30)**: `io_sel=11` (Selection.X) as `io_active` briefly dips to 0. ControllerB transitions from READY to BUSY at time ~20–30, setting `ackReg=1`. From this point, controllerB starts asserting `io.ackB=1`.

3. **Cycle 3 (time 30–40)**: `io_sel=10` (Selection.C). ControllerC becomes selected (`io_id_C=10`), enters READY state. **Crucially, controllerB is still BUSY and acking**, but the arbiter has already moved to select controllerC.

4. **Cycle 4 (time 40–50)**: `io_sel=00` (Selection.A). ControllerC transitions from READY to BUSY, setting `ackReg=1`.

5. **Cycle 5 (time 50–60)**: Both controllerB and controllerC are simultaneously in BUSY state with `ackReg=1` and `io_req=1`, producing `io.ackB=1` AND `io.ackC=1` — **violating mutual exclusion**.

**Root cause mechanism in detail:**

The `Controller.pass_token` logic at line 219–220 is:
```scala
io.pass_token := pass_tokenReg || (state === ControllerState.BUSY && io.req)
```

When a controller enters BUSY with `io.req=1` (as controllerB does at time 30), `io.pass_token` becomes `true`. This keeps `io.active=true`, which tells the arbiter to keep rotating. The arbiter, interpreting `active=true` as "there are pending requests, keep cycling," moves to the next client (C), which also has a pending request, causing it to enter the BUSY/ack pipeline too.

**The design intent** was likely that pass_token should keep the arbiter "aware" of the active request, but the arbiter incorrectly uses this signal to advance to the next client rather than to stall on the current one.

### Evidence from Waveform

| Time | ControllerB | ControllerC | io.ackB | io.ackC |
|------|------------|------------|---------|---------|
| 20 ns | READY (01), ackReg=0 | IDLE (00), ackReg=0 | 0 | 0 |
| 30 ns | **BUSY (10), ackReg=1** | IDLE (00), ackReg=0 | **1** | 0 |
| 40 ns | BUSY (10), ackReg=1 | **READY (01), ackReg=0** | 1 | 0 |
| 50 ns | BUSY (10), ackReg=1 | **BUSY (10), ackReg=1** | **1** | **1** ← FAILURE |

### Why This Causes the Assertion to Fail

The assertion `assertMutex(Seq(io.ackA, io.ackB, io.ackC))` requires that at most one of the three ack signals be high at any time. At time 50 ns, both `io.ackB` and `io.ackC` are high because both controllerB and controllerC are in the BUSY state, which asserts `ackReg=true` and outputs `io.ack := ackReg && io.req`. Since both have `io_req=1`, both ack signals are driven high simultaneously.

### Proposed Fix Direction

The fix should ensure the arbiter does not select a new client while an existing client is still being serviced (in BUSY state). Options include:

1. **Gate pass_token for BUSY controllers**: Modify pass_token logic so a BUSY controller de-asserts pass_token, causing `active=false` and the arbiter to output `Selection.X`. However, this would need to be paired with changes to the BUSY state to stop acking when de-selected, to avoid violating the "No acks when sel is X" assertion.

2. **Stall the arbiter on BUSY**: Modify the Arbiter to not advance its state when the currently selected controller is BUSY (however, the arbiter lacks visibility into controller states).

3. **Add cross-controller gating**: Add a mechanism to prevent a controller from entering BUSY if another controller is already in BUSY — effectively a global mutual-exclusion lock on the BUSY state.

The most appropriate fix depends on the designer's intent, but option 1 (fix pass_token and ack gating) is the most aligned with a correct token-ring protocol.
