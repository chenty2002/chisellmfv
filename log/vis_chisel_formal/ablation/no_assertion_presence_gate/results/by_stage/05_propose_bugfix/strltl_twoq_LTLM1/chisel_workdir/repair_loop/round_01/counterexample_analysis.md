# Counterexample Analysis Report: `strltl_twoq_LTLM1`

## 1. Verification Environment

- **Top Module**: `twoQ` (from `twoqLTLM1.scala`, line 203)
- **Module Structure**:
  - `twoQ` instantiates two `sampleq` modules (`q0` and `q1`) acting as dual queues with read/write FIFOs
  - A `Buechi` module implementing an LTL monitor (automaton with states n1-n4, Trap)
  - A bus arbiter with a 2-bit grant register (`bus_gnt[1:0]`)
- **Key Components**:
  - `q0`, `q1`: Queue modules that can request the bus via `io_bus_req`
  - Bus arbiter: selects which queue gets the grant based on `io_select` and which queue is requesting
- **Design Function**: A dual-queue system with bus arbitration where `io_select` determines which queue is prioritized and a `Buechi` automaton tracks liveness properties.

## 2. Violated Assertion

- **Assertion Name**: `bus_gnt_onehot`
- **Waveform File**: `twoQ.bus_gnt_onehot.fst`
- **Source Code** (twoqLTLM1.scala, line 299):
  ```scala
  assertOneHot(bus_gnt, "bus_gnt_onehot")
  ```
- **Generated Verilog** (twoQ.sv, lines 437-439):
  ```verilog
  bus_gnt_onehot:
      assert property (@(posedge clock) disable iff (~hasBeenReset)
                       {1'h0, bus_gnt[0]} + {1'h0, bus_gnt[1]} == 2'h1);
  ```
- **Property Description**: The assertion checks that `bus_gnt` has **exactly one** bit set (i.e., `PopCount(bus_gnt) == 1`). This is a "strict one-hot" check.

## 3. Waveform Information

- **Full Waveform Path**: `verilog/extra_bench/strltl_twoq_LTLM1/twoQ.bus_gnt_onehot.fst`
- **Time Range**: 0 ns → 10 ns (1 clock cycle)
- **Failure Point**: Time = 0 ns (first cycle after reset)
- **Key Signal Values at Time 0**:

| Signal | Value |
|--------|-------|
| `twoQ.bus_gnt [1:0]` | `00` (no grants active) |
| `twoQ.bus_gnt_onehot` | `1` (assertion fires/violated) |
| `twoQ.bus_req [1:0]` | `00` (neither queue is requesting) |
| `twoQ.io_select` | `0` (select low) |
| `twoQ.hasBeenReset` | `1` (assertion enabled) |
| `twoQ.reset` | `0` (reset deasserted) |

## 4. Root Cause Analysis

### Category: **Incorrect Assertion** (assertion_error)

### Bug Location

- **File**: `twoqLTLM1.scala`
- **Line**: 299
- **Function**: `twoQ` class, formal assertions block

### Nature of the Bug

The assertion `assertOneHot(bus_gnt, "bus_gnt_onehot")` is too strong. It checks that `bus_gnt` is **exactly one-hot** (i.e., `PopCount(bus_gnt) == 1`), but the design legitimately produces `bus_gnt = 00` (no grants) when no queue is requesting the bus.

### Bus Grant Arbitration Logic (twoqLTLM1.scala, lines 247-264)

```scala
val bus_gnt = RegInit(0.U(2.W))

// Bus request signals
val bus_req = Wire(UInt(2.W))
bus_req := Cat(q1.io.bus_req, q0.io.bus_req)

// Bus grant arbitration logic
when(io.select && bus_req(1)) {
    bus_gnt := 2.U(2.W)   // binary 10: grant to q1
}.elsewhen(!io.select && bus_req(0)) {
    bus_gnt := 1.U(2.W)   // binary 01: grant to q0
}.otherwise {
    bus_gnt := 0.U(2.W)   // binary 00: no grant
}
```

The arbitration logic has three cases:
1. **`io_select && bus_req(1)`**: Grant to q1 → `bus_gnt = 10` (PopCount = 1)
2. **`!io_select && bus_req(0)`**: Grant to q0 → `bus_gnt = 01` (PopCount = 1)
3. **otherwise (no requesting queue)**: `bus_gnt = 00` (PopCount = 0)

The third case is perfectly legal — when neither queue is requesting the bus, no grant should be issued.

### The Assertion Check (Generated Verilog, lines 437-439)

```verilog
assert property (@(posedge clock) disable iff (~hasBeenReset)
                 {1'h0, bus_gnt[0]} + {1'h0, bus_gnt[1]} == 2'h1);
```

The expression `{1'h0, bus_gnt[0]} + {1'h0, bus_gnt[1]} == 2'h1` computes the population count of `bus_gnt` and checks it is exactly 1.

### Failure Scenario

At time 0 (first cycle after reset):
- `hasBeenReset` = 1 → assertion is enabled
- `bus_req [1:0]` = `00` → neither queue is requesting the bus
- The `otherwise` branch executes → `bus_gnt := 0.U(2.W)` (binary `00`)
- PopCount(`00`) = 0, which fails the check `PopCount == 1` → **assertion violation**

### Evidence from Waveform

| Cycle | `bus_req` | `io_select` | Expected `bus_gnt` | Actual `bus_gnt` | Assertion Result |
|-------|-----------|-------------|-------------------|-----------------|-----------------|
| 0 | `00` | `0` | `00` (no request → no grant) | `00` | **FAIL** (PopCount=0 ≠ 1) |

### Comment in Source Code Confirms the Intent

The comment on line 298 explicitly states the intended property:
```scala
// Safety: Bus grant must be one-hot (at most one queue gets grant)
```

The comment says **"at most one"** (i.e., PopCount ≤ 1), which includes the valid case of `bus_gnt = 00`. However, `assertOneHot` implements **"exactly one"** (PopCount = 1), which is too restrictive.

### Why Other Assertions Don't Fail

The other assertions (lines 300-317) correctly handle the `bus_gnt = 00` case:
- `gnt0_requires_req0`: Only fires when `bus_gnt(0)` is true — vacuously true when `bus_gnt(0) = 0`
- `gnt1_requires_req1`: Same — vacuously true when `bus_gnt(1) = 0`
- `no_req_no_gnt`: Checks `(bus_req != 0) OR (bus_gnt == 0)` — holds when both are zero

Only the `assertOneHot` check fails because it incorrectly requires PopCount to be exactly 1.

### Suggested Fix

Replace the strict one-hot assertion with an at-most-one-hot assertion:

```scala
// Option 1: Use PopCount ≤ 1 (if chiselFv provides assertAtMostOneHot)
// assertAtMostOneHot(bus_gnt, "bus_gnt_onehot")

// Option 2: Alternatively, check using PopCount
// assert(PopCount(bus_gnt) <= 1.U, "bus_gnt_onehot")
```

This would correctly allow `bus_gnt = 00` (no grants) while still ensuring that at most one queue receives a grant at any time.
