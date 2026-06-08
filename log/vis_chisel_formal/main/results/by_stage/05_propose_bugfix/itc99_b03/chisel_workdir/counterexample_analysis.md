# Counterexample Analysis Report: REQUEST1_bounded_liveness

## 1. Verification Environment

### Top Module
- **Module**: `b03` (package: `llmverify`)
- **File**: `b03.scala` (166 lines)

### Structure
The design implements a simple round-robin arbiter with a 3-state FSM:
- **sInit** (00): Capture initial requests into ru1-ru4 registers
- **sAnalisReq** (01): Check request registers (ru1-ru4) to enqueue pending requests into the coda queue; update follow-up registers (fu1-fu4)
- **sAssegna** (10): Process queue head (coda0), assign grant, shift queue, capture new requests into ru1-ru4

Key components:
- **ru1..ru4** (4 registers): Capture registered request inputs
- **fu1..fu4** (4 registers): "Follow-up" registers that track which requests have been enqueued (ru1..ru4 delayed by 1 cycle through sAnalisReq→sAssegna)
- **coda0..coda3** (4 × 3-bit queue): FIFO queue for pending requests
- **grant** (4-bit register): Output grant signal

## 2. Violated Assertion

### Assertion Name
`REQUEST1_bounded_liveness`

### Code Snippet (b03.scala, lines 155-160)
```scala
// Bounded liveness 2: Requests eventually lead to grants within 15 cycles
// When a request is asserted, the corresponding grant bit must become true within 1..15 cycles
AssertProperty(io.REQUEST1 |-> Sequence(grant(3)).delayRange(1, 15), None, None, Some("REQUEST1_bounded_liveness"))
```

### Property Description
Whenever the input `io.REQUEST1` is asserted at some cycle N, the grant bit 3 (`grant(3)` = "b1000") must become true at some cycle between N+1 and N+15 (inclusive).

### File Location
- **File**: `b03.scala`
- **Line**: 157

## 3. Waveform Information

### Waveform File
- **Full path**: `verilog/extra_bench/itc99_b03/b03.REQUEST1_bounded_liveness.fst`
- **Duration**: 17 cycles (0 ns → 170 ns)
- **Assertion failure**: `b03.REQUEST1_bounded_liveness` transitions from 1→0 at **time = 160 ns** (cycle 16)

### Key Signal Timeline

| Time (ns) | Cycle | Clock | stato | io.REQUEST1 | ru1 | coda0 | grant | Event |
|-----------|-------|-------|-------|-------------|-----|-------|-------|-------|
| 0 | 0 | ↑ | sInit (00) | 0 | 0 | 000 | 0000 | Initial: ru1←io.REQUEST1=0 |
| 10 | 1 | ↑ | sAnalisReq (01) | **1** (rising) | 0 | 000 | 0000 | **Request 1 asserted, but ru1=0 → missed!** |
| 20 | 2 | ↑ | sAssegna (10) | 0 | ← io.REQUEST1=0 | 010(U2) | 0000 | ru1 captures io.REQUEST1=0 (too late!) |
| 30 | 3 | ↑ | sAnalisReq (01) | 0 | 0 | 000 | 0100 | grant for U2 |
| ... | ... | ... | ... | ... | ... | ... | ... | ... |
| 110 | 11 | ↑ | sAnalisReq (01) | **1** (rising) | 0 | 000 | 0100 | **Request 1 again, but ru1=0 → missed again!** |
| 120 | 12 | ↑ | sAssegna (10) | 0 | ← io.REQUEST1=0 | 000 | 0000 | ru1 captures io.REQUEST1=0 |
| 130 | 13 | ↑ | sAnalisReq (01) | 0 | 0 | 000 | 0000 | |
| 140 | 14 | ↑ | sAssegna (10) | **1** | ← io.REQUEST1=1 | 000 | 0000 | **Finally, ru1 captures io.REQUEST1=1!** |
| 150 | 15 | ↑ | sAnalisReq (01) | 0 | **1** | 000 | 0000 | ru1=1 → U1 enqueued → coda0←U1 |
| **160** | **16** | ↑ | sAssegna (10) | 0 | ← io.REQUEST1=0 | **100(U1)** | **0000** | **Last window cycle: grant(3)=0 → ASSERTION FAILS** |

### Critical Signal Values at Failure (time = 160 ns)
- **stato**: `10` (sAssegna)
- **coda0**: `100` (= U1 = "b100")
- **grant**: `0000` (grant(3) = 0)
- **ru1**: 0
- **fu1**: 1 (ru1 propagated to fu1 just now)
- **fu2**: 1
- **fu4**: 1

## 4. Root Cause Analysis

### Bug Location
- **File**: `b03.scala`
- **Line**: 63 (in `is(sAnalisReq)` block)
- **Module**: `b03`

### Description of the Bug

The bug is in the **sAnalisReq** state (lines 63-83). When deciding whether to enqueue a request (U1) into the coda queue, the design checks the **registered** request signal `ru1`:

```scala
when(ru1 && !fu1) {  // Line 63 - uses ru1 (registered), NOT io.REQUEST1 (direct input)
    ...
    coda0 := U1
}
```

The `ru1` register is only updated in two states:
1. **sInit** (line 54): `ru1 := io.REQUEST1` — only on the very first cycle
2. **sAssegna** (lines 117-120): `ru1 := io.REQUEST1` — after processing the queue

The problem is that when `sAnalisReq` evaluates (state 01), it uses the stale `ru1` value from the previous capture cycle. If `io.REQUEST1` asserts during sAnalisReq (or during sAssegna before the capture occurs), the request is completely missed because:

1. **sAnalisReq** checks `ru1` but doesn't directly sample `io.REQUEST1`
2. By the time **sAssegna** captures `io.REQUEST1` into `ru1` (lines 117-120), the request pulse may have already de-asserted

### Evidence from Waveform

The waveform trace shows this exact sequence:

1. **Cycle 1 (time 10)**: `io.REQUEST1` goes high while `stato=sAnalisReq`. The code checks `ru1=0` (captured at cycle 0 when io.REQUEST1 was 0), so U1 is **NOT** enqueued. The request is missed.

2. **Cycle 2 (time 20)**: `stato=sAssegna` samples `io.REQUEST1`, but by this time `io.REQUEST1` has already gone back to 0. So `ru1` stays 0.

3. The request is only finally captured at **cycle 14 (time 140)**, when `io.REQUEST1` happens to be 1 during an `sAssegna` state (line 117: `ru1 := io.REQUEST1`).

4. **Cycle 15 (time 150)**: `sAnalisReq` sees `ru1=1`, enqueues U1 into coda0
5. **Cycle 16 (time 160)**: `sAssegna` sees coda0=U1, but since `grant` is a register, its update takes effect at the **next** clock edge (time 170). At time 160, `grant(3)` is still 0 → **Assertion fails** because the 15-cycle window (cycles 2-16 from the original request at cycle 1) has expired.

### Why This Causes the Failure

The bounded-liveness assertion requires grant(3) within 15 cycles of io.REQUEST1. The request at cycle 1 is missed by the design due to the `ru1` vs `io.REQUEST1` mismatch, causing it to be delayed by 14 extra cycles. Even when the request is belatedly serviced at cycle 17 (time 170), the 15-cycle window expired at cycle 16 (time 160).

### Error Classification: **Bug in the Original Design (DUT Bug)**

The property is correct — it's a reasonable bounded-liveness requirement for an arbiter. The design's use of stale registered request values (`ru1`) in `sAnalisReq` instead of the direct input (`io.REQUEST1`) causes genuine request pulses to be missed.

### Potential Fix

In the `sAnalisReq` state, change the enqueue condition from checking `ru1` to checking the direct input `io.REQUEST1` (or a combination of `ru1` and `io.REQUEST1`):

```scala
// Option 1: Check direct input
when((ru1 || io.REQUEST1) && !fu1) { ... }

// Option 2: Capture io.REQUEST1 into a temporary at the start of sAnalisReq
// (Alternative structural fix)
```

The simplest fix is Option 1, which ensures that a request asserted during `sAnalisReq` is not missed, even if `ru1` hasn't been updated yet.
