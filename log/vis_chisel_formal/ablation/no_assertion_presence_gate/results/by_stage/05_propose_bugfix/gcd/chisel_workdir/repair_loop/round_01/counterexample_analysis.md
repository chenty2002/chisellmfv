# Counterexample Analysis Report: `output_stable_when_idle`

## 1. Verification Environment

- **Top Module**: `Gcd` (parametric, N=8, logN=3)
- **Key Components**:
  - `Gcd` module: Binary GCD computation engine with registers `x`, `y`, `lsb`, `busyReg`, `oReg`
  - Internal wires: `xy_lsb`, `diff`, `done`, `load`
- **Design Under Test**: A sequential binary GCD accelerator that computes GCD(a,b) using a shift-subtract algorithm. Inputs arrive via `io.start`, `io.a`, `io.b`. Output is available on `io.o`, busy status on `io.busy`.

## 2. Violated Assertion

- **Assertion Name**: `output_stable_when_idle`
- **Waveform File**: `Gcd.output_stable_when_idle.fst`
- **Property**: "Output should not change while the module is idle"

**Source Code (gcd.scala, line 122)**:
```scala
assertStableWhen(!busyReg, io.o, "output_stable_when_idle")
```

**Natural Language Description**: When the `Gcd` module is not busy (`busyReg == 0`), the output signal `io.o` must remain stable (must not change value from cycle to cycle).

## 3. Waveform Information

- **Waveform File**: `verilog/extra_bench/gcd/Gcd.output_stable_when_idle.fst`
- **Time Range**: 0 ns → 30 ns (3 clock cycles, each 10 ns period)
- **Clock**: Rising edges at 0 ns, 10 ns, 20 ns; falling edges at 5 ns, 15 ns, 25 ns

### Key Time Points

| Time (ns) | Cycle | Clock | busyReg | io_o | oReg | done | load | Event |
|-----------|-------|-------|---------|------|------|------|------|-------|
| 0 | 0 | Rising | 0 | 0x00 | 0x00 | 0 | 1 | Load triggered: x←0xFF, y←0xFF, busyReg→1 |
| 5 | 0 | Falling | 0 | 0x00 | 0x00 | 0 | 1 | — |
| 10 | 1 | Rising | 1 | 0x00 | 0x00 | 1 | 0 | done=1: oReg←0xFF scheduled, busyReg→0 scheduled |
| 15 | 1 | Falling | 1 | 0x00 | 0x00 | 1 | 0 | — |
| **20** | **2** | **Rising** | **0** | **0xFF** | **0xFF** | **0** | **1** | **ASSERTION FAILS: busyReg→0 and io_o changes simultaneously** |
| 25 | 2 | Falling | 0 | 0xFF | 0xFF | 0 | 1 | — |

### Failure Point (t = 20 ns)
At the rising edge of cycle 2:
- `Gcd.output_stable_when_idle` transitions from `1` to `0` (assertion fails)
- `Gcd.busyReg` transitions from `1` to `0` (module becomes idle)
- `Gcd.io_o` transitions from `0x00` to `0xFF` (output changes)
- `Gcd.oReg` transitions from `0x00` to `0xFF` (output register updates)

## 4. Root Cause Analysis

### Bug Location
- **File**: `gcd.scala`
- **Lines**: 96-98 (data path `elsewhen(done)` block)
- **Module**: `Gcd`

### Bug Description

The root cause is a **design timing issue (DUT bug)** in how the GCD result is registered relative to the idle signal.

The computation flow is:

1. **Cycle 0 (t=0)**: Inputs `io.a=0xFF`, `io.b=0xFF` are loaded. Since a=b, the GCD computation is trivially complete.
   
2. **Cycle 1 (t=10)**: The signal `done` fires because `x === y` (both are 0xFF). Two assignments are scheduled for the next clock edge:
   - **`oReg` is assigned** the result: `oReg := Mux(x<y, x, y)` = `0xFF` (line 97)
   - **`busyReg` is cleared**: `busyReg := false.B` via controller logic (lines 103-107)

3. **Cycle 2 (t=20)**: Both registers update simultaneously:
   - `oReg` changes from `0x00` → `0xFF` (output changes)
   - `busyReg` changes from `1` → `0` (module signals idle)
   - The assertion `assertStableWhen(!busyReg, io.o, ...)` checks stability of `io.o` when `!busyReg` is true. Since `!busyReg` just became `true` **and** `io.o` also changed at this exact cycle, the assertion fires.

### Root Cause Code (lines 96-98)
```scala
.elsewhen(done) {
    oReg := Mux(x < y, x, y)
}
```

The problem is that `oReg` is updated in the `.elsewhen(done)` block. Since `done` also controls `busyReg` going low (via the controller at lines 103-107), the output register updates **on the same clock edge** where the module transitions from busy to idle. This violates the "output stable when idle" property.

### Evidence from Waveform

| Signal | Cycle 0 (t=0) | Cycle 1 (t=10) | Cycle 2 (t=20) |
|--------|--------------|---------------|---------------|
| busyReg | 0 | 1 | **0** ← idle start |
| oReg | 0x00 | 0x00 | **0xFF** ← changes here |
| io_o | 0x00 | 0x00 | **0xFF** ← changes here |
| done | 0 | 1 | 0 |

At t=20, the module transitions to idle simultaneously with the output changing, confirming the timing mismatch.

### Why This Is a DUT Bug

The design intends that when the module signals idle (`busyReg=0`), the output should already be stable and valid. However, the current implementation schedules both the output update AND the idle transition for the same clock cycle. The proper fix would be one of:

1. **Option A**: Update `oReg` combinationally when `done` fires, so the output is valid one cycle before `busyReg` goes low. For example, compute `io.o` as a wire: `io.o := Mux(done, Mux(x < y, x, y), oReg)`.

2. **Option B**: Keep `busyReg` high for an extra cycle after `done`, allowing the output to settle before the module signals idle. This would require a one-cycle delay between `done` and `busyReg` going low.

3. **Option C**: Make `io.o` a direct combinational read of the result (bypassing `oReg` entirely), so the output tracks `x` and `y` regardless of `busyReg` state.

The simplest and most robust fix is **Option B**: introduce a one-cycle delay between the `done` assertion and the `busyReg` deassertion, ensuring the output is stable for at least one full cycle before the module becomes idle.

### Error Classification
- **Error Type**: `dut_bug` — The design has a genuine timing issue where the output changes on the same cycle the module signals idle, violating the output-stability-when-idle property.
