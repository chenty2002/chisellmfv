**Round 1 fix: repair 3 failing assertions in lock.scala**  
- **Error type**: `assertion_error` — the chiselFv library helpers `assertStableWhen` and `assertImpliesDelay` generate incorrect Verilog assertions for this design  
- **Target property**: `lock.position_stable_when_no_net_movement` (and homologues `lock.up_increments_position_by_1`, `lock.down_decrements_position_by_1`)  
- **Root cause**:  
  1. `assertStableWhen` uses a delay register `r` that captures `position` at the same posedge where `position` updates, creating a one-cycle mismatch — position changes due to previous-cycle inputs but assertion checks current-cycle stability condition.  
  2. `assertImpliesDelay` completely drops the antecedent in generated Verilog, producing unconditional assertions that require position to increment/decrement every cycle.  
- **Fix**: Replaced all three chiselFv helper calls with direct `assert(antecedent || consequent)` using explicit `RegNext` temporal sampling.  
  - `position_stable_when_no_net_movement`: `assert(RegNext(io.up ^ io.down) || RegNext(position) === position)` — snapshots the net-movement signal from previous cycle so the stability check correctly allows position changes caused by earlier inputs.  
  - `up_increments_position_by_1`: `assert(!RegNext(io.up && !io.down) || position === (RegNext(position) + 1.U))` — properly delays the antecedent by 1 cycle to match when position actually updates.  
  - `down_decrements_position_by_1`: same pattern with `io.down && !io.up` antecedent.  
- **Homologous assertions**: All three failing assertions (position_stable_when_no_net_movement, up_increments_position_by_1, down_decrements_position_by_1) were structurally identical in that they used chiselFv library helpers that generate incorrect Verilog; all three were replaced with equivalent direct `assert()` patterns.  
- **Label preservation**: All original assertion labels preserved exactly.