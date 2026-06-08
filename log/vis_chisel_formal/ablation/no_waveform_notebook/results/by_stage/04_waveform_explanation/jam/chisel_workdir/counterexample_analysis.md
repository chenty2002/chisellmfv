# Counterexample Analysis Report: `move_fills_empty_slot`

## 1. Verification Environment

- **Top Module**: `Jam` (from `jam.scala`, line 14)
- **Module Type**: `Module with Formal`
- **Key Components**:
  - `slots` (RegInit, Vec(7, UInt(2.W))) — 7 game slots initialized to `[RIGHT, RIGHT, RIGHT, EMPTY, LEFT, LEFT, LEFT]`
  - `empty` (Wire, UInt(3.W)) — combinational decoder locating the empty slot via `Mux1H`
  - `valid` (Wire, Bool) — combinational validity of the current move
  - `mp1/mp2/mm1/mm2` — wires computing move±1 and move±2 positions (3-bit wrap)
  - `old_slots/old_valid/old_move/old_empty` — `RegNext` snapshots for post-move assertions
- **Design**: A 7-slot traffic-jam puzzle where RIGHT-facing cars move right and LEFT-facing cars move left, sliding or jumping over same-facing pieces into the empty slot.

## 2. Violated Assertion

- **Assertion Name**: `move_fills_empty_slot`
- **Source File**: `jam.scala`, lines 137–138
- **Code**:
  ```scala
  fvAssert(!old_valid || (slots(old_empty) === old_slots(old_move)),
    "move_fills_empty_slot")
  ```
- **Property Description**: On the cycle after a valid move, the slot at the *previous* empty position should contain the piece that was at the *previous* move position.
- **Location**: `jam.scala`, lines 137–138

## 3. Waveform Information

- **Waveform File**: `verilog/extra_bench/jam/Jam.move_fills_empty_slot.fst`
- **Time Range**: 0 ns → 10 ns (1 cycle, no signal transitions observed)

### Key Signal Values at Time 0

| Signal | Value | Encoding |
|--------|-------|----------|
| `Jam.io_move [2:0]` | `111` | 7 |
| `Jam.valid` | `0` | false |
| `Jam.old_valid` | `1` | **true** |
| `Jam.old_move [2:0]` | `110` | 6 |
| `Jam.old_empty [2:0]` | `011` | 3 |
| `Jam.slots_3 [1:0]` | `00` | EMPTY |
| `Jam.old_slots_6 [1:0]` | `01` | LEFT |
| `Jam.slots_0 [1:0]` | `10` | RIGHT |
| `Jam.slots_1 [1:0]` | `10` | RIGHT |
| `Jam.slots_2 [1:0]` | `10` | RIGHT |
| `Jam.slots_4 [1:0]` | `01` | LEFT |
| `Jam.slots_5 [1:0]` | `01` | LEFT |
| `Jam.slots_6 [1:0]` | `01` | LEFT |
| `Jam.:jasper_formal_reset` | `0` | never asserted |

### Assertion Failure Condition

```
!old_valid || (slots(old_empty) === old_slots(old_move))
= !1 || (slots(3) === old_slots(6))
= 0 || (EMPTY === LEFT)
= false
```

## 4. Root Cause Analysis

### Bug Category: **Setup Issue** — verification registers lack proper reset initialization

#### Root Cause

The assertion uses `RegNext`-based registers to snapshot state before a move:

```scala
val old_valid = RegNext(valid)    // line 133
val old_move = RegNext(io.move)   // line 134
val old_empty = RegNext(empty)    // line 135
val old_slots = RegNext(slots)    // line 83
```

These registers are created **without explicit initialization values**. In the generated Verilog, `RegNext` with default init (`0` for UInt, `false` for Bool) relies on the reset signal being asserted to apply those initial values. **However, the formal reset signal (`Jam.:jasper_formal_reset`) is never asserted (stays `0` throughout the waveform)**, meaning these registers are treated as having *arbitrary* initial values by the formal solver.

#### How the counterexample is constructed

1. **At the first clock cycle** (before time 0), the formal solver freely chooses:
   - `old_valid = 1` (arbitrary initial value — should be `false.B` at reset)
   - `old_move = 6` (arbitrary initial value — should be `0.U`)
   - `old_empty = 3` (arbitrary initial value — should be `0.U`)

2. **`old_slots`** captures the reset-time value of `slots` at the first clock edge, giving `[RIGHT, RIGHT, RIGHT, EMPTY, LEFT, LEFT, LEFT]`.

3. **At time 0**, the assertion evaluates:
   - `old_valid = 1` (spurious)
   - `slots(old_empty) = slots(3) = EMPTY` (correct — no valid move was ever executed)
   - `old_slots(old_move) = old_slots(6) = LEFT` (correct initial value)
   - `EMPTY ≠ LEFT` → assertion **fails**

#### Why this is a setup issue, not a DUT bug

- **The valid move logic is correct**: For `io_move=6` with the initial configuration, `valid` correctly evaluates to `0` (none of the four move conditions are satisfied). There is no design bug causing spurious valid moves.
- **The slot update logic is correct**: The `when(valid)` block correctly swaps the piece at `io.move` with the empty slot when `valid=1`.
- **The assertion property is correct**: The check `slots(old_empty) === old_slots(old_move)` is a valid invariant after a real move.
- **The root cause is uninitialized verification registers**: Without a proper reset, the formal solver can assign arbitrary initial values to `old_valid`, `old_move`, and `old_empty`, creating an impossible scenario where `old_valid=1` (claimed valid move) but no actual update occurred (`slots(3)` is still empty).

#### Evidence from the waveform

- `Jam.:jasper_formal_reset` = 0 at all times — registers never see reset
- `Jam.valid` = 0 at time 0 — no valid move in the current cycle
- `Jam.slots` are unchanged from the initial reset state — no move was ever executed
- `Jam.old_valid` = 1 — directly contradicts the circuit behavior (valid was never 1)
- All `_GEN` and `_GEN_7` signals match the current `slots` — confirming no update would occur even at the next clock edge

#### Suggested Fix

Add explicit initialization values to the `RegNext` registers so the formal solver cannot assign arbitrary values:

```scala
val old_slots = RegNext(slots, VecInit(Seq.fill(7)(CellState.EMPTY)))
val old_valid = RegNext(valid, false.B)
val old_move = RegNext(io.move, 0.U(3.W))
val old_empty = RegNext(empty, 0.U(3.W))
```

Alternatively, ensure the formal reset is properly asserted during the initial cycle to apply the default `RegNext` initial values.
