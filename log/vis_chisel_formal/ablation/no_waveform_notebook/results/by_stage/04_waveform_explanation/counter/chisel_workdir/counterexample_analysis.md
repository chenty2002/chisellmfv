# Counterexample Analysis Report: Counter Increment Assertion

## 1. Verification Environment

- **Top Module**: `Counter` (in `chisel/extra_bench/counter/counter.scala`, line 29)
- **Structure**: Counter module instantiates three `CounterCell` modules in a ripple-carry configuration to form a 3-bit binary counter.
- **Key Components**:
  - `CounterCell`: A single-bit counter/toggle flip-flop with carry_in, carry_out, and value outputs
  - `Counter`: Top module connecting three CounterCell instances (bit0, bit1, bit2) in a ripple-carry chain
  - Formal assertions using ChiselFv's `Formal` trait
- **Connections**: 
  - `bit0.io.carry_in := true.B` (always enabled)
  - `bit1.io.carry_in := bit0.io.carry_out`
  - `bit2.io.carry_in := bit1.io.carry_out`
  - Outputs: `io.out0/1/2` connected to each bit's value

## 2. Violated Assertion

- **Assertion Name**: `Counter_increments_by_1_modulo_8_each_cycle` (from waveform filename `Counter.Counter_increments_by_1_modulo_8_each_cycle.fst`)
- **Full Path**: `chisel/extra_bench/counter/counter.scala`, lines 59-61

### Code Snippet
```scala
// Assemble 3-bit counter value
val prev_value = RegNext(Cat(io.out2, io.out1, io.out0), 0.U(3.W))
val curr_value = Cat(io.out2, io.out1, io.out0)

// Safety: counter increments by exactly 1 (mod 8) each cycle
fvAssert(curr_value === (prev_value + 1.U)(2, 0), "Counter increments by 1 modulo 8 each cycle")
```

### Property Description
The assertion checks that the 3-bit counter value increments by exactly 1 (modulo 8) every clock cycle. Specifically, it asserts that `curr_value` (the current counter output) equals `(prev_value + 1) mod 8`, where `prev_value` is the counter output from the previous cycle (captured via `RegNext`).

## 3. Waveform Information

- **Waveform File**: `verilog/extra_bench/counter/Counter.Counter_increments_by_1_modulo_8_each_cycle.fst`
- **Duration**: 1 cycle (0 ns → 10 ns)
- **Key Time Points and Signal Values**:

| Time | Signal | Value |
|------|--------|-------|
| 0 ns | `curr_value` | 000 (0) |
| 0 ns | `prev_value` | 000 (0) |
| 0 ns | `bit0.value` | 0 |
| 0 ns | `bit1.value` | 0 |
| 0 ns | `bit2.value` | 0 |
| 0 ns | `clock` | 1 |
| 0 ns | `reset` | 0 (deasserted) |

All signals remain stable throughout the 10 ns waveform with no transitions (except the clock falling at 5 ns). The counter registers never update because there is only one clock posedge (at time 0) and no subsequent posedge within the trace.

## 4. Root Cause Analysis

### Bug Classification: **Incorrect Assertion** (assertion_error)

### Root Cause

The assertion fails **at the initial state (time 0)** because it does not account for the reset condition properly.

**Why it fails:**

1. After reset, all registers in the design are at their reset values:
   - `bit0.value = 0`, `bit1.value = 0`, `bit2.value = 0` → `curr_value = Cat(0,0,0) = 0`
   - `prev_value = 0` (reset value of `RegNext(..., 0.U(3.W))`)

2. At the initial state (time 0), the assertion condition evaluates as:
   ```
   curr_value === (prev_value + 1.U)(2, 0)
   → 0 === (0 + 1) mod 8
   → 0 === 1
   → FALSE
   ```

3. Since both `curr_value` and `prev_value` are initialized to 0 (matching the counter's post-reset value of 0), the increment-by-1 relation cannot possibly hold at the very first cycle. The counter needs one full clock cycle to transition from 0 to 1.

### Why the Design is Actually Correct

The ripple-carry counter design itself is functionally correct. Tracing through the first clock cycle:
- Bit0 toggles (carry_in=1): 0 → 1
- Bit1: carry_in=bit0.carry_out=0, stays 0
- Bit2: carry_in=bit1.carry_out=0, stays 0
- After first posedge: curr_value = 1, prev_value captures old value 0
- Assertion: 1 === (0+1) → TRUE ✓

The assertion would pass on all subsequent cycles as well (1→2, 2→3, ..., 7→0).

### Bug Location

**File**: `chisel/extra_bench/counter/counter.scala`, lines 55-58
```scala
val prev_value = RegNext(Cat(io.out2, io.out1, io.out0), 0.U(3.W))
val curr_value = Cat(io.out2, io.out1, io.out0)
fvAssert(curr_value === (prev_value + 1.U)(2, 0), "Counter increments by 1 modulo 8 each cycle")
```

### The Fix

The assertion should be conditioned to skip the initial cycle after reset. Options include:

**Option 1**: Initialize `prev_value` to `7` (i.e., -1 mod 8) so the initial condition `0 === (7+1) mod 8 → 0 === 0` passes:
```scala
val prev_value = RegNext(Cat(io.out2, io.out1, io.out0), 7.U(3.W))
```

**Option 2**: Use `past()` from ChiselFv instead of manual `RegNext`, which may handle the first-cycle semantics correctly.

**Option 3**: Add a `disable` condition or use `assertAfterNStepWhen` to skip the assertion on the first cycle after reset.

**Option 4**: Use ChiselFv's `past()` function which provides proper first-cycle semantics:
```scala
import chiselFv._
fvAssert(curr_value === (past(curr_value, 1.U) + 1.U)(2, 0), "Counter increments by 1 modulo 8 each cycle")
```

### Evidence Summary

| Evidence Point | Detail |
|----------------|--------|
| Waveform shows | `curr_value=0`, `prev_value=0` at all times |
| Assertion checks | `0 === (0+1)` → **false** |
| Design logic | Ripple-counter correctly produces 1→2→3→...→7→0 sequence |
| Root cause | `prev_value` reset value (0) matches initial counter value (0), making the first-cycle check invalid |
