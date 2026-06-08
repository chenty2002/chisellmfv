# Counterexample Analysis Report: `SerialCSAMult.pipeline_invariant`

## 1. Verification Environment

- **Top module**: `SerialCSAMult` (32-bit serial carry-save adder multiplier)
- **Source file**: `spm.scala` (package `llmverify`)
- **Key components**:
  - `s` (31-bit register): Sum component of the carry-save representation
  - `c` (31-bit register): Carry component of the carry-save representation
  - `i` (32-bit register): Registered multiplicand input
  - `j` (1-bit register): Registered multiplier bit input
  - `andA` (32-bit combinational): Partial product = `Fill(BITS, j) & i`
  - `andA_trunc` (31-bit): `andA[BITS-2:0]`
  - `faS` (31-bit): CSA sum output = `c ^ s ^ andA_trunc`
  - `faC` (31-bit): CSA carry output = `(c & s) | (c & andA_trunc) | (s & andA_trunc)`
- **Pipeline update rule** (posedge, non-reset): `s := Cat(andA[31], faS[30:1])`, `c := faC`, `io.o := faS(0)`

## 2. Violated Assertion

- **Assertion name**: `pipeline_invariant`
- **Source location**: `spm.scala`, lines 72–80

```scala
// Assertion 2: Pipeline arithmetic invariant
// (next_s << 1) + next_c + io.o === s + c + andA
// This shows the serial multiplier correctly accumulates partial products
// while shifting out the LSB of the sum each cycle.
val next_s = Cat(andA(BITS-1), faS(BITS-2, 1))
fvAssert((next_s << 1).asUInt + faC + faS(0) === s + c + andA, "pipeline_invariant")
```

- **Property description**: The assertion checks that the next-cycle pipeline state (with `next_s` left-shifted by 1, plus the carry output `faC`, plus the LSB just output `faS(0)`) equals the current accumulated total (`s + c + andA`). This is intended to verify the serial multiplier correctly accumulates partial products while shifting out bits.

- **Waveform signal**: `SerialCSAMult.pipeline_invariant` transitions from `1` (true) to `0` (false) at time **20 ns**.

## 3. Waveform Information

- **Waveform file**: `verilog/extra_bench/smult/SerialCSAMult.pipeline_invariant.fst`
- **Time range**: 0 ns → 30 ns (3 clock cycles)
- **Clock**: Rising edges at 0 ns, 10 ns, 20 ns
- **Reset**: Never asserted (`io_reset = 0` throughout)

### Key Signal Values at Failure Point (time = 20 ns)

| Signal | Value (binary) | Hex |
|--------|---------------|-----|
| `s [30:0]` | `0011101000111111101101110110111` | `0x1D1FDBB7` |
| `c [30:0]` | `0000000000000000000000000000000` | `0x00000000` |
| `i [31:0]` | `11100010111000000010001010110010` | `0xE2E022B2` |
| `j` | `1` | `1` |
| `faS [30:0]` | `1111111111111111111100100000101` | `0x7FFFF205` |
| **`faC [30:0]`** | **`0000000000000000000001010110010`** | **`0x000002B2`** (nonzero!) |
| `andA_trunc [30:0]` | `1100010111000000010001010110010` | `0x62E022B2` |
| `io_o` (faS(0)) | `1` | `1` |
| `andA [31]` | `1` (since io_i_raw MSB = 1) | — |

### Timeline

| Time | Event |
|------|-------|
| 0 ns | Initial state: `s=0, c=0, i=0, j=0`. Inputs: `io_i_raw=0x3A3FBB6E, io_j_raw=1`. Assertion holds (1). |
| 10 ns | Clock posedge: `i` latches `0x3A3FBB6E`, `j` latches `1`. Now `andA=0x3A3FBB6E, andA[31]=0, faC=0`. Since `faC=0`, assertion holds accidentally. |
| 20 ns | **Clock posedge**: `s` updates to `Cat(andA[31], faS[30:1]) = 0x1D1FDBB7`. `i` latches new `io_i_raw=0xE2E022B2`. Now `andA=0xE2E022B2, andA[31]=1`. `faC = s & andA_trunc = 0x000002B2` (nonzero). **Assertion fails (→0)**. |

## 4. Root Cause Analysis

### Root Cause Category: **Incorrect Assertion** (assertion_error)

### Bug Location

**File**: `spm.scala`, **line 78** (within the `fvAssert` call for `pipeline_invariant`).

### Description of the Bug

The assertion formula is algebraically incorrect. It uses `faC` (the carry output) directly, but in carry-save arithmetic the carry word represents bits that must be **shifted left by 1** (multiplied by 2) before being added. The correct invariant requires `(faC << 1)` instead of `faC`.

**Mathematical derivation:**

The assertion checks:
```
(next_s << 1) + faC + faS(0) === s + c + andA
```

Expanding `next_s = Cat(andA[31], faS[30:1])`:
```
LHS = andA[31]*2^31 + faS[30:1]*2 + faS(0) + faC
    = andA[31]*2^31 + faS + faC

RHS = s + c + andA
    = s + c + andA_trunc + andA[31]*2^31
```

Cancelling `andA[31]*2^31`:
```
faS + faC === s + c + andA_trunc       ... (assertion's reduced form)
```

But the **correct** CSA property (verified by the separate `csa_correctness` assertion on line 69) is:
```
faS + (faC << 1) === s + c + andA_trunc  ... (correct CSA identity)
```

The two are only equivalent when `faC = 0`. Whenever the carry output is nonzero, the assertion fails.

### Evidence from Waveform

- **Cycle 1 (time 10 ns)**: `faC = 0`. The assertion passes because `faC = 0 = faC << 1`. This is a **false positive** — it holds only by coincidence.
- **Cycle 2 (time 20 ns)**: `faC = 0x000002B2` (nonzero). The assertion fails because `faC ≠ faC << 1`. The correct LHS would use `faC << 1 = 0x00000564`.

### Corrected Assertion

The fix is to change line 78 from:
```scala
fvAssert((next_s << 1).asUInt + faC + faS(0) === s + c + andA, "pipeline_invariant")
```
to:
```scala
fvAssert((next_s << 1).asUInt + (faC << 1).asUInt + faS(0) === s + c + andA, "pipeline_invariant")
```

This correctly accounts for the carry word's bit-position significance in the carry-save representation. The comment on line 73 (`// (next_s << 1) + next_c + io.o === s + c + andA`) also needs to be updated to reflect `(faC << 1)`.

### Why It Is Not a DUT Bug or Setup Error

- **Not a DUT bug**: The design's CSA logic (`faS = c ^ s ^ andA_trunc`, `faC = ...`) correctly implements a carry-save adder. The pipeline update (`s := Cat(andA[31], faS[30:1])`, `c := faC`) correctly stores the shifted sum and carry. The separate `csa_correctness` assertion (`faS + (faC << 1) === s + c + andA_trunc`) is correctly formulated and would pass on this same waveform.
- **Not a setup error**: The top module constraints (toggling `io_i_raw`, constant `io_j_raw=1`, no reset) are realistic stimulus for a formal verification of the pipeline invariant. The issue is solely in the assertion expression itself.
