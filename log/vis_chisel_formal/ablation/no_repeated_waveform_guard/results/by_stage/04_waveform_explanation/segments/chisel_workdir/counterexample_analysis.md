# Counterexample Analysis Report: `sg1.A_i_to_B`

## 1. Verification Environment

- **Top module**: `sg1` (defined in `sg1.scala`)
- **Key components**:
  - `sg1` — a state machine with 5 states (A, B, C, D, E) that transitions based on input `io_i`
  - `ResetCounter` — a black-box module that tracks time since reset and provides `notChaos` signal
  - `Formal` trait — provides formal verification assertion helpers
- **Design under test**: A simple 5-state Mealy-like state machine where:
  - State A: stays in A on `io_i=0`, goes to B on `io_i=1`
  - State B: goes to C on `io_i=1`, goes to D on `io_i=0`
  - State C: unconditionally goes to B
  - State D: unconditionally goes to E
  - State E: sink state (self-loop)
  - Output `io_o` is true iff in state A

## 2. Violated Assertion

- **Full assertion name**: `A_i_to_B`
- **Assertion call in source** (`sg1.scala`, line 38):
  ```scala
  assertNextStepWhen(state === States.A && io.i, state === States.B, "A_i_to_B")
  ```
- **Generated Verilog** (`generated/sg1.sv`, line ~68):
  ```verilog
  A_i_to_B: assert property (@(posedge clock) disable iff (~hasBeenReset) _GEN);
  ```
  where `_GEN = state == 3'h1` (i.e., `state === States.B`).

- **Natural language description**: The intended property is: *"When the state machine is in state A and the input `io_i` is asserted, then in the **next cycle** the state should transition to state B."*

## 3. Waveform Information

- **Waveform file**: `verilog/extra_bench/segments/sg1.A_i_to_B.fst`
- **Time range**: 0 ns to 10 ns (1 clock cycle)
- **Key time point**: 0 ns (posedge clock)
- **Critical signal values at time 0**:

| Signal | Value | Interpretation |
|--------|-------|---------------|
| `sg1.clock` | 1 | Rising clock edge |
| `sg1.state [2:0]` | `000` (3'h0) | State is **A** |
| `sg1.io_i` | 0 | Input is **not asserted** |
| `sg1._GEN` | 0 | `state == B` is **false** |
| `sg1.hasBeenReset` | 1 | Assertion is **enabled** (not in reset hold-off) |
| `sg1.A_i_to_B` | 1 | Assertion status signal |

The counterexample shows that **at the very first cycle after reset**, with `state = A` and `io_i = 0`, the assertion `A_i_to_B` fails because it checks `state == B`, which is false when the machine is in state A.

## 4. Root Cause Analysis

### Root Cause Category: **Incorrect Assertion** (`assertion_error`)

### The Bug

The bug is in the **assertion generation mechanism** used by `assertNextStepWhen`. The implementation in `chiselFv/Formal.scala` (lines ~200-209) is:

```scala
def assertAfterNStepWhen(cond: Bool, n: Int, asert: Bool, msg: String = "")
                        (implicit sourceInfo: SourceInfo): Unit = {
  when(delayedBool(cond && notChaos, n, sticky = false)) {
    fvAssert(asert, msg)
  }
}

def assertNextStepWhen(cond: Bool, asert: Bool, msg: String = "")
                      (implicit sourceInfo: SourceInfo): Unit = {
  assertAfterNStepWhen(cond, 1, asert, msg)
}
```

where `fvAssert` (line ~158) is:
```scala
def fvAssert(cond: Bool, msg: String = "")
            (implicit sourceInfo: SourceInfo): Unit = {
  when(notChaos) {
    AssertProperty(cond, msg)
  }
}
```

For `A_i_to_B`, this expands to:
```scala
when(delayedBool(  // delay by 1 cycle
  (state === States.A && io.i) && notChaos,
  1, sticky = false
)) {
  when(notChaos) {
    AssertProperty(state === States.B, "A_i_to_B")
  }
}
```

The intention is:
1. **`delayedBool(cond && notChaos, 1)`** creates a register that captures the condition `(state === A && io.i) && notChaos`, and outputs it one cycle later.
2. **`when(delayedBool(...)) { ... }`** only evaluates the assertion body when the delayed condition is true (i.e., when `cond` was true one cycle ago).
3. **`fvAssert(asert, msg)`** generates a SystemVerilog `assert property`.

### Why it fails

The problem is that **`when` blocks in Chisel do NOT guard SystemVerilog concurrent assertions**. In the generated Verilog, the `AssertProperty` call produces a standalone `assert property` statement that sits **outside** the `always @(posedge clock)` block. The `when` block's guard condition is lost during compilation because `assert property` is a **declarative concurrent assertion**, not a procedural statement.

**Evidence from the generated Verilog:**

In `generated/sg1.sv`, the assertions are placed outside the `always` block:
```verilog
A_i_to_B: assert property (@(posedge clock) disable iff (~hasBeenReset) _GEN);
```
```verilog
always @(posedge clock) begin
  // ... procedural assignments only ...
end
```

The `assert property` line has NO guard from the `when(delayedBool(...))` condition. It just unconditionally checks `state == B` at every clock cycle. The `disable iff (~hasBeenReset)` only preserves the `notChaos` condition, but the **critical temporal precondition** (`state === A && io.i` was true last cycle) is completely missing.

### Why the assertion fails in the counterexample

At time 0 ns (the only cycle in the 1-cycle counterexample):
- The DUT has just come out of reset (`hasBeenReset = 1`)
- The state machine is in state A (`state = 3'h0`, its reset value)
- Input `io_i = 0`
- The generated assertion `A_i_to_B` checks: `state == B` → **false** (state is A, not B)
- Therefore the assertion **fails immediately**

This failure is a **false negative** — the assertion fails not because the DUT has a bug, but because the assertion itself is incorrectly generated. The DUT is correctly in state A after reset, and there is no requirement that it should be in state B at this point. The assertion should only fire when the precondition `state === A && io_i` was true in the *previous* cycle.

### Fix needed

The `assertNextStepWhen` / `assertAfterNStepWhen` mechanism needs to generate a proper **temporal implication** using the SystemVerilog `|->` (overlapping implication) or `|=>` (non-overlapping implication) operator, e.g.:

```verilog
A_i_to_B: assert property (@(posedge clock) disable iff (~hasBeenReset)
  (state == A && io_i) |=> state == B);
```

This would correctly express: *"If state is A and io_i is high, then in the NEXT cycle state must be B."*

The current approach of wrapping `fvAssert`/`AssertProperty` in a `when` block does not work because `when` blocks only generate procedural code inside `always` blocks, while `AssertProperty` generates standalone concurrent assertion statements that are immune to `when` guards.
