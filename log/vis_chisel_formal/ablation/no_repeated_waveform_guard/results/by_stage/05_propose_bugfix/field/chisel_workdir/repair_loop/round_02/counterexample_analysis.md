# Counterexample Analysis Report: `center_empty` Assertion Failure

## 1. Verification Environment

### Top Module
- **Top module**: `field5` (class in `field5.scala`)
- **Verilog file**: `verilog/extra_bench/field/field5.center_empty.fst`

### Key Components
| Component | Description |
|-----------|-------------|
| `board` | `RegInit(Vec(25, UInt(1.W)))` — 25-bit register representing the 5×5 peg solitaire board |
| `validMove` | Combinational wire indicating whether the current `io.from`/`io.dir` move is valid |
| `io.from` | Input (5-bit) — source position for a move |
| `io.dir` | Input (2-bit) — direction of the move (U=0, D=1, L=2, R=3) |
| `io.cnt` | Output (5-bit) — combinational sum of pegs on the board |
| `io.board` | Output (Vec(25, UInt(1.W))) — current board state |

### Design Description
This is a 5×5 **peg solitaire** puzzle (English variant). The board has 25 holes arranged in a 5×5 grid. Initially, pegs fill all positions except the **center hole (position 12)** and the **lower-right corner (position 24)**. A move consists of one peg jumping over an adjacent peg into an empty hole beyond it, removing the jumped-over peg. The goal is to reduce the board to a single peg.

---

## 2. Violated Assertion

- **Assertion name**: `center_empty` (from waveform filename `field5.center_empty.fst`)
- **Location**: `field5.scala`, line 125

### Code Snippet
```scala
// field5.scala, lines 124-125
// Safety 2: Center hole (position 12) is always empty
fvAssert(board(12) === 0.U, "center_empty")
```

### Property Description
The assertion checks that the **center position (index 12)** of the peg solitaire board is **always empty** (value 0). This property is expected to hold for all reachable states of the design.

---

## 3. Waveform Information

- **Waveform file**: `verilog/extra_bench/field/field5.center_empty.fst`
- **Duration**: 2 cycles (0–20 ns)
- **Key time points**: 0 ns (cycle 0) and 10 ns (cycle 1)

### Critical Signal Values

#### Time 0 ns — Initial State (assertion passes: `center_empty=1`)

| Signal | Value | Meaning |
|--------|-------|---------|
| `field5.io_from [4:0]` | `01010` (10) | Move source = position 10 (row 2, col 0) |
| `field5.io_dir [1:0]` | `11` (R=3) | Move direction = RIGHT |
| `field5.io_board_10` | `1` | Position 10 has a peg |
| `field5.io_board_11` | `1` | Position 11 has a peg |
| `field5.io_board_12` | `0` | Position 12 (center) is empty |
| `field5.io_board_24` | `0` | Position 24 (corner) is empty |
| `field5.center_empty` | `1` | Assertion holds initially |
| `field5.prevValidMove` | `1` | A valid move occurred in previous cycle |
| `field5.reset` | `0` | Not in reset |
| All other `board_0..board_9, board_13..board_23` | `1` | All non-center, non-corner positions have pegs |

#### Time 10 ns — After Clock Edge (assertion fails: `center_empty=0`)

| Signal | Value | Change from t=0 |
|--------|-------|-----------------|
| `field5.io_board_10` | `0` | Peg removed (jumping peg vacates source) |
| `field5.io_board_11` | `0` | Peg removed (jumped-over peg is removed) |
| `field5.io_board_12` | `1` | **Peg added** (landing target now occupied) |
| `field5.io_board_24` | `0` | No change |
| `field5.center_empty` | `0` | **Assertion violated** |
| `field5.io_cnt [4:0]` | `00000` | Count decreased |

### Board Layout Visualization

```
Time 0 (initial):                     Time 10 (after move):
 0  1  2  3  4                         0  1  2  3  4
[1][1][1][1][1]  row 0                [1][1][1][1][1]  row 0
[1][1][1][1][1]  row 1                [1][1][1][1][1]  row 1
[1][1][0][1][1]  row 2  ← center 12   [1][0][0][1][1]  row 2  ← position 12 now 1!
[1][1][1][1][1]  row 3                [1][1][1][1][1]  row 3
[1][1][1][1][0]  row 4  ← corner 24   [1][1][1][1][0]  row 4
```

---

## 4. Root Cause Analysis

### Error Classification: **Incorrect Assertion** (`assertion_error`)

The assertion `board(12) === 0.U` ("center_empty") is **not a valid invariant** of the peg solitaire game. It describes an **initial condition**, not a property that must hold throughout all reachable states.

### Why the assertion is wrong

The peg solitaire game allows a peg to **jump into the center hole** as part of normal gameplay. Position 12 starts empty to enable the first move, but during the game, pegs may legally occupy any position, including the center.

### Evidence from Waveform

The counterexample shows a perfectly legal RIGHT move from position 10:

```
Position 10 (peg) → jumps right over → Position 11 (peg) → lands in → Position 12 (empty center)
                      ↓                   ↓                        ↓
                  1 → 0                 1 → 0                    0 → 1
```

This move satisfies all validity conditions in the game logic (lines 78-85 of `field5.scala`):
1. **Boundary check**: `resMod5(io.from) < 3.U` — position 10 is at column 0 (10 % 5 = 0), which is < 3 ✓
2. **Source has peg**: `board(io.from) === 1.U` — position 10 has a peg ✓
3. **Adjacent has peg**: `board(io.from + 1.U) === 1.U` — position 11 has a peg ✓
4. **Target is empty**: `board(io.from + 2.U) === 0.U` — position 12 (center) is empty ✓

The move is **valid and correctly executed** by the game logic. The assertion fails because it incorrectly assumes the center must remain empty forever.

### Bug vs. Assertion Analysis

| Possibility | Assessment | Reason |
|-------------|-----------|--------|
| **Bug in DUT** | ❌ No | The move logic correctly implements peg solitaire mechanics |
| **Incorrect Assertion** | ✅ Yes | `board(12) === 0.U` is an initial condition, not an invariant |
| **Setup Error** | ❌ No | The test harness correctly provides a valid move input |

### Recommended Fix

Replace the assertion with a more appropriate property. Options include:

1. **Remove the assertion entirely** — the center emptiness is not a safety property of the game.

2. **Replace with an initial-state assertion** (e.g., only check the center is empty before any move):
   ```scala
   // Check center was initially empty (will hold at time 0)
   ```

3. **Replace with a valid invariant** such as:
   - Parity: the parity of the board is preserved during gameplay
   - Symmetry: certain reachable-state properties

The simplest correct approach is to **remove** the `center_empty` assertion (line 125) from the design.
