# Counterexample Analysis Report: `puzzle_eventually_solved`

## 1. Verification Environment

- **Top Module**: `Jam` (in `jam.scala`)
- **Design Under Test**: A 7-slot traffic-jam puzzle game. Initial configuration: `[RIGHT, RIGHT, RIGHT, EMPTY, LEFT, LEFT, LEFT]`. Goal configuration: `[LEFT, LEFT, LEFT, EMPTY, RIGHT, RIGHT, RIGHT]`.
- **Key Components**:
  - `slots[0..6]`: Registers holding the current board state (2-bit each: 0=EMPTY, 1=LEFT, 2=RIGHT)
  - `empty`: Wire tracking the position of the empty slot
  - `valid`: Wire computed from `io.move` and board state, checking move legality
  - `done`: Wire indicating the puzzle is solved
  - `io.move` (UInt(3.W)): Unconstrained external input specifying which piece to move

## 2. Violated Assertion

- **Assertion Name**: `puzzle_eventually_solved`
- **Assertion Location**: `jam.scala`, line 167
- **Assertion Code**:
  ```scala
  astRelaxedLiveness(valid && !done, done, 200, "puzzle_eventually_solved")
  ```
- **Description**: A relaxed liveness property asserting that whenever there is a valid move available (`valid`) and the puzzle is not yet solved (`!done`), then the puzzle must reach the solved state (`done`) within 200 clock cycles.

## 3. Waveform Information

- **Waveform File**: `verilog/extra_bench/jam/Jam.puzzle_eventually_solved.fst`
- **Time Range**: 0 ns → 2020 ns (202 cycles)
- **Key Time Points**:
  - **t=0 ns (cycle 0)**: Initial state `[RIGHT,RIGHT,RIGHT,EMPTY,LEFT,LEFT,LEFT]`, `valid=1`, `done=0`, `pending=0`, `timer=0`
  - **t=10 ns (cycle 1)**: `pending` becomes 1 (trigger condition `valid && !done` satisfied)
  - **t=20 ns (cycle 2)**: `timer` begins incrementing (starts at 1)
  - **t=100 ns (cycle 10)**: Board enters a 2-cycle oscillation between state A `[RIGHT,LEFT,EMPTY,RIGHT,RIGHT,LEFT,LEFT]` and state B `[RIGHT,EMPTY,LEFT,RIGHT,RIGHT,LEFT,LEFT]`
  - **t=1980 ns (cycle 198)**: Board changes to `[EMPTY,RIGHT,LEFT,RIGHT,RIGHT,LEFT,LEFT]`, `valid=0`, `io_move=7`
  - **t=2010 ns (cycle 201)**: `timer=200` (0xC8), assertion fails (`puzzle_eventually_solved` goes to 0)
- **Critical Signal Values at Failure (t=2010 ns)**:
  - `slots`: `[0, 2, 1, 2, 2, 1, 1]` = `[EMPTY,RIGHT,LEFT,RIGHT,RIGHT,LEFT,LEFT]`
  - `empty`: 0
  - `io_move`: 7
  - `valid`: 0
  - `done`: 0
  - `timer`: 200 (0xC8)
  - `pending`: 1

## 4. Root Cause Analysis

### Error Type: **Setup Error (Assertion too strong for unconstrained environment)**

### Description

The root cause is **not a bug in the DUT**, but rather a **mismatch between the assertion's semantics and the verification environment**. Specifically:

1. **The assertion** `astRelaxedLiveness(valid && !done, done, 200, ...)` asserts: "For ALL possible input sequences, whenever `valid && !done` holds, the puzzle shall reach the solved state within 200 cycles."

2. **The problem**: The environment provides `io.move` as a **completely unconstrained 3-bit input**. The formal tool can choose ANY value for `io.move` at each cycle, including values that satisfy the `valid` condition but keep the puzzle cycling in a loop, never reaching the solved state.

3. **The counterexample demonstrates this**:
   - Between times 100-1980 ns, the formal tool drives `io.move` to alternate between 1 and 2, causing the board to oscillate between two states:
     - State A: `[RIGHT, LEFT, EMPTY, RIGHT, RIGHT, LEFT, LEFT]` (empty=2)
     - State B: `[RIGHT, EMPTY, LEFT, RIGHT, RIGHT, LEFT, LEFT]` (empty=1)
   - Each move is "valid" according to the game rules (slide-left/slide-right of a LEFT piece into the adjacent empty slot).
   - This 2-cycle oscillation continues for ~188 cycles while the `timer` counts up to 200.
   - The `done` signal never becomes true because the puzzle never progresses towards the goal configuration `[LEFT,LEFT,LEFT,EMPTY,RIGHT,RIGHT,RIGHT]`.

4. **Why this is not a DUT bug**: The DUT correctly implements all move rules. Every move applied in the counterexample is a legal move (slide-left or slide-right of a LEFT piece into the adjacent empty slot). The DUT faithfully updates the board state according to the rules each time a valid move is received.

5. **Why this is not an assertion error**: The assertion correctly expresses a liveness property. However, the property "from any state where a valid move exists, every possible sequence of valid moves leads to the solution within 200 steps" is **false** for this puzzle (and for most puzzles), because adversarial move choices can cause cycles. The intended property was likely "there exists a sequence of valid moves that solves the puzzle within 200 steps," which requires a different formal verification approach (e.g., bounded reachability).

### Fix Recommendation

To make this liveness assertion meaningful, one of the following approaches is needed:

**Option A: Constrain the environment** — Add assumptions that `io.move` values follow a strategy that makes progress toward the solution, or constrain the environment to be non-adversarial. For example, adding a fairness assumption that the same piece is not moved back and forth repeatedly.

**Option B: Change the property** — Instead of using `astRelaxedLiveness` (which asserts ALL paths lead to the target), use a reachability/bounded-model-checking approach to prove that a solution EXISTS within 200 moves from any reachable state. This requires a non-deterministic choice mechanism rather than an unconstrained input.

**Option C: Remove the assertion** — The puzzle's correctness can be sufficiently verified by the other seven structural and execution-correctness invariants (exactly one empty, exactly three LEFT, exactly three RIGHT, empty tracking correctness, move fills empty slot, move empties source). The liveness property is inherently difficult to verify without constraining the environment.
