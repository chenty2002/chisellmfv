# Counterexample Analysis Report: `load_mode_output_correct`

## 1. Verification Environment

- **Top Module**: `spinner32`
- **Source File**: `spinner32.scala` (lines 1–117)
- **Structure**: A barrel shifter that rotates a 32-bit input by a 5-bit amount. The design has three modes of operation (controlled by `io.spin`): load mode (data is loaded from `io.din` and rotated), spin mode (the rotated output is fed back through `inrReg` for progressive rotation), and the output is registered through a pipelined barrel shifter.
- **Key Components**:
  - `inrReg`: RegInit(0.U, 32-bit) — input register for the barrel shifter
  - `doutReg`: RegInit(0.U, 32-bit) — output register
  - `splReg`: RegInit(false.B) — spin mode status register
  - `tmp0`–`tmp5`: wired cascade of barrel shifter stages (rotate-by-1,2,4,8,16)
  - `s1`–`s5`: reference (golden) cascaded rotation of `inrReg` for verification

## 2. Violated Assertion

- **Assertion Name**: `load_mode_output_correct` (from waveform filename `spinner32.load_mode_output_correct.fst`)
- **Code Location**: `spinner32.scala`, line 110
- **Code Snippet**:
  ```scala
  assertImplies(!splReg, doutReg === s5, "load_mode_output_correct")
  ```
- **Property Description**: When the design is in load mode (i.e., `!splReg` is true, meaning `io.spin` is false), the registered output `doutReg` must equal the correct cascaded rotation `s5` of `inrReg` by `io.amount`.

## 3. Waveform Information

- **Waveform File**: `verilog/extra_bench/spinner/spinner32.load_mode_output_correct.fst`
- **Time Range**: 0 ns → 20 ns (2 clock cycles)
- **Clock**: Posedges at 0 ns and 10 ns; negedges at 5 ns and 15 ns
- **Key Time Point**: 10 ns (cycle 1, assertion failure point)

### Critical Signal Values at Time 10 ns (Failure)

| Signal | Value | Description |
|--------|-------|-------------|
| `spinner32.io_spin` | `0` | Load mode active |
| `spinner32.splReg` | `0` | `!splReg` = true → assertion precondition holds |
| `spinner32.io_din [31:0]` | `01000000000000000000000000000000` | Input data (bit 30 set) |
| `spinner32.io_amount [4:0]` | `11110` (30) | Rotation amount |
| `spinner32.inrReg [31:0]` | `01000000000000000000000000000000` | Current value of input register (just loaded with io.din) |
| `spinner32.s5 [31:0]` | `00000000000000000000000000000001` | Correct rotation of current `inrReg` by 30 (bit 30→bit 0) |
| `spinner32.doutReg [31:0]` | `00000000000000000000000000000000` | Registered output (rotation from PREVIOUS cycle) |
| `spinner32.load_mode_output_correct` | `0` | **Assertion failure**: doutReg (0) ≠ s5 (1) |

### Related Passing Assertion

The companion assertion `barrel_shifter_correct` (checking `tmp5 === s5`) passes, confirming that the barrel shifter hardware (`tmp5`) correctly implements the cascaded rotation. The failure is specifically in the pipeline timing between `doutReg` and `s5`.

## 4. Root Cause Analysis

### Classification: **Incorrect Assertion** (Category 2)

The assertion `load_mode_output_correct` has a **timing mismatch** — it compares a registered signal (`doutReg`) with a combinational signal (`s5`) in the same cycle, without accounting for the one-cycle pipeline delay between `inrReg` and `doutReg`.

### Detailed Explanation

The design has the following register update logic (lines 75–80):

```scala
when(splReg) {
  inrReg := doutReg
}.otherwise {
  inrReg := io.din       // Load mode: inrReg gets io.din
}

doutReg := tmp5           // Output register latches rotated result
```

The barrel shifter `tmp5` is a **combinational** function of `inrReg`. So at the clock edge:

1. **`doutReg`** latches `tmp5`, which is the rotation of **`inrReg`'s value from before the clock edge** (i.e., the OLD value from the previous cycle).
2. **`inrReg`** latches `io.din` (in load mode), getting the NEW input value.

Meanwhile, the reference signal `s5` is also **combinational** and reflects the rotation of the **current `inrReg`** (which was just updated to the NEW value).

This creates a one-cycle mismatch:

| Cycle | Before Clock Edge | After Clock Edge | `doutReg` | `s5` (rotation of `inrReg`) |
|-------|-------------------|------------------|-----------|---------------------------|
| 0     | reset: inrReg=0   | inrReg←io.din=0x40000000 | rots(0, amt=0)=0 | rots(0x40000000, amt=0)=0x40000000 |
| 1     | inrReg=0x40000000 | inrReg←io.din=0x40000000 | rots(0x40000000, amt=30)=1 | rots(0x40000000, amt=30)=1 |

At cycle 1, `doutReg=0` (rotation of OLD inrReg=0 by amount=0) while `s5=1` (rotation of NEW inrReg=0x40000000 by amount=30).

**The assertion `doutReg === s5` fails because `doutReg` always reflects the rotation of `inrReg` from the previous cycle, while `s5` reflects the rotation of the current `inrReg`.**

### Why This Is Not a DUT Bug

The barrel shifter hardware (`tmp5`) correctly computes the rotation (confirmed by the passing `barrel_shifter_correct` assertion). The DUT's pipeline behavior — updating `doutReg` with the rotated value of `inrReg` from the previous cycle — is the intended sequential logic. The design correctly loads `inrReg` with `io.din` and produces `doutReg` as the rotation of the *previously loaded* value in each cycle.

### Suggested Fix

The assertion should use `RegNext(s5)` to match the pipeline delay, or alternatively use `RegNext(!splReg)` as the precondition:

```scala
// Fix option: check doutReg against the rotation from the previous cycle
assertImplies(!splReg, doutReg === RegNext(s5), "load_mode_output_correct")
```

Or equivalently:

```scala
// Fix option: delay the precondition by one cycle
assertImplies(RegNext(!splReg), doutReg === s5, "load_mode_output_correct")
```

The correct fix is to account for the fact that `doutReg` contains the rotation of `inrReg`'s value from the *previous* clock cycle, not the current one.
