# Counterexample Analysis: Hanoi.done_stays_done

## 1. Verification Environment

- **Top Module**: `Hanoi` (from `newHanoi.scala`)
- **Key Components**:
  - `disc[0..19]` — Array of 20 2-bit registers tracking which peg each disc is on (A=0, B=1, C=2)
  - `sizeFrom` — Index of the smallest disc (highest-indexed) on the `from` peg, computed via priority encoder
  - `sizeTo` — Index of the smallest disc on the `to` peg, or 20 if peg is empty
  - `legal` — Condition that a move is legal: `sizeFrom < 20` (from peg has a disc) and `sizeFrom < sizeTo` (the disc being moved is smaller than the top disc on the destination peg)
  - `io.done` — True when all 20 discs are on peg B (value 1)
- **Design Description**: Implements the Tower of Hanoi puzzle with 20 discs on 3 pegs (A=0, B=1, C=2). All discs start on peg A. A move moves the top disc from `io.from` to `io.to` if the move is legal according to the standard Hanoi rules (can only place a disc on an empty peg or on a larger disc).

## 2. Violated Assertion

- **Assertion Name**: `done_stays_done`
- **Full Name** (from waveform filename): `Hanoi.done_stays_done`
- **Code Snippet** (from `newHanoi.scala`, line 62):
  ```scala
  AssertProperty(io.done |-> Sequence(io.done).delay(1), None, None, Some("done_stays_done"))
  ```
  Compiled in Verilog (line ~315 of `Hanoi.sv`) as:
  ```verilog
  done_stays_done: assert property (io_done_0 |-> ##1 io_done_0);
  ```
- **Property**: Once all 20 discs are on peg B (`io.done` is true), then `io.done` must remain true at the next clock cycle (and by induction, forever).
- **File Location**: `newHanoi.scala`, line 62

## 3. Waveform Information

- **Waveform File**: `verilog/extra_bench/newhanoi/Hanoi.done_stays_done.fst`
- **Time Range**: 0 ns → 220 ns (22 cycles, 10 ns per cycle)
- **Key Time Points**:
  - **Cycle 0–18**: Discs are progressively moved from peg A (0) to peg B (1), one per cycle. `sizeFrom` counts down from 19 to 1.
  - **Cycle 19 (time 190 ns)**: `io_from=0` (A), `io_to=1` (B), `sizeFrom=0`, `_GEN_0=1`. `disc_0` moves from A to B.
  - **Cycle 20 (time 200 ns)**: **All 20 discs on peg B. `io_done=1`.** Inputs are now `io_from=1` (B), `io_to=0` (A), `sizeFrom=19`, `legal=1`, `_GEN_0=1`. The move logic activates: `disc_19` is updated from B to A.
  - **Cycle 21 (time 210 ns)**: `disc_19=0` (peg A), all others on peg B. `io_done=0`. **Assertion `done_stays_done` fires** (transitions from 1 to 0 at time 210).

### Critical Signal Values at Failure Time (210 ns)

| Signal | Value |
|--------|-------|
| `io_done` / `io_done_0` | **0** (was 1 at time 200) |
| `disc_0` – `disc_18` | `01` (peg B, all unchanged) |
| `disc_19` | `00` (peg A — **changed from B at this cycle**) |
| `legal` | 1 |
| `_GEN_0` | 1 |
| `io_from` | `01` (B) |
| `io_to` | `00` (A) |

## 4. Root Cause Analysis

### Bug Type: **Bug in the Original Design (DUT Bug)**

### Buggy Code Location

**File**: `newHanoi.scala`, **lines 44–46** (the `when` block that performs disc updates):

```scala
when(legal && io.to <= 2.U) {
  disc(sizeFrom) := io.to
}
```

### Description of the Bug

The design has **no guard against moves after the puzzle is solved**. Once all 20 discs are on peg B (`io.done` becomes true), the move logic should be disabled to preserve the solved state. Currently, the only guards are `legal` (a valid Hanoi move) and `io.to <= 2.U` (valid peg), but neither prevents a move that disturbs the solved configuration.

### Mechanism of Failure

1. **Cycle 19 (190 ns)**: The last disc (`disc_0`) is moved from peg A to peg B. All 20 discs are now on peg B.
2. **Cycle 20 (200 ns)**: At the same posedge clock, the inputs are `io_from=1` (B), `io_to=0` (A). The priority encoder computes `sizeFrom=19` (disc 19 is the highest-indexed disc on peg B). Since peg A is empty (`sizeTo=20 > sizeFrom=19`), the move is considered **legal**. The `when` clause fires and **`disc_19` is moved from peg B back to peg A**.
3. **Cycle 21 (210 ns)**: `disc_19` is now on peg A, so `io_done` becomes 0. The assertion `io_done |-> ##1 io_done` is violated because `io_done` was true at cycle 20 but false at cycle 21.

### Evidence from Waveform

- At time 200 ns: all 20 discs on peg B (all `disc_*` signals = `01`), `io_done_0 = 1`. Simultaneously, `_GEN_0 = 1`, `sizeFrom = 19 (5'h13)`, so the update `disc_19 <= io_to` (where `io_to = 0` = peg A) is registered.
- At time 210 ns: `disc_19 = 00` (peg A), `io_done_0 = 0`. The solved state is lost.

### Root Cause Summary

The `when` condition on line 44 needs an additional guard to prevent moves when the puzzle is already solved:

```scala
when(legal && io.to <= 2.U && !io.done) {
  disc(sizeFrom) := io.to
}
```

Adding `!io.done` ensures that once all discs are on peg B, no further input can disturb the solved state, making the `done_stays_done` property hold.
