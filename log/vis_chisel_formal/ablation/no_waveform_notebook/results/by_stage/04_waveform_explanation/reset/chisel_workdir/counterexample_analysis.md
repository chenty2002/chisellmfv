# Counterexample Analysis Report

## 1. Verification Environment

- **Top Module**: `ResetModule` (from `llmverify` package)
- **Source File**: `reset.scala`
- **Key Components**:
  - `ResetModule` — the design under test, an 8-bit counter with configurable max value
  - `resetCounter` — a formal verification helper submodule (part of chiselFv library)
- **Design Description**: A counter that starts at 0 after reset and increments each cycle when `io.en` is asserted. When the counter reaches `io.max`, it wraps around to 0 and asserts `io.wrap`.

## 2. Violated Assertion

- **Full Assertion Name**: `counter_never_exceeds_max`
- **File Location**: `reset.scala`, line 43
- **Code Snippet**:
  ```scala
  // --- Safety: counter never exceeds max ---
  fvAssert(count <= io.max, "counter_never_exceeds_max")
  ```
- **Property Description**: The counter value (`count`) must always be less than or equal to the maximum value (`io.max`) at all times.

## 3. Waveform Information

- **Waveform File**: `verilog/extra_bench/reset/ResetModule.counter_never_exceeds_max.fst`
- **Time Range**: 0 ns → 20 ns (2 clock cycles)
- **Key Time Points**:

| Time (ns) | count | io_max | io_en | Assertion Status |
|-----------|-------|--------|-------|-----------------|
| 0         | 0     | 128 (0b10000000) | 1 | Passes (0 ≤ 128) |
| 5         | 0     | 128 (0b10000000) | 1 | Passes |
| 10        | 1     | 0 (0b00000000)   | 1 | **Fails** (1 ≤ 0) |

- **Critical Signal States at Failure Point (t=10ns)**:
  - `ResetModule.count` = 1 (00000001)
  - `ResetModule.io_max` = 0 (00000000)
  - `ResetModule.io_en` = 1
  - `ResetModule.io_wrap` = 0
  - `ResetModule.wrap_detected` = 0
  - `ResetModule.reset` = 0 (no reset ever asserted)
  - `ResetModule.counter_never_exceeds_max` = 0 (assertion violated)

## 4. Root Cause Analysis

### Bug Location
- **File**: `reset.scala`
- **Lines**: 25–33 (the counter logic inside `when(io.en)`)
- **Module**: `ResetModule`

### Bug Description

The counter's wrap detection logic on **line 26** uses an **equality check** (`===`) instead of a **greater-than-or-equal check** (`>=`) to decide when to wrap the counter back to 0:

```scala
when(io.en) {
    when(count === io.max) {       // <--- BUG: should be count >= io.max
      count := 0.U
      wrap_detected := true.B
    }.otherwise {
      count := count + 1.U
      wrap_detected := false.B
    }
}
```

When `io.max` is dynamically changed to a value **below** the current `count`, the condition `count === io.max` will never be true (the counter keeps incrementing past `io.max`), and the counter exceeds the maximum value, violating the assertion.

### Evidence from Waveform

The formal tool's counterexample shows the following scenario:

1. **At time 0 (initial state)**: `count = 0`, `io_max = 128` (0b10000000), `io_en = 1`. The condition `count(0) === io_max(128)` is **false**, so the counter prepares to increment.

2. **At time 10 (posedge clock)**: Two things happen simultaneously:
   - `count` increments from 0 → 1 (because `io_en=1` and `count(0) != io_max(128)` in the previous cycle)
   - `io_max` changes from 128 → 0 (driven by the formal tool as an unconstrained input)

3. **Assertion failure**: Now `count=1` and `io_max=0`. The check `count(1) === io_max(0)` is **false** (1 ≠ 0), so the counter will **continue incrementing** (to 2, 3, 4, ...) without ever wrapping. The assertion `count ≤ io_max` (1 ≤ 0) is immediately violated.

### Why This Causes the Assertion to Fail

The core issue is that the wrap condition only triggers on **exact equality** with `io.max`. When `io.max` drops below the current count, the counter enters a state where:
- `count > io.max` (violating the assertion)
- `count ≠ io.max` (so the wrap logic never triggers)
- The counter keeps incrementing indefinitely, making the violation permanent

### Fix Recommendation

Change the wrap condition on line 26 from `count === io.max` to `count >= io.max`:

```scala
when(io.en) {
    when(count >= io.max) {        // FIX: use >= instead of ===
      count := 0.U
      wrap_detected := true.B
    }.otherwise {
      count := count + 1.U
      wrap_detected := false.B
    }
}
```

This ensures that the counter wraps to 0 whenever it reaches **or exceeds** the maximum value, guaranteeing that `count ≤ io.max` is always satisfied.

### Error Classification

- **Error Type**: **DUT Bug** — the design's counter logic incorrectly uses `===` instead of `>=` for wrap detection.

