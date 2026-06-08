# Counterexample Analysis Report: `iqc.slot0_forward_progress`

## 1. Verification Environment

- **Top Module**: `iqc` (in package `llmverify`, file `iqc.scala`)
- **Design Description**: An instruction queue (IQ) with 3 slots that accepts instructions from 2 dispatch ports and issues to 2 execution units. It implements age-based priority ordering and operand-readiness tracking.
- **Key Components**:
  - `valid(3)`: Valid bits for 3 queue slots
  - `qAge(3)`: Age comparison bits (qAge(0)=slot0>slot1, qAge(1)=slot0>slot2, qAge(2)=slot1>slot2)
  - `issue0_x` / `issue1_x`: Issue signals to execution units 0 and 1
  - `io_iqLoads`: Dispatch load signals (2-bit, port0 and port1)
  - `io_exeReady`: Execution unit ready signals (2-bit)
  - `io_opsReady`: Operand ready signals (3-bit, one per slot)
  - `io_flush`: Flush signals (3-bit, one per slot)

## 2. Violated Assertion

- **Assertion Name**: `slot0_forward_progress` (extracted from waveform filename `iqc.slot0_forward_progress.fst`)
- **File Location**: `iqc.scala`, lines 153–157
- **Code Snippet**:
  ```scala
  astRelaxedLiveness(
    valid(0),
    !valid(0) || issue0_0 || issue1_0 || io.flush(0),
    20, "slot0_forward_progress"
  )
  ```
- **Property Description**: This liveness assertion checks that whenever slot 0 is valid (`valid(0)` is true), then within 20 cycles, one of the following must occur:
  1. `valid(0)` becomes false (instruction exits/clears)
  2. `issue0_0` fires (slot 0 issues to execution unit 0)
  3. `issue1_0` fires (slot 0 issues to execution unit 1)
  4. `io.flush(0)` fires (slot 0 gets flushed)

## 3. Waveform Information

- **Waveform File**: `verilog/extra_bench/ibuf/iqc.slot0_forward_progress.fst`
- **Duration**: 230 ns (23 cycles of 10 ns each)
- **Failure Point**: `slot0_forward_progress` transitions from `1` to `0` at **t = 220 ns** (cycle 22)
- **Critical Timeline**:

| Time (ns) | Cycle | Key Events |
|-----------|-------|------------|
| t=0 | 0 | Initial state. `valid=000`, `io_iqLoads=11` (both ports active) |
| t=10 | 1 | `valid=011` (slots 0 and 1 become valid). `qAge=001` (slot0 older than slot1). `opsReady=010` |
| t=70 | 7 | **Critical missed opportunity**: `valid=011`, `qAge=001`, `exeReady=01`, **`opsReady=111`** (all three slots' operands ready!). But `issue0_0=0` because the age condition `(qAge(1) | ~opsReady(2))` evaluates to `(0 | 0) = 0` — slot 2 is invalid but `opsReady(2)=1` blocks slot 0. |
| t=130 | 13 | `valid=011`, `qAge=001`, `exeReady=01`, `opsReady=010`. Slot 1 issues (`issue0_1=1`), but slot 0 cannot issue because `opsReady(0)=0`. |
| t=210 | 21 | Last cycle before failure. `valid=011`, `exeReady=01`, `opsReady=010`. Slot 1 issues again but slot 0 remains stuck. `slot0_forward_progress` is still `1` (assertion not yet violated). |
| t=220 | 22 | **Assertion violation**: `slot0_forward_progress` becomes `0`. |

## 4. Root Cause Analysis

### Bug Category: **Bug in the Original Design (DUT Bug)**

### Buggy Code Location

**File**: `iqc.scala`, **lines 46–47**

```scala
val issue0_0 = io.exeReady(0) & io.opsReady(0) & valid(0) &
    (qAge(0) | ~io.opsReady(1)) & (qAge(1) | ~io.opsReady(2))
```

### Description of the Bug

The `issue0_0` signal determines whether slot 0 can issue to execution unit 0. It includes an age-based priority check against slot 2:

```
(qAge(1) | ~io.opsReady(2))
```

This condition says: slot 0 can issue to EX0 if either:
- Slot 0 is older than slot 2 (`qAge(1) = 1`), **OR**
- Slot 2's operands are NOT ready (`~opsReady(2) = 1`)

**The bug**: This condition does **not** check whether slot 2 is actually valid (`valid(2)`). When slot 2 is empty (invalid) but the testbench drives `opsReady(2)=1` (e.g., at t=70 ns), the condition evaluates to `(0 | 0) = 0`, which incorrectly blocks slot 0 from issuing — even though slot 2 has no instruction to issue.

### Evidence from Waveform (t = 70 ns)

At t = 70 ns, the following conditions hold:

| Signal | Value | Condition for issue0_0 |
|--------|-------|----------------------|
| `exeReady(0)` | 1 | ✓ Execution unit 0 ready |
| `opsReady(0)` | 1 | ✓ Slot 0 operands ready |
| `valid(0)` | 1 | ✓ Slot 0 is valid |
| `qAge(0)` | 1 | ✓ `(qAge(0) | ~opsReady(1)) = (1 | 0) = 1` |
| **`qAge(1)`** | **0** | ✗ `(qAge(1) | ~opsReady(2)) = (0 | 0) = 0` |
| `opsReady(1)` | 1 | |
| **`opsReady(2)`** | **1** | **Slot 2 opsReady=1 but valid(2)=0!** |
| **`valid(2)`** | **0** | **Slot 2 is NOT valid — no instruction present** |

All five prerequisites for `issue0_0` are satisfied **except** the last age condition `(qAge(1) | ~io.opsReady(2))`. Slot 2 is invalid (`valid(2)=0`), but `opsReady(2)=1` causes `~opsReady(2)=0`, making the condition fail. Slot 0 is erroneously blocked from issuing.

### Why the Age Bit `qAge(1)` Is 0

The `qAge(1)` bit (slot0 older than slot2) is computed as (line 74):
```scala
nv0 & nv2 & (qAge(1) | ~valid(2))
```

Since `nv2` is `0` (slot 2 either invalid or being flushed), the AND gate forces `qAge(1) = 0`. This is correct behavior for the age register — you cannot compare ages when a slot is invalid. **The bug is that the issue logic uses `qAge(1)` without falling back when `valid(2)=0`.**

### Why This Causes the Assertion to Fail

1. Slot 0 becomes valid at t = 10 ns and remains valid for 22 cycles.
2. `io.flush(0)` is **never** asserted throughout the entire trace.
3. `issue1_0` can **never** fire because `exeReady(1)` remains `0` throughout.
4. `issue0_0` requires `opsReady(0)=1`, which only occurs at t = 70 ns.
5. At t = 70 ns, the only time all other conditions align, the invalid-slot-2 age bug blocks `issue0_0`.
6. After t = 70 ns, `opsReady(0)` becomes `0` again, so `issue0_0` can never fire.
7. After 20 cycles of valid(0)=1 without resolution, the liveness assertion `slot0_forward_progress` fails at t = 220 ns.

### Proposed Fix

Modify the `issue0_0` condition on line 47 to account for invalid slots:

```scala
// Original (buggy):
val issue0_0 = io.exeReady(0) & io.opsReady(0) & valid(0) &
    (qAge(0) | ~io.opsReady(1)) & (qAge(1) | ~io.opsReady(2))

// Fixed:
val issue0_0 = io.exeReady(0) & io.opsReady(0) & valid(0) &
    (qAge(0) | ~io.opsReady(1) | ~valid(1)) & (qAge(1) | ~io.opsReady(2) | ~valid(2))
```

The fix adds `| ~valid(1)` to the first age condition and `| ~valid(2)` to the second. When a slot is invalid (no instruction present), its `opsReady` signal is irrelevant and should not block the issuing of instructions from the current slot.

The same pattern should also be checked for consistency in:
- `issue0_1` (line 48-49): condition `(qAge(2) | ~io.opsReady(2))` — could be `(qAge(2) | ~io.opsReady(2) | ~valid(2))`
- `issue1_0` (line 54-56): condition `(qAge(1) | ~io.opsReady(2) | issue0_2)` — `issue0_2` already requires `valid(2)`, so this is less critical but could also benefit from the explicit valid check
