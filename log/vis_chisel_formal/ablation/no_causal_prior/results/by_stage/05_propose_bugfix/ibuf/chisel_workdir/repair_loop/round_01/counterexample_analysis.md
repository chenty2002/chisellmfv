# Counterexample Analysis Report: `iqc.issue0_one_hot0`

## 1. Verification Environment

- **Top Module**: `iqc` (Instruction Queue with 3 slots, 2 execution units)
- **Design Hierarchy**:
  - `iqc` (DUT) instantiates formal verification assertions
  - 3-entry instruction queue (slots 0, 1, 2)
  - 2 dispatch ports (`iqLoads[1:0]`)
  - 2 execution units (`exeReady[1:0]`)
  - Age tracking via `qAge[2:0]` register
- **Key Components**:
  - `valid[2:0]` register tracking which slots hold valid instructions
  - `qAge[2:0]` register encoding relative ages: `qAge[0]` = slot0 older than slot1, `qAge[1]` = slot0 older than slot2, `qAge[2]` = slot1 older than slot2
  - Issue arbitration logic (`issue0_0`, `issue0_1`, `issue0_2`) selects which slot issues to execution unit 0
  - Load assignment logic routes incoming instructions to free slots
- **Verification Objective**: Ensure `io.issue0` is one-hot (at most one slot issues to execution unit 0 per cycle)

## 2. Violated Assertion

- **Assertion Name**: `issue0_one_hot0` (from waveform filename `iqc.issue0_one_hot0.fst`)
- **Code Location**: `iqc.scala`, line 73: `assertOneHot0(io.issue0, "issue0_one_hot0")`
- **Code Snippet**:
  ```scala
  // ----- Safety: Issue Mutex -----
  // At most one instruction issued per execution unit per cycle
  assertOneHot0(io.issue0, "issue0_one_hot0")
  ```
- **Property Description**: The signal `io.issue0[2:0]` must have at most one bit set at any time (i.e., at most one instruction can be issued to execution unit 0 per cycle). A value of 0 is acceptable (no issue), and a value with exactly one bit set is also acceptable. Two or more bits set simultaneously violates the assertion.

## 3. Waveform Information

- **Waveform File**: `verilog/extra_bench/ibuf/iqc.issue0_one_hot0.fst`
- **Waveform Duration**: 3 cycles (0 ns to 30 ns, 10 ns per cycle)
- **Key Time Points**:
  | Time (ns) | Cycle | Issue0 | Valid | qAge | exeReady | opsReady | Flush | iqLoads |
  |-----------|-------|--------|-------|------|----------|----------|-------|---------|
  | 0         | 0     | 000    | 000   | 000  | 00       | 000      | 010   | 11      |
  | 10        | 1     | 001    | 001   | 011  | 01       | 001      | 000   | 11      |
  | 20        | 2     | **110**| 110   | 100  | 01       | 111      | 000   | 11      |

- **Failure Point**: At time 20 ns, `io_issue0 = 3'b110` (bits 1 and 2 both set), violating one-hot.
  - `issue0_1 = 1`, `issue0_2 = 1` (both firing simultaneously)
  - `issue0_one_hot0 = 0` (assertion signal goes low)

## 4. Root Cause Analysis

### Bug Location
- **File**: `iqc.scala`
- **Line**: 51
- **Signal**: `issue0_2` — the issue-select signal for slot 2 (instruction queue entry 2) to execution unit 0

### Bug Description

The arbitration logic in `issue0_2` contains an inverted age comparison. The condition is:

```scala
val issue0_2 = io.exeReady(0) & io.opsReady(2) & valid(2) &
    (~qAge(1) | ~io.opsReady(0)) & (qAge(2) | ~io.opsReady(1))   // BUG: qAge(2) should be ~qAge(2)
```

The term `(qAge(2) | ~io.opsReady(1))` uses `qAge(2)` in the **same polarity** as in `issue0_1`. The two signals are intended to be mutually exclusive:

- **issue0_1** (line 50): `(qAge(2) | ~io.opsReady(2))` — Slot 1 can issue over slot 2 when slot 1 is older (`qAge(2)=1`) OR slot 2's operands are not ready.
- **issue0_2** (line 51): `(qAge(2) | ~io.opsReady(1))` — Bug: Slot 2 can issue when `qAge(2)=1` (slot 1 is older than slot 2), which is backwards. **Should be `(~qAge(2) | ~io.opsReady(1))`**: Slot 2 can issue over slot 1 when slot 2 is older (`~qAge(2)=1`, i.e., `qAge(2)=0`) OR slot 1's operands are not ready.

### Evidence from Waveform

At time 20 ns in cycle 2:
1. `valid = 3'b110` — Slots 1 and 2 are valid (slot 0 is invalid)
2. `qAge = 3'b100` — `qAge[2]=1` means slot 1 is older than slot 2
3. `opsReady = 3'b111` — All three slots have ready operands
4. `exeReady = 2'b01` — Only execution unit 0 is ready

Evaluating the issue conditions:
- **issue0_0**: `exeReady(0)` & `opsReady(0)` & `valid(0)=0` → **0** (valid bit is 0)
- **issue0_1**: `exeReady(0)=1` & `opsReady(1)=1` & `valid(1)=1` & `(~qAge(0)=1 | ~opsReady(0)=0)=1` & `(qAge(2)=1 | ~opsReady(2)=0)=1` → **1** (correctly fires — slot 1 is oldest and should issue)
- **issue0_2**: `exeReady(0)=1` & `opsReady(2)=1` & `valid(2)=1` & `(~qAge(1)=1 | ~opsReady(0)=0)=1` & `(qAge(2)=1 | ~opsReady(1)=0)=1` → **1** (incorrectly fires — should be blocked because slot 1 is older)

Both `issue0_1` and `issue0_2` evaluate to `1`, making `io.issue0 = 3'b110`, which violates the one-hot assertion.

### Why the Bug Causes the Assertion to Fail

The age-comparison `qAge(2)` encodes "slot 1 is older than slot 2." When this bit is active:
- `issue0_1` correctly allows slot 1 to issue (slot 1 is older and has priority).
- `issue0_2` incorrectly also allows slot 2 to issue because `(qAge(2) | ...)` evaluates to `1` when `qAge(2)=1`.

The correct behavior is: when slot 1 is older than slot 2, slot 2 should **defer** to slot 1. Therefore `issue0_2` should use `~qAge(2)` (slot 2 is older than or equal in age to slot 1) instead of `qAge(2)` (slot 1 is older than slot 2).

### Error Classification

This is a **Bug in the Original Design (DUT bug)**. The assertion itself is correctly specified (one-hot is a standard safety property for issue-select signals), and the top-module test harness configuration is valid. The design's issue-2 arbitration logic has an incorrect age priority condition.

### Corrected Code

Line 51 of `iqc.scala` should be changed from:
```scala
val issue0_2 = io.exeReady(0) & io.opsReady(2) & valid(2) &
    (~qAge(1) | ~io.opsReady(0)) & (qAge(2) | ~io.opsReady(1))
```
to:
```scala
val issue0_2 = io.exeReady(0) & io.opsReady(2) & valid(2) &
    (~qAge(1) | ~io.opsReady(0)) & (~qAge(2) | ~io.opsReady(1))
```

The fix flips `qAge(2)` to `~qAge(2)` in the condition that arbitrates between slot 2 and slot 1. With this fix, when `qAge(2)=1` (slot 1 older than slot 2) and both slots are ready, only `issue0_1` will fire, and `issue0_2` will be blocked — preserving one-hot behavior.
