# Counterexample Analysis Report: `iqc.slot0_issue_liveness`

## 1. Verification Environment

- **Top module**: `iqc` (Issue Queue Controller) in package `llmverify`
- **Source file**: `chisel/extra_bench/ibuf/iqc.scala`
- **Design under test**: A 3-slot issue queue controller that manages:
  - Loading uops from 2 dispatch ports into a 3-entry queue
  - Issuing uops to 2 execution units with age-based priority
  - Flushing individual queue slots
  - Tracking instruction age (qAge register)
- **Key components**: `valid` register (3-bit), `qAge` register (3-bit), issue arbiter logic, load logic

## 2. Violated Assertion

- **Assertion name**: `slot0_issue_liveness` (from waveform filename `iqc.slot0_issue_liveness.fst`)
- **Location**: `iqc.scala`, lines 124-128

```scala
astRelaxedLiveness(
    valid(0) && io.opsReady(0) && io.exeReady.orR,
    issue0_0 || issue1_0, 5,
    "slot0_issue_liveness"
)
```

- **Property**: When slot 0 is valid (has a uop), its operands are ready, and at least one execution unit is ready, then within 5 cycles slot 0 must issue to either execution unit 0 or execution unit 1.
- **Bound**: 5 cycles

## 3. Waveform Information

- **Waveform file**: `verilog/extra_bench/ibuf/iqc.slot0_issue_liveness.fst`
- **Time range**: 0 ns → 90 ns (9 cycles, clock period = 10 ns)
- **Key time points**:

| Time (ns) | Cycle | Event |
|-----------|-------|-------|
| 0 (posedge) | 0 | All signals reset, `valid=000`, `qAge=000`, flush(0)=1 |
| 10 (posedge) | 1 | `valid=010` (slot 1 loaded), `io_load0=10` (load to slot 0 via port 1), `io_iqLoads=10` |
| 20 (posedge) | 2 | **Trigger fires**: `valid=011`, `io_opsReady=111`, `io_exeReady=01`, `io_flush=001` |
| 20 (posedge) | 2 | `issue0_1=1` (slot 1 issues to FU 0), `issue0_0=0` (slot 0 blocked) |
| 30 (posedge) | 3 | `valid=000` (slot 0 flushed, slot 1 issued), `pending=1` |
| 40 | 4 | `timer=001`, `valid=000`, no issue possible |
| 50 | 5 | `timer=010`, `valid=000`, no issue possible |
| 60 | 6 | `timer=011`, `valid=000`, no issue possible |
| 70 | 7 | `timer=100`, `valid=000`, no issue possible |
| **80** | **8** | **`timer=101` (bound=5 reached), assertion fails** |

### Critical Signal Values at Key Time Points

**At t=20 (trigger fires)**:
| Signal | Value |
|--------|-------|
| `iqc.valid [2:0]` | `011` (slots 0 and 1 valid) |
| `iqc.qAge [2:0]` | `110` (age order: slot 1 > slot 0 > slot 2) |
| `iqc.io_flush [2:0]` | `001` (flush slot 0) |
| `iqc.io_opsReady [2:0]` | `111` (all ops ready) |
| `iqc.io_exeReady [1:0]` | `01` (only FU 0 ready) |
| `iqc.io_issue0 [2:0]` | `010` (issue0_1=1, slot 1 issues to FU 0) |
| `iqc.io_issue1 [2:0]` | `000` (no issue to FU 1) |
| `iqc.pending` | `0` (not yet set) |

**At t=30 (cycle after trigger)**:
| Signal | Value |
|--------|-------|
| `iqc.valid [2:0]` | `000` (both slots cleared) |
| `iqc.pending` | `1` (assertion pending) |
| `iqc.timer [2:0]` | `000` (timer starts) |

**At t=80 (assertion failure)**:
| Signal | Value |
|--------|-------|
| `iqc.valid [2:0]` | `000` (all slots empty) |
| `iqc.timer [2:0]` | `101` (5 cycles elapsed = bound reached) |
| `iqc.pending` | `1` (assertion still pending at failure) |
| `iqc.io_issue0 [2:0]` | `000` |
| `iqc.io_issue1 [2:0]` | `000` |

## 4. Root Cause Analysis

### Classification: **Incorrect Assertion** (assertion_error)

### Detailed Explanation

The liveness assertion `slot0_issue_liveness` requires that whenever `valid(0) && io.opsReady(0) && io.exeReady.orR` is true, the target `issue0_0 || issue1_0` must become true within 5 cycles. However, the trigger condition **does not exclude the case where `io.flush(0)` is simultaneously asserted**, leading to a false failure.

### Evidence from Waveform

**Sequence of events:**

1. **Cycle 1 (t=10)**: Two uops are loaded into slots 0 and 1. Slot 1 is the older uop (`qAge(2)=1` at t=10, meaning slot 1 > slot 2, and later at t=20 `qAge=110` means slot 1 > slot 0 > slot 2).

2. **Cycle 2 (t=20)**: Both slots 0 and 1 are valid (`valid=011`), all operands are ready (`opsReady=111`), and only FU 0 is ready (`exeReady=01`). Simultaneously, `flush(0)=1` is asserted.

3. **Issue arbitration at t=20**: The issue logic gives priority to older instructions:
   ```scala
   issue0_0 = io.exeReady(0) & io.opsReady(0) & valid(0) &
       (qAge(0) | ~io.opsReady(1)) & (qAge(1) | ~io.opsReady(2))
   ```
   Since `qAge(0)=0` (slot 0 is younger than slot 1) and `opsReady(1)=1` (slot 1's ops are ready), the term `(qAge(0) | ~io.opsReady(1)) = (0 | 0) = 0`, forcing `issue0_0 = 0`.

   For FU 1: `issue1_0` requires `io.exeReady(1)=1`, but `exeReady=01`, so `exeReady(1)=0` and `issue1_0 = 0`.

   Result: **Slot 0 cannot issue** because FU 0 is taken by the older slot 1, and FU 1 is not ready.

4. **Flush takes effect**: At t=20, the next-value logic computes:
   ```scala
   nv0 = ~io.flush(0) & (valid(0) & ~(issue0_0 | issue1_0) | io.load0.orR)
       = ~1 & (1 & ~(0|0) | 0)
       = 0
   ```
   So `nv0=0`, meaning slot 0's valid bit is cleared at the next clock edge.

5. **Cycle 3 (t=30)**: `valid=000` — slot 0 is gone. The assertion's trigger was already true at t=20, but the target can never fire because both `issue0_0` and `issue1_0` require `valid(0)` as a precondition (confirmed by safety assertions `issue0_0_requires_valid` and `issue1_0_requires_valid`).

6. **Cycles 3-8 (t=30 to t=80)**: With `valid(0)=0`, the target `issue0_0 || issue1_0` remains permanently false. The timer reaches 5 at t=80, causing the liveness assertion to fail.

### Why This is an Assertion Error

The liveness property intends to verify that "a valid, ready instruction eventually issues." However, when `flush(0)` is asserted, the instruction in slot 0 is being explicitly cancelled by the system. The DUT correctly implements flush behavior: the valid bit is cleared and no issue is attempted. The assertion should not expect a flushed instruction to issue.

**The correct trigger condition should be:**
```scala
valid(0) && io.opsReady(0) && io.exeReady.orR && !io.flush(0)
```
or equivalently, it should also be guarded against simultaneous flush.

### Buggy Code Location

- **File**: `iqc.scala`
- **Lines**: 122-128
- **Issue**: The trigger condition of `astRelaxedLiveness` for slot 0 needs `!io.flush(0)` to exclude the case where the instruction in slot 0 is being flushed.

### Design Correctness Note

The DUT's behavior is correct:
- Age-based arbitration correctly gives priority to older slot 1 over younger slot 0
- When `flush(0)=1`, slot 0 is correctly cleared
- The issue logic correctly prevents issuing a flushed instruction

The fix should be applied to the assertion, not the DUT.
