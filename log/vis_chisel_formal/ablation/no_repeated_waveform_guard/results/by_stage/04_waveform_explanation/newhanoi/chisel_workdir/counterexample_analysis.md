# Counterexample Analysis Report: Hanoi.disc_0_valid_peg

## 1. Verification Environment

- **Top module**: `Hanoi` (class `Hanoi extends Module with Formal`)
- **Design**: Tower of Hanoi puzzle with 20 discs, each disc stores a peg value (0=A, 1=B, 2=C)
- **Inputs**: `io.from` (2-bit peg source), `io.to` (2-bit peg destination)
- **Key logic**: The design uses priority encoders to find the smallest disc on the `from` peg and the smallest disc on the `to` peg. A move is legal if `sizeFrom < sizeTo` (disc moves to a larger-index disc or empty peg).
- **Formal tool**: Chisel formal verification (BTOR2 backend)

## 2. Violated Assertion

- **Assertion name**: `disc_0_valid_peg` (from waveform filename `Hanoi.disc_0_valid_peg.fst`)
- **Code location**: `newHanoi.scala`, line 53
- **Code snippet**:
  ```scala
  // Safety 1: All disc peg values must be valid (only A=0, B=1, or C=2)
  for (i <- 0 until 20) {
    fvAssert(disc(i) <= 2.U, s"disc_${i}_valid_peg")
  }
  ```
- **Property description**: Every disc register must always contain a valid peg value (0, 1, or 2). The 2-bit disc field should never hold the value 3.

## 3. Waveform Information

- **Waveform file**: `verilog/extra_bench/newhanoi/Hanoi.disc_0_valid_peg.fst`
- **Waveform duration**: 210 ns (21 clock cycles)
- **Failure time**: 200 ns (clock cycle 20)
- **Critical signal values**:

| Time | Signal | Value | Meaning |
|------|--------|-------|---------|
| 190 ns | `Hanoi.io_from [1:0]` | `00` | Peg A (valid) |
| 190 ns | `Hanoi.io_to [1:0]` | `11` | Value 3 (INVALID peg) |
| 190 ns | `Hanoi.sizeFrom [4:0]` | `00000` = 0 | Disc 0 is smallest on peg A |
| 190 ns | `Hanoi._legal_T` | `1` | Move declared "legal" |
| 190 ns | `Hanoi.disc_0 [1:0]` | `00` | Disc 0 on peg A (still valid) |
| 200 ns | `Hanoi.disc_0 [1:0]` | `11` | Disc 0 moved to peg 3 (INVALID) |
| 200 ns | `Hanoi.disc_0_valid_peg` | `0` | **Assertion FAILS** |

## 4. Root Cause Analysis

### Bug Location
- **File**: `newHanoi.scala`
- **Line**: 39
- **Function**: The `legal` signal computation

### The Bug

The `legal` signal is computed as:
```scala
val legal = (sizeFrom < 20.U) && (sizeFrom < sizeTo)
```

This check **does not validate that `io.from` and `io.to` are valid peg values** (only 0, 1, and 2 are valid). A 2-bit UInt can represent 0, 1, 2, or 3. When the formal tool provides `io.to = 3` (invalid), the design misbehaves.

### Detailed Failure Mechanism

1. At time 190 ns, the inputs are `io.from = 0` (peg A) and `io.to = 3` (INVALID peg value).

2. **sizeFrom computation**: Since `io.from = 0` and `disc(0) = 0` (disc 0 is on peg A), the `fromMatches` have `disc(0) === 0 = true`. The reverse priority encoder finds the first match at index 19 (all discs initially on A). `sizeFrom = 19 - 19 = 0`.

3. **sizeTo computation**: Since `io.to = 3` and **no disc is ever on peg 3**, `toMatches` are all false. The `sizeTo` computation falls through: `toMatches.reduce(_ || _) = false`, so `sizeTo := 20.U` (meaning the target peg is empty, which is correct).

4. **legal check**: `(0 < 20) && (0 < 20) = true`. **The move is incorrectly declared legal** because the design treats the invalid peg value 3 as a valid empty destination peg.

5. **Disc update**: Since `legal = true`, the design executes `disc(sizeFrom) := io.to`, which is `disc(0) := 3`. Disc 0 is now on invalid peg 3.

6. **Assertion failure**: `disc(0) <= 2.U` evaluates to false because `disc(0) = 3`.

### Why This Is a DUT Bug (Not an Assertion Error)

The assertion is correct: all discs should always be on valid pegs (0, 1, or 2). The property `disc(i) <= 2.U` is a fundamental safety invariant for the Tower of Hanoi design. The bug is in the design itself:

- The `legal` signal must guard against invalid input values to prevent transitions to invalid states.
- The fix should add validity checks for `io.from` and `io.to`:
  ```scala
  val validPegs = io.from <= 2.U && io.to <= 2.U
  val legal = validPegs && (sizeFrom < 20.U) && (sizeFrom < sizeTo)
  ```

### Prior Cycles of Corruption

The waveform trace shows that disc_1 through disc_18 had already been corrupted to value 3 earlier in the counterexample trace (before disc_0 was affected), confirming the systematic nature of this bug. Each time `io.to = 3` was presented and `sizeFrom` pointed to a disc on peg A, that disc was moved to the invalid peg 3.

### Bug Classification

- **Error type**: `dut_bug`
- **The design fails to constrain that input peg values must be in the valid range [0, 2]**
- **The `legal` signal lacks a validity guard on `io.from` and `io.to`**
