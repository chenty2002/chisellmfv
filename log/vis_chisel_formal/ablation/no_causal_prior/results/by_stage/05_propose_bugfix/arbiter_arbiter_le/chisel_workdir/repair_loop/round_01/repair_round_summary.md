**Round 1/3 — Repair round for two failing properties**

**Properties handled:** `ackA_implies_reqA` (and homologous `ackB_implies_reqB`, `ackC_implies_reqC`), `mutex_pass_token`

**Error categories:** `dut_bug` (ack) and `setup_error` (pass_token initial condition)

**Root cause (ack):** Controller.io.ack := ackReg is purely registered. When Client drops req (rand_choice in HAVE_TOKEN), ack stays high for one extra cycle because the state machine clears ackReg only at the next clock edge, violating the combinational assertion !io.ack || io.req.

**Root cause (pass_token):** All three Controllers initialize pass_tokenReg = true.B, causing all three pass_tokens to be true simultaneously at reset (PopCount = 3 > 1). The Arbiter outputs Selection.X when active=false, which would cause deadlock if pass_tokenReg initial value were changed to false.B without also updating the Arbiter.

**Changes:**
1. `arbiter_le.scala` line 42: `io.ack := ackReg` → `io.ack := ackReg && io.req` — Makes ack combinational with req, so ack drops immediately when req de-asserts. This fixes all three homologous labels (ackA_implies_reqA, ackB_implies_reqB, ackC_implies_reqC) since all use the same Controller module.

2. `arbiter_le.scala` line 38: `val pass_tokenReg = RegInit(true.B)` → `val pass_tokenReg = RegInit(false.B)` — Prevents initial triple-assertion of pass_token at reset.

3. `arbiter_le.scala` line 82: `io.sel := Mux(io.active, state, Selection.X)` → `io.sel := state` — Arbiter always outputs the current round-robin state instead of gating with active. This prevents deadlock when pass_token is de-asserted: the selected controller (initially A) can assert pass_token when in IDLE with no request, bootstrapping the system.

**Expected effect:** Both ackA_implies_reqA and mutex_pass_token should be proven (no CEX). The arbiter always providing a valid selection changes behavior only when active=false, and the IDLE-state logic ensures token passing works correctly.