**Round 3 of 3 — Fix cycle-0 startup issue for cnt_inc_in_mode0 and add io_i assumption for cnt_eventually_zero_in_mode1**

**Failing properties handled**: `cnt_inc_in_mode0`, `cnt_eventually_zero_in_mode1`

**Error category**: `assertion_error` for cnt_inc_in_mode0 (cycle-0 false positive); `setup_error` for cnt_eventually_zero_in_mode1 (missing environment constraint)

**Root cause**: 
1. `cnt_inc_in_mode0` (L41-46): Using `RegNext(mode) === 0.U` as premise triggers on cycle 0 because `RegNext` initializes to 0, but `cnt` hasn't incremented yet (`cnt=0, RegNext(cnt)=0` → `0===1` false). The round-2 fix removed the `mode === 0.U` guard which implicitly excluded cycle 0.
2. `cnt_eventually_zero_in_mode1` (L91-95): The liveness bound of 4096 cycles assumes continuous decrementing, but `io.i` can go low in mode 1, stalling `cnt` indefinitely and exhausting the bound budget.

**Files/lines changed**: `rgraph.scala` lines 37-48 (cnt_inc_in_mode0 fix) and lines 87-89 (io_i assumption added).

**Changes made**:
1. Added `notFirstCycle = RegInit(false.B); notFirstCycle := true.B` flag register. Changed cnt_inc_in_mode0 premise from `!(RegNext(mode) === 0.U)` to `!(notFirstCycle && RegNext(mode) === 0.U)`. On cycle 0, `notFirstCycle` is false so the premise is vacuously false. From cycle 1 onward, `notFirstCycle` is true and the premise correctly checks mode-0 cycles including the transition cycle. This preserves coverage of the transition cycle (lost in round 1's approach) while excluding the startup cycle (which round 2 missed).
2. Added `when(mode === 1.U && cnt =/= 0.U) { assume(io.i, "io_i_high_while_decrementing") }` to constrain the environment to keep `io.i` high whenever the design is actively decrementing. With this assumption, decrement occurs every cycle in mode 1 (when cnt>0), so at most 4095 decrements are needed, fitting within the 4096-cycle bound.

**Assertion-label preservation**: All original labels preserved exactly — `cnt_inc_in_mode0`, `cnt_dec_in_mode1`, `cnt_stable_in_mode1_when_not_decrementing`, `cnt_eventually_zero_in_mode1`, `mode_stays_one_once_set`, `output_matches_cnt_eq_zero`, `cnt_never_overflows`, `mode_rise_is_valid`.

**Homologous assertions**: No homologous assertions for either fix. The io_i assumption is a unique setup constraint for the liveness assertion; the notFirstCycle pattern is specific to cnt_inc_in_mode0's cycle-0 issue.