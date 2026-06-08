# Counterexample Analysis Report: `working_eventually_ready`

## 1. Verification Environment

### Top Module
- **Module**: `Main` (from `reqAckRed.scala`)
- **Hierarchy**: `Main.ra` (ReqAck) → `Main.ra.slv` (SlaveND)

### Design Structure
```
Main
 ├── lfsr [7:0]      — LFSR generating non-deterministic req signal (nd = lfsr(0))
 ├── req = nd         — Reg that samples nd and feeds to ReqAck
 │
 └── ra (ReqAck)
      ├── state machine: idle (00) → starting (01) → working (10) → done (11)
      ├── io.req  ← from Main's req
      ├── io.ack  ← state === done
      ├── io.start ← state === starting
      └── slv (SlaveND)
           ├── lfsr [7:0] — LFSR for non-deterministic nd behavior
           ├── count [1:0] — increments to 3, then ready is asserted
           └── io.ready ← count === 3
```

### Key Observations
- Both Main and SlaveND have independent LFSRs with identical seed (1) and polynomial.
- The LFSRs track each other exactly in this waveform (same values at all times).
- The `nd` signal (LFSR bit 0) is intended to model `$ND(0,1)` non-deterministic behavior for simulation.

---

## 2. Violated Assertion

### Assertion Name
`working_eventually_ready`

### Code Snippet (reqAckRed.scala, lines 107-108)
```scala
// Liveness: when in working state, ready must eventually be asserted
// After start, slave needs at most 3 cycles to assert ready
astRelaxedLiveness(state === working, io.ready, 5, "working_eventually_ready")
```

### Property Description
When the ReqAck state machine enters the **working** state, the slave's `io.ready` signal must be asserted within **5 clock cycles**. This is a bounded liveness property.

### File Location
`reqAckRed.scala`, line 108, in class `ReqAck`

---

## 3. Waveform Information

### Waveform File
`verilog/extra_bench/reqack_reqAckRed/Main.ra.working_eventually_ready.fst`

### Time Range
0 ns → 270 ns (27 cycles at 10 ns per cycle)

### Key Time Points (in nanoseconds)

| Time | Event | Value |
|------|-------|-------|
| 190 ns | State enters `starting` (01) | io.start goes high, resetting slave count to 0 |
| 200 ns | State enters `working` (10) | **Assertion trigger**: working_eventually_ready starts counting |
| 200 ns | slv.lfsr = 10111000, nd = 0 | Slave count stays at 0 (stalled) |
| 210 ns | slv.lfsr = 01110000, nd = 0 | Slave count stays at 0 (stalled) |
| 220 ns | slv.lfsr = 11100000, nd = 0 | Slave count stays at 0 (stalled) |
| 230 ns | slv.lfsr = 11000000, nd = 0 | Slave count stays at 0 (stalled) |
| 240 ns | slv.lfsr = 10000001, nd = 1 | Slave count finally begins incrementing |
| 250 ns | slv.count = 01, nd = 1 | Count reaches 1 **(5th cycle, bound exceeded)** |
| 260 ns | **working_eventually_ready = 0** | **Assertion FAILS** |
| 270 ns | slv.count = 10 (2), io_ready still 0 | Would reach ready at ~280 ns |

### Critical Signal Values at Failure (time = 260 ns)
| Signal | Value |
|--------|-------|
| `Main.ra.state [1:0]` | `10` (working) |
| `Main.ra.slv.io_ready` | `0` |
| `Main.ra.slv.count [1:0]` | `10` (2) |
| `Main.ra.slv.lfsr [7:0]` | `00000110` (nd=0) |
| `Main.ra.working_eventually_ready` | `0` (assertion failed) |

---

## 4. Root Cause Analysis

### Buggy Code Location
**File**: `reqAckRed.scala`, class `SlaveND`, **lines 69-73**

```scala
when(io.start) {
    count := 0.U
} .elsewhen(count === 0.U) {
    count := count + nd    // ← BUG: count can stall at 0 when nd=0
} .otherwise {
    count := count + 1.U
}
```

### Description of the Bug

The `SlaveND` module's counter logic has a critical flaw: when the count is 0 (after being reset by `io.start`), it only increments if `nd = lfsr(0)` is 1. If `nd` is 0, the count stays at 0 indefinitely.

This means the slave's ready latency is **unbounded** — it depends on the LFSR producing `nd=1` at the right time. In this counterexample:

1. At **time 200**, the state enters `working` and the slave count is 0 (reset by `io.start` at time 190).
2. The slave's `nd = lfsr(0)` is **0** for four consecutive cycles (times **200, 210, 220, 230**).
3. Since `count === 0` and `nd === 0`, the `elsewhen` branch executes: `count := 0 + 0 = 0` — **no progress**.
4. At time **240**, `nd` finally becomes 1, so count advances to 1 by time 250.
5. But by time **250**, the 5-cycle bound has already been exceeded (cycles at 210, 220, 230, 240, 250 → 5 cycles after entering working at 200).
6. The assertion checker fires at **time 260**, declaring `working_eventually_ready = 0`.

### Root Cause Category: **DUT Bug**

This is a genuine design bug in the `SlaveND` module. The `nd`-gated increment at `count === 0` creates an unbounded stall point that violates the bounded liveness property. The counter should always make progress from 0, or the `nd` non-determinism should be applied after the count has started advancing (e.g., by adding an extra delay after count reaches 3, rather than preventing count from leaving 0).

### Detailed Trace (Third Working Period)

| Cycle | Time | State | slv.count | slv.lfsr | nd=lfsr(0) | slv.io_ready |
|-------|------|-------|-----------|----------|------------|--------------|
| 19 | 190 | starting (01) | 10 → 00 | 01011100 | 0 | 0 |
| **20** | **200** | **working (10)** | **00** | **10111000** | **0 ← STALL** | **0** |
| 21 | 210 | working (10) | 00 | 01110000 | 0 ← STALL | 0 |
| 22 | 220 | working (10) | 00 | 11100000 | 0 ← STALL | 0 |
| 23 | 230 | working (10) | 00 | 11000000 | 0 ← STALL | 0 |
| 24 | 240 | working (10) | 00 → 01 | 10000001 | **1** | 0 |
| **25** | **250** | **working (10)** | **01** | **00000011** | **1** | **0 ← bound exhausted** |
| 26 | 260 | working (10) | 02 | 00000110 | 0 | 0 |
| 27 | 270 | - | 03 | - | - | 1 (too late) |

### Contrast with Previous Successful Iterations

| Working Period | Entry Time | nd pattern (at count=0) | Cycles to ready | Within bound? |
|---------------|-----------|------------------------|-----------------|:------------:|
| 1st (30-80) | 30 | nd=1 at T=30 (lfsr=00010001) | 4 cycles | ✓ |
| 2nd (130-170) | 130 | nd=1 at T=130 (lfsr=10001001) | 3 cycles | ✓ |
| **3rd (200-...)** | **200** | **nd=0 for 4 cycles (200-230)** | **≥7 cycles** | **✗ FAIL** |

### Why This Is the Bug

The design intent (from comments) states: *"After start, slave needs at most 3 cycles to assert ready."* However, the `elsewhen(count === 0.U) { count := count + nd }` branch allows the count to stall indefinitely when the LFSR produces `nd=0`. This is a direct violation of the bounded-latency requirement. A correct implementation should guarantee count progression from 0 regardless of `nd`, for example by using:

```scala
} .elsewhen(count === 0.U) {
    count := 1.U  // Always progress from 0
```

