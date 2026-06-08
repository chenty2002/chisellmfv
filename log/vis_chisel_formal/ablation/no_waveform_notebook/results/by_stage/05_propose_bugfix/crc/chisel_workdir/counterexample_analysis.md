# Counterexample Analysis Report: crc_stable_no_clken

## 1. Verification Environment

- **Top Module**: `vcrc32_8` (Chisel module with `Formal` mixin)
- **Design Under Test**: A parallel CRC-32 computation module with clock enable, load, compute, and reset control signals
- **Key Components**:
  - `crcReg`: 32-bit register holding the CRC state, initialized to `0xFFFFFFFF`
  - `parallel_crc()`: Combinational function computing the next CRC value from current state and input data
  - Control: `io.clken` (clock enable), `io.load` (load data), `io.compute` (compute CRC), `io.reset`
- **Input Protocol**: `load` and `compute` are mutually exclusive (enforced by `assume` constraint)

## 2. Violated Assertion

- **Assertion Name**: `crc_stable_no_clken` (from waveform filename `vcrc32_8.crc_stable_no_clken.fst`)
- **File Location**: `vcrc32_8.scala`, line 126
- **Code Snippet**:
  ```scala
  // --- Safety: CRC register stability when clock enable is low ---
  // crcReg must not change when clken is deasserted
  assertStableWhen(!io.clken, crcReg, "crc_stable_no_clken")
  ```
- **Natural Language Description**: The assertion verifies that when the clock enable signal `io.clken` is deasserted (low), the CRC register `crcReg` must not change its value from the previous clock cycle.

## 3. Waveform Information

- **Waveform File**: `verilog/extra_bench/crc/vcrc32_8.crc_stable_no_clken.fst`
- **Time Range**: 0 ns → 20 ns (2 cycles)
- **Failure Time**: 10 ns (cycle 1)

### Signal Values at Key Time Points

| Signal | Time 0ns (Cycle 0) | Time 10ns (Cycle 1) |
|--------|-------------------|--------------------|
| `vcrc32_8.io_clken` | 1 | **0** (transitions at 10ns) |
| `vcrc32_8.io_load` | 1 | 1 |
| `vcrc32_8.io_reset` | 0 | 0 |
| `vcrc32_8.io_compute` | 0 | 0 |
| `vcrc32_8.io_data_in [7:0]` | 0xEF | 0xEF |
| `vcrc32_8.crcReg [31:0]` | **0xFFFFFFFF** | **0xFFFFFFEF** (changed) |
| `vcrc32_8.io_crc [31:0]` | 0xFFFFFFFF | 0xFFFFFFEF |
| `vcrc32_8.io_data_out [7:0]` | 0x00 | 0x00 |
| `vcrc32_8.crc_stable_no_clken` | **1** (passing) | **0** (failing) |

### Timing of Key Events

Both `io_clken` and `crcReg` transition at exactly time **10 ns**:
- `io_clken`: 1 → 0
- `crcReg`: `0xFFFFFFFF` → `0xFFFFFFEF`

## 4. Root Cause Analysis

### Classification: **Incorrect Assertion (assertion_error)**

The assertion `assertStableWhen(!io.clken, crcReg, "crc_stable_no_clken")` is incorrectly specified for the design's behavior. The assertion generates a **false positive** counterexample.

### Detailed Explanation

**Sequence of events:**

1. **Cycle 0 (time 0ns)**: `io.clken` = 1, `io.load` = 1, `io.reset` = 0
   - `newCrc` = `Cat(crcReg(23,0), io.data_in)` = `Cat(0xFFFFFF, 0xEF)` = `0xFFFFFFEF`
   - Since `io.clken` = 1, `crcReg` is enabled and captures `newCrc = 0xFFFFFFEF`

2. **Cycle 1 (time 10ns)**: `io.clken` = 0 (transitions from 1 to 0)
   - `crcReg` now holds the value `0xFFFFFFEF` that was captured at cycle 0
   - The `assertStableWhen` checker evaluates: `!io.clken` = `!0` = 1 (true)
   - It checks: `crcReg` at cycle 1 should equal `crcReg` at cycle 0
   - `0xFFFFFFEF` ≠ `0xFFFFFFFF` → **assertion fails**

### Why This is an Assertion Error

The assertion checks: "when `!io.clken` is true, `crcReg` must equal its value from the previous cycle." This is equivalent to saying "`crcReg` must not change on any clock edge where `io.clken` is low."

However, the register change was **legitimately initiated in cycle 0** when `io.clken` was **high (1)** and `io.load` was asserted. The register captured `newCrc` at the cycle 0 clock edge, and the new value propagates to cycle 1. At cycle 1, `io.clken` transitions to 0, but the register value has already been updated due to the cycle 0 operation.

The `crcReg` register is working correctly:
- It captures new values only when `io.clken` is 1 (enabled)
- It holds values when `io.clken` is 0 (disabled)

The bug is in the timing semantics of the assertion. `assertStableWhen` checks "current-cycle condition implies previous-to-current cycle stability", which is violated when a register update was initiated in the previous cycle when the condition was false.

### Correct Assertion

The correct property to verify is: **"crcReg should only change value when io.clken is asserted"** — checking backwards from the change to the condition, rather than forwards from the condition to the change.

This can be expressed as:
```scala
// If crcReg changed from the previous cycle, then io.clken must have been asserted
fvAssert(!(crcReg === RegNext(crcReg)) || io.clken, "crc_only_changes_when_clken_enabled")
```

Or equivalently, the existing assertion `assertStableWhen(!io.clken, crcReg, ...)` should be removed because it generates a false positive for the scenario where the register was enabled in the previous cycle and then disabled in the current cycle.

### Evidence Summary

| Evidence | Value |
|----------|-------|
| Cycle 0 `io.clken` | 1 (HIGH) |
| Cycle 0 `io.load` | 1 (asserted) |
| Cycle 0 `newCrc` | `0xFFFFFFEF` (correct load result) |
| Cycle 1 `io.clken` | 0 (LOW) — assertion condition triggers |
| Cycle 1 `crcReg` | `0xFFFFFFEF` (legitimate update from cycle 0) |
| DUT register behavior | Correct — updates when enabled, holds when disabled |
| Root cause | Assertion timing semantics incompatible with registered enable behavior |
