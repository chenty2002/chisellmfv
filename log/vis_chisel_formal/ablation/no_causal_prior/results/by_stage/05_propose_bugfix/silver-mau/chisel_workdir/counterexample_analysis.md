# Counterexample Analysis Report: `reset_holds_state_idle`

## 1. Verification Environment

- **Top Module**: `controlvis` (Chisel module with `Formal` mixin)
- **Generated Verilog**: `chisel/extra_bench/silver-mau/generated/`
- **Waveform File**: `verilog/extra_bench/silver-mau/controlvis.reset_holds_state_idle.fst`
- **Key Components**:
  - `stateReg [2:0]` — 3-bit FSM state register (IDLE=000, READ_HIT=001, READ_MISS=010, READ_DATA=011, WRITE_HIT=100, WRITE_MISS=101)
  - `vector [5:0]` — 6-bit control vector encoding outputs
  - `io.Rst_n` — Active-low synchronous reset input
  - Input registers (`rWorkMAU`, `rAccessMode`, `rMatch`, `rValid`, etc.) — synchronized inputs
  - FSM transitions through states based on input conditions

## 2. Violated Assertion

- **Assertion Name**: `reset_holds_state_idle`
- **Full Name**: `controlvis.reset_holds_state_idle`
- **File Location**: `controlvis.scala`, lines 195–198

### Code Snippet

```scala
fvAssert(
    !io.Rst_n === (stateReg === State.IDLE),
    "reset_holds_state_idle"
)
```

### Natural Language Description

The assertion claims that the reset signal being asserted (`!io.Rst_n` is true, i.e., `io.Rst_n` is 0) is **equivalent** to the state being IDLE. In other words, the state is IDLE **if and only if** reset is asserted.

### Breakdown of the Property

The assertion `!io.Rst_n === (stateReg === State.IDLE)` is a **biconditional** (equivalence) that requires:

1. **If reset IS asserted** (`io.Rst_n = 0`, i.e., `!io.Rst_n = true`), then `stateReg === State.IDLE` must be true. ✓ *(This part is correct)*
2. **If reset is NOT asserted** (`io.Rst_n = 1`, i.e., `!io.Rst_n = false`), then `stateReg === State.IDLE` must be false (state must NOT be IDLE). ✗ *(This part is wrong)*

## 3. Waveform Information

- **Waveform Path**: `verilog/extra_bench/silver-mau/controlvis.reset_holds_state_idle.fst`
- **Duration**: 1 cycle (0 ns – 10 ns)
- **Time Point of Failure**: Time 0 ns (initial state)

### Critical Signal Values at Failure Point (Time 0 ns)

| Signal | Value | Meaning |
|--------|-------|---------|
| `controlvis.clock` | 1 | Clock high |
| `controlvis.io_Rst_n` | 1 | **Reset NOT asserted** |
| `controlvis.stateReg [2:0]` | 000 = `State.IDLE` | FSM in IDLE state |
| `controlvis.io_State [2:0]` | 000 | Same as stateReg |
| `controlvis.reset_holds_state_idle` | 1 | Assertion checker signal |

### Derived Values at Time 0

- `!io.Rst_n` = `!1` = `false` (0)
- `stateReg === State.IDLE` = `0 === 0` = `true` (1)
- `!io.Rst_n === (stateReg === State.IDLE)` = `0 === 1` = `false` → **Assertion fails!**

## 4. Root Cause Analysis

### Root Cause Type: **Incorrect Assertion (assertion_error)**

The bug is in the assertion itself, not in the design. The assertion `reset_holds_state_idle` is incorrectly written.

### Buggy Code

**File**: `controlvis.scala`, lines 195–198

```scala
fvAssert(
    !io.Rst_n === (stateReg === State.IDLE),
    "reset_holds_state_idle"
)
```

### Description of the Bug

The assertion uses the Chisel equivalence operator `===` to create a **biconditional** statement: "reset is asserted if and only if the state is IDLE." This is too strong.

The assertion requires that the FSM state is IDLE **only when** reset is asserted, and never when reset is de-asserted. However, the IDLE state is the **normal resting state** of the FSM — the FSM naturally returns to IDLE after completing any operation (READ_HIT, READ_DATA, WRITE_HIT, WRITE_MISS all transition to IDLE via the FSM logic, lines 117–138). The IDLE state is also the initial state on power-up (line 77: `val stateReg = RegInit(State.IDLE)`).

### Why This Is Wrong

The **intended** property is: **"When reset is asserted, the state must be IDLE."** This is an **implication** (reset asserted → state is IDLE), not a biconditional (reset asserted ↔ state is IDLE).

An implication allows the state to be IDLE when reset is not asserted — which is exactly the normal behavior shown in the counterexample waveform.

### Correct Assertion Already Exists

The correct version of this assertion already exists in the same file at lines 203–206:

```scala
fvAssert(
    io.Rst_n || stateReg === State.IDLE,
    "reset_asserts_idle"
)
```

This is logically equivalent to `!io.Rst_n → stateReg === State.IDLE` (if reset is asserted, state must be IDLE), which is the correct implication. It passes because:
- When `io.Rst_n = 0` (reset asserted): `0 || (stateReg === State.IDLE)` → requires `stateReg === State.IDLE` ✓
- When `io.Rst_n = 1` (reset not asserted): `1 || ...` → always true, regardless of state ✓

### Evidence from Waveform

The counterexample waveform definitively shows:

| Condition | Value | Expected by Correct Property | Expected by Buggy Property |
|-----------|-------|------------------------------|----------------------------|
| `io.Rst_n = 1` (reset NOT asserted) | | OK (reset not asserted, implication allows anything) | **FAILS** (biconditional requires state ≠ IDLE) |
| `stateReg = IDLE` (000) | | OK (normal idle state) | **FAILS** (biconditional requires state ≠ IDLE when reset is not asserted) |

### Impact

This is a **false positive** — the formal tool correctly found a counterexample to the assertion, but the assertion itself is wrong. The design is actually correct. The `reset_asserts_idle` assertion (which correctly captures the intended property using implication) would pass on this same trace.

### Recommended Fix

**Option 1 (Recommended)**: Remove the erroneous `reset_holds_state_idle` assertion entirely, since `reset_asserts_idle` already correctly covers the intended property.

**Option 2**: Fix the assertion to use implication:

```scala
fvAssert(
    !io.Rst_n || stateReg === State.IDLE,
    "reset_holds_state_idle"
)
```

Or equivalently:

```scala
fvAssert(
    io.Rst_n || stateReg === State.IDLE,
    "reset_holds_state_idle"
)
```
