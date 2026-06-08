# Counterexample Analysis Report: iqc.issue0_onehot0

## 1. Verification Environment

- **Top Module:** `iqc` (Issue Queue Controller)
- **Source File:** `iqc.scala`
- **Generated Verilog Directory:** `chisel/extra_bench/ibuf/generated/`
- **Design Under Test:** A 3-entry issue queue that manages instruction dispatch from two load ports to two execution units. It tracks valid bits and age bits (qAge) to implement oldest-first arbitration.

### Key Components and Connections

| Component | Type | Description |
|-----------|------|-------------|
| `valid` | 3-bit Reg | Tracks which queue slots hold valid instructions |
| `qAge` | 3-bit Reg | Age comparison matrix: qAge[0]=entry0>entry1, qAge[1]=entry0>entry2, qAge[2]=entry1>entry2 |
| `io.iqLoads[1:0]` | Input | Dispatch port loads (port 0, port 1) |
| `io.exeReady[1:0]` | Input | Execution unit ready signals |
| `io.opsReady[2:0]` | Input | Operand-ready signals for each queue entry |
| `io.issue0[2:0]` | Output | Issue signals for execution unit 0 (one-hot expected) |
| `io.issue1[2:0]` | Output | Issue signals for execution unit 1 (one-hot expected) |

## 2. Violated Assertion

- **Assertion Name:** `issue0_onehot0` (from waveform filename `iqc.issue0_onehot0.fst`)
- **File Location:** `iqc.scala`, line 118
- **Code Snippet:**

```scala
assertOneHot0(io.issue0, "issue0_onehot0")
```

- **Property Description:** The `io.issue0` output must have at most one bit set (i.e., it must be one-hot or zero) on any cycle. This ensures that only one instruction is issued to execution unit 0 per cycle.

## 3. Waveform Information

- **Waveform File:** `verilog/extra_bench/ibuf/iqc.issue0_onehot0.fst`
- **Time Range:** 0–30 ns (3 cycles)
- **Failure Point:** Time = 20 ns (positive clock edge of cycle 2)

### Critical Signal Values at Time 20 ns

| Signal | Value | Description |
|--------|-------|-------------|
| `iqc.io_issue0 [2:0]` | `110` ✗ | **Two bits set** (entries 1 and 2) — violates one-hot |
| `iqc.issue0_0` | `0` | Entry 0 not issuing (not valid) |
| `iqc.issue0_1` | `1` | Entry 1 issuing |
| `iqc.issue0_2` | `1` | Entry 2 issuing (should NOT issue when entry 1 is older) |
| `iqc.valid [2:0]` | `110` | Entries 1 and 2 are valid |
| `iqc.qAge [2:0]` | `100` | qAge[2]=1 → entry 1 is older than entry 2 |
| `iqc.io_opsReady [2:0]` | `111` | All entries have ready operands |
| `iqc.io_exeReady [1:0]` | `01` | Execution unit 0 is ready |

### Signal Timeline

| Time | io_issue0 | valid | qAge | opsReady | exeReady | Event |
|------|-----------|-------|------|----------|----------|-------|
| 0 ns | 000 | 000 | 000 | 000 | 00 | Initial/reset state |
| 10 ns | 001 | 011 | 111 | 001 | 01 | Entry 0 loaded, issues; entries 0,1 loaded |
| 20 ns | **110** ✗ | 110 | 100 | 111 | 01 | **Both entries 1 and 2 issue simultaneously** |

## 4. Root Cause Analysis

### Buggy Location

- **File:** `iqc.scala`
- **Line:** 96
- **Module:** `iqc`
- **Buggy Expression:** `issue0_2` qualification logic

### Bug Description

The `issue0_2` signal (determining whether queue entry 2 issues to execution unit 0) contains a **typo in the age-comparison logic**.

The current (buggy) code at line 96:

```scala
val issue0_2 = io.exeReady(0) & io.opsReady(2) & valid(2) &
    (~qAge(1) | ~io.opsReady(0)) & (qAge(2) | ~io.opsReady(1))
                                              ^^^^^^^^
                                              BUG: should be ~qAge(2)
```

The correct logic should be:

```scala
val issue0_2 = io.exeReady(0) & io.opsReady(2) & valid(2) &
    (~qAge(1) | ~io.opsReady(0)) & (~qAge(2) | ~io.opsReady(1))
                                              ^^^^^^^^^
                                              FIX: negate qAge(2)
```

### Why This Is a Bug

The age arbitration for issue0 works as follows:

- **qAge[2]=1** means **entry 1 is older than entry 2**
- When entry 1 is older AND ready, it should issue, and entry 2 should **not** issue to the same port
- The condition for issue0_2 should allow entry 2 to issue only if **entry 2 is NOT younger than entry 1** (i.e., `~qAge(2)`) **OR** entry 1 is not ready (`~io.opsReady(1)`)

### Comparison with Correct Conditions

| Signal | Current Condition | Correct Condition |
|--------|-------------------|-------------------|
| `issue0_0` | `(qAge(0) \| ~io.opsReady(1)) & (qAge(1) \| ~io.opsReady(2))` | ✅ Correct |
| `issue0_1` | `(~qAge(0) \| ~io.opsReady(0)) & (qAge(2) \| ~io.opsReady(2))` | ✅ Correct |
| `issue0_2` | `(~qAge(1) \| ~io.opsReady(0)) & (qAge(2) \| ~io.opsReady(1))` | ❌ **Should be `~qAge(2)`** |

### Concrete Waveform Evidence

At time 20 ns:
- `qAge[2]=1` → entry 1 is older than entry 2
- `io.opsReady[1]=1` → entry 1 is ready
- With correct `(~qAge(2) | ~io.opsReady(1)) = (~1 | ~1) = (0 | 0) = 0`, issue0_2 would be **blocked**
- With buggy `(qAge(2) | ~io.opsReady(1)) = (1 | ~1) = (1 | 0) = 1`, issue0_2 is incorrectly **allowed**

This causes both `issue0_1` and `issue0_2` to be `1` simultaneously, producing `io_issue0 = 110`, which directly violates the `assertOneHot0(io.issue0, "issue0_onehot0")` assertion.

### Error Classification

**Category: Bug in the Original Design (DUT Bug)**

The assertion is correct — `io.issue0` should indeed be one-hot to ensure only one instruction issues per execution unit per cycle. The age-comparison logic in `issue0_2` uses `qAge(2)` instead of `~qAge(2)`, which is a typo/bug that breaks the oldest-first arbitration. The setup and assertion are both valid.
