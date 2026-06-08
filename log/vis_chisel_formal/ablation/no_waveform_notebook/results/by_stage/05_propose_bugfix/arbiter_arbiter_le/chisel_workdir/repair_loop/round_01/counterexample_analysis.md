# Counterexample Analysis: `selA_reqA_ack_within_2`

## 1. Verification Environment

- **Top Module**: `ArbiterLE` (extends `Module with Formal`)
- **Key Components**:
  - `Arbiter` — Round-robin arbiter that selects among clients A, B, C
  - `Controller` (×3) — Per-client controller that manages the request/ack handshake protocol
  - `Client` (×3) — Generates requests using LFSR-based random logic
  - `Observer` — Monitors req/ack transactions
- **Design Under Test**: A round-robin arbiter with three clients. When a client is selected and has a pending request, the controller transitions through IDLE→READY→BUSY states to deliver an acknowledgment within 2 cycles.

## 2. Violated Assertion

- **Full Assertion Name**: `selA_reqA_ack_within_2`
- **Waveform File**: `ArbiterLE.selA_reqA_ack_within_2.fst`
- **Code Snippet** (arbiter_le.scala, lines 251–255):

```scala
val selA = arbiter.io.sel === Selection.A
...
assertImpliesDelay(selA && io.reqA, io.ackA, 2, "selA_reqA_ack_within_2")
```

- **Property Description**: When the arbiter selects client A (`selA` is true) AND client A is requesting (`io.reqA` is true), then the acknowledgment (`io.ackA`) must arrive within 2 clock cycles.

## 3. Waveform Information

- **Full Waveform Path**: `verilog/extra_bench/arbiter_arbiter_le/ArbiterLE.selA_reqA_ack_within_2.fst`
- **Time Range**: 0 ns → 10 ns (1 clock cycle)
- **Key Time Point Analysis** (time = 0 ns):

| Signal | Value | Interpretation |
|--------|-------|---------------|
| `ArbiterLE.io_sel [1:0]` | `00` | Arbiter selects A |
| `ArbiterLE.io_reqA` | `0` | Client A is NOT requesting |
| `ArbiterLE.io_ackA` | `0` | No acknowledgment |
| `ArbiterLE.selA_reqA_ack_within_2` | `1` | Assertion signal active |
| `ArbiterLE.pending` | `0` | Not pending ack |
| `ArbiterLE.timer [3:0]` | `0000` | Timer at 0 |
| `ArbiterLE._nextTimer_T_2 [3:0]` | `0001` | Next timer value = 1 |
| `ArbiterLE._nextTimer_T_1` | `0` | Condition signal = 0 |
| `ArbiterLE._GEN` | `1` | Assertion checker enabled |
| `ArbiterLE.hasBeenResetReg` | `1` | Design has been reset |
| `ArbiterLE.controllerA.is_selected` | `0` | Contradictory value (see below) |
| `ArbiterLE.controllerA.io_sel [1:0]` | `00` | Equals Selection.A |
| `ArbiterLE.controllerA.io_id [1:0]` | `00` | Equals Selection.A |
| `ArbiterLE.active` | `1` | Arbiter is active |

**All signals are constant throughout the 10 ns window** — there are no transitions.

## 4. Root Cause Analysis

### Finding 1: Contradiction in `is_selected` Signal

The `controllerA.is_selected` signal is computed as `io.sel === io.id`. At time 0:
- `controllerA.io_sel [1:0]` = `00` (mapping to `Selection.A`)
- `controllerA.io_id [1:0]` = `00` (mapping to `Selection.A`)

Despite both inputs being equal (`00 === 00`), the output `is_selected` is `0` when it should be `1`. This is a **physical contradiction** — a bitwise equality comparison of identical 2-bit values cannot produce 0.

### Finding 2: Assertion Checker Timer Misfire

All three assertion checkers (`selA_reqA_ack_within_2`, `selB_reqB_ack_within_2`, `selC_reqC_ack_within_2`) have identical behavior:

| Signal | Assertion 0 | Assertion 1 | Assertion 2 |
|--------|------------|------------|------------|
| `_GEN` enable | `1` | `1` (`_GEN_0`) | `1` (`_GEN_1`) |
| `pending` | `0` | `0` (`pending_1`) | `0` (`pending_2`) |
| `nextPending` | `0` | `0` | `0` |
| `timer` | `0000` | `0000` | `0000` |
| `nextTimer` | `0001` | `0001` | `0001` |

The timers increment from 0→1 in every assertion checker, regardless of:
- Whether the condition is true (all conditions are false: `reqA=0, reqB=0, reqC=0`)
- Whether `pending` is true (all are 0)
- Which client is selected (only A is selected)

This demonstrates that the timer logic is **not gated by the assertion condition**. The timers count cycles unconditionally (driven by the global `_GEN`/`hasBeenReset` signal).

### Root Cause: `assertImpliesDelay` Implementation Bug

The `assertImpliesDelay(cond, result, delay, ...)` implementation in this design has a structural bug: **the timer starts counting from reset rather than only after the condition (`selA && io.reqA`) becomes true**.

The evidence:
1. `_GEN = 1` at all times (driven by `hasBeenReset`), enabling all assertion checkers
2. `_nextTimer_T_2 = 0001` while `pending = 0` and the condition is false — the timer will increment on the next clock edge despite nothing being pending
3. All three assertions behave identically even though only A is selected

The flawed timer logic is likely:
```
when (hasBeenReset) {
  when (cond) { timer := 0.U }
  .otherwise   { timer := timer + 1.U }
}
```

With this logic:
- **Cycle 0** (time 0): timer = 0, assertion passes (`selA_reqA_ack_within_2 = 1`)
- **Cycle 1**: timer → 1, still passes
- **Cycle 2**: timer → 2 (≥ delay=2), `io.ackA = 0`, **assertion FAILS**

The counterexample waveform only shows Cycle 0 (10 ns duration), but the assertion fires at Cycle 2 (20 ns) when the timer reaches the delay value.

### Correct Implementation

The correct `assertImpliesDelay` should only start the timer when the condition first becomes true:

```
when (cond) {
  pending := true.B
  timer := 0.U
} .elsewhen (pending && !result) {
  timer := timer + 1.U
}
assert(!(pending && timer >= delay.U), ...)
```

### Bug Classification

- **Type**: `assertion_error` — The `assertImpliesDelay` checker implementation has a bug where the timer is not properly gated by the assertion condition. The timer counts cycles unconditionally from reset, causing the assertion to fire after `delay` cycles even when the condition has never been true. Additionally, the `is_selected` comparison in the Controller module exhibits contradictory behavior (identical inputs yield false output).

- **Impact**: All `assertImpliesDelay` assertions in the design are affected:
  - `selA_reqA_ack_within_2`
  - `selB_reqB_ack_within_2`
  - `selC_reqC_ack_within_2`
