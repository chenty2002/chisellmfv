# Counterexample Analysis Report: `entry0_progress` Failure

## 1. Verification Environment

- **Top Module**: `iqc` (package `llmverify`)
- **Module Type**: Issue Queue Controller (IQC) — a 3-entry issue queue with 2 execution units and 2 dispatch ports
- **Key Components**:
  - 3 internal registers: `valid` (3-bit), `qAge` (3-bit age matrix)
  - 2 dispatch ports producing load signals for 3 entries
  - 2 execution units with arbitration logic (older-first, exec0 priority over exec1)
  - Formal fairness timer `exeReadyTimer` (3-bit)
- **Connections**:
  - `io.iqLoads[1:0]` — dispatch port activity (2-bit)
  - `io.exeReady[1:0]` — execution unit ready signals (2-bit)
  - `io.opsReady[2:0]` — operand-ready per entry (3-bit)
  - `io.flush[2:0]` — flush per entry (3-bit)
  - `io.issue0[2:0]` / `io.issue1[2:0]` — issue signals to exec units 0 and 1
  - `io.valid[2:0]` — valid bits (output)
  - `io.load0[1:0]` / `io.load1[1:0]` / `io.load2[1:0]` — load signals per entry

## 2. Violated Assertion

- **Full Assertion Name**: `entry0_progress`
- **Waveform File**: `verilog/extra_bench/ibuf/iqc.entry0_progress.fst`
- **Location**: `iqc.scala`, lines 135–140

### Code Snippet

```scala
astRelaxedLiveness(
    valid(0) && io.opsReady(0),
    issue0_0 || issue1_0 || !valid(0) || !io.opsReady(0),
    8,
    "entry0_progress"
)
```

### Property Description

**Trigger**: `valid(0) && io.opsReady(0)` — Entry 0 is valid and its operands are ready.

**Success Condition**: `issue0_0 || issue1_0 || !valid(0) || !io.opsReady(0)` — Within a bounded number of cycles, one of the following must occur:
1. Entry 0 issues to execution unit 0 (`issue0_0`)
2. Entry 0 issues to execution unit 1 (`issue1_0`)
3. Entry 0 becomes invalid (`!valid(0)`)
4. Entry 0's operands become not ready (`!io.opsReady(0)`)

**Bound**: 8 cycles after trigger activation.

## 3. Waveform Information

- **Full Waveform Path**: `verilog/extra_bench/ibuf/iqc.entry0_progress.fst`
- **Duration**: 12 cycles (0 ns – 120 ns)
- **Failure Time**: `entry0_progress` transitions from 1 → 0 at **110 ns** (10th cycle after trigger)

### Critical Time Points

| Time (ns) | valid[2:0] | opsReady[2:0] | exeReady[1:0] | issue0_0 | issue0_1 | issue1_0 | qAge[2:0] | exeReadyTimer[2:0] | Event |
|-----------|------------|---------------|---------------|----------|----------|----------|-----------|-------------------|-------|
| 20 (t=2)  | 011        | 001           | 00            | 0        | 0        | 0        | 110       | 000               | **Trigger fires**: valid(0)=1, opsReady(0)=1 |
| 30 (t=3)  | 111        | 001           | 00            | 0        | 0        | 0        | 110       | 001               | No exec units ready |
| 40 (t=4)  | 011        | 001           | 00            | 0        | 0        | 0        | 110       | 010               | No exec units ready; flush(2)=1 |
| 50 (t=5)  | 011        | 001           | 00            | 0        | 0        | 0        | 110       | 011               | No exec units ready |
| 60 (t=6)  | 011        | 001           | 00            | 0        | 0        | 0        | 110       | 100               | No exec units ready |
| 70 (t=7)  | 111        | 001           | 00            | 0        | 0        | 0        | 110       | 101               | No exec units ready |
| 80 (t=8)  | 111        | 011           | **01**        | **0**    | **1**    | 0        | 110       | 110               | exeReady(0)=1 but entry 1 (older) wins arbitration |
| 90 (t=9)  | 101        | 001           | 00            | 0        | 0        | 0        | 011       | 000               | Entry 1 invalidated; no exec units ready |
| 100 (t=10)| 001        | 001           | 00            | 0        | 0        | 0        | 011       | 001               | No exec units ready |
| **110 (t=11)** | 111    | 001           | 00            | 0        | 0        | 0        | 111       | 010               | **Assertion fails** — 9th consecutive cycle without success |

## 4. Root Cause Analysis

### Error Classification: **Setup Issue** (`setup_error`)

The root cause is an **insufficiently constrained environment** — the fairness assumption on `exeReady` is too weak to prevent indefinite starvation of entry 0.

### Buggy Code Location

**File**: `iqc.scala`, lines 116–124

```scala
// Fairness constraint: execution units eventually become ready
val exeReadyTimer = RegInit(0.U(3.W))
when(io.exeReady.orR) {
    exeReadyTimer := 0.U
} .otherwise {
    exeReadyTimer := exeReadyTimer + 1.U
}
assume(exeReadyTimer < 7.U, "exeReady_fairness")
```

### Description of the Problem

The fairness assumption `assume(exeReadyTimer < 7.U)` only requires that **at least one** of the two execution units (`exeReady.orR`) becomes ready every 7 cycles. This is insufficient because:

1. **Shared single exec unit bottleneck**: When both entry 0 and entry 1 (older) are valid and ready, they compete for exec unit 0. Entry 1 always wins due to the age-based priority (`qAge(0)=0` means entry 1 is older).

2. **No fairness per exec unit**: The assumption allows the solver to set `exeReady[1:0]=00` for 6 consecutive cycles, then pulse `exeReady[0]=1` for a single cycle — but that single cycle may be consumed by the older entry (entry 1), leaving entry 0 stuck.

3. **exeReadyTimer resets spuriously**: The timer resets whenever `exeReady.orR=1` (t=80), even though `exeReady[0]=1` was consumed by entry 1 and didn't help entry 0. After this reset, the solver keeps `exeReady=00` for the remaining cycles.

### Evidence from Waveform

**Critical sequence in the counterexample**:

1. **t=20**: Trigger fires — `valid(0)=1, opsReady(0)=1`
2. **t=20–70**: `exeReady=00` for **6 consecutive cycles** (timer counts 0→5). Entry 0 cannot issue.
3. **t=80**: `exeReady=01` — exec unit 0 becomes ready. However, `opsReady(1)=1` and entry 1 is older (`qAge(0)=0`). The arbitration correctly selects `issue0_1=1` (entry 1 issues), NOT `issue0_0`. Entry 0 remains stuck.
4. **t=80**: `exeReady.orR=1` resets `exeReadyTimer` to 0 (it was at 6, about to trigger the fairness bound).
5. **t=90–110**: `exeReady=00` for **3 more cycles**. Entry 0 has now been waiting **9 consecutive cycles** without issuing.
6. **t=110**: Assertion fails — the 8-cycle bound is exceeded.

### Why the Assertion Fails

The `astRelaxedLiveness` property with bound 8 checks that entry 0 issues within 8 cycles of the trigger. However, the environment:

- Delays `exeReady(0)` for 6 cycles (t=20–70)
- When `exeReady(0)` finally appears at t=80, it is consumed by entry 1 (older)
- Never provides `exeReady(0)` or `exeReady(1)` again within the remaining 3 cycles

This is a **valid counterexample from the solver's perspective** because the fairness constraint is satisfied (the timer never reaches 7), but it is **unrealistic hardware behavior** — in a real design, each execution unit would become ready independently and periodically.

### Recommended Fix

There are three possible approaches:

**Option A (Recommended — Fix the Environment Constraints)**: Strengthen the fairness assumption to require each individual execution unit to be ready periodically, not just their OR. For example:
```scala
// Require both execution units to be ready periodically
val exeReady0Timer = RegInit(0.U(3.W))
when(io.exeReady(0)) { exeReady0Timer := 0.U }
.otherwise { exeReady0Timer := exeReady0Timer + 1.U }
assume(exeReady0Timer < 7.U, "exeReady0_fairness")

val exeReady1Timer = RegInit(0.U(3.W))
when(io.exeReady(1)) { exeReady1Timer := 0.U }
.otherwise { exeReady1Timer := exeReady1Timer + 1.U }
assume(exeReady1Timer < 7.U, "exeReady1_fairness")
```

**Option B**: Increase the liveness bound from 8 to a larger value (e.g., 12–16) to account for worst-case arbitration delays when a younger entry must wait for an older entry to issue first.

**Option C**: Strengthen the fairness to guarantee that if `valid(0) && opsReady(0)` and the target exec units are ready but consumed by older entries, the younger entry eventually gets a turn.
