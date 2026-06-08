# Counterexample Analysis Report: `gnt0_only_when_q0_req`

## 1. Verification Environment

- **Top module**: `twoQ` (class `twoQ` in `twoqLTLM1.scala`)
- **Structure**:
  - Two `sampleq` (queues): `q0` and `q1` — each holds read/write FIFO entries and tracks empty/full status
  - One `Buechi` automaton — tracks fairness properties
  - Internal arbiter that grants the bus based on `io.select` and per-queue requests
- **Key connections**:
  - `io.bus_req[0]` ← `q0.io.bus_req` (combinatorial, `!(readempty && writeempty)`)
  - `io.bus_req[1]` ← `q1.io.bus_req`
  - `bus_gnt` ← arbiter logic, registered (`RegInit(0.U(2.W))`)
  - `q0.io.bus_gnt` ← `bus_gnt(0)`
  - `q1.io.bus_gnt` ← `bus_gnt(1)`

## 2. Violated Assertion

- **Assertion name**: `gnt0_only_when_q0_req` (from waveform filename `twoQ.gnt0_only_when_q0_req.fst`)
- **Code** (twoqLTLM1.scala, line 260):
  ```scala
  assertImplies(bus_gnt(0), q0.io.bus_req, "gnt0_only_when_q0_req")
  ```
- **Property**: If `bus_gnt(0)` is asserted (i.e., q0 is granted the bus), then `q0.io.bus_req` must also be asserted (the queue must actually be requesting).
- **File location**: `twoqLTLM1.scala`, line 260

## 3. Waveform Information

- **Waveform file**: `verilog/extra_bench/strltl_twoq_LTLM1/twoQ.gnt0_only_when_q0_req.fst`
- **Time range**: 0 ns → 40 ns (4 cycles, each cycle = 10 ns)
- **Key time points** (all values captured at `:jasper_formal_clock` rising edges at 0, 10, 20, 30 ns):

| Time | `bus_gnt[1:0]` | `q0.io_bus_req` | `q0.io_bus_gnt` | `io_select` | `bus_req[1:0]` | `q0.readempty` | `q0.writeempty` | `q0.io_validout` |
|------|----------------|-----------------|-----------------|-------------|----------------|----------------|-----------------|------------------|
| **0** | 00             | 0               | 0               | 0           | 00             | 1              | 1               | 0                |
| **10**| 00             | 1               | 0               | 0           | 01             | 1              | 0               | 0                |
| **20**| 01             | 1               | 1               | 0           | 01             | 1              | 0               | 0                |
| **30**| **01**         | **0**           | 1               | 0           | 00             | 1              | 1               | 1                |
| **35**| 01             | 0               | 1               | -           | 00             | -              | -               | -                |

**Assertion failure at time 30 ns**: `bus_gnt(0)=1` while `q0.io_bus_req=0`.

## 4. Root Cause Analysis

### Bug Location
- **File**: `twoqLTLM1.scala`
- **Lines**: 199 (declaration) and 210–216 (arbiter logic)
- **Module**: `twoQ`

### Bug Description

The root cause is that `bus_gnt` is declared as a **register** (`RegInit`) on line 199:

```scala
val bus_gnt = RegInit(0.U(2.W))
```

When a register is used, the grant decision computed by the arbiter takes effect **one cycle later** (on the next clock edge). This creates a **one-cycle overhang** where the grant signal persists (remains high) for one extra cycle after the requesting queue has finished processing and deasserted its request.

### Detailed Trace of the Failure

**Cycle at time 10** (clock rising edge):
- `io.validin[1:0]=00`, `io.readin[1:0]=01` → q0 receives a read-entry write (`validin(0)=0`?... wait, let me re-check)

Actually, looking at the values at time 10: `io_validin[1:0]=00`, `io_readin[1:0]=01`. But q0.io_validin=0. So no new entry was being added at time 10.

Wait, let me look at time 0: `io_validin[1:0]=01`, `io_readin[1:0]=00`, `io_inaddr0[1:0]=00`. So at time 0, q0 receives a write request (validin=1, readin=0, addr=00). This writes to the write FIFO.

Let me trace the actual flow:

**Time 0 → 10** (clock edge at 0):
- `io_validin[0]=1`, `io_readin[0]=0` → q0 receives a write transaction (value `00` written to write FIFO)
- `writetail` advances from `00` → `01` at time 10
- `writeempty` goes from 1 → 0 at time 10
- `q0.io_bus_req` = `!(readempty && writeempty)` = `!(1 && 0)` = 1 at time 10
- Arbiter sees: `io_select=0`, `bus_req(0)=1` → computes `bus_gnt := 1.U`

**Time 10 → 20** (clock edge at 10):
- The arbiter's decision `bus_gnt := 1.U` takes effect: `bus_gnt[1:0] = 01` at time 20
- `q0.io_bus_gnt` = 1
- Inside q0: `when(io.bus_gnt)` fires

**Time 20 → 30** (clock edge at 20):
- q0 processes the write entry: `readempty=1`, `matchReg=false`, so falls to `elsewhen(!writeempty)` branch
- `outaddrReg := writefifo(writehead)` (= writefifo(0) = 00)
- `writehead := writehead + 1.U` (writehead goes from 00 → 01)
- `outisareadReg := false.B`, `validoutReg := true.B`

**Time 30** (clock edge):
- After register updates: `writehead=01`, `writetail=01` → `writeempty = (01===01) = 1`
- `readempty = (00===00) = 1`
- `q0.io_bus_req = !(1 && 1) = 0` → q0 no longer requests
- **BUT** `bus_gnt` is still `01` (the registered value from the previous cycle's arbiter decision)
- **Assertion violation**: `bus_gnt(0)=1` while `q0.io_bus_req=0`

### Causal Chain

```
io.validin[0]=1 (time 0) 
  → q0 write FIFO has one entry (writetail=01, writehead=00)
    → q0.io_bus_req = 1 (time 10)
      → Arbiter computes bus_gnt := 01 (time 10)
        → bus_gnt register updates to 01 at clock edge (time 20)
          → q0 processes, writehead advances to 01 (time 20-30)
            → q0 becomes empty (writeempty=1), q0.io_bus_req = 0 (time 30)
              → bus_gnt still 01 (register hasn't been updated yet) 
                → ASSERTION FAILS (time 30)
```

### Root Cause Classification

This is a **DUT bug** (category 1: bug in the original design). The `bus_gnt` signal is incorrectly declared as a `RegInit` when it should be a `Wire`. The registered grant creates a one-cycle overhang that violates the safety property, which is a reasonable assertion ensuring grants are only given to requesting queues.

### Fix

Change line 199 from:
```scala
val bus_gnt = RegInit(0.U(2.W))
```
to:
```scala
val bus_gnt = Wire(UInt(2.W))
```

With a `Wire`, the grant deasserts combinatorially in the same cycle as the arbitration decision. When q0 finishes processing and deasserts `bus_req` at time 30, the arbiter immediately computes `bus_gnt := 0.U` (via the `otherwise` clause), and `bus_gnt(0)` becomes 0 in the same cycle — eliminating the one-cycle overhang and satisfying the assertion.
