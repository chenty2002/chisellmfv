# Counterexample Analysis Report: `twoQ.buechi_progress_on_match`

## 1. Verification Environment

- **Top Module**: `twoQ` (WIDTH=2, LENGTH=4)
- **Waveform File**: `verilog/extra_bench/strltl_twoq_LTLM1/twoQ.buechi_progress_on_match.fst`
- **Waveform Duration**: 12 cycles (0–120 ns)

### Key Components
| Component | Type | Role |
|---|---|---|
| `q0` / `q1` | `sampleq` | Dual-queue FIFO with read/write paths. Each queue has separate read and write FIFOs with independent pointers. |
| `buechi` | `Buechi` | LTL monitor automaton tracking queue-0 progress. States: n1(0), n2(1), n3(2), n4(3), Trap(4). |
| `bus_gnt` | Wire | Combinatorial arbiter: `select=0 → grant q0`; `select=1 → grant q1`. |

### Connections
- `q0.io.bus_gnt := bus_gnt(0)` — queue 0 is granted when `!io.select && bus_req(0)`
- `buechi.io.q0match := q0.io.match_out` — Buechi monitors queue 0 match events
- `buechi.io.q0storeaddrNEQq0readhead := (q0.io.storeaddr === q0.io.readhead)` — equality check (misleading name)
- `buechi.io.busgnt0 := bus_gnt(0)` — Buechi also monitors bus grants

---

## 2. Violated Assertion

### Full Assertion Name
**`twoQ.buechi_progress_on_match`** (from waveform filename: `twoQ.buechi_progress_on_match.fst`)

### Code Snippet (twoqLTLM1.scala, line 321‑323)
```scala
// When q0 has a match (q0match), the Buechi should make progress toward
// s_n1 or beyond within a bounded number of cycles.
astRelaxedLiveness(q0.io.match_out, io.scc || !q0.io.match_out, 8, "buechi_progress_on_match")
```

### Natural Language Description
> **When `q0.io.match_out` (matchReg) is true, within 8 clock cycles, either `io.scc` must become true (indicating the Buechi automaton reached state s_n3 or s_n4), OR `q0.io.match_out` must become false again.**

In other words, a detected match should be followed by progress through the Buechi automaton toward the accepting SCC states (n3/n4), or the match should be cleared.

### File Location
- **File**: `twoqLTLM1.scala`
- **Line**: 321–323

---

## 3. Waveform Information

### Full Waveform Path
`verilog/extra_bench/strltl_twoq_LTLM1/twoQ.buechi_progress_on_match.fst`

### Time Range
- **Start**: 0 ns (initial state)
- **End**: 120 ns (12 cycles)
- **Clock period**: 10 ns per cycle
- **Assertion fires low at**: 110 ns (cycle 11)

### Key Time Points

| Time (ns) | Event |
|---|---|
| 0 | Initial state. `q0.readheadReg=0, storeaddrReg=0, matchReg=0, writeempty=1` |
| 10 | First clock edge processed. `readtail=1, bus_gnt=1` (q0 granted). Match detection begun but OLD matchReg value still 0. |
| 20 | **FALSE MATCH DETECTED.** `matchReg=1, readheadReg=1`. `readfifo(0)=00` falsely matches `writefifo(0)=00`. |
| 30 | Buechi state transitions: `s_n2(001)→s_n1(000)`. `storeaddrReg=1, readheadReg=1`, so `q0storeaddrNEQq0readhead=1`. |
| 40 | Buechi state: `s_Trap(100)` — trapped because `q0storeaddrNEQq0readhead=1` when entering s_n1. |
| 40–110 | Buechi stuck in s_Trap. `io.scc` stays 0, `q0.match_out` stays 1. |
| 110 | **Assertion `buechi_progress_on_match` goes low (fails).** |

### Critical Signal Values at Failure Point (110 ns)
| Signal | Value |
|---|---|
| `buechi_progress_on_match` | **0** (assertion failed) |
| `q0.io_match_out` | **1** (still asserted) |
| `buechi.state` | **100** (s_Trap) |
| `io.scc` | **0** (never became true) |
| `io.fair` | **0** (never became true) |
| `q0.io_storeaddr` / `q0.io_readhead` | **01 / 01** (equal, stuck) |

---

## 4. Root Cause Analysis

### Bug Location
- **File**: `twoqLTLM1.scala`
- **Module**: `sampleq`  
- **Lines**: 92‑97 (the `writeEntryValid` computation inside the `for` loop in the `when(io.bus_gnt)` block)

### Buggy Code
```scala
// Lines 92-97 of twoqLTLM1.scala (inside for-loop in when(io.bus_gnt) block)
val writeEntryValid = Mux(
    writehead < writetail,
    (i.U >= writehead) && (i.U < writetail),
    (i.U >= writehead) || (i.U < writetail)   // ← BUG: false when writehead == writetail (empty)
)
```

When `writehead == writetail` (write FIFO is empty), the condition `writehead < writetail` is false, so the **else** branch executes: `(i.U >= writehead) || (i.U < writetail)`. For `i=0` (and `writehead=0, writetail=0`), this evaluates to `(0>=0) || (0<0)` = `true || false` = **true**, incorrectly reporting that entry 0 is valid when the write FIFO is actually empty.

### Root Cause Description
1. **The `writeEntryValid` circuit fails to handle the empty FIFO case.** When `writehead == writetail`, the FIFO is empty, but the `Mux` takes the wrapped-case branch (`(i >= writehead) || (i < writetail)`) which always evaluates to true for `i == writehead`, incorrectly marking stale write FIFO entries as valid.

2. **This causes a false positive match.** In the counterexample:
   - At time 0–10: a read request writes `00` to `readfifo(0)` via `readtail=0→1`
   - The write FIFO remains empty (`writehead=0, writetail=0, writeempty=true`), so `writefifo(0)` retains its initial value of `00`
   - At cycle 2 (time 10→20): `bus_gnt=1` triggers the match detection loop. The buggy `writeEntryValid(0)` evaluates to `true` (even though no write has occurred)
   - `readfifo(0) === writefifo(0)` evaluates to `00 === 00` → **true**
   - **`matchReg` is set to `true` and `storeaddrReg` is set to `readheadReg` (which was 0)**

3. **Match persists because readheadReg gets incremented before matchReg takes effect.** In Chisel, register reads in `when` conditions use the **old** value. During the match-detecting cycle:
   - The for-loop sets `matchReg := true.B` (new value deferred to next edge)
   - The output selection reads `!matchReg` → reads **old** value (0) → enters the read branch → increments `readheadReg` to 1
   - After the edge: `matchReg=1, readheadReg=1`

4. **The Buechi automaton traps.** With `storeaddrReg=0` and `readheadReg=1`, the equality `storeaddr === readhead` = `false`, so `q0storeaddrNEQq0readhead=0`. The Buechi transitions `s_n2 → s_n1`. In s_n1:
   ```scala
   when(io.q0storeaddrNEQq0readhead === false.B) {
       state := s_n3   // would make progress
   }.otherwise {
       state := s_Trap  // ← THIS BRANCH: q0storeaddrNEQq0readhead was 1!
   }
   ```
   Wait — at time 30 when entering s_n1, `storeaddrReg=1, readheadReg=1`, so `q0storeaddrNEQq0readhead=1`. But this is because `storeaddrReg` was updated to `readheadReg` at the time of match (which was 0), but then readheadReg incremented to 1 at the same time... Actually, the match set `storeaddrReg := readheadReg` (which was 0), but in the *next* cycle (time 20→30), the match detection loop runs again with `readheadReg=1`, finds `readfifo(1)=00` matching `writefifo(0)=00` again, and sets `storeaddrReg := readheadReg = 1`. So `storeaddrReg` becomes 1 at time 30. Then `q0storeaddrNEQq0readhead` = `(storeaddr === readhead)` = `(1===1)` = `true = 1`, causing s_n1 → s_Trap.

5. **Deadlock:** Once in s_Trap, the Buechi stays there forever. `io.scc` never becomes true. `q0.match_out` stays true because the false match persists (the for-loop keeps finding a match every cycle). The assertion fails because neither `io.scc` becomes true nor `q0.match_out` becomes false within 8 cycles.

### Evidence from Waveform
- **Time 20**: `readfifo_0=00, writefifo_0=00, writeempty=1` → False match on uninitialized write entry
- **Time 20**: `matchReg: 0→1, readheadReg: 0→1` → matchReg latches, readheadReg increments using OLD matchReg value
- **Time 30**: `storeaddrReg: 0→1` → match detection loop sets storeaddrReg to readheadReg again
- **Time 40**: `buechi.state: 000(s_n1)→100(s_Trap)` → Buechi enters trap state
- **Time 40–110**: `io.scc=0, q0.match_out=1` → neither condition of the assertion is met

### Classification
- **Type**: **`dut_bug`** — genuine design bug in the `sampleq` module
- **Category**: **Bug in the Original Design** (not an assertion error or test setup issue)

### Fix
Change `writehead < writetail` to `writehead <= writetail` in the `writeEntryValid` computation (line 93 of `twoqLTLM1.scala`):

```scala
val writeEntryValid = Mux(
    writehead <= writetail,       // ← FIXED: use <= instead of <
    (i.U >= writehead) && (i.U < writetail),  // non-wrapped (or empty → all false)
    (i.U >= writehead) || (i.U < writetail)   // wrapped
)
```

When `writehead == writetail` (empty FIFO), `writehead <= writetail` is `true`, so the first branch executes: `(i>=writehead) && (i<writetail)` = `(i>=0) && (i<0)` = `false` for all i. This correctly prevents false matches on stale FIFO entries.
