# Counterexample Analysis Report: `read_done_transitions_to_read_data`

## 1. Verification Environment

### Top Module
- **Module**: `controlvis` (Chisel, from `controlvis.scala`)
- **Structure**: A state machine that manages memory access control with the following states: IDLE (0), READ_HIT (1), READ_MISS (2), READ_DATA (3), WRITE_HIT (4), WRITE_MISS (5).
- **Key Components**:
  - `stateReg` — Main state register (UInt(3.W))
  - `prevStateReg` — Pipeline register holding the previous cycle's state
  - `rReadDoneFromBCU_n` — Registered (synchronized) version of the `io.ReadDoneFromBCU_n` input
  - `rRst_n` — Registered version of `io.Rst_n`
  - `rWorkMAU`, `rAccessMode`, `rMatch`, `rValid` — Other registered inputs
- **Description**: The design is a memory control unit that transitions through states based on work requests, access modes, match/valid signals, and read/write completion signals from a BCU (Bus Control Unit).

## 2. Violated Assertion

### Assertion Name
`read_done_transitions_to_read_data` (from waveform filename: `controlvis.read_done_transitions_to_read_data.fst`)

### Code Snippet
```scala
// Safety 7: When in READ_MISS and read done arrives, next state must be READ_DATA.
// rRst_n guards against reset being active in the previous cycle, which forces stateReg to IDLE.
fvAssert(
    !(prevStateReg === State.READ_MISS && io.Rst_n && rRst_n && !rReadDoneFromBCU_n) ||
    (stateReg === State.READ_DATA),
    "read_done_transitions_to_read_data"
)
```
— **File**: `controlvis.scala`, **Lines 176–180**

### Natural Language Description
If the previous cycle's state (`prevStateReg`) was READ_MISS, and there is no active reset (`io.Rst_n=1`, `rRst_n=1`), and the read-done signal is asserted (`rReadDoneFromBCU_n=0`), then the current state (`stateReg`) must be READ_DATA.

## 3. Waveform Information

### Waveform File
`verilog/extra_bench/silver-mau/controlvis.read_done_transitions_to_read_data.fst`

### Time Range
0 ns → 30 ns (3 clock cycles, 10 ns period)

### Key Time Points and Signal Values

| Time | Event | stateReg (3-bit) | prevStateReg (3-bit) | rReadDoneFromBCU_n | io_ReadDoneFromBCU_n |
|------|-------|-------------------|----------------------|--------------------|----------------------|
| 0    | Posedge 0 | 000 (IDLE) | 000 (IDLE) | 0 (reset) | 1 |
| 10   | Posedge 1 | 010 (READ_MISS) | 000 (IDLE) | 1 | 0 |
| **20** | **Posedge 2** | **010 (READ_MISS)** | **010 (READ_MISS)** | **0** | **0** |
| 30   | Posedge 3 | will be 011 (READ_DATA) | 010 (READ_MISS) | 0 | 0 |

### Critical Values at Failure Point (time=20ns)

| Signal | Value |
|--------|-------|
| `stateReg [2:0]` | **010 (READ_MISS)** ← violates assertion |
| `prevStateReg [2:0]` | 010 (READ_MISS) ← satisfies antecedent |
| `io_Rst_n` | 1 |
| `rRst_n` | 1 |
| `rReadDoneFromBCU_n` | **0** ← asserts true ("read done arrived") |
| `io_ReadDoneFromBCU_n` | 0 |

## 4. Root Cause Analysis

### Classification: **Incorrect Assertion** (assertion_error)

### Bug Location
**File**: `controlvis.scala`, **Lines 176–180**  
**Assertion**: `read_done_transitions_to_read_data`

### Description of the Bug

The assertion has an **incorrect timing assumption**. It expects `stateReg` to already be `READ_DATA` in the **same cycle** that `rReadDoneFromBCU_n` becomes 0, but due to register timing, the state machine requires **one additional cycle** to react to the new `rReadDoneFromBCU_n` value.

### Detailed Timing Analysis

The design uses the following registers (all updated at the positive clock edge):

```scala
val stateReg = RegInit(State.IDLE)            // line 40
val rReadDoneFromBCU_n = RegNext(io.ReadDoneFromBCU_n, false.B)  // line 48
val prevStateReg = RegNext(stateReg)           // line 133
```

The state machine's next-state logic (lines 79–82) uses the **current** (pre-update) value of `rReadDoneFromBCU_n`:

```scala
is(State.READ_MISS) {
    when(!rReadDoneFromBCU_n) {  // uses old register value
        stateReg := State.READ_DATA
    }
}
```

**Trace of events:**

1. **Time 0 (posedge)**: `io.WorkMAU=1`, `io.ReadDoneFromBCU_n=1`
   - State machine transitions: IDLE → READ_MISS (stateReg=010)
   - `rReadDoneFromBCU_n` registers the input: 0 (reset init) → 1

2. **Time 10 (posedge)**: `io.WorkMAU=0`, `io.ReadDoneFromBCU_n=0`
   - State machine sees: stateReg=READ_MISS, **rReadDoneFromBCU_n=1 (old value)**
   - Since `!rReadDoneFromBCU_n = 0`, no transition → stateReg stays READ_MISS
   - `rReadDoneFromBCU_n` registers the input: 1 → 0

3. **Time 20 (posedge)**: ✱ **FAILURE POINT** ✱
   - State machine sees: stateReg=READ_MISS, **rReadDoneFromBCU_n=0 (new value!)**
   - Now `!rReadDoneFromBCU_n = 1`, so stateReg := READ_DATA
   - **But the assertion checks `stateReg` BEFORE this update!**
   - `prevStateReg=READ_MISS` + `rReadDoneFromBCU_n=0` → expects `stateReg=READ_DATA`
   - **Actual**: `stateReg=010 (READ_MISS)` → **Assertion fails!**

4. **Time 30 (posedge)**: The transition completes
   - `stateReg` becomes 011 (READ_DATA) — one cycle too late for the assertion.

### Root Cause Summary

The assertion incorrectly assumes that when `rReadDoneFromBCU_n` transitions to 0, `stateReg` should immediately reflect `READ_DATA` in the same cycle. However, `rReadDoneFromBCU_n` and `stateReg` are **both registers updated simultaneously** at the positive clock edge. The state machine's next-state logic runs using the **pre-update** values, so it cannot respond to the new `rReadDoneFromBCU_n=0` value until the **next** clock cycle.

**Correct behavior**: When `prevStateReg=READ_MISS` and `rReadDoneFromBCU_n=0` (done), `stateReg` will become `READ_DATA` **one cycle later** (at the next posedge), not in the current cycle.

### Evidence from Causal Analysis

The causal analysis returned no structured root candidates, but the manual waveform analysis confirms a clear **cycle-level timing mismatch** between the assertion's expectation and the actual register update semantics.

### Fix Recommendation

The assertion should be modified to account for the one-cycle delay between detecting `rReadDoneFromBCU_n=0` and the state transition to `READ_DATA`. Possible fixes:

**Option A** (simplest): Allow `stateReg` to be either `READ_MISS` or `READ_DATA` when the done signal is first asserted, since the transition takes one extra cycle:
```scala
fvAssert(
    !(prevStateReg === State.READ_MISS && io.Rst_n && rRst_n && !rReadDoneFromBCU_n) ||
    (stateReg === State.READ_DATA || stateReg === State.READ_MISS),
    "read_done_transitions_to_read_data"
)
```

**Option B** (stronger, two-cycle check): Use `prevPrevStateReg` to check the transition happened within two cycles:
```scala
val prevPrevStateReg = RegNext(prevStateReg)
fvAssert(
    !(prevPrevStateReg === State.READ_MISS && io.Rst_n && rRst_n && !RegNext(rReadDoneFromBCU_n)) ||
    (stateReg === State.READ_DATA || stateReg === State.IDLE),
    "read_done_transitions_to_read_data"
)
```

The liveness assertion (`read_miss_eventually_completes`, lines 200–205) already ensures that READ_MISS will eventually transition to READ_DATA or IDLE, so Option A remains sound.
