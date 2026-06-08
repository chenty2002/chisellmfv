# Counterexample Analysis Report: SerialCSAMult.accumulation_invariant

## 1. Verification Environment

- **Benchmark**: smult
- **Top Module**: `SerialCSAMult` (32-bit serial carry-save adder multiplier)
- **Design Under Test**: A serial multiplier using carry-save addition (CSA) to compute the product `i * j` through iterative accumulation. The multiplier bit `j` is multiplied with the full multiplicand `i` to produce `andA = Fill(BITS, j) & i`, which is accumulated via a carry-save adder (sum `s`, carry `c`) over successive cycles.
- **Key Components**:
  - Registers: `s` (31-bit sum), `c` (31-bit carry), `i` (32-bit multiplicand), `j` (1-bit multiplier)
  - Combinational logic: `andA`, `andA_trunc`, `faS` (sum output), `faC` (carry output)
  - Output: `io.o = faS(0)`
- **Source File**: `spm.scala`

## 2. Violated Assertion

- **Assertion Name**: `accumulation_invariant`
- **Waveform Filename**: `SerialCSAMult.accumulation_invariant.fst`
- **File Location**: `spm.scala`, lines 82-85
- **Code Snippet**:
  ```scala
  val prevCS = RegNext(c + s)
  fvAssert((s << 1.U) + c + io.o === prevCS + andA, "accumulation_invariant")
  ```
- **Natural Language Description**: The assertion is intended to verify the carry-save accumulation invariant. According to the comments, the correct recurrence is:
  ```
  2*s_new + c_new + io.o = s_old + c_old + andA
  ```
  However, the assertion as written uses the **old register values** `s` and `c` on the left side (instead of `s_new` and `c_new`), and uses `prevCS` (a delayed version of `c+s` from the previous cycle) on the right side (instead of `c + s` directly).

## 3. Waveform Information

- **Waveform File**: `verilog/extra_bench/smult/SerialCSAMult.accumulation_invariant.fst`
- **Time Range**: 0 ns → 30 ns (3 clock cycles, clock period = 10 ns)
- **Failure Point**: Time = 10 ns (second clock cycle)

### Key Signal Values at Failure (time = 10 ns)

| Signal | Value (binary) | Value (hex) |
|--------|---------------|-------------|
| `s [30:0]` | `0...00` (31 bits) | `0x00000000` |
| `c [30:0]` | `0...00` (31 bits) | `0x00000000` |
| `i [31:0]` | `1000...00` (32 bits) | `0x80000000` |
| `j` | `1` | `1` |
| `andA` (computed) = Fill(32, j) & i | `1000...00` (32 bits) | `0x80000000` |
| `io.o` = `faS(0)` | `0` | `0` |
| `prevCS` (RegNext(c+s)) | `0...00` (31 bits) | `0x00000000` |
| `accumulation_invariant` | `0` (**FAIL**) | `0` |

### Value Snapshots Over Time

| Time | s | c | i | j | io_o | andA |
|------|---|---|---|---|---|---|
| 0 ns | 0 | 0 | 0x00000000 | 0 | 0 | 0x00000000 |
| 10 ns | 0 | 0 | 0x80000000 | 1 | 0 | 0x80000000 |
| 20 ns | 0x40000000 | 0 | 0xFFFFFFFF | 1 | 1 | 0xFFFFFFFF |

### Evaluation at Time 10 ns

**Assertion as written**:
- Left: `(s << 1) + c + io.o` = `(0 << 1) + 0 + 0` = **0**
- Right: `prevCS + andA` = `0 + 0x80000000` = **0x80000000**
- **0 !== 0x80000000 → FAIL** ✗

**Correct recurrence** (using next-state values):
- `s_next = Cat(andA(31), faS(30,1))` = `Cat(1, 0)` = `0x40000000`
- `c_next = faC` = `0`
- Left: `(s_next << 1) + c_next + io.o` = `(0x40000000 << 1) + 0 + 0` = **0x80000000**
- Right: `c + s + andA` = `0 + 0 + 0x80000000` = **0x80000000**
- **0x80000000 === 0x80000000 → PASS** ✓

## 4. Root Cause Analysis

### Classification: **Incorrect Assertion (assertion_error)**

### Buggy Code Location
- **File**: `spm.scala`
- **Lines**: 82-85
- **Code**:
  ```scala
  val prevCS = RegNext(c + s)
  fvAssert((s << 1.U) + c + io.o === prevCS + andA, "accumulation_invariant")
  ```

### Description of the Bug

The assertion contains **two errors** that cause it to fail even though the DUT logic is correct:

1. **Wrong left-hand side**: The assertion uses the **current** register values `s` and `c` in `(s << 1) + c + io.o`, but the correct recurrence requires the **next-state** values `s_next` and `c_next` (the values that will be written to the registers on the next clock edge). The DUT computes:
   - `s_next = Cat(andA(BITS-1), faS(BITS-2, 1))`
   - `c_next = faC`
   
   Using `s` and `c` directly fails to reflect the intended "next state" part of the invariant.

2. **Wrong right-hand side**: The assertion uses `prevCS = RegNext(c + s)` — which is a **one-cycle delayed** version of `(c + s)`. When the DUT has just started (e.g., after reset), `prevCS` holds the initial value `0` rather than the current cycle's `c + s`. The correct right-hand side should be the simple combinational sum `c + s + andA` (the previous sum/carry plus the partial product).

### Why This Causes the Assertion to Fail

At time = 10 ns (the second clock cycle):
- The DUT has just received inputs `io_i_raw = 0x80000000` and `io_j_raw = 1` on the previous clock edge.
- The register values are: `s = 0`, `c = 0`, `i = 0x80000000`, `j = 1`.
- `andA = Fill(32, 1) & 0x80000000 = 0x80000000`.
- The assertion's left side evaluates to `0`, but the right side evaluates to `0x80000000`, a mismatch.

**However, the DUT itself is correct**:
- The next sum value `s_next = Cat(andA(31), faS(30,1)) = Cat(1, 0) = 0x40000000` is correctly computed.
- The next carry `c_next = faC = 0` is correctly computed.
- Using the correct formula `(s_next << 1) + c_next + io.o === c + s + andA` would give `0x80000000 === 0x80000000`, which passes.

### Proposed Fix

Replace lines 82-85 with the correct invariant assertion:

```scala
val s_next = Cat(andA(BITS-1), faS(BITS-2, 1))
val c_next = faC
fvAssert((s_next << 1.U) + c_next + io.o === s + c + andA, "accumulation_invariant")
```

This correctly captures the recurrence: **the next state accumulated value equals the current accumulated value plus the partial product**.
