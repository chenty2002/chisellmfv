# Counterexample Analysis Report: `spinner32.load_mode_update`

## 1. Verification Environment

- **Top Module**: `spinner32` (RTL: `spinner32.scala`, 109 lines)
- **Design Under Test**: A 32-bit barrel shifter (rotator) with two operating modes:
  - **Load mode** (`splReg=0`): Loads `io.din` into internal register `inrReg`
  - **Spin mode** (`splReg=1`): Rotates `inrReg` by `io.amount` bits and stores result in `doutReg`, with feedback back to `inrReg`
- **Key Registers**: `inrReg` (input), `doutReg` (output), `splReg` (spin/load mode)
- **Key Inputs**: `io.spin` (mode select), `io.amount` (rotation amount), `io.din` (data input)
- **Key Output**: `io.dout` (= `doutReg`)

## 2. Violated Assertion

- **Assertion Name**: `load_mode_update` (from waveform filename `spinner32.load_mode_update.fst`)
- **Source Location**: `spinner32.scala`, lines 99-100
- **Code Snippet**:
  ```scala
  val dinDelayed = RegNext(io.din, 0.U(32.W))
  assertImpliesDelay(!splReg, inrReg === dinDelayed, 1, "load_mode_update")
  ```
- **Intended Property** (from comments, line 90-93):
  > "When splReg is deasserted, the *next* value of inrReg must equal the current io.din."

## 3. Waveform Information

- **Waveform File**: `verilog/extra_bench/spinner/spinner32.load_mode_update.fst`
- **Duration**: 30 ns (3 clock cycles)
- **Clock Posedges**: time 0 (initial), time 10, time 20
- **Failure Time**: `load_mode_update` signal drops from `1` to `0` at time 20 ns

### Critical Signal Timeline

| Time (ns) | Event | io.spin | io.din [31:0] | splReg | inrReg [31:0] | dinDelayed [31:0] | doutReg [31:0] | load_mode_update |
|-----------|-------|---------|---------------|--------|---------------|-------------------|----------------|------------------|
| 0 (init) | Initial values | 1 | `0x50200001` | 0 | 0 | 0 | 0 | 1 |
| 5 (negedge) | Stable values | 1 | `0x50200001` | 0 | 0 | 0 | 0 | 1 |
| 10 (posedge) | Cycle 1: registers update | 1 | `0x80000000` | **1** | **`0x50200001`** | **`0x50200001`** | 0 | 1 |
| 15 (negedge) | Stable values | 1 | `0x80000000` | 1 | `0x50200001` | `0x50200001` | 0 | 1 |
| 20 (posedge) | Cycle 2: registers update | 1 | `0x80000000` | 1 | **0** | **`0x80000000`** | `0x002A0010` | **0 (FAIL)** |
| 25 (negedge) | Stable values | 1 | `0x80000000` | 1 | 0 | `0x80000000` | `0x002A0010` | 0 |

### Key Observations

- `io.spin` = 1 at ALL times (design always in spin mode after cycle 0)
- `io.din` changes at time 10 from `0x50200001` to `0x80000000`
- `io.amount` changes at time 10 from 15 (0b01111) to 9 (0b01001)
- At time 10 (cycle 1): `inrReg` loads `io.din=0x50200001` because old `splReg=0` (initial state)
- At time 20 (cycle 2): `inrReg` gets **overwritten** to `0` because old `splReg=1` → `inrReg <= doutReg = 0`

## 4. Root Cause Analysis

### Classification: **Incorrect Assertion**

The assertion (`assertImpliesDelay(!splReg, inrReg === dinDelayed, 1, "load_mode_update")`) has a **timing mismatch**: it checks the consequent one cycle too late.

### Detailed Explanation

The `assertImpliesDelay` function with `delay=1` generates a check at clock cycle **T+1** using the antecedent sampled at cycle **T**:

```
At cycle T:  captures !splReg(T) into a shift register
At cycle T+1: checks (inrReg(T+1) === dinDelayed(T+1))   [triggered if !splReg(T) was true]
```

**The problem occurs because of the following sequence of events:**

1. **Cycle 0 (time 0 posedge)**: `splReg` initial=0 → `!splReg` = 1 (antecedent triggered)
   - The design loads: `inrReg <= io.din = 0x50200001`
   - `dinDelayed <= io.din = 0x50200001`
   - `splReg <= io.spin = 1`

2. **Cycle 1 (time 10 posedge)**: The assertion check fires because `RegNext(!splReg)` = 1 (captured from cycle 0)
   - **Before NBA update**: `inrReg = 0x50200001`, `dinDelayed = 0x50200001` → **EQUAL** ✓
   - **After NBA update** (what the formal tool sees): 
     - `inrReg` gets **overwritten** to `doutReg(0) = 0` (because `splReg` became 1)
     - `dinDelayed` gets updated to `io.din(10) = 0x80000000`
   - Post-update check: `inrReg(0) !== dinDelayed(0x80000000)` → **FALSE** ✗

3. **Cycle 2 (time 20 posedge)**: The `load_mode_update` signal drops to 0, reporting the violation detected at cycle 1.

### Why This Is an Incorrect Assertion

The DUT **correctly** implements load-mode behavior:
- When `splReg=0`: `inrReg := io.din` (loads the data input)
- When `splReg=1`: `inrReg := doutReg` (spins the previously stored value)

At cycle 0, the load occurs correctly: `inrReg` gets `io.din = 0x50200001`. However, the assertion's `delay=1` means the check is evaluated at cycle 1, by which time `inrReg` has already been overwritten by the spin-mode feedback (`doutReg = 0`).

The assertion was designed to verify the DUT's internal mux (`when(splReg) ... .otherwise ...`), but the 1-cycle delay creates a false violation because:
- The assertion uses `dinDelayed = RegNext(io.din, 0.U(32.W))` which holds the *previous* `io.din`
- But `inrReg` at cycle 1 has already been updated to `doutReg(0) = 0` (spin mode took over)
- Only by coincidence would `doutReg(0)` equal `io.din(0)` (i.e., only if the rotation amount is 0)

### Correct Property

The load-mode update should be checked with **delay=0**:

```scala
// Check that in the same cycle where !splReg, inrReg gets io.din
assertImpliesDelay(!splReg, inrReg === io.din, 0, "load_mode_update")
```

With delay=0, the check evaluates combinationally at the same clock edge where the condition `!splReg` is true. At that point:
- After NBA: `inrReg = io.din` (correctly loaded by the mux) → `inrReg === io.din` → **TRUE** ✓

Alternatively, remove `dinDelayed` entirely as it is unnecessary for this property.

### Spin Mode Assertion (Not Violated, for Reference)

The companion assertion `spin_mode_update` works correctly because:
- `doutReg` does NOT change between the trigger cycle and the check cycle in a way that breaks the property
- `doutRegDelayed = RegNext(doutReg, 0)` correctly captures the value of `doutReg` at the trigger cycle

The `load_mode_update` assertion fails because `dinDelayed` captures `io.din` at cycle T, but by cycle T+1, `io.din` may have changed AND `inrReg` may have been overwritten by spin-mode logic.

### Summary

| Aspect | Detail |
|--------|--------|
| **Bug type** | Incorrect assertion timing |
| **Root cause** | `assertImpliesDelay(!splReg, inrReg === dinDelayed, 1, ...)` uses delay=1, checking 1 cycle too late |
| **Faulty file** | `spinner32.scala`, line 100 |
| **Fix** | Change `delay=1` to `delay=0` and replace `dinDelayed` with `io.din` |
| **Evidence** | Waveform shows at cycle 1: `!splReg(0)=1`, but at cycle 2 check: `inrReg=0`, `dinDelayed=0x80000000` → mismatch |
