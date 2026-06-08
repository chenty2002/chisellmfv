Repair round 2/3 — Fixes 6 failing assertions by replacing assertStableWhen with per-cycle RegNext-based checks and reverting the DUT prediction gating.

**Root cause (assertion_error)**: The Chisel `assertStableWhen` primitive samples its reference once when the condition first becomes true, but never re-samples after a false→true transition. This causes failures when a bank is selected (condition false), updated, then de-selected (condition true again) — the stale pre-update reference mismatches the legitimate post-update value.

**Fixes applied:**

1. **DUT prediction gating (lines 40–44)**: Reverted round-1 change that removed `when (!io.stall)` guard. Prediction now only updates when not stalled, naturally satisfying stall stability without requiring `assertStableWhen` workarounds.

2. **`stall_preserves_prediction` (lines 70–73)**: Replaced `assertStableWhen(io.stall, prediction.asUInt, ...)` with `when (io.stall) { fvAssert(prediction.asUInt === RegNext(prediction.asUInt), ...) }`. Per-cycle RegNext comparison handles any number of stall→non-stall→stall transitions correctly.

3. **`bank0–3_stable_except_when_selected` (lines 98–112)**: Replaced `assertStableWhen(!RegNext(io.update && io.buffer_offset === N.U), state_bankN(io.buffer_addr).asUInt, ...)` with `when (!bankN_selected) { fvAssert(state_bankN(io.buffer_addr) === RegNext(state_bankN(io.buffer_addr)), ...) }`. Each cycle independently checks that the bank entry at `io.buffer_addr` matches its immediately preceding value when the bank is not being updated, naturally handling non-consecutive stable periods.

All assertion labels preserved exactly. No assertions deleted, disabled, or weakened.