# Counterexample Analysis Report: `iqc.issue1_mutex_12`

## 1. Verification Environment

- **Top Module**: `iqc` (Issue Queue Controller)
- **Source File**: `chisel/extra_bench/ibuf/iqc.scala`
- **Design Structure**: A 3-slot issue queue controller that:
  - Loads instructions from 2 dispatch ports into 3 queue slots (slots 0, 1, 2)
  - Tracks slot validity (`valid[2:0]`) and relative age (`qAge[2:0]`)
  - Issues instructions to 2 execution units (exec0, exec1) using age-based priority
  - Supports flushing individual slots
- **Connections**:
  - `io.iqLoads[1:0]` — dispatch port valid signals
  - `io.exeReady[1:0]` — execution unit ready signals
  - `io.opsReady[2:0]` — per-slot operand ready signals
  - `io.flush[2:0]` — per-slot flush signals
  - `io.issue0[2:0]` — which slot issues to exec0
  - `io.issue1[2:0]` — which slot issues to exec1

## 2. Violated Assertion

- **Assertion Name** (from waveform filename): `issue1_mutex_12`
- **Full Source** (line 105 in `iqc.scala`):
  ```scala
  AssertProperty(!(issue1_1 && issue1_2), "issue1_mutex_12")
  ```
- **Description**: Mutex property for execution unit 1 — at most one instruction slot (slot 1 or slot 2) may issue to execution unit 1 in any given cycle.
- **File Location**: `iqc.scala`, line 105

## 3. Waveform Information

- **Waveform File**: `verilog/extra_bench/ibuf/iqc.issue1_mutex_12.fst`
- **Time Range**: 0 ns → 30 ns (3 clock cycles)
- **Failure Time**: **20 ns** (cycle 2, rising edge)
- **Assertion Signal**: `iqc.issue1_mutex_12` transitions from 1 (pass) to **0 (fail)** at time 20 ns

### Key Signal Values at Failure Point (time 20 ns)

| Signal | Value | Description |
|--------|-------|-------------|
| `iqc.io_exeReady [1:0]` | `10` | Only exec unit 1 ready |
| `iqc.io_opsReady [2:0]` | `110` | Slots 1,2 ready; slot 0 not ready |
| `iqc.valid [2:0]` | `111` | All slots occupied |
| `iqc.qAge [2:0]` | `110` | Age ordering: slot 1 > slot 0 > slot 2 |
| `iqc.issue1_1` | **1** | Slot 1 issuing to exec1 (correct) |
| `iqc.issue1_2` | **1** | Slot 2 ALSO issuing to exec1 (BUG!) |
| `iqc.issue1_0` | 0 | Slot 0 not issuing to exec1 |
| `iqc.issue0_*` | all 0 | No issues to exec0 (exeReady(0)=0) |
| `iqc.io_issue1 [2:0]` | `110` | issue1_2=1, issue1_1=1, issue1_0=0 |

### Sequence Leading to Failure

| Time | Cycle | Event |
|------|-------|-------|
| 0–9 | 0 | Reset + flush(0)=1. Load slot 0 (load0_0) and slot 1 (load1_1). |
| 10–19 | 1 | valid=010 (only slot 1 valid). Load slot 0 (load0_0) and slot 2 (load2_1). nv0=nv1=nv2=1. |
| 20–29 | 2 | **FAILURE**: valid=111, qAge=110 (slot1>slot0>slot2). Both issue1_1=1 and issue1_2=1. |

## 4. Root Cause Analysis

### Buggy Code Location

- **File**: `iqc.scala`, **line 70** (inside the `issue1_2` expression)
- **Module**: `iqc`

### Bug Description

The `issue1_2` signal (slot 2 issuing to execution unit 1) contains a **copy-paste error** in its age-based priority check against slot 1.

**Buggy expression** (line 70):
```scala
val issue1_2 = io.exeReady(1) & io.opsReady(2) & valid(2) &
    (~qAge(1) | ~io.opsReady(0) | issue0_0) &
    (qAge(2) | ~io.opsReady(1) | issue0_1) & ~issue0_2
                                                  ^^^^^^^^
                                                  BUG: should be ~qAge(2)
```

The subexpression `(qAge(2) | ~io.opsReady(1) | issue0_1)` uses **`qAge(2)`** (which is 1 when slot 1 is OLDER than slot 2). When qAge(2)=1, this condition evaluates to 1, incorrectly allowing slot 2 (the younger slot) to issue over the older slot 1.

**Correct logic** should be:
```scala
    (~qAge(2) | ~io.opsReady(1) | issue0_1)
```

This means: slot 2 can issue to exec1 if:
- Slot 2 is older than slot 1 (`~qAge(2)` = 1), **OR**
- Slot 1's operands are not ready (`~io.opsReady(1)` = 1), **OR**
- Slot 1 already issued to exec0 (`issue0_1` = 1)

### Evidence from Waveform

At time 20 ns, `qAge[2:0] = 110`:
- `qAge(2) = 1` → Slot 1 is **older** than slot 2
- `qAge(1) = 1` → Slot 0 is older than slot 2
- `qAge(0) = 0` → Slot 1 is older than slot 0

Age ordering (oldest to youngest): **Slot 1 → Slot 0 → Slot 2**

With `exeReady=10` (only exec1 ready) and `opsReady=110` (slots 1,2 ready, slot 0 not ready):

- **Slot 1** (oldest) correctly issues to exec1: `issue1_1 = 1` ✓
- **Slot 2** (youngest) **incorrectly** also issues to exec1: `issue1_2 = 1` ✗

The buggy condition `(qAge(2) | ~io.opsReady(1) | issue0_1)` evaluates as `(1 | 0 | 0) = 1` because `qAge(2)=1`. With `~qAge(2)` it would evaluate as `(0 | 0 | 0) = 0`, correctly blocking slot 2 from issuing.

### Comparison with Correctly-Written Analogous Code

For reference, the analogous check in `issue0_2` (slot 2 to exec 0, line 55) uses the correct negation:

```scala
val issue0_2 = io.exeReady(0) & io.opsReady(2) & valid(2) &
    (~qAge(1) | ~io.opsReady(0)) & (~qAge(2) | ~io.opsReady(1))
                                      ^^^^^^^^
                                      CORRECT: uses ~qAge(2)
```

And the slot-1-to-exec1 expression (`issue1_1`, line 68) correctly uses `qAge(2)`:

```scala
val issue1_1 = io.exeReady(1) & io.opsReady(1) & valid(1) &
    (~qAge(0) | ~io.opsReady(0) | issue0_0) &
    (qAge(2) | ~io.opsReady(2) | issue0_2) & ~issue0_1
     ^^^^^^^^
     CORRECT: slot 1 checks if it's older than slot 2
```

The pattern is clear:
- A signal for **slot X** comparing with **slot Y** (where X is older) uses `qAge(?)` (not negated).
- A signal for **slot Y** comparing with **slot X** (where Y is younger) should use `~qAge(?)` (negated).

For `issue1_2` (slot 2 being younger checking against slot 1), it should use `~qAge(2)`, but instead it incorrectly uses `qAge(2)`.

### Error Classification

**Type**: **Bug in the Original Design (DUT bug)**

The assertion is correct — it is a valid mutex property. The inputs are realistic (all slots valid with ready operands, only one exec unit ready). The design logic for `issue1_2` has a genuine age-comparison bug that allows two slots to simultaneously issue to the same execution unit.

### Fix

Change line 70 of `iqc.scala` from:
```scala
    (qAge(2) | ~io.opsReady(1) | issue0_1)
```
to:
```scala
    (~qAge(2) | ~io.opsReady(1) | issue0_1)
```
