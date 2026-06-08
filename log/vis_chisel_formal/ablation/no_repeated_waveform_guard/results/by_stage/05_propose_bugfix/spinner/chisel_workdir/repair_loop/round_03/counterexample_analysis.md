# Counterexample Analysis: `spinner32.load_then_rotate_by_0`

## 1. Verification Environment

- **Top module**: `spinner32` (class in `spinner32.scala`)
- **Module structure**:
  - **Inputs**: `io.spin` (Bool), `io.amount` (UInt<5>), `io.din` (UInt<32>)
  - **Outputs**: `io.dout` (UInt<32>)
  - **Internal registers**: `doutReg` (UInt<32>, output register), `inrReg` (UInt<32>, input register), `splReg` (Bool, spin-mode flag)
  - **Barrel shifter**: 5-stage rotate-right shifter (`tmp0`→`tmp1`→`tmp2`→`tmp3`→`tmp4`→`tmp5`) controlled by bits of `io.amount`
  - **Pipeline**: `inrReg` loads from `io.din` (when `splReg=false`) or from `doutReg` (when `splReg=true`); the barrel shifter computes `rotate(inrReg, io.amount)`; `doutReg` captures the result

## 2. Violated Assertion

- **Assertion name** (from waveform filename): `load_then_rotate_by_0`
- **Full assertion** (spinner32.scala, line 88):
  ```scala
  assertAfterNStepWhen(!io.spin && io.amount === 0.U, 2, io.dout === io.din, "load_then_rotate_by_0")
  ```
- **Natural language**: When the trigger condition `!io.spin && io.amount === 0.U` is true, then after 2 clock steps, assert that `io.dout === io.din`.
- **File location**: `spinner32.scala`, line 88 (Assertion P3)

## 3. Waveform Information

- **Waveform file**: `verilog/extra_bench/spinner/spinner32.load_then_rotate_by_0.fst`
- **Waveform duration**: 1 cycle (10 ns), time range 0 ns → 10 ns
- **Key time points**:

| Signal | Time 0 (posedge) | Time 5 | Time 10 |
|--------|------------------|--------|---------|
| `spinner32.clock` | 1 | 0 | 0 |
| `spinner32.io_din [31:0]` | 0x00000001 | 0x00000001 | 0x00000001 |
| `spinner32.io_spin` | 0 | 0 | 0 |
| `spinner32.io_amount [4:0]` | 0 | 0 | 0 |
| `spinner32.io_dout [31:0]` | 0x00000000 | 0x00000000 | 0x00000000 |
| `spinner32.splReg` | 0 | 0 | 0 |
| `spinner32.inrReg [31:0]` | 0x00000000 | 0x00000000 | 0x00000000 |
| `spinner32.doutReg [31:0]` | 0x00000000 | 0x00000000 | 0x00000000 |
| `spinner32.load_then_rotate_by_0` | 1 | 1 | 1 |
| `spinner32.hasBeenReset` | 1 | 1 | 1 |

- **Critical observation**: The waveform is only 1 cycle (10 ns). The trigger `!io.spin && io.amount === 0.U` is TRUE at time 0 (io.spin=0, io.amount=0). The assertion checks 2 steps later (at time 20 ns), which is outside the captured waveform. The formal tool found a counterexample where `io.din` changes value between cycle 0 and cycle 2, causing the check to fail.

## 4. Root Cause Analysis

### Buggy Code Location
- **File**: `spinner32.scala`
- **Line**: 88
- **Assertion**: `assertAfterNStepWhen(!io.spin && io.amount === 0.U, 2, io.dout === io.din, "load_then_rotate_by_0")`

### Error Category: **Incorrect Assertion**

The assertion is logically incorrect because it does not account for the fact that `io.din` is an **unconstrained free input** that can change arbitrarily between clock cycles.

### Detailed Explanation

The design has a 2-cycle pipeline latency:

| Cycle | Event | inrReg (after clk) | doutReg (after clk) |
|-------|-------|-------------------|-------------------|
| 0 (init) | Initial state | 0 | 0 |
| 0 (posedge) | Trigger TRUE: io.spin=0, io.amount=0, io.din=1 | ← io.din = **1** | ← rotate(0,0)=0 |
| 1 (posedge) | io.din may change; io.amount=0; splReg=0 | ← io.din (new value) | ← rotate(1,0) = **1** |
| 2 (posedge) | **Assertion check**: io.dout === io.din | — | io.dout = **1** |

At the assertion check point (cycle 2):
- **`io.dout`** = `doutReg` = **1** (this is the rotation of `io.din` from **cycle 0** through the pipeline, and since amount=0, the rotation is identity: `rotate(1, 0) = 1`)
- **`io.din`** at cycle 2 is a **free input** that the formal tool can set to **any 32-bit value** (e.g., 0x00000005, 0xFFFFFFFF, etc.)

Assertion check: `io.dout (1) === io.din (any value)`

The formal tool easily constructs a counterexample by making `io.din` at cycle 2 **different from 1**, causing `io.dout !== io.din`.

### Why This Is an Assertion Error (Not a DUT Bug)

The DUT hardware is **correct**: The pipeline correctly performs `doutReg := rotate(inrReg, amount)` where `inrReg` was loaded with `io.din` two cycles earlier. This is a 2-cycle latency design by construction.

The assertion is flawed because it compares `io.dout` (the rotated value of `io.din` from 2 cycles ago) against the **current** `io.din`, implicitly assuming `io.din` stays constant throughout the 2-cycle window. This assumption is not enforced by any constraint — the formal tool can freely vary `io.din` between cycles.

### Fix

The assertion should either:
1. **Compare against the past value of `io.din`**: Use `past(io.din, 2)` to get the `io.din` value from 2 cycles ago, or
2. **Sample `io.din` when the trigger fires** and compare against the sampled value after the pipeline delay

For example:
```scala
// Fix option: compare against the din value from 2 cycles ago
// (This requires adding a register to delay io.din by 2 cycles, or using past())
// Or simply restructure the assertion to account for variable io.din:
assertAfterNStepWhen(!io.spin && io.amount === 0.U, 2, io.dout === past(io.din, 2), "load_then_rotate_by_0")
```

This is an **assertion error** (`assertion_error`), not a DUT bug.
