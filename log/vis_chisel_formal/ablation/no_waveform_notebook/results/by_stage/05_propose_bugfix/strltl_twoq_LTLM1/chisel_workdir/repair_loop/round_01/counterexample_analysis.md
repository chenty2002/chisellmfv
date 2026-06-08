# Counterexample Analysis Report: twoQ.bus_gnt_one_hot

## 1. Verification Environment

- **Top Module**: `twoQ` (from `twoqLTLM1.scala`)
- **Design Structure**: The `twoQ` module instantiates two `sampleq` FIFO queue modules (`q0`, `q1`) and a `Buechi` monitor module. A 2-bit `bus_gnt` register controls which queue receives a bus grant. The arbitration logic selects between `q0` (bit 0) and `q1` (bit 1) based on the `io.select` input and each queue's `bus_req` signal.
- **Formal Framework**: ChiselFv (`chiselFv._`) with JasperGold backend.

## 2. Violated Assertion

- **Assertion Name**: `bus_gnt_one_hot`
- **Full Path**: `twoQ.bus_gnt_one_hot`
- **Waveform File**: `twoQ.bus_gnt_one_hot.fst`
- **Code Snippet** (from `twoqLTLM1.scala`, line 249):
  ```scala
  // Safety 1: Bus grant signals are one-hot (at most one grant active at a time)
  assertOneHot(bus_gnt, "bus_gnt_one_hot")
  ```
- **Intended Property**: The comment says "at most one grant active at a time" — i.e., `PopCount(bus_gnt) ≤ 1`, which would accept values `00` (idle), `01` (grant to q0), and `10` (grant to q1).
- **Actual Check**: The `assertOneHot` function in ChiselFv checks that exactly one bit is set (`PopCount(bus_gnt) === 1`), which only accepts `01` or `10`, rejecting `00`.

## 3. Waveform Information

- **Full Path**: `verilog/extra_bench/strltl_twoq_LTLM1/twoQ.bus_gnt_one_hot.fst`
- **Duration**: 1 cycle (10 ns), time range 0–10 ns
- **Key Time Points and Signal Values**:
  | Time | `bus_gnt [1:0]` | `bus_gnt_one_hot` | `clock` | `io_clock` | `hasBeenReset` |
  |------|-----------------|-------------------|---------|------------|----------------|
  | 0 ns | `00`            | 1                 | 1       | 0          | 1              |
  | 5 ns | `00`            | 1                 | 0       | 0          | 1              |
  | 10 ns| `00`            | 1                 | 0       | 0          | 1              |
- **All Inputs**: `io_inaddr0=00`, `io_inaddr1=00`, `io_validin=00`, `io_readin=00`, `io_select=0` — all inputs are zero throughout the trace.
- **Submodule State**: All FIFO queues are empty (`readempty=1`, `writeempty=1`), all pointers are at reset values.

## 4. Root Cause Analysis

### Bug Classification: **Incorrect Assertion** (Assertion Error)

The root cause is that `assertOneHot(bus_gnt)` checks that `bus_gnt` has **exactly one bit set** (`PopCount === 1`), but the design correctly allows `bus_gnt = 00` (zero bits set) when no queue is requesting the bus.

### Detailed Explanation

**Design Behavior**: The `bus_gnt` register (line 199) is initialized to `0.U(2.W)` at reset. Its update logic (lines 210–216) is:

```scala
when(io.select && bus_req(1)) {
    bus_gnt := 2.U(2.W)   // Grant to q1 (bit 1)
}.elsewhen(!io.select && bus_req(0)) {
    bus_gnt := 1.U(2.W)   // Grant to q0 (bit 0)
}.otherwise {
    bus_gnt := 0.U(2.W)   // No grant
}
```

This arbitration logic produces three legal values:
- `00` (no grant) — when both queues are idle or the selected queue isn't requesting
- `01` (grant to q0) — when `!io.select && bus_req(0)` is true
- `10` (grant to q1) — when `io.select && bus_req(1)` is true

All three values are valid in normal operation. The value `00` is the initial reset state and the idle state when no queue is requesting, which is the case shown in the counterexample trace.

**Assertion Mismatch**: The comment on line 248 states the intent: "at most one grant active at a time", which corresponds to `PopCount(bus_gnt) ≤ 1`. However, the `assertOneHot` function from ChiselFv checks for **exactly one-hot** (`PopCount(bus_gnt) === 1`), rejecting the `00` (idle) state.

**Why This Is Not a Design Bug**: The design's arbitration logic never produces a non-one-hot value (`11` is impossible because the two `when` conditions are mutually exclusive due to `io.select` vs `!io.select`). The only "non-exactly-one-hot" value it can produce is `00`, which is a legitimate idle state.

**Why This Is Not a Setup Bug**: The inputs are unconstrained (all zero), which is a valid scenario — both queues are idle, no one is requesting the bus, and no grant should be given. This correctly exercises the idle condition that triggers the assertion failure.

### Evidence Summary

1. **Waveform evidence**: `bus_gnt = 00` at all time points, with `bus_gnt_one_hot = 1` (assertion violated).
2. **Code evidence**: The `bus_gnt` register is `RegInit(0.U(2.W))` and the `otherwise` branch sets it to `0.U(2.W)`, both producing the value `00`.
3. **Semantic mismatch**: Comment says "at most one grant" but `assertOneHot` checks "exactly one grant".

### Recommended Fix

Change the assertion to allow the zero-hot (idle) state. Use either:

```scala
// Fix 1: Use PopCount <= 1 (at most one-hot)
fvAssert(PopCount(bus_gnt) <= 1.U, "bus_gnt_one_hot")

// Fix 2: Or use assertAtMostOneHot if available, or a custom check
```

This correction aligns the assertion with the design's ability to have zero active grants when no queue is requesting the bus.
