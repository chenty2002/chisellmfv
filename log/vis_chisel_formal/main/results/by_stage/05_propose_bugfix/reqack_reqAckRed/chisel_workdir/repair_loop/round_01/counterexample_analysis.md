# Counterexample Analysis Report: `req_ack_not_concurrent`

## 1. Verification Environment

- **Top Module**: `Main` (in `reqAckRed.scala`)
- **Major Components**:
  - `Main` (top-level): Contains an LFSR-based non-deterministic `req` generator, instantiates `ReqAck`, and four formal assertions.
  - `ReqAck`: A 4-state FSM (idle → starting → working → done) that processes one request at a time, instantiates `SlaveND` for variable-latency slave response.
  - `SlaveND`: Simulates a non-deterministic slave with a 2-bit counter; asserts `io.ready` when `count === 3` (binary `11`).
- **Stimulus**: `req` is driven by an LFSR (`Main.nd`), toggling pseudo-randomly each cycle without any handshake feedback from the `ReqAck` module.
- **Assertions under test**:
  1. `req_rise_leads_to_ack` (bounded liveness)
  2. `ack_is_single_cycle` (safety)
  3. `ack_only_when_req_pending` (safety)
  4. `req_ack_not_concurrent` (safety) — **THE FAILING ASSERTION**

## 2. Violated Assertion

- **Full Assertion Name**: `req_ack_not_concurrent` (from waveform filename: `Main.req_ack_not_concurrent.fst`)
- **Code Snippet** (file `reqAckRed.scala`, line 54):
  ```scala
  fvAssert(!(req && io.ack), "req_ack_not_concurrent")
  ```
- **Natural Language Description**: The assertion requires that the `req` (request) and `io.ack` (acknowledge) signals are never simultaneously high. It assumes the FSM guarantees that ack cannot coincide with any request.
- **File Location**: `reqAckRed.scala`, line 54

## 3. Waveform Information

- **Waveform File**: `verilog/extra_bench/reqack_reqAckRed/Main.req_ack_not_concurrent.fst`
- **Duration**: 18 cycles (0–180 ns)
- **Failure Time**: t = 170 ns (assertion deasserted from 1 → 0)
- **Critical Signal Values at Failure Point (t = 170 ns)**:

| Signal | Value | Description |
|--------|-------|-------------|
| `Main.req` | 1 | Request is high (new request arrived at t=160) |
| `Main.io_ack` | 1 | Ack is high (previous request completed) |
| `Main.req_ack_not_concurrent` | **0** | **Assertion FAILS** |
| `Main.ra.state [1:0]` | 11 (done) | FSM in done state |
| `Main.ra.slv.count [1:0]` | 00 | Slave count reset |
| `Main.ra.slv.io_ready` | 0 | Ready deasserted after transition to done |
| `Main.pendingReq` | **1** | Pending request flag is true (passing assertion #3) |

## 4. Root Cause Analysis

### Classification: **Assertion Error (incorrect assertion)**

The assertion `!(req && io.ack)` is **too strict** for the design's behavior. The design intentionally allows `req` to be driven independently by the LFSR without any handshake back-pressure, and the FSM correctly handles new requests that arrive while a transaction is in progress.

### Detailed Sequence of Events

The counterexample unfolds as follows:

1. **t=110**: LFSR drives `req=1` (rising edge). `Main.ra.state` is `00` (idle). FSM begins processing.
2. **t=120**: FSM enters `01` (starting). `req` sampled as 0 at this edge.
3. **t=130**: FSM enters `10` (working). Slave count begins incrementing.
4. **t=140**: **New request arrives** (`req=1`) while FSM is in `10` (working). The state machine correctly ignores it while working — only the `idle` state reacts to `req`. However, `pendingReq` is set true.
5. **t=150**: `req=0`.
6. **t=160**: **Another new request** (`req=1`) arrives. Slave `count` reaches `11` → `io_ready=1`. The FSM captures `io_ready` and will transition to `done` at next clock.
7. **t=170**: FSM enters `11` (done). `io_ack=1`. **But `req` is still 1** (from t=160). The assertion `!(req && io_ack)` evaluates to `!(1 && 1) = 0`, **FAILING**.

### Why This Is an Assertion Error (Not a DUT Bug)

- The `ReqAck` state machine correctly handles the scenario:
  - At t=170, state is `done`. The `is(done)` block unconditionally sets `state := idle`.
  - At the next clock edge (t=180), state becomes `idle`. The `is(idle)` block sees `req=1` and transitions to `starting` — a new transaction begins correctly.
- The **`pendingReq` safety assertion (#3) passes** at t=170 (`pendingReq=1`, confirming the ack is valid).
- The **`ack_is_single_cycle` assertion (#2) passes** (ack is only high for one cycle).
- The **`req_rise_leads_to_ack` assertion (#1) passes** (all requests eventually receive an ack).

The design properly handles the condition where a new request arrives before the previous ack has been delivered. The FSM finishes the current transaction, asserts ack for one cycle, then immediately starts servicing the pending new request. The `pendingReq` register correctly tracks outstanding requests and validates that every ack corresponds to at least one pending request.

### Root Cause Explanation

The assertion `!(req && io_ack)` on line 54 of `reqAckRed.scala` makes an unrealistic assumption: it requires the external request stimulus to be back-pressured by the ack handshake. However, the LFSR-based `req` generator drives `req` independently. Since the LFSR can assert `req` while the FSM is in the `working` state processing a previous request, `req` can remain high (or be re-asserted) when the FSM reaches `done` and asserts `io_ack`. This concurrent high condition is **harmless** — the FSM handles it correctly at the next clock cycle — but the assertion flags it as a violation.

### How to Fix

The assertion should be **removed or replaced** with a weaker property. The `pendingReq` assertion (#3, `ack_only_when_req_pending`) already covers the relevant safety property: ack should only fire when there is an outstanding unacknowledged request. The `req_ack_not_concurrent` assertion is redundant and imposes an unrealistic constraint.
