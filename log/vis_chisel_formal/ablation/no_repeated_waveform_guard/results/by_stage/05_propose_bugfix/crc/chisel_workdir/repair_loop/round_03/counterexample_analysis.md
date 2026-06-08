# Counterexample Analysis Report: `reset_initializes_crc`

## 1. Verification Environment

- **Top module**: `vcrc32_8` (chisel/extra_bench/crc/vcrc32_8.scala)
- **Key components**:
  - `crcReg` — 32-bit CRC register, initialized to `0xFFFFFFFF`
  - `parallel_crc` — combinational function computing the next CRC value from the current CRC and an 8-bit data byte
  - `newCrc` — wire that selects between reset/load/compute/hold paths
  - `ResetCounter` — external module tracking time since reset
- **Design under test**: A CRC-32 computation circuit supporting four operations: reset, load, compute, and hold, all gated by a clock enable signal.

## 2. Violated Assertion

- **Assertion name**: `reset_initializes_crc`
- **Waveform filename**: `vcrc32_8.reset_initializes_crc.fst`
- **Source code** (vcrc32_8.scala, line 124–125):
  ```scala
  assertNextStepWhen(io.reset && io.clken, crcReg === CRC_INITIAL_VALUE,
    "reset_initializes_crc")
  ```
- **Intended property**: When both `io.reset` and `io.clken` are asserted, then on the **next clock cycle** the CRC register should equal `CRC_INITIAL_VALUE` (32'hFFFFFFFF).
- **Generated Verilog SVA** (generated/vcrc32_8.sv):
  ```verilog
  reset_initializes_crc:
      assert property (@(posedge clock) disable iff (~hasBeenReset) &crcReg);
  ```
- **Actual property checked**: On every posedge clock (when `hasBeenReset` is true), the AND-reduction of `crcReg` (`&crcReg`) must be 1 — i.e., **all 32 bits of crcReg must always be 1**.

## 3. Waveform Information

- **Waveform file**: `verilog/extra_bench/crc/vcrc32_8.reset_initializes_crc.fst`
- **Time range**: 0 ns to 20 ns (2 clock cycles at 10 ns period)
- **Key time points**:

| Time | Clock | `crcReg` | `&crcReg` | `reset_initializes_crc` | Key Events |
|------|-------|----------|-----------|------------------------|------------|
| 0 ns | posedge | 0xFFFFFFFF | 1 | **1 (PASS)** | `io_compute=1`, `io_clken=1`, `io_data_in=0x61`, `io_reset=0` |
| 10 ns | posedge | **0xE66C6494** | **0** | **0 (FAIL)** | CRC computation completed; `io_compute` transitions to 0 |

## 4. Root Cause Analysis

### Root Cause: Incorrect Assertion Compilation (Assertion Error)

The `assertNextStepWhen` Chisel formal primitive is **not being compiled correctly** into the SystemVerilog assertion.

**The intended semantics** of the Chisel assertion:
```scala
assertNextStepWhen(io.reset && io.clken, crcReg === CRC_INITIAL_VALUE, "reset_initializes_crc")
```
This should generate an SVA assertion equivalent to:
```verilog
assert property (@(posedge clock) disable iff (~hasBeenReset)
                  $past(io_reset && io_clken) |-> (crcReg == 32'hFFFFFFFF));
```
This checks that **if** the condition `io.reset && io.clken` was true in the previous cycle, **then** `crcReg === CRC_INITIAL_VALUE` in the current cycle.

**What was actually generated**:
```verilog
assert property (@(posedge clock) disable iff (~hasBeenReset) &crcReg);
```
This checks `&crcReg` (all bits of crcReg are 1) on **every** cycle.

**Key discrepancies**:
1. **Condition lost**: The triggering condition `io.reset && io.clken` is completely absent from the generated assertion.
2. **Timing lost**: The "next step" semantics (`$past(...)` or similar delayed check) is absent.
3. **Property simplified**: `crcReg === CRC_INITIAL_VALUE` (32'hFFFFFFFF) was reduced to `&crcReg`, which is mathematically equivalent *only* when CRC_INITIAL_VALUE is all-ones, but the AND-reduction form loses the semantic intent.

### Why the Assertion Fails

Looking at the waveform:

1. **Time 0 ns**: `crcReg = 0xFFFFFFFF` → `&crcReg = 1` → assertion **passes** (signal = 1).
2. **Between time 0 and 10**: Since `io_compute=1` and `io_clken=1`, the CRC computation runs: `newCrc = parallel_crc(0xFFFFFFFF, 0x61) = 0xE66C6494`. On the posedge at time 10, `crcReg` updates to this new value.
3. **Time 10 ns**: `crcReg = 0xE66C6494` → `&crcReg = 0` (because not all bits are 1) → assertion **fails** (signal transitions to 0).

The CRC register correctly computes the next CRC value when `io_compute` and `io_clken` are asserted — this is **correct design behavior**. The assertion should **not** fire in this scenario because the condition `io.reset && io.clken` was never true (io_reset = 0 throughout).

### Conclusion

| Category | Assessment |
|----------|-----------|
| **Bug in DUT?** | ❌ No. The CRC circuit computes correctly. |
| **Incorrect Assertion?** | ✅ **Yes.** The `assertNextStepWhen` primitive fails to compile the condition and timing into the generated SVA, producing a wrong assertion that checks `&crcReg` on every cycle instead of checking the property one cycle after the condition. |
| **Setup Error?** | ❌ No. The TestTop configuration is fine. |

### Fix Recommendation

The assertion needs to be rewritten to generate the correct SVA directly. Replace the `assertNextStepWhen` call with an explicit check that properly captures the condition and next-step timing. For example:

```scala
val resetAndClken = io.reset && io.clken
val prevCond = RegNext(resetAndClken, false.B)
fvAssert(!prevCond || (crcReg === CRC_INITIAL_VALUE), "reset_initializes_crc")
```

This explicitly delays the condition by one cycle using a register and checks the property when the condition was true in the previous cycle, correctly implementing the intended "next step when" semantics.
