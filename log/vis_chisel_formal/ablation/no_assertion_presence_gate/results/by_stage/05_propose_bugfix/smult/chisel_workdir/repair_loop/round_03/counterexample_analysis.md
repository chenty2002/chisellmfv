# Counterexample Analysis Report: SerialCSAMult.accumulation_invariant

## 1. Verification Environment

### Top Module and Structure
- **Top Module**: `SerialCSAMult` (BITS = 32)
- **File**: `spm.scala`
- **Key Components**:
  - `s` (31-bit register): Sum accumulator for the carry-save adder
  - `c` (31-bit register): Carry accumulator for the carry-save adder
  - `i` (32-bit register): Registered multiplicand
  - `j` (1-bit register): Registered multiplier bit
  - **Combinational Logic**: 
    - `andA = Fill(BITS, j) & i` – partial product (i if j=1, else 0)
    - `andA_trunc = andA(30,0)` – lower 31 bits of partial product
    - `faS = c ^ s ^ andA_trunc` – CSA sum output
    - `faC = (c & s) | (c & andA_trunc) | (s & andA_trunc)` – CSA carry output
    - `io.o = faS(0)` – serial output bit

### Connections
```
io.i_raw ---[Reg]---> i
io.j_raw ---[Reg]---> j
(i, j) ------------> andA = Fill(32,j) & i
(s, c, andA_trunc) -> faS, faC (combinational CSA)
faS(0) ------------> io.o
s_next = Cat(andA(31), faS(30,1)) ---[Reg]---> s
c_next = faC --------------------------[Reg]---> c
```

### Design Description
This is a serial multiplier using carry-save addition. Each cycle, the partial product `andA` is added to the accumulated sum `(s, c)` via a carry-save adder. The LSB of the CSA sum is output serially (`io.o`), the remaining sum bits are shifted right to form the new `s`, and the CSA carry `faC` becomes the new `c`.

## 2. Violated Assertion

- **Assertion Name**: `accumulation_invariant`
- **File**: `spm.scala`
- **Line**: 76

### Code Snippet (lines 70–76)
```scala
// Assertion 3: Accumulation Invariant
// The serial multiplier correctly accumulates partial products.
// The key recurrence: 2*s_new + c_new + io.o = s_old + c_old + andA
// This proves that the carry-save accumulation preserves the arithmetic sum.
// Use next-state values (s_next = Cat(andA[BITS-1], faS[BITS-2:1]), c_next = faC)
// and guard with io.reset to exclude reset cycles from the invariant check.
fvAssert(io.reset || ((Cat(andA(BITS-1), faS(BITS-2, 1)) << 1.U) + faC + io.o === s + c + andA), "accumulation_invariant")
```

### Natural Language Description
The assertion checks that when `io.reset` is de-asserted (`false`), the following arithmetic invariant holds:

> 2 × s<sub>next</sub> + c<sub>next</sub> + io.o = s + c + andA

where:
- s<sub>next</sub> = `Cat(andA(31), faS(30,1))` (the next value of register s)
- c<sub>next</sub> = `faC` (the next value of register c)
- io.o = `faS(0)` (the serial output bit)
- s, c, andA are the CURRENT values of the registers and partial product

The invariant should ensure that the carry-save accumulation preserves the total arithmetic sum across clock cycles.

## 3. Waveform Information

### Waveform File
- **Path**: `verilog/extra_bench/smult/SerialCSAMult.accumulation_invariant.fst`
- **Format**: FST
- **Duration**: 30 ns (3 clock cycles)

### Key Time Points and Signal Values

| Time (ns) | Event |
|-----------|-------|
| 0         | Initial state; `accumulation_invariant` = 1 (pass) |
| 5         | Clock falling edge |
| 10        | Clock rising edge #1; registers update (s stays 0, c stays 0, i becomes 0x37E8, j becomes 1) |
| 15        | Clock falling edge |
| 20        | Clock rising edge #2; **assertion fails** (`accumulation_invariant` = 0) |
| 25        | Clock falling edge; assertion remains failing |
| 30        | End of trace |

**Critical signal values at time 20 ns (failure point):**

| Signal | Value (binary) | Decimal/Hex |
|--------|----------------|-------------|
| `s [30:0]` | `0000000000000000001101111110100` | 0x1BF4 (7156) |
| `c [30:0]` | `0000000000000000000000000000000` | 0 |
| `i [31:0]` | `01000000000000000100111111111110` | 0x40004FFE |
| `j` | `1` | 1 |
| `andA_trunc [30:0]` | `1000000000000000100111111111110` | bits 30..0 of 0x40004FFE |
| `faS [30:0]` | `1000000000000000101010000001010` | CSA sum output |
| `faC [30:0]` | `0000000000000000000101111110100` | CSA carry output |
| `io.o` | `0` | LSB of faS |

## 4. Root Cause Analysis

### Verdict: **Incorrect Assertion (Assertion Bug)**

**The assertion itself is wrong.** The DUT implementation is correct; the assertion's left-hand side is missing a factor of 2 on the `faC` term.

### Bug Location
- **File**: `spm.scala`, **Line 76**
- **Module**: `SerialCSAMult`
- **Bug Type**: Assertion equation error — missing `(faC << 1.U)` instead of bare `faC`

### Mathematical Derivation

The assertion checks:

```
LHS = (Cat(andA(31), faS(30,1)) << 1) + faC + io.o
RHS = s + c + andA
```

Let s<sub>next</sub> = `Cat(andA(31), faS(30,1))`.

**Step 1**: Expand 2 × s<sub>next</sub> + io.o:

```
2 × s_next + io.o = 2 × Cat(andA(31), faS(30,1)) + faS(0)
                  = andA(31) × 2³¹ + faS(30) × 2³⁰ + … + faS(1) × 2¹ + faS(0)
                  = andA(31) × 2³¹ + faS
```

Therefore:
```
LHS = andA(31) × 2³¹ + faS + faC
```

**Step 2**: Expand RHS:

```
RHS = s + c + andA
    = s + c + andA(31) × 2³¹ + andA_trunc
```

Cancelling `andA(31) × 2³¹` from both sides, the assertion requires:

```
faS + faC = s + c + andA_trunc    ... (Equation A)
```

**Step 3**: The CSA full-adder identity (assertion `CSA_correctness` on line 64) gives:

```
faS + 2 × faC = s + c + andA_trunc    ... (Equation B)
```

Substituting Equation B into Equation A:

```
faS + faC = faS + 2 × faC
⇒ faC = 2 × faC
⇒ faC = 0
```

**Conclusion**: The assertion only holds when `faC = 0`. In general, `faC ≠ 0`, so the assertion fails.

### Correct Assertion

The left-hand side should include `(faC << 1.U)` (i.e., 2 × faC) instead of bare `faC`:

```scala
fvAssert(io.reset || ((Cat(andA(BITS-1), faS(BITS-2, 1)) << 1.U) + (faC << 1.U) + io.o === s + c + andA), "accumulation_invariant")
```

**Verification**:
```
LHS_correct = andA(31) × 2³¹ + faS + 2 × faC
            = andA(31) × 2³¹ + (s + c + andA_trunc)   [by CSA identity]
            = s + c + andA(31) × 2³¹ + andA_trunc
            = s + c + andA                              [since andA = andA(31) × 2³¹ + andA_trunc]
            = RHS ✓
```

### Why the Assertion Passed at Time 10 but Failed at Time 20

| Time | faC | Why assertion passed/failed |
|------|-----|-----------------------------|
| 0    | 0   | Passes: when faC=0, Equation A and Equation B are identical |
| 10   | 0   | Passes: same reason — no carry generated (s=0, c=0) |
| 20   | non-zero | **Fails**: faC = `0000000000000000000101111110100` ≠ 0, so Equation A (faS+faC) differs from Equation B (faS+2×faC) by exactly faC |

### Evidence from Waveform

At time 20:
- **faC ≠ 0** = `0000000000000000000101111110100` (decimal 1012)
- The accumulation invariant fails **precisely at the first cycle where faC becomes non-zero**
- All other signals (s, c, i, j, andA, faS) are correctly computed by the CSA logic

### Summary

The DUT logic (`SerialCSAMult`) is **correct** — the CSA correctly produces `faS` and `faC`, the registers properly update with `s := Cat(andA(31), faS(30,1))` and `c := faC`, and the output `io.o := faS(0)` is correct.

The bug is in **Assertion 3** (`accumulation_invariant`), which uses `faC` instead of `(faC << 1.U)` in the left-hand side sum. This causes the assertion to fail whenever the CSA generates a carry output (`faC ≠ 0`), which is the normal operating condition of a carry-save adder. The correct recurrence `2 × s_next + 2 × c_next + io.o = s + c + andA` is arithmetically sound and would pass for all counterexample traces.
