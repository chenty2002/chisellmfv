**Round 1/3 fix — two failing properties handled.**  
- **Property 1**: `ArbiterLE.No_acks_when_sel_is_X` — sel=X while ack is high.  
  **Root cause**: Controller enters BUSY with `pass_tokenReg=false` (set in IDLE→READY transition), all pass_tokens go false → arbiter sets sel=X, but ackReg stays true (BUSY).  
  **Fix**: Changed `io.pass_token` to a combinational OR: `pass_tokenReg || (state === BUSY && io.req)`. When BUSY with a live request, pass_token stays true → arbiter active → sel≠X.  
  **Expected effect**: Arbiter never goes to X while any controller is actively servicing (BUSY && req).

- **Property 2**: `ArbiterLE.ackA_requires_reqA` (and homologs B, C) — ack high when req drops.  
  **Root cause**: `io.ack := ackReg` (pure registered) keeps ack high one extra cycle after req drops, because the BUSY `!io.req` transition reads old req value on the same edge.  
  **Fix**: Changed to `io.ack := ackReg && io.req` — combinational AND drops ack immediately when req goes low.  
  **Expected effect**: ackA/ackB/ackC assertion holds.

**Files changed**: `arbiter_le.scala` lines 42-43 (Controller module, 2 output assignments).  
**Assertion labels preserved unchanged**: `ackA requires reqA`, `ackB requires reqB`, `ackC requires reqC`, `No acks when sel is X`.  
**Homologous assertions repaired** for ack: `ackB requires reqB` and `ackC requires reqC` are structurally identical to `ackA requires reqA` — all three are addressed by the single `io.ack := ackReg && io.req` change in the Controller module (all three instances use the same module).