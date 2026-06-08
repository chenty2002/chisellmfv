# Counterexample Analysis Report: `disc_0_stable_when_illegal`

## 1. Verification Environment

### Top Module
- **Module**: `Hanoi` (class `Hanoi extends Module with Formal`)
- **Package**: `llmverify`
- **Source File**: `newHanoi.scala` (77 lines)

### Key Components and Connections
- **`disc`**: A `Vec` of 20 registers (`RegInit`), each storing a 2-bit peg value (A=00, B=01, C=10). All initialized to peg A.
- **`io_from`**: 2-bit input indicating the source peg for a Tower of Hanoi move.
- **`io_to`**: 2-bit input indicating the destination peg.
- **`sizeFrom`**: 5-bit wire — the index of the smallest disc on the `from` peg (computed via reversed priority encoder). Set to 20 if `from` peg has no discs.
- **`sizeTo`**: 5-bit wire — the index of the smallest disc on the `to` peg (computed similarly). Set to 20 if `to` peg is empty.
- **`validPegs`**: `io_from <= 2.U && io_to <= 2.U` — verifies inputs are valid peg values.
- **`legal`**: Combinational signal — `validPegs && (sizeFrom < 20.U) && (sizeFrom < sizeTo)`. True when the move is a valid Tower of Hanoi move.
- **Reset Counter**: Standard Chisel FV `resetCounter` for initialization.

### Design Under Test Behavior
The design implements a Tower of Hanoi solver with 20 discs and 3 pegs (A=0, B=1, C=2). At each clock cycle, it reads `io_from` and `io_to` inputs. If the move is legal (both pegs are valid, there is a disc on the source peg, and it is smaller than the smallest disc on the destination peg), it updates the smallest disc on the source peg to the destination peg.

## 2. Violated Assertion

### Assertion Name
`disc_0_stable_when_illegal` (from waveform filename: `Hanoi.disc_0_stable_when_illegal.fst`)

### Code Snippet

From `newHanoi.scala`, lines 56-57:
```scala
// Safety 2: When move is illegal, no disc register changes value
for (i <- 0 until 20) {
    assertStableWhen(!legal, disc(i), s"disc_${i}_stable_when_illegal")
}
```

### Natural Language Description
**Property**: When the current move is illegal (`!legal` is true), every disc register `disc(i)` must retain its value from the previous clock cycle (i.e., it must be stable and not change).

### File Location
- **File**: `chisel/extra_bench/newhanoi/newHanoi.scala`
- **Lines**: 56-57

## 3. Waveform Information

### Full Path to Waveform File
`verilog/extra_bench/newhanoi/Hanoi.disc_0_stable_when_illegal.fst`

### Time Range and Key Time Points
- **Waveform Duration**: 0 ns to 210 ns (21 cycles, each cycle = 10 ns)
- **Assertion Failure Point**: **200 ns** (cycle 20)
- **Preceding Legal Move Point**: **190 ns** (cycle 19)

### Critical Signal Values at Failure Point (200 ns)

| Signal | Value at 200 ns | Value at 190 ns (prev cycle) |
|--------|-----------------|------------------------------|
| `Hanoi.disc_0 [1:0]` | `01` (B) | `00` (A) — **changed!** |
| `Hanoi._legal_T` / `Hanoi.legal` | `0` (false) | `1` (true) |
| `Hanoi.io_from [1:0]` | `11` (3, invalid) | `00` (A) |
| `Hanoi.io_to [1:0]` | `00` (A) | `01` (B) |
| `Hanoi.sizeFrom [4:0]` | `10100` (20) | `00000` (0) |
| `Hanoi.disc_0_stable_when_illegal` | `0` (FAIL) | `1` (pass) |

### Full Disc State at 190 ns (cycle 19)
- disc_0=00(A), disc_1=10(C), disc_2=01(B), disc_3=10(C), disc_4=10(C), disc_5=01(B)
- disc_6=10(C), disc_7=10(C), disc_8=10(C), disc_9=10(C), disc_10=01(B), disc_11=10(C)
- disc_12=01(B), disc_13=01(B), disc_14=10(C), disc_15=10(C), disc_16=10(C), disc_17=10(C)
- disc_18=10(C), disc_19=10(C)

## 4. Root Cause Analysis

### Bug Type: **Incorrect Assertion (assertion_error)**

The assertion `assertStableWhen(!legal, disc(i), ...)` has incorrect temporal semantics that do not align with the synchronous register update behavior of the design.

### The Buggy Assertion

**File**: `newHanoi.scala`, **Lines**: 56-57

```scala
for (i <- 0 until 20) {
    assertStableWhen(!legal, disc(i), s"disc_${i}_stable_when_illegal")
}
```

### Root Cause Explanation

The assertion `assertStableWhen(!legal, disc(i))` checks at each clock cycle: if `!legal` is true in the **current** cycle (post-edge), then `disc(i)` must have the same value as it did in the **previous** cycle (i.e., it must not have changed).

The problem arises because `legal` is a **combinational** signal computed from the current cycle's inputs, while `disc(i)` is a **register** whose update takes effect at the clock edge based on the **previous** cycle's `legal` evaluation.

### Detailed Failure Sequence

**Cycle 19** (time 190 ns → 200 ns):
1. **Inputs**: `io_from = 00` (peg A), `io_to = 01` (peg B)
2. **Legal Check**: `legal = 1` because:
   - `validPegs = (0<=2 && 1<=2) = true`
   - `sizeFrom = 0` (disc_0 is on peg A)
   - `sizeTo = 2` (disc_2 is the smallest disc on peg B)
   - `0 < 20 && 0 < 2` = true
3. **Sequential Update**: The `when(legal)` block triggers: `disc(0) := io.to = 01` (B)
4. This sets the **D input** of `disc(0)` register to `01` for the upcoming clock edge.

**Cycle 20** (time 200 ns — clock edge):
1. **Inputs change to**: `io_from = 11` (3, invalid peg), `io_to = 00` (A)
2. **Register Update**: `disc(0)` captures its D input (`01`) from the previous cycle — **it changes from 00 (A) to 01 (B)**
3. **Legal Check (new inputs)**: `legal = 0` because `validPegs = (3<=2 && 0<=2) = false`
4. **Assertion Check** (`assertStableWhen(!legal, disc(0))`):
   - `!legal` = true (current cycle)
   - `disc(0)` at cycle 20 = `01`
   - `disc(0)` at cycle 19 = `00`
   - **01 ≠ 00 → ASSERTION FAILS**

### Why This Is an Assertion Error

The assertion assumes that when `!legal` is true in the current cycle, `disc(i)` should not have changed from the previous cycle. However, this ignores the fundamental behavior of synchronous sequential logic:

1. **Register updates have one-cycle latency**: The `when(legal)` block updates `disc(sizeFrom)` at the clock edge based on the combinational values present at that edge.
2. **`legal` is combinational**: It reflects the **current** inputs immediately, while the register change reflects the **previous** cycle's computation.
3. **The update was legitimate**: `disc(0)` changed from A to B because a **legal move** was made in cycle 19. The assertion incorrectly flags this as a violation because the inputs changed to invalid values in cycle 20 at the same clock edge.

The design correctly prevents updates during illegal moves (the `when(legal)` guard works correctly). The assertion is simply too strict — it doesn't account for the fact that a disc register can legitimately change at the clock edge due to a legal move from the previous cycle, even if the current cycle's inputs are invalid.

### Evidence from Waveform

The waveform evidence clearly shows:
- **At 190 ns** (cycle 19): `legal=1`, `io_from=00` (A), `io_to=01` (B), `disc_0=00` (A)
- **At 200 ns** (cycle 20): `legal=0`, `io_from=11` (invalid), `io_to=00` (A), `disc_0=01` (B)
- The assertion signal `Hanoi.disc_0_stable_when_illegal` transitions from `1` to `0` exactly at 200 ns

### Suggested Fix

The assertion should use a **registered** version of `legal` to account for the one-cycle latency of the register update:

```scala
val legalReg = RegNext(legal, false.B)
for (i <- 0 until 20) {
    assertStableWhen(!legalReg, disc(i), s"disc_${i}_stable_when_illegal")
}
```

With this fix, the assertion checks: "if the move was illegal in the **previous** cycle, then the disc should be stable in the current cycle." This correctly aligns the temporal semantics because:
- A legal move in cycle N updates `disc(i)` at the clock edge to cycle N+1
- `legalReg` captures the `legal` value from cycle N and presents it in cycle N+1
- In cycle N+1: if `!legalReg` (meaning cycle N's move was illegal), then `disc(i)` should not have changed — which is correct, because the `when(legal)` guard would have prevented the update in cycle N
