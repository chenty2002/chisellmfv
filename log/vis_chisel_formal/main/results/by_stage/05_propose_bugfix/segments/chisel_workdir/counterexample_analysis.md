# Counterexample Analysis Report: `sg1.state_E_is_sink`

## 1. Verification Environment

### Top Module
- **Module**: `sg1` (package `llmverify`)
- **Source File**: `sg1.scala`
- **Generated Verilog**: `generated/sg1.sv`

### Design Under Test
The DUT implements a 5-state finite state machine (FSM) with states A, B, C, D, and E:

```
A → (io_i=0) A, (io_i=1) B
B → (io_i=0) D, (io_i=1) C
C → B
D → E
E → E
```

The output `io_o` is `(state === States.A)`, which is true only when in state A.

### Key Components
- **State register** (`state [2:0]`): 3-bit register holding current FSM state
- **Input** (`io_i`): Single-bit input controlling state transitions
- **Output** (`io_o`): Single-bit output, high only in state A
- **`hasBeenReset`**: Reset tracking signal from ChiselFv library (used for assertion disable)
- **`ResetCounter`**: External module tracking post-reset stabilization count

### Assertion Infrastructure
- **ChiselFv** library provides `fvAssert`, `assertNextStepWhen`, and `astRelaxedLiveness` macros
- **`assertNextStepWhen(antecedent, consequent, name)`** is intended to assert: *whenever `antecedent` holds in a cycle, `consequent` must hold in the next cycle*
- Assertions are compiled to SystemVerilog Assertions (SVA) via the Chisel/firtool flow

## 2. Violated Assertion

### Assertion Name
`state_E_is_sink` (from waveform filename: `sg1.state_E_is_sink.fst`)

### Source Code (sg1.scala, line 48)
```scala
// Safety: State E is a sink - once in E, stays in E forever
assertNextStepWhen(state === States.E, state === States.E, "state_E_is_sink")
```

### Intended Property
**"When state is E in a cycle, state should remain E in the next cycle."** This is the standard "sink state" invariant: once the FSM enters state E, it should never leave it.

### Generated Verilog (generated/sg1.sv)
```verilog
wire       _GEN_2 = state == 3'h4;    // state === States.E (value 4 = 3'h4)
state_E_is_sink: assert property (@(posedge clock) disable iff (~hasBeenReset) _GEN_2);
```

### What the Generated Assertion Actually Checks
At EVERY cycle (when `hasBeenReset` is true), `state === 3'h4` (state must be E). This is **not** the intended property — it checks that state is E *at all times*, not only when it transitions from E.

## 3. Waveform Information

### Waveform File
`verilog/extra_bench/segments/sg1.state_E_is_sink.fst`

### Time Range and Key Time Points
- **0 ns**: Posedge of clock — assertion evaluated; **ASSERTION FAILS HERE**
- **5 ns**: Negedge of clock — all values unchanged
- **10 ns**: Negedge of clock — all values unchanged (second cycle)

### Waveform Duration
1 cycle (0 ns → 10 ns)

### Critical Signal Values at Failure Point (t=0 ns)

| Signal | Value | Meaning |
|--------|-------|---------|
| `clock` | 1 | Posedge — assertion evaluation point |
| `reset` | 0 | Not in reset |
| `hasBeenReset` | 1 | Reset completed, assertions are enabled (not disabled) |
| `state [2:0]` | `000` (3'h0) | **States.A** — NOT in state E |
| `io_i` | 0 | Input is low |
| `io_o` | 1 | Output high (correct for state A) |
| `_GEN_2` | 0 | `state == 3'h4` is FALSE |
| `state_E_is_sink` (monitor) | 1 | Assertion monitor signal is active (property being checked) |

### Failure Mechanism
At the posedge of `clock` at t=0 ns:
1. `disable iff (~hasBeenReset)` evaluates to `disable iff (0)` → assertion is **enabled** (not disabled)
2. The assertion expression `_GEN_2` = `state == 3'h4` is evaluated
3. Since `state = 3'h0` (States.A), the expression evaluates to **0** (false)
4. The SVA assertion expects the property expression to be true at every posedge clock
5. **Assertion fails** because `state == 3'h4` is false

## 4. Root Cause Analysis

### Root Cause Category: **Incorrect Assertion** (assertion_error)

### Bug Location
**ChiselFv library** — the `assertNextStepWhen` macro in `sg1.scala`, line 48, and the underlying `AssertProperty`/`fvAssert` mechanism.

### Description of the Bug
The `assertNextStepWhen` macro is implemented using:
```scala
when(delayedBool(antecedent, 1, sticky = false)) {
  fvAssert(consequent, name)
}
```

The `when` block is intended to make the assertion conditional — the assertion should only fire when the antecedent condition held in the previous cycle. However, in the generated Verilog, the `when` condition is **completely lost**. The assertion appears unconditionally at the module top level:

```verilog
state_E_is_sink: assert property (@(posedge clock) disable iff (~hasBeenReset) _GEN_2);
```

The Chisel → Verilog compilation flow (firtool) does **not** properly gate `assert property` statements within `when` blocks. The `assert property` directive is emitted as a module-level concurrent assertion, active at every posedge clock, regardless of any `when` condition in the Chisel source.

### Evidence from Waveform and Source Code

1. **No pipe registers for antecedent delays**: The `delayedBool(antecedent, 1, ...)` call should create a 1-bit pipeline register to delay the antecedent signal. The waveform signal list contains **no such registers** — the only registers are `state`, `pending`, `timer` (and their variants), and `hasBeenResetReg`. This confirms that the `delayedBool` logic and the `when` condition around `AssertProperty` were both optimized away or never emitted.

2. **Unconditional assertion in Verilog**: The generated SVA assertion has **no antecedent condition**, no `|->` or `|=>` operator, and no if-condition. It is purely:
   ```
   assert property (@(posedge clock) disable iff (~hasBeenReset) _GEN_2);
   ```
   This checks `_GEN_2` (state == E) at every cycle unconditionally.

3. **Counterexample demonstrates the bug**: At t=0 ns, the state is `000` (A), not `100` (E). A correct implementation of `assertNextStepWhen` would **not** fire this assertion because the antecedent (`state === E`) never held in a prior cycle. But the generated assertion fires and fails, proving the conditional gating is missing.

4. **All `assertNextStepWhen` assertions are affected**: The generated Verilog shows that ALL assertions from `assertNextStepWhen` are unconditional:
   - `A_stays_on_input_0`: checks `~(|state)` (state == A) at every cycle
   - `A_goes_to_B_on_input_1`: checks `state == B` at every cycle
   - `B_goes_to_D_on_input_0`: checks `state == D` at every cycle
   - `B_goes_to_C_on_input_1`: checks `state == C` at every cycle
   - `C_goes_to_B`: checks `state == B` at every cycle
   - `D_goes_to_E`: checks `state == E` at every cycle
   - `E_stays_in_E`: checks `state == E` at every cycle

   All of these are incorrect for the same reason — they should be conditional on their antecedents.

### Why This Causes the Assertion to Fail

The assertion `state_E_is_sink` is the first to be checked by the formal tool because the FSM starts in state A (3'h0). The assertion checks `state == 3'h4` at every cycle. Since the initial state is A (not E), the assertion fails at cycle 0 — the very first clock edge. The assertion is checking an invariant that should only apply when the FSM is in state E, but it is applied unconditionally to all states.

### Correct Intended Behavior
The correct SVA for the intended property would be:
```verilog
state_E_is_sink:
  assert property (@(posedge clock) disable iff (~hasBeenReset)
                   (state == 3'h4) |=> (state == 3'h4));
```
This uses the `|=>` (non-overlapping implication) operator: *if state was E at a cycle, then in the next cycle state must also be E.* Alternatively, with `delayedBool`:
```verilog
// When the antecedent was true last cycle (delayed by 1), check consequent
assert property (@(posedge clock) disable iff (~hasBeenReset)
                 antecedent_delayed |-> consequent);
```

## Summary

| Aspect | Finding |
|--------|---------|
| **Root Cause** | ChiselFv's `assertNextStepWhen` macro generates unconditional SVA assertions — the `when(cond)` gating of `AssertProperty` is lost during Chisel→Verilog compilation |
| **Error Type** | `assertion_error` — the assertion is incorrectly generated, not the DUT logic |
| **DUT Behavior** | The FSM logic is correct; state A with io_i=0 correctly stays in A |
| **Fix Required** | The `assertNextStepWhen` macro must generate conditional SVA (e.g., using `|=>` implication operator) instead of relying on `when` blocks to gate `AssertProperty` calls |
