# Counterexample Analysis: mode_stays_one_once_set

## 1. Verification Environment

- **Top Module**: `rgraph` (from `rgraph.scala`)
- **Module Type**: Chisel Module with Formal verification mixin
- **Key Components**:
  - `mode`: 1-bit register, initial value 0 — controls the operation mode
  - `cnt`: 12-bit register (bits 11:0), initial value 0 — counter
  - `io_i`: 1-bit input — control signal
  - `io_o`: 1-bit output — equals `(cnt === 0.U)`
- **Design Description**: A two-mode counter. In mode 0, `cnt` increments by 1 each cycle. In mode 1, when `io_i` is high and `cnt` is non-zero, `cnt` decrements by 1. The mode transitions from 0 to 1 when `mode === 0.U && io_i`.

## 2. Violated Assertion

- **Full Assertion Name**: `mode_stays_one_once_set`
- **Code Snippet** (from `rgraph.scala`, line 31):
  ```scala
  assertStableWhen(mode === 1.U, mode, "mode_stays_one_once_set")
  ```
- **Natural Language Description**: The assertion checks that **when `mode` equals 1, the `mode` signal stays stable (does not change between consecutive cycles)**. The intended high-level property is: "Once mode becomes 1, it stays 1 forever."
- **File Location**: `rgraph.scala`, line 31

## 3. Waveform Information

- **Full Waveform Path**: `verilog/extra_bench/rgraph/rgraph.mode_stays_one_once_set.fst`
- **Time Range**: 0 ns → 20 ns (2 clock cycles, clock period = 10 ns)
- **Key Time Points**:

| Time (ns) | mode | cnt [11:0] | io_i | io_o | assertion |
|-----------|------|------------|------|------|-----------|
| 0         | 0    | 0x000      | 1    | 1    | 1 (pass)  |
| 10        | 1    | 0x001      | 1    | 0    | 0 (FAIL)  |

- **Critical Signal Values at Failure Point (time = 10 ns)**:
  - `mode` = 1
  - `RegNext(mode)` (implied by REG signals) = 0
  - `cnt` = 0x001
  - `io_i` = 1
  - `mode_stays_one_once_set` = 0 (assertion fails)

## 4. Root Cause Analysis

### Bug Category: **Incorrect Assertion** (assertion_error)

### Buggy Code Location

- **File**: `rgraph.scala`
- **Line**: 31
- **Assertion**:
  ```scala
  assertStableWhen(mode === 1.U, mode, "mode_stays_one_once_set")
  ```

### Description of the Bug

The assertion `assertStableWhen(mode === 1.U, mode, ...)` is semantically incorrect for the intended property "once mode becomes 1, it stays 1 forever."

`assertStableWhen(condition, signal)` works by checking that **when `condition` is true, `signal` equals `RegNext(signal)`** (i.e., the signal's value hasn't changed since the previous cycle). Formally:
```
condition === 1.U  ⇒  signal === RegNext(signal)
```

The problem is that on the **first cycle** where `mode` transitions from 0 to 1:
- The condition `mode === 1.U` is **true** (activates the assertion check)
- The signal `mode` has value **1** (current cycle)
- `RegNext(mode)` has value **0** (from the previous cycle when mode was 0)
- Therefore `mode (1) === RegNext(mode) (0)` is **false**
- The assertion **fails**

This is a false counterexample caused by the assertion checking stability **from the previous cycle to the current cycle**, which inherently fails on the first cycle where the monitored condition becomes true.

### Evidence from Waveform

1. **Time 0 ns**: `mode = 0`, `cnt = 0`, `io_i = 1`. The condition `mode === 0.U && io_i` is true, so `mode` is scheduled to become 1 in the next cycle.

2. **Time 10 ns**: `mode = 1`, `cnt = 1`, `io_i = 1`. The assertion fires because:
   - `mode === 1.U` → true (triggers the check)
   - `mode` (current = 1) ≠ `RegNext(mode)` (from previous cycle = 0)
   - Result: assertion violation

3. The waveform also shows that after transitioning to 1 at time 10 ns, the `mode` signal **does not subsequently change** — there is no transition back to 0. The DUT correctly implements the monotonic mode transition; the bug is purely in the assertion specification.

### Correct Fix

The intended property "once mode becomes 1, it stays 1 forever" should be specified using a **past-looking** operator that checks the invariant based on historical state, not current-state stability:

**Option 1**: Check that mode never transitions from 1 to 0:
```scala
fvAssert(!(past(mode === 1.U) && mode === 0.U), "mode_stays_one_once_set")
```

**Option 2**: Use `assertStableWhen` with an additional condition to exclude the first cycle:
```scala
// Wait one cycle after mode becomes 1 before asserting stability
assertStableWhen(RegNext(mode) === 1.U, mode, "mode_stays_one_once_set")
```

The core issue is that `assertStableWhen` checks **current-to-previous** equality, which is fundamentally incompatible with properties that become true for the first time on the current cycle.
