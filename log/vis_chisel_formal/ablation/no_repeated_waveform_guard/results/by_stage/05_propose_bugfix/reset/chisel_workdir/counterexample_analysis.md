# Counterexample Analysis Report: `st0_follows_sel0_one_cycle_delay`

## 1. Verification Environment

- **Top Module**: `reset` (from `reset.scala`)
- **Work Directory**: `chisel/extra_bench/reset`
- **Generated Verilog**: `generated/reset.sv`
- **Waveform File**: `verilog/extra_bench/reset/reset.st0_follows_sel0_one_cycle_delay.fst`

**Top Module Structure**:
- `reset` module with `clock`, `reset`, `io_sel [1:0]` inputs and `io_st [2:0]` output
- Three internal registers: `st0`, `st1`, `st2` (all `RegInit(0.U(1.W))`)
- Internal registers: `prev_st1`, `prev_sel0`, `hasBeenResetReg`, `pending`, `timer`
- External module: `ResetCounter` (provides `notChaos` signal indicating when chaos period has elapsed)
- Assertions use `disable iff (~hasBeenReset)` as their disable condition

**Key Connections**:
- `st0 := io.sel(0)` — st0 follows sel(0) combinatorially
- `st1 := ~st1` — st1 toggles every cycle
- `st2 := io.sel(1) | st2` — st2 is sticky once set
- `prev_sel0 := io.sel(0)` — captures prior value of sel(0)
- `hasBeenReset = hasBeenResetReg === 1'h1 & reset === 1'h0`

## 2. Violated Assertion

- **Assertion Name**: `st0_follows_sel0_one_cycle_delay`
- **Waveform Filename**: `reset.st0_follows_sel0_one_cycle_delay.fst`

**Source Code** (reset.scala, lines 46–53):
```scala
val prev_sel0 = RegNext(io.sel(0))
val guard0 = RegInit(false.B)
guard0 := true.B
when (guard0) {
  fvAssert(st0 === prev_sel0, "st0_follows_sel0_one_cycle_delay")
}
```

**Generated Verilog** (generated/reset.sv, lines 47–48):
```verilog
st0_follows_sel0_one_cycle_delay:
    assert property (@(posedge clock) disable iff (~hasBeenReset) st0 == prev_sel0);
```

**Natural Language Description**: The assertion checks that `st0` (which follows `io.sel(0)` with one cycle delay) equals the previous value of `io.sel(0)` sampled by `prev_sel0`. The intended guard mechanism (`when(guard0)`) was supposed to suppress this check at cycle 0 to allow both signals to stabilize after reset.

## 3. Waveform Information

- **Waveform File**: `verilog/extra_bench/reset/reset.st0_follows_sel0_one_cycle_delay.fst`
- **Duration**: 1 cycle (0 ns → 10 ns)
- **Failure Point**: Time = 0 ns

**Critical Signal Values at Time 0**:

| Signal | Value | Description |
|--------|-------|-------------|
| `reset.st0` | `0` | Internal state register |
| `reset.prev_sel0` | `1` | Previous value of `io.sel(0)` |
| `reset.io_sel [1:0]` | `11` | Input selection bits |
| `reset.hasBeenReset` | `1` | Reset-complete indicator |
| `reset.hasBeenResetReg` | `1` | Reset-complete register |
| `reset.st0_follows_sel0_one_cycle_delay` | `1` | Assertion fires (1 = asserted) |
| `reset.reset` | `0` | Reset is deasserted |
| `reset.resetCounter.reset` | `0` | ResetCounter module reset |
| `reset.resetCounter.flag` | `1` | ResetCounter flagged complete |

**Assertion Failure Condition**: `st0 (0) != prev_sel0 (1)`, so `st0 == prev_sel0` evaluates to false.

## 4. Root Cause Analysis

### Buggy Code Location
- **File**: `reset.scala`
- **Lines**: 47–53
- **Function**: st0 assertion guard mechanism (`when(guard0)`)

### Root Cause Category: **assertion_error**

### Description of the Bug

The assertion uses an ineffective guard mechanism. The intended design is:

```scala
val guard0 = RegInit(false.B)  // starts false
guard0 := true.B                // becomes true after first posedge
when (guard0) {                 // only check after first cycle
    fvAssert(st0 === prev_sel0, ...)
}
```

This guard was supposed to suppress the assertion on cycle 0 (when `guard0` is still `false.B`) and enable it from cycle 1 onward. **However, the `when(guard0)` block is completely lost during Chisel/FIRRTL compilation.**

**Evidence from generated Verilog** (generated/reset.sv):
- There is **no `guard0` register** in the Verilog output
- There is **no `guard1` register** either (the same pattern is used for st1)
- The assertion is generated as a bare `assert property` without any guard condition:
  ```verilog
  assert property (@(posedge clock) disable iff (~hasBeenReset) st0 == prev_sel0);
  ```
- The only disable condition is `~hasBeenReset`, which is the framework's built-in mechanism

### Why the Assertion Fails

The failure is caused by a **cycle-0 mismatch** between `st0` and `prev_sel0`:

1. **During the reset cycle** (posedge where `reset=1`):  
   - `st0 <= 1'h0` (explicitly reset to 0 in the `if (reset)` block)  
   - `prev_sel0 <= io_sel[0]` (this assignment is **outside** the `if (reset)` block, so it executes unconditionally on every posedge, even during reset)  
   - If `io_sel[0] = 1` at this time, then `prev_sel0 = 1`

2. **At cycle 0** (first posedge after reset, where `reset=0`):  
   - `hasBeenReset = 1` (since `hasBeenResetReg` was set during reset and now `reset=0`)  
   - `~hasBeenReset = 0`, so the assertion is **NOT disabled**  
   - `st0` still holds the reset value `0` (hasn't been updated yet this cycle)  
   - `prev_sel0` holds `1` (captured from the reset cycle)  
   - **Assertion evaluates**: `st0 (0) == prev_sel0 (1)` → **FALSE** → **FAIL**

3. **The intended guard** (`when(guard0)`) would have suppressed this check at cycle 0. After cycle 1, both `st0` and `prev_sel0` would have been updated with the same `io.sel(0)` value, making the assertion pass.

### Why the Guard Fails

The `when(guard0)` pattern (a `RegInit(false.B)` that is immediately set to `true.B`, used as a `when` condition) is a Chisel-level construct. During FIRRTL compilation to Verilog, the Chisel formal framework (`chiselFv`) does not translate `when`-block conditions into assertion disable conditions. The `when(guard)` blocks are simply eliminated, resulting in unconditional assertions that use only the framework's built-in `hasBeenReset` disable condition.

This `hasBeenReset` mechanism becomes active one cycle earlier than the intended guard: it enables assertions as soon as `reset` is deasserted, whereas the Chisel-level `guard` register was supposed to wait one additional cycle.

### Suggested Fix

The assertion guard mechanism needs to be implemented at the **assertion property level** rather than the Chisel `when` block. Two possible approaches:

**Option A**: Add a proper disable condition to the assertion that accounts for the first cycle:
```scala
// Use a manual guard that actually translates to the assertion
val guard0 = RegInit(false.B)
guard0 := true.B
fvAssert(st0 === prev_sel0, "", guard0.asBool)
```
(If the framework supports an explicit enable parameter)

**Option B**: Override the `hasBeenReset` mechanism or add an additional delay:
```scala
// Wait for the first non-reset cycle to complete before checking
val guard0 = RegInit(false.B)
guard0 := true.B
fvAssert(st0 === prev_sel0 || !guard0, "st0_follows_sel0_one_cycle_delay")
```
(Using the guard as part of the assertion logic)

**Option C**: Simply accept that the assertion needs the startup cycle and use the assertion without a guard, but ensure the property holds for cycles ≥ 1:
The property `st0 === prev_sel0` is actually correct for all cycles ≥ 1 (since both get the same `io.sel(0)` value). The issue is only cycle 0. The most practical fix would be to use a guard that survives compilation, e.g., by embedding the guard condition into a `past()` or `delay()` construct that works with the formal framework.

In the generated Verilog, the most straightforward fix would be to add an explicit cycle-0 disable:
```verilog
st0_follows_sel0_one_cycle_delay:
    assert property (@(posedge clock) disable iff (~hasBeenReset || $past(reset)) st0 == prev_sel0);
```
Which disables the assertion for one more cycle after reset deasserts.
