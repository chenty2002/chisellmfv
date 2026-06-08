# Counterexample Analysis Report

## 1. Verification Environment

### Top Module
- **Module**: `twoQ` (in `twoqLTLM1.scala`, line 155)
- **Package**: `llmverify`
- **Clock**: 10 ns period (rising edges at 0, 10, 20 ns)

### Key Components
| Component | Module | Role |
|-----------|--------|------|
| `q0` | `sampleq(WIDTH=2)` | Queue 0 – handles read/write transactions for client 0 |
| `q1` | `sampleq(WIDTH=2)` | Queue 1 – handles read/write transactions for client 1 |
| `buechi` | `Buechi` | Buchi automaton for liveness tracking |
| `bus_gnt` | `RegInit(0.U(2.W))` | 2-bit registered bus grant (bit 0 → q0, bit 1 → q1) |

### Connections
- `q0.io.bus_gnt` ← `bus_gnt(0)` (line 206)
- `q1.io.bus_gnt` ← `bus_gnt(1)` (line 207)
- `bus_req` ← Cat(`q1.io.bus_req`, `q0.io.bus_req`) (line 203)
- `io.select` → priority selector: when high → q1 priority; when low → q0 priority

### Bus Arbitration Logic (lines 210–216)
```scala
when(io.select && bus_req(1)) {
    bus_gnt := 2.U(2.W)    // grant to q1
}.elsewhen(!io.select && bus_req(0)) {
    bus_gnt := 1.U(2.W)    // grant to q0
}.otherwise {
    bus_gnt := 0.U(2.W)    // no grant
}
```

## 2. Violated Assertion

| Field | Value |
|-------|-------|
| **Assertion Name** | `gnt0_requires_select_low` |
| **Waveform File** | `twoQ.gnt0_requires_select_low.fst` |
| **Source Location** | `twoqLTLM1.scala`, **line 257** |
| **Code** | `assertImplies(bus_gnt(0), !io.select, "gnt0_requires_select_low")` |
| **Natural Language** | "If bus_gnt(0) is active (q0 is granted), then io.select must be low (q0 priority mode)." |

### Adjacent Assertions (lines 249–270)
```scala
// Line 250: assertOneHot0(bus_gnt, "bus_gnt_onehot0")
// Line 253: assertMutex(Seq(bus_gnt(0), bus_gnt(1)), "bus_gnt_mutex")
// Line 257: assertImplies(bus_gnt(0), !io.select, "gnt0_requires_select_low")  ← VIOLATED
// Line 260: assertImplies(bus_gnt(1), io.select, "gnt1_requires_select_high")
// Line 263: assertImplies(buechi.io.fair, buechi.io.scc, "fair_implies_scc")
// Line 267: astRelaxedLiveness(!io.select && bus_req(0), bus_gnt(0), 2, "q0_grant_liveness")
// Line 270: astRelaxedLiveness(io.select && bus_req(1), bus_gnt(1), 2, "q1_grant_liveness")
```

## 3. Waveform Information

| Detail | Value |
|--------|-------|
| **Waveform File** | `verilog/extra_bench/strltl_twoq_LTLM1/twoQ.gnt0_requires_select_low.fst` |
| **Failure Time** | **20 ns** (rising edge of cycle 2) |
| **Counterexample Duration** | 3 cycles (0 ns → 30 ns) |

### Signal Values at Key Clock Edges

| Time (ns) | Cycle | `io_select` | `bus_gnt[1:0]` | `io_bus_req[1:0]` | `q0.io_bus_req` | `q0.io_bus_gnt` |
|-----------|-------|-------------|-----------------|-------------------|-----------------|-----------------|
| 0 | Cycle 0 | **0** | **00** | 00 | 0 | 0 |
| 10 | Cycle 1 | **0** | **00** | 01 | **1** | 0 |
| **20** | **Cycle 2** | **1** | **01** | 01 | **1** | **1** |

### Assertion Failure at 20 ns
- `bus_gnt(0) = 1` (q0 is granted the bus)
- `io_select = 1` (q1 priority mode)
- The implication `bus_gnt(0) → !io_select` evaluates to `1 → 0` → **false** → assertion violation

## 4. Root Cause Analysis

### Finding: The assertion is an **Assertion Error** (`assertion_error`)

The `assertImplies(bus_gnt(0), !io.select)` assertion is a **combinational** check—it verifies that at every cycle, if `bus_gnt(0)` is high then `io.select` must be low at that same instant. However, `bus_gnt` is a **registered** signal (line 199: `val bus_gnt = RegInit(0.U(2.W))`), which introduces a **1-cycle pipeline delay** between the arbitration decision and the grant output.

### Detailed Bug Trace

**Cycle 1 (time = 10 ns, rising edge):**
1. `io.select = 0` (low, q0 priority mode)
2. `q0.io_bus_req = 1` (q0 is requesting the bus)
3. Arbitration logic evaluates: `!io.select && bus_req(0)` → `1 && 1` → **true**
4. The arbitration decides to grant to q0: `bus_gnt := 1.U(2.W)` (sets bit 0)
5. However, because `bus_gnt` is a register, this value is **captured** and will appear on the output **only at the next clock edge** (at 20 ns)

**Between cycles 1 and 2 (10–20 ns):**
- `io.select` transitions from **0 → 1** (q1 priority mode now enabled)
- `bus_req(0)` remains 1 (q0 is still requesting)

**Cycle 2 (time = 20 ns, rising edge):**
1. `bus_gnt` register **updates** to `01` (the grant decision made in cycle 1)
2. `io.select` is now **1** (changed from the previous cycle)
3. Arbitration logic evaluates: `io.select=1, bus_req(1)=0` → otherwise case → `bus_gnt := 0`
4. **The registered value `01` conflicts with the current `io.select=1`**
5. The assertion `assertImplies(bus_gnt(0), !io.select)` evaluates `bus_gnt(0)=1` and `io.select=1` → **violation**

### Why This Is an Assertion Error, Not a Design Bug

The arbitration logic itself is **correct**:
- In cycle 1, `!io.select && bus_req(0)` → grant to q0 ✓
- In cycle 2, `io.select=1` and `bus_req(1)=0` → no grant (otherwise case) ✓

The registered grant simply carries the previous cycle's decision. The liveness assertion on line 267 correctly anticipates this 1-cycle delay:
```scala
astRelaxedLiveness(!io.select && bus_req(0), bus_gnt(0), 2, "q0_grant_liveness")
```
It allows up to 2 cycles for the grant to appear, acknowledging the 1-cycle register pipeline plus 1 cycle slack.

### Evidence from the Code Comments

Line 256–257 contains the revealing comment:
```scala
// Safety: grant to q0 only happens when select is low and q0 is requesting
// (checked with a 1-cycle lookback via implication on the registered grant)
assertImplies(bus_gnt(0), !io.select, "gnt0_requires_select_low")
```

The comment says **"checked with a 1-cycle lookback"**, but `assertImplies` is a purely **combinational** operator with no temporal semantics. There is no actual 1-cycle lookback—the assertion checks the current values of `bus_gnt(0)` and `io.select` simultaneously. This is a mismatch between the author's intent and the assertion implementation.

### Fix Recommendation

The assertion should either:

**(a) Check the combinational grant decision instead of the registered value:**
Create an explicit wire for the combinational arbitration decision and assert on that wire. This verifies that the arbitration priority logic itself is correct.

**(b) Use a temporal operator for the "lookback" semantics:**
For example, use an LTL-style assertion that states: "at every cycle where `bus_gnt(0)` is asserted, `io.select` must have been low in the previous cycle."

**(c) Or simply correct the assertion to match the intended design behavior:**
Since the liveness assertion (line 267) already covers the expected behavior with a 2-cycle bound, the safety assertion `gnt0_requires_select_low` as written is too strict for a registered grant signal and should be relaxed or rewritten.

### Conclusion

| Category | Detail |
|----------|--------|
| **Root Cause Type** | `assertion_error` |
| **Buggy Signal** | The assertion checks `bus_gnt(0)` (registered) vs `io.select` in the same cycle |
| **Design Correctness** | The arbitration logic correctly computes the grant decision; the 1-cycle register pipeline is standard practice |
| **Assertion Issue** | `assertImplies` is combinational and cannot provide the intended 1-cycle lookback; the assertion is too strict for a registered signal |
