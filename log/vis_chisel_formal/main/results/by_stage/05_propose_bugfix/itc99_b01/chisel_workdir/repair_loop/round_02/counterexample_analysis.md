# Counterexample Analysis Report: `overflow_when_in_state_e`

## 1. Verification Environment

- **Top Module**: `b01` (from `b01.scala`)
- **Module Type**: Chisel Module with `Formal` mixin
- **Design Under Test**: ITC'99 b01 benchmark — an FSM-based sequence detector with overflow detection
- **Key Components**:
  - `stato` (3-bit state register) — FSM state
  - `prevStato` (3-bit register) — previous state snapshot, initialized to `e` to avoid false failures at cycle 0
  - `overflwReg` (1-bit register) — overflow flag
  - `outpReg` (1-bit register) — output register
  - `io.LINE1`, `io.LINE2` — inputs
  - `io.OUTP`, `io.OVERFLW` — outputs
- **FSM States**: a(0), b(1), c(2), e(3), f(4), g(5), wf0(6), wf1(7)
- **Connection**: `io.OVERFLW := overflwReg`, `io.OUTP := outpReg`

## 2. Violated Assertion

- **Assertion Name**: `overflow_when_in_state_e`
- **Code Snippet** (from `b01.scala`, lines 115-116):
  ```scala
  // Safety 2: When in state 'e', the overflow flag must be asserted
  fvAssert(stato =/= b01State.e || overflwReg, "overflow_when_in_state_e")
  ```
- **Natural Language Property**: "When the FSM is currently in state `e`, the overflow register (`overflwReg`) must be asserted (true)."
- **File Location**: `b01.scala`, lines 115-116

## 3. Waveform Information

- **Waveform File**: `verilog/extra_bench/itc99_b01/b01.overflow_when_in_state_e.fst`
- **Duration**: 5 cycles (0 ns to 50 ns)
- **Assertion Failure Point**: Time = **40 ns** (assertion signal `b01.overflow_when_in_state_e` transitions from 1 to 0)

### Critical Signal Values at Failure Point (t = 40 ns)

| Signal | Value | Meaning |
|--------|-------|---------|
| `b01.stato [2:0]` | `011` (3) | Current state is **e** |
| `b01.overflwReg` | `0` | Overflow flag is **false** |
| `b01.prevStato [2:0]` | `110` (6) | Previous state was **wf0** |
| `b01.io_LINE1` | `1` | Input LINE1 is high |
| `b01.io_LINE2` | `1` | Input LINE2 is high |
| `b01.reset` | `0` | Not in reset |

### Sequence of Events Leading to Failure

| Time (ns) | stato | prevStato | overflwReg | LINE1 | LINE2 | Comment |
|-----------|-------|-----------|------------|-------|-------|---------|
| 0 | a (000) | e (011) | 0 | 1 | 0 | Initial state, reset |
| 10 | b (001) | a (000) | 0 | 1 | 1 | Transition a→b |
| 20 | g (101) | b (001) | 0 | 0 | 0 | Transition b→g |
| 30 | wf0 (110) | g (101) | 0 | 1 | 1 | Transition g→wf0 |
| **40** | **e (011)** | **wf0 (110)** | **0** | **1** | **1** | **ASSERTION FAILS: stato=e but overflwReg=0** |

## 4. Root Cause Analysis

### Bug Type: **Incorrect Assertion** (`assertion_error`)

The assertion `overflow_when_in_state_e` is **timing-incorrect** and cannot be satisfied under any valid input sequence.

### Root Cause

The assertion checks the **current** state (`stato`) against `overflwReg`, but `overflwReg` is a **registered** signal that updates one cycle **after** the state transition due to the standard register timing semantics.

**Detailed Explanation:**

1. **State Transition Path**: The FSM transitions to state `e` **only** from `wf0` (when `LINE1 & LINE2`) or `wf1` (when `LINE1 | LINE2`).

2. **In state wf0** (cycles before entering e):
   ```scala
   is(b01State.wf0) {
     when(io.LINE1 & io.LINE2) { stato := b01State.e }
     // ...
     overflwReg := false.B  // overflwReg is SET TO FALSE
   }
   ```
   When in `wf0`, `overflwReg` is unconditionally set to `false.B`.

3. **At the clock edge (t=40)**: `stato` becomes `e`, `overflwReg` becomes `0` (the value driven by wf0's logic during the previous cycle).

4. **In state e** (cycle starting at t=40):
   ```scala
   is(b01State.e) {
     when(io.LINE1 & io.LINE2) { stato := b01State.f }
     // ...
     overflwReg := true.B  // SET TO TRUE — but only for the NEXT cycle!
   }
   ```
   The state `e` case sets `overflwReg := true.B`, but this only takes effect at the **next** clock edge (t=50).

5. **State e always transitions out**: In state `e`, the FSM **always** transitions to either `f` (if `LINE1 & LINE2`) or `b` (otherwise). It never stays in `e` for a second cycle.

6. **Consequence**: At t=50, when `overflwReg` finally becomes `1` (true), `stato` is no longer `e` — it has already transitioned to `f` or `b`. The assertion `stato =/= b01State.e || overflwReg` can **never** be satisfied when `stato === e` because `overflwReg` is still `0` on the first (and only) cycle in state `e`.

### Evidence from the Source Code

The design itself acknowledges this timing behavior in **Safety 1** (line 110-113):
```scala
// Safety 1: Overflow flag is only asserted in the overflow state (state 'e')
// Use prevStato because overflwReg is registered and updates one cycle after
// the state transition — when overflwReg=1 the state has already moved to f,
// but prevStato still holds the previous value e.
fvAssert(!overflwReg || prevStato === b01State.e, "overflow_only_in_state_e")
```

The comment explicitly states: **"Use prevStato because overflwReg is registered and updates one cycle after the state transition."** Safety 1 correctly uses `prevStato`, but Safety 2 (`overflow_when_in_state_e`) mistakenly uses `stato` instead of `prevStato`, violating the same timing principle that Safety 1 correctly accounts for.

### Suggested Fix

The assertion should use `prevStato` instead of `stato` to match the register timing:

```scala
// Corrected: When the PREVIOUS state was 'e', the overflow flag must be asserted
fvAssert(prevStato =/= b01State.e || overflwReg, "overflow_when_in_state_e")
```

Alternatively, if the intent is to check forward-looking behavior:
```scala
// Alternative: When in state 'e', the next cycle's overflow flag should be asserted
fvAssert(stato =/= b01State.e || RegNext(overflwReg), "overflow_when_in_state_e")
```

The first fix (`prevStato`) is recommended as it is consistent with Safety 1 and the documented timing behavior of the design.
