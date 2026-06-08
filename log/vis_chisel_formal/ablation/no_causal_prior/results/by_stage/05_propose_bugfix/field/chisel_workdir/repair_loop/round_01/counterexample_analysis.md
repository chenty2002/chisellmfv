# Counterexample Analysis Report: `field5.initial_state_23_pegs`

## 1. Verification Environment

**Top module**: `field5` (from `field5.scala`)
**File location**: `chisel/extra_bench/field/field5.scala`

**Design description**:  
A 5×5 peg solitaire puzzle. The board has 25 positions (indices 0–24). In the initial state, pegs should occupy 23 holes, with position 12 (center) and position 24 (lower-right corner) empty. Each move jumps a peg over an adjacent peg into an empty hole, removing the jumped-over peg.

**Key signals**:
- `board`: `RegInit(Vec(25, UInt(1.W)))` — 25-bit register, 1 = peg present, 0 = empty
- `io.cnt`: `Output(UInt(5.W))` — count of pegs on board
- `io.from`: `Input(UInt(5.W))` — source position of the jumping peg
- `io.dir`: `Input(UInt(2.W))` — direction (U/D/L/R)
- `nextBoard`: `Wire(Vec(25, UInt(1.W)))` — next-state value for board
- `validMove`: `Wire(Bool())` — asserted when a valid move is executed

## 2. Violated Assertion

**Assertion name**: `initial_state_23_pegs`

**Code** (line 130 of `field5.scala`):
```scala
assertAt(0.U, io.cnt === 23.U, "initial_state_23_pegs")
```

**Property**: At cycle 0 after deassertion of reset (i.e., at time 0), the peg count `io.cnt` must equal 23, reflecting the initial configuration where positions 12 (center) and 24 (lower-right corner) are empty.

## 3. Waveform Information

**Waveform file**: `verilog/extra_bench/field/field5.initial_state_23_pegs.fst`

**Time range**: 0 ns → 10 ns (1 clock cycle)

**Key observations at time 0 ns**:

| Signal | Value | Expected |
|--------|-------|----------|
| `io.cnt [4:0]` | `00001` (1) | `10111` (23) |
| `board_0` through `board_11` | all `1` | all `1` |
| `board_12` | `1` (should be `0` — center empty) | `0` |
| `board_13` through `board_23` | all `1` | all `1` |
| `board_24` | `1` (should be `0` — lower-right corner empty) | `0` |
| `reset` | `0` | `0` |
| `_boardSum_T` | `0` | `2` (`board_0 + board_1 = 1 + 1`) |

## 4. Root Cause Analysis

### Bug Location

**File**: `chisel/extra_bench/field/field5.scala`

**Bug type**: **Bug in the Original Design** (Category 1)

There are **two compounding bugs** that together cause the assertion failure.

---

### Bug 1: Board initialization overridden by bulk assignment

**Code** (lines 31–35, 106):
```scala
val board = RegInit(VecInit(Seq.fill(25)(1.U(1.W))))  // line 31

// Initialize board: center (12) and lower right corner (24) are empty
board(12) := 0.U   // line 34
board(24) := 0.U   // line 35

// ... (nextBoard logic, lines 53–103) ...

board := nextBoard  // line 106 — *** OVERRIDES lines 34-35 ***
```

**Mechanism**:  
In Chisel, when multiple unconditional assignments drive the same register element, the **last assignment in declaration order** wins.

- Lines 34–35 assign `board(12) := 0.U` and `board(24) := 0.U`, intending to clear positions 12 and 24 every cycle.
- Line 106 later assigns `board := nextBoard`, a **bulk assignment** to the entire Vec.
- Because `board := nextBoard` is declared after `board(12) := 0.U` and `board(24) := 0.U`, it overrides them. All 25 elements of `board` take their values from `nextBoard`.

**Consequence**:  
`nextBoard` is initialized as `nextBoard := board` (line 54), which copies the current board state. Since `nextBoard` never clears positions 12 and 24, and since `board := nextBoard` overrides the individual clears, the register `board` retains all 25 pegs after reset — positions 12 and 24 remain `1`.

**Waveform evidence**: At time 0:
- `board_12 = 1` (should be 0)
- `board_24 = 1` (should be 0)
- All 25 board positions are `1` (25 pegs instead of 23)

---

### Bug 2: `io.cnt` peg-count computation uses 1-bit addition tree

**Code** (line 109):
```scala
io.cnt := board.map(x => x).reduce(_ + _)
```

**Generated Verilog** (from `field5.sv`):
```verilog
wire        _boardSum_T = board_0 + board_1;
wire [4:0]  io_cnt_0 =
    {4'h0,
     _boardSum_T + board_2 + board_3 + ... + board_24};
```

**Mechanism**:  
The Chisel compilation (firtool) generates a 1-bit intermediate `_boardSum_T` for the sum `board_0 + board_1`. Because `_boardSum_T` is declared as an implicit 1-bit wire (`wire _boardSum_T` without a range), the computation `1 + 1 = 2` (binary `10`) is **truncated to 1 bit**, yielding `0`.

All subsequent additions in the tree (`_boardSum_T + board_2 + board_3 + ... + board_24`) are then computed at **1-bit width** due to Verilog's expression-width rules. The result is the **parity** (odd/even) of the peg count, not the actual count.

**Waveform evidence**: At time 0:
- `_boardSum_T = 0` (should be 2, but `1+1` truncated to 1 bit)
- `io.cnt = 00001` (decimal 1 = 25 mod 2, parity of 25 pegs)

---

### Why the Assertion Fails

The assertion `assertAt(0.U, io.cnt === 23.U, "initial_state_23_pegs")` fails because:

1. **Bug 1** ensures the board has 25 pegs instead of 23 after reset (positions 12 and 24 are incorrectly occupied).
2. **Bug 2** ensures `io.cnt` shows `1` (the parity of 25) at time 0 instead of the correct count.

Even if only Bug 1 were present, `io.cnt` would show the parity of 25 = 1 (not 23). Even if only Bug 2 were present, the board would still have all 25 pegs. Both bugs must be fixed for the assertion to pass.

### Suggested Fix

**For Bug 1**: Move the zero-initialization of positions 12 and 24 from the `board` register to the `nextBoard` wire, so they are properly reflected in the next state:

```scala
val nextBoard = Wire(Vec(25, UInt(1.W)))
nextBoard := board
nextBoard(12) := 0.U   // moved here
nextBoard(24) := 0.U   // moved here
```

Alternatively, use a custom `RegInit` initialization vector:
```scala
val initVals = Seq.tabulate(25)(i => if (i == 12 || i == 24) 0.U(1.W) else 1.U(1.W))
val board = RegInit(VecInit(initVals))
```

**For Bug 2**: Ensure the peg-count computation uses proper bit-width. A workaround is to explicitly cast or widen:
```scala
io.cnt := board.map(x => x.asUInt).reduce(_ +& _)  // use +& for explicit width growth
```
Or use a manual for-loop with proper width accumulation.
