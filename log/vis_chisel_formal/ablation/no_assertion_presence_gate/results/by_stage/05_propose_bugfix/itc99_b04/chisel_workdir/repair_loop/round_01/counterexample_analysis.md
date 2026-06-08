# Counterexample Analysis Report: itc99_b04

## 1. Verification Environment

- **Top Module**: `b04` (from `chisel/extra_bench/itc99_b04/b04.scala`)
- **Key Components**:
  - FSM with 3 states: sA (0), sB (1), sC (2)
  - Registers: RMAX, RMIN, RLAST, REG1–REG4, DATA_OUT
  - Registers cycle through sA → sB → sC and then stay in sC
  - Average/min/max computation on 8-bit signed data
- **Formal Environment**: Chisel `Formal` module with `assertImpliesDelay` assertions, verified using a formal tool

## 2. Violated Assertion

| Field | Value |
|---|---|
| **Assertion Name** | `sA_to_sC_in_2_cycles` |
| **File** | `b04.scala`, line 138 |
| **Chisel Source** | `assertImpliesDelay(stato === sA, stato === sC, 2, "sA_to_sC_in_2_cycles")` |
| **Intended Property** | When the FSM state is sA, then the FSM must reach sC within 2 clock cycles |

### Generated Verilog SVA (incorrect)

```verilog
wire _GEN = stato == 2'h2;  // stato == sC

sA_to_sC_in_2_cycles:
    assert property (@(posedge clock) disable iff (~hasBeenReset) _GEN);
```

The assertion should have been an SVA implication with a delay range, roughly:
```verilog
assert property (@(posedge clock) disable iff (~hasBeenReset)
    (stato == 2'h0) |-> ##[0:2] (stato == 2'h2));
```

But what was generated is a simple unconditional assertion: `stato == 2'h2` at every cycle.

## 3. Waveform Information

| Field | Value |
|---|---|
| **Waveform File** | `verilog/extra_bench/itc99_b04/b04.sA_to_sC_in_2_cycles.fst` |
| **Duration** | 1 cycle (10 ns), time range 0→10 ns |
| **Clock** | posedge at time 0, negedge at time 5 |

### Critical Signal Values at Time 0 ns (first clock edge)

| Signal | Value | Meaning |
|---|---|---|
| `b04.stato [1:0]` | `00` | sA (0) — initial state |
| `b04._GEN` | `0` | `stato == 2'h2` is false |
| `b04.sA_to_sC_in_2_cycles` | `1` | Assertion fired and failed |
| `b04.hasBeenReset` | `1` | Assertion is active (not disabled) |
| `b04.hasBeenResetReg` | `1` | Reset has been applied |
| `b04.reset` | `0` | Reset signal is low |
| `b04.io_ENABLE` | `0` | Enable is low |
| `b04.io_RESTART` | `0` | Restart is low |
| `b04.io_DATA_IN [7:0]` | `11111111` | All 1s on data bus |
| `b04.io_DATA_OUT [7:0]` | `00000000` | Output is 0 |
| `b04.RMAX [7:0]` | `00000000` | Max register = 0 |
| `b04.RMIN [7:0]` | `00000000` | Min register = 0 |
| `b04.REG [7:0]` | `00000000` | Assertion tracking register |

## 4. Root Cause Analysis

### Category: Assertion Compilation Error (Setup/Toolchain Issue)

**The violation is caused by incorrect compilation of the `assertImpliesDelay` function, NOT by a bug in the DUT logic or an incorrectly written assertion.**

### Detailed Explanation

The Chisel source code at line 138 of `b04.scala` correctly expresses the property:

```scala
assertImpliesDelay(stato === sA, stato === sC, 2, "sA_to_sC_in_2_cycles")
```

This should generate a SystemVerilog Assertion (SVA) with:
1. An **antecedent** (`stato === sA`) as the triggering condition
2. A **delay range** (`##[0:2]`) allowing 0–2 cycles for the consequent to hold
3. A **consequent** (`stato === sC`) as the expected outcome

However, the generated Verilog shows:

```verilog
wire       _GEN = stato == 2'h2;  // Only the consequent remains

sA_to_sC_in_2_cycles:
    assert property (@(posedge clock) disable iff (~hasBeenReset) _GEN);
```

**Both the antecedent (implication guard) and the delay range are completely lost.** The assertion is reduced to a simple unconditional check: `stato == 2'h2` at every clock cycle.

### Why the Assertion Fails

1. At time 0 (first posedge clock), `stato` = sA = `2'h0` (the `RegInit(sA)` initial value)
2. The generated assertion checks `stato == 2'h2` (sC) — this is **false** at time 0 because the FSM starts in sA
3. The assertion `sA_to_sC_in_2_cycles` fires immediately at time 0, indicating a violation

### The DUT is Actually Correct

The FSM transitions correctly:
- **Cycle 0** (time 0): stato = sA (0) → next state = sB (1)
- **Cycle 1** (time 10): stato = sB (1) → next state = sC (2)  
- **Cycle 2** (time 20): stato = sC (2)

So the property "from sA, reach sC within 2 cycles" should **pass** — the FSM correctly reaches sC at cycle 2.

### Evidence of Systematically Incorrect Compilation

The same issue affects all `assertImpliesDelay` assertions:

| Chisel Assertion | Generated SVA | Issue |
|---|---|---|
| `assertImpliesDelay(stato === sA, stato === sC, 2, ...)` | `stato == 2'h2` | Antecedent + delay lost |
| `assertImpliesDelay(stato === sC, stato === sC, 1, ...)` | `stato == 2'h2` | Antecedent lost (same check as above!) |
| `assertImpliesDelay(stato === sC, REG2 === RegNext(REG1), 1, ...)` | `REG2 == REG` | Antecedent `stato===sC` lost |

All delay=1 assertions at least retained the consequent tracking registers (REG, REG_1, REG_2), but **none** of them retained the antecedent guard condition. The delay=2 case lost everything — no pipeline, no implication, just the bare consequent.

### Root Cause Summary

**Bug**: The `assertImpliesDelay` function in the Chisel formal library/compilation pipeline does not generate correct SystemVerilog Assertions with implication (`|->`) and timing delays (`##[0:n]`). The antecedent condition and delay are stripped during compilation, leaving only the consequent as an unconditional immediate assertion.

**Impact**: This causes false violations because the assertion fires at cycle 0 when `stato = sA`, even though the DUT correctly transitions sA → sB → sC within the 2-cycle window.
