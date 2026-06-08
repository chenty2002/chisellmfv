# Counterexample Analysis: lock.no_position_overflow

## 1. Verification Environment

- **Top Module**: `lock` (package `llmverify`)
- **Generated Verilog**: `chisel/extra_bench/lock/generated/`
- **Design**: A lock mechanism with a 5-bit position counter (0-31), up/down button inputs, a 4-state finite state machine, and output `io.open` when state reaches 3.
- **Key Components**:
  - `position`: 5-bit register tracking current position, with overflow protection (`position < 31.U`) and underflow protection (`position > 0.U`)
  - `prevPosition`: `RegNext(position)` — the previous cycle's position value
  - `upReg`, `downReg`: latched versions of up/down signals (mutually exclusive by construction)
  - `state`: 2-bit FSM state register

## 2. Violated Assertion

- **Assertion Name**: `no_position_overflow` (extracted from waveform filename `lock.no_position_overflow.fst`)
- **File Location**: `lock.scala`, line 77
- **Code Snippet**:
  ```scala
  // Safety: position must not overflow when moving up (would wrap 31 -> 0)
  // Use temporal check: when up is pressed, position should not decrease
  // (wrapping 31->0 would cause position to decrease)
  fvAssert(!(io.up && !io.down) || position >= prevPosition, "no_position_overflow")
  ```
- **Property Description**: The assertion checks that when the up button is pressed (and down is not pressed), the current position must be greater than or equal to the previous cycle's position. This is intended to detect overflow (wrapping from 31 to 0).

## 3. Waveform Information

- **Waveform File**: `verilog/extra_bench/lock/lock.no_position_overflow.fst`
- **Time Range**: 0 ns → 30 ns (3 cycles)
- **Key Time Points and Signal Values**:

### Time 0 (Initial/Reset)
| Signal | Value |
|--------|-------|
| io_up | 1 |
| io_down | 0 |
| position [4:0] | 00000 (0) |
| prevPosition [4:0] | 00000 (0) |
| upReg | 0 |
| downReg | 0 |
| state [1:0] | 00 |
| no_position_overflow | 1 (passing) |

### Time 10 (First Clock Edge)
| Signal | Value |
|--------|-------|
| io_up | 0 |
| io_down | 1 |
| position [4:0] | 00001 (1) |
| prevPosition [4:0] | 00000 (0) |
| upReg | 1 |
| downReg | 0 |
| state [1:0] | 00 |
| no_position_overflow | 1 (passing) |

### Time 20 (Second Clock Edge — **Failure Point**)
| Signal | Value |
|--------|-------|
| io_up | 1 |
| io_down | 0 |
| position [4:0] | 00000 (0) |
| prevPosition [4:0] | 00001 (1) |
| upReg | 0 |
| downReg | 1 |
| state [1:0] | 00 |
| **no_position_overflow** | **0 (FAILING)** |

## 4. Root Cause Analysis

### Error Type: **Incorrect Assertion** (`assertion_error`)

### Root Cause

The assertion `!(io.up && !io.down) || position >= prevPosition` is **too strict** and produces a **false positive**. It incorrectly flags a legitimate sequence of events as an overflow violation.

### Detailed Explanation of the Counterexample

The counterexample shows a realistic stimulus sequence:

1. **Cycle 0 (time 0)**: `io_up=1`, `io_down=0`. The up button is pressed. Since `position=0` (and `0 < 31`), the position register transitions from 0 to 1 by the next clock edge.

2. **Cycle 1 (time 10)**: `io_up=0`, `io_down=1`. The down button is pressed. Since `position=1` (and `1 > 0`), the position register legitimately decrements from 1 to 0 by the next clock edge. Meanwhile, `prevPosition` latches the old value of 1.

3. **Cycle 2 (time 20)**: `io_up=1`, `io_down=0`. The up button is pressed again. Now `position=0` and `prevPosition=1`. The assertion evaluates:
   - `!(io.up && !io.down)` = `!(1 && 1)` = `0`
   - `position >= prevPosition` = `0 >= 1` = `0`
   - Result: `0 || 0` = **0** → **ASSERTION FAILS**

The position decreased from 1 to 0 at cycle 1, but this was a **legitimate decrease caused by pressing the down button**, not an overflow (wrapping from 31 to 0). When the assertion checks at cycle 2 (when up is pressed), it incorrectly blames the previous cycle's legitimate down-press for the decrease.

### Why the Design Does NOT Have a Bug

The position update logic already has built-in overflow protection:
```scala
when(io.up && !io.down && position < 31.U) {  // <-- overflow guard
    position := position + 1.U
}.elsewhen(io.down && !io.up && position > 0.U) {  // <-- underflow guard
    position := position - 1.U
}
```

Since `position < 31.U` guards the increment, position can **never** reach 31 when up is pressed, making overflow impossible. The assertion's intended purpose (catching overflow) is already satisfied by the design logic.

### Recommended Fix

The assertion should account for legitimate position decreases caused by the down button in the previous cycle. A correct reformulation:

**Option A** (allow decreases when down was pressed last cycle):
```scala
fvAssert(!(io.up && !io.down) || position >= prevPosition || downReg, "no_position_overflow")
```

This adds `|| downReg` — meaning the assertion passes even if `position < prevPosition`, as long as the down button was pressed last cycle (which justifies the decrease).

**Option B** (remove the assertion entirely): Since the design already prevents overflow via `position < 31.U`, the assertion is redundant and can be safely removed.

**Option A** is recommended to preserve the intent of monitoring for overflow bugs.

### Symmetric Underflow Issue

Note that the sibling assertion `no_position_underflow` on line 82:
```scala
fvAssert(!(io.down && !io.up) || position <= prevPosition, "no_position_underflow")
```

has the identical class of bug — it would fail in a symmetric scenario where up was pressed before down (the counterexample for this assertion would show `io_down=1` while `position > prevPosition` due to a prior up press).
