# Counterexample Analysis Report

## 1. Verification Environment

- **Top Module**: `b04` (in package `llmverify`)
- **Source File**: `b04.scala`
- **Description**: The ITC'99 b04 benchmark — a state machine that computes running min, max, and average of a signed 8-bit data stream. It has 3 states (sA, sB, sC), with state sC being the steady operating state where data is processed.
- **Key Components**:
  - State register `stato` (2-bit, states sA/sB/sC)
  - Registers `REG1`, `REG2`, `REG3`, `REG4` — forming a 4-stage pipeline
  - Registers `RMAX`, `RMIN` — running min/max of inputs
  - Register `RLAST` — last input value
  - Register `DATA_OUT` — output of average computation

## 2. Violated Assertion

- **Assertion Name**: `pipeline_reg1_to_reg2` (from waveform filename `b04.pipeline_reg1_to_reg2.fst`)
- **Code Snippet** (file `b04.scala`, line 133):

```scala
// Safety 4: Pipeline shift -- REG1 shifts into REG2 each cycle in sC
assertNextStepWhen(stato === sC, REG2 === REG1, "pipeline_reg1_to_reg2")
```

- **Property**: When `stato === sC` is true on cycle N, then on the **next** cycle (N+1), the assertion checks that `REG2 === REG1`.
- **File Location**: `b04.scala`, line 132-133

## 3. Waveform Information

- **Waveform File**: `verilog/extra_bench/itc99_b04/b04.pipeline_reg1_to_reg2.fst`
- **Key Time Points** (all times in nanoseconds):

| Time (ns) | clock | stato [1:0] | io_DATA_IN [7:0] | REG1 [7:0] | REG2 [7:0] | pipeline_reg1_to_reg2 |
|-----------|-------|-------------|------------------|------------|------------|----------------------|
| 20        | 1     | 10 (sC)     | 0x02             | 0x00       | 0x00       | 1 (active)           |
| 30        | 1     | 10 (sC)     | 0x02             | 0x02       | 0x00       | 0 (failing!)         |

- **Failure Point**: At time 30 ns (rising edge, 2nd cycle in state sC):
  - `REG2` = 0x00 (still the old value from reset)
  - `REG1` = 0x02 (just updated from `io.DATA_IN`)
  - `REG2 !== REG1` → assertion `pipeline_reg1_to_reg2` deasserts to 0

## 4. Root Cause Analysis

### Classification: **Incorrect Assertion** (`assertion_error`)

### Root Cause

The assertion `pipeline_reg1_to_reg2` has a **semantic error**: it compares `REG2` against the **current** value of `REG1`, but the pipeline shift means `REG2` receives the **previous** value of `REG1`.

### The Bug in Detail

In the DUT's `sC` state (lines 97-100 of `b04.scala`):

```scala
REG4 := REG3
REG3 := REG2
REG2 := REG1
REG1 := io.DATA_IN
```

This is a parallel update in Chisel (all updates happen simultaneously on the clock edge):
- `REG1` ← `io.DATA_IN` (new input value)
- `REG2` ← old value of `REG1` (previous DATA_IN)

So after the clock edge at time 20 (first cycle in sC):
- `REG1` = 0x02 (= io.DATA_IN at time 20)
- `REG2` = 0x00 (= old REG1 from reset)

Then at time 30 (next clock edge), the assertion `assertNextStepWhen(stato === sC, REG2 === REG1, ...)` fires because:
- Cond (`stato === sC`) was true at time 20 → checks next step at time 30
- At time 30: `REG2` = 0x00 (carried forward from time 20 update), `REG1` = 0x02 (= io.DATA_IN at time 30)
- `REG2 (0x00) !== REG1 (0x02)` → **FAIL**

The DUT logic is **correct** — the pipeline shift properly moves data through `REG1 → REG2 → REG3 → REG4` each cycle. The assertion incorrectly expects `REG2` to equal the **current** `REG1`, but the pipeline semantics dictate that `REG2` equals the **previous** cycle's `REG1`.

### Correct Fix

The assertion should compare `REG2` against the **previous** value of `REG1` (i.e., the value REG1 had before being updated with io.DATA_IN):

```scala
val prev_REG1 = RegNext(REG1)
assertNextStepWhen(stato === sC, REG2 === prev_REG1, "pipeline_reg1_to_reg2")
```

Or equivalently, using a simpler `when` + `fvAssert`:

```scala
when(stato === sC) {
  fvAssert(RegNext(REG1) === REG2, "pipeline_reg1_to_reg2")
}
```

### Evidence Summary

| Signal | Time 20 (rising edge, cond true) | Time 30 (next step, assertion check) |
|--------|----------------------------------|--------------------------------------|
| stato  | sC (10)                          | sC (10)                              |
| io.DATA_IN | 0x02                         | 0x02                                 |
| REG1   | 0x00 (before update) → gets 0x02 | 0x02 (was updated at time 20) → gets 0x02 |
| REG2   | 0x00 (before update) → gets 0x00 | 0x00 (was updated with old REG1=0x00 at time 20) |
| **Assertion** | cond true → enables check | REG2(0x00) !== REG1(0x02) → **FAIL** |

The DUT is correct. The assertion incorrectly expects `REG2` to equal the freshly-updated `REG1`, when it should instead equal the **previous** value of `REG1`.
