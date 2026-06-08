# Counterexample Analysis Report: `cnt_increments_in_mode0`

## 1. Verification Environment

- **Top Module**: `rgraph` (Chisel class in package `llmverify`)
- **Module Type**: Extends `Module with Formal` (chiselFv formal verification library)
- **Key Components**:
  - `cnt` (12-bit register): Counter, initialized to 0 via `RegInit(0.U)`
  - `mode` (1-bit register): Mode selector, initialized to 0 via `RegInit(0.U)`, transitions from 0→1 when `mode === 0.U && io_i`
  - `io_i` (Bool input): Input signal that triggers mode transition and decrement
  - `io_o` (Bool output): True when `cnt === 0.U`
  - `hasBeenReset`, `hasBeenResetReg`: Reset status signals from the `Formal` trait
- **Design Behavior**:
  - In mode 0: `cnt` increments by 1 every cycle (`cnt := cnt + 1.U`)
  - In mode 1: When `io_i && cnt =/= 0.U`, `cnt` decrements by 1 (`cnt := cnt - 1.U`)
  - Mode transitions from 0 to 1 when `mode === 0.U && io_i`, and is "sticky" (never transitions back)

## 2. Violated Assertion

- **Assertion Name**: `cnt_increments_in_mode0`
- **Waveform File**: `rgraph.cnt_increments_in_mode0.fst`

### Code Snippet

```scala
// rgraph.scala, line 33
fvAssert(!(mode === 0.U) || cnt === RegNext(cnt, 0.U) + 1.U, "cnt_increments_in_mode0")
```

### Natural Language Description

**Property**: If `mode` is 0, then `cnt` must equal the previous cycle's value of `cnt` plus exactly 1. In other words, every cycle where mode=0, the counter increments by 1.

- **File**: `chisel/extra_bench/rgraph/rgraph.scala`
- **Line**: 33

## 3. Waveform Information

- **Waveform File Path**: `verilog/extra_bench/rgraph/rgraph.cnt_increments_in_mode0.fst`
- **Waveform Duration**: 1 cycle (0 ns to 10 ns)
- **Clock**: Positive edge at time 0 ns, negative edge at time 5 ns

### Critical Signal Values

| Signal | Time 0 ns | Time 5 ns |
|--------|-----------|-----------|
| `rgraph.clock` | 1 (posedge) | 0 |
| `rgraph.cnt [11:0]` | `000000000000` (0) | `000000000000` (0) |
| `rgraph.mode` | 0 | 0 |
| `rgraph.io_i` | 0 | 0 |
| `rgraph.hasBeenReset` | 1 | 1 |
| `rgraph.hasBeenResetReg` | 1 | 1 |
| `rgraph.reset` | 0 | — |
| `rgraph.cnt_increments_in_mode0` | 1 (asserted FAIL) | 1 |
| `rgraph.REG_1 [11:0]` (RegNext(cnt, 0.U)) | `000000000000` (0) | `000000000000` (0) |

### Failure Point

The assertion fails at **time 0 ns**, the first positive clock edge, because:

- `mode` = 0 (premise is true, so the implication must check the conclusion)
- `cnt` = 0
- `RegNext(cnt, 0.U)` (REG_1) = 0 (initial value, same as cnt since no prior cycle has occurred)
- Check: `0 === 0 + 1` → `0 === 1` → **false**
- Result: `!(0===0) || (0 === 1)` → `false || false` → **false** → **ASSERTION FAILS**

No signal values change between time 0 and time 5 (the entire trace is a single cycle with no state changes).

## 4. Root Cause Analysis

### Bug Classification: **Incorrect Assertion**

This is **NOT** a bug in the DUT (design under test). The DUT logic is correct — the `when(mode === 0.U) { cnt := cnt + 1.U }` block correctly increments `cnt` by 1 on every cycle when mode=0.

### Root Cause

The assertion `cnt_increments_in_mode0` fails due to an **incorrect assertion** that does not account for the first cycle after reset/initialization:

**The Problem**: On the very first clock cycle, both `cnt` (a `RegInit(0.U)`) and `RegNext(cnt, 0.U)` (a register initialized to 0) hold the value 0. The assertion expects `cnt === RegNext(cnt) + 1`, i.e., `0 === 1`, which is impossible on the first cycle because there has not yet been a prior cycle to establish the relationship.

The `RegNext(cnt, 0.U)` creates a register with initial value 0, inheriting the same initial value as `cnt`. This means that on cycle 1, both the current value and the "previous" value are 0, making the increment check `0 === 0 + 1` naturally false.

### Explanation of the False Violation

1. At time 0 (first posedge of clock after formal reset is released):
   - All registers hold their `RegInit` values
   - `cnt = 0`, `mode = 0`
   - `RegNext(cnt, 0.U) = 0` (initial value of the delay register)

2. The design would correctly update `cnt` to `0 + 1 = 1` on the **next** clock edge, but the assertion fires on the **current** edge before any update occurs.

3. The assertion is checking a relationship that requires at least one previous cycle of history, but no history exists at the first cycle.

### Fix

The assertion should include a guard that excludes the very first cycle. For example, add `RegNext(hasBeenReset, 0.U)` as a precondition:

```scala
fvAssert(!RegNext(hasBeenReset, 0.U) || !(mode === 0.U) || cnt === RegNext(cnt, 0.U) + 1.U,
         "cnt_increments_in_mode0")
```

This ensures the property is only checked starting from the second cycle after reset, giving `cnt` one cycle to establish the increment relationship. Alternatively, the assertion could use `past(cnt)` with appropriate cycle count to properly reference the true "previous" value.

### Similar Issue in Other Assertions

The same pattern affects other assertions in the same file:
- Line 31: `assertImplies(RegNext(mode, 0.U) === 1.U, mode === 1.U, ...)` — could also have first-cycle issues
- Line 37: `fvAssert(!(mode === 1.U) || cnt <= RegNext(cnt, 0.U), ...)` — similar first-cycle problem
- Line 39: `fvAssert(!(mode === 1.U && io.i && cnt =/= 0.U) || cnt === RegNext(cnt, 0.U) - 1.U, ...)` — similar
- Line 41: `fvAssert(!(mode === 1.U && !(io.i && cnt =/= 0.U)) || cnt === RegNext(cnt, 0.U), ...)` — similar

All of these use `RegNext(..., 0.U)` without any `hasBeenReset` guard, making them vulnerable to the same first-cycle false-failure pattern.

### Evidence Summary

| Evidence | Detail |
|----------|--------|
| `cnt` at time 0 | 0 (initial value) |
| `RegNext(cnt, 0.U)` at time 0 | 0 (initial value, same as cnt) |
| Mode at time 0 | 0 (triggers the implication) |
| Expected: `cnt === RegNext(cnt)+1` | `0 === 0 + 1` → false |
| DUT logic | Correct: `when(mode===0) { cnt := cnt + 1.U }` is correct |
| Root cause | **Incorrect assertion** — missing first-cycle guard |
