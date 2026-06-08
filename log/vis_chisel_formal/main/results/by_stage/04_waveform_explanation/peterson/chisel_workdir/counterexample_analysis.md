# Counterexample Analysis Report

## 1. Verification Environment

- **Top Module**: `peterson` (peterson.scala:12)
- **Key Components**:
  - `self` register: captures `io.select` input each cycle, determines which process the state machine executes
  - `pc(0)`, `pc(1)`: program counter registers for two processes implementing Peterson's mutual exclusion algorithm
  - `interested(0)`, `interested(1)`: flag registers indicating process interest in entering critical section
  - `turn`: shared turn register for tie-breaking
  - Eight formal assertions: mutual_exclusion, p0_enters_cs_on_condition, p1_enters_cs_on_condition, interested_invariant_p0/p1, interested_cleared_p0/p1, and four liveness assertions
- **Design Under Test**: A two-process Peterson's mutual exclusion algorithm implemented as a hardware state machine with six states (L0–L5) that runs only the process selected by `self` each cycle

## 2. Violated Assertion

- **Assertion Name**: `p0_enters_cs_on_condition`
- **Source Code** (peterson.scala, lines 79–84):
  ```scala
  assertNextStepWhen(
    pc(0) === Loc.L3 && io.select === 0.U && (!interested(1) || turn === 0.U),
    pc(0) === Loc.L4,
    "p0_enters_cs_on_condition"
  )
  ```
- **Intended Property**: When process 0 is waiting at location L3 (entry protocol) AND io_select selects process 0 AND either process 1 is not interested or it is process 0's turn, then on the **next cycle**, process 0 MUST enter the critical section (L4).
- **Generated Verilog** (peterson.sv, lines 62–63):
  ```verilog
  p0_enters_cs_on_condition:
      assert property (@(posedge clock) disable iff (~hasBeenReset) _GEN);
  ```
  where `_GEN = pc_0 == 3'h4` (line 58)

## 3. Waveform Information

- **Waveform File**: `verilog/extra_bench/peterson/peterson.p0_enters_cs_on_condition.fst`
- **Time Range**: 0 ns → 10 ns (1 cycle)
- **Key Signals at Failure Point (time 0, posedge clock)**:

| Signal | Value | Meaning |
|--------|-------|---------|
| `pc_0 [2:0]` | `000` (L0) | Process 0 at initial/idle state |
| `pc_1 [2:0]` | `000` (L0) | Process 1 at initial/idle state |
| `io_select` | `1` | Selects process 1 |
| `io_pause` | `1` | Pause active, state machine frozen |
| `interested_0` | `0` | Process 0 not interested |
| `interested_1` | `0` | Process 1 not interested |
| `turn` | `0` | Turn indicator |
| `self` | `0` | Registered select value (stale) |
| `_GEN` | `0` | pc_0 == 3'h4 is FALSE |
| `hasBeenReset` | `1` | Assertion is enabled |
| **`p0_enters_cs_on_condition`** | **`1`** | **Assertion is being evaluated and FAILS** |

## 4. Root Cause Analysis

### Incorrect Assertion Compilation (Assertion Error)

**Root Cause**: The `assertNextStepWhen` Chisel primitive was incorrectly compiled to Verilog SVA. The generated assertion in Verilog is:

```verilog
assert property (@(posedge clock) disable iff (~hasBeenReset) pc_0 == 3'h4);
```

This checks that `pc_0` **is always equal to Loc.L4 (critical section)** — a property that is trivially false because the process starts at L0 and only reaches L4 under specific conditions. The compiled assertion is **missing**:

1. **The antecedent condition**: `pc(0) === Loc.L3 && io.select === 0.U && (!interested(1) || turn === 0.U)` — the condition that should trigger the check
2. **The next-cycle timing**: the `|=>` operator that would defer the consequent check to the following clock cycle

The correct SVA should have been:
```verilog
assert property (@(posedge clock) disable iff (~hasBeenReset)
    (pc_0 == 3'h3 && !io_select && (!interested_1 || !turn)) |=> pc_0 == 3'h4);
```

### Evidence from Generated Verilog

In `peterson.sv`, the following signals are defined but the antecedent is completely absent from the assertion:

- `_GEN_1 = pc_0 == 3'h3` (would be `pc(0) === Loc.L3`) — **defined at line 61 but never used in the assertion**
- `_GEN = pc_0 == 3'h4` (would be `pc(0) === Loc.L4`) — **used alone at line 63 as the entire property**

The assertion `pc_0 == 3'h4` at all times is fundamentally wrong — the waveform shows `pc_0 = 000 (L0)` at the posedge clock, which immediately fails the assertion because L0 ≠ L4. The signal `_GEN` is 0 (false), causing the assertion to fail.

### Error Classification

This is an **assertion_error** (incorrect assertion). The assertion as it exists in the generated Verilog does not match the intended property in the Chisel source code. The antecedent and timing conditions were dropped during compilation, leaving a trivially false assertion that the formal tool correctly finds a counterexample for.
