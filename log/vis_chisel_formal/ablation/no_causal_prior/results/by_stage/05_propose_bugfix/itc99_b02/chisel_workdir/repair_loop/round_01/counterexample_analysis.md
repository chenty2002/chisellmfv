# Counterexample Analysis Report: `itc99_b02`

## 1. Verification Environment

- **Top Module**: `b02` (Chisel module with `Formal` mixin)
- **Work Directory**: `chisel/extra_bench/itc99_b02/`
- **Generated Verilog**: `chisel/extra_bench/itc99_b02/generated/b02.sv`
- **Key Components**:
  - `b02` — Main module implementing an ITC99 b02 serial controller FSM
    - `stato [2:0]` — 3-bit state register (7 states: A=0 through G=6)
    - `U_reg` — Output register for the `io.U` output signal
    - `io.LINEA` — Input control signal
    - `io.U` — Output signal (assigned from `U_reg`)
  - `ResetCounter` — External black-box module for formal verification reset tracking
- **Design Description**: A 7-state finite state machine (FSM) with one input `LINEA` and one output `U`. The FSM sequences through states A→B→C→D→E→B... for `LINEA=0`, or A→B→F→G→A... for `LINEA=1`. The output `U` is expected to be high only when the FSM is in State E.

## 2. Violated Assertion

- **Assertion Name**: `output_only_high_in_state_E` (from waveform filename `b02.output_only_high_in_state_E.fst`)
- **Source Location**: `b02.scala`, line 84
- **Code Snippet**:
  ```scala
  // Safety 2: Output should only be high when the FSM is in StateE
  fvAssert(!U_reg || stato === StateE, "output_only_high_in_state_E")
  ```
- **Generated Verilog** (line 91):
  ```verilog
  output_only_high_in_state_E:
    assert property (@(posedge clock) disable iff (~hasBeenReset) ~U_reg | _GEN_3);
  ```
  where `_GEN_3` = `stato == 3'h4` (State E).
- **Property Description**: At every positive clock edge, if `U_reg` is high (1), then the FSM must be in State E (`stato == 4`). In other words, the output `U` should only be asserted when the state machine is in state E.

## 3. Waveform Information

- **Waveform File**: `verilog/extra_bench/itc99_b02/b02.output_only_high_in_state_E.fst`
- **Duration**: 60 ns (6 clock cycles, 10 ns period)
- **Failure Time**: **t = 50 ns** (positive clock edge of cycle 6)
- **Failure Point Signal Values** (at t = 50 ns):

| Signal | Value | Meaning |
|--------|-------|---------|
| `b02.stato [2:0]` | `001` | State B (1) |
| `b02.U_reg` | `1` | Output register is high |
| `b02.io_U` | `1` | Output is high |
| `b02.io_LINEA` | `0` | Input LINEA is low |
| `b02.clock` | `1` | Rising clock edge |
| `b02.reset` | `0` | Not in reset |
| `b02.output_only_high_in_state_E` | `0` | Assertion FAILED |
| `b02._GEN_0` | `1` | `stato == 3'h1` (State B) |
| `b02.hasBeenReset` | `1` | Reset has occurred (assertion enabled) |

**Time-Line of State/Output Transitions**:

| Time (ns) | Clock Edge | `stato` | `U_reg` | Event |
|-----------|-----------|---------|---------|-------|
| 0 | posedge | 000 (A) | 0 | Initial state after reset |
| 10 | posedge | 001 (B) | 0 | A→B transition |
| 20 | posedge | 010 (C) | 0 | B→C (LINEA=0) |
| 30 | posedge | 011 (D) | 0 | C→D (LINEA=0) |
| 40 | posedge | 100 (E) | 0 | D→E. **U_reg set to 1 as part of State E action** |
| **50** | **posedge** | **001 (B)** | **1** | **E→B. U_reg=1 persists! ASSERTION FAILS** |

## 4. Root Cause Analysis

### Buggy Location
- **File**: `b02.scala`, lines 57-59
- **Module**: `b02` (inside the `switch(stato)` block)
- **State**: `is(StateE)` case

### Code
```scala
is(StateE) {
  stato := StateB     // Transition to next state
  U_reg := true.B     // Set output high
}
```

### Description of the Bug

The bug is a **race condition** between the state transition and the output register update. Both assignments happen **simultaneously** on the same clock edge (non-blocking assignments in Verilog semantics). The sequence of events is:

1. **At t = 40 ns** (posedge clock): The FSM is in State E. The `is(StateE)` branch executes:
   - `stato <= StateB` — schedules State B to be the next state
   - `U_reg <= 1'b1` — schedules U_reg to be set high

2. **At t = 50 ns** (posedge clock): The scheduled updates take effect. The FSM is now **in State B** with **U_reg = 1**. The assertion samples these values and checks `~U_reg | (stato == StateE)` = `0 | 0 = 0`, which is a **violation**.

The problem is that `U_reg` is a **registered output** that gets set to 1 in State E but retains that value for one full cycle after leaving State E. The U_reg update logic in the generated Verilog confirms this:

```verilog
U_reg <= ~(_GEN | _GEN_0 | _GEN_1 | _GEN_2) & (_GEN_3 | ~(_GEN_4 | _GEN_5) & U_reg);
```

- When in State E (`_GEN_3=1`): `U_reg` becomes **1**
- When in State B (`_GEN_0=1`): `U_reg` becomes **0** (cleared)

Since both assignments use non-blocking `<=`, the U_reg=1 set in State E persists for the entire cycle when the state has already transitioned to State B.

### Why This Causes the Assertion to Fail

The assertion `!U_reg || stato === StateE` requires that whenever `U_reg` is high, the state must be State E. At t = 50 ns:
- `U_reg = 1` (set during the State E cycle)
- `stato = State B` (already transitioned out of State E)

This creates a **one-cycle window** where the output is high outside of State E, violating the safety property.

### Bug Classification

**Type: DUT Bug** — The design has a genuine timing issue. The output `U_reg` is implemented as a registered signal that is set in State E, but it holds its value for one extra cycle after the state transitions away.

### Recommended Fix

The simplest fix is to make the output **combinatorial** from the current state rather than using a registered output. Change line 32 of `b02.scala`:

```scala
// Current (buggy):
io.U := U_reg

// Fixed:
io.U := (stato === StateE)
```

Alternatively, keep `U_reg` but clear it when transitioning out of State E by adding a separate `when` condition:

```scala
is(StateE) {
  stato := StateB
  U_reg := true.B
}
// Add elsewhere:
when(stato === StateB && stato === past(stato)) {
  // Not actually needed if we restructure...
}
```
