# Counterexample Analysis Report: `itc99_b04` / `b04.stay_in_sC.fst`

---

## 1. Verification Environment

| Component | Description |
|---|---|
| **Top Module** | `b04` — Chisel module that translates the original ITC99 b04 benchmark circuit |
| **Design Under Test** | A finite-state machine (FSM) that computes running statistics (RMAX, RMIN) over an input data stream |
| **Key Components** | FSM states sA (idle), sB (init), sC (steady); registers RMAX, RMIN, RLAST, REG1–REG4, DATA_OUT |
| **Assertion Framework** | Chisel FV (`chiselFv`) with `Formal` trait; assertions compiled to SystemVerilog `assert property` for the Jasper formal tool |
| **Verilog File** | `generated/b04.sv` |

**State Machine Transitions**: sA (2'h0) → sB (2'h1) → sC (2'h2) → self-loop in sC.

---

## 2. Violated Assertion

| Field | Value |
|---|---|
| **Assertion Name** | `stay_in_sC` |
| **Waveform File** | `verilog/extra_bench/itc99_b04/b04.stay_in_sC.fst` |

### Source Code (Chisel, `b04.scala` line ~123):

```scala
// Safety: Once the state machine enters sC (the steady state), it must
// stay in sC forever. The state machine transitions sA -> sB -> sC, then
// the sC state loops back to itself via "stato := sC".
assertNextStepWhen(stato === sC, stato === sC, "stay_in_sC")
```

### Generated Verilog (`generated/b04.sv`, lines ~166-168):

```verilog
wire _GEN_0 = stato == 2'h2;  // stato == sC

stay_in_sC:
    assert property (@(posedge clock) disable iff (~hasBeenReset)
                     _GEN_0);
```

### Natural Language Description of the Intended Property

**Intended**: "Once the state machine enters state sC, it must remain in sC forever." Formally: `(stato == sC) -> next(stato == sC)`. This is a classic "once-in-steady-state, stay-in-steady-state" safety property that is vacuously true when the state machine is in sA or sB.

**What Was Actually Generated**: `assert property (stato == sC)` — an unconditional "stato must always equal sC" at every clock cycle. This is a much stronger assertion that fails whenever the FSM is in sA or sB.

---

## 3. Waveform Information

| Field | Value |
|---|---|
| **Waveform File** | `verilog/extra_bench/itc99_b04/b04.stay_in_sC.fst` |
| **Duration** | 1 cycle (0 ns – 10 ns) |
| **Key Time Points** | 0 ns (failure point) |

### Critical Signal Values at Failure (t = 0 ns)

| Signal | Value | Meaning |
|---|---|---|
| `b04.stato [1:0]` | `00` (2'h0) | State machine is in **sA** (initial reset state) |
| `b04._GEN_0` | `0` | `stato == 2'h2` is FALSE — assertion condition unmet |
| `b04.stay_in_sC` | `1` (asserted) | Assertion fires (fails) at this cycle |
| `b04.reset` | `0` | Reset is NOT active |
| `b04.hasBeenReset` | `1` | Reset has been deasserted, so assertion is **enabled** |
| `b04.hasBeenResetReg` | `1` | Confirms reset has already been applied |
| `b04.io_DATA_IN [7:0]` | `11111111` | Input data (irrelevant to this assertion) |
| `b04.io_ENABLE` | `0` | Not enabled |
| `b04.io_RESTART` | `0` | Not restarting |
| `b04.io_AVERAGE` | `0` | Not averaging |

### Signal Trace Summary

- **`b04.stato [1:0]`** remains `00` (sA) for the entire trace (t = 0 ns through t = 10 ns). The simulation does not advance the state machine through sA → sB → sC because the formal tool found a counterexample at the very first cycle.
- **`b04._GEN_0`** is `0` at all times because `stato` never equals `2'h2`.
- **`b04.stay_in_sC`** (assertion fail signal) is asserted (`1`) at t = 0 ns and remains asserted.

---

## 4. Root Cause Analysis

### Classification: **Incorrect Assertion (`assertion_error`)**

### Root Cause

The root cause is a **bug in how the `assertNextStepWhen` Chisel FV assertion is compiled into Verilog**. The Chisel FV library's implementation of `assertNextStepWhen(cond, result, name)` does **not** generate the correct temporal implication.

#### What the Chisel Code Intends

```scala
assertNextStepWhen(stato === sC, stato === sC, "stay_in_sC")
```

This should express: **"when `stato === sC` holds at the current cycle, then `stato === sC` must hold at the next cycle"** — i.e., `(stato == sC) |=> (stato == sC)`.

This property is vacuously true when the state machine is in sA or sB, and only becomes active once the steady state sC is reached. It correctly captures the intent "once in sC, stay in sC."

#### What the Verilog Actually Checks

```verilog
wire _GEN_0 = stato == 2'h2;
stay_in_sC: assert property (@(posedge clock) disable iff (~hasBeenReset) _GEN_0);
```

This asserts **`stato == 2'h2` at every single clock cycle unconditionally**. It has **no temporal implication** (`|=>` or `|->`). It is equivalent to "stato MUST always be sC," which contradicts:

1. The reset state initializes `stato := sA` (RegInit(sA))
2. The FSM transitions sA → sB → sC, so stato is legitimately sA and sB during normal operation

#### Why the Assertion Fails

- At t = 0 ns (first positive clock edge after reset), `stato` is `2'h0` (sA)
- The assertion checks `_GEN_0 = (stato == 2'h2)`, which evaluates to `FALSE`
- Since `hasBeenReset` is `1`, the disable condition `~hasBeenReset` is `0`, so the assertion is **enabled**
- The assertion fails immediately on the first cycle

#### What the Correct Assertion Should Generate

The correct SystemVerilog assertion should be:

```verilog
stay_in_sC:
    assert property (@(posedge clock) disable iff (~hasBeenReset)
                     (stato == 2'h2) |=> (stato == 2'h2));
```

This would be vacuously true when `stato` is not sC, and would check the "stay in sC" property only when it's relevant.

### Evidence Summary

| Evidence Item | Detail |
|---|---|
| **Chisel source** (`b04.scala:123`) | `assertNextStepWhen(stato === sC, stato === sC, "stay_in_sC")` — intends temporal implication |
| **Generated Verilog** (`generated/b04.sv:166-168`) | `assert property (_GEN_0)` where `_GEN_0 = stato == 2'h2` — **no temporal operator** |
| **Waveform** (t = 0 ns) | `stato = 00` (sA), `_GEN_0 = 0`, assertion fires |
| **FSM reset** | `val stato = RegInit(sA)` — starts in sA, not sC |

### Recommended Fix

The fix should be applied to the **Chisel FV library's implementation of `assertNextStepWhen`**. The compiler/lowering pass for `assertNextStepWhen` should generate a SystemVerilog assertion with the `|=>` (non-overlapping implication) temporal operator rather than a simple combinatorial check.

Alternatively, the user could rewrite the assertion manually using `fvAssert` with explicit temporal logic:

```scala
// Workaround: manually implement the temporal implication
when (stato === sC) {
  fvAssert(stato === sC, "stay_in_sC_next")
}
// Or use a formal property library that correctly lowers temporal assertions
```

**Note**: This is NOT a bug in the original design — the b04 state machine correctly transitions sA → sB → sC and loops in sC. The design is sound. The assertion is incorrectly compiled from Chisel to Verilog.
