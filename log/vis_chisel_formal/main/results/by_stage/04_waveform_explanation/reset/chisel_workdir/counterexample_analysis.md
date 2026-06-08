# Counterexample Analysis Report

## 1. Verification Environment

- **Top Module**: `reset` (from `reset.scala`)
- **Design Under Test**: A module with three registers (`st0`, `st1`, `st2`) each initialized to 0 on reset, performing:
  - `st0 := io.sel(0)` — follows the LSB of input sel
  - `st1 := ~st1` — toggles every cycle
  - `st2 := io.sel(1) | st2` — sticky bit that stays set once set
  - Output `io.st = Cat(st2, st1, st0)`
- **Key Components**:
  - Three `RegInit(0.U(1.W))` registers: `st0`, `st1`, `st2`
  - An `initDone` register (`RegInit(false.B)`) used to skip first-cycle checks
  - Three formal assertions using `AssertProperty`
- **Formal Setup**: The design is compiled to Verilog and verified with Jasper Formal. The formal tool checks assertions over one clock cycle (0–10 ns). Input `io.sel` is unconstrained (free variable for the formal solver).

## 2. Violated Assertion

- **Assertion Name**: `st0_follows_sel0`
- **Source File**: `reset.scala`, line 37
- **Code Snippet**:
  ```scala
  // Safety: st0 follows io.sel(0) with a one-cycle delay through the register
  AssertProperty(st0 === RegNext(io.sel(0)), "st0_follows_sel0")
  ```
- **Property Description**: The register `st0` should always equal the one-cycle-delayed value of `io.sel(0)`. In other words, at every cycle, `st0` matches what `io.sel(0)` was on the previous cycle.

## 3. Waveform Information

- **Waveform File**: `verilog/extra_bench/reset/reset.st0_follows_sel0.fst`
- **Time Range**: 0 ns → 10 ns (1 clock cycle)
- **Key Time Point**: 0 ns (initial state, clock posedge)
- **Critical Signal Values (at all time points)**:

| Signal | Value | Interpretation |
|--------|-------|---------------|
| `reset.st0` | `0` | `RegInit(0.U)` → reset to 0, gets `io.sel(0)=0`, stays 0 |
| `reset.REG_2` | `1` | `RegNext(io.sel(0))` → **no reset**, starts at nondeterministic value 1 |
| `reset.io_sel [1:0]` | `10` | `io.sel(0)=0`, `io.sel(1)=1` |
| `reset.hasBeenReset` | `1` | Reset has been applied |
| `reset.initDone` | `0` | Initialization NOT yet done (first cycle) |
| `reset.st0_follows_sel0` | `1` | Assertion is failing (fired) |

## 4. Root Cause Analysis

### Bug Classification: **Incorrect Assertion** (assertion_error)

### Buggy Code Location

- **File**: `reset.scala`
- **Line**: 37
- **Function**: The `AssertProperty(...)` call for `st0_follows_sel0`

### Bug Description

The assertion `st0 === RegNext(io.sel(0))` compares two registers with **different reset behaviors**:

1. **`st0`** is declared as `RegInit(0.U(1.W))` — it is **explicitly initialized to 0 on reset**.
2. **`RegNext(io.sel(0))`** creates an implicit register with **no reset initialization** — its initial value is nondeterministic in formal verification.

On the **first cycle after reset** (when `initDone` is still `false`):
- `st0` = 0 (from reset)
- `RegNext(io.sel(0))` can be either 0 or 1 (nondeterministic, chosen by the formal solver)

The formal solver found a counterexample where `RegNext(io.sel(0))` = 1 at time 0, causing `st0 (0) !== RegNext(io.sel(0)) (1)`, which violates the assertion.

### Evidence from Waveform

- The waveform signal `REG_2` (which is the Verilog register backing `RegNext(io.sel(0))`) has a constant value of `1` throughout the trace, while `st0` is constantly `0`.
- Since `io.sel(0) = 0` (from `io_sel = 10`), `st0 := io.sel(0)` gives `st0 = 0`, matching the reset value.
- But `REG_2` holds `1` from the initial state — this is the nondeterministic initial value that the formal solver chose to demonstrate the violation.

### Why the Other Assertions Don't Fail

The `st1_toggles_every_cycle` assertion correctly guards against the first cycle:
```scala
AssertProperty(!initDone || (st1 === ~RegNext(st1)), "st1_toggles_every_cycle")
```
The `!initDone ||` clause skips the check on the first cycle when `RegNext` values are uninitialized.

The `st0_follows_sel0` assertion is **missing this guard**, making it fail on the first cycle.

### Fix Recommendation

Add the `!initDone ||` guard to the assertion, consistent with the `st1_toggles_every_cycle` pattern:

```scala
AssertProperty(!initDone || (st0 === RegNext(io.sel(0))), "st0_follows_sel0")
```

This skips the assertion check on the first cycle when `RegNext(io.sel(0))` may not yet match `st0` due to their different reset behaviors.
