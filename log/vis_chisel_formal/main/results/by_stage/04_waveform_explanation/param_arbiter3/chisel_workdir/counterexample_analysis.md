# Counterexample Analysis Report: `ackA_implies_reqA`

## 1. Verification Environment

- **Top Module**: `Main`
- **Design Under Test**: A round-robin arbiter system with 3 clients (A, B, C) and 3 controllers connected to a shared arbiter
- **Key Components**:
  - `Client` (×3): Generates pseudo-random requests using an LFSR, transitions through states NO_REQ → REQ → HAVE_TOKEN → NO_REQ
  - `Controller` (×3): Acknowledges requests when selected by arbiter, transitions through states IDLE → READY → BUSY → IDLE
  - `Arbiter` (×1): Round-robin selection among clients A→B→C→A
- **Connections**: Each Client's `io.req` drives the corresponding Controller's `io.req`; each Controller's `io.ack` drives the corresponding Client's `io.ack`; the Arbiter's `io.sel` is shared among all Controllers

## 2. Violated Assertion

- **Assertion Name**: `ackA_implies_reqA`
- **Source File**: `arbiter3.scala`, line ~205
- **Code Snippet**:
  ```scala
  AssertProperty(!io.ackA || io.reqA, "ackA_implies_reqA")
  ```
- **Property Description**: The assertion checks that whenever `io_ackA` is asserted (high), `io_reqA` must also be high. In other words, the arbiter should never send an acknowledgement to client A unless client A is actively requesting.

## 3. Waveform Information

- **Waveform File**: `verilog/extra_bench/param_arbiter3/Main.ackA_implies_reqA.fst`
- **Time Range**: 0 ns → 200 ns (20 cycles)
- **Key Time Point**: **T = 190 ns** (cycle 19, positive clock edge)
- **Critical Signal Values at Failure Point (T=190 ns)**:

| Signal | Value |
|--------|-------|
| `Main.io_ackA` | **1** |
| `Main.io_reqA` | **0** ✗ |
| `Main.io_sel [1:0]` | 11 (Selection.X) |
| `Main.io_active` | 0 |
| `Main.controllerA.state [1:0]` | 10 (BUSY) |
| `Main.controllerA.ackReg` | 1 |
| `Main.controllerA.io_req` | 0 |
| `Main.clientA.state [1:0]` | 00 (NO_REQ) |
| `Main.clientA.reqReg` | 0 |
| `Main.clientA.lfsr [7:0]` | 0b10100011 (LSB=1 → randChoice=true) |

## 4. Root Cause Analysis

### Bug Location

- **File**: `arbiter3.scala`, class `Controller`
- **Line**: ~42 (`io.ack := ackReg`)
- **Bug Type**: **Design Bug in the Controller (DUT Bug)**

### Description of the Bug

The **Controller** module drives `io.ack` directly from a registered signal `ackReg`:

```scala
io.ack := ackReg
```

The `ackReg` is set to `true.B` when the Controller enters the READY state and only cleared to `false.B` when the Controller sees `!io.req` in the BUSY state. However, this clearing happens **synchronously** at the next clock edge — there is a one-cycle window where `ackReg` is still high after `io.req` has already dropped.

The proper fix is to gate `io.ack` with `io.req` so that the acknowledgement is withdrawn combinatorially the moment the request drops:

```scala
io.ack := ackReg && io.req
```

### Evidence from the Waveform

The sequence of events leading to the failure at T=190 ns:

1. **T=140–150 ns (cycles 14–15)**: The arbiter sets `io_sel=00` (Selection.A), selecting client A. Controller A is in IDLE state and sees `isSelected=true` and `io_req=1`. It transitions IDLE → READY (state 00→01) at T=150.

2. **T=160 ns (cycle 16)**: Controller A transitions from READY → BUSY (state 01→10) and sets `ackReg=1`. Client A (in REQ state) receives `io_ack=1` and schedules transition to HAVE_TOKEN at T=170. At this point `io_ackA=1` and `io_reqA=1` — assertion holds.

3. **T=170–180 ns (cycles 17–18)**: Client A transitions to HAVE_TOKEN state and holds `reqReg=1`. Controller A remains in BUSY state with `ackReg=1`. Both `io_ackA` and `io_reqA` are 1 — assertion holds.

4. **T=190 ns (cycle 19)**: Client A's LFSR produces `randChoice=true` (bit 0 of 0b10100011 is 1), so the Client transitions from HAVE_TOKEN → NO_REQ and sets `reqReg=0` → `io_reqA=0`. However, Controller A's `ackReg` is still 1 (it will only be cleared at T=200 when the BUSY state sees `!io.req`). This creates a one-cycle mismatch: **`io_ackA=1` while `io_reqA=0`**, violating the assertion.

### Assertion Violation Mechanism

| Cycle | Time | ControllerA.ackReg | ClientA.reqReg | io_ackA | io_reqA | !ackA \|\| reqA |
|-------|------|-------------------|----------------|---------|---------|-----------------|
| 16    | 160  | 1                 | 1              | 1       | 1       | 1 (OK)          |
| 17    | 170  | 1                 | 1              | 1       | 1       | 1 (OK)          |
| 18    | 180  | 1                 | 1              | 1       | 1       | 1 (OK)          |
| **19**| **190**| **1**           | **0**          | **1**   | **0**   | **0 (FAIL)**    |
| 20    | 200  | 0 (cleared)       | 0              | 0       | 0       | 1 (OK)          |

The root cause is a classic **pipeline/mismatch bug**: the Controller does not deassert its acknowledgement combinatorially when the request drops, relying instead on the next clock cycle to clear the ack register. This creates a one-cycle window where a stale acknowledgement exists without a matching request.

### Classification

**Error Type**: `dut_bug` — The Controller's `io.ack` output is not properly gated with `io.req`, causing a stale acknowledgement to persist for one cycle after the request drops.
