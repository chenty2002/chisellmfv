# Counterexample Analysis Report: `overflow_detection_correct`

## 1. Verification Environment

- **Top Module**: `rollercoasterNumbers` (from `rcnum16.scala`)
- **Design Under Test**: A Collatz/3n+1 number generator implementing one step:
  - If `numReg` is odd: compute `3*numReg + 1` (with overflow to 0)
  - If `numReg` is even: compute `numReg / 2` (right-shift)
- **Key Components**:
  - `numReg` — 16-bit register storing the current Collatz number
  - `tmp` — 18-bit computed value of `3*numReg + 1` using a shift-add method
  - `overflow` — overflow detection from `tmp[17] | tmp[16]`
  - `threeNplus1` — 18-bit computed value of `3*numReg + 1` using multiplication (`3.U * numReg + 1.U`)
- **Assertion Type**: Formal equivalence check between two methods of computing `3*numReg+1`

## 2. Violated Assertion

- **Assertion Name**: `overflow_detection_correct`
- **Waveform File**: `rollercoasterNumbers.overflow_detection_correct.fst`
- **Source Location**: `rcnum16.scala`, lines 36–38

**Code Snippet (rcnum16.scala)**:
```scala
// 1. Overflow detection correctness:
//    overflow must be true exactly when 3*numReg+1 > 65535 (exceeds 16-bit range)
val threeNplus1 = 3.U * numReg + 1.U
fvAssert(overflow === (threeNplus1 > 65535.U), "overflow_detection_correct")
```

**Generated Verilog Assertion** (rollercoasterNumbers.sv):
```verilog
overflow_detection_correct:
    assert property (@(posedge clock) disable iff (~hasBeenReset)
                     overflow == (|(_threeNplus1_T_1[17:16])));
```

**Property Description**: The assertion checks that the overflow detection computed via the `tmp` signal (shift-add method) matches the overflow detection computed via the `threeNplus1` signal (multiplication method). Since both methods compute `3*numReg + 1`, their overflow bits should be identical. The Verilog implements `threeNplus1 > 65535` as `|(_threeNplus1_T_1[17:16])` — checking if either of the upper two bits (bits 17 or 16) is set, which indicates the 18-bit result exceeds 16-bit range.

## 3. Waveform Information

- **Waveform File**: `verilog/extra_bench/rcnum_rcnum16/rollercoasterNumbers.overflow_detection_correct.fst`
- **Time Range**: 0 ns – 10 ns (1 clock cycle)
- **Failure Time**: 0 ns (the assertion is already violated at the initial state)

### Key Signal Values at Time 0 ns

| Signal | Value (binary) | Value (hex) | Notes |
|--------|-------|------|-------|
| `numReg [15:0]` | `1000000000000000` | `0x8000` (32768) | Initial register value |
| `_GEN [17:0]` | `001000000000000000` | `0x8000` (32768) | `{2'h0, numReg}` |
| `_tmp_T_2 [17:0]` | `011000000000000001` | `0x18001` (98305) | **Correct** `3*numReg+1` via shift-add |
| `_threeNplus1_T_1 [17:0]` | `000000000000000001` | `0x1` (1) | **WRONG** — should be `0x18001` |
| `overflow` | `1` | — | `_tmp_T_2[17] \| _tmp_T_2[16] = 0 \| 1` |
| `mult_23.left [17:0]` | `001000000000000000` | `0x8000` | Multiplier input A = `_GEN` |
| `mult_23.right [1:0]` | `11` | `3` | Multiplier input B |
| `mult_23.out [19:0]` | `00000000000000000000` | `0` | Multiplier output — **WRONG** (should be `0x18000`) |
| `hasBeenReset` | `1` | — | Assertion is enabled |
| `overflow_detection_correct` | `1` | — | Assertion FAILED |

## 4. Root Cause Analysis

### Buggy Location

- **File**: `rcnum16.scala`, line 37
- **Module**: `rollercoasterNumbers`
- **Signal**: `threeNplus1` / `_threeNplus1_T_1`

### The Bug

The multiplication `3.U * numReg` computes incorrectly when `numReg = 0x8000`. The expected result is `3 * 32768 = 98304 = 0x18000`, but the actual multiplier output is `0`.

### Evidence from Waveform

**Correct computation (shift-add method — `tmp`)**:
```
_tmp_T_2 = _GEN + {1'h0, numReg, 1'h1}
         = 0x8000 + {0, 0x8000, 1}
         = 0x8000 + 0x10001
         = 0x18001   ✓
```

**Incorrect computation (multiplication method — `threeNplus1`)**:
```
_threeNplus1_T_1 = _GEN * 18'h3 + 18'h1
                 = 0x8000 * 3 + 1
                 = 0x18000 + 1
                 = expected: 0x18001
                 = actual:   0x00001   ✗
```

The multiplier `mult_23` receives:
- `left  = 0x8000` (18-bit value of `_GEN`)
- `right = 3` (2-bit value)
- `out   = 0` (20-bit result — should be `0x18000`)

Since `mult_23.out = 0`, the addition `+ 1` produces `_threeNplus1_T_1 = 1` instead of `0x18001`.

### Why the Assertion Fails

The assertion checks: `overflow == (|(_threeNplus1_T_1[17:16]))`

- `overflow = _tmp_T_2[17] | _tmp_T_2[16] = 0 | 1 = 1` (correctly detects overflow for `0x18001`)
- `|(_threeNplus1_T_1[17:16]) = |(0, 0) = 0` (incorrect because `_threeNplus1_T_1 = 1`, not `0x18001`)
- Result: `1 == 0` → **false** → assertion violation

### Root Cause Category

**Bug in the Original Design (DUT Bug)**: The multiplier-based computation `3.U * numReg + 1.U` produces incorrect output when `numReg = 0x8000`. The addition-based computation (`tmp`) works correctly, but the multiplication-based computation (`threeNplus1`) fails due to the multiplier producing output `0` instead of `0x18000` for the input pair `(0x8000, 3)`.

### Impact

This bug means the `threeNplus1` signal is not a reliable computation of `3*numReg + 1`. Any formal assertion relying on this signal (including assertions 1, 3, and 4 in the source) would be impacted. The overflow detection from `tmp` is correct; the problem is that the reference computation (`threeNplus1`) gives a wrong result.
