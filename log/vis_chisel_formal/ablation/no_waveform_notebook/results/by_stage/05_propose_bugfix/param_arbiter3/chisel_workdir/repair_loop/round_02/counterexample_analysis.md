# Counterexample Analysis Report: `Main.liveness_reqA_ackA`

## 1. Verification Environment

- **Top module**: `Main` (from `arbiter3.scala`, line 135)
- **Waveform file**: `verilog/extra_bench/param_arbiter3/Main.liveness_reqA_ackA.fst`
- **Design structure**:
  - `Main` instantiates three `Controller` modules (A, B, C), one `Arbiter`, and three `Client` modules (A, B, C).
  - Each `Controller` manages token passing for its client.
  - The `Arbiter` cycles through selections A → B → C → A via a round-robin scheme when `active` is true.
  - The `active` signal is the OR of all three controllers' `pass_token` outputs.
  - Each `Client` uses an LFSR-based random number generator to drive its request signal.
  - The `Controller` pipeline: IDLE → READY → BUSY, where `ack` is asserted in the BUSY state.

## 2. Violated Assertion

- **Assertion name**: `liveness_reqA_ackA`
- **Source location**: `arbiter3.scala`, line 224
- **Assertion code** (Chisel):
  ```scala
  astRelaxedLiveness(io.reqA && io.sel === Selection.A, io.ackA, 10, "liveness_reqA_ackA")
  ```
- **Generated Verilog** (lines 289-291 of `Main.sv`):
  ```verilog
  wire nextPending = _resetCounter_notChaos & ~_controllerA_io_ack 
                   & (pending | _clientA_io_req & _GEN);
  // _GEN = _arbiter_io_sel == 2'h0  (sel == Selection.A)

  liveness_reqA_ackA:
    assert property (@(posedge clock) disable iff (~hasBeenReset)
                     ~nextPending | (_nextTimer_T_1 ? _nextTimer_T_2 : 4'h0) < 4'hB);
  ```
- **Property description**: This is a "relaxed liveness" assertion. It checks that whenever Client A has a pending request (`io.reqA`) AND the arbiter is selecting Client A (`io.sel === Selection.A`), then Client A should receive an acknowledgment (`io.ackA`) within 10 clock cycles. The property uses a `pending` flag (set when trigger condition is first met) and a `timer` that counts cycles until the ack arrives. If the timer reaches 11 (0xB), the assertion fails.

## 3. Waveform Information

- **Full waveform path**: `verilog/extra_bench/param_arbiter3/Main.liveness_reqA_ackA.fst`
- **Duration**: 0 ns to 170 ns (17 clock cycles)
- **Failure time**: The assertion signal (`Main.liveness_reqA_ackA`) transitions from 1 to 0 at **time 160 ns** (cycle 16).
- **Key signal values**:

| Time (ns) | Cycle | io_reqA | io_ackA | io_sel | active | ctrlA.state | pending | timer |
|-----------|-------|---------|---------|--------|--------|-------------|---------|-------|
| 0         | 0     | 0       | 0       | A(00)  | 1      | IDLE        | 0       | 0     |
| 10        | 1     | 1       | 0       | B(01)  | 1      | IDLE        | 0       | 0     |
| 50        | 5     | 1       | 0       | A(00)  | 1      | IDLE        | 0       | 0     |
| 60        | 6     | 1       | 0       | X(11)  | 0      | **READY**   | 1       | 0     |
| 70        | 7     | 1       | 0       | B(01)  | 1      | IDLE        | 1       | 1     |
| 80        | 8     | 1       | 0       | X(11)  | 0      | IDLE        | 1       | 2     |
| 110       | 11    | 1       | 0       | A(00)  | 1      | IDLE        | 1       | 5     |
| 120       | 12    | 1       | 0       | X(11)  | 0      | **READY**   | 1       | 6     |
| 130       | 13    | 1       | 0       | B(01)  | 1      | IDLE        | 1       | 7     |
| 150       | 15    | 1       | 0       | C(10)  | 1      | IDLE        | 1       | 9     |
| 160       | 16    | 1       | 0       | X(11)  | 0      | IDLE        | 1       | **10** |

- **Critical observation**: `io_ackA` remains **0 for the entire 170 ns** — Client A never receives an acknowledgment.

## 4. Root Cause Analysis

### Bug Location

- **File**: `arbiter3.scala`
- **Affected modules**: `Controller` (lines 20-67), `Arbiter` (lines 71-92), and the `Main.top` module's `active` signal wiring (line 183)
- **Bug type**: **Design Bug (DUT Bug)** — Livelock in the arbiter-controller handshake

### Description of the Bug

The controller requires **two consecutive cycles** with a matching selection to produce an acknowledgment:

1. **Cycle N** (IDLE state): If `isSelected & io_req` is true, move to READY state.
2. **Cycle N+1** (READY state): If `isSelected` is still true, move to BUSY state and assert `ackReg = 1`.

However, the system's `active` signal, which gates the arbiter's valid output, drops to 0 for one cycle after every valid selection, causing the controller pipeline to stall permanently.

**Detailed trace of the failure:**

1. **Time 50** (cycle 5): `io_sel = A(00)`, `active = 1`, `io_reqA = 1`, `ctrlA.state = IDLE`.
   - Controller A transitions: IDLE → READY. `passTokenReg <= 0` (since `isSelected & ~io_req = 1 & 0 = 0`).
   - At this time: `ctrlA.passTokenReg=0`, `ctrlB.passTokenReg=0`, `ctrlC.passTokenReg=1`. So `active = 0|0|1 = 1`.

2. **Time 60** (cycle 6): Controller A is in READY state.
   - But between cycles 5→6, Controller C's pass_token also became 0 (because it was not selected and in IDLE state: `passTokenReg <= isSelected & ~io_req = 0 & 0 = 0`).
   - Result: **all three pass_tokens are LOW**, so `active = 0`.
   - With `active = 0`, the arbiter outputs `io_sel = X(11) = Selection.X` (invalid).
   - Controller A sees `isSelected = 0` (since io_sel=X ≠ A), so in READY state: `state <= {0, 0} = IDLE`.
   - Controller A returns to IDLE **without ever reaching BUSY** (no ack generated).

3. **Time 60→70**: Controller A transitions READY → IDLE. In this transition, `passTokenReg <= ~isSelected | passTokenReg = 1 | 0 = 1`, making `passTokenReg = 1` again.

4. **Time 70**: `active = 1` again (Controller A's pass_token went high), arbiter advances to `sel = B(01)`.

5. **Time 110**: The same pattern repeats — `sel = A(00)` again at cycle 11, Controller A goes IDLE→READY at cycle 12, but `active` is 0 again, `sel = X`, and Controller A goes READY→IDLE at cycle 13 without producing ack.

### Root Cause Mechanism

The fundamental issue is an **interlocking timing mismatch** between the arbiter and the controllers:

- The `active` signal (`passTokenA | passTokenB | passTokenC`) serves as both the arbiter's enable and the gating signal for valid selection output.
- When a valid selection occurs, the selected controller's `passTokenReg` goes to 0 (because `isSelected & ~io_req = 0` when req=1).
- Other controllers that weren't selected also have their `passTokenReg` go to 0 (in IDLE state: `passTokenReg <= isSelected & ~io_req = 0 & ~req`).
- This causes `active` to drop to 0 for one cycle, making `io_sel = X` (invalid).
- When `io_sel = X`, the controller in READY state cannot proceed to BUSY, so it falls back to IDLE.
- This creates a **livelock**: the arbiter keeps cycling through clients, each controller enters READY and falls back to IDLE, but no ack is ever produced.

### Why the Assertion Fails

The assertion `liveness_reqA_ackA` triggers when `io.reqA && io.sel === Selection.A` is first true (at cycle 5, time 50). The `pending` flag is set and the `timer` starts incrementing. Since `ackA` never fires (remains 0 throughout), the timer eventually reaches 10 (0xA) at time 160, violating the bound of < 11 (0xB).

### Fix Recommendation

The design needs to ensure that once a controller is selected and transitions to READY, it has a guaranteed path to reach BUSY. One approach:

1. **Hold the selection valid for two consecutive cycles**: The arbiter should not change selection immediately after the controller enters READY. This could be done by latching the selection when a transition to READY occurs and holding it stable for one more cycle.

2. **Redesign the active/pass_token logic**: The `active` signal should remain asserted until a controller completes its token acquisition (reaches BUSY state), rather than dropping based on combinational pass_token values.

3. **Modify the controller pipeline**: Use a different handshake mechanism that doesn't require two consecutive cycles of the same selection.
