# Counterexample Analysis Report: `grant3_requires_fu1`

## 1. Verification Environment

- **Benchmark**: `itc99_b03`
- **Top Module**: `b03` (extends `Module with Formal`)
- **Design Under Test**: A finite-state machine (FSM) priority arbiter with a request queue. The design handles up to 4 request inputs (`io.REQUEST1`–`io.REQUEST4`) and issues a one-hot grant output (`io.GRANT_O`).
- **FSM States**: `sInit` (0), `sAnalisReq` (1), `sAssegna` (2). The FSM alternates between `sAnalisReq` (analyze requests) and `sAssegna` (assign grants), starting from `sInit`.
- **Key Components**:
  - `ru1`–`ru4`: Data registers capturing raw request inputs
  - `fu1`–`fu4`: Follow-up registers (copies of `ru1`–`ru4` updated in `sAnalisReq`)
  - `coda0`–`coda3`: Queue (FIFO) storing pending request encodings (`U1`–`U4`)
  - `grant`: One-hot grant output register

## 2. Violated Assertion

- **Assertion Name**: `grant3_requires_fu1`
- **Waveform Filename**: `b03.grant3_requires_fu1.fst`
- **Source File**: `b03.scala`, lines 103
- **Code Snippet**:
  ```scala
  // --- Safety: Grant bit asserted only when the corresponding follow-up register was set ---
  fvAssert(!grant(3) || fu1, "grant3_requires_fu1")
  ```
- **Natural Language Property**: "Whenever grant bit 3 (the grant for requestor 1) is asserted, the corresponding follow-up register `fu1` must also be asserted." In other words, a grant must not be active without a pending request from the corresponding unit.

## 3. Waveform Information

- **Waveform File**: `verilog/extra_bench/itc99_b03/b03.grant3_requires_fu1.fst`
- **Time Range**: 0 ns → 50 ns (5 clock cycles at 10 ns period)

### Key Timeline

| Time (ns) | stato       | grant[3:0] | fu1 | ru1 | coda0[2:0] | io.REQUEST1 | Event                                                      |
|-----------|-------------|------------|-----|-----|------------|-------------|------------------------------------------------------------|
|  0        | sInit (00)  | 0000       | 0   | 0   | 000        | 1           | Reset; ru1 latches REQUEST1=1                              |
| 10        | sAnalisReq(01) | 0000    | 0   | 1   | 000        | 0           | ru1=1, fu1<=ru1=1 (takes effect next cycle); coda0<=U1     |
| 20        | sAssegna(10) | 0000      | 1   | 1   | 100 (U1)   | 0           | fu1=1, coda0=U1 → grant <= 1000 (grant(3)=1)              |
| 30        | sAnalisReq(01) | 1000    | 1   | 0   | 000        | 0           | fu1<=ru1=0 (will update at t=40); ru1 already 0            |
| **40**    | **sAssegna(10)** | **1000** | **0** | **0** | **000** | **0**       | **fu1=0, grant(3)=1 → ASSERTION FAILS**                   |

### Assertion Failure Signal

The formal assertion monitor `b03.grant3_requires_fu1` transitions from `1` (holding) to `0` (failing) at **time 40 ns**.

### Critical Signal Values at Failure (t=40 ns)

| Signal               | Value  |
|----------------------|--------|
| `b03.grant [3:0]`    | `1000` (bit 3 = 1) |
| `b03.fu1`            | `0`    |
| `b03.stato [1:0]`    | `10` (sAssegna) |
| `b03.coda0 [2:0]`    | `000`  |
| `b03.ru1`            | `0`    |
| `b03.io_REQUEST1`    | `0`    |

## 4. Root Cause Analysis

### Bug Location

**File**: `b03.scala`, lines 67–87 (inside the `is(sAssegna)` state)

### Description of the Bug

The grant register is only updated **inside** the `when(fu1 || fu2 || fu3 || fu4)` guard block in the `sAssegna` state (lines 68–84). When no follow-up registers are asserted (i.e., no pending requests), the grant register is **not updated at all** — it retains its previous value. This causes a stale grant to persist after the corresponding request has been serviced and the follow-up register has been cleared.

The problematic code structure (lines 67–87):

```scala
is(sAssegna) {
  when(fu1 || fu2 || fu3 || fu4) {        // <-- grant only updated here
    switch(coda0) {
      is(U1) { grant := "b1000".U }
      is(U2) { grant := "b0100".U }
      is(U3) { grant := "b0010".U }
      is(U4) { grant := "b0001".U }
    }
    when(!(coda0 === U1 || coda0 === U2 || coda0 === U3 || coda0 === U4)) {
      grant := "b0000".U
    }
    // ... queue shift ...
  }
  // Missing: otherwise clause to clear grant when no fu's are pending
  // ...
}
```

### Root Cause Mechanism (Step by Step)

1. **Cycle 0 (t=0, sInit)**: `io.REQUEST1` is high (1). Register `ru1` captures this value. FSM advances to `sAnalisReq`.

2. **Cycle 1 (t=10, sAnalisReq)**: `ru1=1`, `fu1` is still 0 (its previous value). Since `ru1 && !fu1` is true, request `U1` is enqueued (`coda0 <= U1`). `fu1 <= ru1` schedules fu1 to become 1 at the next clock.

3. **Cycle 2 (t=20, sAssegna)**: `fu1=1` and `coda0=U1`, so the `when(fu1||...)` block executes. `grant` is set to `1000` (grant(3)=1), and the queue shifts (`coda0 <= 0`). Also, `ru1 <= io.REQUEST1 = 0` (the request pulse has ended).

4. **Cycle 3 (t=30, sAnalisReq)**: `ru1=0` (updated from previous cycle). `fu1 <= ru1 = 0` schedules fu1 to become 0. No new enqueue since `ru1=0`.

5. **Cycle 4 (t=40, sAssegna)**: **The failure point**. `fu1=0`, `fu2=fu3=fu4=0`, so the `when(fu1||...)` condition is **false**, and the grant register is **not updated**. It retains its old value of `1000`. The assertion `!grant(3) || fu1` evaluates to `!1 || 0 = 0` → **ASSERTS**.

### Why This Is a Bug

The grant signal should be de-asserted when there are no pending requests. A stale grant output (`grant=1000`) with no active follow-up (`fu1=0`) violates the safety property that "a grant bit must only be asserted when the corresponding request is pending." In a real hardware implementation, this could cause a client to think it still has bus ownership when it does not.

### Fix

Add an `.otherwise` clause to the `when(fu1 || fu2 || fu3 || fu4)` block to clear the grant when no requests are pending:

```scala
is(sAssegna) {
  when(fu1 || fu2 || fu3 || fu4) {
    switch(coda0) {
      is(U1) { grant := "b1000".U }
      is(U2) { grant := "b0100".U }
      is(U3) { grant := "b0010".U }
      is(U4) { grant := "b0001".U }
    }
    when(!(coda0 === U1 || coda0 === U2 || coda0 === U3 || coda0 === U4)) {
      grant := "b0000".U
    }
    coda0 := coda1
    coda1 := coda2
    coda2 := coda3
    coda3 := 0.U
  }.otherwise {
    grant := 0.U    // <-- FIX: clear grant when no requests pending
  }
  // ...
}
```

### Error Classification

**DUT Bug** (`dut_bug`): The design has a genuine logic error where the grant register is not cleared when there are no pending requests, causing the safety assertion to fail.

### Assertion Validity

The assertion `grant3_requires_fu1` is **correct** and correctly captures the safety property that a grant should not be active without a pending request. It is not an assertion error — the design is genuinely buggy.
