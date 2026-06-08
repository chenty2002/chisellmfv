# Counterexample Analysis: `minMax.last_stores_previous_in`

## 1. Verification Environment

- **Top Module**: `minMax` (from `minMax.scala`, package `llmverify`)
- **Generated Verilog**: The design compiles the `minMax` Chisel module into a Verilog formal model
- **Key Components**:
  - `min` register (128-bit, initialized to all-ones `0xFFF...F`)
  - `max` register (128-bit, initialized to 0)
  - `last` register (128-bit, initialized to 0)
  - `sup` = combinational max of `io.in` and `max`
  - `inf` = combinational min of `io.in` and `min`
  - `avg` = lower 128 bits of `sup + inf`
  - `REG` = `RegNext(io.in)` (128-bit register capturing the previous cycle's input)
  - `initDone` = `RegNext(true.B, false.B)` (initialization guard)
- **Formal Library**: ChiselFv (`chisel3.util._`, `chiselFv._`)

## 2. Violated Assertion

- **Assertion Name (from waveform filename)**: `last_stores_previous_in`
- **Waveform File**: `minMax.last_stores_previous_in.fst`
- **Source Location**: `minMax.scala`, line 87 (the `assertNextStepWhen` call)

### Code Snippet

```scala
// Line 81-87 in minMax.scala
val initDone = RegNext(true.B, false.B)

assertNextStepWhen(
    initDone && io.enable && !io.reset && !io.clear,
    last === RegNext(io.in),
    "last_stores_previous_in")
```

### Natural Language Description

The property states: **When the module has completed initialization (`initDone` is true), the enable signal is high, and neither reset nor clear is asserted, then the `last` register should equal the value of `io.in` from the previous cycle (captured by `RegNext(io.in)`).**

In other words: after a rising clock edge where `enable` is asserted (and `reset` and `clear` are low), the `last` register should store the `io.in` value from the cycle *before* that clock edge.

## 3. Waveform Information

- **Waveform File**: `verilog/extra_bench/param_minmax/minMax.last_stores_previous_in.fst`
- **Duration**: 1 cycle (10 ns)
- **Time Range**: 0 ns → 10 ns

### Key Signal Observations

| Signal | Time 0 | Time 5 | Time 10 |
|--------|--------|--------|---------|
| `clock` | 1 | 0 | 0 |
| `last_stores_previous_in` (assertion) | **1** | **1** | **1** |
| `io_enable` | 0 | 0 | 0 |
| `io_reset` | 0 | 0 | 0 |
| `io_clear` | 0 | 0 | 0 |
| `io_in [127:0]` | 0x0 | — | 0x0 |
| `last [127:0]` | 0x0 | — | 0x0 |
| `REG [127:0]` (= RegNext(io.in)) | **0x1** | — | **0x1** |
| `_GEN` | 1 | 1 | — |
| `hasBeenReset` | 1 | 1 | 1 |
| `_sup_T` | 0 | 0 | — |
| `_inf_T` | 1 | 1 | — |

### Critical Observation

There is **no rising clock edge** (0→1 transition) in the entire trace. The clock starts at 1 and falls to 0 at time 5, staying there. No register ever gets clocked. All sequential elements retain their initial (nondeterministic) values.

## 4. Root Cause Analysis

### Bug Category: **Assertion Error (incorrect assertion)**

The bug is in how `assertNextStepWhen` handles the initial state of its internal register, combined with the `initDone` guard being embedded in the *condition* rather than being a true initialization guard on the assertion logic.

### Mechanism of Failure

1. **How `assertNextStepWhen` works internally**: The macro `assertNextStepWhen(cond, prop, msg)` generates an assertion of the form:
   ```
   fvAssert(!RegNext(cond) || prop, msg)
   ```
   It creates a register `RegNext(cond)` that delays the condition by one cycle, then asserts that whenever `cond` was true in the previous cycle, `prop` holds in the current cycle.

2. **Nondeterministic initial value of `RegNext(cond)`**: In formal verification, registers have nondeterministic initial values at time 0. The `RegNext(cond)` register inside `assertNextStepWhen` can be initialized to either 0 or 1. In this counterexample, it is initialized to **1** (the failure-inducing case).

3. **The `initDone` guard is ineffective**: The user added `initDone = RegNext(true.B, false.B)` as a condition input to `assertNextStepWhen`. The intention is that `cond = initDone && io.enable && !io.reset && !io.clear` should be false at time 0, preventing the assertion from firing. However, `RegNext(cond)` is a **separate register** from `initDone`. Even though `cond` evaluates to false at time 0 (because `io.enable = 0`), the internal `RegNext(cond)` register has its own independent nondeterministic initial value of **1**.

4. **The check fires spuriously**: When `RegNext(cond) = 1` at time 0, the assertion evaluates `last === RegNext(io.in)`. At time 0:
   - `last = 0` (initialized to 0 as `RegInit(0.U(128.W))`)
   - `RegNext(io.in) = REG = 1` (nondeterministic initial value of `RegNext(io.in)`)
   - `0 === 1` → **false** → assertion fails

5. **No clock ever fires**: The trace shows no rising clock edge (clock stays at 1 then falls to 0), confirming this is purely an initial-state failure. No sequential logic ever updates from its initial nondeterministic state.

### Why the `initDone` Guard Cannot Work Here

In ChiselFv's `assertNextStepWhen`:

```scala
def assertNextStepWhen(cond: Bool, prop: Bool, msg: String): Unit = {
  fvAssert(!RegNext(cond) || prop, msg)
}
```

The register `RegNext(cond)` has no reset value specified, so in formal verification its initial value is **nondeterministic** (can be 0 or 1). The `initDone` condition only affects the *value* of `cond` in the expression, but `RegNext(cond)` is a separate register whose initial value is independent of `cond` at time 0.

### Comparison with Other Assertions

The other assertions in the file (`sup_ge_inf`, `clear_output_zero`, `disabled_output_last`, `reset_output_in`, `normal_output_avg`) are combinational assertions using `fvAssert` directly. These don't suffer from the same issue because they don't involve `RegNext` registers. Indeed, the waveform filename only mentions `last_stores_previous_in`, confirming that only this assertion fails.

### Required Fix

The fix should ensure that the internal `RegNext(cond)` register inside `assertNextStepWhen` has a proper reset value of `false.B` so it cannot nondeterministically be `true` at time 0. Alternatively, the assertion should be restructured to use `hasBeenReset` as a separate guard:

```scala
// Option 1: Initialize the internal RegNext properly
// This requires modifying the assertNextStepWhen implementation:
fvAssert(!hasBeenReset || !RegNext(initDone && io.enable && !io.reset && !io.clear) || last === RegNext(io.in))

// Option 2: Use a manually written next-state assertion with proper guards
when(hasBeenReset) {
  fvAssert(!RegNext(initDone && io.enable && !io.reset && !io.clear) || last === RegNext(io.in))
}
```

The key insight is that `hasBeenReset` (provided by ChiselFv's `Formal` trait) must guard the *entire* assertion, including the `RegNext(cond)` register, not just the combinatorial condition.
