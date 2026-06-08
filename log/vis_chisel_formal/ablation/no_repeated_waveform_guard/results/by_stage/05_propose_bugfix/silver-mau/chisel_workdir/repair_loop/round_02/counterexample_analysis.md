# Counterexample Analysis Report: `controlvis.read_miss_to_read_data_on_done`

## 1. Verification Environment

- **Top module**: `controlvis` (from `controlvis.scala`)
- **Structure**: A cache/memory controller FSM with states IDLE, READ_MISS, READ_DATA
- **Key components**:
  - `stateReg` (3-bit register): FSM state, defaults to IDLE on reset
  - `rReadDoneFromBCU_n`, `rWorkMAU`, `rValid`, `rMatch`, `rAccessMode`: registered inputs
  - `io.Rst_n`: active-low reset input (asynchronous-style, synchronous reset in Chisel)
  - `resetCounter`: counts cycles since last reset, generates `notChaos` signal
- **Design under test**: A memory access controller that transitions from IDLE to READ_MISS on a read miss, then to READ_DATA when data is ready

## 2. Violated Assertion

- **Full assertion name**: `read_miss_to_read_data_on_done`
- **Code snippet** (line 155–156 of `controlvis.scala`):
  ```scala
  val ant = RegNext(stateReg === State.READ_MISS && !rReadDoneFromBCU_n && notChaos, false.B)
  fvAssert(!ant || (stateReg === State.READ_DATA), "read_miss_to_read_data_on_done")
  ```
- **Natural language description**: If the FSM was in READ_MISS state with data ready (`!rReadDoneFromBCU_n`) during a stable period (`notChaos`), then the next cycle the FSM must be in READ_DATA state.
- **File location**: `controlvis.scala`, lines 155–156

## 3. Waveform Information

- **Waveform file**: `verilog/extra_bench/silver-mau/controlvis.read_miss_to_read_data_on_done.fst`
- **Time range**: 0 ns → 40 ns (4 clock cycles, clock period = 10 ns)
- **Key time points**:

| Time (ns) | Event |
|-----------|-------|
| 0 | Clock posedge 0: io_Rst_n=0 (in reset), stateReg=IDLE |
| 10 | Clock posedge 1: io_Rst_n=1 (reset released), stateReg=IDLE, rWorkMAU=1, io_WorkMAU=0 |
| 20 | Clock posedge 2: **io_Rst_n=0** (reset re-asserted), stateReg=READ_MISS, ant condition true |
| 30 | Clock posedge 3: io_Rst_n=0, stateReg=IDLE (reset forcing), **assertion FAILS** |
| 40 | Clock posedge 4: io_Rst_n=0, stateReg=IDLE |

- **Critical signals at t=30 (failure point)**:
  - `ant_2` = 1 (antecedent high)
  - `stateReg` = 000 (IDLE — should be READ_DATA=011 per assertion)
  - `io_Rst_n` = 0 (reset active)
  - `read_miss_to_read_data_on_done` = 0 (assertion output, failure detected)

## 4. Root Cause Analysis

### Buggy Code Location

**File**: `controlvis.scala`, line 155
```scala
val ant = RegNext(stateReg === State.READ_MISS && !rReadDoneFromBCU_n && notChaos, false.B)
```

### Description of the Bug

The assertion is **incorrectly written** — it fails to guard against the reset signal (`io.Rst_n`) going low between the antecedent evaluation cycle and the consequent evaluation cycle. This is an **assertion error**, not a DUT bug.

### Causal Sequence (Evidence from Waveform)

1. **Cycle 1 (t=10 posedge)**: `io_Rst_n=1` (reset released). The state machine transitions from IDLE to READ_MISS because `rWorkMAU=1`, `rAccessMode(0)=0` (read), and `rValid=0` (miss).

2. **Cycle 2 (t=20 posedge)**: `stateReg=010` (READ_MISS), `rReadDoneFromBCU_n=0` (data ready). The combinational condition `stateReg === READ_MISS && !rReadDoneFromBCU_n && notChaos` evaluates to `true` (as confirmed by `_ant_T_4=1` at t=20). The `RegNext` will pass this value to `ant_2` at the next posedge.

   However, at t=20, `io_Rst_n` transitions from 1 to 0. At the clock sampling point, `_GEN=0` (reset not yet active — see trace), so the FSM would normally transition to READ_DATA (011). But `io_Rst_n=0` takes effect immediately for the next evaluation.

3. **Cycle 3 (t=30 posedge)**: `io_Rst_n=0` (reset active). The `when(!io.Rst_n)` reset override takes priority, forcing `stateReg := IDLE` (000). Meanwhile, `ant_2=1` (the previous condition is now the registered value). The assertion `!ant_2 || (stateReg === READ_DATA)` evaluates to `false` because:
   - `ant_2=1` → left side is `false`
   - `stateReg=000` (IDLE) ≠ `011` (READ_DATA) → right side is `false`
   - Result: `fvAssert` fires the counterexample

### Why This Is Not a DUT Bug

The state machine design correctly implements the reset's highest priority:
```scala
when(!io.Rst_n) {
  stateReg := State.IDLE  // Reset override — acts at the next clock edge
}.otherwise {
  // Normal FSM transitions
}
```
When `io.Rst_n` goes low, **the design should** force IDLE. The DUT behaves correctly.

### Why This Is an Assertion Error

The `notChaos` guard was intended to filter out unstable initialization scenarios, but **it is always high** (confirmed by `controlvis.resetCounter.notChaos=1` throughout the trace). The assertion never anticipated the case where `io.Rst_n` re-asserts between the antecedent and consequent evaluations.

### Fix Recommendation

**Option A**: Add `io.Rst_n` to the antecedent guard so the implication only applies when reset is inactive:

```scala
val ant = RegNext(stateReg === State.READ_MISS && !rReadDoneFromBCU_n && notChaos && io.Rst_n, false.B)
fvAssert(!ant || (stateReg === State.READ_DATA), "read_miss_to_read_data_on_done")
```

This ensures that if `io.Rst_n` goes low, the antecedent cannot be true, making the assertion vacuously pass.

**Option B**: Add `io.Rst_n` as an additional condition on the assertion itself (defensive):

```scala
fvAssert(!ant || (stateReg === State.READ_DATA) || !io.Rst_n, "read_miss_to_read_data_on_done")
```

**Option C**: Correct the `notChaos` signal generation to track `io.Rst_n` going active. If `notChaos` were properly tied to `io.Rst_n` stability, the existing assertion would work.

### Error Classification

**Type**: `assertion_error` — the assertion doesn't account for the legitimate reset behavior of the DUT.
