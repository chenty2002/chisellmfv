# Counterexample Analysis Report: `sg1.A_i1_to_B`

## 1. Verification Environment

- **Top Module**: `sg1` (Chisel-generated SystemVerilog)
- **Key Components**:
  - **FSM with 5 states** (A=0, B=1, C=2, D=3, E=4) defined via ChiselEnum
  - **State register** (`state[2:0]`) — initialized to A (0) during reset
  - **Output** (`io_o`) — combinatorial: `io_o = ~(|state)` i.e., true when in state A
  - **Pre-condition registers** (`A_i1_pre`, `B_i1_pre`, `B_i0_pre`, `C_pre`, `D_pre`, `E_pre`) — created via `RegNext(...)` to capture FSM transition conditions from the previous cycle
  - **Formal verification wrapper** (`ResetCounter`, `hasBeenReset`, `hasBeenResetReg`) — gates assertion evaluation until after the first reset
- **Structure**: The DUT is a simple 5-state FSM with input `io_i` controlling transitions. Assertions check one-step FSM transition correctness.

## 2. Violated Assertion

- **Full assertion name (from waveform filename)**: `A_i1_to_B`
- **Assertion label in Verilog**: `A_i1_to_B`
- **Source file**: `sg1.scala`, lines 53–55
- **Code snippet**:

```scala
// From A with io.i=1, next state must be B
val A_i1_pre = RegNext(state === States.A && io.i)
AssertProperty(!A_i1_pre || state === States.B, "A_i1_to_B")
```

- **Natural language description**: If in the previous cycle the FSM was in state A AND `io_i` was asserted (1), then in the current cycle the state must be B. This checks the FSM transition `A --(io_i=1)--> B`.

- **Generated Verilog equivalent** (lines 63–65 of `sg1.sv`):
```verilog
A_i1_to_B:
    assert property (@(posedge clock) disable iff (~hasBeenReset)
                     ~A_i1_pre | _B_i0_pre_T);
```
Where `_B_i0_pre_T = state == 3'h1` (i.e., `state == States.B`).

## 3. Waveform Information

- **Waveform file**: `verilog/extra_bench/segments/sg1.A_i1_to_B.fst`
- **Duration**: 1 cycle (0 ns → 10 ns)
- **Clock**: starts at 1, falls to 0 at 5 ns (single half-cycle)

### Critical Signals at Time 0 ns (the failure point)

| Signal | Value | Meaning |
|--------|-------|---------|
| `sg1.state [2:0]` | `000` (0) | State is A |
| `sg1.io_i` | 0 | Input is de-asserted |
| `sg1.A_i1_pre` | **1** | **PREVIOUS cycle had state=A AND io_i=1** |
| `sg1.io_o` | 1 | Output is 1 (since state=A) |
| `sg1._B_i0_pre_T` | 0 | State is NOT B |
| `sg1.reset` | 0 | Reset is inactive |
| `sg1.hasBeenReset` | 1 | Reset has occurred |
| `sg1.A_i1_to_B` | 1 | Assertion signal |

## 4. Root Cause Analysis

### Root Cause Category: **Bug in the Original Design** (incorrect register initialization)

### Bug Location

- **File**: `sg1.scala`, line 54
- **Code**: `val A_i1_pre = RegNext(state === States.A && io.i)`
- **Generated Verilog**: `sg1.sv`, line 68 (register declaration) and line 135 (update logic)

### Description of the Bug

The register `A_i1_pre` is created using Chisel's `RegNext(...)`, which generates a register with **no synchronous reset logic**. In the generated Verilog:

```verilog
always @(posedge clock) begin
  if (reset) begin
    hasBeenResetReg <= 1'h1;
    state <= 3'h0;
    pending <= 1'h0;
    timer <= 2'h0;
    // NOTE: A_i1_pre is NOT reset here!
  end
  else begin
    // ...
    A_i1_pre <= ~(|state) & io_i;
    // ...
  end
end
```

The `A_i1_pre` register (and similarly `B_i1_pre`, `B_i0_pre`, `C_pre`, `D_pre`, `E_pre`) is **absent from the reset block**. It only receives a random initial value via the `initial` block:

```verilog
initial begin
  ...
  A_i1_pre = _RANDOM[/*Zero width*/ 1'b0][4];  // Random bit!
  ...
end
```

In formal verification, initial blocks are treated as don't-cares — the register can start as **either 0 or 1**. In this counterexample, `A_i1_pre` randomly initializes to **1**, even though no previous cycle with `state=A && io_i=1` has occurred.

### Chain of Events Leading to Failure

1. **Reset completes**: `state` = States.A (0), `reset` = 0, `hasBeenReset` = 1 (assertion enabled).
2. **Initial state**: `A_i1_pre` = 1 (arbitrary random initial value), `io_i` = 0.
3. **Assertion evaluation** at (posedge clock) time 0:
   - `~A_i1_pre | _B_i0_pre_T` = `~1 | (state==B)` = `0 | 0` = **0** → **ASSERTION FAILS**
4. The assertion spuriously fails because `A_i1_pre` = 1 suggests a previous A→B transition was expected, but this is just an artifact of the uninitialized register.

### Evidence from Waveform

- **`sg1.A_i1_pre` = 1** at time 0, while **`sg1.state` = A (0)** and **`sg1.io_i` = 0**.
- The assertion condition requires that when `A_i1_pre` is 1, the state must be B (1). Since state is A (0), the assertion fires.
- All other pre-condition registers (`B_i1_pre`, `B_i0_pre`, `C_pre`, `D_pre`, `E_pre`) are 0 at time 0 — they happened to randomly initialize to 0, so their corresponding assertions do not fail.
- This is strictly an initialization/random-value issue, not a functional FSM bug.

### Why This Is Not an Assertion Error or Setup Error

- **Not an assertion error**: The property `!(state==A && io_i in prev cycle) || (state==B in curr cycle)` is semantically correct for the FSM specification.
- **Not a setup error**: The test top correctly applies reset and provides stimulus. The assertion is correctly gated by `hasBeenReset`.
- **It IS a design bug**: The `RegNext` registers used in the assertion infrastructure should be properly reset to 0 to reflect that no transitions occurred before the first cycle. Using `RegNext(condition, false.B)` instead of `RegNext(condition)` would provide the correct reset value.

### Recommended Fix

Replace all `RegNext(...)` calls in assertions with `RegNext(..., false.B)` (for Bool-typed conditions) to ensure proper reset initialization:

```scala
// Before (buggy):
val A_i1_pre = RegNext(state === States.A && io.i)

// After (fixed):
val A_i1_pre = RegNext(state === States.A && io.i, false.B)
```

This applies to all six pre-condition registers in `sg1.scala` (lines 49, 54, 57, 60, 63, 66).
