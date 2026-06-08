**Round 3/3 fix — 4 remaining failing properties handled.**

**Bug 1 (deadlock — addresses reqA/B/C liveness assertions):**
- **Root cause**: `arbiter.io.active := RegNext(active)` initializes to `false.B` (default). At reset, all controllers have `pass_token=false` (no controller is selected because arbiter sel=X), so `active=0` permanently. The arbiter never starts cycling, all requests go unacknowledged forever.
- **Fix**: `arbiter.io.active := RegNext(active, true.B)` — initializes the arbiter's `active` register to `true.B` at reset, providing the bootstrap pulse needed for the arbiter to begin cycling through Selection.A→B→C→A. Once cycling, controllers that are selected-without-req assert `pass_token` (combinational), sustaining `active=true` naturally.
- **DUT causality**: The causal path is `RegNext(active)=false → arbiter.io.active=false → arbiter.io.sel=X → no controller.is_selected=true → all pass_token=0 → active=0`. Setting the init value to `true.B` breaks this by making `arbiter.io.active=true` on the first cycle, allowing `sel` to be a valid client ID and letting the system bootstrap.

**Bug 2 (selC pass_token edge case — addresses selC_and_no_reqC_implies_pass_tokenC):**
- **Root cause**: When controllerC is in BUSY state and `io.reqC` drops, the combinational `pass_tokenC = (state===BUSY && io.req)` evaluates to `(true && false)=false`. But during this cycle `sel=C` (arbiter hasn't rotated yet) and `!reqC` is true, so the assertion expects `pass_tokenC=true`. The controller transitions to IDLE on the next clock, but the combinational output is already wrong for this cycle.
- **Fix**: Change `io.pass_token` to `(state===IDLE && is_selected && !io.req) || (state===BUSY)`. When in BUSY, always assert pass_token regardless of io.req, so the transition cycle (BUSY with !req) correctly passes the token.
- **DUT causality**: The causal path is `state=BUSY && !io.req → pass_token=false → active may drop → arbiter may stall`. Asserting pass_token in any BUSY state ensures the arbiter keeps cycling through the transition cycle.

**Assertion labels preserved unchanged**: All 11 labels are unchanged.  
**Homologous assertions**: `selA_and_no_reqA_implies_pass_tokenA`, `selB_and_no_reqB_implies_pass_tokenB`, `selC_and_no_reqC_implies_pass_tokenC` are structurally identical — the single BUSY-pass change in the Controller module fixes all three (the selC CEX was the first exposed).  
**Files changed**: `arbiter_le.scala` — Controller line 48 (pass_token adds BUSY unconditionally), ArbiterLE line 220 (RegNext init value `true.B`).