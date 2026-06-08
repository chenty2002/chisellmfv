# Counterexample Analysis Report: `SerialCSAMult.CSA_correctness`

## 1. Verification Environment

- **Top module**: `SerialCSAMult` (BITS=32)
- **Key components**:
  - `s` (31-bit register) — sum accumulator
  - `c` (31-bit register) — carry accumulator
  - `i` (32-bit register) — registered multiplicand
  - `j` (1-bit register) — registered multiplier bit
  - Carry-save adder (CSA) with full-adder logic
- **Description**: A serial multiplier using carry-save addition. Each cycle, the multiplier bit `j` and multiplicand `i` produce a partial product `andA = j * i`. This is summed with the existing `s` and `c` registers using a carry-save adder (CSA). The CSA decomposes the sum `c + s + andA_trunc` into new `s` (sum) and `c` (carry) values.

## 2. Violated Assertion

- **Assertion name**: `CSA_correctness` (from waveform filename `SerialCSAMult.CSA_correctness.fst`)
- **Code location**: `spm.scala`, line ~67
- **Assertion code**:

```scala
fvAssert(faS + (faC << 1.U) === c + s + andA_trunc, "CSA_correctness")
```

- **Property description**: The carry-save adder must satisfy the full-adder identity: the sum and carry outputs, when added (with carry weighted by 2), must equal the three inputs added together. Mathematically: `faS + 2*faC = c + s + andA_trunc`.

## 3. Waveform Information

- **Waveform file**: `verilog/extra_bench/smult/SerialCSAMult.CSA_correctness.fst`
- **Duration**: 30 ns (3 clock cycles, clock period = 10 ns)
- **Key time points**:
  - Time 0 ns: Initial state. All registers (s, c, i) = 0. io_i_raw = 0x94D3BF70. Assertion passes.
  - Time 10 ns (posedge): Registers update. s becomes 0x4A69DFB8 (from `Cat(andA[31], faS[30:1])`), i becomes 0xF5962036. Assertion passes before update.
  - **Time 20 ns (posedge)**: Assertion **fails**. Signal values at failure:
    - `s` [30:0] = `1001010011010011101111110111000` (0x4A69DFB8)
    - `c` [30:0] = `0000000000000000000000000000000`
    - `andA_trunc` [30:0] = `1110101100101100010000000110110` (0x75962036)
    - `faS` [30:0] = `0111111111111111111111110001110` (0x3FFFFF8E)
    - `faC` [30:0] = `1000000000000000000000000110000` (0x40000030)
    - `CSA_correctness` = 0 (failure)

## 4. Root Cause Analysis

### Root Cause Type: **Incorrect Assertion (assertion_error)**

### The Bug: Width Mismatch in Assertion

The assertion `faS + (faC << 1.U) === c + s + andA_trunc` suffers from a **bit-width mismatch** between its left and right sides.

#### Width Analysis

All signals (`faS`, `faC`, `s`, `c`, `andA_trunc`) are declared as `UInt((BITS-1).W)` = `UInt(31.W)`.

**Left side** — `faS + (faC << 1.U)`:
- `faC << 1.U` shifts a 31-bit value left by 1, producing a **32-bit** result
- `faS` (31 bits) + 32-bit value = **32-bit** result

**Right side** — `c + s + andA_trunc`:
- All 31-bit operands, all additions produce 31-bit results
- The final result is **31 bits**

When comparing with `===`, Chisel zero-extends the 31-bit right side to 32 bits. This zero-extension **always sets bit 31 to 0**.

#### Why the Assertion Fails at Time 20

At time 20, `faC[30] = 1` (the MSB of faC is set). This means:
- `faC << 1` has bit 31 = 1 in the 32-bit result
- Left side `faS + (faC << 1)` has bit 31 = 1
- Right side `c + s + andA_trunc` (31 bits), when zero-extended to 32 bits, has bit 31 = 0

**The left and right sides are mathematically equal modulo 2^31, but the comparison fails because the left side captures the overflow into bit 31 while the right side cannot.**

#### Mathematical Verification

The CSA implements the standard full-adder equations:
- `faS = c ^ s ^ andA_trunc` (XOR — sum bits)
- `faC = (c & s) | (c & andA_trunc) | (s & andA_trunc)` (MAJ — carry bits)

The well-known identity holds: `c + s + andA_trunc = faS + 2*faC` (as mathematical integers).

At time 20:
- `c + s + andA_trunc = 0 + 0x4A69DFB8 + 0x75962036 = 0xBFFFFFEE` (as a 32-bit value)

Wait, let me recompute more carefully. The actual property:

When `c=0`:
- `s + andA_trunc = (s ^ andA_trunc) + 2*(s & andA_trunc) = faS + 2*faC`

This identity is exact for unbounded integers. At time 20:
- `s & andA_trunc = faC = 0x40000030` (has bit 30 set)
- `faC << 1 = 0x80000060` (bit 31 set in 32-bit)
- `faS + (faC << 1) = 0x3FFFFF8E + 0x80000060 = 0xBFFFFFEE` (32-bit)

Right side in 31-bit: `c + s + andA_trunc = faS = 0x3FFFFF8E` (the carry into bit 31 is discarded)
Right side zero-extended to 32-bit: `0x3FFFFF8E`

`0xBFFFFFEE ≠ 0x3FFFFF8E` → Assertion fails!

#### Why the Assertion Passes Before Time 20

At times 0–19, `faC = 0`, so `faC << 1 = 0` and there is no bit 31 contribution. Both sides are effectively 31-bit and equal.

### The Fix

The assertion should ensure matching bit widths. The correct way to write the CSA correctness assertion is to truncate the left side to 31 bits (since both sides should be compared modulo 2^31):

```scala
fvAssert((faS + (faC << 1.U))(BITS-2, 0) === c + s + andA_trunc, "CSA_correctness")
```

This extracts bits [30:0] of the 32-bit left sum, matching the 31-bit right side. Since `c + s + andA_trunc ≡ faS + 2*faC (mod 2^31)`, this assertion will correctly validate the CSA property.
