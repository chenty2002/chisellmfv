# Counterexample Analysis Report: `pending_request_eventually_served`

## 1. Verification Environment

- **Top Module**: `b03` (package `llmverify`)
- **Design Under Test**: A simple 4-request arbiter with a finite state machine controller (3 states: `sInit`, `sAnalisReq`, `sAssegna`) and a FIFO queue of depth 4.
- **Key Components**:
  - `stato` (2-bit state register): cycles through `sInit → sAnalisReq → sAssegna → sAnalisReq → ...`
  - `ru1..ru4` (request capture registers): sample `io.REQUEST1..4` inputs
  - `fu1..fu4` (follow-up registers): track which requests have been captured/queued
  - `coda0..coda3` (3-bit queue registers): FIFO storing request types (U1=100, U2=010, U3=001, U4=111)
  - `grant` (4-bit register): one-hot grant output (`io.GRANT_O`)
- **Clock**: 10 ns per cycle, 17 cycles total (0–170 ns)

## 2. Violated Assertion

- **Assertion Name**: `pending_request_eventually_served`
- **Source File**: `b03.scala`, lines 163–166
- **Code Snippet**:
  ```scala
  // Bounded liveness: pending tracked requests (fu bits) must be serviced
  // within 10 cycles.  The queue depth is at most 4, each entry takes at
  // most 2 cycles to serve, so 8 + margin = 10.
  astRelaxedLiveness(any_fu, any_grant, 10,
    "pending_request_eventually_served")
  ```
- **Natural Language Description**: Whenever there is a pending follow-up request (any of `fu1`, `fu2`, `fu3`, or `fu4` is asserted), a grant (any bit of `grant` is set) must appear within 10 clock cycles.

## 3. Waveform Information

- **Waveform File**: `verilog/extra_bench/itc99_b03/b03.pending_request_eventually_served.fst`
- **Duration**: 170 ns (17 cycles), one cycle = 10 ns
- **Key Time Points**:

| Time (ns) | Cycle | Event |
|-----------|-------|-------|
| 0 | 0 | Reset; `stato = sInit` |
| 10 | 1 | `ru4` captures `io_REQUEST4=1`; `stato → sAnalisReq` |
| 20 | 2 | `fu4=1`, `coda0=U4(111)`; `stato → sAssegna`; `any_fu=1` |
| 30 | 3 | `grant=0001` (request 4 served); **`fu4` NOT cleared** (BUG); `stato → sAnalisReq` |
| 40 | 4 | `fu4=1`, but `coda0=0` (queue emptied); `stato → sAssegna` |
| 50 | 5 | `grant=0000` (no valid queue head); `fu4=1`, `any_fu=1`, `any_grant=0` → liveness tracker triggered |
| 60 | 6 | `fu4=0` (finally cleared in sAnalisReq when `ru4=0`); `pending_1` latched at 1 |
| 70 | 7 | `timer_1=1` starts incrementing |
| 80–150 | 8–15 | `timer_1` increments: 2→3→...→9; `grant` stays 0 throughout |
| 160 | 16 | `timer_1=10` → **Assertion fails**; `fu2=1`, `fu4=1` arrive too late |
| 170 | 17 | Waveform ends; `grant=0000`, `any_fu=1` |

- **Critical Signal Values at Failure (time=160 ns)**:
  - `fu2 = 1`, `fu4 = 1` (new pending requests)
  - `any_fu = 1`
  - `grant = 0000` (no grant active)
  - `coda0 = 010` (U2, about to be served next cycle, but too late)
  - `pending_1 = 1` (liveness tracker latched)
  - `timer_1 = 1010` (10 cycles, bound exceeded)

## 4. Root Cause Analysis

### Bug Location

- **File**: `b03.scala`
- **Lines**: 76–92 (the `sAssegna` state's `when` block)
- **Buggy Module**: The `sAssegna` state handler in the main `switch(stato)` statement

### Description of the Bug

**The `fu` (follow-up) bits are never cleared when a grant is issued.** When the state machine serves a request from the queue head (`coda0`) and issues a grant, the corresponding `fu` bit remains set. This has two cascading consequences:

1. **Prevents re-queuing**: In the next `sAnalisReq` cycle, if the same input request is still active (`ru` bit is 1), the condition `ruN && !fuN` evaluates to `false` because `fuN` is still 1. This prevents the request from being re-enqueued into the FIFO.

2. **Stale fu bits trigger liveness**: In the subsequent `sAssegna` cycle, `fuN` is still 1 (so `any_fu` is 1) but the queue is empty (because the request was not re-enqueued). The switch on `coda0` hits the default case, setting `grant := "b0000"`. This creates a window where `any_fu=1` and `any_grant=0`, triggering the liveness assertion's timer.

### Detailed Failure Trace

1. **Cycle 0–1** (`sInit`): `io_REQUEST4=1` is captured → `ru4=1`
2. **Cycle 1–2** (`sAnalisReq`): `ru4=1, fu4=0` → enqueue `U4` into queue, `fu4 := 1`
3. **Cycle 2–3** (`sAssegna`): `fu4=1, coda0=U4` → issue `grant=0001`, shift queue. **`fu4` should be cleared here but is NOT.**
4. **Cycle 3–4** (`sAnalisReq`): `io_REQUEST4` is still high (pulsed again from time 20–30), so `ru4=1`. But `fu4=1`, so `ru4 && !fu4 = false` → request 4 is NOT re-enqueued. `fu4 := ru4 = 1` (stays set).
5. **Cycle 4–5** (`sAssegna`): `fu4=1` but `coda0=0` (empty queue) → `grant=0000`.
6. **Cycle 5** (time=50): `any_fu=1, any_grant=0` → `nextPending_1` asserted, liveness timer starts.
7. **Cycle 5–6** (`sAnalisReq`): `io_REQUEST4=0`, so `ru4=0`. `fu4 := 0` (finally cleared).
8. **Cycle 6** (time=60): `any_fu=0`, but `pending_1` is already latched at 1. Timer remains active because `grant` never returns to 1.
9. **Cycles 7–16**: Timer counts 1→2→...→10. No grant is issued.
10. **Cycle 16** (time=160): Timer reaches 10 → **assertion fails**.

### Why the Assertion Fails

The assertion checks that whenever `any_fu` is true, a grant must appear within 10 cycles. The failure is caused by a spurious `any_fu=1` window (time 50–60) that occurs because `fu4` lingered after its request was already served. This lingering was caused by the design's failure to clear `fu` bits upon grant issuance. The liveness timer latches onto this condition and counts to 10 without ever seeing a grant.

### Evidence from Waveform

| Signal | time=30 | time=40 | time=50 | time=60 |
|--------|---------|---------|---------|---------|
| `fu4` | 1 | 1 | 1 | **0** |
| `grant` | 0001 | 0001 | **0000** | 0000 |
| `coda0` | 000 | 000 | 000 | 000 |
| `any_fu` | 1 | 1 | 1 | **0** |
| `pending_1` | 1 | 0 | 0 | **1** (latched) |
| `timer_1` | 0 | 0 | 0 | 0 |

The critical moment is at time=50: `fu4=1, grant=0000, coda0=0, any_fu=1, any_grant=0`. The liveness monitor sees `any_fu=1 ∧ any_grant=0` and starts its timer. By the time `fu4` clears at time=60, the pending flag is already latched, and no subsequent grant ever arrives to reset it.

### Fix

In the `sAssegna` state handler (lines 76–92 of `b03.scala`), clear the corresponding `fu` bit when a grant is issued for a specific queue entry:

```scala
is(sAssegna) {
  when(fu1 || fu2 || fu3 || fu4) {
    switch(coda0) {
      is(U1) { 
        grant := "b1000".U
        fu1 := false.B  // Clear fu1 when request 1 is served
      }
      is(U2) { 
        grant := "b0100".U
        fu2 := false.B  // Clear fu2 when request 2 is served
      }
      is(U3) { 
        grant := "b0010".U
        fu3 := false.B  // Clear fu3 when request 3 is served
      }
      is(U4) { 
        grant := "b0001".U
        fu4 := false.B  // Clear fu4 when request 4 is served
      }
    }
    // Default case for invalid queue head
    when(!(coda0 === U1 || coda0 === U2 || coda0 === U3 || coda0 === U4)) {
      grant := "b0000".U
    }
    // Shift queue
    coda0 := coda1
    coda1 := coda2
    coda2 := coda3
    coda3 := 0.U
  }
  // ... rest of the state remains the same
}
```

This ensures that once a request is served, its `fu` bit is immediately cleared, preventing stale `any_fu` assertions and allowing the same request to be properly re-enqueued if the input remains active.

### Error Classification

**Category: DUT Bug** — The design has a genuine bug: `fu` bits are never cleared when the corresponding grant is issued, causing stale pending signals that trigger false liveness violations.
