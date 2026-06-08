# Counterexample Analysis Report: `after_wrap_count_advances`

## 1. Verification Environment

- **Top Module**: `ResetModule` (from `llmverify` package in `reset.scala`)
- **Test Harness**: Formal-aware wrapper with `resetCounter` for reset stability, `hasBeenReset`/`hasBeenResetReg` for reset state tracking, and `pending`/`nextPending` for input scheduling
- **Design Under Test**: A simple counter with enable (`io.en`), max value (`io.max`), count output (`io.out`), and wrap indicator (`io.wrap`). The counter resets to 0, increments each cycle when enabled, and wraps to 0 when count >= max.

## 2. Violated Assertion

- **Assertion Name**: `after_wrap_count_advances`
- **Waveform File**: `ResetModule.after_wrap_count_advances.fst`
- **File Location**: `reset.scala`, line 80

### Code Snippet

```scala
fvAssert(!(RegNext(io.wrap) && !io.en && count === RegNext(io.max)), "after_wrap_count_advances")
```

### Property Description

The assertion is intended to check that **after a wrap event, the count advances away from the max value**. Specifically, it asserts that the following combination should **never** occur:

- `RegNext(io.wrap)` is true (a wrap happened in the previous cycle)
- `io.en` is false (the counter is disabled this cycle)
- `count === RegNext(io.max)` (the current count equals the previous cycle's max value)

In other words: after a wrap (which sets count to 0), if the counter is then disabled, the count should not be stuck at a value equal to the previous max -- it should have advanced beyond it.

## 3. Waveform Information

- **Waveform File Path**: `verilog/extra_bench/reset/ResetModule.after_wrap_count_advances.fst`
- **Duration**: 3 cycles (30 ns), clock period = 10 ns
- **Time Range**: 0 ns → 30 ns

### Key Signal Traces

| Time (ns) | Cycle | count | io_en | io_max | io_wrap | REG (RegNext wrap) | REG_1 (RegNext max) | Assertion |
|-----------|-------|-------|-------|--------|---------|-------------------|--------------------|-----------|
| 0-10      | 0     | 0x00  | 1     | 0x00   | 0→1*   | 0                 | 0xFF (initial)     | 1 (pass)  |
| 10-20     | 1     | 0x00  | 1     | 0x01   | 1      | 0                 | 0x00 (captured)    | 1 (pass)  |
| 20-30     | 2     | 0x01  | 0     | 0xFF   | 0      | **1**             | **0x01**           | **0 (FAIL)** |

*Note: io_wrap transitions to 1 at time 10 due to register update from cycle 0 evaluation.

### Assertion Failure Point

At **time 20 ns** (rising edge of cycle 2):
- **Assertion signal `after_wrap_count_advances`**: transitions from 1 → **0** (FAILURE)
- `RegNext(io.wrap)` = REG = **1** (io_wrap was 1 in cycle 0)
- `io.en` = **0** (disabled)
- `count` = **0x01**
- `RegNext(io.max)` = REG_1 = **0x01**
- Failure condition: `1 && 1 && (0x01 === 0x01)` = **true** → assertion violated

### Cycle-by-Cycle Analysis

**Cycle 0 (time 0-10, rising edge at 0 ns):**
- Initial state after reset: count=0, io_en=1, io_max=0
- `count(0) >= io_max(0)` → **wrap!** → count stays 0, wrap_detected=1
- io_wrap becomes 1 (combinational from wrap_detected)

**Cycle 1 (time 10-20, rising edge at 10 ns):**
- count=0, io_en=1, io_max=1 (changed from 0 to 1 at this edge)
- `count(0) >= io_max(1)`? **No** (0 < 1) → count increments: count := 0+1 = 1
- wrap_detected := 0, io_wrap = 0

**Cycle 2 (time 20-30, rising edge at 20 ns):**
- count=1, io_en=0 (disabled), io_max=0xFF
- No register updates (io_en=0)
- RegNext(io.wrap) = 1 (captured io_wrap=1 at time 10 edge)
- RegNext(io.max) = 1 (captured io_max=1 at time 10 edge)
- count(1) === RegNext(io.max)(1) → assertion fails

## 4. Root Cause Analysis

### Error Classification: **Incorrect Assertion** (assertion_error)

### Detailed Explanation

The assertion `after_wrap_count_advances` is **incorrectly formulated** and produces a **false negative** (spurious counterexample) for a legitimate counter behavior.

#### The Scenario

The counterexample demonstrates a completely valid sequence:

1. **Cycle 0**: io_max=0, count=0 (reset). Since count(0) >= io_max(0), the counter wraps: count stays 0, wrap_detected=1.
2. **Cycle 1**: io_max=1, count=0. Since count(0) < io_max(1), the counter increments: count becomes 1. No wrap.
3. **Cycle 2**: io_en=0, count=1. The counter is disabled.

At cycle 2, `RegNext(io.wrap)=1` (because a wrap happened in cycle 0), and `count(1) === RegNext(io.max)(1)`. The assertion fires.

#### Why the Assertion is Wrong

The assertion's condition `count === RegNext(io.max)` is checking whether **the current count equals the previous cycle's max value**. After a wrap that sets count to 0, and then an increment to 1 (because max=1), the count legitimately equals the previous max value (1). The count **did advance** -- it went from 0 (post-wrap) to 1 -- but it happened to land on the same value as the max from the previous cycle.

The **intent** of the assertion (per the code comment) was: "When wrap is asserted and then deasserted, count should be < max." However, the assertion actually checks something subtly different: it checks that after a wrap, if enable is low, the count doesn't equal the *previous* max. But the count could have advanced during an intermediate enabled cycle and landed on the max value.

#### What a Correct Assertion Would Look Like

A correct safety property for "count advances after wrap" could be:

```scala
// After a wrap, the count should not remain at 0 (the post-wrap value) 
// when the counter is subsequently disabled
fvAssert(!(RegNext(io.wrap) && !io.en && count === 0.U), "after_wrap_count_not_stuck")
```

Or, to capture the original intent more precisely:

```scala
// When the counter wraps, count goes to 0.
// After a wrap (RegNext(io.wrap) true), if the counter is enabled,
// the count should have moved away from the wrapped value.
// A simpler check: after wrap, count should not be 0 when enabled in the next cycle.
fvAssert(!(RegNext(io.wrap) && io.en && count === 0.U), "count_advances_on_enable_after_wrap")
```

### Buggy Code Location

- **File**: `reset.scala`
- **Line**: 80
- **Module**: `ResetModule`
- **Code**:
  ```scala
  fvAssert(!(RegNext(io.wrap) && !io.en && count === RegNext(io.max)), "after_wrap_count_advances")
  ```

### Evidence Summary

1. **Waveform at time 20 ns**: count=0x01, RegNext(io.max)=0x01, RegNext(io.wrap)=1, io_en=0
2. **count did advance**: it went from 0 (post-wrap at cycle 0) to 1 (cycle 1 increment)
3. **The assertion triggers spuriously** because count happened to equal the previous max value (1)
4. **The underlying counter logic is correct**: it properly wraps at max=0, increments correctly for max=1, and holds stable when disabled

The design itself (`ResetModule`) is bug-free. The assertion needs to be rewritten to correctly capture the post-wrap advancement property without false negatives.
