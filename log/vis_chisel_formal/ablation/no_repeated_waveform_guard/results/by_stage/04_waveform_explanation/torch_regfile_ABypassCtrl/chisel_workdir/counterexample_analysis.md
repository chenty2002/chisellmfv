# Counterexample Analysis Report

## 1. Verification Environment

- **Top Module**: `ABypassCtrl` (package `llmverify`)
- **Module Type**: Chisel Module with `Formal` mixin (chiselFv)
- **Key Components**:
  - Pipeline registers for A-side and B-side instruction validity (AValid_s2e, AValid_s1m, AValid_s2m, AValid_s1w, BValid_s2e, BValid_s1m, BValid_s2m)
  - Kill chain registers for boost signals
  - Bypass control logic (ASBypassLoad/ASBypassData, ATBypassLoad/ATBypassData)
  - Stall delay registers (IStall_s2, MemStall_s2)
  - Two-phase clocking (Phi1, Phi2 = ~Phi1)
- **Design Description**: The `ABypassCtrl` module manages pipeline bypass control for a dual-issue out-of-order processor. It tracks instruction validity through a 4-stage pipeline (s1e→s2e→s1m→s2m→s1w) with stall support, kill handling, and memory bypass selection for address-side (A) and base-side (B) instructions.

## 2. Violated Assertion

- **Assertion Name**: `AValid_s2e_stable_during_stall` (extracted from waveform filename `ABypassCtrl.AValid_s2e_stable_during_stall.fst`)
- **Code Snippet** (from `ABypassCtrl.scala`, line ~246):
  ```scala
  // When stalled on Phi1, AValid_s2e must hold its value (register does not update)
  assertStableWhen(io.Phi1 & io.Stall_s1, AValid_s2e,
    "AValid_s2e_stable_during_stall")
  ```
- **Natural Language Description**: When the pipeline is stalled (`Stall_s1` is asserted) during phase 1 (`Phi1` is high), the `AValid_s2e` pipeline register must retain its value (i.e., not change between consecutive clock cycles).
- **File Location**: `ABypassCtrl.scala`, lines ~245–247

## 3. Waveform Information

- **Full Waveform Path**: `verilog/extra_bench/torch_regfile_ABypassCtrl/ABypassCtrl.AValid_s2e_stable_during_stall.fst`
- **Time Range**: 0 ns → 20 ns (2 clock cycles, clock period = 10 ns)
- **Key Time Points**:

### Cycle 1 (time = 0 ns, rising clock edge)
| Signal | Value | Description |
|--------|-------|-------------|
| `ABypassCtrl.clock` | 1 | Rising edge |
| `ABypassCtrl.io_Phi1` | 1 | Phase 1 active |
| `ABypassCtrl.io_Stall_s1` | 0 | Stall NOT asserted |
| `ABypassCtrl._GEN` (= Phi1 & ~Stall_s1) | 1 | Register enable ACTIVE |
| `ABypassCtrl.io_AKill_s1e` | 0 | Not killed |
| `ABypassCtrl.io_ANoDest_s1e` | 0 | Has destination |
| `ABypassCtrl.io_Except_s1w` | 0 | No exception |
| `ABypassCtrl._AValid_s2e_T` | 0 | Register input data |
| `ABypassCtrl.AValid_s2e` | 0 | Register output (still at reset value) |
| `ABypassCtrl.AValid_s2e_stable_during_stall` | 1 | Assertion passes (condition false) |

### Cycle 2 (time = 10 ns, rising clock edge)
| Signal | Value | Description |
|--------|-------|-------------|
| `ABypassCtrl.clock` | 1 | Rising edge |
| `ABypassCtrl.io_Phi1` | 1 | Phase 1 active |
| `ABypassCtrl.io_Stall_s1` | 1 | Stall NOW asserted (transitioned 0→1) |
| `ABypassCtrl._GEN` (= Phi1 & ~Stall_s1) | 0 | Register enable INACTIVE |
| `ABypassCtrl.AValid_s2e` | **1** | Register output CHANGED (0→1 from previous cycle's update) |
| `ABypassCtrl.AValid_s2e_stable_during_stall` | **0** | Assertion FAILS |

## 4. Root Cause Analysis

### Bug Classification: **Assertion Error (assertion_error)**

The assertion `assertStableWhen(io.Phi1 & io.Stall_s1, AValid_s2e, ...)` is incorrectly conditioned.

### Detailed Explanation

The pipeline register `AValid_s2e` is defined as:

```scala
val AValid_s2e = RegInit(false.B)

when(io.Phi1 & ~io.Stall_s1) {
    AValid_s2e := ~(io.AKill_s1e | io.ANoDest_s1e | io.Except_s1w)
    // ...
}
```

The register updates on the rising clock edge when the enable condition `io.Phi1 & ~io.Stall_s1` is true.

**What happens in the counterexample:**

1. **Cycle 1 (time 0–10)**: `Stall_s1 = 0`, so the enable condition `Phi1 & ~Stall_s1 = 1`. The register input is prepared as `~(0|0|0) = 1`, and the register latches this value on the rising edge at time 10.

2. **Cycle 2 (time 10–20)**: At the exact clock edge (time 10), two things happen simultaneously:
   - The register `AValid_s2e` updates from `0` to `1` (latching the value computed when enabled in the previous cycle).
   - `Stall_s1` transitions from `0` to `1` (as injected by the formal solver).
   - The assertion condition `Phi1 & Stall_s1` becomes `1 & 1 = 1` (just became true).
   - The assertion checks: since condition is true, `AValid_s2e` must equal its previous value (`0`), but it is now `1` → **assertion fails**.

### Why This Is an Assertion Error (Not a Design Bug)

- **Design logic is correct**: The register enable `io.Phi1 & ~io.Stall_s1` correctly gates the update: when `Stall_s1=0` the register updates, when `Stall_s1=1` it holds. The register value changes at time 10 because it was enabled in the **previous** cycle (when `Stall_s1=0`), not because it updated during the stalled cycle.

- **The assertion condition is too aggressive**: The property "during a stall, the register must be stable" is conceptually correct. However, the assertion checks `io.Phi1 & io.Stall_s1` in the **same** cycle where the register may have just been updated from the **previous** non-stalled cycle. This creates a false failure when `Stall_s1` transitions from `0` to `1` simultaneously with the register update.

- **The assertion does not account for the initial transition**: A proper stability assertion should check that the register is stable only after it has already been in the stalled state for at least one full cycle. The current assertion fires at the **first** cycle where both `Phi1` and `Stall_s1` are true, without ensuring the register was already stalled in the previous cycle.

### Root Cause Location

- **File**: `ABypassCtrl.scala`, line ~246
- **Code**:
  ```scala
  assertStableWhen(io.Phi1 & io.Stall_s1, AValid_s2e,
    "AValid_s2e_stable_during_stall")
  ```
- **Issue**: The `assertStableWhen` primitive checks `cond ==> (signal === past(signal))`. When `Stall_s1` transitions from `0→1`, the condition becomes true at the same moment the register `AValid_s2e` updates from `0→1` (due to the previous non-stalled cycle), causing a false assertion failure.

### Potential Fixes

**Option A (Preferred — Relax assertion condition):** Change the assertion to only check stability after the register has already been stalled for at least one cycle:

```scala
// Use past() to ensure we only check after stall is already established
assertStableWhen(past(io.Phi1 & io.Stall_s1), AValid_s2e,
  "AValid_s2e_stable_during_stall")
```

**Option B (Add formal assumption):** Constrain `Stall_s1` such that it does not change simultaneously with the register update:

```scala
// When Phi1 is active and Stall transitions, it must remain stable
fvAssume(!(io.Phi1 & (io.Stall_s1 =/= past(io.Stall_s1))),
  "Stall_stable_during_Phi1")
```

**Option C (Alternative assertion formulation):** Use a dedicated assertion that defers stability checking by one cycle relative to the stall assertion:
```scala
// On the first cycle of stall, allow the register to have updated from previous cycle
// Then check stability from the second stalled cycle onward
assertImplies(io.Phi1 & io.Stall_s1 & past(io.Phi1 & io.Stall_s1),
  AValid_s2e === past(AValid_s2e),
  "AValid_s2e_stable_during_stall_v2")
```
