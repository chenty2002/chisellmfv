# Counterexample Analysis Report: `grant_requires_request`

## 1. Verification Environment

### Top Module and Structure
- **Top Module**: `twoQ` (in `twoqLTLM1.scala`, line ~116)
- **Key Components**:
  - `twoQ` — Top-level module with arbitration logic, containing:
    - `q0` — Instance of `sampleq` (queue 0)
    - `q1` — Instance of `sampleq` (queue 1)
    - `buechi` — Instance of `Buechi` (LTL monitor)
  - `sampleq` — Dual FIFO (read/write) queue with bus request/grant interface
  - `Buechi` — LTL assertion monitor for fair scheduling

### Key Connections
- `q0.io.bus_gnt` ← `bus_gnt(0)` (registered signal)
- `q1.io.bus_gnt` ← `bus_gnt(1)` (registered signal)
- `bus_req` ← `Cat(q1.io.bus_req, q0.io.bus_req)` (combinatorial Wire)
- `bus_gnt` ← `RegInit(0.U(2.W))` updated combinatorially based on `io.select` and `bus_req`

---

## 2. Violated Assertion

### Assertion Name
`grant_requires_request` (from waveform filename: `twoQ.grant_requires_request.fst`)

### Code Snippet
```scala
// Source: twoqLTLM1.scala, lines 262-263
// More precisely: if bus_gnt is non-zero, at least one queue must be requesting
fvAssert(!bus_gnt.orR || bus_req.orR, "grant_requires_request")
```

### Natural Language Property
Whenever the bus grant signal `bus_gnt` is non-zero (i.e., a grant is active for at least one queue), the bus request signal `bus_req` must also be non-zero (i.e., at least one queue must be requesting service).

### File Location
- **File**: `twoqLTLM1.scala`
- **Line**: 263

---

## 3. Waveform Information

### Waveform File
- **Full path**: `verilog/extra_bench/strltl_twoq_LTLM1/twoQ.grant_requires_request.fst`
- **Time range**: 0 ns to 40 ns (4 clock cycles, period = 10 ns)
- **Clock rising edges**: 0 ns, 10 ns, 20 ns, 30 ns

### Key Time Points and Signal Values

| Time (ns) | Event | `bus_gnt` | `bus_req` | `io_select` | `q0.io_bus_req` | `q0.io_bus_gnt` |
|-----------|-------|-----------|-----------|-------------|-----------------|-----------------|
| 0 | **Cycle 0 rising edge**: write to q0 (validin=1, readin=0, addr=0) | `00` | `00` | `0` | `0` | `0` |
| 5 | (mid-cycle) q0 input still active | `00` | `00` | `0` | — | — |
| 10 | **Cycle 1 rising edge**: q0 starts requesting (queue not empty) | `00` | `01` | `0` | `1` | `0` |
| 20 | **Cycle 2 rising edge**: grant given to q0 (bus_gnt ← 01, computed from cycle 1); q0 processes write | `01` | `01` | `0` | `1` | `1` |
| 30 | **Cycle 3 rising edge**: **ASSERTION FAILS**; q0 writehead updated → queue empty → bus_req drops; bus_gnt still `01` (will clear at time 40) | `01` | `00` | `0` | `0` | `1` |

### Assertion Signal Trace
- `twoQ.grant_requires_request`: `1` at time 0–30, drops to `0` at time 30 (failure point)

---

## 4. Root Cause Analysis

### Root Cause Category: **Incorrect Assertion** (assertion_error)

The assertion is **too strict** — it does not account for the one-cycle timing mismatch between the registered `bus_gnt` signal and the combinatorial `bus_req` wire.

### Detailed Explanation

#### Design Behavior

1. **`bus_gnt` is a registered signal** (`RegInit(0.U(2.W))` in `twoQ`, line ~140). It updates on the rising clock edge based on the arbitration logic:
   ```scala
   when(io.select && bus_req(1)) {
     bus_gnt := 2.U(2.W)
   }.elsewhen(!io.select && bus_req(0)) {
     bus_gnt := 1.U(2.W)
   }.otherwise {
     bus_gnt := 0.U(2.W)
   }
   ```

2. **`bus_req` is a combinatorial Wire** (line ~146):
   ```scala
   val bus_req = Wire(UInt(2.W))
   bus_req := Cat(q1.io.bus_req, q0.io.bus_req)
   ```
   Each queue's `io_bus_req` is also combinatorial: `!(readempty && writeempty)`.

3. **The `sampleq` module** updates its internal pointers (e.g., `writehead`) on the clock edge following a grant.

#### Sequence of Failure (Cycle-by-Cycle)

| Cycle | Time | What Happens |
|-------|------|-------------|
| 0 | 0 ns | Input written to q0 (validin=1, readin=0, addr=00). q0's writetail increments. |
| 0→1 | 0–10 ns | q0's combinatorial `io_bus_req` becomes 1 (queue not empty). |
| 1 | 10 ns | `bus_req=01`, `io_select=0`. Arbiter computes: `bus_gnt := 01` (scheduled for cycle 2). |
| 2 | 20 ns | `bus_gnt = 01`. q0 sees grant, processes write: `writehead := writehead + 1`, `validoutReg := true` (scheduled for cycle 3). |
| 2→3 | 20–30 ns | q0's combinatorial `io_bus_req` drops to 0 (writehead will equal writetail at cycle 3). |
| 3 | 30 ns | `bus_gnt = 01` (still active from cycle 2's grant), but `bus_req = 00` (queues empty). **Assertion fails**: `!bus_gnt.orR || bus_req.orR` = `0 || 0` = `0`. Arbiter computes `bus_gnt := 0` (for cycle 4). |

#### Why This Is an Assertion Bug

The DUT behaves correctly:
- The arbitration logic gave q0 a grant at cycle 2 because q0 was requesting at cycle 1.
- q0 was serviced in cycle 2, and its internal pointer (`writehead`) updates at cycle 3.
- The combinatorial `bus_req` reflects the post-service state immediately (cycle 2→3), dropping to 0 at cycle 3.
- The registered `bus_gnt` takes one extra cycle to clear (it becomes 0 at cycle 4).

This one-cycle overlap — where `bus_gnt` is still high from the previous grant decision but `bus_req` has already dropped because the request was serviced — is **expected behavior** in a design with registered grant signals and combinatorial request signals.

The assertion incorrectly assumes that `bus_gnt` and `bus_req` are always synchronous. It should either:
1. Check the property with a one-cycle delay (e.g., use `Past()` or a registered version of `bus_req`), or
2. Be rewritten to account for the pipeline: `bus_gnt` was set based on `bus_req` from the previous cycle.

### Evidence from Waveform

- **Time 20**: `bus_gnt=01`, `bus_req=01`, `q0.io_bus_req=1`, `q0.writehead=00`, `q0.writetail=01` (queue not empty → request active).
- **Time 30**: `bus_gnt=01`, `bus_req=00`, `q0.io_bus_req=0`, `q0.writehead=01`, `q0.writetail=01` (queue empty → request dropped). The assertion `grant_requires_request` goes to 0 at this exact time.
- **Time 30 Arbiter Inputs**: `io_select=0`, `bus_req=00` → arbiter correctly computes `bus_gnt := 0` for the next cycle.

### Fix Recommendation

The assertion should be corrected to reflect the one-cycle pipeline delay. One approach:

```scala
// Use the previous value of bus_req (the request that caused the grant)
// or use a registered version of bus_req for the comparison
fvAssert(!bus_gnt.orR || RegNext(bus_req).orR, "grant_requires_request")
```

Alternatively, if the intent is to check that a grant is never given spuriously (without a corresponding request), the assertion in the other direction already exists and passes:

```scala
fvAssert(!(bus_req(0) || bus_req(1)) || bus_gnt.orR, "no_request_no_grant")  // passes correctly
```

This assertion (`no_request_no_grant`) checks that if there's no request, there's no grant — and it passes because at time 20 when `bus_req=01`, `bus_gnt` is also non-zero.
