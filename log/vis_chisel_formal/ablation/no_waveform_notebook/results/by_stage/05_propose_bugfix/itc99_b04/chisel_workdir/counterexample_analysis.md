# Counterexample Analysis: `liveness_reach_sC`

## 1. Verification Environment

- **Top Module**: `b04` (package `llmverify`)
- **Source File**: `b04.scala` (211 lines)
- **Design**: ITC99 b04 benchmark — a finite state machine that processes signed 8-bit data values, tracking running maximum (RMAX), minimum (RMIN), last value (RLAST), and maintaining a 4-deep pipeline of input values (REG1–REG4). The state machine has three states: sA (0), sB (1), sC (2), where sC is the steady operating state.
- **Formal Framework**: Chisel `Formal` with `fvAssert` assertions

## 2. Violated Assertion

### Assertion Name (from waveform filename)
`liveness_reach_sC`

### Code Snippet
```scala
val first_cycle = RegInit(true.B)
first_cycle := false.B
val first_cycle_d3 = ShiftRegister(first_cycle, 3)
fvAssert(!first_cycle_d3 || (stato === sC), "liveness_reach_sC")
```

### Property Description
The assertion checks a bounded-liveness property: after reset, the state machine must reach the steady operating state `sC` within 3 clock cycles. The mechanism:
1. `first_cycle` is a flag that is `true` only at cycle 0 (right after reset), then goes `false`.
2. `first_cycle_d3` is `first_cycle` delayed by 3 cycles via a shift register.
3. At cycle 3 (when `first_cycle_d3` becomes true), the assertion checks that `stato === sC`.

### File Location
- **Path**: `b04.scala`
- **Line**: ~204 (the `fvAssert` call for `liveness_reach_sC`)

## 3. Waveform Information

### Waveform File
`verilog/extra_bench/itc99_b04/b04.liveness_reach_sC.fst`

### Time Range
0 ns → 10 ns (1 clock cycle)

### Key Signal Values at Time 0 ns

| Signal | Value | Expected |
|--------|-------|----------|
| `b04.stato [1:0]` | `00` (sA) | sA (correct for cycle 0) |
| `b04.first_cycle` | `1` (true) | true (correct for cycle 0) |
| `b04.first_cycle_d3_r` | `1` | **should be 0** (shift register stage 1) |
| `b04.first_cycle_d3_r_1` | `1` | **should be 0** (shift register stage 2) |
| `b04.first_cycle_d3` | `1` | **should be 0** (shift register output) |
| `b04.:jasper_formal_reset` | `0` | Not in reset |
| `b04.hasBeenReset` | `1` | Reset has completed |
| `b04.io_RESTART` | `0` | — |
| `b04.io_ENABLE` | `0` | — |
| `b04.io_AVERAGE` | `0` | — |
| `b04.io_DATA_IN [7:0]` | `00` | — |
| `b04.liveness_reach_sC` | `1` | Assertion violated |

### Assertion Evaluation at Time 0
`!first_cycle_d3 || (stato === sC)` = `!1 || (sA === sC)` = `false || false` = **false** → assertion violated

## 4. Root Cause Analysis

### Bug Classification: **Incorrect Assertion** (Assertion/Setup Issue)

### Root Cause
The assertion uses `ShiftRegister(first_cycle, 3)` to create a 3-cycle delayed marker. **The `ShiftRegister` internal registers are not properly initialized to 0 in the formal verification context.**

In Chisel, `ShiftRegister(in, n)` generates `n` stages of `RegNext` without explicit initialization values. In a formal verification environment, registers without explicit `RegInit` are treated as having **symbolic (arbitrary) initial values**. The formal solver can therefore choose any initial value for each shift register stage.

In this specific counterexample, the solver chose:
- `first_cycle_d3_r` (stage 1) = 1
- `first_cycle_d3_r_1` (stage 2) = 1
- `first_cycle_d3` (output) = 1

This causes `first_cycle_d3` to be `true` at cycle 0 — the very first cycle after reset — when the assertion check fires. At that point, `stato` is still `sA` (the initial state), so the assertion fails.

### Why This Is Not a DUT Bug
The state machine itself is working correctly: at cycle 0, `stato = sA` is the expected initial state. The state machine transitions `sA → sB → sC` over cycles 1–2, and by cycle 3 it would be in `sC`. The issue is purely in how the assertion's timing mechanism is constructed.

### Evidence from Waveform
1. `b04.stato [1:0]` = `00` (sA) at time 0 — correct initial state
2. `b04.first_cycle` = `1` — correct, marks cycle 0
3. `b04.first_cycle_d3` = `1` — **incorrect** for cycle 0 (should be 0)
4. All three shift register stages (`first_cycle_d3_r`, `first_cycle_d3_r_1`, `first_cycle_d3`) are 1 at time 0, confirming that the shift register was not properly initialized

### Proposed Fix
Replace `ShiftRegister(first_cycle, 3)` with explicit `RegNext` stages that have proper initialization to `false.B`:

```scala
val first_cycle_d1 = RegNext(first_cycle, false.B)
val first_cycle_d2 = RegNext(first_cycle_d1, false.B)
val first_cycle_d3 = RegNext(first_cycle_d2, false.B)
```

This ensures that all three delay stages are explicitly initialized to `false`, so `first_cycle_d3` will only become true after 3 clock cycles have elapsed — giving the state machine enough time to reach `sC`.

### Alternative Fixes
If the explicit-register approach still has issues with the formal backend:

**Option 2 — Counter-based approach:**
```scala
val cycle_cnt = RegInit(0.U(3.W))
when (cycle_cnt =/= 7.U) { cycle_cnt := cycle_cnt + 1.U }
fvAssert(cycle_cnt < 3.U || (stato === sC), "liveness_reach_sC")
```

**Option 3 — Simpler liveness property (untimed):**
```scala
val reached_sC = RegInit(false.B)
when (stato === sC) { reached_sC := true.B }
fvAssert(reached_sC, "liveness_reach_sC")
```
