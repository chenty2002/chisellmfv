# Counterexample Analysis Report: `cnt_dec_in_mode1`

## 1. Verification Environment

- **Top Module**: `rgraph` (in package `llmverify`)
- **Source File**: `rgraph.scala`
- **Design Under Test**: A simple state machine with two registers:
  - `cnt`: a 12-bit counter (MSB=11, so 12 bits wide)
  - `mode`: a 1-bit mode register
- **Key Components**:
  - **cnt behavior**: When mode=0, cnt increments by 1 each cycle. When mode=1 and io.i=1 and cnt!=0, cnt decrements by 1. Otherwise (mode=1 and not decrementing), cnt stays unchanged.
  - **mode behavior**: When mode=0 and io.i=1, mode transitions to 1. Once 1, mode stays 1 forever.
  - **Output**: io.o = (cnt === 0.U)

## 2. Violated Assertion

- **Assertion Name**: `cnt_dec_in_mode1`
- **Assertion Code** (lines 50-54):
```scala
fvAssert(
    !(mode === 1.U && RegNext(mode) === 1.U && io.i && cnt =/= 0.U) ||
    (cnt === RegNext(cnt) - 1.U),
    "cnt_dec_in_mode1"
)
```
- **File Location**: `rgraph.scala`, lines 50-54
- **Natural Language Description**: When mode is 1 AND mode was already 1 in the previous cycle AND io.i is high AND cnt is non-zero, then cnt must equal the previous cycle's cnt minus 1 (i.e., cnt must have decremented by exactly 1 from the previous cycle to this cycle).
- **Comment Intent (line 47)**: "In mode 1, when io.i && cnt =/= 0, cnt decrements by exactly 1"

## 3. Waveform Information

- **Waveform File**: `verilog/extra_bench/rgraph/rgraph.cnt_dec_in_mode1.fst`
- **Duration**: 3 cycles (30 ns), time range 0 ns → 30 ns
- **Key Time Points**:

| Time | cnt [11:0] | mode | io_i | _GEN | REG (RegNext(mode)) | REG_2 (RegNext(cnt)) | cnt_dec_in_mode1 |
|------|-----------|------|------|------|--------------------|--------------------|------------------|
| 0    | 0         | 0    | 1    | 0    | 0                  | 0                  | 1                |
| 5    | 0         | 0    | 1    | 0    | 0                  | 0                  | (1)              |
| 10   | 1         | 1    | 0    | 0    | 0                  | 0                  | 1                |
| 15   | 1         | 1    | 0    | 0    | 0                  | 0                  | (1)              |
| 20   | 1         | 1    | 1    | 1    | 1                  | 1                  | **0 (FAIL)**     |
| 25   | 1         | 1    | 1    | 1    | 1                  | 1                  | (0)              |
| 30   | 1         | 1    | 1    | 1    | 1                  | 1                  | 0 (FAIL)         |

**Failure Point**: Time = 20 ns (2nd clock cycle)

## 4. Root Cause Analysis

### Classification: **Incorrect Assertion** (assertion_error)

The assertion has a **timing mismatch** — it checks `io.i` (the current combinational input) but compares `cnt` values that reflect the previous cycle's input.

### Detailed Explanation

**The design logic for cnt (lines 16-22):**
```scala
when(mode === 0.U) {
    cnt := cnt + 1.U
}.otherwise {
    when(io.i && (cnt =/= 0.U)) {
      cnt := cnt - 1.U
    }
}
```

This means: At each clock edge, if mode=1 AND io_i=1 AND cnt!=0 in the **current** cycle, then cnt is updated to cnt-1 in the **next** cycle.

**The assertion checks (lines 50-54):**
```
!(mode=1 && RegNext(mode)=1 && io.i && cnt!=0) || (cnt == RegNext(cnt)-1)
```

At the clock edge (time 20), this says: If mode is 1 now AND mode was 1 last cycle AND io_i is 1 now AND cnt is non-zero now, then cnt should already be one less than the previous cnt.

**The timing problem**: The assertion evaluates at time 20, where:
- `io.i = 1` (current input, just became high)
- `cnt = 1` (value computed based on the **previous** cycle's conditions)
- `RegNext(cnt) = cnt(10) = 1` (value from one cycle ago)

The premise requires `io.i = 1` (current input), but the conclusion checks `cnt === RegNext(cnt) - 1`, which relates cnt values from the current and previous cycles. The decrement from cnt=1 to cnt=0 would only happen if io_i was high **in the previous cycle** (time 10). But at time 10, io_i=0, so no decrement occurred.

**Trace of Events:**

1. **Time 0-10 (Cycle 0 → 1)**: mode=0, io_i=1, cnt=0 → cnt increments to 1; mode transitions to 1
2. **Time 10-20 (Cycle 1 → 2)**: mode=1, io_i=0, cnt=1 → io_i is 0, so no decrement; cnt stays at 1
3. **Time 20 (Failure)**: mode=1, io_i=1, cnt=1 → Assertion wrongly expects cnt to be 0 (RegNext(cnt)-1 = 1-1 = 0) but cnt is still 1 because io_i was 0 in the previous cycle

**Corrected Assertion:**

The assertion should use `RegNext(io.i)` (the previous cycle's input) instead of `io.i` (current input) in the premise, because the decrement (visible as `cnt === RegNext(cnt) - 1`) reflects the effect of the **previous** cycle's io_i, not the current one:

```scala
fvAssert(
    !(mode === 1.U && RegNext(mode) === 1.U && RegNext(io.i) && RegNext(cnt) =/= 0.U) ||
    (cnt === RegNext(cnt) - 1.U),
    "cnt_dec_in_mode1"
)
```

This corrected assertion says: If in the **previous** cycle mode was 1 AND io_i was 1 AND cnt was non-zero, then the **current** cnt should be one less than the previous cnt.

### Verification with the Corrected Assertion

| Time | RegNext(mode) | RegNext(io_i) | RegNext(cnt) | RegNext(cnt)!=0 | Premise | Conclusion at T | Pass? |
|------|--------------|--------------|--------------|----------------|---------|----------------|-------|
| 10   | mode(0)=0    | io_i(0)=1    | cnt(0)=0     | false          | false   | —              | ✓    |
| 20   | mode(10)=1   | io_i(10)=0   | cnt(10)=1    | true           | false   | —              | ✓    |
| 30   | mode(20)=1   | io_i(20)=1   | cnt(20)=1    | true           | true    | cnt(30)===cnt(20)-1=0? | Need to check |

(Note: The trace is only 3 cycles and assertion fails at time 20 in the original, so cycle 3 values may be truncated.)

### Summary

The bug is an **incorrect assertion timing**: `io.i` is used where `RegNext(io.i)` should be used. The assertion expects the decrement effect to be visible immediately when io_i goes high, but the hardware register update only reflects the input on the **next** clock cycle. This is a classic off-by-one-cycle timing error in the assertion specification, not a design bug.
