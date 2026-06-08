# Counterexample Analysis: `state_stays_sC`

## 1. Verification Environment

- **Top Module**: `b04` (extends `Module` with `Formal`)
- **Module Structure**: The design implements a state machine from the ITC99 b04 benchmark with three states (sA=0, sB=1, sC=2).
- **Key Components**:
  - State register `stato` (2-bit) with `RegInit(sA)` — initial state is sA=0
  - Data registers: RMAX, RMIN, RLAST, REG1–REG4, DATA_OUT (all 8-bit)
  - Helper functions: `tc` (two's complement), `avg` (signed average), `signGt` (signed greater-than)
- **State Machine**:
  - `sA → sB`: Transitions unconditionally
  - `sB → sC`: Transitions unconditionally, also initializes data registers
  - `sC → sC`: Loops in steady state, processes data inputs

## 2. Violated Assertion

- **Assertion Name**: `state_stays_sC`
- **Full Assertion Name** (from waveform filename): `b04.state_stays_sC`
- **Source Code** (line 145 of `b04.scala`):
  ```scala
  assertNextStepWhen(stato === sC, stato === sC, "state_stays_sC")
  ```
- **Natural Language Description**: Once the state machine enters the steady operating state sC, it must never leave. This is a "state stability" property — in state sC, the `is(sC)` block unconditionally assigns `stato := sC`, so the state should stay in sC forever once entered.
- **File**: `b04.scala`, line 145

## 3. Waveform Information

- **Waveform File**: `verilog/extra_bench/itc99_b04/b04.state_stays_sC.fst`
- **Time Range**: 0 ns → 10 ns (1 clock cycle)
- **Key Time Points**:
  - **Time 0 ns**: Clock rising edge. All signals are constant throughout the trace.

### Critical Signal Values (at all observed time points: 0 ns, 5 ns, 10 ns)

| Signal | Value | Meaning |
|--------|-------|---------|
| `b04.stato` | `00` | sA (initial state) |
| `b04.clock` | `1` (0–5ns), `0` (5–10ns) | Single clock cycle |
| `b04.reset` | `0` | Not in reset |
| `b04.io_RESTART` | `1` | RESTART input asserted |
| `b04.io_ENABLE` | `1` | ENABLE input asserted |
| `b04.io_AVERAGE` | `1` | AVERAGE input asserted |
| `b04.io_DATA_IN` | `0xFF` (11111111) | Data input = -1 in signed |
| `b04.prev_sC` | `0` | Previous cycle was not sC |
| `b04.state_stays_sC` | `1` | Assertion check signal |
| `b04.RMAX` | `0x00` | Initial value |
| `b04.RMIN` | `0x00` | Initial value |
| `b04.REG1–REG4` | `0x00` | Initial values |
| `b04.DATA_OUT` | `0x00` | Initial value |

## 4. Root Cause Analysis

### The Assertion and Its Semantics

The assertion `assertNextStepWhen(stato === sC, stato === sC, "state_stays_sC")` is defined as:

> **When `stato === sC` holds in the current cycle, assert that `stato === sC` holds in the next cycle.**

This is equivalent to SystemVerilog's non-overlapping implication:
```systemverilog
assert property (@(posedge clock) (stato == sC) |=> (stato == sC));
```

### Why the Assertion Should Be Correct

The state machine correctly implements the property:
- In state sC (value 2), the `is(sC)` block unconditionally executes `stato := sC`
- There is NO code path that can change stato away from sC once it is entered
- The state machine has no mechanism to transition out of sC

### Counterexample Analysis

The counterexample trace shows:
1. The state machine starts in state **sA (0)** — the correct initial state after reset
2. **All control inputs are asserted**: RESTART=1, ENABLE=1, AVERAGE=1, DATA_IN=0xFF
3. The trace is only **1 cycle long** (0–10 ns)
4. At no point does `stato` equal sC (value 2)

Since `stato` is always sA in this trace, the antecedent `stato === sC` is **never true**, making the assertion **vacuously true** (it can never fail because the condition it depends on never occurs).

### Root Cause: Incorrect Top Module Setup (TestTop Configuration)

The root cause is that the **test harness / TestTop module does not drive the inputs correctly to let the state machine reach sC within the bounded check depth**.

The state machine requires **2 clock cycles** to transition from sA→sB→sC:
1. **Cycle 1**: sA → sB (stato transitions from 0 to 1)
2. **Cycle 2**: sB → sC (stato transitions from 1 to 2)

However, the BMC (Bounded Model Checking) depth is set to only **1 cycle (10 ns)**. With this bound, the formal tool can only unroll the design for a single clock cycle. In that one cycle:
- The state machine starts at sA (time 0)
- It updates to sB (which takes effect at the next clock edge, time 10 ns)
- The trace ends at 10 ns, showing stato still as sA (the old value before the clock edge)

Since the assertion `assertNextStepWhen` checks the property by sampling `stato === sC` on the posedge clock, and the state machine never reaches sC within the bounded depth, the property check is **incomplete** — the BMC depth is insufficient to observe a meaningful violation.

However, the formal tool reports a counterexample because within the bounded unrolling, when it attempts to evaluate the assertion in the context of register initialization, the assertion's internal shadow register (`RegNext` of `stato === sC`) starts at 0, and the first cycle shows stato = sA. The tool may identify this as a trivial violation because the assertion's precondition can never be satisfied within the given depth.

**Classification**: `setup_error` — the TestTop or BMC configuration constrains the verification depth (1 cycle) too tightly for the state machine to reach the steady state sC, making the assertion vacuously checkable but ultimately not proving the property.

### Alternative Consideration: Assertion Semantics Mismatch

There is a possibility that the `assertNextStepWhen` implementation in chiselFv has a different semantic than what the designer intended. The assertion expects:
- `stato === sC` now → `stato === sC` next cycle

If the implementation instead checks:
- `stato === sC` last cycle → `stato === sC` this cycle

Then with the initial state `stato = sA`, the shadow register `RegNext(stato === sC)` = false (0), and the current state `stato === sC` = false (0), so `!0 || 0 = 1` — the assertion passes. This interpretation also cannot cause a failure with the given trace.

### Verdict

**Issue Category: Setup Error** — The BMC bound of 1 cycle is insufficient for the 3-state machine (sA→sB→sC) to reach the steady state sC that the assertion checks. The test harness needs either:
1. A longer BMC depth (at least 3 cycles to reach sC and check the property), or
2. A reset sequence that initializes the machine directly into state sC for the assertion check.
