# Counterexample Analysis Report

## 1. Verification Environment

- **Top Module**: `Main` (from `reqAckRed.scala`)
- **TestTop Structure**: The top module `Main` instantiates a `ReqAck` module (`ra`), which in turn instantiates a `SlaveND` module (`slv`). A non-deterministic LFSR drives the `req` signal, which feeds into the request-acknowledge handshake controller.
- **Key Component Connections**:
  - `Main.req` (driven by LFSR) → `ra.io.req`
  - `ra.io.ack` → `Main.io_ack` (output)
  - `ra.io.start` → triggers the FSM transaction capture
  - `ra.slv.io.ready` → internal slave ready signal
  - `Main.pendingReq` → tracks outstanding unacknowledged requests
  - `Main.reqTriggered` → captures the specific request that triggered the current FSM transaction

## 2. Violated Assertion

- **Full Assertion Name**: `req_ack_not_concurrent`
- **Waveform File**: `Main.req_ack_not_concurrent.fst`
- **Code Snippet** (from `reqAckRed.scala`, lines 105-109):

```scala
val reqTriggered = RegInit(false.B)
when(ra.io.start)  { reqTriggered := RegNext(req, false.B) }  // capture req that triggered the FSM
when(io.ack)       { reqTriggered := false.B }                // clear when ack fires
fvAssert(!(reqTriggered && io.ack), "req_ack_not_concurrent")
```

- **Natural Language Description**: The property asserts that the specific request signal that triggered the current FSM transaction must never be concurrently high with the acknowledge signal. The FSM requires at least 3 cycles from the triggering request to the ack (idle→starting→working→done), so the ack should never coincide with the capture of that specific request.

- **File Location**: `reqAckRed.scala`, lines 105-109 (assertion at line 109)

## 3. Waveform Information

- **Full Waveform Path**: `verilog/extra_bench/reqack_reqAckRed/Main.req_ack_not_concurrent.fst`
- **Time Range**: 0 ns to 90 ns (9 cycles)
- **Failure Time**: **80 ns** (cycle 8, positive clock edge)
- **Key Time Points**:

| Time (ns) | Event |
|-----------|-------|
| 0 | Initial state, `req_ack_not_concurrent=1` (passing) |
| 10 | `Main.io_req` rises (first request, `req=1`) |
| 20 | `Main.ra.io_start` rises (FSM enters `starting` state), `pendingReq=1` |
| 30 | `Main.ra.state` → `working` (10), `reqTriggered=1` (captures the triggering req) |
| 50 | `Main.io_req` rises again (second request) |
| 70 | `Main.ra.slv.io_ready` rises (slave finishes, state transitions to `done` next cycle) |
| **80** | **Failure point**: `Main.io_ack=1`, `Main.reqTriggered=1`, assertion evaluates to `!(1 && 1)=0` |
| 80 | `req_ack_not_concurrent` falls to 0 (assertion violation) |

- **Critical Signal Values at Failure Point (80 ns)**:

| Signal | Value |
|--------|-------|
| `Main.req_ack_not_concurrent` | **0** (assertion FAILS) |
| `Main.io_ack` | **1** |
| `Main.reqTriggered` | **1** |
| `Main.req` | **0** (falling at 80) |
| `Main.io_req` | **0** (falling at 80) |
| `Main.ra.io_ack` | **1** |
| `Main.ra.state [1:0]` | **11** (done) |
| `Main.ra.slv.io_ready` | **0** (already deasserted) |

## 4. Root Cause Analysis

### Bug Location

- **File**: `reqAckRed.scala`
- **Line**: 108 (within the `Main` class)
- **Buggy Code**: `when(io.ack) { reqTriggered := false.B }`

### Description of the Bug

The bug is a **race condition** between the assertion evaluation and the `reqTriggered` clear assignment, caused by Verilog's non-blocking assignment semantics.

The design tracks `reqTriggered` — the specific request value that was sampled when the FSM transaction started. This signal is supposed to be cleared when `io.ack` fires. The sequence is:

1. **Time 70**: The slave (`SlaveND`) sets `io.ready=1` (count reaches 3). The FSM in `ReqAck` sees this and transitions from `working` to `done` at time 80.
2. **Time 80**: State becomes `done`, so `io.ack = (state === done)` becomes 1.
3. Also at **time 80**: the `when(io.ack) { reqTriggered := false.B }` block fires, scheduling a non-blocking assignment to clear `reqTriggered`.
4. Also at **time 80**: the assertion `fvAssert(!(reqTriggered && io.ack), ...)` is evaluated.

The problem is that **in Verilog simulation, non-blocking assignments (used by Chisel's `RegInit` and `:=`) take effect AFTER all right-hand-side evaluations in the same time step**. Therefore:

- The assertion reads `reqTriggered` (still **1**, because the non-blocking assignment hasn't taken effect yet)
- The assertion reads `io.ack` (which is **1**, driven by combinational logic)
- `!(1 && 1)` = `0` → assertion FAILS

The `reqTriggered` register does get updated to 0, but **too late** — the assertion in the same cycle already sees the stale value.

### Evidence from Waveform

The waveform trace confirms:

- `Main.reqTriggered` transitions from 0→1 at time 30 and **never returns to 0** throughout the entire simulation (the non-blocking clear at time 80 takes effect after the assertion evaluation, and there are no further cycles)
- `Main.io_ack` transitions from 0→1 at time 80 (the done state)
- At time 80, both signals are high simultaneously → assertion violation

### Why This Causes the Assertion to Fail

The intended behavior is that `reqTriggered` should be 0 when `io.ack` fires (since the triggering request happened at least 3 cycles earlier). However, the clear logic `when(io.ack) { reqTriggered := false.B }` uses the **same cycle's** `io.ack` signal as both the condition and the evaluation reference. The assertion `!(reqTriggered && io.ack)` also evaluates in the same cycle, creating a race condition where the clear takes effect too late.

### Fix Strategy

The fix is to clear `reqTriggered` **one cycle earlier**, using the internal `ra.io.ready` signal instead of `io.ack`. The `ra.io.ready` signal (mapped to `Main.ra.slv.io_ready`) rises at time 70 — one cycle before `io.ack` at time 80. By clearing `reqTriggered` at time 70, it will be stably 0 by the time `io.ack` fires at time 80, eliminating the race condition.

Proposed change (line 108):
```scala
// BEFORE (buggy):
when(io.ack)       { reqTriggered := false.B }
// AFTER (fixed):
when(ra.io.ready)  { reqTriggered := false.B }
```

This works because:
- `ra.io.ready` is the `ReqAck` module's `io.ready` output, which is driven by `slv.io.ready`
- When `io.ready` fires (state transitions to `done`), the slave has finished
- The ack will fire exactly one cycle later (state stays in `done` for one cycle)
- Clearing `reqTriggered` at `io.ready` guarantees it is 0 when `io.ack` fires the next cycle

### Error Classification: **dut_bug** (Bug in the Original Design)

The assertion itself is correct and captures a meaningful safety property. The top module setup is correct. The design has a genuine Verilog race condition between the assertion evaluation and the register clear logic in the same clock cycle.
