# Counterexample Analysis: `pending_request_eventually_served`

## 1. Verification Environment

- **Top module**: `b03` (from `b03.scala`, package `llmverify`)
- **Generated Verilog**: `chisel/extra_bench/itc99_b03/generated/`
- **Design under test**: A 4-input arbiter with a FIFO queue that processes incoming requests in fixed priority order (request 1 > request 2 > request 3 > request 4).

### Key design components

| Component | Type | Description |
|---|---|---|
| `ru1`-`ru4` | Registers | Captured request values (sampled from io.REQUESTn in sInit or sAssegna) |
| `fu1`-`fu4` | Registers | Follow-up flags tracking which requests are pending service |
| `coda0`-`coda3` | Registers | FIFO queue (depth 4) holding encoded request IDs |
| `grant` | Register | One-hot grant output |
| `stato` | Register | State machine: sInit (00), sAnalisReq (01), sAssegna (10) |

### State machine flow

1. **sInit** → capture requests into `ru` registers, go to **sAnalisReq**
2. **sAnalisReq** → enqueue highest-priority ru&!fu into FIFO, set `fu` for ALL ru, go to **sAssegna**
3. **sAssegna** → if any fu is set, dequeue from FIFO and set grant based on queue head, clear corresponding fu, capture new requests into ru, go to **sAnalisReq**

---

## 2. Violated Assertion

- **Assertion name**: `pending_request_eventually_served`
- **Full source** (line 178 of `b03.scala`):
  ```scala
  astRelaxedLiveness(any_fu, any_grant, 10, "pending_request_eventually_served")
  ```
- **Supporting code** (lines 166-168):
  ```scala
  val any_fu = fu1 || fu2 || fu3 || fu4
  val any_grant = grant.orR
  ```
- **Natural language property**: Whenever a follow-up register (`fu1`-`fu4`) becomes set (indicating a tracked pending request), a non-zero grant must appear within 10 clock cycles. This is a bounded liveness check.
- **Assertion type**: `astRelaxedLiveness` — a liveness checker that triggers the `pending` signal when `any_fu` is true, starts a timer, and fails if `any_grant` does not become true within the bound of 10 cycles.

---

## 3. Waveform Information

### Waveform file
`verilog/extra_bench/itc99_b03/b03.pending_request_eventually_served.fst`

### Time range
0 ns – 170 ns (17 clock cycles at 10 ns period)

### Key time points and signal values

| Time (ns) | Cycle | stato | Event |
|---|---|---|---|
| 0 | 0 | sInit | Reset. io_REQUEST3=1, io_REQUEST4=1 |
| 10 | 1 | sAnalisReq | ru3=1, ru4=1 captured. fu3=0, fu4=0 |
| 20 | 2 | sAssegna | ru3&&!fu3 enqueues U3. **fu3:=1, fu4:=1 (BUG)**. coda0=U3 |
| 30 | 3 | sAnalisReq | **grant=0010** (U3 served). fu3 cleared to 0. fu4 stays 1 |
| 40 | 4 | sAssegna | coda0=000 (queue empty). fu4=1 but no queue entry → stays pending |
| 50 | 5 | sAnalisReq | grant back to 0000. fu4 still 1 |
| 60 | 6 | sAssegna | **pending_1** becomes 1 (latches any_fu=fu4=1). Timer starts |
| 70-130 | 7-13 | ... | fu4=1, grant=0000 consistently. Timer counts up |
| 140 | 14 | sAssegna | fu4:=ru4=0 (ru4 went to 0). fu4 cleared. But pending_1 still 1 (latched) |
| 160 | 16 | sAssegna | **timer_1=10 (1010 binary)** → assertion FAILS at time 160 |

### Critical signal values at failure (time 160 ns)

| Signal | Value |
|---|---|
| `b03.pending_request_eventually_served` | **0** (assertion failed) |
| `b03.timer_1 [3:0]` | **1010 (10)** — exceeds bound of 10 |
| `b03.grant [3:0]` | **0000** — no grant active |
| `b03.fu4` | 0 (just cleared at time 140, too late) |
| `b03.stato [1:0]` | 10 (sAssegna) |
| `b03.coda0 [2:0]` | 000 |

---

## 4. Root Cause Analysis

### Bug location

**File**: `b03.scala`, **lines 60-91** — the `sAnalisReq` state logic

### Buggy code (lines 62-88)

```scala
is(sAnalisReq) {
  // Queue update logic  (lines 62-82)
  when(ru1 && !fu1) {
    coda3 := coda2; coda2 := coda1; coda1 := coda0; coda0 := U1
  }.elsewhen(ru2 && !fu2) { ... }.elsewhen(ru3 && !fu3) { ... }.elsewhen(ru4 && !fu4) { ... }

  // Update follow-up registers — BUG: UNCONDITIONAL  (lines 85-88)
  fu1 := ru1
  fu2 := ru2
  fu3 := ru3
  fu4 := ru4
}
```

### Description of the bug

The follow-up register assignment (`fu4 := ru4` on line 88) is **unconditional** — it fires regardless of whether the corresponding request was actually enqueued into the FIFO queue. The queue enqueue logic uses a priority encoder (ru1 > ru2 > ru3 > ru4), so only the highest-priority active request gets enqueued. However, **all** `fu` registers are set to their corresponding `ru` values.

This creates a livelock scenario:

1. `ru3=1` and `ru4=1` are both active (time 10)
2. The priority encoder enqueues only `U3` (ru3 has higher priority) at time 20
3. But **both** `fu3` and `fu4` are set to 1 unconditionally (lines 85-88)
4. In `sAssegna` (time 30), the queue head is `U3`, so fu3 is cleared and grant is set to `0010`
5. After shifting, the queue is empty (`coda0=000`), but **fu4 remains 1**
6. In subsequent `sAnalisReq` cycles, `ru4 && !fu4` is false (because fu4=1), so U4 can **never** be enqueued
7. In subsequent `sAssegna` cycles, `coda0=000` doesn't match any valid U value, so fu4 is never cleared

### Why the assertion fails

- `any_fu = fu4 = 1` persists from time 20 to time 140 (12 cycles)
- `any_grant = grant.orR` stays 0 after time 50, because there is no queue entry to serve
- The `astRelaxedLiveness(any_fu, any_grant, 10, ...)` checker monitors liveness: pending request (`any_fu`) must be served by a grant (`any_grant`) within 10 cycles
- Since fu4 is stuck pending without a corresponding queue entry, no grant ever appears, and the timer exceeds the 10-cycle bound

### Fix suggestion

The `fu` register assignments (lines 85-88) should be moved **inside** the priority-encoder conditional branches so that only the actually enqueued request gets its `fu` bit set:

```scala
is(sAnalisReq) {
  when(ru1 && !fu1) {
    coda3 := coda2; coda2 := coda1; coda1 := coda0; coda0 := U1
    fu1 := true.B       // Only set fu for the enqueued request
  }.elsewhen(ru2 && !fu2) {
    coda3 := coda2; coda2 := coda1; coda1 := coda0; coda0 := U2
    fu2 := true.B
  }.elsewhen(ru3 && !fu3) {
    coda3 := coda2; coda2 := coda1; coda1 := coda0; coda0 := U3
    fu3 := true.B
  }.elsewhen(ru4 && !fu4) {
    coda3 := coda2; coda2 := coda1; coda1 := coda0; coda0 := U4
    fu4 := true.B
  }
  // Remove unconditional fu1:=ru1 ... fu4:=ru4
}
```

Alternatively, if tracking all requests is desired regardless of priority, the `sAssegna` state should have a fallback mechanism to clear `fu` bits that have no matching queue entry.

---

### Error classification

**`dut_bug`** — The design unconditionally sets follow-up registers for all requests, even those not enqueued. This causes a tracked request (fu4) to be stuck pending forever with no queue entry to serve it, violating the bounded liveness property.
