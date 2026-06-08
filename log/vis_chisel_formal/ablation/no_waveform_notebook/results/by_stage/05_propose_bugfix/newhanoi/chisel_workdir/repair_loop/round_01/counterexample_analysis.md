# Counterexample Analysis Report: `newhanoi`

## 1. Verification Environment

- **Top Module**: `Hanoi` (defined in `newHanoi.scala`)
- **Structure**: The module implements a Tower of Hanoi game controller with 20 discs.
  - **Inputs**: `io.from` (2-bit peg source) and `io.to` (2-bit peg destination)
  - **Output**: `io.done` (all discs on peg B)
  - **Internal State**: `disc` array of 20 registers, each holding a 2-bit peg value
  - **Peg Encoding**: A=0, B=1, C=2
- **Key Components**:
  - `sizeFrom`: priority encoder finding the topmost disc on the `from` peg
  - `sizeTo`: priority encoder finding the topmost disc on the `to` peg
  - `legal`: true if a valid disc exists on `from` and it's smaller than the top disc on `to`
  - Sequential update: `when(legal) { disc(sizeFrom) := io.to }`

## 2. Violated Assertion

- **Full Assertion Name**: `disc_0_valid_peg`
- **Code Snippet** (from `newHanoi.scala`, line 60):
  ```scala
  for (i <- 0 until 20) {
    AssertProperty(disc(i) <= 2.U, s"disc_${i}_valid_peg")
  }
  ```
- **Description**: This assertion checks that all 20 discs always reside on a valid peg (A=0, B=1, or C=2). The specific failing instance is `disc(0)`, which asserts `disc(0) <= 2.U`.
- **File Location**: `newHanoi.scala`, lines 59-61 (the for loop generating assertions for i=0 through 19)

## 3. Waveform Information

- **Waveform File**: `verilog/extra_bench/newhanoi/Hanoi.disc_0_valid_peg.fst`
- **Time Range**: 0 ns → 210 ns (21 clock cycles, clock period = 10 ns)
- **Key Time Points**:

| Time (ns) | Cycle | Event |
|-----------|-------|-------|
| 40 | 4 | `io_to` = 3 (invalid peg). `sizeFrom` = 15. `legal` = 1. `disc(15) := 3` → disc 15 corrupted. |
| 50 | 5 | `disc_15` becomes 3 (invalid). |
| 140 | 14 | `io_to` = 3 (invalid peg). `sizeFrom` = 5. `legal` = 1. `disc(5) := 3` → disc 5 corrupted. |
| 150 | 15 | `disc_5` becomes 3 (invalid). |
| 180 | 18 | `io_to` = 3 (invalid peg). `sizeFrom` = 1. `legal` = 1. `disc(1) := 3` → disc 1 corrupted. |
| 190 | 19 | `disc_1` becomes 3. `io_to` = 3. Now disc 1 off peg A. `sizeFrom` = 0 (disc 0 is top on A). `legal` = 1. `disc(0) := 3` → disc 0 corrupted. |
| **200** | **20** | **`disc_0` becomes 3. Assertion `disc_0_valid_peg` fails (`disc(0) = 3 > 2`).** |

- **Critical Signal Values at Failure Point (200 ns)**:
  | Signal | Value | Meaning |
  |--------|-------|---------|
  | `Hanoi.disc_0 [1:0]` | `11` (3) | **INVALID peg value** |
  | `Hanoi.io_to [1:0]` | `11` (3) | Invalid input (not A, B, or C) |
  | `Hanoi.io_from [1:0]` | `00` (0) | Peg A |
  | `Hanoi.sizeFrom [4:0]` | `10100` (20) | No disc on peg A anymore |
  | `Hanoi.legal` | 0 | Move not legal (no disc on source) |

## 4. Root Cause Analysis

### Bug Classification: **DUT Bug**

### Buggy Code Location
- **File**: `newHanoi.scala`
- **Line**: 52 (the sequential update logic)
- **Code**:
  ```scala
  when(legal) {
    disc(sizeFrom) := io.to
  }
  ```

### Description of the Bug
The design unconditionally writes `io.to` into the disc register whenever a move is deemed legal, **without checking whether `io.to` is a valid peg value** (0, 1, or 2). Since `io.to` is a `UInt(2.W)`, it can take the value 3 (binary `11`), which is not one of the three valid pegs (A=0, B=1, C=2). When `io.to = 3` and `legal` is true, the disc at index `sizeFrom` gets set to the invalid value 3, corrupting the game state.

### Propagation Chain
1. **Cycle 4 (40 ns)**: Formal tool drives `io_to = 3`, and `legal = 1` (there exists a valid move on `from` peg). `disc(15) := 3` → disc 15 corrupted to invalid value 3.
2. **Cycle 14 (140 ns)**: `io_to = 3` again, `legal = 1`. `disc(5) := 3` → disc 5 corrupted.
3. **Cycle 18 (180 ns)**: `io_to = 3`, `legal = 1`. `disc(1) := 3` → disc 1 corrupted.
4. **Cycle 19 (190 ns)**: After disc 1 is moved off peg A (to invalid peg 3), disc 0 becomes the topmost disc on peg A. `io_from = 0` (A), `io_to = 3`, `legal = 1`. `disc(0) := 3` → disc 0 corrupted.
5. **Cycle 20 (200 ns)**: Assertion `disc_0_valid_peg` fires because `disc(0) = 3 > 2`.

### Why This Causes the Assertion to Fail
The assertion `disc(0) <= 2.U` expects every disc to hold a valid peg value (0, 1, or 2). However, because the design does not guard against `io.to` being set to the invalid value 3 during a legal move, the internal state becomes corrupted. Once disc 0 is updated to value 3 (via `disc(0) := io.to` at cycle 19), the assertion check on the next clock edge detects `disc(0) = 3 > 2` and fails.

### Possible Fixes
1. **Add a validity guard** in the sequential update:
   ```scala
   when(legal && io.to <= 2.U) {
     disc(sizeFrom) := io.to
   }
   ```
   This prevents any disc from being updated to an invalid peg value.

2. **Constrain the inputs** in the formal verification setup by adding an assumption:
   ```scala
   AssertProperty(io.to <= 2.U, None, None, Some("io_to_valid"))
   ```
   (Though this would be an assumption, not an assertion, in formal verification.)

3. **Both** approaches together for defense-in-depth.
