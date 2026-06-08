# Counterexample Analysis Report: `proc0_cs_exit_progress`

## 1. Verification Environment

- **Top Module**: `dekker` (Dekker's mutual exclusion algorithm)
- **Source File**: `dekker.scala`
- **Key Components**:
  - **Registers**: `c` (2-bit Vec for contention flags), `turn` (1-bit turn indicator), `self` (1-bit selected process), `pc` (2 × 3-bit program counters)
  - **Inputs**: `io.select` (selects active process), `io.pause` (pauses state machine)
  - **Outputs**: `io.c0`, `io.c1`, `io.turn`, `io.self`, `io.pc0`, `io.pc1`
- **Design**: Implements Dekker's algorithm where the selected process (determined by `self`) executes its state machine across states L0–L6. L5 is the critical section, and L6 is the exit state.

## 2. Violated Assertion

- **Full Assertion Name**: `proc0_cs_exit_progress`
- **Code Snippet** (line 125 of `dekker.scala`):
  ```scala
  assertNextStepWhen(self === 0.U && pc(0) === L5 && !io.pause, pc(0) === L6, "proc0_cs_exit_progress")
  ```
- **Natural Language Property**: When process 0 (selected), process 0 is in the critical section (`pc(0) == L5`), and the machine is not paused, then in the very next cycle process 0 must advance to the exit state (`pc(0) == L6`).
- **File Location**: `dekker.scala`, line 125

## 3. Waveform Information

- **Full Waveform Path**: `verilog/extra_bench/dekker/dekker.proc0_cs_exit_progress.fst`
- **Duration**: 1 cycle (10 ns)
- **Time Range**: 0 ns → 10 ns

### Key Signal Values at Time 0 (posedge clock after reset):

| Signal | Value | Meaning |
|--------|-------|---------|
| `dekker.io_select` | 1 | Select process 1 |
| `dekker.io_pause` | 1 | State machine is paused |
| `dekker.self` | 1 | Active process is 1 |
| `dekker.c_0` | 1 | Process 0 not contending |
| `dekker.c_1` | 1 | Process 1 not contending |
| `dekker.turn` | 0 | Turn belongs to process 0 |
| `dekker.pc_0 [2:0]` | 000 (L0) | Process 0 at idle |
| `dekker.pc_1 [2:0]` | 000 (L0) | Process 1 at idle |
| `dekker.io_pc0 [2:0]` | 000 (L0) | Output matching pc_0 |
| `dekker.io_pc1 [2:0]` | 000 (L0) | Output matching pc_1 |
| `dekker.proc0_cs_exit_progress` | 1 | Assertion check wire (1 = passing) |
| `dekker.clock` | 1 | Clock high |
| `dekker.reset` | 0 | Reset not asserted |

### Key Signal Values at Time 10 (negedge clock):

All signals remain identical to time 0 except `dekker.clock` which transitions to 0.

**Observation**: The waveform shows only a single clock cycle with all signals at their reset/initial values and no transitions on any signal.

## 4. Root Cause Analysis

### Root Cause Category: **Setup Error (setup_error)**

### Detailed Analysis:

#### 4.1 The Counterexample Does Not Show a Direct Violation

The assertion `proc0_cs_exit_progress` checks:
```
assertNextStepWhen(self === 0.U && pc(0) === L5 && !io.pause, pc(0) === L6)
```

In the entire 1-cycle trace:
- `self = 1` (not 0), so `self === 0.U` is **false**
- `pc(0) = L0` (not L5), so `pc(0) === L5` is **false**
- `io_pause = 1`, so `!io.pause` is **false**

The antecedent `self === 0.U && pc(0) === L5 && !io.pause` is **false** throughout the trace, making the implication vacuously true. The assertion signal `proc0_cs_exit_progress` remains **1** (passing) at all observed time points.

#### 4.2 The Trace Shows an Unreachable Antecedent Condition

With `io_select = 1` (constant throughout the trace):
- `self = RegInit(io.select) = 1` (since `io_select = 1` at reset)
- `self` is updated to `io_select` every cycle, remaining at 1
- `self === 0.U` can **never** be true

Additionally:
- `pc(0) = L0` (initial/reset state), and process 0 never advances because the state machine operates on `pc(self)` where `self = 1`, meaning only `pc(1)` is evaluated
- `io_pause = 1` means the state machine never advances past L0 for any process

#### 4.3 The Formal Tool Cannot Reach a Failing State

For this assertion to fail, the formal tool would need to:
1. Set `io_select = 0` to make `self = 0`
2. Advance process 0 through L0→L1→L2→L5 (taking at least 3 cycles with `io_pause = 0`)
3. Then check that `pc(0) = L6` in the next cycle

However, the trace shows all inputs constant (`io_select = 1`, `io_pause = 1`), preventing the assertion from ever being meaningfully exercised. The 1-cycle trace with no signal transitions suggests the formal verification environment may have constraints or assumptions that prevent the tool from exploring the state space where the antecedent could be true.

#### 4.4 The State Machine Logic is Correct

Examining the state machine at L5 (lines 75-80 of `dekker.scala`):
```scala
is(L5) {
  when(!io.pause) {
    c(self) := true.B
    pc(self) := L6
  }
}
```

When `self = 0`, `pc(0) = L5`, and `!io.pause`:
- `pc(0) := L6` is correctly assigned via non-blocking update
- At the next clock cycle, `pc(0)` = L6 as expected
- The assertion's consequent `pc(0) === L6` would be satisfied

There is no bug in the state machine logic for critical section exit.

#### 4.5 Potential `assertNextStepWhen` Timing Issue

The `assertNextStepWhen` macro from ChiselFv generates an assertion checking that when the antecedent (`cond`) is true at cycle N, the consequent (`target`) is true at cycle N+1. If the generated Verilog assertion uses immediate implication (`|->`) instead of next-cycle implication (`|=>`), the assertion would check `target` in the **same** cycle as `cond`. At the cycle where `self=0`, `pc(0)=L5`, `!io_pause`:
- `pc(0)` still holds the value L5 (register update hasn't taken effect yet)
- `pc(0) === L6` evaluates to **false**
- The assertion would **fail** even though the design is correct

However, this timing hypothesis cannot be fully verified without examining the generated Verilog assertion code.

### Conclusion

The root cause is a **setup error**: the verification environment constrains `io_select = 1` and `io_pause = 1` in a way that makes the assertion's antecedent unreachable. The formal tool cannot meaningfully check the property because the test harness/prelude prevents the state space from reaching states where `self = 0` and `pc(0) = L5` simultaneously. The design logic itself is correct for Dekker's algorithm.

Alternatively, there may be a timing issue in how `assertNextStepWhen` generates the assertion (checking same-cycle instead of next-cycle), which would require fixing the assertion macro implementation.

### Recommended Fix

1. **Remove input constraints**: Ensure the formal environment allows `io_select` to be both 0 and 1, and `io_pause` to be both 0 and 1 freely
2. **Verify `assertNextStepWhen` implementation**: Check that the generated Verilog uses next-cycle implication (`|=>` in SVA) rather than immediate implication (`|->`)
3. **Test with both processes**: Verify `proc1_cs_exit_progress` similarly after fixing the environment
