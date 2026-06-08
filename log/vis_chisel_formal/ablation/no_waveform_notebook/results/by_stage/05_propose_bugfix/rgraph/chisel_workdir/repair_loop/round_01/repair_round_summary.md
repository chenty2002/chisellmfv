**Round 1 of 3 — Assertion repair round for 4 safety assertions**

**Failing properties handled**: `mode_stays_one_once_set`, `cnt_inc_in_mode0`, `cnt_dec_in_mode1`, `cnt_stable_in_mode1_when_not_decrementing`

**Error category**: `assertion_error` — all failures stem from assertions not accounting for the transition/initial cycle, not from DUT bugs.

**Root cause**: The `assertStableWhen(mode === 1.U, ...)` checks `mode === RegNext(mode)` on the very cycle where mode transitions 0→1, so 1===0 fails. Similarly, all mode-1 assertions check cnt against `RegNext(cnt)` without excluding the transition cycle where cnt received its last mode-0 increment. The `cnt_inc_in_mode0` assertion fails on the first evaluation cycle because cnt hasn't yet incremented from its initial value.

**Files/lines changed**: `rgraph.scala` lines 31, 34, 41, 47.

**Changes made**:
1. `mode_stays_one_once_set` (L31): `mode === 1.U` → `RegNext(mode) === 1.U` in the stability condition, so the assertion only checks stability once mode has been 1 for ≥1 cycle.
2. `cnt_inc_in_mode0` (L34): Added `&& RegNext(mode) === 0.U` to the guard to skip the initial cycle.
3. `cnt_dec_in_mode1` (L41): Added `&& RegNext(mode) === 1.U` to the guard to skip the transition cycle.
4. `cnt_stable_in_mode1_when_not_decrementing` (L47): Added `&& RegNext(mode) === 1.U` to the guard to skip the transition cycle.

**Assertion-label preservation**: All original labels preserved exactly — `mode_stays_one_once_set`, `cnt_inc_in_mode0`, `cnt_dec_in_mode1`, `cnt_stable_in_mode1_when_not_decrementing`.

**Homologous assertions**: The three structurally identical mode-1 assertions (`cnt_dec_in_mode1`, `cnt_stable_in_mode1_when_not_decrementing`, and `mode_stays_one_once_set`) all had the same class of bug — failing to exclude the transition cycle — and were all repaired with the same pattern (adding `RegNext(mode) === 1.U` guard). The `cnt_inc_in_mode0` assertion had an analogous first-cycle issue and was repaired with `RegNext(mode) === 0.U`.

**Expected effect**: All safety assertions should now be provable. The `cnt_eventually_zero_in_mode1` liveness is left unchanged as it should pass once the DUT's mode-1 decrement behavior is correctly bounded (max 4095 decrements + 1 transition cycle ≤ 4096 bound).