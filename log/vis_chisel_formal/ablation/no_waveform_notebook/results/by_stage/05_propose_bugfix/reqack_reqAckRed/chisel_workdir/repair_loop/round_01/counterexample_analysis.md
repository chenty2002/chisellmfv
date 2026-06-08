# Counterexample Analysis Report: `ack_one_cycle_pulse`

## 1. Verification Environment

### Top Module
- **Module**: `Main` (defined in `reqAckRed.scala`, line 6)
- **Structure**: Contains a `ReqAck` handshake module and LFSR-based non-deterministic stimulus generator

### Key Components
- **Main** (top): Generates non-deterministic `req` signal via LFSR, instantiates `ReqAck`
- **ReqAck**: 4-state handshake controller (idle → starting → working → done)
- **SlaveND**: Counts cycles with non-deterministic delay, signals `ready` when count reaches 3

### Connections
- `Main.req` (register updated by LFSR bit 0) → `ReqAck.io.req`
- `ReqAck.io.ack` → `Main.io.ack`
- `ReqAck.io.start` → `SlaveND.io.start`
- `SlaveND.io.ready` → `ReqAck.io.ready`

## 2. Violated Assertion

### Assertion Name
`ack_one_cycle_pulse` (extracted from waveform filename: `Main.ack_one_cycle_pulse.fst`)

### Location
`reqAckRed.scala`, line 40

### Code Snippet
```scala
// Safety: Once ack fires, it must de-assert next cycle.
// The done state transitions back to idle immediately, so ack is a single-cycle pulse.
assertNextStepWhen(ra.io.ack, !ra.io.ack, "ack_one_cycle_pulse")
```

### Property Description
The assertion `assertNextStepWhen(ra.io.ack, !ra.io.ack)` specifies:
> Whenever `ra.io.ack` is asserted (true), in the **next clock cycle** it must be de-asserted (false).

This is a **safety property** ensuring the acknowledge signal is a single-cycle pulse, as required by the req/ack handshake protocol.

## 3. Waveform Information

### Waveform File
`verilog/extra_bench/reqack_reqAckRed/Main.ack_one_cycle_pulse.fst`

### Time Range
0 ns to 90 ns (9 clock cycles, clock period = 10 ns)

### Key Time Points and Signal Values

| Time (ns) | Clock | ra.state | ra.io_ack | ra.io_ready | ra.io_req | req | count |
|-----------|-------|----------|-----------|-------------|-----------|-----|-------|
| 0         | rising| 00 (idle)| 0         | 0           | 0         | 0   | 00    |
| 10        | rising| 00 (idle)| 0         | 0           | 1         | 1   | 01    |
| 20        | rising| 01 (start)|0         | 0           | 0         | 0   | 10    |
| 30        | rising| 10 (work)| 0         | 0           | 0         | 0   | 00    |
| 40        | rising| 10 (work)| 0         | 0           | 0         | 0   | 00    |
| 50        | rising| 10 (work)| 0         | 0           | 1         | 1   | 01    |
| 60        | rising| 10 (work)| 0         | 0           | 1         | 1   | 10    |
| 70        | rising| 10 (work)| 0         | **1**       | 1         | 1   | **11**|
| **80**    | rising| **11 (done)**| **1** | 0           | 0         | 0   | 00    |
| **90**    | -     | **11 (done)**| **1** | 0           | 0         | 0   | 00    |

### Assertion Failure
- `Main.ack_one_cycle_pulse` transitions from `1` (passing) to `0` (failing) at **time 80 ns**
- At time 80: `ra.io_ack` = 1 (first asserted)
- At time 90: `ra.io_ack` = 1 (still asserted — **violation**)

## 4. Root Cause Analysis

### Bug Location
**File**: `reqAckRed.scala`
**Module**: `ReqAck` (line 52)
**Buggy Logic**: State machine `is(done)` clause (lines 83-85)

### Bug Description

The **done-to-idle state transition fails to execute**, causing the state machine to remain in the `done` state for multiple cycles.

The state machine code (lines 83-85):
```scala
is(done) {
  state := idle
}
```

This is an **unconditional** transition from `done` (state value `11`) to `idle` (state value `00`). However, the waveform evidence shows:

1. **At time 70** (clock rising edge): State is `10` (working), `io_ready=1`
2. **At time 80** (clock rising edge): State transitions to `11` (done) correctly — the working→done transition works
3. **At time 80** (after the edge): The done→idle transition should compute `next_state = idle (00)`
4. **At time 90**: State remains `11` (done) — **the done→idle transition did NOT execute**

### Evidence from Waveform

| Signal | Time 80 | Time 90 | Expected at Time 90 |
|--------|---------|---------|---------------------|
| `Main.ra.state [1:0]` | `11` (done) | `11` (done) | `00` (idle) |
| `Main.ra.io_ack` | `1` | `1` | `0` |
| `Main.ra.io_ready` | `0` | `0` | — |
| `Main.ra.io_req` | `0` | `0` | — |

The state value `11` persists from time 80 through time 90, demonstrating that the register did not capture the `idle` value. Since the `is(done)` clause unconditionally assigns `state := idle`, this indicates the **assignment is not taking effect** — the state register's input remains `11` (done) rather than `00` (idle).

### Why the Assertion Fails

The assertion `assertNextStepWhen(ra.io.ack, !ra.io.ack)` requires:

- **Cycle N** (time 80): `ra.io.ack = 1` → triggers the check
- **Cycle N+1** (time 90): `ra.io.ack` must be `0`

Because the state machine is **stuck in `done`** (state=`11`), the output `io.ack := (state === done)` remains `1` at time 90, violating the one-cycle pulse property.

### Root Cause Classification

**Bug in the Original Design (dut_bug)**: The `ReqAck` state machine has a genuine defect where the `done → idle` transition fails to execute, causing the acknowledge signal to persist for multiple cycles instead of being a single-cycle pulse. The state machine works correctly for the `idle→starting→working→done` path but fails at the `done→idle` return transition. This suggests either:

1. A synthesis/compilation issue in the Chisel-generated Verilog where the `is(done)` case is not properly emitted or connected
2. A potential naming conflict where the `done` identifier (used as both a state enum value and potentially elsewhere) causes the transition logic to be dropped or miswired during code generation

The fix should ensure the `done → idle` transition is properly implemented in the generated hardware.
