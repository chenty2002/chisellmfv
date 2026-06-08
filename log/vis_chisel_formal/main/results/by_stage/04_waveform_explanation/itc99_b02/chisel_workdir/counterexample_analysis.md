# Counterexample Analysis Report: itc99_b02

## 1. Verification Environment

- **Top Module**: `b02`
- **DUT Source**: `b02.scala` (103 lines)
- **Design Under Test**: A finite state machine (FSM) with 7 states (StateA–StateG) implementing a sequential control circuit from the ITC99 benchmark set. The FSM has one input (`io.LINEA`) and one output (`io.U` / `U_reg`).
- **Key Components**:
  - `stato [2:0]`: 3-bit state register
  - `REG [2:0]`: Implicit `RegNext(stato)` register (a delay register)
  - `U_reg`: Output register
  - `io_LINEA`: Boolean input controlling certain state transitions
  - `resetCounter`: Reset tracking submodule
- **Formal Framework**: Chisel with `chiselFv` formal verification library

## 2. Violated Assertion

- **Assertion Name**: `state_must_change_every_cycle_28no_deadlock29` (decoded: `state_must_change_every_cycle_(no_deadlock)`)
- **Waveform File**: `b02.state_must_change_every_cycle_28no_deadlock29.fst`
- **Source Location**: `b02.scala`, line 93

**Code Snippet**:

```scala
// Liveness: The FSM must always make forward progress — the state should change
// every cycle. All states have defined unconditional or combinational next-state
// logic, so no state should persist for more than one cycle.
fvAssert(stato =/= RegNext(stato), "state must change every cycle (no deadlock)")
```

**Property Description**: This assertion checks that at every positive clock edge, the current state (`stato`) differs from the state one cycle earlier (`RegNext(stato)`). This ensures the FSM never stays in the same state for two consecutive cycles.

## 3. Waveform Information

- **Full Path**: `verilog/extra_bench/itc99_b02/b02.state_must_change_every_cycle_28no_deadlock29.fst`
- **Duration**: 1 cycle (0 ns → 10 ns)
- **Key Time Points**:
  - **Time 0 ns** (first positive clock edge after reset): Assertion fires (assertion output signal = 1)
  - **Time 5 ns** (falling edge): All signals remain unchanged

**Critical Signal Values at Time 0 ns**:

| Signal | Value | Meaning |
|--------|-------|---------|
| `b02.stato [2:0]` | `000` | StateA |
| `b02.REG [2:0]` | `000` | StateA (previous state = `RegNext(stato)`) |
| `b02.clock` | `1` | Positive edge of clock |
| `b02.reset` | `0` | Reset is deasserted |
| `b02.io_LINEA` | `0` | Input low |
| `b02.U_reg` | `0` | Output low |
| `b02.hasBeenReset` | `1` | Reset has completed |
| `b02.resetCounter.flag` | `1` | Reset counter active |
| `b02.resetCounter.notChaos` | `1` | Normal operation |
| `b02.resetCounter.timeSinceReset [31:0]` | `0` | Zero cycles since reset |
| `b02.resetCounter.count [31:0]` | `0` | Zero count |
| `b02.pending` | `0` | No pending |
| `b02.nextPending` | `1` | Next will have pending |

## 4. Root Cause Analysis

### Category: **Incorrect Assertion** (Assertion Error)

### Root Cause

The assertion `fvAssert(stato =/= RegNext(stato), ...)` fails **not** because of a bug in the FSM design, but because it does not account for the first cycle after reset, when both `stato` and `RegNext(stato)` are initialized to the same value.

### Detailed Explanation

**State Encoding** (from `b02.scala`):
```scala
val StateA = 0.U(3.W)
val StateB = 1.U(3.W)
...
val stato = RegInit(StateA)  // Initialized to 0 (StateA)
```

**The FSM transition from StateA**:
```scala
is(StateA) {
    stato := StateB   // Next state becomes StateB
    U_reg := false.B
}
```

**Verilog/Chisel semantics of `RegNext(stato)`**:
- `RegNext(stato)` creates a flip-flop that captures `stato`'s value on each clock edge and outputs the value from the previous cycle.
- On reset, both `stato` and `RegNext(stato)` are initialized to 0 (their `RegInit`/`RegNext` defaults).

**Sequence of events**:

1. After reset deasserts, at the **first positive clock edge** (time 0 ns):
   - `stato` = StateA (000) — its initialized value
   - `RegNext(stato)` = 000 — its initialized value (also 0, which equals `StateA`)
   - **`stato =/= RegNext(stato)` evaluates to `0 =/= 0` → `false`** → assertion fails

2. The FSM's next-state logic is correct: from StateA, `stato` would transition to StateB (001) on the *next* clock edge. However, the assertion fires before this transition takes effect, on the very first clock edge where both registers still hold their reset values.

**Why this is not a design bug**:
- The state machine has valid transitions defined for all 7 states
- From StateA (000), the next state is always StateB (001), regardless of inputs
- The design would correctly change state every cycle starting from the second cycle

**Evidence from the waveform**:
- At time 0: `stato = 000` and `REG = 000` (both equal, causing failure)
- The assertion output (`state_must_change_every_cycle_28no_deadlock29`) is 1 (asserting/failing) at time 0
- `resetCounter.timeSinceReset = 0` confirms this is the very first cycle after reset
- There is no subsequent cycle data in the waveform because the assertion fails immediately at time 0

### Suggested Fix

The assertion should exclude the first cycle after reset. A corrected version would be:

```scala
// Skip the check on the first cycle after reset to avoid
// false failure when both stato and RegNext(stato) have the same
// initial value (StateA = 0).
fvAssert(!reset.asBool && RegNext(reset.asBool) |=> (stato =/= RegNext(stato)),
  "state must change every cycle after the first (no deadlock)")
```

Or, if using Chisel's formal primitives differently:

```scala
// Alternative: include the reset guard
fvAssert(past(reset.asBool) |=> (stato =/= RegNext(stato)),
  "state must change every cycle (no deadlock)")
```

The key insight is that on the **first** cycle after reset, both `stato` and `RegNext(stato)` hold the same reset value, so `stato =/= RegNext(stato)` will always be false at that point regardless of the FSM's actual correctness.
