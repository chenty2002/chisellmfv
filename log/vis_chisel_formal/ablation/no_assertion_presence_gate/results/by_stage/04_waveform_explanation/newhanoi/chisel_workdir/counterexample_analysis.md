# Counterexample Analysis Report: disc_0_on_valid_peg

## 1. Verification Environment

- **Top Module**: `Hanoi` (from `newHanoi.scala`)
- **Module Structure**: A Hanoi Tower solver with 20 discs, each disc storing a peg ID (2 bits).
- **Key Components**:
  - `disc` (Vec[20] of UInt(2.W)): Register array storing which peg each disc occupies
  - `io_from` (UInt(2.W)): Input - source peg for a disc move
  - `io_to` (UInt(2.W)): Input - destination peg for a disc move
  - `io_done` (Bool): Output - true when all discs are on peg B
  - `sizeFrom` (UInt(5.W)): Size of the smallest disc on the source peg
  - `sizeTo` (UInt(5.W)): Size of the smallest disc on the destination peg
  - `legal`: True when `sizeFrom < 20` and `sizeFrom < sizeTo`

## 2. Violated Assertion

- **Assertion Name**: `disc_0_on_valid_peg` (from waveform filename `Hanoi.disc_0_on_valid_peg.fst`)
- **Code Location**: `newHanoi.scala`, line 72
- **Code Snippet**:
  ```scala
  for (i <- 0 until 20) {
    fvAssert(disc(i) <= 2.U, s"disc_${i}_on_valid_peg")
  }
  ```
- **Property Description**: All discs must be on valid pegs (A=0, B=1, or C=2). The 2-bit encoding allows value 3 (invalid peg), which must never occur.

## 3. Waveform Information

- **Waveform File**: `verilog/extra_bench/newhanoi/Hanoi.disc_0_on_valid_peg.fst`
- **Time Range**: 0 ns → 210 ns (21 cycles)
- **Failure Point**: Time 200 ns (disc_0_on_valid_peg transitions from 1 to 0)
- **Key Time Points and Signal Values**:

| Time | Signal | Value | Meaning |
|------|--------|-------|---------|
| 30 ns | `io_to` | 11 (3) | **Invalid peg!** Only 0,1,2 are valid |
| 30 ns | `io_from` | 00 (0) | Valid peg A |
| 30 ns | `sizeFrom` | 16 | Disc 16 is top disc on peg A |
| 30 ns | `sizeTo` | 20 | No disc on peg 3 yet → defaults to 20 |
| 30 ns | `legal` | 1 | (16<20) && (16<20) = true → move allowed |
| 40 ns | `disc_16` | 11 (3) | **Disc 16 placed on invalid peg 3** |
| 190 ns | `io_to` | 11 (3) | Again invalid peg |
| 190 ns | `sizeFrom` | 0 | Disc 0 is top disc on peg A |
| 190 ns | `sizeTo` | 16 | Disc 16 is on peg 3 |
| 190 ns | `legal` | 1 | (0<20) && (0<16) = true → move allowed |
| 200 ns | `disc_0` | 11 (3) | **Disc 0 placed on invalid peg 3 — ASSERTION FAILS** |
| 200 ns | `disc_0_on_valid_peg` | 0 | Assertion output goes low (failure) |

## 4. Root Cause Analysis

### Root Cause Category: **Incorrect Top Module Setup (Setup Error)**

### The Bug: Missing Input Constraints for Valid Peg Values

The design's inputs `io.from` and `io.to` are declared as `UInt(2.W)`, which allows values 0-3. However, only values 0 (peg A), 1 (peg B), and 2 (peg C) are valid pegs. Value 3 (`11`) is an invalid peg.

The `legal` check in the design is:
```scala
val legal = (sizeFrom < 20.U) && (sizeFrom < sizeTo)
```
This only checks disc sizes, **not** whether `io.to` or `io.from` are valid pegs.

The formal verification environment does **not** constrain the inputs `io.from` and `io.to` to valid peg values. There are no `fvAssume` calls that would tell the formal tool to only consider valid peg values. While the code contains `fvAssert(io.to <= 2.U, "to_valid_peg")` and `fvAssert(io.from <= 2.U, "from_valid_peg")`, these are **assertions** (properties to prove), not **assumptions** (environment constraints). The formal tool is free to assign any value to these inputs.

### Chain of Events

1. **First Invalid Move (Time 30-40 ns)**: The formal tool assigns `io_to = 11` (invalid peg 3). Since no disc is on peg 3, `sizeTo = 20`. The top disc on peg A has `sizeFrom = 16`. The legality check `16 < 20` passes, so `disc(16)` is assigned to peg 3 (`11`).

2. **Cascade Effect**: Once `disc_16` resides on invalid peg 3, subsequent moves to peg 3 find a valid `sizeTo` (16 from disc_16), allowing further discs to be moved there. Each time a disc from peg A is moved to peg 3, the top disc on peg A shrinks.

3. **Final Failure (Time 190-200 ns)**: When `disc_0` is the top disc on peg A, another move with `io_to = 11` is made. The legality check passes (`0 < 16`), and `disc(0)` is assigned to peg 3, violating `disc_0_on_valid_peg`.

### Evidence from Waveform

- At **time 30 ns**: `io_to = 11` (invalid), `io_from = 00`, `sizeFrom = 16`, `sizeTo = 20`, `legal = 1`. No disc is on peg 3 yet.
- At **time 40 ns**: `disc_16 = 11` — the first disc placed on an invalid peg.
- At **time 190 ns**: `io_to = 11` (invalid again), `sizeFrom = 0` (disc 0 is top on peg A), `sizeTo = 16` (disc 16 still on peg 3), `legal = 1`.
- At **time 200 ns**: `disc_0 = 11` — assertion violation.

### Fix Required

The formal verification setup (or the Chisel design) should add **assumptions/constraints** that restrict `io.from` and `io.to` to valid peg values:

```scala
fvAssume(io.from <= 2.U, "from_valid_peg_assume")
fvAssume(io.to <= 2.U, "to_valid_peg_assume")
```

These assumptions would prevent the formal tool from choosing `io_to=3` (invalid peg), which in turn prevents the cascade of invalid peg assignments. The `to_valid_peg` and `from_valid_peg` assertions would then be provable under these assumptions.
