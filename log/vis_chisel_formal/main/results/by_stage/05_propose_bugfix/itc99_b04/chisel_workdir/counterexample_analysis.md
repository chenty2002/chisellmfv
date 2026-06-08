# Counterexample Analysis Report: `itc99_b04`

## 1. Verification Environment

- **Top Module**: `b04` (extends `Module with Formal`)
- **Source File**: `b04.scala` (167 lines)
- **Key Components**:
  - **State Machine**: 3-state FSM (`sA`, `sB`, `sC`) with register `stato`
  - **Registers**: `RMAX`, `RMIN`, `RLAST`, `REG1–REG4`, `DATA_OUT` — all `RegInit(0.U(8.W))`
  - **Inputs**: `RESTART`, `AVERAGE`, `ENABLE`, `DATA_IN[7:0]`
  - **Outputs**: `DATA_OUT[7:0]`, `stato[1:0]`, `RMAX`, `RMIN`, etc.
- **Design Description**: Translation of ITC99 b04 benchmark — a sequential data-processing circuit with min/max tracking, pipeline registers, and restart averaging logic.

## 2. Violated Assertion

| Field | Value |
|---|---|
| **Assertion Name** | `restart_output_correct` |
| **Waveform File** | `b04.restart_output_correct.fst` |
| **File Location** | `b04.scala`, lines 126–129 |
| **Code** | ```scala
fvAssert(
  !(stato === sC && io.RESTART) || DATA_OUT === avg(RMAX, RMIN),
  "restart_output_correct"
)
``` |
| **Property** | When the state machine is in state `sC` AND `io.RESTART` is asserted, the output `DATA_OUT` must equal `avg(RMAX, RMIN)` (the average of the current min and max values). |
| **Type of Failure** | The assertion fires because the condition is checked **in the same cycle** when `RESTART` goes high, but `DATA_OUT` is a register that hasn't been updated yet. |

## 3. Waveform Information

- **Waveform File Path**: `verilog/extra_bench/itc99_b04/b04.restart_output_correct.fst`
- **Waveform Duration**: 30 ns (3 clock cycles)
- **Clock Period**: 10 ns
- **Time of Failure**: 20 ns (cycle 2)

### Critical Signal Values at Time 20 ns

| Signal | Value | Interpretation |
|---|---|---|
| `b04.stato [1:0]` | `10` (binary) = `sC` | State machine has transitioned to sC |
| `b04.io_RESTART` | `1` | RESTART is asserted |
| `b04.DATA_OUT [7:0]` | `00000000` (0x00) | DATA_OUT still holds its initial value |
| `b04.RMAX [7:0]` | `01010000` (0x50 = 80) | Max register = DATA_IN from cycle 1 |
| `b04.RMIN [7:0]` | `01010000` (0x50 = 80) | Min register = DATA_IN from cycle 1 |
| `b04.io_DATA_IN [7:0]` | `01010000` (0x50 = 80) | Input data |
| `b04.io_ENABLE` | `0` | ENABLE is deasserted |
| `b04.io_AVERAGE` | `1` | AVERAGE is asserted |

### Sequence of Events

| Time (ns) | Clock Cycle | State | RESTART | DATA_IN | DATA_OUT | Notes |
|---|---|---|---|---|---|---|
| 0 | 0 | sA | 0 | 0x03 | 0x00 | Initial state; transitions to sB |
| 10 | 1 | sB | 0 | 0x50 | 0x00 | In sB: `RMAX := DATA_IN`, `RMIN := DATA_IN`. `DATA_OUT := Mux(RESTART=0, avg(...), 0)` = 0. Transitions to sC. |
| 20 | 2 | sC | 1 | 0x50 | **0x00** | In sC: `when(RESTART)` → `DATA_OUT := avg(RMAX,RMIN)` scheduled for next cycle. **Assertion fires here** because `DATA_OUT (0) !== avg(RMAX,RMIN)`. |

## 4. Root Cause Analysis

### Root Cause Category: **Incorrect Assertion (Assertion Error)**

### Description

The assertion `restart_output_correct` on line 126–129 of `b04.scala` suffers from a **timing mismatch**. It uses `fvAssert` to verify a condition that can only be true on the **next clock cycle** after the trigger condition.

### Evidence from Code

**1. `DATA_OUT` is a sequential register (line 42):**
```scala
val DATA_OUT = RegInit(0.U(8.W))
```
All `:=` assignments to `DATA_OUT` take effect on the **next clock edge**, not immediately.

**2. In state `sC`, the assignment is conditional on `RESTART` (lines 88–98):**
```scala
when(io.RESTART) {
  DATA_OUT := avg(RMAX, RMIN)
}.elsewhen(io.ENABLE) {
  ...
}.otherwise {
  DATA_OUT := RLAST
}
```
When `RESTART=1` in cycle 2 (time 20 ns), the register `DATA_OUT` **schedules** the assignment `avg(RMAX,RMIN)` for the next clock edge — the current value remains `0`.

**3. The assertion checks the same-cycle value (lines 126–129):**
```scala
fvAssert(
  !(stato === sC && io.RESTART) || DATA_OUT === avg(RMAX, RMIN),
  "restart_output_correct"
)
```
The `fvAssert` combinator evaluates its predicate **in the same clock cycle**. At the moment when `stato === sC && io.RESTART` is true, `DATA_OUT` still holds its previous value (`0`), not the newly assigned `avg(RMAX,RMIN)`.

### Evidence from Waveform

At **time 20 ns**:
- `stato` = `10` (sC) — just transitioned from sB
- `io_RESTART` = `1` — condition trigger
- `DATA_OUT` = `0x00` — still holding the initial/previous value
- The assertion requires `DATA_OUT === avg(RMAX,RMIN) = avg(0x50,0x50)`, which would be approximately 0x50, but DATA_OUT = 0x00

The other assertions in the same file (Safety 4–7, lines 131–145) correctly use `assertNextStepWhen` for checking register updates that take effect on the next cycle:
```scala
assertNextStepWhen(stato === sC, REG2 === RegNext(REG1), "pipeline_reg1_to_reg2")
```

This confirms the pattern: Safety 3 should also use `assertNextStepWhen` instead of `fvAssert`.

### Fix

Change the assertion on lines 126–129 from:

```scala
fvAssert(
  !(stato === sC && io.RESTART) || DATA_OUT === avg(RMAX, RMIN),
  "restart_output_correct"
)
```

To:

```scala
assertNextStepWhen(stato === sC && io.RESTART, DATA_OUT === avg(RMAX, RMIN), "restart_output_correct")
```

This checks: **On the next cycle after** `stato === sC && io.RESTART` is true, `DATA_OUT` should equal `avg(RMAX, RMIN)` — which matches the register timing behavior of the design.

### Why This Fix Works

- When `stato === sC` and `io.RESTART` is asserted at cycle `N`, the `when(io.RESTART)` block assigns `DATA_OUT := avg(RMAX, RMIN)`.
- At cycle `N+1`, `DATA_OUT` has been updated to `avg(RMAX, RMIN)`.
- `assertNextStepWhen` checks the property at cycle `N+1`, which is exactly when `DATA_OUT` holds the correct value.
