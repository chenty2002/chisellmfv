# Counterexample Analysis Report — `spinner32.barrel_shifter_correctness`

## 1. Verification Environment

- **Top Module**: `spinner32` (a 32-bit barrel shifter / rotator with feedback)
- **Waveform File**: `verilog/extra_bench/spinner/spinner32.barrel_shifter_correctness.fst`
- **Key Components**:
  - `inrReg` — input-rotation register (32-bit)
  - `doutReg` — output register (32-bit)
  - `splReg` — spin-mode flag register
  - `tmp1`–`tmp5` — 5 cascaded barrel-shifter stages (rotate-right by 1, 2, 4, 8, 16 bits respectively)
  - `io.din` — data input
  - `io.amount` — rotation amount (5 bits, values 0–31)
  - `io.spin` — spin mode enable
- **Brief Description**: The design implements a 32-bit rotate-right barrel shifter. Input data can be loaded from `io.din` (when not in spin mode) or fed back from the output register `doutReg` (when in spin mode). Cascaded stages decode each bit of `io.amount` to perform the rotation.

## 2. Violated Assertion

- **Assertion Name**: `barrel_shifter_correctness` (extracted from waveform filename)
- **Waveform Signal**: `spinner32.barrel_shifter_correctness` — transitions `1→0` at time **10 ns**
- **Code Location**: `spinner32.scala`, lines 86–88

### Assertion Code Snippet

```scala
val shiftLeftAmt = Mux(io.amount === 0.U, 0.U, 32.U - io.amount)
val expectedRot = (inrReg >> io.amount) | (inrReg << shiftLeftAmt)
fvAssert(tmp5 === expectedRot, "barrel_shifter_correctness")
```

### Natural Language Description

The assertion checks that the barrel shifter's final output (`tmp5`) equals the mathematically correct **rotate-right** of `inrReg` by `io.amount` bits. The formula `(value >> N) | (value << (32-N))` with `N = io.amount` computes a rotate-right by keeping `io.amount` at a small width (5 bits, so `32.N - io.amount` is at most 6 bits).

## 3. Waveform Information

### Full Path to Waveform File
`verilog/extra_bench/spinner/spinner32.barrel_shifter_correctness.fst`

### Time Range and Key Time Points

| Time (ns) | Event |
|-----------|-------|
| 0         | Initial state, `barrel_shifter_correctness = 1` (assertion holds) |
| 9         | **Last posedge before assertion failure**: clock = 0, all combinational values stable with old `io.amount = 30` |
| **10**    | **Assertion fails**: `barrel_shifter_correctness` transitions `1→0`; clock transitions `0→1` |
| 20        | End of trace (2 cycles total) |

### Critical Signal Values at Failure Time (10 ns)

| Signal | Value | Remarks |
|--------|-------|---------|
| `clock` | `1` | Rising clock edge |
| `io.amount [4:0]` | `01110` (= 14) | Rotation amount |
| `io.din [31:0]` | `0x80000000` | Input data (bit 31 set) |
| `io.spin` | `1` | Spin mode active |
| `inrReg [31:0]` | `0x80000000` | Data being rotated (bit 31 only) |
| `splReg` | `1` | In spin mode |
| `tmp1 [31:0]` | `0x80000000` | Stage 1 bypass (amount[0]=0) |
| `tmp2 [31:0]` | `0x20000000` | Stage 2: rotated right by 2 (bit 29 set) |
| `tmp3 [31:0]` | `0x02000000` | Stage 3: rotated right by 4 (bit 25 set) |
| `tmp4 [31:0]` | `0x00020000` | Stage 4: rotated right by 8 (bit 17 set) |
| `tmp5 [31:0]` | `0x00020000` | Stage 5 bypass (amount[4]=0) — **final result = bit 17 set** |
| `doutReg [31:0]` | `0x00000000` | Not yet updated |
| `io.dout [31:0]` | `0x00000000` | Still old value |

## 4. Root Cause Analysis

### Root Cause Category: **Incorrect Assertion** (Assertion Error)

The assertion formula on line 87 has a **width-inference bug** in Chisel's FIRRTL backend. This is **not** a bug in the barrel shifter design itself — the hardware correctly computes `rotate-right(0x80000000, 14) = 0x00020000` as verified by the tmp1–tmp5 stage outputs.

### Buggy Code Location

- **File**: `spinner32.scala`
- **Line**: 87
- **Problem**: `inrReg << shiftLeftAmt` where `shiftLeftAmt` has width **6 bits** (inferred from `32.U - io.amount` where `32.U` is a 6-bit literal)

### Detailed Explanation

**Why `shiftLeftAmt` is 6 bits wide:**
- `io.amount` is `UInt(5.W)` (values 0–31)
- `32.U` is a Scala literal that FIRRTL encodes with minimum width 6 (since 32 = 0b100000 requires 6 bits)
- `32.U - io.amount` therefore has width `max(6, 5) + 1 = 6` bits (allowing values 0–32)
- `Mux(io.amount === 0.U, 0.U, 32.U - io.amount)` takes the wider of the two branches → **6 bits**

**Why this causes an assertion failure at runtime:**
- In FIRRTL, `dshl(inrReg, shiftLeftAmt)` has result width = `width(inrReg) + 2^width(shiftLeftAmt) - 1 = 32 + 2^6 - 1 = 95` bits
- At time 10 ns: `inrReg = 0x80000000` (bit 31 set), `io.amount = 14`, so `shiftLeftAmt = 32 - 14 = 18`
- `inrReg << 18` left-shifts bit 31 to position 49 in a **95-bit** intermediate value
- `inrReg >> 14` right-shifts bit 31 to position 17 in a 32-bit value
- The 95-bit `expectedRot` has **both bit 49** (from left shift) and **bit 17** (from right shift) set
- `tmp5` is only 32 bits (`0x00020000`, bit 17 set)
- In FIRRTL, equality `===` zero-extends the narrower operand, so `tmp5` (32-bit) is compared against `expectedRot` (95-bit)
- Bit 49 of `expectedRot` is set while the zero-extended `tmp5` has bit 49 = 0 → **assertion fails**

### Evidence Trace

1. At time 0: `io.amount = 30` (11110), `shiftLeftAmt = 32 - 30 = 2`. `inrReg` is initially 0, so `0 << 2 = 0` — no issue, assertion passes.
2. At time 10: `io.amount` transitions to 14 (01110), `inrReg` becomes `0x80000000` (bit 31 set), `shiftLeftAmt = 32 - 14 = 18`
3. The barrel shifter (hardware) correctly computes `rotate-right(0x80000000, 14) = 0x00020000` → `tmp5 = 0x00020000`
4. The assertion formula computes `expectedRot` as a 95-bit value where the left-shift overflows beyond bit 31, setting bit 49
5. `tmp5 (32-bit) === expectedRot (95-bit)` evaluates to **false** because bit 49 differs

### Fix Recommendation

Truncate the left-shift result to 32 bits before ORing. The simplest fix for line 86–87:

```scala
val shiftLeftAmt = Mux(io.amount === 0.U, 0.U, 32.U - io.amount)
val shiftLeftRes = Wire(UInt(32.W))
shiftLeftRes := inrReg << shiftLeftAmt
val expectedRot = (inrReg >> io.amount) | shiftLeftRes
fvAssert(tmp5 === expectedRot, "barrel_shifter_correctness")
```

This forces the left-shift result to be exactly 32 bits, discarding any overflow bits beyond bit 31, which is the correct behavior for a rotate-right operation in 32-bit arithmetic.

### Alternative Fix

A more concise approach uses `.asUInt(32.W)`:
```scala
val shiftLeftAmt = Mux(io.amount === 0.U, 0.U, 32.U - io.amount)
val expectedRot = (inrReg >> io.amount) | (inrReg << shiftLeftAmt).asUInt(32.W)
fvAssert(tmp5 === expectedRot, "barrel_shifter_correctness")
```
