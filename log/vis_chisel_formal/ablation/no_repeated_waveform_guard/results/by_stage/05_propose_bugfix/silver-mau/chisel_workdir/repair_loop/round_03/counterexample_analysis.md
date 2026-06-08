# Counterexample Analysis: `read_miss_to_read_data_on_done`

## 1. Verification Environment

- **Top Module**: `controlvis`
- **Source File**: `controlvis.scala` (200 lines)
- **Design Under Test**: A cache controller state machine with 6 states (IDLE, READ_HIT, READ_MISS, READ_DATA, WRITE_HIT, WRITE_MISS) that manages cache/BCU interactions.
- **Key Components**:
  - `stateReg` (3-bit register): FSM state register
  - Input synchronization registers: `rRst_n`, `rWorkMAU`, `rAccessMode`, `rMatch`, `rValid`, `rReadDoneFromBCU_n`, `rWriteDoneFromBCU_n`
  - `io.Rst_n`: External reset (active low)

## 2. Violated Assertion

- **Assertion Name**: `read_miss_to_read_data_on_done`
- **Waveform File**: `controlvis.read_miss_to_read_data_on_done.fst`
- **Source Location**: `controlvis.scala`, lines 164–166

### Code Snippet
```scala
{
    val ant = RegNext(stateReg === State.READ_MISS && !rReadDoneFromBCU_n && notChaos, false.B)
    fvAssert(!ant || (stateReg === State.READ_DATA) || !io.Rst_n, "read_miss_to_read_data_on_done")
}
```

### Property Description
If the state machine was in the `READ_MISS` state with the read-done signal asserted (`!rReadDoneFromBCU_n = 1`, active low) and the system is not in a chaotic/initialization state (`notChaos = 1`), then in the **next cycle**, the state machine must be in `READ_DATA`, **unless** the reset (`io.Rst_n`) is currently active (low).

## 3. Waveform Information

- **Waveform File**: `verilog/extra_bench/silver-mau/controlvis.read_miss_to_read_data_on_done.fst`
- **Time Range**: 0 ns → 40 ns (4 cycles)
- **Failure Time**: 30 ns (3rd clock cycle)

### Key Signal Values at Failure (time = 30 ns)

| Signal | Value | Meaning |
|--------|-------|---------|
| `controlvis.ant_2` | 1 | Antecedent (delayed condition) asserted |
| `controlvis.stateReg [2:0]` | 000 (`IDLE`) | **Not READ_DATA (011)** — causing failure |
| `controlvis.io_Rst_n` | 1 | Reset is NOT active — guard fails |
| `controlvis.rReadDoneFromBCU_n` | 0 | Data ready signal still asserted |
| `controlvis.read_miss_to_read_data_on_done` | 0 | **Assertion failed** (dropped 1→0 at 30ns) |

### Full Signal Timeline

| Time | stateReg | rReadDoneFromBCU_n | io_Rst_n | ant_2 | Event |
|------|----------|-------------------|----------|-------|-------|
| 0 | IDLE | 0 | 1 | 0 | Initial state |
| 10 | IDLE | 1 | 1 | 0 | io_ReadDoneFromBCU_n goes 1→0 (posedge) |
| 20 | **READ_MISS** | **0** | **0** | 0 | stateReg transitions IDLE→READ_MISS; io_Rst_n goes low; ant_2 condition becomes TRUE |
| 30 | **IDLE** | 0 | **1** | **1** | stateReg reset to IDLE (io_Rst_n=0 caused reset); assertion: 0 \|\| 0 \|\| 0 = **FAIL** |

## 4. Root Cause Analysis

### Classification: **Assertion Error**

The design behavior is **correct** — when `io.Rst_n` goes low, the state machine correctly resets to `IDLE`. The problem is that the assertion's guard against reset checks the **wrong cycle's** reset value.

### Detailed Sequence

1. **Cycle 1** (time 10–20): State machine is in `IDLE`. At the posedge at time 10, `io_ReadDoneFromBCU_n` is sampled (value=1 before its transition to 0 at the same time point), so `rReadDoneFromBCU_n` becomes 1. The FSM logic computes next state from `IDLE`: `rWorkMAU=1`, `rAccessMode(0)=0` (read), `rValid=0` or `rMatch=0` → transition to `READ_MISS`. The state register captures `READ_MISS` at time 20.

2. **Cycle 2** (time 20–30): State machine is now in `READ_MISS`. During this cycle:
   - `io.Rst_n` transitions from 1→0 at time 20 (reset asserted).
   - `rReadDoneFromBCU_n` transitions from 1→0 at time 20 (read done sampled).
   - The antecedent fires: `stateReg === READ_MISS && !rReadDoneFromBCU_n && notChaos` = TRUE.
   - This value will be registered into `ant_2` at the next posedge (time 30).
   - The next-state logic computes: `when(!io.Rst_n)` is TRUE (since `io.Rst_n=0`), forcing next state to `IDLE`.

3. **Cycle 3** (time 30–40): The posedge at time 30 captures:
   - `stateReg` = `IDLE` (due to reset override from previous cycle)
   - `ant_2` = 1 (antecedent from time 20 registered)
   - `io.Rst_n` has returned to 1 (reset released)

4. **Assertion Check at time 30**:
   - `!ant_2` = `!1` = 0
   - `stateReg === State.READ_DATA` = `000 === 011` = false
   - `!io.Rst_n` = `!1` = 0
   - Condition: `0 || false || false` = **false** → **ASSERTION FAILS**

### The Bug

The assertion guard `!io.Rst_n` checks `io.Rst_n` at the **consequent evaluation time** (time 30). However, the reset that interfered with the `READ_MISS → READ_DATA` transition occurred in the **previous cycle** (time 20–30), when `io.Rst_n = 0`. By time 30, the reset has already been released (`io.Rst_n = 1`), so the guard does not deactivate the assertion.

The guard `!io.Rst_n` at line 166 should be synchronized to the same cycle as the antecedent (`ant`), not the current cycle. When `io.Rst_n = 0` at time 20, it correctly resets the state machine to `IDLE`, but also enables the antecedent condition (since `ant_2` reg was computed at that time). The assertion then incorrectly fires one cycle later because the guard checks the wrong time point.

### Fix

The `!io.Rst_n` in the `fvAssert` consequent should be registered as well, so it refers to the cycle when the antecedent was sampled:

```scala
{
    val ant = RegNext(stateReg === State.READ_MISS && !rReadDoneFromBCU_n && notChaos, false.B)
    val wasReset = RegNext(!io.Rst_n, false.B)
    fvAssert(!ant || (stateReg === State.READ_DATA) || wasReset, "read_miss_to_read_data_on_done")
}
```

With this fix, at time 30:
- `wasReset` = `RegNext(!io.Rst_n)` = `!io_Rst_n at time 20` = `!0` = 1
- Assertion: `!1 || (stateReg===READ_DATA) || 1` = `0 || false || 1` = **true** ✓

### Design Correctness

The design (`controlvis`) itself is correct. The state machine properly resets to `IDLE` when `io.Rst_n` goes low. The issue is purely a verification artifact — an assertion that does not properly handle the one-cycle delay between the reset assertion and the assertion check.

### Error Type: **Assertion Error**
