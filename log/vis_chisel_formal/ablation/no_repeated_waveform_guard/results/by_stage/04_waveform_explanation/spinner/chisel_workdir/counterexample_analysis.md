# Counterexample Analysis Report: spinner32.barrel_shifter_correctness

## 1. Verification Environment

| Item | Description |
|------|-------------|
| **Top Module** | `spinner32` (from `spinner32.scala`) |
| **Design Under Test** | A 32-bit barrel shifter with optional spin mode that feeds back rotated data |
| **Key Components** | `inrReg` (input register), `splReg` (spin mode register), `doutReg` (output register), 5-stage barrel shifter |
| **Inputs** | `io.din` (32-bit data), `io.amount` (5-bit rotation amount), `io.spin` (spin mode enable) |
| **Output** | `io.dout` (32-bit rotated output) |

**Data Flow**: When `io.spin` is asserted (`splReg` set last cycle), `inrReg` feeds back `doutReg`. Otherwise, `inrReg` loads `io.din`. The barrel shifter produces `tmp5` by rotating `inrReg` right by `io.amount` bits across 5 stages (1, 2, 4, 8, 16 bits respectively).

## 2. Violated Assertion

**Assertion Name**: `barrel_shifter_correctness` (from waveform filename `spinner32.barrel_shifter_correctness.fst`)

**Source File**: `spinner32.scala`, line 69

**Code Snippet** (from lines 65-69):
```scala
val shiftLeftAmt = Mux(io.amount === 0.U, 0.U, 32.U - io.amount)
val expectedRot = (inrReg >> io.amount) | (inrReg << shiftLeftAmt)
fvAssert(tmp5 === expectedRot, "barrel_shifter_correctness")
```

**Property Description**: The barrel shifter's final output `tmp5` must equal the correct mathematical rotate-right of `inrReg` by `io.amount` bits. The correct rotate-right of a 32-bit value `V` by `N` is `(V >> N) | (V << (32-N))`.

## 3. Waveform Information

**Waveform File**: `verilog/extra_bench/spinner/spinner32.barrel_shifter_correctness.fst`

**Key Time Points**:

| Time (ns) | Event |
|-----------|-------|
| 0 | Initial state: amount=16 (binary 10000), din=0x80000000, inrReg=0x00000000, assertion passing |
| 5 | Between clock edges (same state) |
| 10 | **Failure point**: amount=31 (binary 11111), inrReg=0x80000000, tmp5=0x00008000, **assertion fails** |
| 15-20 | Failure persists |

**Critical Signal Values at Failure (time=10 ns)**:

| Signal | Value | Meaning |
|--------|-------|---------|
| `io.amount [4:0]` | `11111` (31) | Rotate right by 31 bits |
| `io.din [31:0]` | `0x80000000` | Input data with only MSB set |
| `inrReg [31:0]` | `0x80000000` | Pipeline register (fed from previous `doutReg` via spin mode) |
| `splReg` | `1` | Spin mode active (feedback path) |
| `tmp5 [31:0]` | `0x00008000` | **Actual barrel shifter output** |
| **Expected** | `0x00000001` | Correct rotate-right of 0x80000000 by 31 |
| `barrel_shifter_correctness` | `0` | **Assertion firing (failing)** |

## 4. Root Cause Analysis

### Bug Type: **DUT Bug** — Incorrect barrel shifter cascade implementation

### Location
- **File**: `spinner32.scala`
- **Line**: 41, 48, 55, 62 (stages 2 through 5)

### Bug Description

The barrel shifter is implemented as a 5-stage cascade, where each stage should progressively rotate the result from the **previous stage**. However, stages 2–5 all read from `tmp0` (the original input `inrReg`) instead of the previous stage's output.

**Buggy code (lines 38-63)**:

```scala
tmp0 := inrReg

// Stage 1: rotate by 1 bit
when(io.amount(0)) {
  tmp1 := Cat(tmp0(0), tmp0(31, 1))    // ✓ Correct: reads from tmp0
}.otherwise {
  tmp1 := tmp0
}

// Stage 2: rotate by 2 bits
when(io.amount(1)) {
  tmp2 := Cat(tmp0(1, 0), tmp0(31, 2))  // ✗ BUG: should read from tmp1, not tmp0
}.otherwise {
  tmp2 := tmp1
}

// Stage 3: rotate by 4 bits
when(io.amount(3)) {
  tmp3 := Cat(tmp0(3, 0), tmp0(31, 4))  // ✗ BUG: should read from tmp2, not tmp0
}.otherwise {
  tmp3 := tmp2
}

// Stage 4: rotate by 8 bits
when(io.amount(3)) {  // Note: should be io.amount(3) 
  tmp4 := Cat(tmp0(7, 0), tmp0(31, 8))  // ✗ BUG: should read from tmp3, not tmp0
}.otherwise {
  tmp4 := tmp3
}

// Stage 5: rotate by 16 bits
when(io.amount(4)) {
  tmp5 := Cat(tmp0(15, 0), tmp0(31, 16)) // ✗ BUG: should read from tmp4, not tmp0
}.otherwise {
  tmp5 := tmp4
}
```

**Correct code should be**:
- `tmp2 := Cat(tmp1(1, 0), tmp1(31, 2))` — reads from stage 1 output
- `tmp3 := Cat(tmp2(3, 0), tmp2(31, 4))` — reads from stage 2 output
- `tmp4 := Cat(tmp3(7, 0), tmp3(31, 8))` — reads from stage 3 output
- `tmp5 := Cat(tmp4(15, 0), tmp4(31, 16))` — reads from stage 4 output

### Evidence from Waveform

**At time 0**: `io.amount = 10000` (binary) = 16 (decimal).

- Only bit 4 of `io.amount` is set. Since each stage's `when` block reads from `tmp0` and stages 1-4 are in `.otherwise` (pass-through), the final output `tmp5` only applies the rotate-by-16 operation.
- For input 0x80000000, rotate-right by 16 gives 0x00008000, which coincidentally matches the correct answer. The assertion passes, masking the bug.

**At time 10**: `io.amount = 11111` (binary) = 31 (decimal).

- **All 5 bits** of `io.amount` are set, so all stages activate simultaneously.
- Because each stage reads from `tmp0` (value = 0x80000000) instead of the previous stage's output, the computations are:
  - Stage 1 (amount(0)=1): rotates 0x80000000 right by 1 → 0x40000000 (written to `tmp1`)
  - Stage 2 (amount(1)=1): rotates 0x80000000 right by 2 → 0x20000000 (written to `tmp2`) — **does NOT use tmp1!**
  - Stage 3 (amount(2)=1): rotates 0x80000000 right by 4 → 0x08000000 (written to `tmp3`) — **does NOT use tmp2!**
  - Stage 4 (amount(3)=1): rotates 0x80000000 right by 8 → 0x00800000 (written to `tmp4`) — **does NOT use tmp3!**
  - Stage 5 (amount(4)=1): rotates 0x80000000 right by 16 → **0x00008000** (written to `tmp5`) — **does NOT use tmp4!**

- Since stage 5 is active (`when(io.amount(4))`), it overwrites `tmp5` with the rotate-by-16 result (0x00008000), **ignoring** all previous stages' contributions. The other stages' outputs (tmp1–tmp4) are computed but never used in the final result.

- **Correct expected output**: `(0x80000000 >> 31) | (0x80000000 << 1)` = `0x00000001 | 0x00000000` = **0x00000001**

- **Actual output**: **0x00008000** (only the most-significant bit's rotation is applied)

### Root Cause Summary

The bug is that the barrel shifter stages are **not cascaded** — each stage independently rotates the original input `tmp0` rather than the accumulated result from the previous stage. This means that when multiple amount bits are set, only the **most significant bit's rotation** takes effect, making all lower-significance bits' rotations ignored. The bug is masked when only one amount bit is set (e.g., amount=16 or amount=8) or when inrReg=0, but it becomes visible when multiple amount bits are set simultaneously (e.g., amount=31).
