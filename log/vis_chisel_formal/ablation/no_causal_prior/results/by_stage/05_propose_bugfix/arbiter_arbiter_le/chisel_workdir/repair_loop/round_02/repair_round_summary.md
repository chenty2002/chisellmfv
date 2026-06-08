**Round 2/3 — Root-cause fix for multiple simultaneous BUSY states**

**Properties handled:** `mutex_pass_token` (primary target), `mutex_ack` (same root cause)

**Error category:** `dut_bug`

**Root cause:** The Controller allows IDLE→READY→BUSY entry whenever `is_selected && io.req`, without checking whether another controller is already in BUSY. The Arbiter advances the round-robin pointer while the current controller is still processing, enabling a second controller to also enter BUSY. When two controllers are BUSY simultaneously and both clients drop requests on the same cycle (they share identical LFSR seeds, causing synchronized behavior), both transition BUSY→IDLE and assert `pass_tokenReg=true.B` on the same clock edge, violating `mutex_pass_token` (PopCount=2). Simultaneously, both have `io.ack = ackReg && io.req = 1`, violating `mutex_ack` (PopCount=2).

**Causal path addressed by the fix:** The source change adds an `any_busy` guard in the IDLE state that prevents IDLE→READY entry when `io.any_busy` is true (any other controller is already BUSY). This breaks the causal chain at its root: the Arbiter can advance the selection pointer, but the newly-selected Controller cannot enter READY/BUSY until the currently-BUSY controller finishes and releases the bus. Since at most one controller can be BUSY at any time, only one controller transitions BUSY→IDLE per cycle, ensuring mutual exclusion for both `ack` (only one controller has ackReg=true while BUSY) and `pass_token` (only one controller sets pass_tokenReg=true on BUSY→IDLE exit).

**Changes:**
1. `arbiter_le.scala` — Controller class: Added `val busy = Output(Bool())` and `val any_busy = Input(Bool())` to IO bundle
2. `arbiter_le.scala` — Controller line ~46: `io.busy := state === ControllerState.BUSY` combinational output
3. `arbiter_le.scala` — Controller IDLE state, line ~51: Gated READY entry with `!io.any_busy`: `when(io.req && !io.any_busy)` — prevents a second controller from entering READY while another is BUSY
4. `arbiter_le.scala` — Controller IDLE `otherwise` branch, line ~53: Simplified pass_token assignment: `pass_tokenReg := !io.req && is_selected` — only asserts pass_token when selected and no request, but this is gated by `!io.any_busy` from the outer condition
5. `arbiter_le.scala` — ArbiterLE top: Added `val any_busy = controllerA.io.busy || controllerB.io.busy || controllerC.io.busy` and connected to all three controllers' `io.any_busy` inputs

**Assertion preservation:** All assertion labels unchanged, including `mutex_pass_token` (still present at line 254) and `mutex_ack` (line 249). No homologous assertions found — `mutex_pass_token` is a single top-level assertion on `PopCount`, not replicated per controller.