# Counterexample Analysis Report: `reset_clears_s` Assertion Failure

## 1. Verification Environment

- **Top module**: `SerialCSAMult` (in package `llmverify`)
- **Source file**: `spm.scala`
- **Module type**: A serial carry-save adder (CSA) multiplier that processes one multiplier bit per cycle
- **Key components**:
  - `s` (RegInit, 31-bit): Sum register of the CSA
  - `c` (RegInit, 31-bit): Carry register of the CSA
  - `i` (RegInit, 32-bit): Registered multiplicand input
  - `j` (RegInit, 1-bit): Registered multiplier bit
  - Combinational CSA logic: `andA`, `andA_trunc`, `faS`, `faC`
- **Verification tool**: JasperGold via ChiselFv formal verification framework
- **Clock period**: 10 ns (posedge at 0, 10, 20 ns)

## 2. Violated Assertion

- **Assertion name**: `reset_clears_s` (from waveform filename `SerialCSAMult.reset_clears_s.fst`)
- **Source location**: `spm.scala`, line 82
- **Code snippet**:
  ```scala
  // Safety 2: Custom reset clears sum and carry registers.
  fvAssert(!io.reset || (s === 0.U), "reset_clears_s")
  ```
- **Property description**: When the custom reset signal `io.reset` is asserted, the sum register `s` must be 0.

## 3. Waveform Information

- **Waveform file**: `verilog/extra_bench/smult/SerialCSAMult.reset_clears_s.fst`
- **Time range**: 0 ns → 30 ns (3 clock cycles)
- **Key time points**:

| Time (ns) | Event | io_reset | s [30:0] | i [31:0] | j | io_i_raw | io_j_raw |
|-----------|-------|----------|----------|----------|---|----------|----------|
| 0 | Posedge (cycle 1) | 0 | 0x0000000 | 0x00000000 | 0 | 0x80000000 | 1 |
| 10 | Posedge (cycle 2) | 0 | 0x0000000 | 0x80000000 | 1 | 0xFFFFFFFE | 1 |
| 15 | Mid-cycle | 0 | 0x0000000 | 0x80000000 | 1 | 0xFFFFFFFE | 1 |
| 20 | Posedge (cycle 3) — **FAILURE** | **1** | **0x4000000** | 0xFFFFFFFE | 1 | 0xFFFFFFFE | 1 |

- **Failure point**: Time = 20 ns
- **Critical signal values at failure**:
  - `io_reset` = 1
  - `s` = 0x4000000 (binary: `1000000000000000000000000000000`)
  - `c` = 0x0000000
  - `andA_trunc` = 0x7FFFFFFE (binary: `1111111111111111111111111111110`)
  - `faS` = 0x3FFFFFFE (binary: `0111111111111111111111111111110`)
  - `faC` = 0x4000000 (binary: `1000000000000000000000000000000`)

## 4. Root Cause Analysis

### Classification: **Assertion Error** (Incorrect assertion timing)

### Bug Location
- **File**: `spm.scala`
- **Line**: 82 (the `reset_clears_s` assertion)
- **Assertion**:
  ```scala
  fvAssert(!io.reset || (s === 0.U), "reset_clears_s")
  ```

### Description of the Issue

The assertion checks that **at the exact moment** `io.reset` is 1, the register `s` must already be 0. However, `s` is a synchronous register (`RegInit`), and it is cleared by the `when(io.reset)` block. In synchronous digital design, a register's output only updates **after** the clock edge — not at the instant the control signal becomes active.

#### Detailed Failure Sequence

**Cycle 1 (time 0–10):**
- Inputs: `io_i_raw = 0x80000000`, `io_j_raw = 1`, `io_reset = 0`
- At posedge (time 0): `i := io_i_raw` → i becomes 0x80000000 (visible at time 10)
- At posedge (time 0): `j := io_j_raw` → j becomes 1 (visible at time 10)
- Since `io_reset = 0`: `s <= Cat(andA[31], faS[30:1])`
  - `andA = Fill(32, j_old=0) & i_old=0 = 0`
  - `faS = c_old ^ s_old ^ andA_trunc = 0`
  - `s <= Cat(0, 0) = 0` (stays 0)

**Cycle 2 (time 10–20):**
- Inputs: `io_i_raw = 0xFFFFFFFE`, `io_j_raw = 1`, `io_reset = 0`
- At posedge (time 10): `i := io_i_raw = 0xFFFFFFFE` (visible at time 20)
- At posedge (time 10): `j := io_j_raw = 1` (stays 1)
- Since `io_reset = 0`: `s <= Cat(andA[31], faS[30:1])`
  - `andA = Fill(32, 1) & 0x80000000 = 0x80000000`
  - `andA[31] = 1`
  - `andA_trunc = andA[30:0] = 0`
  - `faS = c_old(0) ^ s_old(0) ^ andA_trunc(0) = 0`
  - `s <= Cat(1, 0) = 0x40000000`

**Cycle 3 (time 20–30) — Failure:**
- `io_reset = 1` is asserted at this posedge
- The `when(io.reset)` block schedules `s <= 0.U` (non-blocking assignment)
- However, the **immediate assertion** `!io.reset || (s === 0.U)` evaluates **before** the NBA update takes effect
- At evaluation time: `io_reset = 1` but `s` still reads `0x40000000` (the value from cycle 2)
- Result: `!1 || (0x40000000 === 0)` → `0 || 0` → **FALSE → FAIL**

### Why This is an Assertion Error, Not a DUT Bug

1. **DUT behavior is correct**: The register `s` will correctly update to 0 after the clock edge (visible at time 30 in the next cycle). The synchronous clear works as designed.

2. **Misaligned timing**: The assertion uses an immediate assertion (`fvAssert` which maps to an immediate SystemVerilog `assert`), which evaluates combinationally. For synchronous reset, the assertion should check the value **after** the register updates, not at the same instant.

3. **Correct assertion form**: The property should use temporal logic — either checking at the **next clock cycle** after reset is asserted, or using a `past()` operator. In ChiselFv, this could be expressed as:
   ```scala
   // Option 1: Check s is 0 on the NEXT cycle after io.reset
   fvAssert(RegNext(!io.reset || (s === 0.U)), "reset_clears_s")
   
   // Option 2: Use a simple temporal delay
   fvAssert(!io.reset || RegNext(s === 0.U), "reset_clears_s")
   ```

### Evidence Summary

- The waveform **unambiguously** shows `s = 0x40000000` at time 20 (the third posedge) when `io_reset = 1`
- The DUT's `when(io.reset)` block correctly generates `s <= 0`, but in Chisel (and SystemVerilog), the non-blocking assignment updates the register output only after the clock edge
- The assertion's immediate evaluation at the posedge sees the stale value of `s` before the reset takes effect
- This is a **classic synchronous-reset assertion timing mismatch** — the property should be checked on the cycle following the reset assertion, not at the very same cycle
