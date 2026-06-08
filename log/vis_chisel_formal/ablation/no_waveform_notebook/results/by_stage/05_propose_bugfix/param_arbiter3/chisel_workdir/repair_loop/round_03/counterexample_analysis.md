# Counterexample Analysis: `liveness_reqB_ackB`

## 1. Verification Environment

- **Top Module**: `Main` (from `arbiter3.scala`)
- **Waveform File**: `verilog/extra_bench/param_arbiter3/Main.liveness_reqB_ackB.fst`
- **Design Structure**:
  - Three `Controller` instances (`controllerA`, `controllerB`, `controllerC`) — each handles arbitration for one client
  - One `Arbiter` instance that cycles through selections A→B→C→A when `active` is true
  - Three `Client` instances (`clientA`, `clientB`, `clientC`) that generate requests based on LFSR random timing
  - The `active` signal is the OR of all `pass_token` signals from controllers
- **Connections**: Each controller's `io.sel` connects to the arbiter's `io.sel` output, and each controller's `io.req` connects to its corresponding client's `io.req`

## 2. Violated Assertion

- **Assertion Name**: `liveness_reqB_ackB` (from waveform filename)
- **Code Snippet** (line 195 of `arbiter3.scala`):
  ```scala
  astRelaxedLiveness(io.reqB && io.sel === Selection.B, io.ackB, 10, "liveness_reqB_ackB")
  ```
- **Property Description**: Whenever client B has a pending request (`io.reqB = true`) AND the arbiter selects client B (`io.sel === Selection.B`), then `io.ackB` must be asserted within 10 clock cycles.
- **File Location**: `chisel/extra_bench/param_arbiter3/arbiter3.scala`, line 195

## 3. Waveform Information

- **Full Waveform Path**: `verilog/extra_bench/param_arbiter3/Main.liveness_reqB_ackB.fst`
- **Waveform Duration**: 0 ns – 1350 ns (135 cycles)
- **Key Time Points**:

| Time (ns) | Event |
|-----------|-------|
| 1200 | `controllerB.state` = IDLE (00), `isSelected`=1, `io_req`=1 → **ackB=1** (same-cycle ack) |
| 1210 | `controllerB.state` → BUSY (10), `isSelected` → 0, ackB → 0 |
| 1230 | **Trigger fires**: `io_reqB`=1, `io_sel`=01 (B), but `controllerB.state`=BUSY → ackB=0 |
| 1240 | `pending_1` → 1 (liveness timer starts) |
| 1250–1330 | `timer_1` counts 1→2→3→...→9 |
| 1260 | `controllerB` still BUSY, `isSelected`=1, `io_req`=1 → ackB=0 |
| 1280 | `io_reqB` → 0 |
| 1290 | `controllerB.state` → IDLE, but `io_reqB`=0 → ackB=0 (no request to ack) |
| 1320 | `controllerB` IDLE, `isSelected`=1, `io_reqB`=0 → ackB=0 |
| 1340 | `io_reqB`→1, but `io_sel`=00 (A) → ackB=0; **timer reaches 10 → assertion fails** |

### Critical Signal Values at Trigger Point (time 1230)

| Signal | Value | Meaning |
|--------|-------|---------|
| `Main.controllerB.state [1:0]` | `10` | BUSY state |
| `Main.controllerB.io_req` | `1` | Client B has pending request |
| `Main.controllerB.io_sel [1:0]` | `01` | Arbiter selects B |
| `Main.controllerB.isSelected` | `1` | sel matches id |
| `Main.controllerB.io_ack` | `0` | **No ack generated** |
| `Main.io_reqB` | `1` | External reqB is high |
| `Main.io_sel [1:0]` | `01` | External sel is B |
| `Main.io_ackB` | `0` | **No ack** |
| `Main.pending_1` | `0` | Liveness not yet pending (registers next cycle to 1) |
| `Main.timer_1 [3:0]` | `0000` | Timer not yet started |

## 4. Root Cause Analysis

### Bug Classification: **Design Bug (dut_bug)**

### Buggy Code Location

**File**: `chisel/extra_bench/param_arbiter3/arbiter3.scala`
**Module**: `Controller`
**Lines**: 59–72 (BUSY state handling)

### Bug Description

The `Controller` module has a single-cycle pipeline (IDLE→BUSY) where ack is generated combinatorially when `state === IDLE && isSelected && io.req`. After acknowledging, the controller transitions to BUSY. The problem is in the BUSY state handler:

```scala
is(ControllerState.BUSY) {
    when(!io.req) {
        state := ControllerState.IDLE
        passTokenReg := true.B
    }
}
```

The controller **only** exits BUSY when `io.req` drops to 0. It does **not** handle the case where the client is re-selected (by the arbiter cycling back to this client) while still having a pending request. Consequently:

1. When the arbiter selects B again (as it does every 3 cycles: A→B→C→A), `isSelected` becomes true and `io_req` is still 1.
2. The ack logic (`(state === IDLE) && isSelected && io.req`) evaluates to 0 because `state` is BUSY, not IDLE.
3. The controller remains stuck in BUSY, unable to generate any new ack, until `io_req` eventually drops.

### Evidence from Waveform

1. **Time 1200**: The first ack works correctly — `controllerB.state=IDLE`, `isSelected=1`, `io_req=1` → ackB=1. Controller transitions to BUSY at time 1210.

2. **Time 1230–1270**: The arbiter cycles back to B (sel=01 at times 1230 and 1260). At both times, `controllerB.state=BUSY`, `isSelected=1`, `io_req=1` → **no ack** (ackB=0).

3. **Time 1280**: Client B finally drops `io_reqB` to 0.

4. **Time 1290**: ControllerB finally returns to IDLE (one cycle after req drops). But now `io_req` is 0, so the ack condition is not met.

5. **The liveness timer** (`pending_1`) started at time 1240 (registered from the trigger at 1230) and counted 1→2→...→10 by time 1340, at which point the assertion fails because ackB never became 1 again.

### Why This Causes Assertion Failure

The liveness assertion `astRelaxedLiveness(io.reqB && io.sel === Selection.B, io.ackB, 10, ...)` checks that whenever the trigger condition (`reqB && sel===B`) is true, ackB appears within 10 cycles. The trigger becomes true at time 1230 (reqB=1, sel=B). The ackB last was asserted at time 1200 (before the trigger) and never fires again because:

- From time 1210 to 1280: controllerB is stuck in BUSY (cannot ack)
- From time 1290 onward: controllerB is IDLE but reqB is 0 (no request to ack)
- At time 1340: reqB goes high but sel is A, not B

The controller's BUSY→IDLE transition depends solely on `io.req` dropping, but there is no mechanism to re-acknowledge the client when re-selected while BUSY. This creates a window where the client has a pending request and the arbiter selects it, but no ack is generated, violating the liveness property.

### Suggested Fix

Modify the Controller's BUSY state handling to re-enter IDLE (or directly re-ack) when the controller is re-selected while in BUSY, for example:

```scala
is(ControllerState.BUSY) {
    when(!io.req) {
        state := ControllerState.IDLE
        passTokenReg := true.B
    }.elsewhen(isSelected) {
        // Re-selected while busy: go back to IDLE so ack can fire next cycle
        state := ControllerState.IDLE
    }
}
```

This allows the controller to return to IDLE when re-selected, enabling the combinational ack logic to fire in the same (or next) cycle when `io_req` is still high.
