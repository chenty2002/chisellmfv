# Counterexample Analysis Report: `grant0_requires_fu4`

## 1. Verification Environment

- **Top module**: `b03` (package `llmverify`)
- **Module type**: Chisel `Module with Formal`
- **Description**: A 4-request arbiter with a round-robin-like queue. It processes incoming requests through a 3-state FSM (sInit → sAnalisReq → sAssegna → sAnalisReq → ...). Requests are captured in request registers (`ru1..ru4`), enqueued into a 4-deep queue (`coda0..coda3`), and serviced in the `sAssegna` state by asserting the corresponding grant bit.
- **State machine states**: sInit (`b001`), sAnalisReq (`b010`), sAssegna (`b100`), one-hot encoded.
- **Key signals**: `ru1..ru4` (request pending registers), `fu1..fu4` (follow-up registers tracking that a request was captured), `coda0..coda3` (FIFO queue), `grant[3:0]` (output grant vector).

## 2. Violated Assertion

- **Assertion name**: `grant0_requires_fu4` (from waveform filename `b03.grant0_requires_fu4.fst`)
- **Source location**: `b03.scala`, line 128
- **Code**:
  ```scala
  fvAssert(!grant(0) || fu4, "grant0_requires_fu4")
  ```
- **Natural language**: If grant bit 0 (corresponding to REQUEST4) is asserted, then the follow-up register `fu4` must also be asserted. In other words, grant(0) can only be high when there is an active follow-up for REQUEST4.
- **Logical form**: `grant(0) → fu4` (equivalently `¬grant(0) ∨ fu4`)

## 3. Waveform Information

- **Waveform file**: `verilog/extra_bench/itc99_b03/b03.grant0_requires_fu4.fst`
- **Simulation duration**: 0 ns to 50 ns (5 cycles at 10 ns/cycle)
- **Key time points**:

| Time (ns) | stato | grant | fu4 | ru4 | coda0 | io_REQUEST4 | Event |
|-----------|-------|-------|-----|-----|-------|-------------|-------|
| 0 | 001 (sInit) | 0000 | 0 | 0 | 000 | 1 | Initial state, REQUEST4 active |
| 10 | 010 (sAnalisReq) | 0000 | 0 | 1 | 000 | 0 | ru4 captures io_REQUEST4=1, fu4 still 0 |
| 20 | 100 (sAssegna) | 0000 | **1** | 1 | **111 (U4)** | 0 | fu4←ru4=1, coda0←U4 (enqueued) |
| 30 | 010 (sAnalisReq) | **0001** | 1 | **0** | 000 | 0 | grant(0) set; io_REQUEST4 is now 0, so ru4←0 |
| 40 | 100 (sAssegna) | 0001 | **0** | 0 | 000 | 0 | **ASSERTION FAILS**: grant(0)=1, fu4=0 |
| 45 | 100 (sAssegna) | 0001 | 0 | 0 | 000 | 0 | **ASSERTION FAILS** (same state) |
| 50 | 100 (sAssegna) | 0001 | 0 | 0 | 000 | 0 | End of trace |

- **Critical observations**:
  - `fu4` transitions from 1→0 at time 40 (clock edge)
  - `grant` transitions from 0000→0001 at time 30 and stays 0001 through time 50
  - Between time 40 and time 50, `fu4=0` while `grant(0)=1`, violating the assertion

## 4. Root Cause Analysis

### Bug Location
- **File**: `b03.scala`
- **Line**: 117-119 (in `sAnalisReq` state)
- **Buggy code snippet**:
  ```scala
  // Line 117-119 in b03.scala, inside is(sAnalisReq) block
  fu1 := ru1
  fu2 := ru2
  fu3 := ru3
  fu4 := ru4
  ```

### Description of the Bug

This is a **DUT bug** — a genuine design error in the arbiter's timing logic. The root cause is a **timing mismatch** between when the follow-up registers (`fu4`) are cleared and when the grant bits are cleared.

**The execution sequence that triggers the bug:**

1. **Cycle 1 (sAnalisReq, time 10–20)**: `ru4=1` causes the request to be enqueued (`coda0 := U4`) and the follow-up is set (`fu4 := ru4 = 1`).

2. **Cycle 2 (sAssegna, time 20–30)**: `fu4=1` triggers the `when(fu1||fu2||fu3||fu4)` branch, which sets `grant(0) = 1` and shifts the queue. At the end of this cycle, a new `ru4` is captured from `io_REQUEST4`, which is now `0` (the request was de-asserted).

3. **Cycle 3 (sAnalisReq, time 30–40)**: `ru4=0` and `fu4` is overwritten: **`fu4 := ru4 = 0`**. The follow-up register is cleared. But `grant(0)` is still `1` — it was set in the previous cycle and hasn't been cleared yet.

4. **Cycle 4 (sAssegna, time 40–50)**: Now `fu4=0`, so the `.otherwise` branch executes: `grant := 0.U`. However, this assignment only takes effect at the **next clock edge** (time 50). Between time 40 and time 50, `grant(0)=1` and `fu4=0`, violating the assertion.

**Why this is wrong**: The follow-up registers `fu1..fu4` serve two purposes in the design:
- They indicate that a request was captured and should be serviced
- They gate the grant assignment in the `sAssegna` state

When `fu4` is cleared in `sAnalisReq` (because the input REQUEST4 went low), it loses its original "pending grant" state. The grant bit, however, persists for one more full cycle before it is cleared in the next `sAssegna` state. This creates a **one-cycle window** where `grant(0) = 1` and `fu4 = 0` simultaneously, which violates the assertion `!grant(0) || fu4`.

### Correct Fix

The follow-up register should not be unconditionally overwritten with the new `ru` value. Instead, it should be cleared **only after the grant has been de-asserted**, or alternatively, the grant should be cleared at the same time as the follow-up. Two possible approaches:

**Option A**: Clear the grant bits inside `sAnalisReq` when the follow-up registers are cleared:
```scala
// In sAnalisReq:
fu4 := ru4
when(!ru4 && grant(0)) {
  grant := grant & "b1110".U  // Clear grant(0) when ru4 drops
}
```

**Option B**: Keep `fu4` asserted until the grant is cleared (revise the follow-up update logic):
```scala
// In sAnalisReq: fu4 should track whether the request still needs servicing
fu4 := ru4 || (fu4 && !grant(0) cleared...)
```

**Option C (simplest)**: Clear the grant in `sAnalisReq` if the follow-up is about to be cleared:
```scala
// In sAnalisReq, before updating fu4:
when(grant(0) && !ru4) {
  grant := grant & "b1110".U
}
fu4 := ru4
```

### Evidence from Waveform

The waveform traces show the exact sequence:
- `fu4` transitions: `0 → 1` (time 20), `1 → 0` (time 40)
- `grant[0]` transitions: `0 → 1` (time 30), then stays `1` through time 50
- The gap between `fu4` dropping (time 40) and `grant[0]` remaining high (through time 50) is the assertion violation window

### Error Classification

**Type**: `dut_bug` — The design has a genuine timing bug where `fu4` is cleared one cycle before `grant(0)` is cleared, creating a window where the safety property is violated. The assertion correctly captures the intended invariant.
