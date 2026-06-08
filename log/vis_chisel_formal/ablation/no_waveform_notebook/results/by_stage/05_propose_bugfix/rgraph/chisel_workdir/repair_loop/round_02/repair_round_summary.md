**Round 2 of 3 — Timing mismatch repair for mode-1 assertions and cnt_inc_in_mode0**  

**Failing properties handled**: `cnt_dec_in_mode1`, `cnt_stable_in_mode1_when_not_decrementing`, `cnt_inc_in_mode0`  

**Error category**: `assertion_error` — all failures stem from assertions using current-cycle values where previous-cycle register-based values should be used.  

**Root cause**:  
1. `cnt_dec_in_mode1` (L50-54): Used `io.i && cnt =/= 0.U` (current combinational input/state) but the decrement computed at the clock edge uses the **previous** cycle's io_i and cnt. Changed to `RegNext(io.i) && RegNext(cnt) =/= 0.U`.  
2. `cnt_stable_in_mode1_when_not_decrementing` (L59-62): Same timing mismatch — stability is determined by whether the previous cycle had `io.i=0` or `cnt=0`. Changed `!(io.i && cnt =/= 0.U)` to `!(RegNext(io.i) && RegNext(cnt) =/= 0.U)`.  
3. `cnt_inc_in_mode0` (L41-46): The premise `mode === 0.U && RegNext(mode) === 0.U` was overly restrictive — the increment `cnt := cnt + 1.U` fires whenever `mode` was 0 at the previous clock edge, regardless of current mode. Changed premise from `mode === 0.U && RegNext(mode) === 0.U` to just `RegNext(mode) === 0.U`. This correctly captures that cnt(N)=cnt(N-1)+1 whenever mode(N-1)=0, even on the cycle where mode transitions 0→1.  

**Files/lines changed**: `rgraph.scala` lines 41-46, 50-54, 59-62.  

**Assertion-label preservation**: All original labels preserved exactly — `cnt_inc_in_mode0`, `cnt_dec_in_mode1`, `cnt_stable_in_mode1_when_not_decrementing`.  

**Homologous assertions**: `cnt_dec_in_mode1` and `cnt_stable_in_mode1_when_not_decrementing` are structurally identical (both check mode-1 cnt behavior against current-cycle io.i) and were both repaired with the same pattern (RegNext(io.i) + RegNext(cnt)). `cnt_inc_in_mode0` has a different structure (no io.i dependency) and was repaired separately by fixing the premise to use `RegNext(mode) === 0.U`.  

**Expected effect**: After fixing the timing of all three safety assertions, they should be provable. The `cnt_eventually_zero_in_mode1` liveness may also become provable once the safety properties are satisfied.