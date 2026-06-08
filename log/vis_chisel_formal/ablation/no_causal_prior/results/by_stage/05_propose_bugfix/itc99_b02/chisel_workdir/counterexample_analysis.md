# Counterexample Analysis Report: `from_A_goes_to_B`

## 1. Verification Environment

- **Top module**: `b02` (itc99_b02 benchmark)
- **Design under test**: A 7-state one-hot encoded finite state machine (FSM) with states A through G, controlled by the `io_LINEA` input
- **Input**: `io_LINEA` (Bool)
- **Output**: `io_U` (Bool, high only in StateE)
- **Clock/reset**: Standard posedge clock with synchronous reset
- **Assertion framework**: `chiselFv` library with `fvAssert` macro, wrapping SVA `assert property` with `disable iff (~hasBeenReset)`

## 2. Violated Assertion

- **Assertion name**: `from_A_goes_to_B` (from waveform filename `b02.from_A_goes_to_B.fst`)
- **File location**: `b02.scala`, line 91
- **Code snippet**:

```scala
// From StateA, next state must be StateB
fvAssert(!(prevStato === StateA) || (stato === StateB), "from_A_goes_to_B")
```

- **Generated Verilog** (line ~125 of `generated/b02.sv`):

```verilog
from_A_goes_to_B:
    assert property (@(posedge clock) disable iff (~hasBeenReset)
                     prevStato != 7'h1 | _GEN_0);
```

Where:
- `StateA = 7'h1 = 0000001` (one-hot encoding, bit 0)
- `StateB = 7'h2 = 0000010` (one-hot encoding, bit 1)
- `_GEN_0 = (stato == 7'h2)` (i.e., `stato === StateB`)
- `hasBeenReset = hasBeenResetReg === 1'h1 & reset === 1'h0`

- **Property description**: "At every posedge clock, if the FSM was in StateA during the previous cycle (`prevStato === StateA`), then the current state must be StateB (`stato === StateB`)." This property verifies the unconditional transition `StateA → StateB`.

## 3. Waveform Information

- **Waveform file**: `verilog/extra_bench/itc99_b02/b02.from_A_goes_to_B.fst`
- **Duration**: 1 cycle (0 ns → 10 ns)
- **Time range**: Only one posedge at time 0; clock falls at time 5 ns and stays low through time 10 ns
- **Critical signal values**:

| Signal | Time 0 (posedge) | Time 5 | Time 10 |
|--------|:--------:|:------:|:-------:|
| `clock` | 1 | 0 | 0 |
| `reset` | 0 | 0 | 0 |
| `stasto [6:0]` | 0000001 (StateA) | 0000001 | 0000001 |
| `prevStato [6:0]` | 0000001 (StateA) | 0000001 | 0000001 |
| `hasBeenResetReg` | 1 | 1 | 1 |
| `hasBeenReset` | 1 | 1 | 1 |
| `_GEN_0` (stasto==StateB) | 0 | 0 | 0 |
| `io_LINEA` | 1 | 1 | 1 |
| `_GEN` (stasto==StateA) | 1 | 1 | 1 |
| `io_U` | 0 | 0 | 0 |

**Key observation**: All signals are constant throughout the entire trace. There is no clock transition after time 5 (no second posedge).

### Assertion Evaluation at Time 0 (posedge clock)

```
prevStato != 7'h1 | _GEN_0
= 0000001 != 0000001 | (0000001 == 0000010)
= 0 | 0
= 0  → FAIL
```

## 4. Root Cause Analysis

### Nature of the Issue: **Setup/Initialization Error** (primary) + **Assertion Timing Sensitivity** (secondary)

#### 4.1 The `hasBeenReset` X-Initialization Problem

The `fvAssert` wrapper in the chiselFv library generates:

```verilog
reg hasBeenResetReg;
initial
    hasBeenResetReg = 1'bx;
wire hasBeenReset = hasBeenResetReg === 1'h1 & reset === 1'h0;
```

The `hasBeenResetReg` register is initialized to `1'bx` (unknown). In formal verification, X may be interpreted as either 0 or 1. When the tool interprets it as **1**, `hasBeenReset` evaluates to `1` on the very first cycle (before any reset cycle), making the assertions **active immediately** without a proper reset sequence having occurred.

In a realistic simulation scenario, the flow would be:
1. **Reset cycle** (`reset=1`): `hasBeenResetReg <= 1`, `stasto <= StateA`, `prevStato <= <old_value>`
2. **First non-reset cycle** (`reset=0`): `hasBeenReset = 1` (set by the reset cycle), assertion fires
   - `prevStato = <old_value>` (random/X, typically NOT StateA after random initialization)
   - `stasto` transitions from StateA → StateB via non-blocking assignment
   - Assertion: `prevStato != StateA` is most likely true → **PASS**
3. **Second non-reset cycle**: `prevStato = StateA`, `stasto = StateB` → **PASS**

But in the formal counterexample, `hasBeenResetReg` is initialized to 1 (from X) and `reset` is 0, so `hasBeenReset = 1` activates the assertion at the very first cycle without any reset having occurred.

#### 4.2 The Non-Blocking Assignment Timing Issue

The DUT uses non-blocking assignments (`<=`) in an `always @(posedge clock)` block for the state transition:

```verilog
always @(posedge clock) begin
    if (reset) begin
        stato <= 7'h1;  // StateA
    end
    else if (_GEN)      // stato == StateA
        stato <= 7'h2;  // StateB
    ...
    prevStato <= stato;  // captures old value of stato
end
```

At the counterexample's only posedge (time 0):
- `stato = StateA` (current value, before non-blocking update)
- `_GEN = (stato == StateA) = 1`, so `stato <= StateB` (scheduled for after the posedge)
- `prevStato <= stato` (captures the **old** StateA value)
- The assertion evaluates **before** the non-blocking assignments take effect (in SVA's "preponed" region)

So the assertion sees `prevStato = StateA` and `stato = StateA` (not yet StateB), causing the failure. The assertion formulation `!(prevStato === StateA) || (stato === StateB)` is **correct for steady-state behavior** but fails at the first cycle because `prevStato` and `stato` are both initialized to `StateA` via `RegInit`, and the non-blocking transition hasn't propagated yet.

#### 4.3 Why This Is Not a DUT Bug

The DUT correctly implements:
```scala
is(StateA) {
    stato := StateB  // Correct FSM transition
}
```

The transition `StateA → StateB` is structurally correct and would be confirmed after one full clock cycle of operation.

#### 4.4 All Transition Assertions Are Similarly Affected

The same RegNext-based pattern is used for all transition assertions (lines 91–120 of `b02.scala`):
- `from_D_goes_to_E`
- `from_E_goes_to_B`
- `from_F_goes_to_G`
- And all conditional transition assertions

All of these will fail at the first non-reset cycle if the `hasBeenReset` gate is active, because `prevStato` captures the value of `stato` before the transition takes effect.

#### 4.5 Evidence Summary

| Evidence | Detail |
|----------|--------|
| `hasBeenResetReg` = 1 at time 0 | Formal tool interpreted X as 1, activating assertions without reset |
| `reset` = 0 throughout trace | No reset cycle occurred |
| `prevStato` = StateA throughout | Initialized same as `stato` (via RegNext semantics) |
| `_GEN_0` = 0 throughout | `stato` never shows StateB (non-blocking not yet applied) |
| Single clock cycle | Only 1 posedge; insufficient for proper FSM initialization |

### Root Cause Classification: **Setup Error (setup_error)**

The primary root cause is that the `hasBeenResetReg` X-initialization (`1'bx`) in the formal verification framework allows assertions to fire without a proper reset cycle. This creates an unrealistic scenario where the assertion evaluates at the very first posedge with both `prevStato` and `stato` at `StateA`, before the non-blocking transition assignment has taken effect.

Two potential fixes:
1. **Fix in the formal library**: Initialize `hasBeenResetReg` to `0` instead of `1'bx`, ensuring assertions only fire after at least one reset cycle has occurred.
2. **Fix in the assertion**: Restructure the transition assertions to use a two-cycle delay (e.g., `RegNext(RegNext(stato))` for the "from" condition) to avoid the first-cycle timing sensitivity.

