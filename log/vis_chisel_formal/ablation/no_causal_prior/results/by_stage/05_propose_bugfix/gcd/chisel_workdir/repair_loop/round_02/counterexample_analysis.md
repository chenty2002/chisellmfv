# Counterexample Analysis Report: `output_correct_on_done`

## 1. Verification Environment

- **Top Module**: `Gcd` (from `llmverify` package, in `gcd.scala`)
- **Properties Verified**: The `Gcd` module implements a binary GCD algorithm for unsigned N-bit numbers (N=8).
- **Key Components**:
  - **Registers**: `x`, `y` (the two operands), `busyReg` (busy flag), `oReg` (output register), `lsb` (bit position counter)
  - **Combinational Logic**: `xy_lsb` (concatenation of LSBs of x and y), `diff` (absolute difference), `done` (GCD found), `load` (new inputs)
  - **Controller**: FSM governing loading, computation, and done states
- **Assertion Filename**: `Gcd.output_correct_on_done.fst`

## 2. Violated Assertion

- **Full Assertion Name**: `output_correct_on_done`
- **Code Snippet** (file `gcd.scala`, line ~128):
  ```scala
  // Safety: When done is asserted, the output register holds min(x, y) which is the GCD result.
  assertImplies(done, oReg === Mux(x < y, x, y), "output_correct_on_done")
  ```
- **Natural Language Property**: "When the `done` signal is asserted, the output register `oReg` must contain the minimum of `x` and `y`, which is the GCD result (since when `x === y`, the GCD is their common value, and when one is zero, the GCD is the other)."
- **File Location**: `gcd.scala`, line 128

## 3. Waveform Information

- **Full Path to Waveform File**: `verilog/extra_bench/gcd/Gcd.output_correct_on_done.fst`
- **Waveform Duration**: 4 cycles (40 ns), clock rising edges at 0, 10, 20, 30 ns
- **Key Time Points** and critical signal values:

| Time (ns) | Signal | Value | Description |
|-----------|--------|-------|-------------|
| **0** (Cycle 1) | `io.start`, `load` | 1, 1 | New inputs loaded: io.a=0x72 (114), io.b=0xAB (171) |
| 0 | `x`, `y` | 0x72, 0xAB | Registers loaded with input values |
| 0 | `oReg` | 0x72 (114) | Pre-loaded with min(0x72, 0xAB)=0x72 |
| 0 | `busyReg` | 0 → 1 | Becomes busy |
| 0 | `done` | 0 | Computation in progress |
| **10** (Cycle 2) | `x` | 0x72 (114) | Unchanged |
| 10 | `y` | 0xAB (171) | Unchanged |
| 10 | `xy_lsb` | 01 (2'b01) | lsb=0: x(0)=0, y(0)=1 → shift x right |
| 10 | `load`, `done` | 0, 0 | Busy, computing |
| **20** (Cycle 3) | `x` | 0x39 (57) | x was shifted right by 1 |
| 20 | `y` | 0xAB (171) | Unchanged |
| 20 | `xy_lsb` | 11 (2'b11) | Both LSBs=1: compute diff, y = diff>>1 |
| 20 | `load`, `done` | 0, 0 | Still computing |
| **30** (Cycle 4) | **`done`** | **1** | **Assertion fires** |
| 30 | `x` | 0x39 (57) | **GCD result (57)** |
| 30 | `y` | 0x39 (57) | **GCD result (57)** |
| 30 | **`oReg`** | **0x72 (114)** | **Still holds stale pre-loaded value!** |
| 30 | **`output_correct_on_done`** | **0** | **ASSERTION FAILS** |
| 30 | `Mux(x<y, x, y)` | 57 ≠ 114 | Expected: oReg=57, Actual: oReg=114 |
| 40 (Cycle 5) | `oReg` | 0x72 (114) | Still stale — `.elsewhen(done)` update hasn't propagated |
| 40 | `done` | 1 | Still asserted |
| 40 | `busyReg` | 1 | Still busy |

## 4. Root Cause Analysis

### Buggy Code Location

**File**: `gcd.scala`, lines 91–105 (the `when`/`.elsewhen` chain for datapath logic)

### Description of the Bug

The design has a **timing mismatch** between when the `done` signal fires and when `oReg` is updated to the correct GCD value.

**The `load` phase (line 94-96):**
```scala
when(load) {
    x := io.a
    y := io.b
    lsb := 0.U
    // Pre-compute oReg when loading
    oReg := Mux(io.a < io.b, io.a, io.b)  // oReg = min(a, b)
}
```

When `load` fires, `oReg` is pre-loaded with `min(io.a, io.b)`. This works correctly for the trivial case where GCD is immediately known (a==b, a==0, or b==0), because `done` will fire on the **next** cycle and `oReg` already holds the right value.

**The `done` phase (lines 101-103):**
```scala
}.elsewhen(done) {
    oReg := Mux(x < y, x, y)  // Update oReg to the computed GCD
}
```

When `done` fires (after multi-cycle computation), the `.elsewhen(done)` block schedules `oReg := Mux(x < y, x, y)`. However, because `oReg` is a register (line 28: `val oReg = RegInit(0.U(N.W))`), this update takes effect at the **next clock edge**, not immediately.

**The assertion (line 128):**
```scala
assertImplies(done, oReg === Mux(x < y, x, y), "output_correct_on_done")
```

The assertion checks **combinatorially** that when `done=1`, `oReg` already equals `min(x,y)`. But in the multi-cycle case, `oReg` still holds the stale pre-loaded value `min(io.a, io.b)` when `done` first fires, because the `.elsewhen(done)` block updates `oReg` for the *next* cycle.

### Evidence from Waveform

| Cycle | Time | x | y | oReg | done | oReg should be | oReg is |
|-------|------|---|---|------|------|----------------|---------|
| 1 | 0 | 114 | 171 | 114 | 0 | — | 114 (min(a,b), correct pre-load) |
| 2 | 10 | 114 | 171 | 114 | 0 | — | 114 |
| 3 | 20 | 57 | 171 | 114 | 0 | — | 114 |
| **4** | **30** | **57** | **57** | **114** | **1** | **57** | **114 ❌** |
| 5 | 40 | 57 | 57 | 114 | 1 | 57 | 114 ❌ (still stale) |

At cycle 4 (time=30), `done=1` and `x=y=57`. The assertion requires `oReg === min(57,57) = 57`, but `oReg` is still 114 — the value from the initial load in cycle 1. The `.elsewhen(done)` block does schedule `oReg := 57`, but this takes effect at cycle 5 (time=40). Even at cycle 5, the waveform shows `oReg=114`, indicating the register update may not have propagated within the simulation window.

### Root Cause Category: **Bug in the Original Design (dut_bug)**

This is a genuine design bug. The `oReg` register is updated one cycle too late relative to the `done` signal for non-trivial GCD computations. The pre-loading strategy (`oReg := min(io.a, io.b)` on load) only works for trivial cases where GCD is immediately known. For multi-cycle GCD computations, `oReg` still contains the stale pre-loaded value when `done` fires.

### Possible Fixes

1. **Combinational output**: Replace the register read with a wire that selects between `oReg` (for idle/busy states) and `min(x,y)` (for done state):
   ```scala
   io.o := Mux(done, Mux(x < y, x, y), oReg)
   ```
   This avoids the one-cycle lag entirely.

2. **Update oReg during computation**: Track the current running GCD value in `oReg` throughout computation, so when `done` fires, `oReg` already holds the correct value.
