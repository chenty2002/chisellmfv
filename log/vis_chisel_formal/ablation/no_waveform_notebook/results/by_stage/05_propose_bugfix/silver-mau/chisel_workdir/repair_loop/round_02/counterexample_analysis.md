# Counterexample Analysis: `read_miss_eventually_completes`

## 1. Verification Environment

- **Top Module**: `controlvis` (Chisel class with `Formal` mixin)
- **Module Structure**:
  - **controlvis**: Main module with a 6-state FSM (IDLE, READ_HIT, READ_MISS, READ_DATA, WRITE_HIT, WRITE_MISS)
  - **Inputs**: `io_Rst_n`, `io_WorkMAU`, `io_AccessMode[1:0]`, `io_Match`, `io_Valid`, `io_ReadDoneFromBCU_n`, `io_WriteDoneFromBCU_n`
  - **Outputs**: Control signals derived from a 6-bit vector register
  - **ResetCounter**: A black-box module tracking cycles since reset
- **Design Under Test**: A cache controller FSM that manages read/write transactions with a BCU (Bus Control Unit). The FSM transitions through various states depending on access type (read/write), hit/miss status, and BCU completion signals.

## 2. Violated Assertion

- **Full Assertion Name**: `read_miss_eventually_completes`
- **Waveform File**: `controlvis.read_miss_eventually_completes.fst`

### Code Snippet (controlvis.scala, lines 178-186)

```scala
// --- Bounded Liveness: Read miss eventually completes ---
// If the FSM enters READ_MISS, it must reach IDLE within 100 cycles.
// This catches deadlocks where the BCU read-done signal never arrives or the
// FSM gets stuck in READ_MISS or READ_DATA.
astRelaxedLiveness(stateReg === State.READ_MISS,
                   stateReg === State.IDLE,
                   100,
                   "read_miss_eventually_completes")
```

### Property Description
The assertion checks that whenever the FSM enters the `READ_MISS` state (state encoding `010`), the FSM must transition to `IDLE` state (state encoding `000`) within 100 clock cycles. This is a bounded liveness property intended to prevent deadlocks where the BCU read-done signal never arrives or the FSM gets stuck.

### File Location
- **Source**: `controlvis.scala`, lines 178-186
- **Generated Verilog**: `generated/controlvis.sv`, lines 158-162

## 3. Waveform Information

- **Full Waveform Path**: `verilog/extra_bench/silver-mau/controlvis.read_miss_eventually_completes.fst`
- **Waveform Duration**: 1040 ns (104 cycles, 10 ns/cycle)
- **Time Range**: 0 ns → 1040 ns

### Key Time Points

| Time (ns) | Cycle | Event |
|-----------|-------|-------|
| 0 | 0 | Reset active (`io_Rst_n=0`), `stateReg=IDLE(0)`, `io_ReadDoneFromBCU_n=0` |
| 10 | 1 | Reset released (`io_Rst_n=1`), `io_ReadDoneFromBCU_n=1` (de-asserts), `io_Match=0`, `io_Valid=1` |
| 20 | 2 | `stateReg` transitions to `READ_MISS(010)` — triggered by: `rWorkMAU=1`, `rAccessMode(0)=0` (read), `rValid=1`, `rMatch=0` (miss). `rReadDoneFromBCU_n=1` (not done) |
| 30 | 3 | Liveness checker `pending` signal goes high; `timer=0` |
| 40 | 4 | Timer starts incrementing: `timer=1` |
| 1010 | 101 | `io_ReadDoneFromBCU_n` goes low (0) — BCU read completes |
| 1020 | 102 | `rReadDoneFromBCU_n` goes low (0) — registered version of read-done; `timer=99` |
| 1030 | 103 | `stateReg` transitions to `READ_DATA(011)`; **`timer=100`**; **assertion FAILS** (`read_miss_eventually_completes=0`); `io_Rst_n=0`, `io_WorkMAU=0` |

### Critical Signal Values at Failure Point (time=1030 ns)

| Signal | Value | Meaning |
|--------|-------|---------|
| `stateReg` | `011` (3) | READ_DATA (not IDLE) |
| `timer` | `1100100` (100) | Timer reached the bound limit |
| `pending` | `1` | Liveness checker still active |
| `read_miss_eventually_completes` | `0` | **Assertion violated** |
| `rReadDoneFromBCU_n` | `0` | BCU read finally completed |
| `io_Rst_n` | `0` | Reset asserted (at failure point) |
| `io_WorkMAU` | `0` | Work signal de-asserted |

## 4. Root Cause Analysis

### Root Cause Category: **Setup Error** — Unconstrained BCU input

### Buggy Logic Location
- **Not a DUT logic bug**. The FSM state transition logic in `controlvis.scala` is functionally correct.
- **Problem area**: The formal testbench has no constraints binding `io_ReadDoneFromBCU_n` to arrive within a bounded time window.

### Description of the Issue

The formal verification tool's counterexample demonstrates the following scenario:

1. **FSM enters READ_MISS correctly**: At time 20, `stateReg` transitions from `IDLE` to `READ_MISS(010)` because `rWorkMAU=1`, `rAccessMode=0` (read), `rValid=1`, `rMatch=0` (miss). This is correct behavior.

2. **BCU read-done signal stays de-asserted for 100 cycles**: The input `io_ReadDoneFromBCU_n` is asserted low (0 = done) at time 0, but at time 10 it de-asserts (1 = not done) and stays at 1 until time 1010. The registered version `rReadDoneFromBCU_n` tracks this with a 10 ns delay.

3. **FSM stuck in READ_MISS**: The READ_MISS state transition logic is:
   ```scala
   is(State.READ_MISS) {
     when(!rReadDoneFromBCU_n) { // data is ready
       stateReg := State.READ_DATA // update cache
     }
   }
   ```
   Since `rReadDoneFromBCU_n` stays high (not done), the FSM cannot exit READ_MISS.

4. **BCU response arrives too late**: At time 1010, `io_ReadDoneFromBCU_n` goes low. At time 1020, `rReadDoneFromBCU_n` goes low. At time 1030, the FSM transitions to `READ_DATA`. However, by this time the liveness checker's timer has reached 100 cycles — the maximum allowed bound. At time 1030, the assertion check `(timer + 1) < 101` evaluates to `101 < 101 = false`, causing the assertion failure.

5. **Extra cycle through READ_DATA**: The FSM requires an additional cycle in `READ_DATA` before reaching `IDLE` (`READ_DATA → IDLE` unconditionally). Since the BCU took 100 cycles, the FSM cannot reach IDLE within the 100-cycle bound even if the BCU responded at the last possible moment.

### Evidence from Waveform

The waveform trace clearly shows:

- **`stateReg` stays at READ_MISS(010)** from time 20 through time 1020 (100 cycles)
- **`rReadDoneFromBCU_n` stays high (1)** from time 20 through time 1010 — preventing the FSM from advancing
- **`timer` counts from 0 to 100** from time 30 to time 1030
- **`stateReg` only reaches READ_DATA(011)** at time 1030, when the timer is already at 100
- **`read_miss_eventually_completes` transitions to 0** at time 1030

### Why This is a Setup Error

The DUT logic is correct — the state machine properly implements READ_MISS → READ_DATA → IDLE transitions. The assertion correctly checks the bounded liveness property. The failure occurs because:

1. **No input constraint on BCU response time**: The formal environment does not include any assumption or constraint limiting how long `io_ReadDoneFromBCU_n` can remain de-asserted (high). Without such a constraint, the formal solver can arbitrarily delay the BCU read-done signal to force an assertion violation.

2. **Unrealistic stimulus**: In a real hardware system, the BCU would respond within a bounded number of cycles. A 100-cycle BCU delay is unrealistic without a corresponding environmental constraint.

3. **Reset at failure point**: The counterexample also asserts `io_Rst_n=0` and `io_WorkMAU=0` at time 1030, which appears to be the formal solver's way of terminating the counterexample trace after the assertion fails.

### Recommended Fix

Add an input constraint (assumption) to the formal verification environment that bounds the BCU read-done response time. For example, in the Chisel source, add:

```scala
// Constrain BCU to respond within a reasonable timeframe
fvAssume(RegNext(io_ReadDoneFromBCU_n) === io_ReadDoneFromBCU_n || 
         !io_ReadDoneFromBCU_n, "bcu_read_done_eventually_arrives")
```

Or a more targeted assumption that the read-done signal must arrive within some bound after a read request is made. Alternatively, if the 100-cycle bound in the assertion is considered a design requirement (not just a verification bound), then the design itself may need a timeout/watchdog mechanism to handle BCU stalls — but that would be a design enhancement, not a bug fix.
