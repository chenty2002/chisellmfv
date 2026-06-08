# Counterexample Analysis Report: `crc_stable_when_clken_off`

## 1. Verification Environment

- **Top Module**: `vcrc32_8` (Chisel module with Formal mixin)
- **Design Under Test**: A CRC-32 computation engine with the following interfaces:
  - `io.clken` (Bool): Clock enable, gates register updates
  - `io.reset` (Bool): Resets CRC register to initial value `0xFFFFFFFF`
  - `io.load` (Bool): Loads data into lower 8 bits of CRC register (shift-based)
  - `io.compute` (Bool): Computes next CRC value using parallel CRC-32 logic
  - `io.data_in` (UInt(8.W)): Input data byte
  - `io.data_out` (UInt(8.W)): Output = `~crcReg(31, 24)`
  - `io.crc` (UInt(32.W)): Output = current CRC register value
  - `io.crc_ok` (Bool): True when `crcReg === 0xC704DD7B` (CRC_REMAINDER)

- **Key Internal Component**: `crcReg`, a 32-bit register initialized to `0xFFFFFFFF`, updated only when `io.clken` is asserted.

## 2. Violated Assertion

- **Assertion Name**: `crc_stable_when_clken_off`
- **Assertion Label (from waveform)**: `crc_stable_when_clken_off`
- **Source File**: `vcrc32_8.scala`
- **Line Number**: 113

**Code snippet (lines 108-113):**
```scala
// Safety: CRC register must be stable when clock enable is deasserted
// The register update is gated by clken, so the value must persist
// when clken is low
assertStableWhen(!io.clken, crcReg, "crc_stable_when_clken_off")
```

**Property Description:**
The assertion checks that the CRC register (`crcReg`) does not change when the clock enable (`io.clken`) is deasserted (low). Since register updates are gated by `clken`, the value should persist unchanged when `clken` is low.

**How Chisel-FV's `assertStableWhen` works:**
When the condition `!io.clken` is true at clock cycle `t`, the assertion checks that `crcReg(t) == crcReg(t-1)` — i.e., the signal should equal its value in the immediately preceding clock cycle.

## 3. Waveform Information

- **Waveform File**: `verilog/extra_bench/crc/vcrc32_8.crc_stable_when_clken_off.fst`
- **Waveform Duration**: 2 cycles (0–20 ns)
- **Clock Period**: 10 ns (rising edges at times 0 ns and 10 ns)

### Critical Signal Timeline

| Time (ns) | Clock | io_clken | crcReg | crc_stable_when_clken_off | Event |
|-----------|-------|----------|--------|--------------------------|-------|
| 0         | 1     | 1        | 0xFFFFFFFF | 1 | Rising edge, clken=1 sampled, crcReg updated to 0xF1A90FFF |
| 5         | 0     | 1        | 0xFFFFFFFF | 1 | Clock low, crcReg value still old in combinational path |
| 10        | 1     | 0        | 0xF1A90FFF | **0** (FAIL) | Rising edge, clken=0 sampled, assertion fires, sees crcReg changed |
| 15        | 0     | 0        | 0xF1A90FFF | 0 | Clock low, value stable |
| 20        | 0     | 0        | 0xF1A90FFF | 0 | Still failed |

### Key Observation

- At **time 0** (cycle 0): `io_clken=1`, `io_compute=1`, `io_data_in=0x64`. The CRC computation `parallel_crc(0xFFFFFFFF, 0x64)` produces a new value `0xF1A90FFF`. Since `clken=1` at the rising edge, `crcReg` is updated to `0xF1A90FFF` on this clock edge.
- At **time 10** (cycle 1): `io_clken=0`. The assertion `assertStableWhen(!io.clken, crcReg)` checks `crcReg(cycle 1) == crcReg(cycle 0)`, i.e., `0xF1A90FFF == 0xFFFFFFFF` → **false**.

**The assertion fails because `crcReg` was legitimately updated at cycle 0 (when clken was 1), and the change is visible at cycle 1 (when clken is first deasserted).**

## 4. Root Cause Analysis

### Issue Type: **Incorrect Assertion** (assertion is too strict)

The assertion `assertStableWhen(!io.clken, crcReg, "crc_stable_when_clken_off")` is overly strict. It requires that the first cycle where `clken` transitions from 1→0 shows no change in `crcReg`. However, `crcReg` was legitimately updated at the *previous* clock edge (cycle 0) when `clken` was 1.

### Detailed Explanation

1. **Register Update Timing**: In a synchronous design, register updates take effect on the rising edge of the clock. At cycle 0 (time 0 ns), `clken=1` and `compute=1`, so `crcReg` is updated from `0xFFFFFFFF` to `0xF1A90FFF`. This new value settles and is available starting at cycle 1.

2. **Assertion Semantics**: `assertStableWhen(!io.clken, crcReg)` checks at each cycle `t` where `!io.clken(t)` is true: `crcReg(t) == crcReg(t-1)`. At cycle 1: `io.clken(1)=0`, so `!io.clken(1)=1`. The check becomes: `crcReg(1)=0xF1A90FFF` vs `crcReg(0)=0xFFFFFFFF` → mismatch.

3. **The Bug**: The assertion should give one clock cycle of "settling time" — it should only check stability when `clken` has been low for **two consecutive cycles**. The property's comment says "The register update is gated by clken, so the value must persist when clken is low" — and indeed the value *does* persist once clken is low. The problem is that the value changed on the *previous* cycle (when clken was high), and this first cycle of clken=low incorrectly triggers the stability check against the old pre-update value.

### Proposed Fix

Line 113 in `vcrc32_8.scala` should be changed from:

```scala
assertStableWhen(!io.clken, crcReg, "crc_stable_when_clken_off")
```

to:

```scala
assertStableWhen(!io.clken && Past(!io.clken), crcReg, "crc_stable_when_clken_off")
```

This ensures stability is only checked when `clken` has been low for **two consecutive cycles**, giving the register one cycle to settle after any update that occurred when `clken` was high. With this fix:
- At cycle 1 (time 10): `!clken(1)=1` but `Past(!clken)=!clken(0)=0`, so condition is false → **not checked**.
- At cycle 2+ (time 20+): if `io.clken` stays low, then `!clken && Past(!clken)` becomes true, and the assertion correctly verifies that `crcReg` stays stable.

### Evidence Summary

| Evidence | Detail |
|----------|--------|
| `crcReg` at cycle 0 (time 0) | `0xFFFFFFFF` |
| `crcReg` at cycle 1 (time 10) | `0xF1A90FFF` |
| `io.clken` at cycle 0 | 1 (register update enabled) |
| `io.clken` at cycle 1 | 0 (register update disabled) |
| `io.compute` at cycle 0 | 1 (CRC computation active) |
| Assertion state at time 10 | **0** (failure) |
| Root cause | Assertion checks stability during the first deasserted cycle, ignoring that the register was legitimately updated when `clken` was high the cycle before |
