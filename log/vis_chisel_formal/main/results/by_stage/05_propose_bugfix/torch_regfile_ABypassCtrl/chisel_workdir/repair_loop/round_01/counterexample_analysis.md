# Counterexample Analysis Report: `ABypassCtrl.AValid_s2e_stable_during_stall`

## 1. Verification Environment

- **Top Module**: `ABypassCtrl` (from `ABypassCtrl.scala`, package `llmverify`)
- **Module Structure**: A pipelined register-file bypass controller with two instruction pipelines (A-side and B-side). It manages valid-signal propagation, kill/exception clearing, boost tracking, stall handling, and the associated control logic.
- **Key Components**:
  - Pipeline registers: `AValid_s2e`, `AValid_s1m`, `AValid_s2m`, `AValid_s1w` (and B-side equivalents)
  - Kill-chain logic for clearing valid signals (`AKill_s1e`, `ANoDest_s1e`, `Except_s1w`)
  - Stall qualification using `io.Phi1` and `io.Stall_s1`
- **Assumption-Free Formal**: The inputs are free-running (no constraints), meaning `io_Stall_s1` can transition arbitrarily.

## 2. Violated Assertion

- **Full Assertion Name** (from waveform filename): `ABypassCtrl.AValid_s2e_stable_during_stall`
- **File Location**: `ABypassCtrl.scala`, line 207
- **Code Snippet**:
  ```scala
  // Safety 6: Pipeline registers hold their values during stalls
  // (Stall_s1 being high prevents the Phi1-gated pipeline stage from being overwritten)
  assertStableWhen(io.Stall_s1, AValid_s2e, "AValid_s2e_stable_during_stall")
  ```
- **Natural Language Property**: "When the stall signal `io.Stall_s1` is asserted, the pipeline register `AValid_s2e` must remain stable (i.e., the same value as in the previous cycle)."

## 3. Waveform Information

- **Waveform File**: `verilog/extra_bench/torch_regfile_ABypassCtrl/ABypassCtrl.AValid_s2e_stable_during_stall.fst`
- **Duration**: 2 clock cycles (0–20 ns), clock period = 10 ns
- **Key Time Points**:

| Time (ns) | Clock Edge | Signal | Value |
|-----------|-----------|--------|-------|
| t=0       | Posedge   | `io_Phi1` | 1 |
| t=0       | Posedge   | `io_Stall_s1` | 0 |
| t=0       | Posedge   | `AValid_s2e` | 0 |
| t=0       | Posedge   | `r` (delay register) | 0 |
| t=0       | Posedge   | `io_AValid_s1e` | 1 |
| t=10      | Posedge   | `io_Phi1` | 1 |
| t=10      | Posedge   | `io_Stall_s1` | **1** ⬆ |
| t=10      | Posedge   | `AValid_s2e` | **1** |
| t=10      | Posedge   | `r` (delay register) | **0** |
| t=10      | Posedge   | `io_AValid_s1e` | 1 |

- **Assertion Evaluates at t=10**: `~io_Stall_s1 | AValid_s2e == r` → `~1 | (1 == 0)` → **FALSE** → **ASSERTION FAILURE**

## 4. Root Cause Analysis

### Category: **Incorrect Assertion** (assertion error)

The assertion is a false-positive / spurious failure. The DUT's logic is correct; the assertion `assertStableWhen` makes an incorrect assumption about signal stability at the boundary of a stall transition.

### How the DUT Works

The pipeline register `AValid_s2e` is conditionally updated only on non-stalled cycles:

```scala
// ABypassCtrl.scala, lines 119-120
when(io.Phi1 & ~io.Stall_s1) {
  AValid_s2e := ~(io.AKill_s1e | io.ANoDest_s1e | io.Except_s1w)
}
```

When `io.Stall_s1` is high, the condition `io.Phi1 & ~io.Stall_s1` is false, so `AValid_s2e` retains its value — this is correct stall behavior.

### How the Assertion Works

The ChiselFv `assertStableWhen(en, sig, name)` macro expands to (from generated `ABypassCtrl.sv`):

```verilog
reg r;  // 1-cycle delayed copy of AValid_s2e
AValid_s2e_stable_during_stall:
  assert property (@(posedge clock) disable iff (~hasBeenReset)
                   ~io_Stall_s1 | AValid_s2e == r);

always @(posedge clock) begin
  r <= AValid_s2e;  // captures pre-update value every cycle
end
```

The assertion states: "At every posedge clock, either `io_Stall_s1` is deasserted, OR `AValid_s2e` equals its value from the previous cycle (captured in `r`)."

### The Flaw

The counterexample demonstrates a **transition-edge false positive**:

1. **Cycle 0** (t=0, `io_Stall_s1`=0):
   - `AValid_s2e` = 0 (reset value, pre-update)
   - `r` is updated: `r <= AValid_s2e` → **r = 0**
   - Since `io_Phi1 & ~io_Stall_s1` is true, `AValid_s2e` is **updated to 1** (the computed `~(AKill|ANoDest|Except)` value)

2. **Cycle 1** (t=10, `io_Stall_s1`=1):
   - `AValid_s2e` = 1 (correctly retained from the Cycle 0 update)
   - `r` = 0 (captured the **pre-update** value of `AValid_s2e` from Cycle 0)
   - Assertion checks: stall=1, but AValid_s2e(1) ≠ r(0) → **FAIL**

**The key insight**: The register `r` captures `AValid_s2e` at the posedge **before** the combinational update logic takes effect. When `AValid_s2e` legitimately transitions from 0→1 in a non-stalled cycle (Cycle 0), `r` holds the old value (0). Then when stall is first asserted in the next cycle (Cycle 1), the assertion compares the new legitimate value (1) against the stale reference (0) and incorrectly flags a violation.

**The DUT is actually correct**: During the stall (Cycle 1), `AValid_s2e` stays at 1 — it does not change. The assertion mechanism is simply checking against the wrong reference value (the pre-update value rather than the intended "stable during stall" value).

### Why This Is Not a DUT Bug

- The pipeline correctly computes `AValid_s2e := 1` in Cycle 0 (non-stalled).
- In Cycle 1 (stalled), `AValid_s2e` correctly retains its value (does not change under stall).
- All other assertions pass, including `AValid_s2e_tracks_AValid_s1e` (line 195), which confirms the pipeline tracking logic is correct.

### Why This Is Not a Setup Issue

- No unrealistic input values are provided. `io_Stall_s1` transitioning from 0→1 is a normal operating scenario.
- All inputs are generic (no specific constraints), which is expected for free-running formal verification.

### Expected Fix

The `assertStableWhen` mechanism should either:
1. **Skip the check on the first cycle of stall assertion** — only verify stability when stall has been asserted for at least 2 consecutive cycles (i.e., `past(io_Stall_s1)` is also true).
2. **Use a "freeze-at-start-of-stall" register** that captures `AValid_s2e` only when stall goes from deasserted → asserted, and compares against that frozen value throughout the stall duration.

Alternatively, the property should be rewritten as an LTL assertion that accounts for the stall-entry transition, e.g.:
```
assert property (@(posedge clock)
  $rose(io_Stall_s1) |-> ##0 AValid_s2e == $past(AValid_s2e) ||  // allow change at stall entry
  $stable(io_Stall_s1) |-> AValid_s2e == $past(AValid_s2e));     // stable during stall
```

### Buggy Code Location

- **File**: `ABypassCtrl.scala`, line 207
- **Code**: `assertStableWhen(io.Stall_s1, AValid_s2e, "AValid_s2e_stable_during_stall")`
- **Nature**: The assertion `assertStableWhen` produces a false counterexample on the first cycle of a stall when `AValid_s2e` was legitimately updated in the immediately preceding non-stalled cycle.

### Evidence Summary

| Signal | t=0 (Cycle 0, no stall) | t=10 (Cycle 1, stall) |
|--------|------------------------|----------------------|
| `io_Stall_s1` | 0 | 1 |
| `AValid_s2e` | 0 → **1** (updated) | 1 (retained) |
| `r` (delay) | 0 (captured pre-update) | 0 (stale) |
| Assertion result | Vacuously true (stall=0) | **FAIL** (1 ≠ 0) |
