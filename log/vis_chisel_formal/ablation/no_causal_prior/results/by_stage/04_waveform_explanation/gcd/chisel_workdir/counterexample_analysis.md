# Counterexample Analysis Report: `output_correct_on_done`

## 1. Verification Environment

- **Benchmark**: `gcd`
- **Top Module**: `Gcd` (class `Gcd` in package `llmverify`)
- **Design Under Test**: A binary GCD (Greatest Common Divisor) circuit for unsigned N-bit numbers (default N=8, logN=3). The design computes GCD using a subtraction-based binary GCD algorithm with dynamic LSB tracking.
- **Key Components**: Internal registers `x`, `y`, `lsb`, `busyReg`, `oReg`; combinational wires `done`, `load`, `diff`, `xy_lsb`.

## 2. Violated Assertion

- **Assertion Name**: `output_correct_on_done` (from waveform filename `Gcd.output_correct_on_done.fst`)
- **Full Assertion Code** (gcd.scala, line 92):
  ```scala
  assertImplies(done, oReg === Mux(x < y, x, y), "output_correct_on_done")
  ```
- **Natural Language Property**: When the `done` signal is asserted (computation completed), the output register `oReg` must hold the GCD result, which is `min(x, y)`.
- **File Location**: `gcd.scala`, line 92.

## 3. Waveform Information

- **Waveform File**: `verilog/extra_bench/gcd/Gcd.output_correct_on_done.fst`
- **Time Range**: 0 ns → 20 ns (2 clock cycles, clock period = 10 ns)
- **Waveform Duration**: 2 cycles

### Key Signal States

| Time | Signal | Value | Notes |
|------|--------|-------|-------|
| 0 ns | `clock` | rising edge | First clock edge |
| 0 ns | `io_start` | 1 | Start signal asserted |
| 0 ns | `io_a` | 8 (`00001000`) | Input a |
| 0 ns | `io_b` | 8 (`00001000`) | Input b |
| 0 ns | `load` | 1 | `load = io_start && !busyReg` |
| 0 ns | `x` | 0 (`00000000`) | Before register update |
| 0 ns | `y` | 0 (`00000000`) | Before register update |
| 0 ns | `oReg` | 0 (`00000000`) | Output register still zero |
| 0 ns | `busyReg` | 0 | Not busy yet |
| 0 ns | `done` | 0 | Combinational: `(0===0) && 0 = 0` |
| 10 ns | `clock` | rising edge | Second clock edge — **failure point** |
| 10 ns | `x` | 8 (`00001000`) | Loaded at time 0 |
| 10 ns | `y` | 8 (`00001000`) | Loaded at time 0 |
| 10 ns | `busyReg` | 1 | Busy since time 0 edge |
| 10 ns | `done` | 1 | Combinational: `(8===8) && 1 = 1` |
| 10 ns | `oReg` | 0 (`00000000`) | **Still zero — not yet updated** |
| 10 ns | `load` | 0 | `io_start && !busyReg = 1 && 0 = 0` |

### Assertion Evaluation at Failure (time 10 ns)

```
assertImplies(done, oReg === Mux(x < y, x, y))
→ assertImplies(1, 0 === Mux(8 < 8, 8, 8))
→ assertImplies(1, 0 === 8)
→ 1 → false  // FAILS: done=1 but oReg=0 ≠ 8
```

- `output_correct_on_done` transitions from `1` (initial) to `0` (failing) at time 10 ns.

## 4. Root Cause Analysis

### Buggy Code Location

**File**: `gcd.scala`, lines 42–67 (Data path logic in `class Gcd`)

### Description of the Bug

The bug is a **one-cycle-late output register update** — a genuine **design (DUT) bug**.

The `done` signal is computed **combinationally** from the register outputs:

```scala
done := ((x === y) || (x === 0.U) || (y === 0.U)) && busyReg   // Line 38
```

The `oReg` register is updated **only** in the `.elsewhen(done)` branch of the data path logic:

```scala
when(load) {                                 // Line 42
  x := io.a                                  // Line 43
  y := io.b                                  // Line 44
  lsb := 0.U                                 // Line 45
}.elsewhen(busyReg && !done) { ... }         // Line 46
}.elsewhen(done) {                            // Line 65
  oReg := Mux(x < y, x, y)                   // Line 66
}                                             // Line 67
```

**The problem**: When the inputs are equal (e.g., `a=8`, `b=8`), the GCD is immediately known. After the `load` cycle (time 0), registers take values `x=8`, `y=8`. In the very next cycle (time 10), `done` goes high combinationally because `(8===8)` is true. However, `oReg` **has not yet been updated** — it was never written during the `load` phase, and the `.elsewhen(done)` branch writes to `oReg` only at the same clock edge where `done` is already high.

Due to the semantics of register updates (non-blocking assignment in hardware), `oReg` retains its old value (`0`) during the assertion evaluation at time 10 ns, even though the `.elsewhen(done)` block will correctly update it to `8` **after** the clock edge. The assertion expects `oReg` to already hold `min(x,y) = 8` when `done` goes high, but the design only schedules the update for the same cycle — effectively one cycle too late.

### Conditions for Triggering the Bug

The assertion fails whenever the GCD is immediately known after loading, specifically when:
1. **Input a equals input b** (as in this counterexample: `a=8, b=8`), OR
2. **Input a is zero** (`a=0`), OR
3. **Input b is zero** (`b=0`)

In all these cases, `done` goes high the cycle after `load`, before `oReg` has been written with the result.

### Evidence from Waveform

| Time | `x` | `y` | `done` | `load` | `oReg` | `Mux(x<y,x,y)` | Assertion |
|------|-----|-----|--------|--------|--------|----------------|-----------|
| 0 | 0 | 0 | 0 | 1 | 0 | 0 | OK (done=0) |
| 10 | 8 | 8 | **1** | 0 | **0** | **8** | **FAIL** |

### Root Cause Category

**DUT Bug** — The output register `oReg` is not set to the correct GCD result early enough. When inputs are equal or contain zeros, the GCD is known immediately upon loading, but `oReg` is not updated until the `done` branch fires, which is one cycle too late for the assertion.

### Suggested Fix

The `when(load)` block should also set `oReg` to handle the edge case where the GCD is immediately known upon loading:

```scala
when(load) {
  x := io.a
  y := io.b
  lsb := 0.U
  // Set oReg immediately for cases where result is already known
  // (when a==b, a==0, or b==0, done will fire next cycle)
}.elsewhen(busyReg && !done) {
  // ... existing iteration logic ...
}.elsewhen(done) {
  oReg := Mux(x < y, x, y)
}
```

Alternatively, the assertion could check that `oReg` will be correct **after** the clock edge (i.e., `next(oReg) === Mux(x<y, x, y)`), but the current assertion correctly expresses the intended safety property: "When done is asserted, the output is valid."
