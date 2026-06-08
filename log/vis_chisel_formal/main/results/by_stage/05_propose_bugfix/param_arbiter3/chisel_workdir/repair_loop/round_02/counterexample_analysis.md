# Counterexample Analysis Report: `ackB_implies_selB`

## 1. Verification Environment

- **Top Module**: `Main` (defined in `arbiter3.scala`, line 133)
- **Structure**: The design is a round-robin arbiter system with three token-passing controllers, three client modules, and one round-robin arbiter.
  - `Arbiter`: Round-robins through states A→B→C→A when `active` is high. Outputs `io.sel` to indicate which client is selected.
  - `Controller` (×3): Each controller monitors the arbiter's selection and its own client's request. When selected and requested, it enters READY→BUSY state and asserts `ack`. It uses `passTokenReg` for token-passing to determine `active`.
  - `Client` (×3): Each client makes pseudo-random requests using an LFSR and waits for `ack`.
- **Key Connections**:
  - `arbiter.io.sel` feeds all three controllers' `io.sel` inputs
  - Each controller's `io.id` is hardwired to its respective `Selection` value (A=00, B=01, C=10)
  - `active = controllerA.io.pass_token || controllerB.io.pass_token || controllerC.io.pass_token`
  - `arbiter.io.active := active`

## 2. Violated Assertion

- **Assertion Name**: `ackB_implies_selB`
- **Full Assertion** (line 209 of `arbiter3.scala`):
  ```scala
  AssertProperty(!io.ackB || (io.sel === Selection.B), "ackB_implies_selB")
  ```
- **Property**: If `io.ackB` is asserted (high), then `io.sel` must equal `Selection.B` (01). In other words, client B should only receive an acknowledgement when the arbiter has selected client B.
- **File**: `arbiter3.scala`, line 209

## 3. Waveform Information

- **Waveform File**: `verilog/extra_bench/param_arbiter3/Main.ackB_implies_selB.fst`
- **Duration**: 40 ns (4 clock cycles at 10 ns period)
- **Key Time Points**:
  - **t=0 ns (posedge 0)**: All controllers have `passTokenReg=true`, `active=1`. Arbiter starts in state A (00). Clients idle (no requests).
  - **t=10 ns (posedge 1)**: All three clients generate requests simultaneously. Arbiter transitions A→B (01). ControllerB is selected and enters READY state.
  - **t=20 ns (posedge 2)**: `active` goes low (all pass tokens cleared). Arbiter sets `io.sel=11` (Selection.X). ControllerB transitions READY→BUSY.
  - **t=30 ns (posedge 3) — ASSERTION FAILURE**: ControllerB asserts `ackB=1`, but `io.sel=11` (X) does not equal `Selection.B` (01).
- **Critical Signal Values at t=30 ns**:
  - `Main.io_ackB` = **1** (ack asserted)
  - `Main.io_sel [1:0]` = **11** (Selection.X, not Selection.B=01)
  - `Main.io_active` = **0** (all pass tokens cleared)
  - `Main.controllerB.state [1:0]` = **10** (BUSY)
  - `Main.controllerB.ackReg` = **1**
  - `Main.controllerB.io_req` = **1**
  - `Main.controllerB.isSelected` = **1** (FST value; see note below)
  - `Main.controllerB.io_sel [1:0]` = **11** (X)
  - `Main.controllerB.io_id [1:0]` = **01** (Selection.B)
  - `Main.ackB_implies_selB` = **0** (assertion failed)

## 4. Root Cause Analysis

### Bug Location

- **File**: `arbiter3.scala`
- **Buggy Module**: `Arbiter` (lines 68–92)
- **Buggy Line**: Line 77 — `io.sel := Mux(io.active, state, Selection.X)`

### Description of the Bug

The Arbiter module outputs `Selection.X` (binary 11) when `io.active` is low:
```scala
io.sel := Mux(io.active, state, Selection.X)
```

This means that when all pass tokens are cleared (`active=0`), the arbiter withdraws its selection and outputs the special "none selected" value `X`. However, the Controller module's state machine continues to operate independently: if a controller is already in the process of asserting an acknowledgement, it will do so even after the arbiter has withdrawn the selection.

### Sequence Leading to Failure

1. **t=0–10**: Initially all pass tokens are 1, so `active=1`. Arbiter selects A (00). ControllerB (id=01) is not selected, so its `passTokenReg` is cleared to `false.B` by the `is(ControllerState.IDLE).otherwise` branch (line 51).

2. **t=10**: All three clients simultaneously assert requests (LFSR randChoice triggers). Arbiter transitions to state B (01). ControllerB is selected, enters READY→BUSY path. But by this point, controllerB's `passTokenReg` is already `false` (cleared in step 1), controllerA's `passTokenReg` was cleared because it's no longer selected (line 51), and controllerC was never selected either. **All pass tokens are now 0**, so `active=0`.

3. **t=10–20**: The arbiter sees `active=0` and sets `io.sel = Selection.X = 11` (line 77). ControllerB, now selected with `io.sel=01` during this cycle (before the arbiter output updates? Actually, the arbiter's `io.sel` is combinatorial with respect to `io.active`), enters READY state.

   **Detailed timing**: During cycle 10–20, the arbiter's combinational output `io.sel := Mux(io.active, state, Selection.X)` evaluates with the values at the start of the cycle. After the posedge at t=10, `active` was just computed with the new passTokenReg values (all 0). But the arbiter's `state` was updated to B(01) at the same posedge. Since `active=0`, `io.sel=X(11)` immediately. So during the entire cycle 10–20, `io.sel=11`.

   Meanwhile, controllerB evaluates its state machine: during cycle 10–20, it's still in IDLE state (state only updates at the next posedge). With `isSelected = (11===01) = 0`, controllerB's IDLE state hits the `.otherwise` branch setting `passTokenReg := false.B` (already false). **But wait** — controllerB entered READY based on the previous cycle's (t=0–10) values where `isSelected` was true. Let me re-check...

   Actually, the `isSelected` is combinational. At time 10 (posedge):
   - `arbiter.state` updates from A→B
   - `arbiter.io.sel` becomes `Mux(0, B, X)` = X(11) because `active` is already 0
   - So during cycle 10–20, controllerB sees `io_sel=11`, which doesn't match `io_id=01` → `isSelected=0`

   But controllerB was supposed to enter READY based on `isSelected` being true during cycle 0–10. Let me re-check:
   
   During cycle 0–10, `io_sel = A(00)` (arbiter in state A). `controllerB.isSelected = (00===01) = 0`. So controllerB never entered READY during cycle 0–10 either!

   **Wait, that can't be right.** Let me re-trace more carefully.

   At time 0 (initial posedge):
   - All passTokenRegs = 1 (RegInit(true.B))
   - active = 1
   - arbiter.state = A(00), io.sel = A(00)
   - controllerB.isSelected = (00===01) = 0
   
   Cycle 0–10:
   - controllerB (IDLE, isSelected=0): passTokenReg := false.B (line 51)
   - arbiter.io.sel = A(00), active=1, arbiter.state advances to B(01)

   At time 10 (posedge):
   - passTokenRegA=1, passTokenRegB=0, passTokenRegC=0
   - active = 1 || 0 || 0 = 1
   - arbiter.state becomes B(01), arbiter.io.sel = Mux(1, B, X) = B(01)
   - All clients generate requests
   - controllerB (IDLE, isSelected=(01===01)=1, req=1): state := READY, passTokenReg := false.B (already 0)
   - controllerA (IDLE, isSelected=(01===00)=0): passTokenReg := false.B
   - controllerC (IDLE, isSelected=(01===10)=0): passTokenReg := false.B
   - New passTokenRegs: A=0, B=0, C=0 → active = 0

   At time 20 (posedge):
   - active = 0 (computed during cycle 10–20)
   - arbiter.io.sel = Mux(0, B, X) = X(11) (computed during cycle 10–20)
   - controllerB was IDLE during cycle 10–20, now becomes READY (state update)
   - In READY: state := BUSY, ackReg := true.B

   At time 30 (posedge):
   - ackReg becomes 1
   - io_ackB = ackReg && io_req = 1 && 1 = 1
   - io_sel = 11 (X) ≠ Selection.B(01)
   - **ASSERTION FAILS**

4. **t=30**: `controllerB.ackReg` becomes 1, so `io.ackB = 1 && 1 = 1`. But at this moment, `io.sel` has been `11` (X) since t=20, because `active` has been 0 since t=10. The assertion `!io.ackB || (io.sel === Selection.B)` evaluates to `!1 || (11 === 01)` = `false || false` = **false**.

### Root Cause

The fundamental issue is a **design interaction bug** between the Arbiter and the Controller:

1. **Arbiter (line 77)**: When `active=0`, the arbiter immediately sets `io.sel` to `Selection.X` (value 11), which doesn't match any client ID. This is too aggressive — it withdraws the selection before the currently serviced controller has finished its transaction.

2. **Controller (line 38)**: The controller's ack output `io.ack := ackReg && io.req` does not check whether the controller is currently selected (`isSelected`). This means the controller continues to assert ack even after the arbiter has withdrawn the selection.

The design assumes that `active` remains high while any controller is being serviced, but the pass token clearing logic in the IDLE state (lines 50–51) can clear all pass tokens simultaneously when a controller other than the currently selected one was previously selected. ControllerB's token was cleared during cycle 0–10 (when A was selected), and by the time B gets selected (cycle 10–20), its token is already gone — causing `active=0` and the arbiter to withdraw the selection prematurely.

### Evidence from Waveform

| Time | `io_sel` | `io_ackB` | `controllerB.state` | `active` | Assertion |
|------|----------|-----------|---------------------|----------|-----------|
| 0    | 00 (A)   | 0         | 00 (IDLE)           | 1        | 1 (pass)  |
| 10   | 01 (B)   | 0         | 00 (IDLE)           | 1        | 1 (pass)  |
| 20   | 11 (X)   | 0         | 01 (READY)          | 0        | 1 (pass)  |
| 30   | 11 (X)   | **1**     | 10 (BUSY)           | 0        | **0 (fail)** |

At t=30, `io_ackB=1` but `io_sel=11≠01`, directly violating the assertion.

### Proposed Fix

**Option B (Fix Arbiter)** — Remove the `Selection.X` fallback so the arbiter always outputs the current state:

```scala
// line 77 of arbiter3.scala
// io.sel := Mux(io.active, state, Selection.X)
io.sel := state
```

This ensures that when `active` goes low, the arbiter keeps outputting the last selected client's ID rather than switching to `X`. Since the arbiter's `state` only advances when `active=1`, the state remains stable during idle periods. The selected controller can then complete its transaction while the assertion sees `io.sel === Selection.B` hold true.

**Alternative (Fix Controller)** — Gate `io.ack` with `isSelected`:
```scala
// line 38 of arbiter3.scala
// io.ack := ackReg && io.req
io.ack := ackReg && io.req && isSelected
```
This would suppress the ack when the arbiter has withdrawn selection. However, this could cause clientB to never receive an ack (getting stuck in REQ state forever), potentially breaking liveness properties.

### Error Classification

**Category: `dut_bug`** — The design has a genuine interaction bug between the Arbiter and Controller modules. The assertion correctly captures a safety property that should hold in a well-designed arbiter system, and the failure is caused by incorrect design logic rather than an incorrect assertion or TestTop setup.
