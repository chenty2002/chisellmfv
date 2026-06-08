**Round 2/3 fix — four failing properties handled. Combines ack gating + combinational pass_token + register on arbiter active to avoid combinational cycle.**

**Failed build detection**: Initial fix created a combinational cycle: `controller.pass_token → active → arbiter.active → arbiter.sel → controller.sel → controller.is_selected → controller.pass_token`. Fixed by registering `arbiter.io.active := RegNext(active)`.

- **Property 1**: `ArbiterLE.At_most_one_ack_per_cycle` — dual acks from multiple BUSY controllers.  
  **Root cause**: Round-1 fix kept pass_token=1 while BUSY+req, so the arbiter kept rotating and selected other clients that also entered BUSY.  
  **Fix**: `io.ack := ackReg && io.req && is_selected` — only the currently-selected controller can ack. Since the arbiter has a single `sel` output, at most one controller is selected → at most one ack.

- **Properties 2-4**: `selX_and_no_reqX_implies_pass_tokenX` (A, B, C) — pass_token false when sel=X and !reqX.  
  **Root cause**: Registered `pass_tokenReg` lags by one cycle. When a controller transitions BUSY→IDLE or enters IDLE+selected+no_req, the register update takes effect next cycle, but the assertion checks the current cycle.  
  **Fix**: `io.pass_token := (state === IDLE && is_selected && !io.req) || (state === BUSY && io.req)` — fully combinational, computed from current state/selection/req.  
  **Cycle break**: `arbiter.io.active := RegNext(active)` registers the pass_token→active→arbiter path, eliminating the combinational feedback.

**Assertion labels preserved unchanged**: All 11 labels are unchanged.  
**Homologous assertions**: `selA_and_no_reqA_implies_pass_tokenA`, `selB_and_no_reqB_implies_pass_tokenB`, `selC_and_no_reqC_implies_pass_tokenC` are structurally identical and all fixed by the single `io.pass_token` change in the Controller module.  
**Files changed**: `arbiter_le.scala` — Controller line 44 (io.ack gated with is_selected), line 48 (combinational pass_token), ArbiterLE line 214 (RegNext on arbiter.io.active).