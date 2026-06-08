# Counterexample Analysis: `StateG_to_StateE_when_LINEA0`

## 1. Verification Environment

- **Top module**: `b02` (ITC99 benchmark translation to Chisel)
- **Structure**: A single-module FSM with 7 states (StateA through StateG) communicating via a state register `stato`
- **Key components**:
  - `stato [2:0]` — 3-bit state register (initialized to StateA = 0)
  - `U_reg` — output register (initialized to false)
  - `io.LINEA` — Boolean input
  - State transition logic — implemented via Chisel `switch/is/when` construct, compiled to a mux tree (`_GEN_2`)
- **Formal framework**: Chisel `Formal` with `fvAssert` assertions and `astRelaxedLiveness` properties

## 2. Violated Assertion

- **Full assertion name**: `StateG_to_StateE_when_LINEA0`
- **Waveform file**: `b02.StateG_to_StateE_when_LINEA0.fst`

### Code Snippet

From `b02.scala`, lines 103–104:

```scala
// Safety: From StateG, when LINEA is false, the next state is unconditionally StateE.
fvAssert(!(stato === StateG && io.LINEA === false.B) || RegNext(stato) === StateE,
  "StateG_to_StateE_when_LINEA0")
```

### Property Description

The comment correctly states: *"From StateG, when LINEA is false, the next state is unconditionally StateE."* This matches the FSM definition (lines 86–90):

```scala
is(StateG) {
    when(io.LINEA === false.B) {
        stato := StateE
    }.otherwise {
        stato := StateA
    }
    U_reg := false.B
}
```

However, **the assertion formulation does NOT match the comment**. The assertion uses `RegNext(stato) === StateE` which checks the **previous** state (delayed by one register), not the **next** state. The correct temporal check requires deferring the *condition* to the previous cycle, not the *consequence*.

### File Location

- **File**: `b02.scala`
- **Line**: 103–104
- **Module**: `class b02`

## 3. Waveform Information

### Waveform File

- **Full path**: `verilog/extra_bench/itc99_b02/b02.StateG_to_StateE_when_LINEA0.fst`
- **Duration**: 40 ns (4 clock cycles, 10 ns period, 50% duty cycle)
- **Clock edges**: rising at 0, 10, 20, 30 ns; falling at 5, 15, 25, 35 ns

### FSM Trace (by rising clock edge)

| Time (ns) | Rising Edge | stato | State | LINEA | Next State (design) |
|-----------|-------------|-------|-------|-------|---------------------|
| 0         | Yes         | 000   | A     | 1     | B                   |
| 10        | Yes         | 001   | B     | 1     | F (since LINEA=1)   |
| 20        | Yes         | 101   | F     | 0     | G (unconditional)   |
| 30        | Yes         | 110   | G     | 0     | E (since LINEA=0)   |

### Values at Assertion Failure Point (time = 30 ns)

| Signal                    | Value   | Meaning                           |
|---------------------------|---------|-----------------------------------|
| `b02.stato [2:0]`         | `110`   | StateG (6)                        |
| `b02.io_LINEA`            | `0`     | false                             |
| `b02.U_reg`               | `0`     | false                             |
| `b02.REG_1 [2:0]`         | `101`   | RegNext(stato) = StateF (5)       |
| `b02.REG_1 [2:0]`         | `101`   | RegNext(stato) = StateF (5)       |
| `b02._GEN_2[6] [2:0]`     | `100`   | Next-state mux for StateG = StateE (4) ✓ |
| `b02._GEN_2[5] [2:0]`     | `110`   | Next-state mux for StateF = StateG (6) ✓ |

### Assertion Evaluation at time 30 ns

```
!(stato === StateG && io.LINEA === false.B) || RegNext(stato) === StateE
→ !(true && true) || (5 === 4)
→ false || false
→ false   ← ASSERTION FAILS
```

## 4. Root Cause Analysis

### Classification: **Incorrect Assertion** (`assertion_error`)

The assertion has a **temporal misalignment**: it checks the **previous** state register value (`RegNext(stato)`) against StateE, when the property actually requires checking that the **next** state (one cycle later) equals StateE.

### Detailed Explanation

The FSM transition for StateG when `LINEA == 0` is:

```
stato := StateE   // happens on next clock edge
```

At time 30 ns:
- The clock rises and `stato` transitions from StateF (5 → `101`) to **StateG** (6 → `110`)
- In this same cycle, the FSM already computes the *next* next state: StateG with LINEA=0 produces **StateE**
- This is confirmed by `_GEN_2[6] = 100` (StateE) at time 30 ns — the mux output for the StateG entry

The assertion incorrectly compares `RegNext(stato)` (= state before this cycle = StateF = 5) to StateE (4), which is a nonsensical check. The assertion should instead **shift the condition backward in time** — i.e., check that *if in the previous cycle we were in StateG with LINEA=0, then in this cycle we are in StateE*.

### Buggy Code

**File**: `b02.scala`, **lines 103–104**:
```scala
fvAssert(!(stato === StateG && io.LINEA === false.B) || RegNext(stato) === StateE,
  "StateG_to_StateE_when_LINEA0")
```

### Correct Fix

Change the assertion to register the *condition*, not the *consequence*:

```scala
fvAssert(RegNext(!(stato === StateG && io.LINEA === false.B) || stato === StateE),
  "StateG_to_StateE_when_LINEA0")
```

This reads: *"If, in the previous cycle, we were in StateG with LINEA=0, then in THIS cycle, stato must be StateE."* This correctly implements the property described in the comment.

### Why the Design Is Correct

The FSM logic (`_GEN_2[6] = 100 = StateE`) confirms that from StateG with LINEA=0, the correct next state is StateE. The design itself has no bug — it faithfully implements the ITC99 b02 specification. The assertion simply misapplies the `RegNext` temporal operator.

### Comparison with `U_causal_from_StateE` (Correct Assertion)

For reference, the sibling assertion at line 100–101 is correctly written:

```scala
fvAssert(!U_reg || RegNext(stato) === StateE, "U_causal_from_StateE")
```

This correctly uses `RegNext(stato)` because:
- `U_reg` is set to `true.B` in StateE
- `U_reg` becomes visible *in the next cycle* (after the FSM transitions to StateB)
- Therefore, when `U_reg` is true, the *previous* state must have been StateE — exactly what `RegNext(stato)` checks

The failed assertion swaps this logic: it should check that *if the previous condition held, the current state is StateE* — requiring `RegNext` on the left side of the implication, not the right side.
