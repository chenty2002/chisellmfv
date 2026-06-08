# Counterexample Analysis Report: `twoQ.bus_gnt_one_hot`

## 1. Verification Environment

### Top Module and Structure
- **Top Module**: `twoQ` (from `twoqLTLM1.scala`, line 155)
- **Module Hierarchy**:
  - `twoQ` — Top-level bus arbiter and queue manager
    - `q0` (Instance of `sampleq`) — Queue 0, requests bus when it has data
    - `q1` (Instance of `sampleq`) — Queue 1, requests bus when it has data
    - `buechi` (Instance of `Buechi`) — Buchi automaton for fairness/liveness checking
    - `resetCounter` — Reset counter for formal verification

### Key Components and Connections
- **Bus Grant Register** (`bus_gnt[1:0]`): A 2-bit register initialized to `00` (RegInit(0.U(2.W)))
  - `bus_gnt[0]` → Grant for Queue 0 (q0)
  - `bus_gnt[1]` → Grant for Queue 1 (q1)
- **Arbitration Logic**: 
  - When `io.select && bus_req(1)` → `bus_gnt := 2.U` (q1 gets grant)
  - When `!io.select && bus_req(0)` → `bus_gnt := 1.U` (q0 gets grant)
  - Otherwise → `bus_gnt := 0.U` (no grant issued)
- **Bus Requests**: `bus_req = Cat(q1.io.bus_req, q0.io.bus_req)`

### Design Under Test
The `twoQ` module is a bus arbiter that grants access to one of two queues (q0, q1) based on the `select` signal. When `select` is high, q1 gets priority; when `select` is low, q0 gets priority. Each queue requests the bus when it has data (`!readempty & !writeempty`). The design includes a Buechi automaton for tracking fairness properties.

---

## 2. Violated Assertion

### Assertion Name
`bus_gnt_one_hot` (derived from waveform filename: `twoQ.bus_gnt_one_hot.fst`)

### Code Snippet
From `generated/twoQ.sv` lines 340–342:
```systemverilog
bus_gnt_one_hot:
    assert property (@(posedge clock) disable iff (~hasBeenReset)
                     {1'h0, bus_gnt[0]} + {1'h0, bus_gnt[1]} == 2'h1);
```

From `twoqLTLM1.scala` line 249:
```scala
assertOneHot(bus_gnt, "bus_gnt_one_hot")
```

### Property Description
The assertion checks that `bus_gnt` is **exactly one-hot** (i.e., exactly one bit is set to 1) at every positive clock edge when the design has been reset (`hasBeenReset` is true). In other words, `bus_gnt` must be either `01` (q0 granted) or `10` (q1 granted), but never `00` (no grant) or `11` (both granted).

### File Location
- **Scala source**: `twoqLTLM1.scala`, line 249
- **Generated Verilog**: `generated/twoQ.sv`, lines 340–342

---

## 3. Waveform Information

### Waveform File
- **Full path**: `verilog/extra_bench/strltl_twoq_LTLM1/twoQ.bus_gnt_one_hot.fst`
- **Duration**: 1 cycle (0 ns to 10 ns)
- **Key time points**: Only one clock edge at time 0 ns

### Critical Signal Values at Failure Point (time 0 ns)

| Signal | Value | Interpretation |
|--------|-------|---------------|
| `twoQ.clock` | 1 | Positive clock edge (assertion triggered) |
| `twoQ.reset` | 0 | Reset deasserted |
| `twoQ.hasBeenReset` | 1 | Assertion is ACTIVE (disable condition false) |
| `twoQ.hasBeenResetReg` | 1 | Reset has occurred |
| `twoQ.bus_gnt [1:0]` | **00** | **No grant issued — violates one-hot property** |
| `twoQ.io_select` | 0 | Select signal is low |
| `twoQ.io_bus_req [1:0]` | 00 | No bus requests from either queue |
| `twoQ.q0.io_bus_req` | 0 | Queue 0 is not requesting the bus |
| `twoQ.q1.io_bus_req` | 0 | Queue 1 is not requesting the bus |
| `twoQ.q0.io_bus_gnt` | 0 | Queue 0 not granted |
| `twoQ.q1.io_bus_gnt` | 0 | Queue 1 not granted |

---

## 4. Root Cause Analysis

### Buggy Code Location
- **File**: `twoqLTLM1.scala`
- **Line**: 249
- **Code**: `assertOneHot(bus_gnt, "bus_gnt_one_hot")`

### Error Classification: **Incorrect Assertion** (`assertion_error`)

### Description of the Bug

The `assertOneHot` assertion is **too strict** for this design. The Chisel Formal library's `assertOneHot` generates a check that **exactly one bit** must be set to 1 (verification of `{1'h0, bus_gnt[0]} + {1'h0, bus_gnt[1]} == 2'h1`). This means `bus_gnt` must always be `01` or `10`, never `00`.

However, the bus arbiter is designed to issue **no grant (`00`)** when neither queue is requesting the bus under the matching select condition:

```scala
// twoqLTLM1.scala lines 210-216
when(io.select && bus_req(1)) {
    bus_gnt := 2.U(2.W)        // Grant to q1
}.elsewhen(!io.select && bus_req(0)) {
    bus_gnt := 1.U(2.W)        // Grant to q0
}.otherwise {
    bus_gnt := 0.U(2.W)        // No grant (idle state)
}
```

Additionally, `bus_gnt` is initialized to `00` via `RegInit(0.U(2.W))` (line 199), which is the correct idle/initial state.

### Evidence from Waveform

At time 0 ns (the only cycle in the counterexample):
1. **`twoQ.bus_gnt [1:0] = 00`** — Both grant bits are zero
2. **`twoQ.io_select = 0`** — Select is low
3. **`twoQ.io_bus_req [1:0] = 00`** — Neither queue is requesting the bus
4. **`twoQ.q0.io_bus_req = 0` and `twoQ.q1.io_bus_req = 0`** — Confirmed: no requests
5. **`twoQ.hasBeenReset = 1`** — The assertion is active (not disabled)

Since no queue is requesting the bus and `hasBeenReset` is true, the assertion fires at the first positive clock edge. The bus state `00` is valid behavior — there is simply no request to grant. The assertion incorrectly flags this as a violation.

### Why the Assertion Is Wrong

The `assertOneHot` property checks that **exactly one** of the two bus grant bits is high at all times. But a bus arbiter must be able to issue **no grant** (idle state) when no agent requests the bus. The correct invariant for a bus grant signal is "at most one bit is high" (one-hot or zero-hot), not "exactly one bit is high" (strict one-hot).

### Recommended Fix

Replace the `assertOneHot` check with a custom assertion that allows the zero state. For example:

```scala
// Option 1: Use assertZeroOrOneHot (if available in the formal library)
// Option 2: Manually check that bus_gnt is not both bits set
// Option 3: Gate the assertion with a request-active condition
```

A simple fix in the generated SVA (or equivalent Chisel formal check) would be to permit `bus_gnt == 2'b00`:

```systemverilog
// Original (too strict):
// {1'h0, bus_gnt[0]} + {1'h0, bus_gnt[1]} == 2'h1

// Fixed (allows idle/zero state):
// {1'h0, bus_gnt[0]} + {1'h0, bus_gnt[1]} != 2'h2
// Equivalent to: !(&bus_gnt) — bus_gnt must not be 2'b11
```

Alternatively, gate the assertion with a condition that checks only when there is an active bus request:

```scala
// Only check one-hot when at least one queue is requesting
when (bus_req.orR) {
    assertOneHot(bus_gnt, "bus_gnt_one_hot_active")
}
```
