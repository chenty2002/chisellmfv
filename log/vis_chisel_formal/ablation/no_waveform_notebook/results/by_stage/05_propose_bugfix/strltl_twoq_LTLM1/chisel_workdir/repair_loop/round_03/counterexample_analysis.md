# Counterexample Analysis Report: `no_request_no_grant`

## 1. Verification Environment

- **Top Module**: `twoQ` (in `twoqLTLM1.scala`)
- **Module Structure**:
  - `twoQ` instantiates two `sampleq` modules (`q0`, `q1`) and one `Buechi` module
  - `sampleq` implements a dual FIFO (read queue + write queue) with bus-request/grant interface
  - `twoQ` provides arbitration between the two queues based on `io.select` signal
- **Key Connections**:
  - `bus_req` (combinatorial wire) = concatenation of `q0.io.bus_req` and `q1.io.bus_req`
  - `bus_gnt` (register) = latched grant decision, computed combinatorially from `io.select` and `bus_req`
  - `q0.io.bus_gnt` = `bus_gnt(0)`, `q1.io.bus_gnt` = `bus_gnt(1)`

## 2. Violated Assertion

- **Assertion Name**: `no_request_no_grant`
- **Full Path**: `twoQ.no_request_no_grant`
- **Code Snippet** (from `twoqLTLM1.scala`, line ~274):

```scala
// Safety 5: If no queue requests, no grant is given
fvAssert(!(bus_req(0) || bus_req(1)) || bus_gnt.orR, "no_request_no_grant")
```

- **Property Description (Intended)**: "No request, no grant" — when neither queue is requesting the bus, no grant should be active.
- **Actual Check**: The assertion `!(bus_req(0) || bus_req(1)) || bus_gnt.orR` is logically equivalent to `(bus_req(0) || bus_req(1)) → bus_gnt.orR`, i.e., "if there is a request, then there must be a grant". This is the **inverse** of what the property name suggests.
- **File Location**: `twoqLTLM1.scala` (package `llmverify`), line 274 in the `twoQ` class

## 3. Waveform Information

- **Waveform File**: `verilog/extra_bench/strltl_twoq_LTLM1/twoQ.no_request_no_grant.fst`
- **Time Range**: 0 ns → 40 ns (4 clock cycles, clock period = 10 ns)
- **Key Time Points and Signal Values**:

| Time (ns) | `bus_req [1:0]` | `bus_gnt [1:0]` | `q0.io_bus_req` | `q0.io_bus_gnt` | `no_request_no_grant` |
|-----------|-----------------|-----------------|-----------------|-----------------|----------------------|
| 0         | 00              | 00              | 0               | 0               | 1 (pass)             |
| 10        | 01              | 00              | 1               | 0               | **0 (FAIL)**         |
| 20        | 01              | 01              | 1               | 1               | 1 (pass)             |
| 30        | 01              | 00              | 1               | 0               | **0 (FAIL)**         |
| 40        | 01              | 00              | 1               | 0               | 0 (FAIL)             |

- **Failure Points**: The assertion fails at times 10 ns and 30 ns.

## 4. Root Cause Analysis

### Root Cause Category: **Incorrect Assertion** (`assertion_error`)

### Detailed Analysis

The assertion `no_request_no_grant` is fundamentally flawed due to a **timing mismatch** between the combinatorial `bus_req` signal and the registered `bus_gnt` signal, combined with **inverted logical direction**.

#### Timing Architecture

In the `twoQ` module:

```scala
// bus_req is a COMBINATIONAL wire
val bus_req = Wire(UInt(2.W))
bus_req := Cat(q1.io.bus_req, q0.io.bus_req)

// bus_gnt is a REGISTER (latched at clock edge)
val bus_gnt = RegInit(0.U(2.W))
```

Because `bus_req` is combinatorial (derived directly from the `sampleq` modules' outputs) while `bus_gnt` is registered, there is an inherent **1-cycle delay**: when `q0` starts requesting the bus, `bus_req` reflects this immediately, but `bus_gnt` only updates on the next rising clock edge.

#### Failure at Time 10 ns (Cycle 1)

- **State at time 0**: `q0` receives `validin=1, readin=0, inaddr=00`. It writes to its write FIFO. `q0.writetail` becomes 1, `writeempty=false`, so `q0.io_bus_req` becomes 1.
- **At time 10**: `q0.io_bus_req=1` has settled combinatorially → `bus_req=01`. However, `bus_gnt` is a register still holding its reset value of `00` — it hasn't had a clock edge to update yet.
- **Assertion evaluates**: `!(bus_req(0) || bus_req(1))` = `!(1||0)` = `false`. `bus_gnt.orR` = `0`. Result: `false || false` = **false → FAIL**.
- **This is expected behavior**: the grant cannot appear in the same cycle as the request because `bus_gnt` is registered.

#### Failure at Time 30 ns (Cycle 3)

- **At time 20**: `io_select` becomes 1. The arbitration logic is:
  ```scala
  when(io.select && bus_req(1)) { bus_gnt := 2.U }    // q1 wins
  .elsewhen(!io.select && bus_req(0)) { bus_gnt := 1.U } // q0 wins
  .otherwise { bus_gnt := 0.U }
  ```
  Since `io_select=1` and `bus_req(1)=0`, neither condition is met, so `bus_gnt := 0`.
- **At time 30**: `bus_gnt=00` is latched. But `q0` still has pending data in its write FIFO (`writetail=10, writehead=00, writeempty=false`), so `q0.io_bus_req=1` and `bus_req=01`.
- **Assertion evaluates**: Same as before — `false || false` = **false → FAIL**.
- **Again expected behavior**: The arbitration logic legitimately deasserted the grant (because `io_select` changed and q1 is not requesting), but the request from q0 remains active because q0 still has data.

#### Logical Inversion

The **name** `no_request_no_grant` suggests the property: **"no request → no grant"**, i.e., `¬bus_req → ¬bus_gnt`, which is equivalent to `bus_gnt → bus_req` ("grant implies request").

However, the **code** implements: `¬bus_req ∨ bus_gnt`, which is `bus_req → bus_gnt` ("request implies grant").

These are logical inverses of each other. The coded assertion checks the wrong direction.

#### Existing Correct Assertion

The sibling assertion `grant_requires_request` (line 277) already correctly captures the intended property with proper timing:

```scala
fvAssert(!bus_gnt.orR || RegNext(bus_req).orR, "grant_requires_request")
```

This asserts: "if grant is active, then in the previous cycle there was a request" — which is the correct formulation of "no request, no grant" accounting for the 1-cycle register delay.

### Conclusion

The `no_request_no_grant` assertion is **incorrectly written** and should be fixed or removed. It suffers from two distinct errors:

1. **Wrong logical direction**: The assertion checks `request → grant` (if there's a request, grant must be active), rather than the intended `grant → request` (if grant is active, there must have been a request).
2. **Missing timing pipeline**: The spatial comparison uses combinatorial `bus_req` and registered `bus_gnt` without acknowledging the 1-cycle delay between them. The countersibling `grant_requires_request` correctly uses `RegNext(bus_req)`.

**Recommended Fix**: Either remove this assertion (it is redundant with the correct `grant_requires_request` assertion) or replace it with:

```scala
fvAssert(!bus_gnt.orR || RegNext(bus_req).orR, "no_request_no_grant")
```

which correctly captures "no request, no grant" with proper timing.
