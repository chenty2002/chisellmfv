# Counterexample Analysis: `disc_moved_to_destination` Assertion Failure

## 1. Verification Environment

### Top Module Structure
- **Top module**: `Hanoi` (in package `llmverify`, file `newHanoi.scala`)
- **Inputs**: `io.from` (UInt(2.W) — source peg), `io.to` (UInt(2.W) — destination peg)
- **Outputs**: `io.done` (Bool)
- **Internal state**: `disc` — a 20-element Vec[UInt(2.W)] of pegs (A=0, B=1, C=2), initialized to all A

### Key Components
- **`disc(0..19)`**: Array of disc positions, each storing a peg value (0=A, 1=B, 2=C)
- **`sizeFrom`**: Index of the topmost disc on the `from` peg (range 0–19), or 20 if empty
- **`sizeTo`**: Index of the topmost disc on the `to` peg (range 0–19), or 20 if empty
- **`legal`**: Boolean flag — true when `(sizeFrom < 20) && (sizeFrom < sizeTo)`
- **`validPegs`**: Guard — true when both `io.from <= 2` and `io.to <= 2`
- **`resetCounter`**: Library module providing `hasBeenReset` and `hasBeenResetReg`

### Design Description
The Hanoi module implements a Tower of Hanoi puzzle with 20 discs on 3 pegs (A, B, C). It accepts moves as `(from, to)` peg pairs and checks legality: a disc can be moved only if the source peg has a disc (`sizeFrom < 20`) and the destination peg's top disc is larger (`sizeFrom < sizeTo`). All discs start on peg A, and the goal is to move them all to peg B.

## 2. Violated Assertion

### Assertion Name (from waveform filename)
`disc_moved_to_destination`

### Code Snippet (newHanoi.scala, lines 68–73)
```scala
val legalPrev = RegNext(legal)
val sizeFromPrev = RegNext(sizeFrom)
val toPrev = RegNext(io.to)
fvAssert(!legalPrev || (disc(sizeFromPrev) === toPrev),
         "disc_moved_to_destination")
```

### Natural Language Description
**Property**: After every legal move (where `legalPrev` is true), the disc that was moved (identified by `sizeFromPrev`) must now reside on the destination peg (`toPrev`) in the next cycle. In other words, when the design performs a legal disc move, the disc's new position recorded in `disc(sizeFromPrev)` must equal the destination peg `toPrev`.

### File Location
- **File**: `newHanoi.scala`, lines 68–73

## 3. Waveform Information

### Full Path to Waveform File
`verilog/extra_bench/newhanoi/Hanoi.disc_moved_to_destination.fst`

### Time Range
0 ns → 10 ns (1 clock cycle, with clock falling edge at 5 ns)

### Critical Signal Values at Each Time Point

#### At time 0 ns (initial/rising edge):
| Signal | Value (Binary) | Decoded |
|---|---|---|
| `disc_moved_to_destination` (assertion output) | `1` | Assertion FAILED |
| `legal` | `0` | No legal move in current cycle |
| `legalPrev` (RegNext of legal) | `1` | **Solver-chosen arbitrary initial value** |
| `sizeFrom` | `10100` (20) | No disc on invalid peg 3 |
| `sizeFromPrev` (RegNext of sizeFrom) | `11111` (31) | **Out-of-bounds index!** |
| `toPrev` (RegNext of io.to) | `11` (3) | **Invalid peg value** |
| `io.from` | `11` (3) | Invalid input peg |
| `io.to` | `11` (3) | Invalid input peg |
| `disc(0)` through `disc(19)` | all `00` (0) | All discs on peg A (correct init) |
| `hasBeenReset` | `1` | System is past reset |
| `hasBeenResetReg` | `1` | Past-reset register |
| `reset` | `0` | Not in reset |

#### At time 5 ns (clock falling edge):
Same values as time 0, plus `disc_moved_to_destination` = 1.

#### At time 10 ns:
Same as time 0, all values unchanged (no rising clock edge occurred).

### No Signal Transitions
All observed signals have exactly **one value change** (at time 0). No clock edges trigger state updates in the waveform trace — the counterexample is captured in the **single initial time step** before any sequential update.

## 4. Root Cause Analysis

### Category: **Incorrect Assertion (`assertion_error`)**

The assertion `disc_moved_to_destination` is **logically correct** but **lacks a guard for the initial state**, causing a spurious failure before any real clock edge has occurred.

### Explanation

The assertion uses three `RegNext` registers to capture the move state on each cycle:
```scala
val legalPrev = RegNext(legal)
val sizeFromPrev = RegNext(sizeFrom)
val toPrev = RegNext(io.to)
```

**Critical Issue**: In Chisel, `RegNext` generates a Verilog register with an `initial` value but **no explicit reset input**. The equivalent Verilog is:
```verilog
reg [4:0] sizeFromPrev = 5'b10100;  // initial value from Chisel
always @(posedge clk) begin
  sizeFromPrev <= sizeFrom;
end
```

While simulation tools honor the `initial` block, **formal verification tools** (like JasperGold used here) treat registers without reset as having **unconstrained initial values**. The formal solver is free to pick any initial value for the register, which directly causes the violation.

### How the Failure Occurs

1. **`legalPrev`** is unconstrained → the solver sets it to `1` (true), even though no previous legal move occurred.

2. **`sizeFromPrev`** is unconstrained → the solver sets it to `11111` (31), which is out of bounds for the 20-element disc array.

3. **`toPrev`** is unconstrained → the solver sets it to `11` (3), which is an invalid peg value.

4. The assertion condition evaluates as:
   ```
   !legalPrev || (disc(sizeFromPrev) === toPrev)
   = !1 || (disc(31) === 3)
   = 0 || (disc(31) === 3)
   ```
   
   Since `disc` has only 20 elements (indices 0–19), index 31 wraps modulo 20 to index 11. `disc(11)` is 0 (peg A), and `0 === 3` is false. Hence the assertion fails.

### Why This is Not a Design Bug

- **All disc positions** are correctly initialized to peg A (`disc(0..19)` = 0).
- **`legal`** correctly evaluates to `false` (0) because `sizeFrom = 20` and `sizeTo = 20` — no disc to move.
- The design logic itself is sound. **The failure is purely an artifact of unconstrained `RegNext` initial values** in the formal verification model.

### Fix Strategy

The assertion needs a guard condition to exclude the initial cycle before the `RegNext` registers have seen a valid clock edge. Two approaches:

**Option A — Add a "past valid" guard:**
```scala
val prevValid = RegInit(false.B)
prevValid := Mux(reset.asBool, false.B, true.B)
fvAssert(!prevValid || !legalPrev || (disc(sizeFromPrev) === toPrev),
         "disc_moved_to_destination")
```

**Option B — Initialize the registers with explicit reset:**
```scala
val legalPrev = RegInit(false.B)
val sizeFromPrev = RegInit(0.U(5.W))
val toPrev = RegInit(0.U(2.W))
legalPrev := legal
sizeFromPrev := sizeFrom
toPrev := io.to
```

Option A is more general and localizes the change to the assertion guard, while Option B addresses the initialization more broadly. Either fix would prevent the spurious failure.

### Buggy Code Location
- **File**: `newHanoi.scala`
- **Lines**: 69–73
- **Root cause**: `RegNext` registers lack reset in the formal model, causing unconstrained initial values that violate the assertion guardlessly.
