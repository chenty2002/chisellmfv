# Counterexample Analysis: `issue1_one_hot0` Assertion Failure

## 1. Verification Environment

- **Top Module**: `iqc` (Instruction Queue/Issue Unit) from package `llmverify`
- **Source File**: `chisel/extra_bench/ibuf/iqc.scala`
- **Key Components**:
  - `valid[2:0]` — 3-bit register tracking which queue slots hold valid instructions
  - `qAge[2:0]` — Age matrix: `qAge(0)=1` → slot0 older than slot1; `qAge(1)=1` → slot0 older than slot2; `qAge(2)=1` → slot1 older than slot2
  - `io.issue0[2:0]` — Issue signals for execution unit 0 (bits 0=slot0, 1=slot1, 2=slot2)
  - `io.issue1[2:0]` — Issue signals for execution unit 1
- **Design Description**: An instruction queue with 3 slots and 2 execution units. Instructions are dispatched from 2 ports (`iqLoads`), tracked with valid/age bits, and issued to execution units based on age-based priority (older instructions issue first) and operand readiness. Execution unit 0 has priority over execution unit 1 — unit 1 can only issue slots that were NOT selected by unit 0.

## 2. Violated Assertion

- **Assertion Name**: `issue1_one_hot0` (from waveform filename `iqc.issue1_one_hot0.fst`)
- **Code**: `assertOneHot0(io.issue1, "issue1_one_hot0")` at line 158 of `iqc.scala`
- **Property**: At most one bit of `io.issue1` should be set per cycle — i.e., execution unit 1 should issue at most one instruction per cycle.
- **File Location**: `iqc.scala`, line 157

### Assertion Definition (from Chisel FV library)
The `assertOneHot0` assertion checks that the given signal has at most one bit high. When two or more bits of `io.issue1` are simultaneously high, this assertion fires.

## 3. Waveform Information

- **Waveform File**: `verilog/extra_bench/ibuf/iqc.issue1_one_hot0.fst`
- **Time Range**: 0 ns → 30 ns (3 cycles)
- **Failure Point**: **Time = 20 ns** (cycle 2)

### Key Signal Values at Failure Point (Time = 20 ns)

| Signal | Value | Meaning |
|--------|-------|---------|
| `io.issue1[2:0]` | `110` | **Both slot1 and slot2 issued to exec unit 1 — VIOLATION** |
| `io.valid[2:0]` | `110` | Slots 1 and 2 are valid |
| `qAge[2:0]` | `100` | `qAge(2)=1` → **slot1 older than slot2** |
| `io.exeReady[1:0]` | `10` | Execution unit 1 ready, unit 0 not ready |
| `io.opsReady[2:0]` | `111` | All slots have ready operands |
| `io.flush[2:0]` | `111` | All slots being flushed (but affects next cycle, not current) |
| `issue0_0,issue0_1,issue0_2` | `0,0,0` | No instructions issued to execution unit 0 |
| `issue1_0,issue1_1,issue1_2` | `0,1,1` | **Both slot1 and slot2 selected for execution unit 1** |
| `io.iqLoads[1:0]` | `11` | Both dispatch ports active |

### Prior Cycle (Time = 10 ns)
| Signal | Value |
|--------|-------|
| `io.valid[2:0]` | `010` | Only slot1 valid |
| `io.flush[2:0]` | `001` | Slot0 being flushed |
| `io_load2[1:0]` | `10` | Slot2 loaded (load2_1=1) |

## 4. Root Cause Analysis

### Bug Location

**File**: `iqc.scala`, **line 116**
**Module**: `iqc` class
**Signal**: `issue1_2` — issue decision for queue slot 2 to execution unit 1

### Buggy Code

```scala
val issue1_2 = io.exeReady(1) & io.opsReady(2) & valid(2) &
    (~qAge(1) | ~io.opsReady(0) | issue0_0) &
    (qAge(2) | ~io.opsReady(1) | issue0_1) & ~issue0_2   // <-- BUG: qAge(2) should be ~qAge(2)
```

### Correct Code

```scala
val issue1_2 = io.exeReady(1) & io.opsReady(2) & valid(2) &
    (~qAge(1) | ~io.opsReady(0) | issue0_0) &
    (~qAge(2) | ~io.opsReady(1) | issue0_1) & ~issue0_2   // Fix: qAge(2) → ~qAge(2)
```

### Description of the Bug

The `issue1_2` signal determines whether slot 2 can issue its instruction to execution unit 1. Its logic must respect **age-based priority**: if slot 1 is older than slot 2 (`qAge(2)=1`), slot 2 should only issue to unit 1 if slot 1's operands are NOT ready (`~opsReady(1)`) OR slot 1 already got issued by unit 0 (`issue0_1`).

The current code uses `(qAge(2) | ~io.opsReady(1) | issue0_1)`. When `qAge(2)=1` (slot1 older than slot2), this condition evaluates to `1` unconditionally, making `issue1_2` independent of whether slot1's operands are ready or not. This is semantically backwards — it should be `(~qAge(2) | ...)` so that when slot1 is older, slot2 needs an additional reason (operands not ready or slot1 already committed to unit 0).

### Comparison with Correct Logic

For reference, the corresponding condition in `issue0_2` (line 100) is CORRECT:

```scala
val issue0_2 = io.exeReady(0) & io.opsReady(2) & valid(2) &
    (~qAge(1) | ~io.opsReady(0)) &
    (~qAge(2) | ~io.opsReady(1))                        // Correct: uses ~qAge(2)
```

And `issue1_1`'s condition against slot2 (line 115) is also correct:

```scala
val issue1_1 = ... &
    (qAge(2) | ~io.opsReady(2) | issue0_2) & ~issue0_1 // Correct: uses qAge(2)
```

In `issue1_1`, `qAge(2)=1` means slot1 is older, so slot1 can issue regardless — this is correct. But in `issue1_2`, `qAge(2)=1` should BLOCK slot2 from issuing ahead of slot1, so `~qAge(2)` is needed.

### Evidence from Waveform

At time 20 ns:
- `qAge(2)=1` → slot1 is older than slot2
- `opsReady(1)=1` → slot1's operands are ready
- `issue0_1=0` → slot1 did NOT issue to unit 0

Despite slot1 being older AND ready AND not already issued, `issue1_2=1` because `(qAge(2)=1)` short-circuits the priority check to `true`. This causes both `issue1_1=1` and `issue1_2=1`, violating the one-hot property of `io.issue1`.

### Error Classification

**Category: Bug in the Original Design (DUT Bug)**

The assertion `assertOneHot0(io.issue1, "issue1_one_hot0")` is correctly specified for the design. The top module constraints are reasonable. The bug is a genuine logic error in the `issue1_2` signal's age-priority condition, where `qAge(2)` was used instead of `~qAge(2)`.
