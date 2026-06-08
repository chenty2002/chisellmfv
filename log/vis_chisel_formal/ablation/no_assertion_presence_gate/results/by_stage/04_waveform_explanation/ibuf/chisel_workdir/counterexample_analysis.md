# Counterexample Analysis Report: `iqc.issue0_mutex`

## 1. Verification Environment

### Top Module and Structure
- **Top Module**: `iqc` (in package `llmverify`)
- **Source File**: `iqc.scala`
- **Design**: An instruction queue controller (IQC) with 3 entries. It manages:
  - Loading instructions from 2 dispatch ports into free queue slots
  - Issuing instructions to 2 execution units with age-based priority
  - Tracking valid bits and age ordering across entries

### Key Components
- **`valid`** (3-bit register): Tracks which entries hold valid instructions
- **`qAge`** (3-bit register): Age ordering matrix:
  - `qAge(0)` = 1 → entry 0 is older than entry 1
  - `qAge(1)` = 1 → entry 0 is older than entry 2
  - `qAge(2)` = 1 → entry 1 is older than entry 2
- **`issue0_x`** / **`issue1_x`**: Issue select signals for execution units 0 and 1
- **`io.iqLoads`**: Load dispatcher inputs (2 bits, one per dispatch port)
- **`io.opsReady`**: Operand-ready status per entry (3 bits)
- **`io.exeReady`**: Execution unit ready status (2 bits)

## 2. Violated Assertion

- **Assertion Name**: `issue0_mutex` (from waveform filename `iqc.issue0_mutex.fst`)
- **Assertion Code** (line 82):
  ```scala
  assertMutex(Seq(issue0_1, issue0_0, issue0_2), "issue0_mutex")
  ```
  The `assertMutex` helper asserts that at most one of the signals is high at any time.

- **Property Description**: At most one queue entry can issue to execution unit 0 per cycle. This ensures that execution unit 0 receives only one instruction per cycle, preventing resource conflicts.

- **File Location**: `iqc.scala`, line 82

## 3. Waveform Information

- **Waveform File**: `verilog/extra_bench/ibuf/iqc.issue0_mutex.fst`
- **Time Range**: 0 ns → 30 ns (3 clock cycles)
- **Key Time Point**: **20 ns** — where the assertion fails

### Critical Signal Values at 20 ns

| Signal | Value | Meaning |
|--------|-------|---------|
| `valid` | `0b111` | All 3 entries valid |
| `qAge` | `0b111` | Entry 0 > Entry 1 > Entry 2 (age ordering) |
| `io.opsReady` | `0b110` | Entries 1 and 2 have ready operands; entry 0 does not |
| `io.exeReady` | `0b11` | Both execution units ready |
| `issue0_1` | **1** | Entry 1 selected for execution unit 0 |
| `issue0_2` | **1** | Entry 2 ALSO selected for execution unit 0 — **violation** |
| `issue0_0` | 0 | Entry 0 not selected (operands not ready) |
| `issue0_mutex` | **0** | Assertion failed (dropped from 1 to 0 at 20 ns) |

### Sequence of Events

1. **Time 0 ns** (Reset): All entries invalid (`valid=0b000`), `qAge=0b000`. Load dispatcher loads entries 0 and 1 (`io.iqLoads=0b11`). Flush signal set for entry 2 (`io.flush=0b100`).

2. **Time 10 ns** (Cycle 1): Entries 0 and 1 become valid (`valid=0b011`). Entry 2 remains invalid due to flush. `qAge` updates to `0b111` (entry 0 older than 1, both older than 2). `io.opsReady` drops to `0b000` (operands not ready yet). `io.exeReady=0b10`.

3. **Time 20 ns** (Cycle 2 — **Failure**): Entry 2 becomes valid (`valid=0b111`). Entries 1 and 2 have ready operands (`io.opsReady=0b110`). Both `issue0_1=1` and `issue0_2=1` are asserted simultaneously, violating the mutex property.

## 4. Root Cause Analysis

### Buggy Code Location

- **File**: `iqc.scala`
- **Line**: 51
- **Module**: `iqc`
- **Signal**: `issue0_2`

### Bug Description

**The bug is a typo/incorrect age comparison operator in the `issue0_2` calculation.** Specifically, the last term uses `qAge(2)` (which means "entry 1 is older than entry 2") when it should use `~qAge(2)` (which means "entry 2 is older than entry 1").

**Buggy code** (line 50–51):
```scala
val issue0_2 = io.exeReady(0) & io.opsReady(2) & valid(2) &
    (~qAge(1) | ~io.opsReady(0)) & (qAge(2) | ~io.opsReady(1))
    //                                   ^^^^^^^^  BUG: should be ~qAge(2)
```

**Correct code should be**:
```scala
val issue0_2 = io.exeReady(0) & io.opsReady(2) & valid(2) &
    (~qAge(1) | ~io.opsReady(0)) & (~qAge(2) | ~io.opsReady(1))
```

### How the Age-Based Arbitration Works

The `qAge` encoding uses 3 bits to track pairwise age relationships:
- `qAge(0) = 1` → entry 0 is older than entry 1
- `qAge(1) = 1` → entry 0 is older than entry 2
- `qAge(2) = 1` → entry 1 is older than entry 2

For `issue0_2` (entry 2 issuing to execution unit 0), entry 2 must check it can issue ahead of:
- **Entry 0**: `(~qAge(1) | ~io.opsReady(0))` — correct. `~qAge(1)=1` when entry 2 is older than entry 0 (qAge(1)=0), OR entry 0's operands aren't ready.
- **Entry 1**: `(qAge(2) | ~io.opsReady(1))` — **BUG**. This says entry 2 can issue when `qAge(2)=1` (entry 1 is OLDER than entry 2), which is the wrong direction! It should be `~qAge(2)=1` (entry 2 is OLDER than entry 1).

### Evidence from Waveform

At time 20 ns:
- `qAge(2) = 1` → entry 1 is older than entry 2 (entry 1 has priority)
- `io.opsReady(1) = 1` → entry 1's operands are ready
- `io.opsReady(2) = 1` → entry 2's operands are ready

For `issue0_2`, the critical term evaluates as:
- `(qAge(2) | ~io.opsReady(1))` = `(1 | ~1)` = `(1 | 0)` = **1** (BUG — should be 0)
- If correct: `(~qAge(2) | ~io.opsReady(1))` = `(0 | 0)` = **0** ✓

Since entry 1 is older than entry 2 (qAge(2)=1) and both are ready, entry 1 should have exclusive access to execution unit 0. Entry 2 should be blocked. But due to the incorrect `qAge(2)` (instead of `~qAge(2)`), entry 2 is incorrectly allowed to issue simultaneously with entry 1, violating the mutex property.

### Similar Pattern in `issue1_2`

Line 62 has a similar pattern:
```scala
val issue1_2 = ... (qAge(2) | ~io.opsReady(1) | issue0_1) & ~issue0_2
```
Here the same `(qAge(2) | ~io.opsReady(1))` term appears, though it is not the direct cause of the `issue0_mutex` violation since `issue1_2` is gated by `~issue0_2`.

### Error Classification

This is a **DUT Bug** — a genuine logic error in the design. The age-based arbitration for entry 2 incorrectly allows it to issue ahead of an older, ready entry.
