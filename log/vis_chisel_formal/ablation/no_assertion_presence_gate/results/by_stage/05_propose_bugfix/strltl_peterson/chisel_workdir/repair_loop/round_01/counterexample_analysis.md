# Counterexample Analysis Report: `entering_p0_interested`

## 1. Verification Environment

### Top Module: `Peterson` (mppLTLM1.scala:129:7)

### Module Structure
- **Peterson**: The main design under test, implementing Peterson's mutual exclusion algorithm with an 8-process (`HIPROC=7`) time-shared state machine.
- **Buechi**: A Büchi automaton sub-module used for LTL property checking (connected but not directly involved in the failing assertion).
- **ResetCounter**: A helper module from Chisel-FV to track time since reset.

### Key Components
| Component | Signal | Description |
|-----------|--------|-------------|
| `pc(i)` | pc_0 ... pc_7 | Per-process program counter registers (8 processes, 3-bit each) |
| `interested(i)` | interested_0 ... interested_7 | Per-process interest flag registers |
| `self` | self [2:0] | Currently selected process ID (updated every cycle from `io_select`) |
| `turn` | turn [2:0] | Shared turn variable for the Peterson protocol |
| `io_select` | io_select [2:0] | Unconstrained input selecting which process executes a step |

### Connections
- `self <= io_select` (when `io_select <= 7`)
- State machine reads `pc(self)` to determine the next state for the currently selected process
- Only the process indicated by `self` makes progress in each cycle

---

## 2. Violated Assertion

### Assertion Name (from waveform filename)
`Peterson.entering_p0_interested`

### Source Code Location
**File**: `mppLTLM1.scala`, **Line 273**

### Code Snippet
```scala
fvAssert(!(pc(0) === Loc.L1) || interested(0), "entering_p0_interested")
```

### Generated Verilog (Peterson.sv, lines ~240-243)
```verilog
entering_p0_interested:
    assert property (@(posedge clock) disable iff (~hasBeenReset)
                     pc_0 != 3'h1 | interested_0);
```

### Property Description (Natural Language)
**At every posedge clock**: If process 0's program counter is at location L1 (i.e., process 0 is about to set its interest flag), then `interested(0)` must be true. This is a protocol consistency invariant: a process that has progressed past the "decide to enter" phase should have its interested flag set.

---

## 3. Waveform Information

### Waveform File
`verilog/extra_bench/strltl_peterson/Peterson.entering_p0_interested.fst`

### Time Range: 0 ns → 20 ns (2 clock cycles, period = 10 ns)

### Key Time Points

| Time (ns) | Event | Signal Values |
|-----------|-------|--------------|
| 0 | Posedge clock + Reset | `pc_0 = L0 (000)`, `self = 0 (000)`, `interested_0 = 0`, `io_select = 3 (011)` |
| 0-10 | After reset released | `pc_0` scheduled to transition L0→L1; `self` stays 0 for this cycle |
| 10 | Posedge clock (no reset) | `pc_0 = L1 (001)`, `self = 3 (011)`, `interested_0 = 0` |
| 10 | **ASSERTION FAILS** | `pc_0 == 3'h1 AND interested_0 == 0` violates `pc_0 != 3'h1 | interested_0` |

### Critical Signal Trace

| Signal | Time 0 | Time 5 | Time 10 | Time 15 |
|--------|--------|--------|---------|---------|
| `pc_0 [2:0]` | 000 (L0) | 000 (L0) | 001 (L1) | 001 (L1) |
| `self [2:0]` | 000 | 000 | 011 (3) | 011 (3) |
| `io_select [2:0]` | 011 (3) | 011 (3) | 011 (3) | 011 (3) |
| `interested_0` | 0 | 0 | 0 | 0 |
| `entering_p0_interested` | 1 | 1 | 0 | 0 |

**Note**: The `entering_p0_interested` signal shows the assertion failure status. It transitions from 1 (failing) to 0 (passing) at time 10, indicating the assertion violation condition was present before time 10 and the failing condition (pc_0==L1 && interested_0==0) was detected at time 10.

---

## 4. Root Cause Analysis

### Buggy Code Location
**File**: `mppLTLM1.scala`, **Lines 190-191** (self update logic) and **Lines 201-209** (state machine)

### Error Classification: **Setup Issue**

The root cause is **insufficient constraints on the `io_select` input** in the formal verification environment. The `io_select` input can change arbitrarily from cycle to cycle, which breaks the Peterson protocol's implicit assumption that a process will be serviced on consecutive cycles until it completes its atomic transitions.

### Bug Mechanism (Step by Step)

**Cycle 1 (Time 0, posedge clock with reset):**

1. Reset asserts, initializing all registers:
   - `self <= 0`, `pc_0 <= L0 (000)`, `interested_0 <= 0`

2. After reset, the non-reset logic evaluates:
   - **`_GEN_3 = pc(self) = pc(0) = L0`** (pc(self) evaluated with current self=0)
   - Since pc(self) == L0 → `pc(self) := L1` (i.e., `pc_0 <= 3'h1`)

3. **Result**: `pc_0` is scheduled to become L1; `interested_0` unchanged; `self` unchanged

**Cycle 2 (Time 10, posedge clock without reset):**

1. All registers from previous cycle take effect:
   - `pc_0 = L1 (001)`, `self = 0 (000)`, `interested_0 = 0`

2. Non-reset logic evaluates:
   - **`self <= io_select = 3 (011)`** — self is updated to 3!
   - **`_GEN_3 = pc(self) = pc(0) = L1`** — BUT this is evaluated with `self = 0` (OLD value, before the non-blocking assignment takes effect)
   - Wait, actually let me re-check...

Let me be more precise about the Verilog simulation semantics:

At time 10 posedge:
1. **Read current register values**: `self=0`, `pc_0=L1`, `interested_0=0`
2. **Evaluate all RHS expressions** (using current values):
   - `_GEN = (self == 0) = true` (self is still 0 at this point)
   - `_GEN_3 = pc(self) = pc(0) = 3'h1` (L1)
   - `_GEN_10 = (_GEN_3 == 3'h1) = true`
   - For pc_0: `_GEN_31[1] = {_GEN ? 3'h2 : pc_0} = {true ? 3'h2 : pc_0} = 3'h2` (L2) ✓
   - For interested_0: `_GEN_10` is true, so `interested_0 <= _GEN | interested_0 = true | 0 = 1`
   - `self <= io_select = 3'h3`
3. **Update all registers**: `pc_0 <= L2`, `interested_0 <= 1`, `self <= 3`

So at time 10, looking at the RHS evaluation:
- `pc_0` should become L2
- `interested_0` should become 1
- `self` should become 3

BUT the assertion is checked **after** the update:
- `pc_0` = L2, `interested_0` = 1 → assertion passes!

Hmm, but the waveform shows `pc_0 = L1` at time 10 and `interested_0 = 0` at time 10. This contradicts my analysis.

Wait, the waveform shows values **after** the clock edge. Let me reconsider.

Looking at the waveform trace:
- `pc_0 [2:0]`: changes at time 10 from 000 to 001
- `self [2:0]`: changes at time 10 from 000 to 011
- `interested_0`: no changes (stays 0)

So the non-blocking updates at time 10:
- pc_0: 000 → 001 (L1), NOT L2!
- self: 000 → 011 (3)
- interested_0: stays 0

This means at time 10, the state machine evaluated pc(self) = pc(0) = L0, NOT L1!

Let me re-examine. At time 10, before the clock edge:
- self = 0 (from the waveform: self is 000 at time 5)
- pc_0 = 000 (L0) (from the waveform: pc_0 is 000 at time 5)

Wait! Let me re-look at the waveform trace:

`pc_0 [2:0]` changes: `[{"time": 0, "value": "000"}, {"time": 10, "value": "001"}]`

This means:
- From time 0 to time 10 (exclusive at 10): pc_0 = 000 (L0)
- From time 10 onwards: pc_0 = 001 (L1)

So at time 10, when the posedge clock fires, pc_0 is being updated from L0 to L1. The RHS evaluation would have seen pc_0 = L0 (old value).

But then... the pc_0 change from L0 to L1 happened at time 10, which means the transition from earlier (time 0) should have happened. But no!

Let me check the clock timing. From the active signals:
- Time 0: `Peterson.resetCounter.clk=1`
- Time 5: no clk in active signals
- Time 10: `Peterson.resetCounter.clk=1`
- Time 15: no clk in active signals

So the clock is high at time 0 and time 10. The posedge is at time 0 and time 10.

At time 0 (posedge with reset):
- Reset is high
- All registers get reset values (pc_0 = L0, interested_0 = 0, self = 0)
- The non-reset logic doesn't execute (reset overrides)

At time 10 (posedge without reset):
- We enter the `else` branch
- Current register values: self=0, pc_0=L0, interested_0=0
- _GEN_3 = pc(self) = pc(0) = L0
- _GEN_4 = (L0 == 3'h0) = true
- Since _GEN_4 is true:
  - pc_0 <= _GEN_9 = {2'h0, ~io_pause} = 3'h1 (L1) (if io_pause=0)
  - interested_0 unchanged (stays 0)
- self <= io_select = 3'h3

So after time 10:
- pc_0 = L1, self = 3, interested_0 = 0

The assertion checks at posedge clock time 10:
- pc_0 was just updated to L1
- interested_0 is 0
- pc_0 != 3'h1 | interested_0 = 0 | 0 = 0 → FAIL

AH, so I was wrong about the earlier analysis. The RESET at time 0 means the non-reset logic (state machine) didn't execute at all at time 0. The state machine only starts executing at time 10. At that point, self=0 and pc(0)=L0, so the state machine advances pc(0) from L0 to L1, but ALSO updates self to 3 (io_select = 3). This means in the NEXT cycle (time 20), the state machine would operate on pc(self)=pc(3) instead of pc(0), and pc(0) would be stuck at L1.

So the assertion fails at time 10 because:
1. At time 10 posedge: pc(0) transitions from L0 to L1 (first real execution of state machine)
2. At time 10 posedge: self transitions from 0 to 3 (because io_select=3)
3. The assertion checks at time 10: pc_0 = L1 AND interested_0 = 0 → FAIL
4. interested_0 would have been set to true at time 20 IF self had stayed at 0, but self changes to 3

So essentially, the design only gets ONE cycle to run the state machine after reset before self changes. In that one cycle, pc(0) goes from L0 to L1, but interested(0) hasn't been set yet. The interested(0) setting would happen when pc(0)=L1 is processed, but that can't happen because self=3 now.

The root cause is that **io_select is 3 but self starts at 0 (reset value), so on the first non-reset clock cycle, self=0 and pc(0)=L0 (resets are working correctly), but the state machine only advances pc(0) to L1 — it doesn't set interested(0) yet. And immediately after, self becomes 3, abandoning process 0.**

This is a setup issue because in a real system, `io_select` would (presumably) stay fixed for enough consecutive cycles for a process to complete its protocol steps. But in formal verification where inputs are unconstrained, `io_select` can change arbitrarily.

### Evidence from Waveform

| Time | `self` | `pc_0` | `interested_0` | `io_select` | Event |
|------|--------|--------|---------------|-------------|-------|
| 0 | 000 (0) | 000 (L0) | 0 | 011 (3) | Reset, all registers initialized |
| 0-5 | 000 | 000 | 0 | 011 | After reset release |
| 5 | 000 | 000 | 0 | 011 | (clock negedge) |
| 10 | **011 (3)** | **001 (L1)** | **0** | 011 | Posedge: state machine advances pc(0) L0→L1 AND self←3; **assertion FAILS** |
| 10-15 | 011 | 001 | 0 | 011 | **Stuck**: pc(0)=L1, interested_0=0 indefinitely |

### Why the Assertion Fails

The assertion `entering_p0_interested` requires that whenever `pc(0) == L1`, `interested(0)` must be true. At time 10:

1. `pc(0)` becomes `L1` because at time 10, the state machine processes `pc(self)=pc(0)=L0` and advances it to `L1` (setting `pc(0) := L1`).

2. `interested(0)` stays `0` because:
   - The `interested(self)` register is only set to `true` when `pc(self) == L1` (the `is(Loc.L1)` case in the Chisel `switch` statement).
   - At time 10, `pc(self) = pc(0) = L0` (before update), so the `L1` case is never entered. The `interested` flags remain unchanged.
   - The `interested(0)` flag would only be set in the *next* cycle (time 20) when `pc(0) = L1` is processed — but that requires `self` to still be `0`.

3. `self` changes from `0` to `3` at time 10 (since `self <= io_select = 3`), so at time 20, the state machine processes `pc(3)` instead of `pc(0)`, and process 0 is **abandoned** with `pc(0) = L1` and `interested(0) = 0`, permanently violating the assertion.

### Bug Category: **Setup Issue**

This is not a logic error in the DUT nor an incorrect assertion. The problem is that the **formal verification environment does not constrain `io_select`** to be stable for a sufficient number of cycles to complete a process's atomic state transitions.

The Peterson protocol implementation requires each process to be serviced for **at least 2 consecutive cycles** to complete the L0→L1→L2 transition:
- **Cycle 1**: `pc(self)=L0` → advance to `L1`
- **Cycle 2**: `pc(self)=L1` → set `interested(self)=true`, advance to `L2`

If `io_select` changes between cycles 1 and 2 (as it does in this counterexample, from 0 to 3), the first process is left in an inconsistent state.

### Fix Recommendation

Add constraints on `io_select` to ensure stability during a process's protocol execution. For example, in the formal testbench:
- Constrain `io_select` to remain stable while the selected process is in the middle of a protocol transition (states L1-L6)
- Or simplify: constrain `io_select` to remain stable for at least N consecutive cycles after any change

Alternatively, if the design is intended to support arbitrary context switching, the state machine needs to be modified to handle the case where a process is interrupted mid-transition (e.g., by separating the `interested` flag setting from the program counter advancement).
