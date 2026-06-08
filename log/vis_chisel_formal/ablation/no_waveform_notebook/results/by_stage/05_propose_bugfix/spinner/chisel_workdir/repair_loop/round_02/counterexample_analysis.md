# Counterexample Analysis Report

## 1. Verification Environment

- **Benchmark**: `spinner`
- **Top Module**: `spinner32` (extends `Module with Formal`)
- **Design Under Test**: A 32-bit barrel shifter with spin-mode feedback. The module accepts:
  - `io.spin` (Bool): spin mode enable
  - `io.amount` (UInt(5.W)): rotation amount (0–31)
  - `io.din` (UInt(32.W)): data input
  - `io.dout` (UInt(32.W)): data output (registered)
- **Key Internal State**:
  - `inrReg`: internal rotation register (holds data to rotate)
  - `doutReg`: output register (latches rotated result)
  - `splReg`: spin-mode flag
  - `tmp0`–`tmp5`: combinational barrel shifter stages

The formal verification assertion checks that `tmp5` (the output of the barrel shifter) equals the mathematically correct right rotation of `inrReg` by `io.amount`.

## 2. Violated Assertion

- **Assertion Name**: `barrel_shifter_correct_rotation`
- **Location**: `spinner32.scala`, line ~108
- **Code**:
  ```scala
  val correctRot = ((inrReg >> io.amount) | (inrReg << (32.U - io.amount)))(31, 0)
  fvAssert(tmp5 === correctRot, "barrel_shifter_correct_rotation")
  ```
- **Property Description**: The barrel shifter must compute the correct right rotation of `inrReg` by `io.amount`. The mathematically correct formula for a right rotation of a 32-bit value by N bits is `(x >> N) | (x << (32-N))`.

## 3. Waveform Information

- **Waveform File**: `verilog/extra_bench/spinner/spinner32.barrel_shifter_correct_rotation.fst`
- **Time Range**: 0 ns → 20 ns (2 clock cycles, each 10 ns)

### Key Signal Values

| Time | Signal | Value (binary/hex) |
|------|--------|-------------------|
| 0 | `io_amount` | `11111` (31) |
| 0 | `inrReg` | `0x00000000` |
| 0 | `io_din` | `0x80000001` |
| 0 | `tmp5` | `0x00000000` |
| 0 | `barrel_shifter_correct_rotation` | **1** (passes) |
| 10 | `io_amount` | `11110` (30) |
| 10 | `inrReg` | `0x80000001` |
| 10 | `io_din` | `0x80000001` |
| 10 | `tmp5` | `0x00018000` |
| 10 | `barrel_shifter_correct_rotation` | **0** (FAILS) |
| 10 | `splReg` | 1 |
| 10 | `doutReg` | `0x00000000` |

### Timeline

1. **Time 0** (initial): `inrReg=0`, `io_amount=31`. Since `inrReg=0`, any rotation yields 0, so `tmp5=0=correctRot` — assertion passes.
2. **Time 10** (posedge clock): `inrReg` has been loaded with `io_din=0x80000001`. The barrel shifter computes `tmp5=0x00018000` from `inrReg` with `io_amount=30`. The correct rotation should be `0x00000006`. The assertion fails.

## 4. Root Cause Analysis

### Error Classification: **Bug in the Original Design (dut_bug)**

### Bug Description

The barrel shifter incorrectly implements the rotation stages. Each stage should chain from the **previous stage's output** to accumulate the rotation amount. Instead, stages 2–5 all use the **original input** (`tmp0` = `inrReg`), overwriting any rotation computed by earlier stages.

### Buggy Code (spinner32.scala, lines 40–60)

```scala
// Stage 1: rotate by 1 bit
when(io.amount(0)) {
  tmp1 := Cat(tmp0(0), tmp0(31, 1))
}.otherwise { tmp1 := tmp0 }

// Stage 2: rotate by 2 bits
when(io.amount(1)) {
  tmp2 := Cat(tmp0(1, 0), tmp0(31, 2))   // BUG: should use tmp1, not tmp0
}.otherwise { tmp2 := tmp1 }

// Stage 3: rotate by 4 bits
when(io.amount(2)) {
  tmp3 := Cat(tmp0(3, 0), tmp0(31, 4))   // BUG: should use tmp2, not tmp0
}.otherwise { tmp3 := tmp2 }

// Stage 4: rotate by 8 bits
when(io.amount(3)) {
  tmp4 := Cat(tmp0(7, 0), tmp0(31, 8))   // BUG: should use tmp3, not tmp0
}.otherwise { tmp4 := tmp3 }

// Stage 5: rotate by 16 bits
when(io.amount(4)) {
  tmp5 := Cat(tmp0(15, 0), tmp0(31, 16))  // BUG: should use tmp4, not tmp0
}.otherwise { tmp5 := tmp4 }
```

### How the Bug Manifests

For `io.amount = 0b11110` (30), the correct behavior should be a cumulative rotation by 2+4+8+16 = 30 bits:

1. **Stage 1** (bit 0 = 0): `tmp1 = tmp0` (pass through) ✓
2. **Stage 2** (bit 1 = 1): rotates `tmp0` by 2 bits → `tmp2 = 0x60000000`
3. **Stage 3** (bit 2 = 1): rotates `tmp0` by 4 bits → `tmp3 = 0x18000000` (overwrites `tmp2`)
4. **Stage 4** (bit 3 = 1): rotates `tmp0` by 8 bits → `tmp4 = 0x01800000` (overwrites `tmp3`)
5. **Stage 5** (bit 4 = 1): rotates `tmp0` by 16 bits → `tmp5 = 0x00018000` (overwrites `tmp4`)

Since each stage uses `tmp0` (the original input) rather than the previous stage's output, **only the last active stage's rotation takes effect**. Here, only the 16-bit rotation from stage 5 survives, giving `tmp5 = 0x00018000`.

### Correct Rotation Computation

For `inrReg = 0x80000001` and `io.amount = 30`:
```
correctRot = (0x80000001 >> 30) | (0x80000001 << 2)
           = 0x00000002           | 0x00000004
           = 0x00000006
```

### Why the Assertion Fails

`tmp5 = 0x00018000` ≠ `correctRot = 0x00000006`, causing assertion `barrel_shifter_correct_rotation` to fail.

### Corrected Code

Each barrel shifter stage should use the previous stage's output:

```scala
// Stage 1
when(io.amount(0)) { tmp1 := Cat(tmp0(0), tmp0(31, 1)) }
.otherwise         { tmp1 := tmp0 }

// Stage 2: rotate tmp1 (previous stage output) by 2
when(io.amount(1)) { tmp2 := Cat(tmp1(1, 0), tmp1(31, 2)) }
.otherwise         { tmp2 := tmp1 }

// Stage 3: rotate tmp2 by 4
when(io.amount(2)) { tmp3 := Cat(tmp2(3, 0), tmp2(31, 4)) }
.otherwise         { tmp3 := tmp2 }

// Stage 4: rotate tmp3 by 8
when(io.amount(3)) { tmp4 := Cat(tmp3(7, 0), tmp3(31, 8)) }
.otherwise         { tmp4 := tmp3 }

// Stage 5: rotate tmp4 by 16
when(io.amount(4)) { tmp5 := Cat(tmp4(15, 0), tmp4(31, 16)) }
.otherwise         { tmp5 := tmp4 }
```

### Additional Notes

- The `otherwise` branches are already correct (they pass through the previous stage's output).
- Only the `when` branches (the active rotation paths) need to chain to the previous stage.
