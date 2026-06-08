# Counterexample Analysis Report: Hanoi.disc_0_valid_peg

## 1. Verification Environment

### Top Module Structure
- **Module**: `Hanoi` (in package `llmverify`)
- **Source File**: `newHanoi.scala`
- **Design Under Test**: A Tower of Hanoi solver with 20 discs, each disc storing its current peg (A=0, B=1, C=2) in a 2-bit register.

### Key Components and Connections
| Component | Type | Description |
|---|---|---|
| `disc(0..19)` | `RegInit(Vec(20, UInt(2.W)))` | 20 disc registers, each stores the peg the disc is on (initialized to 0/A) |
| `io.from` | `Input(UInt(2.W))` | Source peg for next move |
| `io.to` | `Input(UInt(2.W))` | Destination peg for next move |
| `sizeFrom` | `Wire(UInt(5.W))` | Index of the smallest disc on the `from` peg (reverse priority encoder) |
| `sizeTo` | `Wire(UInt(5.W))` | Index of the smallest disc on the `to` peg (reverse priority encoder) |
| `legal` | `Wire(Bool())` | True when a legal move exists: `sizeFrom < 20` AND `sizeFrom < sizeTo` |

### Update Logic
```scala
when(legal) {
  disc(sizeFrom) := io.to
}
```

## 2. Violated Assertion

- **Assertion Name**: `disc_0_valid_peg` (from waveform filename `Hanoi.disc_0_valid_peg.fst`)
- **Code Location**: `newHanoi.scala`, line 67
- **Assertion Code**:
  ```scala
  for (i <- 0 until 20) {
    fvAssert(disc(i) <= 2.U, s"disc_${i}_valid_peg")
  }
  ```
- **Property**: Each disc register must never contain an invalid peg value. Valid peg values are A=0, B=1, C=2. The value 3 (binary `11`) is invalid and should never be stored in any disc register.

## 3. Waveform Information

- **Waveform File**: `verilog/extra_bench/newhanoi/Hanoi.disc_0_valid_peg.fst`
- **Time Range**: 0 ns → 210 ns (21 cycles at 10 ns/cycle)
- **Assertion Failure Time**: **200 ns** (cycle 20)
- **Failure Value**: `disc_0 [1:0]` = `11` (value 3) at time 200 ns, causing `disc_0_valid_peg` to go low (0)

### Key Signal Values at Critical Time Points

**Time 0 ns (initial state):**
| Signal | Value | Meaning |
|---|---|---|
| `disc_0 [1:0]` | `00` | Disc 0 on peg A |
| `disc_19 [1:0]` | `00` | Disc 19 on peg A |
| `io_from [1:0]` | `00` | from = peg A |
| `io_to [1:0]` | `11` | **to = 3 (INVALID)** |
| `sizeFrom [4:0]` | `10011` (19) | Top disc on peg A is at index 19 |
| `legal` | `1` | legal move (19 < 20 and 19 < sizeTo=20) |
| Action | disc(19) := 3 | **Disc 19 corrupted with value 3** |

**Time 160 ns:**
| Signal | Value | Meaning |
|---|---|---|
| `disc_0 [1:0]` | `00` | Disc 0 still on peg A |
| `disc_19 [1:0]` | `11` | Disc 19 stays corrupted (value 3) |
| `io_to [1:0]` | `11` | **to = 3 (INVALID again)** |
| `sizeFrom [4:0]` | `00011` (3) | Smallest disc on peg A is at index 3 |
| `legal` | `1` | legal move |
| Action | disc(3) := 3 | **Disc 3 corrupted with value 3** |

**Time 190 ns:**
| Signal | Value | Meaning |
|---|---|---|
| `disc_0 [1:0]` | `00` | Disc 0 still on peg A |
| `disc_3 [1:0]` | `11` | Disc 3 corrupted (value 3) |
| `io_from [1:0]` | `00` | from = peg A |
| `io_to [1:0]` | `11` | **to = 3 (INVALID)** |
| `sizeFrom [4:0]` | `00000` (0) | Smallest disc on peg A is disc 0 itself |
| `legal` | `1` | legal move (0 < 20 and 0 < sizeTo=19) |
| Action | disc(0) := 3 | **Disc 0 corrupted with value 3** |

**Time 200 ns (assertion failure):**
| Signal | Value | Meaning |
|---|---|---|
| `disc_0 [1:0]` | `11` | Disc 0 = 3 (INVALID!) |
| `disc_0_valid_peg` | `0` | Assertion **FAILS** (disc(0) > 2) |

## 4. Root Cause Analysis

### Bug Classification: **DUT Bug**

### Bug Location
- **File**: `newHanoi.scala`
- **Lines**: 53-54 (legal move computation), 56-57 (disc update)
- **Module**: `Hanoi`

### Bug Description

The DUT lacks input validation for `io.to`. The `legal` signal is computed as:
```scala
val legal = (sizeFrom < 20.U) && (sizeFrom < sizeTo)
```

This only checks that a disc exists on the `from` peg and that the moved disc is smaller than the top disc on the `to` peg. It does **not** check whether `io.to` is a valid peg value (0, 1, or 2). When `io.to = 3` (invalid):

1. **`sizeTo` can match corrupted discs**: The computation `disc.map(_ === io.to)` finds discs that already hold value 3 (from previous corruption). This creates a feedback loop where corruption propagates.

2. **Illegal write proceeds**: Since `legal` does not include `io.to <= 2.U`, the update `disc(sizeFrom) := io.to` writes the invalid value 3 into a disc register.

### Propagation Chain

```
Cycle 0:  io_to=3, all discs=0 (peg A)  → legal=1 (sizeFrom=19 < sizeTo=20)
           → disc(19) := 3  (disc 19 corrupted)

...

Cycle 16: io_to=3, disc_19=3             → legal=1 (sizeFrom=3 < sizeTo=19)
           → disc(3) := 3   (disc 3 corrupted)

Cycle 19: io_to=3, disc_3=3, disc_19=3   → legal=1 (sizeFrom=0 < sizeTo=19)
           → disc(0) := 3   (disc 0 corrupted → ASSERTION FAILS)
```

### Why This Is a DUT Bug

In a real hardware implementation, `io.to` could be driven to value 3 by an upstream bug, signal integrity issue, or uninitialized state. A robust design should be defensive and prevent writing invalid peg values into its state registers.

### Fix

Add a validity check for `io.to` in the update condition:

```scala
// Current (buggy):
when(legal) {
  disc(sizeFrom) := io.to
}

// Fixed:
when(legal && io.to <= 2.U) {
  disc(sizeFrom) := io.to
}
```

This ensures that even if `legal` evaluates to true, the disc register is only updated when `io.to` contains a valid peg encoding (0, 1, or 2).
