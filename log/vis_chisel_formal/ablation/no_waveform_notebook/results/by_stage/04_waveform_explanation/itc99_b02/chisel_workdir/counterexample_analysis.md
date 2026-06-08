# Counterexample Analysis Report: `itc99_b02`

## 1. Verification Environment

- **Top Module**: `b02` (extends `Module with Formal`)
- **Source File**: `chisel/extra_bench/itc99_b02/b02.scala`
- **Design Under Test**: A 7-state finite state machine (states A–G) from the ITC99 benchmark set. The FSM uses a 3-bit state register `stato` initialized to StateA (0) on reset, with an output register `U_reg`. Transitions depend on the input `io.LINEA`.
- **State Encoding**:
  - StateA = 0, StateB = 1, StateC = 2, StateD = 3, StateE = 4, StateF = 5, StateG = 6
- **FSM Transitions**:
  - StateA → StateB (unconditionally)
  - StateB → StateC (if LINEA=0) or StateF (if LINEA=1)
  - StateC → StateD (if LINEA=0) or StateG (if LINEA=1)
  - StateD → StateE
  - StateE → StateB (U_reg=1)
  - StateF → StateG
  - StateG → StateE (if LINEA=0) or StateA (if LINEA=1)

## 2. Violated Assertion

- **Assertion Name**: `reset_transition_to_B`
- **Code Snippet** (line 92–94):
  ```scala
  // Safety: after reset deasserts, the next cycle must enter StateB.
  // The FSM is initialized to StateA, which unconditionally transitions to StateB.
  assertNextStepWhen(!resetBool, stato === StateB, "reset_transition_to_B")
  ```
- **Natural Language Description**: When the reset signal is deasserted (`!resetBool` is true), then in the next clock cycle, the state register `stato` must equal StateB (1).
- **File Location**: `chisel/extra_bench/itc99_b02/b02.scala`, lines 92–94

## 3. Waveform Information

- **Waveform File**: `verilog/extra_bench/itc99_b02/b02.reset_transition_to_B.fst`
- **Time Range**: 0 ns → 10 ns (1 clock cycle)
- **Key Time Points**:

| Time | Signal | Value |
|------|--------|-------|
| 0 ns | `b02.clock` | 1 |
| 0 ns | `b02.reset` | 0 (deasserted) |
| 0 ns | `b02.stato [2:0]` | 000 (StateA) |
| 0 ns | `b02.io_LINEA` | 0 |
| 0 ns | `b02.U_reg` | 0 |
| 0 ns | `b02.hasBeenReset` | 1 |
| 0 ns | `b02.reset_transition_to_B` | **1 (FAILING)** |
| 5 ns | `b02.clock` | 0 |
| 9 ns | `b02.stato [2:0]` | 000 (StateA, unchanged) |
| 9 ns | `b02.reset_transition_to_B` | 1 (FAILING, unchanged) |

**Critical Observation**: Throughout the entire waveform (0–10 ns), `stato` remains at StateA (000). The clock transitions from 1→0 at 5 ns but `stato` never updates to StateB.

## 4. Root Cause Analysis

### Root Cause Category: **Assertion Error** (`assertion_error`)

### Bug Location
**File**: `chisel/extra_bench/itc99_b02/b02.scala`, lines 92–94
**Module**: `b02`

### Description of the Bug

The assertion `assertNextStepWhen(!resetBool, stato === StateB, "reset_transition_to_B")` is **incorrectly written**. The semantics of `assertNextStepWhen(condition, property)` in the Chisel Formal library evaluate `property` at the **current cycle** and then delay the boolean result by one cycle (using `Next`/`RegNext`). This is NOT the same as evaluating `property` at the next cycle.

### Detailed Explanation

The assertion intends to check: "When reset is deasserted, the state register `stato` must equal StateB in the **next** cycle."

The FSM is designed correctly:
1. On reset, `stato` is initialized to StateA (0) by `RegInit(StateA)`.
2. The combinational switch logic correctly computes: in StateA, `stato := StateB` (next state = 1).
3. On the next clock edge, `stato` would correctly transition to StateB (1).

**However**, `assertNextStepWhen` does NOT evaluate the property at the next cycle. Instead, it evaluates `property` at the **current** cycle (producing a boolean), and delays this boolean result by one cycle via `RegNext`. So:

- At time 0: `stato = StateA` (the register is still in its initial/reset state)
- `stato === StateB` evaluates to `false` (because `StateA ≠ StateB`)
- `Next(stato === StateB)` captures this `false` value via `RegNext` and delays it
- The assertion `Implies(!resetBool, Next(stato === StateB))` = `Implies(true, false)` = **false** → **ASSERTION FAILS**

The property that SHOULD be checked is either:
1. The **next value** of `stato` equals StateB: `Next(stato) === StateB` — this would evaluate whether the register's next stored value is StateB
2. At the cycle **after** reset deasserts, the current state is StateB: using `past(!resetBool)` as the condition and `stato === StateB` as the property

### Evidence from Waveform

1. **`stato` = StateA (000) at all times**: The register holds StateA because it was initialized on reset and the next clock edge (where it would transition to StateB) has not occurred yet in the trace.
2. **`reset` = 0 at all times**: Reset is deasserted from the start, making `!resetBool` = true.
3. **`reset_transition_to_B` = 1 at all times**: The assertion is already failing at time 0 because `Next(stato === StateB)` captures the current-cycle comparison result (false) and delays it — this delayed false makes the implication fail.

### Why the Design is NOT the Problem

The FSM logic in `b02.scala` is actually correct:
- `switch(stato) { is(StateA) { stato := StateB } }` correctly computes the next state as StateB
- The `RegInit(StateA)` correctly initializes to StateA on reset
- There is no bug in the state machine transitions

### Conclusion

This is an **incorrect assertion** (`assertion_error`). The assertion should be rewritten to properly use the `Next` temporal operator by comparing the **next value of stato** with StateB, rather than comparing the current value of stato with StateB and delaying the boolean result.

**Suggested Fix** (line 93):
```scala
// Correct: Check that the NEXT value of stato equals StateB
assertNextStepWhen(!resetBool, Next(stato) === StateB, "reset_transition_to_B")
```

Or alternatively, restructure to use `past`:
```scala
// Alternative: When !resetBool was true last cycle, stato should be StateB now
fvAssert(Implies(past(!resetBool, 1), stato === StateB), "reset_transition_to_B")
```
