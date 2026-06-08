# Counterexample Analysis Report

## 1. Verification Environment

- **Top Module**: `controlvis` (from `controlvis.scala`)
- **DUT**: A cache controller state machine with the following states:
  - IDLE (000), READ_HIT (001), READ_MISS (010), READ_DATA (011), WRITE_HIT (100), WRITE_MISS (101)
- **Inputs**: `Rst_n`, `WorkMAU`, `AccessMode`, `Match`, `Valid`, `ReadDoneFromBCU_n`, `WriteDoneFromBCU_n`
- **Outputs**: `Write`, `BCURequest_n`, `BCUWriteRequest_n`, `BCUDataOE`, `CacheDataSelect`, `MAUNotReady_n`
- **Key signals**: `stateReg` (current state), `prevStateReg` (previous cycle's state), `io_Rst_n` (reset, active low)
- **Clock**: posedge at 0ns, 10ns, 20ns, 30ns

## 2. Violated Assertion

- **Assertion Name**: `read_done_transitions_to_read_data` (from waveform filename `controlvis.read_done_transitions_to_read_data.fst`)
- **File**: `controlvis.scala`, lines 188-192
- **Code**:
  ```scala
  fvAssert(
      !(prevStateReg === State.READ_MISS && io.Rst_n && !rReadDoneFromBCU_n) ||
      (stateReg === State.READ_DATA),
      "read_done_transitions_to_read_data"
  )
  ```
- **Natural Language Property**: "When the previous state was READ_MISS, reset is not active, and the read data is ready (`!rReadDoneFromBCU_n`), then the current state must be READ_DATA."

## 3. Waveform Information

- **Waveform File**: `verilog/extra_bench/silver-mau/controlvis.read_done_transitions_to_read_data.fst`
- **Time Range**: 0ns to 30ns (3 clock cycles)
- **Waveform Duration**: 30ns, periodic at 10ns
- **Key Time Points**:

| Time (ns) | Event | stateReg | prevStateReg | io_Rst_n | rReadDoneFromBCU_n |
|-----------|-------|----------|-------------|----------|-------------------|
| 0 (posedge) | Initial | IDLE (000) | IDLE (000) | 1 | 0 |
| 10 (posedge) | Cycle edge | READ_MISS (010) | IDLE (000) | **0** | 0 |
| 20 (posedge) | Cycle edge | **IDLE (000)** | **READ_MISS (010)** | **1** | 0 |
| 30 (posedge) | **Failure point** | **IDLE (000)** | **READ_MISS (010)** | **1** | 0 |

**Failure at posedge 30ns**:
- `prevStateReg` = 010 (READ_MISS) ✓
- `io_Rst_n` = 1 (not in reset) ✓
- `!rReadDoneFromBCU_n` = 1 (read done asserted) ✓
- Antecedent = TRUE
- `stateReg` = 000 (IDLE), expected = 011 (READ_DATA) ✗
- **Assertion FAILS**

## 4. Root Cause Analysis

### Classification: **Incorrect Assertion**

### Bug Location
- **File**: `controlvis.scala`
- **Line**: 188-192 (Assertion Safety 7)

### Root Cause Description

The assertion `read_done_transitions_to_read_data` does **not** account for the scenario where the reset signal (`io_Rst_n`) was asserted in the **previous cycle**, interrupting the normal state transition from READ_MISS to READ_DATA.

### Sequence of Events Leading to Failure

1. **Cycle 0 (0-10ns)**: The state machine is in IDLE. `io_WorkMAU=1`, `io_WorkMAU=1`, `rAccessMode(0)=0` (read), not a hit, so the next state is computed as READ_MISS (010).

2. **Posedge 10ns**: `stateReg` updates to READ_MISS (010). Simultaneously, `io_Rst_n` transitions from 1 to **0** (reset asserted).

3. **Cycle 1 (10-20ns)**: The combinational next-state logic evaluates:
   ```scala
   when(!io.Rst_n) {
       stateReg := State.IDLE  // Reset takes priority!
   }.otherwise {
       switch(stateReg) {
           is(State.READ_MISS) {
               when(!rReadDoneFromBCU_n) {
                   stateReg := State.READ_DATA  // Never reached!
               }
           }
       }
   }
   ```
   Because `io_Rst_n=0`, the **reset path is selected**, forcing the next state to IDLE. The normal READ_MISS→READ_DATA transition is **bypassed**.

4. **Posedge 20ns**: `stateReg` updates to IDLE (000). `prevStateReg` updates to READ_MISS (010) — the value `stateReg` had during cycle 1.

5. **Posedge 20-30ns check**: The assertion evaluates:
   - `prevStateReg === State.READ_MISS` → true (it was READ_MISS during cycle 1)
   - `io.Rst_n` → true (reset is deasserted now, but it was active during cycle 1!)
   - `!rReadDoneFromBCU_n` → true (read done was always asserted)
   - `stateReg === State.READ_DATA` → false (stateReg is IDLE)
   - **Assertion fails**

### Key Insight

The assertion uses `io.Rst_n` (the **current** reset value), but it should also verify that reset was **not** active during the **previous** cycle (when `stateReg` was READ_MISS). The design correctly handles reset by forcing IDLE; the issue is that the assertion doesn't recognize this scenario.

The state machine has a registered version of the reset signal:
```scala
val rRst_n = RegNext(io.Rst_n, false.B)
```

At the assertion check point (posedge 20/30), `rRst_n` would be `0` (reset was active during the previous cycle), which correctly indicates that the reset path overrode the normal transition.

### Recommended Fix

Modify the assertion Safety 7 to also check that reset was **not** active in the previous cycle using the registered reset `rRst_n`:

```scala
// Fixed assertion - also checks reset was not active in previous cycle
fvAssert(
    !(prevStateReg === State.READ_MISS && io.Rst_n && rRst_n && !rReadDoneFromBCU_n) ||
    (stateReg === State.READ_DATA),
    "read_done_transitions_to_read_data"
)
```

Alternatively, replace `io.Rst_n` with `rRst_n` in the assertion to check the reset status during the previous cycle when the state was actually READ_MISS:

```scala
// Alternative fix - use registered reset
fvAssert(
    !(prevStateReg === State.READ_MISS && rRst_n && !rReadDoneFromBCU_n) ||
    (stateReg === State.READ_DATA),
    "read_done_transitions_to_read_data"
)
```

### Note on Other Assertions

All other assertions in the file (Safety 2-9) use `io.Rst_n` in the same pattern. They happen to pass in this counterexample because their antecedents are not triggered by the specific conditions, but they may have the same latent issue if counterexample search explores scenarios involving reset assertion during any state transition.
