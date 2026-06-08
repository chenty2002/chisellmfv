# Counterexample Analysis Report

## 1. Verification Environment

**Top Module:** `iqc` (Instruction Queue Controller)
**Source File:** `iqc.scala` (package `llmverify`)

**Structure:**
- The `iqc` module implements a 3-entry instruction queue with:
  - 2 dispatch (load) ports
  - 2 issue (execution) ports
  - 3 queue slots, each tracked by a valid bit and relative age bits (`qAge`)
- **Key Components:**
  - `valid(2:0)` — 3-bit register tracking which slots hold valid instructions
  - `qAge(2:0)` — 3-bit register tracking relative ages (higher bit = older than neighboring slot)
  - Slot-specific issue logic (`issue0_0` etc.) and load logic (`load0_0` etc.)
  - Next-valid calculation (`nv0`, `nv1`, `nv2`)
- **Inputs:** `io_iqLoads` (dispatch availability), `io_exeReady` (execution unit readiness), `io_opsReady` (operand readiness), `io_flush` (flush signals)
- **Outputs:** `io_valid`, `io_issue0`, `io_issue1`, `io_load0`, `io_load1`, `io_load2`

## 2. Violated Assertion

**Full Assertion Name (from waveform filename):** `slot0_progress_valid_ready_issued_within_16`

**Code Location:** `iqc.scala`, lines 127–132

**Code Snippet:**
```scala
astRelaxedLiveness(
    valid(0) & io.opsReady(0),
    io.issue0(0) | io.issue1(0) | !valid(0),
    16,
    "slot0_progress_valid_ready_issued_within_16"
)
```

**Natural Language Description:**
> If slot 0 is **valid** and its **operands are ready**, then within **16 cycles** the instruction must either be **issued** to an execution unit (`issue0(0)` or `issue1(0)`) or the slot must become **invalid** (`!valid(0)`).

This is a bounded-liveness (progress) property: a ready instruction must make progress within a bounded time window.

## 3. Waveform Information

**Waveform File:** `verilog/extra_bench/ibuf/iqc.slot0_progress_valid_ready_issued_within_16.fst`

**Clock:** Period = 10 ns (posedge at times 0, 10, 20, …, 180)

**Time Range:** 0 ns → 190 ns (19 cycles)

**Key Time Points and Critical Signal Values:**

| Time | Signal | Value | Interpretation |
|------|--------|-------|----------------|
| 0 (cycle 0) | `io_flush [2:0]` | `010` | `flush(0)=0`, `flush(1)=1`, `flush(2)=0` |
| 0 (cycle 0) | `io_exeReady [1:0]` | `00` | Neither execution unit ready |
| 0 (cycle 0) | `valid [2:0]` | `000` | All slots empty initially |
| 0 (cycle 0) | `load0_0` | `1` | Load dispatched to slot 0 |
| 10 (cycle 1) | `valid [2:0]` | `001` | **Slot 0 becomes valid** (loaded at cycle 0) |
| 10 (cycle 1) | `io_opsReady [2:0]` | `011` | **opsReady(0)=1** (operands ready for slot 0) |
| 10 (cycle 1) | **Trigger fires** | — | `valid(0) & opsReady(0) = 1 & 1 = 1` |
| 10–170 | `io_exeReady [1:0]` | `00` | **Stuck at 00** throughout entire trace |
| 10–170 | `issue0_0` | `0` | Never issues |
| 10–170 | `issue1_0` | `0` | Never issues |
| 10–170 | `io_flush(0)` (bit 0 of flush) | `0` | Never flushes slot 0 |
| 20–170 | `valid [2:0]` | `001` | Slot 0 stays valid for all remaining cycles |
| 180 (cycle 18) | `slot0_progress_valid_ready_issued_within_16` | `0` (falling) | **Assertion violated** |

## 4. Root Cause Analysis

### Classification: **Setup Error (Incorrect Top Module Setup)**

### Explanation

This is **not** a bug in the DUT logic. The `iqc` module's internal logic is functionally correct — the issue/load arbitration, age tracking, and mutual-exclusion properties all compute correctly. The failure is caused by **missing input constraints in the formal verification environment**.

### Root Cause Mechanism

1. **Trigger Activation (cycle 1, time 10):** Slot 0 is loaded at cycle 0, making `valid(0)=1` at cycle 1. At the same time, `opsReady(0)=1`, so the assertion's trigger condition `valid(0) & opsReady(0)` becomes true.

2. **Deadlock — No Path to Satisfaction:** The assertion requires **within 16 cycles** that either:
   - **(a)** Slot 0 is issued (`issue0(0) | issue1(0)`), **OR**
   - **(b)** Slot 0 becomes invalid (`!valid(0)`).

   Neither path is available because:
   - **Path (a) blocked:** `io_exeReady` is **stuck at `00`** for the entire 19-cycle trace. Since issue signals are gated by `exeReady`, slot 0 can never issue.
   - **Path (b) blocked:** `io_flush(0)` is **always `0`** (bit 0 of flush is never asserted). Flush is the only mechanism to invalidate a slot without issuing it. Additionally, no load targets slot 0 after cycle 0 (load0 goes to 0 at cycle 1 and stays 0).

3. **Assertion Failure (cycle 18, time 180):** After 16 cycles of waiting (cycles 2–17, times 20–170), slot 0 is still valid and unissued, so the `astRelaxedLiveness` checker de-asserts the property monitor.

### Evidence Summary

All key signals directly confirm the deadlock:

| Signal | Value Throughout (cycles 1–17) | Why This Blocks Progress |
|--------|-------------------------------|--------------------------|
| `io_exeReady [1:0]` | `00` | Neither execution unit is ever ready → no issue possible |
| `issue0_0` | `0` | Cannot assert without `exeReady(0)` |
| `issue1_0` | `0` | Cannot assert without `exeReady(1)` |
| `io_flush(0)` | `0` | Flush for slot 0 never asserted → cannot be invalidated |
| `valid(0)` | `1` | Remains valid — no path to invalidate |

### Why This Is a Setup Error (Not a DUT Bug)

- The DUT's **issue logic** correctly computes `issue0_0 = exeReady(0) & opsReady(0) & valid(0) & ...`. With `exeReady(0)=0`, `issue0_0` correctly stays `0`.
- The DUT's **next-valid logic** computes `nv0 = ~flush(0) & (valid(0) & ~(issue0_0|issue1_0) | load0.orR)`. Since `flush(0)=0`, `valid(0)=1`, no issue occurs, and `load0.orR=0` after cycle 0, the slot correctly stays valid.
- The DUT's **arbitration, mutual exclusion, and age tracking** logic are all functionally sound — they simply cannot be exercised when the environment never provides the necessary handshake signals.

### Required Fix

The formal verification **TestTop** wrapper needs to add **environment assumptions** that constrain the inputs to be realistic:

1. **`io_exeReady` must eventually assert** — the execution units cannot remain permanently stalled. An assumption like `eventually io_exeReady(0) || io_exeReady(1)` or a fairness constraint on `exeReady` would allow issuance.
2. **Alternatively, a flush mechanism** — if flushes are intended to be the only way to clear a stalled slot, `io_flush(0)` must be allowed to assert when slot 0 is stuck.
3. **Realistic input interleaving** — the formal solver should provide sequences where `exeReady` pulses occur periodically so that ready instructions can actually issue.

Without these constraints, the bounded liveness properties are **unprovable** because the formal solver (which can keep inputs stuck indefinitely) can always construct a counterexample where the environment starves the DUT.
