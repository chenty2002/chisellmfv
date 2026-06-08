# Counterexample Analysis Report: Rotate4 Assertion Failure

## 1. Verification Environment

- **Top module**: `rotate` (from `rotate4.scala`)
- **Module type**: Chisel Module with Formal (ChiselFv)
- **Key components**:
  - `inr` (RegInit): Input register, captures `io.din` on each clock edge
  - `dout` (RegInit): Output register, captures barrel shifter result on each clock edge
  - Barrel shifter: Two-stage MUX chain (`tmp1`, `tmp2`) implementing right rotation by `io.amount`
- **Pipeline delay**: `io.din → inr (1 cycle) → barrel shifter(tmp2) → dout (1 cycle) → io.dout`
  Total: 2 cycles from input change to visible output change
- **Formal verification framework**: ChiselFv with `resetCounter` (tracks reset state)

## 2. Violated Assertion

- **Assertion name**: `output_stable_when_inputs_stable`
- **Location**: `rotate4.scala`, lines 48-51
- **Code snippet**:
  ```scala
  val inputsStable = io.din === RegNext(io.din) && io.amount === RegNext(io.amount)
  val inputsStableDelayed = RegNext(inputsStable)
  when(inputsStableDelayed) {
    fvAssert(io.dout === RegNext(io.dout), "output_stable_when_inputs_stable")
  }
  ```
- **Property description**: When both `io.din` and `io.amount` have been stable (unchanged) for at least 2 consecutive cycles, the output `io.dout` should also remain stable (unchanged). The `inputsStableDelayed` register adds a 1-cycle delay to account for the 2-cycle pipeline latency.

## 3. Waveform Information

- **Waveform file**: `verilog/extra_bench/rotate_rotate4/rotate.output_stable_when_inputs_stable.fst`
- **Time range**: 0 ns – 30 ns (3 clock cycles, period = 10 ns)
- **Key time points and signal values**:

| Time | io_din | io_amount | inr | dout | io_dout | Assertion |
|------|--------|-----------|-----|------|---------|-----------|
| 0    | 0001   | 10        | 0000| 0000 | 0000    | 1 (pass)  |
| 5    | 0001   | 10        | 0000| 0000 | 0000    | 1 (pass)  |
| 10   | 1011   | 00        | 0001| 0000 | 0000    | 1 (pass)  |
| 15   | 1011   | 00        | 0001| 0000 | 0000    | 1 (pass)  |
| **20**| **1011**| **00**   | **1011**| **0001**| **0001**| **0 (FAIL)**|
| 25   | 1011   | 00        | 1011| 0001 | 0001    | 0 (fail)  |
| 30   | 1011   | 00        | 1011| 0001 | 0001    | 0 (fail)  |

- **Clock edges**: time 0 (rising), time 10 (rising), time 20 (rising)
- **Reset**: Never asserted (always 0)
- **Failure time**: The assertion transitions from 1 (passing) to 0 (failing) between time 15 and time 20, failing at the clock edge of time 20

## 4. Root Cause Analysis

### Root Cause: Assertion Bug — Incorrect Input Stability Detection

**Error type**: `assertion_error`

**Bug location**: `rotate4.scala`, line 48

```scala
val inputsStable = io.din === RegNext(io.din) && io.amount === RegNext(io.amount)
```

**Description of the bug**:

The assertion uses `io.din === RegNext(io.din)` to check whether `io.din` has remained unchanged from the previous cycle. However, in formal verification semantics (which treats all register updates as simultaneous at the clock edge), `RegNext(io.din)` captures the **new** value of `io.din` at the same time as the comparison is evaluated. This means:

- When `io.din` changes at the clock edge (which is standard in formal verification), `RegNext(io.din)` captures the **new** value of `io.din`.
- The comparison `io.din === RegNext(io.din)` compares the new value against itself, always returning `true`.
- Therefore, `inputsStable` is computed as `true` even though the inputs **actually changed**.

**Evidence from waveform tracing**:

1. **At time 10** (clock edge), `io.din` changes from `0001 → 1011` and `io.amount` changes from `10 → 00`.
   - If `RegNext(io.din)` correctly held the old value (`0001`), then `inputsStable` should be:
     `(1011 === 0001) && (00 === 10) = false && false = false`
   - `inputsStableDelayed` would then capture `false`, and the assertion at time 20 would pass.
   - **However**, the assertion fails at time 20, proving that `inputsStableDelayed` captured `true` instead.

2. **At time 20** (failure point):
   - `io.dout` changes from `0000 → 0001` (because `inr` was updated to `1011` at time 10, and the barrel shifter result propagated through `tmp2` to `dout` at time 20).
   - `RegNext(io.dout)` holds the old value `0000` (captured at time 10).
   - The check `io.dout === RegNext(io.dout)` evaluates to `0001 === 0000 = false`, causing the assertion to fail.
   - But this failure is **spurious**: the output changed because the inputs changed, which is correct DUT behavior.

3. **The pipeline trace confirms correct DUT behavior**:
   - Time 0: `io.din=0001` → at time 10: `inr=0001` → at time 20: `dout=0001` (rotated by amount `10` which is `0` after time 10)
   - Time 10: `io.din=1011` → at time 20: `inr=1011` → at time 30: `dout` would reflect rotation of `1011` by amount `00`
   
   The data flows correctly through the pipeline. The DUT has no bug.

### Why the Assertion is Incorrect

The root cause is a mismatch between what the assertion checks and how formal verification evaluates it:

- **Intended semantics**: "Check that `io.din` at the current cycle equals `io.din` from the previous cycle."
- **Actual formal semantics**: "Check that `io.din` at the current cycle equals the value that `RegNext(io.din)` captures at this same clock edge." Since `RegNext` captures the current value, the comparison is always `true` when `io.din` is present.

This is a classic pitfall in formal verification: comparing a signal against its own `RegNext` version to detect changes does not work when the value can change at the clock edge.

### Fix

Replace the direct comparison with a comparison between two consecutive registered values:

```scala
// Before (buggy):
val inputsStable = io.din === RegNext(io.din) && io.amount === RegNext(io.amount)

// After (fixed):
val dinPrev = RegNext(io.din)
val amountPrev = RegNext(io.amount)
val inputsStable = dinPrev === RegNext(dinPrev) && amountPrev === RegNext(amountPrev)
```

This fix compares `dinPrev` (the registered value from the previous cycle) against `RegNext(dinPrev)` (the value from two cycles ago). Since both are registered values that are **already in the register before the current clock edge**, the comparison correctly detects changes.

With the fix:
- At time 10, `dinPrev` captures `io.din=1011`, but `RegNext(dinPrev)` holds `0001` (captured at time 0), so `inputsStable = false`.
- `inputsStableDelayed` captures `false`, and the assertion at time 20 does not fire.
- At time 20, if inputs stay at `1011`, then `dinPrev=1011`, `RegNext(dinPrev)=1011`, so `inputsStable = true`.
- At time 30, `inputsStableDelayed=true`, `io.dout` should be stable (unchanged from time 20), and the assertion passes.
