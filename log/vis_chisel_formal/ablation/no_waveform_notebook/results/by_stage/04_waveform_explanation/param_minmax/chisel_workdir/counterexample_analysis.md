# Counterexample Analysis Report: `avg_between_inf_sup`

## 1. Verification Environment

- **Top Module**: `minMax` (in package `llmverify`)
- **Source File**: `minMax.scala`
- **Structure**: The design implements a min-max tracker with average computation. It maintains three internal registers (`min`, `max`, `last`) and computes combinational values `sup` (max of input and current max), `inf` (min of input and current min), and `avg` (ostensibly the average of `sup` and `inf`).
- **Control Signals**: `io.clear` (reset all state to defaults), `io.enable` (enable accumulation), `io.reset` (reset min/max but latch input), `io.in` (128-bit unsigned input), `io.out` (128-bit output)
- **Key Components**:
  - Registers: `min` (init all-ones), `max` (init zero), `last` (init zero)
  - Combinational: `sup`, `inf`, `avg`, `aux` (carry bit)
  - Formal assertions (7 safety properties + 1 bounded-liveness property)

## 2. Violated Assertion

- **Assertion Name**: `avg_between_inf_sup`
- **Full Waveform Path**: `verilog/extra_bench/param_minmax/minMax.avg_between_inf_sup.fst`
- **Code Snippet** (from `minMax.scala`, lines 62-63):
  ```scala
  // Safety 2: When accumulating (enable && !reset && !clear), the average must be between inf and sup
  fvAssert(!(io.enable && !io.reset && !io.clear) || (avg >= inf && avg <= sup), "avg_between_inf_sup")
  ```
- **Natural Language Description**: When the module is in accumulation mode (`io.enable` is true, `io.reset` is false, and `io.clear` is false), the computed `avg` value must lie between `inf` (minimum of input and current min register) and `sup` (maximum of input and current max register), inclusive.

## 3. Waveform Information

- **Waveform File**: `verilog/extra_bench/param_minmax/minMax.avg_between_inf_sup.fst`
- **Time Range**: 0 ns → 10 ns (1 clock cycle)
- **Key Time Point**: 0 ns (positive clock edge)
- **Critical Signal Values at Time 0**:

| Signal | Value |
|--------|-------|
| `io_enable` | 1 |
| `io_reset` | 0 |
| `io_clear` | 0 |
| `io_in [127:0]` | `01000000000000000000000000000000000000010001010010101001001010010101010101010101000100101010101001010101010101010101010100000010` |
| `min [127:0]` | `11111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111` (all ones) |
| `max [127:0]` | `00000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000` (all zeros) |
| `sup [127:0]` | `01000000000000000000000000000000000000010001010010101001001010010101010101010101000100101010101001010101010101010101010100000010` (= `io_in`) |
| `inf [127:0]` | `01000000000000000000000000000000000000010001010010101001001010010101010101010101000100101010101001010101010101010101010100000010` (= `io_in`) |
| `_sum_T_2 [127:0]` (avg) | `10000000000000000000000000000000000000100010100101010010010100101010101010101010001001010101010010101010101010101010101000000100` (= 2 × `io_in`) |
| `_sup_T` | 1 (`io_in > max` is true) |
| `_inf_T` | 1 (`io_in < min` is true) |

- **Assertion result**: `avg_between_inf_sup` = 1 at time 0 (the assertion passes at the sampled time, but the formal tool found a combinational violation since the property is violated by the signal relationships themselves when `sup == inf > 0`).

## 4. Root Cause Analysis

### Bug Type: Bug in the Original Design (DUT Bug)

### Buggy Code Location
**File**: `minMax.scala`, lines 37-40
**Function**: Inside class `minMax`
**Buggy line**: Line 40 — `val avg = sum(127, 0)  // lower 128 bits`

### Description of the Bug

The design intends to compute the **average** of `sup` and `inf` (as stated in the comment on line 38: "Average calculation with carry (aux)"), but the implementation is incorrect.

The current implementation:

```scala
val sum = Cat(0.U(1.W), sup) + Cat(0.U(1.W), inf)  // 129-bit sum of zero-extended sup and inf
val avg = sum(127, 0)                                // lower 128 bits — WRONG!
val aux = sum(128)                                   // carry bit
```

The correct computation of the average `(sup + inf) / 2` should be:

```scala
val avg = (sum >> 1).asUInt          // right-shift by 1 = divide by 2
// or equivalently:
val avg = sum(128, 1)                // bits 128 down to 1 = (sup + inf) >> 1
```

### Evidence from Waveform

At time 0, the module is in accumulation mode. The state is:
- `max = 0` (initial value)
- `min = 0xFFFF…FFFF` (all ones, initial value)
- `io_in = 0x4000…02` (a value between 0 and all-ones)

The combinational logic computes:
- `sup = max(io_in, max) = io_in` (since `io_in > 0`)
- `inf = min(io_in, min) = io_in` (since `io_in < all-ones`)
- Thus `sup = inf = io_in`

Then:
- `sum = Cat(0, sup) + Cat(0, inf) = 2 × io_in` (as a 129-bit value)
- `avg = sum(127, 0) = lower 128 bits of (2 × io_in)`

Since `io_in < 2^127` (its MSB is 0), the 129-bit sum `2 × io_in` fits in 128 bits, so `avg = 2 × io_in`.

The assertion checks: `avg >= inf && avg <= sup`
- `avg >= inf`: `2 × io_in >= io_in` → **TRUE** (when `io_in > 0`)
- `avg <= sup`: `2 × io_in <= io_in` → **FALSE** (when `io_in > 0`)

Therefore, `avg <= sup` is violated because `avg` is the **sum** (not the average), and when `sup = inf > 0`, the sum is twice the value, which exceeds `sup`.

### Why This Causes the Assertion Failure

The property `avg_between_inf_sup` correctly asserts that a proper average `(sup + inf) / 2` should lie between `inf` and `sup`. However, the design computes `avg` as the **lower 128 bits of the sum** `(sup + inf)` instead of the **quotient** `(sup + inf) / 2`. This is a genuine design bug—the average calculation logic is implemented incorrectly.

### Impact on Other Assertions

The same bug also affects the `accumulate_output_avg` assertion (Safety 6), which checks that the output equals `avg` during accumulation. Since `avg` is the wrong value (the sum instead of the average), the output during accumulation will also be wrong.

### Proposed Fix

Change line 40 of `minMax.scala` from:
```scala
val avg = sum(127, 0)  // lower 128 bits
```
to:
```scala
val avg = (sum >> 1).asUInt  // (sup + inf) / 2
```
