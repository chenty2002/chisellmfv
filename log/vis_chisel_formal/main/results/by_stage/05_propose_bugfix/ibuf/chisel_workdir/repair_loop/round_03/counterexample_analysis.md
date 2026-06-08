# Counterexample Analysis Report: `iqc.slot0_issue_liveness`

## 1. Verification Environment

- **Top Module**: `iqc` (Instruction Queue with 3 slots, 2 execution units)
- **Design Structure**:
  - 3 queue slots (indices 0, 1, 2) with valid bits and age tracking
  - 2 dispatch ports (port 0 has precedence over port 1)
  - 2 execution units (exe0 has precedence over exe1)
  - Age-based priority: older instructions issue first
  - Age information stored in `qAge[2:0]` register
- **Key Components**:
  - `valid[2:0]` — valid bits for each slot
  - `qAge[2:0]` — age relationship between adjacent slots
  - `issue0_X` / `issue1_X` — issue signals for slot X to exe0/exe1
  - `nv0/1/2` — next valid values for each slot
- **Inputs**: `iqLoads`, `exeReady`, `opsReady`, `flush`

## 2. Violated Assertion

- **Assertion Name**: `slot0_issue_liveness`
- **Waveform File**: `iqc.slot0_issue_liveness.fst`
- **File Location**: `iqc.scala`, lines 132–136

### Code Snippet

```scala
astRelaxedLiveness(
    valid(0) && io.opsReady(0) && io.exeReady.orR && !io.flush(0),
    issue0_0 || issue1_0, 5,
    "slot0_issue_liveness"
)
```

### Property Description (in natural language)

"If slot 0 is valid, its operands are ready, ANY execution unit is ready, and slot 0 is NOT being flushed, then within 5 cycles, slot 0 must issue to either execution unit 0 or execution unit 1."

### Issue Logic for Reference

```scala
// Slot 0 to exe0
val issue0_0 = io.exeReady(0) & io.opsReady(0) & valid(0) &
    (qAge(0) | ~io.opsReady(1)) & (qAge(1) | ~io.opsReady(2))

// Slot 0 to exe1  
val issue1_0 = io.exeReady(1) & io.opsReady(0) & valid(0) &
    (qAge(0) | ~io.opsReady(1) | issue0_1) &
    (qAge(1) | ~io.opsReady(2) | issue0_2) & ~issue0_0
```

## 3. Waveform Information

- **Full Path**: `verilog/extra_bench/ibuf/iqc.slot0_issue_liveness.fst`
- **Time Range**: 0 ns → 90 ns (9 cycles)
- **Failure Time**: 80 ns (assertion signal `slot0_issue_liveness` transitions from 1 → 0)
- **Key Time Points**:

### t = 20 ns (Assertion Trigger Fires)
| Signal | Value | Notes |
|--------|-------|-------|
| `valid[2:0]` | `011` | Slots 0 and 1 valid |
| `opsReady[2:0]` | `111` | All slots' operands ready |
| `exeReady[1:0]` | `01` | exe0 ready, exe1 NOT ready |
| `flush[2:0]` | `000` | No flush active |
| `qAge[2:0]` | `110` | qAge(0)=0 (slot 0 younger than slot 1), qAge(1)=1, qAge(2)=1 |
| `issue0_0` | `0` | Slot 0 does NOT issue to exe0 — blocked by age check |
| `issue0_1` | `1` | **Slot 1 issues to exe0** — older instruction takes priority |
| `issue1_0` | `0` | Cannot issue to exe1 — exeReady(1)=0 |

### t = 30 ns (Flush Arrives, Instruction Wiped)
| Signal | Value | Notes |
|--------|-------|-------|
| `valid[2:0]` | `001` | Slot 1 cleared (issued at t=20), only slot 0 remains |
| `opsReady[2:0]` | `000` | All operands become not ready |
| `exeReady[1:0]` | `01` | exe0 still ready |
| `flush[2:0]` | `001` | **flush(0)=1** — slot 0 being flushed |
| `qAge[2:0]` | `011` | qAge(0)=1 now, but too late |
| `issue0_0` | `0` | Cannot issue — opsReady(0)=0 |
| `nv0` | `0` | Slot 0 is cleared by flush |

### t = 40–80 ns (Timer Runs, Assertion Fails)
| Time | `timer[2:0]` | `valid[2:0]` | `pending` | `slot0_issue_liveness` |
|------|:-----------:|:-----------:|:---------:|:---------------------:|
| 40   | 001         | 000         | 1         | 1 (holding) |
| 50   | 010         | 000         | 1         | 1 |
| 60   | 011         | 000         | 1         | 1 |
| 70   | 100         | 000         | 1         | 1 |
| 80   | 101         | 000         | 1         | **0 (FAIL)** |

## 4. Root Cause Analysis

### Root Cause Type: **Assertion Bug** (incorrect trigger condition)

### Bug Location

- **File**: `iqc.scala`
- **Lines**: 132–136
- **Signal**: `slot0_issue_liveness` assertion

### Description of the Problem

The assertion's trigger condition uses `io.exeReady.orR`, which evaluates to true when **any** execution unit is ready. However, slot 0's ability to issue depends on **which specific** execution unit is ready and the age-based priority logic:

```scala
// Trigger condition (too permissive):
valid(0) && io.opsReady(0) && io.exeReady.orR && !io.flush(0)
//                      ^^^^^^^^^^^^^^^^
//                      "any exe ready" — fires even when only exe0 is ready

// Actual issue condition for slot 0 to exe0:
io.exeReady(0) && ... && (qAge(0) | ~io.opsReady(1)) && (qAge(1) | ~io.opsReady(2))

// Actual issue condition for slot 0 to exe1:
io.exeReady(1) && ...
```

The assertion can fire when **only exe0** is ready (exeReady=01), but slot 0 may be **blocked from using exe0** by the age-based priority check `(qAge(0) | ~opsReady(1))`.

### Evidence from Waveform

**At t = 20 ns**, the sequence of events is:

1. **Trigger fires**: `valid(0)=1`, `opsReady(0)=1`, `exeReady.orR=1`, `!flush(0)=1` → all conditions met
2. **Slot 0 blocked from exe0**: `issue0_0 = 0` because `(qAge(0) | ~opsReady(1)) = (0 | 0) = 0`. Slot 0 is younger than slot 1 (qAge(0)=0), and slot 1 is also ready (opsReady(1)=1).
3. **Slot 0 blocked from exe1**: `issue1_0 = 0` because `exeReady(1)=0` (exe1 not ready).
4. **Slot 1 issues to exe0 instead**: `issue0_1 = 1` — the older instruction (slot 1) takes the only available execution unit.

**At t = 30 ns**:
- Slot 0 is now the only valid instruction, but `opsReady(0)=0` and `flush(0)=1`
- The instruction gets flushed without ever issuing

**Result**: The instruction was valid and ready for exactly one cycle (t=20 to t=30), but in that cycle it could not issue because the age-based priority gave the only ready execution unit to the older sibling (slot 1). The assertion expected it to issue within 5 cycles, but it was flushed before it ever could.

### Why This is an Assertion Bug

The assertion's trigger condition `io.exeReady.orR` does not accurately capture the conditions under which slot 0 can actually issue. A correct liveness assertion would need to account for:

1. **Execution unit specificity**: Slot 0 on exe0 is blocked when `qAge(0)=0 & opsReady(1)=1`. Even when exe0 is ready, slot 0 may not use it if a younger/older sibling is also ready.

2. **Age arbitration**: The age-based priority means that having *any* execution unit ready is insufficient — the *specific* execution unit that slot 0 can access must be ready, AND the age checks must pass.

3. **Two-cycle scenarios**: Even when slot 1 issues (freeing exe0 for slot 0), the environment may change (opsReady may become 0, flush may arrive) before slot 0 can issue in the next cycle.

### Possible Fixes

**Option A — Fix the assertion (recommended)**:
The trigger condition should account for the age-based issuing constraints. For example, the assertion could check that slot 0 can actually issue to either exe0 or exe1:

```scala
astRelaxedLiveness(
    valid(0) && io.opsReady(0) && !io.flush(0) &&
    ((io.exeReady(0) && (qAge(0) | ~io.opsReady(1)) && (qAge(1) | ~io.opsReady(2))) || 
     (io.exeReady(1) && ... )),
    issue0_0 || issue1_0, 5,
    "slot0_issue_liveness"
)
```

**Option B — Fix the DUT (if liveness is required)**:
Modify the issue logic to allow a younger instruction to use an execution unit when the older instruction that's blocking it has already been assigned to a different execution unit in the same cycle. However, this changes the issue priority semantics.

**Option C — Fix the setup (add constraints)**:
Add formal constraints to prevent unrealistic scenarios where opsReady changes from 111 to 000 simultaneously with a flush arriving. But this is a valid formal scenario, so constraining it away might hide real bugs.
