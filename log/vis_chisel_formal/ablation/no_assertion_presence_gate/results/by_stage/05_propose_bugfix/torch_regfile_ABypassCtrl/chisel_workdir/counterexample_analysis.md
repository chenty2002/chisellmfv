# Counterexample Analysis Report: `ABypassCtrl.AValid_s2e_holds_when_stalled`

## 1. Verification Environment

- **Top Module**: `ABypassCtrl` (in `package llmverify`, `ABypassCtrl.scala`)
- **Design Under Test**: A simple bypass control unit with dual-issue pipeline registers (A-side and B-side)
- **Key Components**:
  - Pipeline registers: `AValid_s2e`, `AValid_s1m`, `AValid_s2m`, `AValid_s1w` (and B-side counterparts)
  - Boost logic registers: `ABoosted_s2e`, `ABoostValid_s2e`, etc.
  - Stall control: `Stall_s1`, `IStall_s1`, `MemStall_s1` inputs
- **Pipeline Update Rule**: 
  - On `io.Phi1` when NOT stalled (`~io.Stall_s1`): update s2e/s2m stage registers (line 119)
  - On `Phi2` (= `~io.Phi1`): update s1m/s1w stage registers (line 130)

## 2. Violated Assertion

- **Full Assertion Name**: `AValid_s2e_holds_when_stalled` (extracted from waveform filename)
- **Source Code Location**: `ABypassCtrl.scala`, **line 209**

```scala
// Lines 205-209:
// ---------------------------------------------------------------------
// SAFETY 4: Stall preserves pipeline register state
// ---------------------------------------------------------------------
// When Stall_s1 is high on Phi1, all pipeline registers should hold.
assertStableWhen(io.Phi1 && io.Stall_s1, AValid_s2e.asUInt, "AValid_s2e_holds_when_stalled")
```

- **Property Description**: When `io.Phi1` is high and `io.Stall_s1` is asserted (pipeline stalled), the pipeline register `AValid_s2e` should remain stable (hold its value).

## 3. Waveform Information

- **Waveform File**: `verilog/extra_bench/torch_regfile_ABypassCtrl/ABypassCtrl.AValid_s2e_holds_when_stalled.fst`
- **Time Range**: 0 ns to 20 ns (2 clock cycles)
- **Clock Period**: 10 ns (positive edge at times 0, 10)

### Key Signal Values

| Signal | Time 0 (Cycle 0) | Time 10 (Cycle 1) |
|---|---|---|
| `io.Phi1` | 1 | 1 |
| `io.Stall_s1` | **0** (NOT stalled) | **1** (STALLED) |
| `AValid_s2e` | **0** (initial) | **1** (updated) |
| `io.AValid_s1e` | 1 | 0 |
| `io.AKill_s1e` | 0 | 1 |
| `io.ANoDest_s1e` | 0 | 1 |
| `io.Except_s1w` | 0 | 1 |
| `_AValid_s2e_T` | 0 | 1 |
| `AValid_s2e_holds_when_stalled` | **1** (pass) | **0** (FAIL) |

### Failure Time Point

The assertion fails at **time 10 ns** (cycle 1). The assertion check signal transitions from 1 (pass) at time 0 to 0 (fail) at time 10.

## 4. Root Cause Analysis

### Root Cause Category: **Incorrect Assertion** (`assertion_error`)

The assertion is incorrectly written — it fires a false violation because it fails to account for legitimate pipeline register updates that occurred in the immediately preceding non-stalled cycle.

### Detailed Explanation

**Cycle 0 (time 0 ns): Not stalled**
- `io.Stall_s1 = 0`, `io.Phi1 = 1`
- The register update logic on line 119 fires:
  ```scala
  when(io.Phi1 & ~io.Stall_s1) {  // 1 & ~0 = 1 → enabled
      AValid_s2e := ~(io.AKill_s1e | io.ANoDest_s1e | io.Except_s1w)
  }
  ```
- Inputs: `io.AKill_s1e=0`, `io.ANoDest_s1e=0`, `io.Except_s1w=0`
- Computation: `~(0|0|0) = 1`
- **`AValid_s2e` is correctly updated from 0 (initial) to 1** at the beginning of cycle 1
- Assertion condition `io.Phi1 && io.Stall_s1 = 1 && 0 = 0` → **not checked** (passes vacuously)

**Cycle 1 (time 10 ns): Stalled**
- `io.Stall_s1 = 1`, `io.Phi1 = 1`
- The register update logic on line 119 does NOT fire:
  ```scala
  when(io.Phi1 & ~io.Stall_s1) {  // 1 & ~1 = 0 → disabled
  ```
- **`AValid_s2e` correctly holds its value at 1** (no update occurs)
- Assertion condition `io.Phi1 && io.Stall_s1 = 1 && 1 = 1` → **checked**
- `assertStableWhen` compares `AValid_s2e` at cycle 1 (value=1) vs cycle 0 (value=0)
- **1 ≠ 0 → FALSE VIOLATION triggered!**

### Why This is an Assertion Bug (Not a Design Bug)

The design is **correct**: 
- In cycle 0 (not stalled), the register was legitimately updated from 0 to 1 based on valid inputs
- In cycle 1 (stalled), the register correctly holds its value of 1

The `assertStableWhen` property operates with a single-cycle lookback semantics: whenever the condition is true, it checks that `signal[current_cycle] == signal[previous_cycle]`. This fails on the FIRST cycle where stalls become active, because the register's value changed in the previous non-stalled cycle.

The correct property should only check stability across **consecutive** stalled cycles, i.e., require that the stall condition was also true in the previous cycle. This can be expressed as:

```scala
// Fix: Add Past() qualifier to only check stability across consecutive stalled cycles
when(io.Phi1 && io.Stall_s1 && Past(io.Phi1 && io.Stall_s1)) {
    assert(Stable(AValid_s2e.asUInt))
}
```

Or equivalently, the `assertStableWhen` should incorporate a two-cycle lookback for the condition.

### Buggy Code Location

- **File**: `ABypassCtrl.scala`
- **Line**: 209
- **Code**: `assertStableWhen(io.Phi1 && io.Stall_s1, AValid_s2e.asUInt, "AValid_s2e_holds_when_stalled")`
- **Issue**: The assertion condition `io.Phi1 && io.Stall_s1` does not include a `Past()` qualifier to exclude the first stalled cycle after a non-stalled cycle, causing a false violation when a pipeline register was legitimately updated in the preceding non-stalled cycle.

### Impact

The same erroneous pattern appears on lines 210-218 for all other `assertStableWhen` checks (for `AValid_s1m`, `AValid_s2m`, `AValid_s1w`, `BValid_s2e`, `BValid_s1m`, `BValid_s2m`, `ABoosted_s2e`, `ABoostValid_s2e`), meaning all of these assertions would also fire false violations under the same scenario.
