# Counterexample Analysis: `after_wrap_count_advances`

## 1. Verification Environment

- **Top Module**: `ResetModule` (from `reset.scala`, line 12)
- **Generated Verilog**: `generated/ResetModule.sv`
- **Waveform**: `verilog/extra_bench/reset/ResetModule.after_wrap_count_advances.fst`
- **Key Components**:
  - `count` (reg [7:0]): 8-bit counter register, initialized to 0 on reset
  - `wrap_detected` (reg): flag set to true when counter reaches/exceeds `io_max`
  - `io.en`: enable input (active high)
  - `io.max`: 8-bit max-value input (can change dynamically)
  - `io.out`: combinational output = count
  - `io.wrap`: combinational output = wrap_detected
- **Design Description**: A simple counter that increments each cycle when enabled, wraps to 0 when reaching/exceeding `io_max`, and asserts a `wrap_detected` flag on wrap events.

## 2. Violated Assertion

- **Assertion Name**: `after_wrap_count_advances`
- **Source Location**: `reset.scala`, line 79
- **Chisel Source**:
  ```scala
  fvAssert(!(RegNext(io.wrap) && !io.en && count === io.max), "after_wrap_count_advances")
  ```
- **Generated Verilog** (lines 102-104):
  ```verilog
  after_wrap_count_advances:
      assert property (@(posedge clock) disable iff (~hasBeenReset)
                       ~(REG & ~io_en & _GEN_0));
  ```
  where `REG` stores `wrap_detected` sampled from the previous cycle (`RegNext(io.wrap)`), and `_GEN_0 = count == io_max`.
- **Property Description**: The assertion states that it should never be the case that:
  1. The previous cycle had a wrap event (`RegNext(io.wrap) = true`), AND
  2. The current cycle has the counter disabled (`io.en = false`), AND
  3. The current count equals the max value (`count === io.max`).
  
  In other words: after a wrap occurs, if the counter is then disabled, the count should not be equal to the max value — the count should have "advanced" past the wrap point.

## 3. Waveform Information

- **Waveform File**: `verilog/extra_bench/reset/ResetModule.after_wrap_count_advances.fst`
- **Waveform Duration**: 30 ns (3 clock cycles, clock period = 10 ns)
- **Key Time Points**:
  - `t=0 ns`: Initial state (clock high)
  - `t=10 ns`: **First posedge (Cycle 0)** — inputs sampled, logic evaluated
  - `t=20 ns`: **Second posedge (Cycle 1)** — inputs sampled, logic evaluated; **assertion fails immediately after**
  - `t=25 ns`: Assertion value = 0 (failed), REG = 1, io_en = 0, count = 1, io_max = 1, _GEN_0 (count==io_max) = 1

### Signal Timeline

| Time | Clock | count | io_max | io_en | wrap_detected | io_wrap | REG (RegNext) | _GEN_0 | Assertion |
|------|-------|-------|--------|-------|---------------|---------|---------------|--------|-----------|
| 0    | 1     | 0x00  | 0x00   | 1     | 0             | 0       | 0             | 1      | 1 (pass)  |
| 5    | 0     | 0x00  | 0x00   | 1     | 0             | 0       | 0             | 1      | 1 (pass)  |
| 10   | 1     | 0x00  | 0x01   | 1     | 1             | 1       | 0             | 0      | 1 (pass)  |
| 15   | 0     | 0x00  | 0x01   | 1     | 1             | 1       | 0             | 0      | 1 (pass)  |
| 20   | 1     | 0x01  | 0x01   | 0     | 0             | 0       | 1             | 1      | 0 (**FAIL**) |
| 25   | 0     | 0x01  | 0x01   | 0     | 0             | 0       | 1             | 1      | 0 (**FAIL**) |

### Failure Point (t=20 ns, after posedge)
At the assertion evaluation point:
- `REG` (RegNext of io.wrap) = **1** — captured wrap_detected=1 from cycle 0
- `io.en` = **0** — counter disabled
- `count == io_max` → 1 == 1 → **true**
- Assertion condition: `~(1 & 1 & 1)` = **0 → FAILURE**

## 4. Root Cause Analysis

### Error Classification: **Incorrect Assertion**

The assertion is incorrectly formulated for a design where `io_max` is a dynamically-changing input.

### Detailed Sequence of Events

The formal solver found the following counterexample trace:

1. **Cycle 0 (t=10 ns posedge)**:
   - State before edge: `count=0`, `io_max=0` (changes to 1 after edge? or 0 at edge-time), `io_en=1`
   - Counter logic: `count (0) >= io_max (0)` → **true** → wrap occurs
   - **Result**: `count <= 0` (stays at 0), `wrap_detected <= 1`
   - io_max then changes to 0x01 (1) some time after the posedge sampling

2. **Cycle 1 (t=20 ns posedge)**:
   - State before edge: `count=0`, `io_max=1`, `io_en=1`
   - **REG <= wrap_detected** → REG captures old `wrap_detected = 1`
   - Counter logic: `count (0) >= io_max (1)` → **false** → normal increment
   - **Result**: `count <= 1`, `wrap_detected <= 0`
   - io_en then changes to 0 some time after the posedge

3. **After Cycle 1 (t=20+ ns)**:
   - `REG = 1` (previous wrap detected), `io_en = 0` (disabled), `count = 1 = io_max = 1`
   - **Assertion fires**: `!(1 & 1 & 1)` = 0 → **fails**

### Why the Assertion is Incorrect

The assertion assumes `io_max` is stable across cycles. However, `io_max` is an unconstrained input that can change at any time. The counterexample exploits this:

- The wrap occurred when `io_max` was **0** (cycle 0)
- After wrapping, `io_max` changed to **1**
- The counter correctly advanced to 1 (not stuck), but 1 happens to equal the **new** io_max value
- When `io_en` goes low, the assertion checks `count == io_max` using the **current** io_max value (1), not the one at wrap time (0)

The design's behavior is **correct**:
- The counter correctly wraps when reaching `io_max`
- The counter correctly increments when enabled and below `io_max`
- The counter correctly holds its value when disabled

The assertion is **too strict** — it should either:
- Constrain `io_max` to be stable (e.g., `assume(io_max === Past(io_max))`), or
- Check `count` against the `io_max` value at the time of wrapping, not the current value, or
- Remove this assertion if the dynamic `io_max` behavior is intentional

### Buggy Code Location

- **File**: `reset.scala`, line 79
- **Code**:
  ```scala
  fvAssert(!(RegNext(io.wrap) && !io.en && count === io.max), "after_wrap_count_advances")
  ```
- **Problem**: The assertion compares `count` against the **current** `io.max` value, but the wrap event occurred using a potentially different (earlier) `io.max` value. When `io.max` decreases (as in this trace, from 1 to... actually here io_max increases from 0 to 1, making the post-wrap count equal the new max), the assertion can false-fire on correct design behavior.

### Fix Recommendation

To fix this assertion, one of the following approaches should be taken:

1. **Make io_max stable** — Add a constraint that `io_max` never changes:
   ```scala
   fvAssume(Past(io.max) === io.max, "io_max_stable") // or use assume(io_max === Past(io_max))
   ```

2. **Fix the assertion** — Use the delayed `io_max` value from the wrap cycle instead of the current one:
   ```scala
   // Check that after wrapping, count is less than the max AT WRAP TIME (not current max)
   fvAssert(!(RegNext(io.wrap) && !io.en && count === RegNext(io.max)), "after_wrap_count_advances")
   ```

3. **Remove the assertion** — If `io_max` is intended to be dynamically controllable, this assertion is not meaningful and should be removed.
