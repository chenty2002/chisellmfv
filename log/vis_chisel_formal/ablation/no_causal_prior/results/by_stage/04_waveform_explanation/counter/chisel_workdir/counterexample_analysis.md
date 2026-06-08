# Counterexample Analysis Report

## 1. Verification Environment

- **Top Module**: `Counter` (Chisel module with `Formal` trait)
- **Structure**:
  - Three `CounterCell` modules (`bit0`, `bit1`, `bit2`) connected as a ripple counter
  - `bit0` has a constant `carry_in = 1`, toggling every cycle
  - `bit1` receives carry from `bit0`
  - `bit2` receives carry from `bit1`
- **Design Under Test**: A 3-bit ripple counter that increments by 1 on each clock cycle

## 2. Violated Assertion

- **Full Assertion Name**: `counter_increments_by_1_mod_8`
- **Code Snippet** (counter.scala, lines 58–59):

```scala
val counter_value = Cat(io.out2, io.out1, io.out0)
val prev_value = RegNext(counter_value)

fvAssert((prev_value + 1.U)(2, 0) === counter_value || reset.asBool,
    "counter_increments_by_1_mod_8")
```

- **Property Description**: On every cycle (when not in reset), the counter value should equal the previous cycle's counter value plus 1 modulo 8.
- **File Location**: `counter.scala`, lines 58–60

## 3. Waveform Information

- **Waveform File**: `verilog/extra_bench/counter/Counter.counter_increments_by_1_mod_8.fst`
- **Time Range**: 0 ns → 10 ns (1 clock cycle)
- **Key Time Point**: 0 ns (the assertion fires combinatorially)

### Critical Signal Values at Time 0 ns

| Signal | Value |
|---|---|
| `Counter.prev_value [2:0]` | `000` |
| `Counter.counter_value [2:0]` | `000` |
| `Counter.reset` | `0` |
| `Counter.hasBeenReset` | `1` |
| `Counter.bit0.io_value` | `0` |
| `Counter.bit1.io_value` | `0` |
| `Counter.bit2.io_value` | `0` |
| `Counter.counter_increments_by_1_mod_8` | **`1` (assertion fired)** |

All counter bits remain at 0 throughout the single-cycle waveform (0–10 ns). No clock edge occurs to cause the counter to toggle.

## 4. Root Cause Analysis

### Classification: **Incorrect Assertion** (Assertion Error)

### Root Cause

The assertion fails because it does not account for the **first evaluation cycle after reset deassertion**.

The assertion logic is:

```
(prev_value + 1.U)(2, 0) === counter_value || reset.asBool
```

At time 0:
- `counter_value` = `Cat(io.out2, io.out1, io.out0)` = `000` (all counter bits are 0)
- `prev_value` = `RegNext(counter_value)` = `000` (initial value of the register)
- `reset.asBool` = `0` (reset is deasserted; `hasBeenReset=1` confirms reset happened previously)

So the check evaluates as: `(0 + 1) mod 8 === 0 || 0` → `1 === 0 || 0` → **false** → Assertion violation.

### Why This Is an Assertion Bug (Not a Design Bug)

The counter design is functionally correct as a 3-bit ripple counter:

1. `bit0` toggles every cycle (carry_in = 1)
2. `bit1` toggles when `bit0` transitions from 1→0 (carry_out = 1)
3. `bit2` toggles when `bit1` transitions from 1→0

After the first clock edge, `bit0` would toggle to 1, making `counter_value = 001`. Then `prev_value = 000` and `counter_value = 001`, and `(0+1) mod 8 = 1 = 001` ✓.

The problem is that `prev_value = RegNext(counter_value)` is checked **combinatorially before any clock edge has occurred**. At this initial instant, `prev_value` has not yet been updated from the previous cycle's counter value — it holds its power-on/reset initial value (0), which happens to equal the current counter value (also 0). The increment check `(0+1)%8 == 0` therefore falsely fails.

### Proposed Fix

The assertion should be gated to allow the first cycle after reset to be in an initial state. A proper fix would be:

```scala
// Option 1: Check that the previous cycle was not in reset
fvAssert((prev_value + 1.U)(2, 0) === counter_value || !RegNext(reset.asBool, false.B),
    "counter_increments_by_1_mod_8")

// Option 2: Check that prev_value has stabilized (using RegNext of reset)
val wasInReset = RegNext(reset.asBool, false.B)
fvAssert(wasInReset || (prev_value + 1.U)(2, 0) === counter_value,
    "counter_increments_by_1_mod_8")
```

Both options ensure the assertion only checks the increment property once a full clock cycle has passed after reset deassertion, allowing `prev_value` to hold a meaningful previous-cycle value.
