# Counterexample Analysis Report: `issue0_mutex_exactly_one_per_cycle`

## 1. Verification Environment

**Top Module**: `iqc` (Instruction Queue Controller)
- **Source File**: `iqc.scala` (127 lines)
- **Design**: An instruction queue controller with 3 issue slots and 2 execution units

**Key Components**:
- **`valid`** (3-bit register): Tracks which slots hold valid instructions
- **`qAge`** (3-bit register): Age comparator matrix tracking relative ages between slots:
  - `qAge[0]` = 1 means slot 0 is older than slot 1
  - `qAge[1]` = 1 means slot 0 is older than slot 2
  - `qAge[2]` = 1 means slot 1 is older than slot 2
- **`io.issue0`**: One-hot encoded output selecting which slot issues to execution unit 0
- **`io.issue1`**: One-hot encoded output selecting which slot issues to execution unit 1
- **`io.opsReady`**: Per-slot operand readiness indicator
- **Inputs**: `iqLoads`, `exeReady`, `opsReady`, `flush`

## 2. Violated Assertion

**Assertion Name**: `issue0_mutex_exactly_one_per_cycle`
- Extracted from waveform filename: `iqc.issue0_mutex_exactly_one_per_cycle.fst`

**Code** (iqc.scala, line 108):
```scala
assertOneHot0(io.issue0, "issue0_mutex_exactly_one_per_cycle")
```

**Property Description**: At most one slot may be issued to execution unit 0 per cycle. The output `io.issue0` must be one-hot (exactly 0 or 1 bits set).

## 3. Waveform Information

**Waveform File**: `verilog/extra_bench/ibuf/iqc.issue0_mutex_exactly_one_per_cycle.fst`

**Key Time Point**: **20 ns** (posedge of cycle 2)

### Critical Signal Values at Time 20 ns

| Signal | Value | Description |
|--------|-------|-------------|
| `io_exeReady` | 2'b11 | Both execution units ready |
| `io_opsReady` | 3'b110 | Slots 1 and 2 have ready operands; slot 0 does NOT |
| `valid` | 3'b111 | All three slots hold valid instructions |
| `qAge` | 3'b111 | Age ordering: slot 0 > slot 1 > slot 2 |
| **`io_issue0`** | **3'b110** | **VIOLATION: both slot 1 and slot 2 selected** |
| `issue0_0` | 0 | Slot 0 not selected (ops not ready) |
| `issue0_1` | 1 | Slot 1 selected |
| `issue0_2` | 1 | Slot 2 selected |
| `io_flush` | 3'b000 | No flushes active |
| `nv0, nv1, nv2` | 1, 1, 1 | All slots remain valid |

### Timeline Summary

| Time | Event |
|------|-------|
| 0 ns | Initial state: all signals zero |
| 10 ns | Cycle 1 posedge: slots 0 and 1 loaded (`valid`=3'b011), `qAge`=3'b111 |
| **20 ns** | **Cycle 2 posedge: all 3 slots valid, both issue0_1 and issue0_2 fire → assertion fails** |

## 4. Root Cause Analysis

### Bug Location

**File**: `iqc.scala`, **Line 49**
**Module**: `iqc`
**Bug Type**: **Category 1 — Bug in the Original Design** (incorrect arbitration comparison)

### Bug Description

The arbitration logic for **issue0_2** (issuing slot 2 to execution unit 0) contains a buggy age-comparison term. On line 49:

```scala
val issue0_2 = io.exeReady(0) & io.opsReady(2) & valid(2) &
    (~qAge(1) | ~io.opsReady(0)) & (qAge(2) | ~io.opsReady(1))
```

The term **`(qAge(2) | ~io.opsReady(1))`** is incorrect.

**Semantics of `qAge(2)`**: `qAge[2]` = 1 means **slot 1 is older than slot 2**. When slot 1 is older, slot 1 should have priority over slot 2 in arbitration.

The correct term should be **`(~qAge(2) | ~io.opsReady(1))`**, which:
- Allows slot 2 to issue when slot 2 is **NOT** older than slot 1 (i.e., slot 2 is younger: `~qAge(2)=1`) **OR** slot 1's operands aren't ready (`~io.opsReady(1)=1`)
- Properly blocks slot 2 when slot 1 is older (`qAge(2)=1`) **AND** slot 1's operands are ready (`io.opsReady(1)=1`)

### Comparison with Correct Arbitration Logic

Let's compare the complementary pairs for each slot-pair arbitration:

| Pair | Slot 0 term | Slot 1/2 term | Complementary? |
|------|------------|---------------|:---:|
| Slot 0 vs Slot 1 | `(qAge(0) \| ~opsReady(1))` | `(~qAge(0) \| ~opsReady(0))` | ✅ |
| Slot 0 vs Slot 2 | `(qAge(1) \| ~opsReady(2))` | `(~qAge(1) \| ~opsReady(0))` | ✅ |
| **Slot 1 vs Slot 2** | `(qAge(2) \| ~opsReady(2))` | **`(qAge(2) \| ~opsReady(1))`** | ❌ **BUG** |

The slot-1-vs-slot-2 pair is **not complementary**. The slot-2 term should be `(~qAge(2) | ~io.opsReady(1))` to properly mirror the slot-1 term `(qAge(2) | ~io.opsReady(2))`.

### Evidence from Waveform

At time 20 ns:
- `qAge(2)` = 1 (slot 1 is older than slot 2) → slot 1 should win arbitration
- `io_opsReady(1)` = 1 (slot 1's operands are ready)
- `io_opsReady(2)` = 1 (slot 2's operands are ready)
- `issue0_1` = 1 → slot 1 correctly wins (older and ready)
- `issue0_2` = 1 → **slot 2 ALSO wins**, violating one-hot exclusivity

The buggy term evaluation:
```
(qAge(2) | ~io.opsReady(1))  = (1 | ~1)  = (1 | 0)  = 1   ← BUG: passes incorrectly
```
With the correct term:
```
(~qAge(2) | ~io.opsReady(1)) = (0 | ~1)  = (0 | 0)  = 0   ← correctly blocks slot 2
```

### Fix

Change line 49 in `iqc.scala` from:
```scala
    (~qAge(1) | ~io.opsReady(0)) & (qAge(2) | ~io.opsReady(1))
```
to:
```scala
    (~qAge(1) | ~io.opsReady(0)) & (~qAge(2) | ~io.opsReady(1))
```

This ensures that when slot 1 is older (`qAge(2)=1`) and ready (`io.opsReady(1)=1`), slot 2 is correctly blocked from issuing to execution unit 0, preserving the one-hot mutual exclusion property.
