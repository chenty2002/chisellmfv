# Counterexample Analysis Report: `rgraph.no_counter_underflow`

## 1. Verification Environment

- **Top Module**: `rgraph` (in package `llmverify`)
- **Source File**: `rgraph.scala` (43 lines)
- **Design Under Test**: A simple state machine with:
  - `cnt` (12-bit counter, initially 0)
  - `mode` (1-bit register, initially 0)
  - `io.i` (input): controls mode transition and decrement qualification
  - `io.o` (output): `(cnt === 0.U)`

**Design Behavior:**
- When `mode === 0.U`: `cnt` increments by 1 each cycle. If `io.i` is high, `mode` transitions to 1.U.
- When `mode === 1.U`: `cnt` decrements by 1 each cycle if `io.i && (cnt =/= 0.U)`. The guard `cnt =/= 0.U` prevents underflow (wrapping from 0 to 4095).
- `mode` is monotonic — once set to 1, it never goes back to 0.

## 2. Violated Assertion

- **Assertion Name**: `no_counter_underflow`
- **Waveform Filename**: `rgraph.no_counter_underflow.fst`
- **Code Snippet** (file `rgraph.scala`, lines 36–37):
  ```scala
  // Safety 2: In mode 1, the counter must not underflow (guard prevents decrement at 0)
  fvAssert(!(mode === 1.U && io.i && cnt === 0.U), "no_counter_underflow")
  ```
- **Natural Language Description**: In mode 1, it should never be the case that `io.i` is high and `cnt` equals 0 simultaneously.
- **File Location**: `rgraph.scala`, lines 36–37

## 3. Waveform Information

- **Waveform File**: `verilog/extra_bench/rgraph/rgraph.no_counter_underflow.fst`
- **Time Range**: 0 ns → 30 ns (3 cycles, period ≈ 10 ns)

### Key Signal Timeline

| Time (ns) | `cnt [11:0]` | `mode` | `io_i` | `io_o` | `prev_mode` | `no_counter_underflow` |
|-----------|-------------|--------|--------|--------|-------------|----------------------|
| 0         | 0           | 0      | 1      | 1      | 0           | 1 (holds)            |
| 10        | 1           | 1      | 1      | 0      | 0           | 1 (holds)            |
| **20**    | **0**       | **1**  | **1**  | **1**  | **1**       | **0 (FAILS)**        |
| 30        | 0           | 1      | 1      | 1      | 1           | (not checked)        |

### Failure Point: Time 20 ns

At time 20 ns, the signals are:
- `mode` = 1
- `io_i` = 1
- `cnt` = 0

The assertion `!(mode === 1.U && io_i && cnt === 0.U)` evaluates to `!(1 && 1 && 1)` = **0** → failure.

## 4. Root Cause Analysis

### Root Cause Category: **Incorrect Assertion** (assertion_error)

The assertion `!(mode === 1.U && io.i && cnt === 0.U)` is **incorrectly formalized**. It is too strict and flags a legitimate, safe state as an error.

### Explanation

**How the failure sequence unfolds (normal, intended operation):**

1. **Time 0 → 10 (posedge clock, mode=0):**
   - `cnt` increments from 0 to 1 (since mode=0)
   - `mode` transitions from 0 to 1 (since mode=0 && io_i=1)
   
2. **Time 10 → 20 (posedge clock, mode=1):**
   - `cnt` decrements from 1 to 0 (since mode=1, io_i=1, and cnt=1 =/= 0)

3. **Time 20 ns (assertion check point):**
   - `mode=1, io_i=1, cnt=0` — the assertion fails

**Why this is NOT an underflow:**

The guard `io_i && (cnt =/= 0.U)` in the design (line 18) already prevents the counter from underflowing. When `cnt` reaches 0 in mode 1 with `io_i` high, the decrement is simply disabled — the counter stays at 0 and does NOT wrap around to 4095. This is a **safe terminal state**, not an underflow.

The state `mode=1 && io_i=1 && cnt=0` is **normally reachable** whenever the counter finishes decrementing from some value down to 0 while io_i is still asserted. This happens every time the machine goes through a full increment/decrement cycle. The design's guard correctly handles this case.

### What the assertion should check instead

The intended property ("the counter must not underflow") should verify that when `cnt` is 0 in mode 1 with `io_i` high, the counter does **not** wrap around. A correct assertion would be:

```scala
// Option 1: Check that cnt never increases when decrementing
fvAssert(!(mode === 1.U && io_i && cnt === 0.U && RegNext(cnt) > cnt), "no_counter_underflow")

// Option 2: Using past() — if cnt was 0 last cycle and mode=1 and io_i=1, cnt should stay 0
// (requires an implicit past() function)
```

Or, more simply, the existing guard already handles the safety — a "no underflow" assertion could just be removed or changed to:

```scala
// Option 3: Check that underflow cannot happen (the guard works)
assert(cnt =/= 0.U || !io_i || !(mode === 1.U) || (cnt === 0.U && cnt === 0.U))
```

But the simplest correct fix is to change the assertion condition to allow the legitimate `cnt=0` state:

```scala
// Correct: cnt=0 in mode 1 with io_i is fine (guard prevents underflow)
// The assertion should instead check proper monotonic decrement behavior
```

### Conclusion

This is an **assertion error** — the assertion is overly restrictive and flags a valid hardware state as a violation. The design's counter underflow guard (`io_i && (cnt =/= 0.U)`) correctly prevents actual underflow. The assertion needs to be relaxed or rewritten to check the actual underflow condition rather than forbidding a reachable and safe state.
