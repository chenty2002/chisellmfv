# Counterexample Analysis: E_is_terminal Assertion Failure

## 1. Verification Environment

### Top Module
- **Module**: `sg1` (from `sg1.scala`)
- **Module Type**: `Module with Formal` (extends Chisel's `Formal` trait for formal verification)

### Key Components
- **State Register**: `state [2:0]` — a 3-bit FSM state register initialized to `States.A` (0)
- **Input**: `io_i` — single-bit boolean input
- **Output**: `io_o` — single-bit boolean output (`state === States.A`)
- **State Encoding** (ChiselEnum): A=0, B=1, C=2, D=3, E=4

### FSM Transitions
| Current State | Condition | Next State |
|---|---|---|
| A | io_i=1 | B |
| A | io_i=0 | A |
| B | io_i=1 | C |
| B | io_i=0 | D |
| C | (any) | B |
| D | (any) | E |
| E | (any) | E (terminal/absorbing) |

## 2. Violated Assertion

### Assertion Name
`E_is_terminal` (from waveform filename: `sg1.E_is_terminal.fst`)

### Source Code
From `sg1.scala`, line 48:
```scala
// Safety 2: State E is a terminal sink — once in E, stay in E
assertNextStepWhen(state === States.E, state === States.E, "E_is_terminal")
```

### Expected Property
The assertion `assertNextStepWhen(precondition, postcondition, name)` is intended to check: **if `precondition` holds at the current clock cycle, then `postcondition` must hold at the next clock cycle.** Specifically for `E_is_terminal`:

> **"If the FSM is in state E at cycle N, then the FSM must also be in state E at cycle N+1."**

This verifies that state E is a terminal/absorbing state — once entered, the FSM never leaves it.

### Generated Verilog (broken lowering)
From `generated/sg1.sv`:
```verilog
wire _GEN_2 = state == 3'h4;                // state == E
E_is_terminal: assert property (@(posedge clock) disable iff (~hasBeenReset) _GEN_2);
```

The assertion checks `_GEN_2` (i.e., `state == E`) **unconditionally at every clock cycle**, rather than checking it only when the precondition was true in the previous cycle.

## 3. Waveform Information

### Waveform File
`verilog/extra_bench/segments/sg1.E_is_terminal.fst`

### Time Range
0 ns → 10 ns (1 full clock cycle)

### Key Signal Values

| Signal | Time 0ns | Time 5ns | Time 9ns |
|---|---|---|---|
| `sg1.state [2:0]` | `000` (A) | `000` (A) | `000` (A) |
| `sg1.io_i` | `0` | `0` | `0` |
| `sg1.io_o` | `1` | `1` | `1` |
| `sg1.E_is_terminal` | `1` (FAIL) | `1` (FAIL) | `1` (FAIL) |
| `sg1.clock` | `1` | `0` | `0` |
| `sg1.reset` | `0` | `0` | `0` |
| `sg1.hasBeenReset` | `1` | `1` | `1` |

### Failure Point
At time 0 ns (first posedge clock after reset), the assertion fires:
- `hasBeenReset` = 1 → assertion is enabled (not disabled)
- `state` = `000` (A), so `_GEN_2` = `0` (state != E)
- `E_is_terminal` assertion signal = `1` (failure)

## 4. Root Cause Analysis

### Root Cause Category: **Incorrect Assertion / Setup Error**

The root cause is that the **`assertNextStepWhen` construct fails to generate proper gating logic** in the Verilog output. The delayed-precondition register that should gate the assertion is missing from the compiled design.

### Detailed Explanation

#### How `assertNextStepWhen` Should Work

From the `chiselFv/Formal.scala` implementation (lines 123-127):
```scala
def assertNextStepWhen(cond: Bool, asert: Bool, msg: String = ""): Unit = {
  assertAfterNStepWhen(cond, 1, asert, msg)
}

def assertAfterNStepWhen(cond: Bool, n: Int, asert: Bool, msg: String = ""): Unit = {
  when(delayedBool(cond && notChaos, n, sticky = false)) {
    fvAssert(asert, msg)
  }
}
```

This chains two levels of gating:
1. **Outer gate** (`delayedBool`): Creates a 1-bit shift register to delay the precondition by one cycle. The output of this register is true only in cycles *following* a cycle where the precondition was true.
2. **Inner gate** (`notChaos`): A reset-completion flag that prevents assertions from firing during reset.

The `fvAssert` function (line 88) further wraps assertions in a `when(notChaos)` condition:
```scala
def fvAssert(cond: Bool, msg: String = ""): Unit = {
  when(notChaos) {
    AssertProperty(cond, msg)
  }
}
```

#### What Actually Gets Generated

The generated Verilog shows none of this gating logic:

```verilog
E_is_terminal: assert property (@(posedge clock) disable iff (~hasBeenReset) _GEN_2);
```

Key observations:
1. **No delayed-precondition register**: The `pipe` register that should store the precondition for one cycle (from `delayedBool`) is completely absent from the generated module.
2. **No precondition gating**: The assertion checks `_GEN_2 = (state == E)` at **every** clock cycle, not just when the precondition was true.
3. **No `notChaos` gating**: The `when(notChaos)` wrapper around `fvAssert` is also lost — only the `disable iff (~hasBeenReset)` remains (which is a different mechanism for initialization).

#### Evidence from Waveform

The counterexample confirms the unconditional nature of the assertion:

- At time 0 (first posedge), `state = A (000)`, `_GEN_2 = 0` → assertion fails
- **The precondition `state === E` was never true** (state is A throughout)
- If the delayed register were present, the assertion would not fire because the precondition was false at the previous cycle
- The fact that the assertion fires despite `state ≠ E` proves the gating logic is missing

#### Impact on All Assertions

This issue affects **all** assertions generated by `assertNextStepWhen` and `fvAssert`:

| Assertion Name | Original Semantics | Generated Check (all fail when state=A) |
|---|---|---|
| `E_is_terminal` | `state=E_last → state=E_now` | `state == E` |
| `A_i1_to_B` | `state=A & io_i → next state=B` | `state == B` |
| `B_i1_to_C` | `state=B & io_i → next state=C` | `state == C` |
| `B_i0_to_D` | `state=B & !io_i → next state=D` | `state == D` |
| `C_to_B` | `state=C → next state=B` | `state == B` |
| `D_to_E` | `state=D → next state=E` | `state == E` |
| `output_eq_state_A` | `io_o === (state===A)` | `1'h1` (always passes, vacuously true) |

Note: `output_eq_state_A` also has the `when(notChaos)` gate missing, but its condition simplifies to `1'h1` which always passes.

### Conclusion

The DUT's FSM logic is correct — state E correctly stays in E on every cycle. However, the assertion `E_is_terminal` is not being correctly lowered to Verilog. The `assertNextStepWhen` precondition gating (via `delayedBool`) is lost during compilation, causing the assertion to check `state == E` unconditionally at every clock cycle. Since the FSM starts in state A after reset (not E), the assertion immediately fails.

**This is an assertion/setup error**: the `assertNextStepWhen` construct (and by extension `fvAssert`) does not generate the necessary gating logic (`when` blocks) around the `AssertProperty` calls in the output Verilog, making the assertions fire unconditionally rather than conditionally on their preconditions.
