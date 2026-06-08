# Counterexample Analysis Report: `lock.entry_state1_requires_pos12_up`

## 1. Verification Environment

- **Top module**: `lock` (from `lock.scala`)
- **Key components**:
  - `position` (5-bit register): Tracks current position, increments/decrements by 1 per cycle
  - `state` (2-bit register): State machine with states 0-3
  - `upReg`, `downReg` (1-bit registers): Latched versions of button inputs
  - `prevState` (2-bit register): Previous state value (used by assertions)
  - `prevPosition` (5-bit register): Previous position value (used by assertions)
- **I/O ports**:
  - `io.up` (input): Button up signal
  - `io.down` (input): Button down signal
  - `io.open` (output): Lock open indicator (state 3)
  - `io.position` (output): Current position value
- **Test stimulus**: `io_up=1` and `io_down=0` throughout the entire simulation, causing position to increment by 1 every clock cycle

## 2. Violated Assertion

- **Assertion name**: `entry_state1_requires_pos12_up`
- **Waveform file**: `lock.entry_state1_requires_pos12_up.fst`
- **Location**: `lock.scala`, lines 93-96

```scala
fvAssert(
    !(prevState === 0.U && state === 1.U) || (position === 12.U && upReg),
    "entry_state1_requires_pos12_up"
)
```

- **Natural language**: When the state machine transitions from state 0 to state 1, at that moment the position must be exactly 12 and upReg must be true. In other words, the only valid way to enter state 1 from state 0 is when position=12 and upReg is asserted.

## 3. Waveform Information

- **Waveform file**: `verilog/extra_bench/lock/lock.entry_state1_requires_pos12_up.fst`
- **Time range**: 0 ns to 140 ns (14 clock cycles, period = 10 ns)
- **Failure point**: Time = 130 ns (assertion signal transitions from 1 to 0)

### Critical signal values at key time points:

| Signal | t=110 | t=120 | t=130 |
|--------|-------|-------|-------|
| `state` | 0 (00) | 0 (00) | **1 (01)** |
| `prevState` | 0 (00) | 0 (00) | 0 (00) |
| `position` | 11 (01011) | 12 (01100) | **13 (01101)** |
| `prevPosition` | 10 (01010) | 11 (01011) | 12 (01100) |
| `upReg` | 1 | 1 | 1 |
| `downReg` | 0 | 0 | 0 |
| `io_up` | 1 | 1 | 1 |
| `io_down` | 0 | 0 | 0 |

### Assertion trace:
- `entry_state1_requires_pos12_up` = 1 (passing) from time 0 to 130
- `entry_state1_requires_pos12_up` = 0 (failing) from time 130 onward

## 4. Root Cause Analysis

### Bug Location

**File**: `lock.scala`, lines 44-46 (position update logic) in conjunction with lines 51-53 (state transition for state 0)

### Bug Type

**Design bug (DUT bug)**: The design has a timing/sequencing issue between the position update and the state machine transition.

### Detailed Explanation

#### How the Bug Manifests

The lock design uses three registers that all update synchronously at every clock edge:

1. **Position update** (lines 44-46):
   ```scala
   when(io.up && !io.down) {
     position := position + 1.U
   }
   ```
   Since `io_up=1` and `io_down=0` throughout the simulation, position increments by 1 every cycle.

2. **State machine transition** (lines 51-53):
   ```scala
   is(0.U) {
     when(position === 12.U && upReg) {
       state := 1.U
     }
   }
   ```
   The condition `position === 12.U && upReg` is evaluated using the **old** value of position (before the increment).

3. **upReg update** (line 49):
   ```scala
   upReg := io.up && !io.down
   ```
   Set to 1 every cycle since `io_up=1`.

#### The Key Timing Problem

At clock edge t=120 ns:
- **Old position** (from t=110) = **11** → Condition `position === 12.U` is **FALSE** → No state transition
- **New position** (after increment) = **12**

At clock edge t=130 ns:
- **Old position** (from t=120) = **12** → Condition `position === 12.U` is **TRUE** → State transitions to 1
- **New position** (after increment) = **13**
- **New state** = **1**

The assertion checks values **after** the clock edge:
- `prevState === 0.U && state === 1.U` → TRUE (transition did occur)
- `position === 12.U` → **FALSE** (position is now 13, not 12!)
- `upReg` → TRUE

**Result**: Assertion fails because position=13, not 12.

#### Root Cause Visualization

```
Cycle:    t=110      t=120      t=130
          |          |          |
position: 11  →→→   12  →→→   13
                   (inc)     (inc)
state:    0         0  →→→    1
                   (cond     (state
                    eval:     transition
                    12==12)   takes effect)
                              
Assert    ✓         ✓         ✗ FAIL
check:                        (pos=13≠12)
```

The fundamental problem is that **position and state update simultaneously**. The condition `position === 12.U` fires one cycle after position reaches 12, but by the time the state register updates to 1, position has already been incremented to 13.

The `prevPosition` register confirms this: at t=130, `prevPosition=12` (it captured position=12 from the t=120 cycle), proving that the transition was indeed triggered by position=12, but the current position has moved on.

#### Why the Assertion is Correct

The assertion captures the correct security property: the only legitimate way to enter state 1 from state 0 is when position=12 and upReg=1. The design's state machine **intends** to enforce this, but due to the one-cycle latency between condition evaluation and state update, combined with the continuous position increment, the timing no longer matches.

### Proposed Fix

The design should prevent position from advancing past the target value when a state transition is triggered. For example, conditionally freeze the position update:

```scala
when(io.up && !io.down && !(state === 0.U && position === 12.U && upReg)) {
  position := position + 1.U
}.elsewhen(io.down && !io.up) {
  position := position - 1.U
}
```

This would ensure that when the transition condition is met (position=12, upReg=1, state=0), position stops incrementing, so it remains at 12 when state transitions to 1 in the next cycle, satisfying the assertion.
