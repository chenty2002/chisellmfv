**Round 3/3 — Fix io.busy to include READY state, preventing simultaneous BUSY entries**

**Properties handled:** `mutex_pass_token` (primary target), `mutex_ack` (same root cause)

**Error category:** `dut_bug`

**Root cause:** `io.busy := state === ControllerState.BUSY` excludes the READY state, so when ControllerA transitions IDLE→READY at time 10 posedge, `io.busy` stays false and `any_busy` stays false. At time 20 posedge, ControllerA transitions READY→BUSY, but ControllerB (now selected because the arbiter rotated at time 10) also sees `any_busy=false` and enters READY. By time 30, both controllers are in BUSY simultaneously. When both clients drop their requests on the same cycle (synchronized LFSRs), both transition BUSY→IDLE asserting `pass_tokenReg=true.B` simultaneously (violating mutex_pass_token) and both have `io.ack=true` (violating mutex_ack).

**Causal path addressed:** The source change widens `io.busy` to cover READY as well as BUSY (`state =/= ControllerState.IDLE`). This is the causal fix because at time 10 when ControllerA enters READY, `io.busy` goes high immediately (combinational), making `any_busy=true` before time 20. At time 20 posedge, ControllerB checks `!io.any_busy` which is now false, preventing ControllerB from entering READY. With at most one controller in the pipeline, simultaneous BUSY→IDLE transitions are impossible, ensuring mutual exclusion for both `ack` (only one BUSY controller has `ackReg=true`) and `pass_token` (only one controller sets `pass_tokenReg=true` on BUSY→IDLE exit).

**Changes:** `arbiter_le.scala` line 48: `io.busy := state === ControllerState.BUSY` → `io.busy := state =/= ControllerState.IDLE`

**Assertion preservation:** All assertion labels unchanged (`mutex_ack`, `mutex_pass_token`, etc.). No homologous assertions — this is a single Controller module whose `io.busy` definition affects all three instances identically.