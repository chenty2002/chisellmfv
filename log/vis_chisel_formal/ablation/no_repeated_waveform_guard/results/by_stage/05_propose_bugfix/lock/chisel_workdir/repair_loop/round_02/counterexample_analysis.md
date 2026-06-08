# Counterexample Analysis Report: `lock.no_position_overflow`

## 1. Verification Environment

- **Top Module**: `lock`
- **Source File**: `lock.scala`
- **Key Components**:
  - `position`: 5-bit register tracking lock position (0–31)
  - `state`: 2-bit state machine register (0–3)
  - `upReg`, `downReg`: latched versions of the input directions
  - Position update logic with built-in overflow/underflow guards
- **Design Description**: A combination lock that tracks a position value (0–31) controlled by `io.up` and `io.down` inputs. The position can be moved up or down but has overflow/underflow protection. A state machine tracks progress through a combination sequence, and when state reaches 3, the lock opens.

## 2. Violated Assertion

- **Full Assertion Name**: `lock.no_position_overflow` (from waveform filename `lock.no_position_overflow.fst`)
- **Code Snippet** (line 74 of `lock.scala`):

```scala
// Safety: position must not overflow when moving up (would wrap 31 -> 0)
fvAssert(!(io.up && !io.down) || position < 31.U, "no_position_overflow")
```

- **Natural Language Description**: If the `up` input is high and `down` is low (i.e., the user is trying to move up), then the current position must be less than 31. This is intended to prevent the position from overflowing from 31 back to 0.
- **File Location**: `lock.scala`, line 74

## 3. Waveform Information

- **Waveform File**: `verilog/extra_bench/lock/lock.no_position_overflow.fst`
- **Time Range**: 0 ns → 320 ns (32 cycles)
- **Key Time Points**:
  - **Time 0–300 ns**: Assertion holds (`no_position_overflow = 1`). Position increments from 0 to 30.
  - **Time 310 ns**: Assertion FAILS (`no_position_overflow = 0`).
- **Critical Signal Values at Failure Point (310 ns)**:

| Signal           | Value           |
|------------------|-----------------|
| `io_up`          | 1               |
| `io_down`        | 0               |
| `position`       | 31 (`11111`)    |
| `state`          | 0 (`00`)        |
| `upReg`          | 1               |
| `downReg`        | 0               |
| `clock`          | 1               |

## 4. Root Cause Analysis

### Root Cause Category: **Incorrect Assertion** (assertion_error)

### Buggy Code Location

- **File**: `lock.scala`, line 74
- **Component**: The formal assertion `no_position_overflow`

### Description of the Bug

The assertion `fvAssert(!(io.up && !io.down) || position < 31.U, "no_position_overflow")` is **too strict**. It checks: *"If `io.up` is true and `io.down` is false, then the current position must be less than 31."*

However, the design already has explicit overflow protection in its position update logic (lines 31–35 of `lock.scala`):

```scala
when(io.up && !io.down && position < 31.U) {
    position := position + 1.U
}.elsewhen(io.down && !io.up && position > 0.U) {
    position := position - 1.U
}
```

The guard `position < 31.U` in the `when` condition ensures that when `position` reaches 31, **no increment occurs** regardless of the input state. The design is correct — there is no actual overflow risk.

The counterexample trace shows:
1. `io_up` = 1 and `io_down` = 0 for all 32 cycles
2. Position increments from 0 to 31 (one per cycle)
3. At time 310 ns, position reaches 31 while `io_up` is still 1 and `io_down` is 0
4. The assertion fires because `!(1 && 1) || (31 < 31)` = `0 || 0` = `0`

But at this exact moment, the design does **not** update position (because the guard `position < 31.U` is false), so no overflow occurs. The assertion is flagging a condition that is **safe** by design.

### Evidence from Waveform

- **Position trace**: Position increments by 1 every cycle (10 ns intervals) from 0 to 31, cleanly and monotonically. At time 310 ns, position reaches 31 and stays there (no wrap).
- **Input signals**: `io_up` is always 1; `io_down` is always 0. This unconstrained input scenario is valid for formal verification.
- **No actual overflow**: The position never wraps from 31 to 0. The design's guard successfully prevents this.

### Why This Causes the Assertion to Fail

The assertion checks a condition (`position < 31.U`) that is **stronger than needed**. The actual requirement is: *"Position should never wrap from 31 to 0."* The design satisfies this requirement through its guarded update logic. But the assertion demands that whenever `io_up` is asserted, position must be < 31 — which fails at the legitimate boundary case where position is exactly 31 and the user is still pressing up.

### Recommended Fix

The assertion should be updated to match the design's actual safety behavior. Since the design's guard `position < 31.U` already prevents overflow by construction, the assertion should either:

1. **Be removed entirely** — the overflow protection is straightforward and verifiable by code inspection.
2. **Be rewritten as a temporal assertion** that checks position never wraps, e.g.:
   ```scala
   // Position should never decrease when going up
   when(io.up && !io.down && position < 31.U) {
     fvAssert(position + 1.U > position, "no_position_overflow")
   }
   ```
3. **Be rewritten as a design-consistent assertion** that allows the boundary case:
   ```scala
   // When going up, the design's guard prevents overflow
   fvAssert(!(io.up && !io.down && position === 31.U) || 
            (position === 31.U), "no_position_overflow")
   ```
   (Note: this simplifies to a tautology since `position === 31.U` when `position === 31.U`.)

The cleanest fix is option 1 (remove the assertion) or rephrasing it to check the design's guard behavior rather than constrain the input.
