# Counterexample Analysis: `issue0_mutex_12`

## 1. Verification Environment

- **Top Module**: `iqc` (Issue Queue Controller)
- **Source File**: `iqc.scala` (202 lines)
- **Design Under Test**: A 3-slot issue queue (instruction queue) with:
  - 2 dispatch ports (load from IQ)
  - 2 execution units (issue to execution)
  - Age-based priority arbitration for issuing
  - Per-slot valid bits, operand-ready tracking, and flush support
- **Key Components**:
  - `valid[2:0]` — which slots are occupied
  - `qAge[2:0]` — age-based priority encoding between slots
  - `issue0[2:0]` / `issue1[2:0]` — which slot issues to exec unit 0 / 1
  - `opsReady[2:0]` — per-slot operand readiness
  - `exeReady[1:0]` — execution unit readiness

## 2. Violated Assertion

- **Full Assertion Name**: `issue0_mutex_12`
- **Waveform Filename**: `iqc.issue0_mutex_12.fst`
- **Code Snippet** (iqc.scala, lines 85-87):
  ```scala
  AssertProperty(!(issue0_1 && issue0_2), "issue0_mutex_12")
  ```
- **Property**: In any given cycle, at most one of slots 1 and 2 may issue to execution unit 0. Both `issue0_1` and `issue0_2` cannot be true simultaneously.
- **File Location**: `iqc.scala`, line 87

## 3. Waveform Information

- **Full Waveform Path**: `verilog/extra_bench/ibuf/iqc.issue0_mutex_12.fst`
- **Waveform Duration**: 0–30 ns (3 clock cycles)
- **Failure Point**: Time = 20 ns (cycle 2, rising edge of clock)
- **Key Signal Values at Failure (time = 20 ns)**:

| Signal | Value | Description |
|--------|-------|-------------|
| `valid[2:0]` | `111` | All 3 slots occupied |
| `qAge[2:0]` | `110` | Age encoding: qAge(2)=1, qAge(1)=1, qAge(0)=0 |
| `opsReady[2:0]` | `110` | Slots 1 and 2 have ready operands; slot 0 does not |
| `exeReady[1:0]` | `01` | Execution unit 0 is ready |
| `issue0_0` | `0` | Slot 0 does not issue (ops not ready) |
| `issue0_1` | **`1`** | Slot 1 issues to exec unit 0 — **BUG: should not co-occur** |
| `issue0_2` | **`1`** | Slot 2 issues to exec unit 0 — **BUG: should not co-occur** |
| `flush[2:0]` | `000` | No flushes |
| `iqLoads[1:0]` | `11` | Both dispatch ports active |

### Timeline

| Cycle | Time (ns) | Event |
|-------|-----------|-------|
| 0 | 0–10 | Initial state: `valid=000`, `qAge=000`. `flush(0)=1`, loads fill slots 0 and 1. |
| 1 | 10–20 | `valid=010` (slot 1 valid), `qAge=100`. Slot 2 loaded via dispatch port 1. |
| 2 | 20–30 | `valid=111` (all slots full), `qAge=110`. `exeReady(0)=1`, `opsReady=110`. **Both `issue0_1` and `issue0_2` fire → assertion violation** |

## 4. Root Cause Analysis

### Bug Location

**File**: `iqc.scala`, **line 63** (within the `issue0_2` definition)

### Bug Description

The `issue0_2` formula contains an incorrect age-comparison term. The mutual-exclusion guarantee between `issue0_1` and `issue0_2` depends on the `qAge(2)` bit, which encodes the relative age of slot 1 vs. slot 2:

- **`qAge(2) = 1`**: slot 1 is **older** than slot 2 → slot 1 has priority
- **`qAge(2) = 0`**: slot 2 is **older** than slot 1 → slot 2 has priority

The current (buggy) code is:

```scala
val issue0_2 = io.exeReady(0) & io.opsReady(2) & valid(2) &
    (~qAge(1) | ~io.opsReady(0)) & (qAge(2) | ~io.opsReady(1))    // ← BUG
```

The last term `(qAge(2) | ~io.opsReady(1))` is **wrong**. It allows `issue0_2` to fire when `qAge(2) = 1`, which means "slot 1 is older than slot 2." But in that case, slot 1 should have priority, and slot 2 should **not** fire.

The correct condition should be **`(~qAge(2) | ~io.opsReady(1))`**, meaning: slot 2 can issue only if **slot 2 is older** (~qAge(2), i.e., qAge(2)=0) **or** slot 1's operands aren't ready.

**Fix**: Change line 63 from:
```scala
    (~qAge(1) | ~io.opsReady(0)) & (qAge(2) | ~io.opsReady(1))
```
to:
```scala
    (~qAge(1) | ~io.opsReady(0)) & (~qAge(2) | ~io.opsReady(1))
```

### Evidence from Waveform

At time = 20 ns:
- `qAge(2) = 1` → slot 1 is older than slot 2
- `opsReady(1) = 1` → slot 1's operands are ready
- `opsReady(2) = 1` → slot 2's operands are ready

**Current (buggy) behavior**:
- `issue0_1`: checks `(qAge(2) | ~opsReady(2))` = `(1 | 0)` = **1** → fires ✓ (slot 1 is older)
- `issue0_2`: checks `(qAge(2) | ~opsReady(1))` = `(1 | 0)` = **1** → **also fires** ✗ (should be blocked)

**With fix**:
- `issue0_1`: checks `(qAge(2) | ~opsReady(2))` = `(1 | 0)` = **1** → fires ✓
- `issue0_2`: checks `(~qAge(2) | ~opsReady(1))` = `(0 | 0)` = **0** → **blocked** ✓

### Systematic Age-Check Pattern

The correct age-check pattern across all three slot-issue signals should follow this consistent matrix:

| Slot issuing | vs slot 0 | vs slot 1 | vs slot 2 |
|-------------|-----------|-----------|-----------|
| issue0_0    | —         | qAge(0)   | qAge(1)   |
| issue0_1    | ~qAge(0)  | —         | **qAge(2)** |
| issue0_2    | ~qAge(1)  | **~qAge(2)** | —       |

The bug is in the **(issue0_2, vs slot 1)** cell: it uses `qAge(2)` instead of `~qAge(2)`.

### Classification

**Error Type**: `dut_bug` — genuine RTL design bug in the `issue0_2` arbitration logic that fails to guarantee mutual exclusion on execution unit 0 when both slots 1 and 2 are ready and slot 1 is older.
