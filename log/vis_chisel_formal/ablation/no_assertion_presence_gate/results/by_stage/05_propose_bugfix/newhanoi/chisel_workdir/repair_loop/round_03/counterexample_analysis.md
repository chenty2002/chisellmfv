# Counterexample Analysis Report: `Hanoi.disc_moved_to_destination`

## 1. Verification Environment

- **Top Module**: `Hanoi` (in package `llmverify`)
- **Source File**: `newHanoi.scala` (lines 1–99)
- **Design Under Test**: A Tower of Hanoi puzzle solver with 20 discs on 3 pegs (A=0, B=1, C=2). The module accepts a from-peg and to-peg input and performs legal disc moves according to the Tower of Hanoi rules. All discs start on peg A (0) and the goal is to move all to peg B (1).
- **Key Components**:
  - `disc`: Vec of 20 registers, each storing the peg (UInt(2.W)) of a disc
  - `sizeFrom` / `sizeTo`: Priority-encoder-based logic to find the top disc on each peg
  - `legal`: Boolean indicating whether the proposed move is legal (smaller disc on larger disc or empty destination)
  - `validPegs`: Guard that both `io.from` and `io.to` are ≤ 2 (valid pegs A, B, or C)
  - Register-based pipeline capture: `prevValid`, `legalPrev`, `sizeFromPrev`, `toPrev`

## 2. Violated Assertion

- **Full Assertion Name**: `disc_moved_to_destination`
- **Waveform Filename**: `Hanoi.disc_moved_to_destination.fst`
- **Code Snippet** (lines 82–87 of `newHanoi.scala`):
  ```scala
  val prevValid = RegInit(false.B)
  prevValid := true.B
  val legalPrev = RegNext(legal)
  val sizeFromPrev = RegNext(sizeFrom)
  val toPrev = RegNext(io.to)
  fvAssert(!prevValid || !legalPrev || (disc(sizeFromPrev) === toPrev),
           "disc_moved_to_destination")
  ```
- **Property Description**: When a legal move was observed in the previous cycle (indicated by `legalPrev === true` and `prevValid === true`), the disc at position `sizeFromPrev` (the disc that was supposed to be moved) must now reside on peg `toPrev` (the destination peg of the move).
- **File Location**: `newHanoi.scala`, lines 82–87.

## 3. Waveform Information

- **Waveform File Path**: `verilog/extra_bench/newhanoi/Hanoi.disc_moved_to_destination.fst`
- **Waveform Duration**: 2 cycles (0 ns → 20 ns)

### Key Time Points and Signal Values

| Time | Signal | Value | Meaning |
|------|--------|-------|---------|
| 0 ns | `Hanoi.clock` | 1 | Rising edge of cycle 0 |
| 0 ns | `Hanoi.io_from [1:0]` | 00 | `io.from = 0` (peg A, valid) |
| 0 ns | `Hanoi.io_to [1:0]` | 11 | `io.to = 3` (INVALID peg) |
| 0 ns | `Hanoi.legal` | 1 | Move is deemed "legal" by move rules |
| 0 ns | `Hanoi.sizeFrom [4:0]` | 10011 (19) | Top disc on peg 0 is disc 19 (smallest disc) |
| 0 ns | `Hanoi.sizeTo [4:0]` | 10100 (20) | No disc on peg 3 (peg 3 is invalid/empty) |
| 0 ns | `Hanoi.disc_19 [1:0]` | 00 | Disc 19 is on peg A (0) |
| 10 ns | `Hanoi.clock` | 1 | Rising edge of cycle 1 |
| 10 ns | `Hanoi.prevValid` | 1 | prevValid is now true (was initialized false) |
| 10 ns | `Hanoi.legalPrev` | 1 | legalPrev captures cycle 0's `legal=true` |
| 10 ns | `Hanoi.sizeFromPrev [4:0]` | 10011 (19) | Captured sizeFrom from cycle 0 |
| 10 ns | `Hanoi.toPrev [1:0]` | 11 (3) | Captured io.to from cycle 0 |
| 10 ns | `Hanoi.disc_19 [1:0]` | 00 | Disc 19 is STILL on peg A — never moved! |
| 10 ns | `Hanoi.disc_moved_to_destination` | **0** | **ASSERTION FAILS** |

## 4. Root Cause Analysis

### Bug Classification: **Incorrect Assertion (assertion_error)**

### Description

The assertion `disc_moved_to_destination` is **missing the `validPegs` guard** in its antecedent. The design's update path is guarded by BOTH `legal` AND `validPegs` (line 63):

```scala
when(legal && validPegs) {
    disc(sizeFrom) := io.to
}
```

However, the assertion only checks `legalPrev` (line 86):

```scala
fvAssert(!prevValid || !legalPrev || (disc(sizeFromPrev) === toPrev),
         "disc_moved_to_destination")
```

When the formal solver assigns an invalid peg value (3) to `io.to`:
- `validPegs` evaluates to `false` (because `io.to <= 2.U` is false)
- `legal` can still be `true` (because `sizeFrom < sizeTo` holds — no disc is on the invalid peg 3, so `sizeTo = 20`, and `sizeFrom = 19`)
- Since `legal && validPegs` is `false`, **the disc is never updated**
- In the next cycle, the assertion fires, expecting `disc(19) === 3`, but disc(19) is still `0` (peg A)

### Buggy Code

**File**: `newHanoi.scala`, lines 82–86

```scala
val prevValid = RegInit(false.B)
prevValid := true.B
val legalPrev = RegNext(legal)
val sizeFromPrev = RegNext(sizeFrom)
val toPrev = RegNext(io.to)
fvAssert(!prevValid || !legalPrev || (disc(sizeFromPrev) === toPrev),
         "disc_moved_to_destination")
```

### Fix

The assertion must also check that `validPegs` was true in the previous cycle (i.e., the inputs were valid pegs). Add a `validPegsPrev` register and include it in the assertion guard:

```scala
val prevValid = RegInit(false.B)
prevValid := true.B
val legalPrev = RegNext(legal)
val sizeFromPrev = RegNext(sizeFrom)
val toPrev = RegNext(io.to)
val validPegsPrev = RegNext(validPegs)
fvAssert(!prevValid || !legalPrev || !validPegsPrev || (disc(sizeFromPrev) === toPrev),
         "disc_moved_to_destination")
```

This ensures the assertion only fires when the disc was **actually moved** (i.e., the move was legal AND the inputs were valid pegs). The antecedent now matches the guard on the update path: `legal && validPegs`.

### Evidence Summary

1. **Waveform at time 0**: `io.from = 0` (valid), `io.to = 3` (invalid), `legal = 1`, `sizeFrom = 19`, `sizeTo = 20`. Since `validPegs = (0 ≤ 2) && (3 ≤ 2) = false`, the disc update `disc(19) := 3` does NOT execute.
2. **Waveform at time 10**: `prevValid = 1`, `legalPrev = 1`, but `disc_19` remains `0` (peg A) because the update was suppressed by the `validPegs` guard.
3. **Code analysis**: Lines 59–64 show `when(legal && validPegs)` guards the disc update; line 86 shows the assertion only checks `legalPrev`, omitting the `validPegs` condition.
