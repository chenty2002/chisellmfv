# Counterexample Analysis Report: `idle_stays_without_work`

## 1. Verification Environment

### Top Module
- **Module name**: `controlvis` (in package `llmverify`)
- **Source file**: `chisel/extra_bench/silver-mau/controlvis.scala`

### Key Components
| Component | Type | Description |
|---|---|---|
| `stateReg [2:0]` | `RegInit(State.IDLE)` | Main FSM state register |
| `prevStateReg [2:0]` | `RegNext(stateReg)` | Previous cycle state (for assertion checks) |
| `rWorkMAU` | `RegNext(io.WorkMAU, false.B)` | Registered work input (1-cycle delayed) |
| `rAccessMode [1:0]` | `RegNext(io.AccessMode, 0.U)` | Registered access mode input |
| `rValid` | `RegNext(io.Valid, false.B)` | Registered valid input |
| `rMatch` | `RegNext(io.Match, false.B)` | Registered match input |
| `rReadDoneFromBCU_n` | `RegNext(io.ReadDoneFromBCU_n, false.B)` | Registered read done input |
| `rWriteDoneFromBCU_n` | `RegNext(io.WriteDoneFromBCU_n, false.B)` | Registered write done input |
| `vector [5:0]` | `RegInit("b011001".U(6.W))` | Combinatorial output vector mapped to control signals |

### FSM States
| State | Encoding | Description |
|---|---|---|
| IDLE | `000` | Idle, waiting for work |
| READ_HIT | `001` | Read hit (completes in 1 cycle back to IDLE) |
| READ_MISS | `010` | Read miss (waits for read done) |
| READ_DATA | `011` | Read data (completes in 1 cycle back to IDLE) |
| WRITE_HIT | `100` | Write hit (waits for write done) |
| WRITE_MISS | `101` | Write miss (waits for write done) |

### Input-Output Connections
- **Inputs**: `io.Rst_n` (reset, active low), `io.WorkMAU`, `io.AccessMode`, `io.Match`, `io.Valid`, `io.ReadDoneFromBCU_n`, `io.WriteDoneFromBCU_n`
- **Outputs**: `io.Write`, `io.BCURequest_n`, `io.BCUWriteRequest_n`, `io.BCUDataOE`, `io.CacheDataSelect`, `io.MAUNotReady_n`, `io.State`, `io.Vector`

---

## 2. Violated Assertion

### Full Name
`controlvis.idle_stays_without_work`

### Code Snippet (lines 152–155)
```scala
// Safety 4: IDLE state must remain stable when no work is requested
fvAssert(
  !(prevStateReg === State.IDLE && io.Rst_n && !rWorkMAU) || (stateReg === State.IDLE),
  "idle_stays_without_work"
)
```

### Natural Language Description
If in the **previous cycle** the FSM was in the **IDLE** state, the **reset is deasserted** (io.Rst_n is high), and **no work is currently requested** (rWorkMAU is low), then the **current state must remain IDLE**. In other words, the FSM should never spontaneously leave IDLE when there is no work to process.

### File Location
- **File**: `controlvis.scala`
- **Lines**: 152–155

---

## 3. Waveform Information

### Waveform File
`verilog/extra_bench/silver-mau/controlvis.idle_stays_without_work.fst`

### Time Range and Key Events (nanoseconds)

| Time (ns) | Event | State |
|---|---|---|
| **0** | **Posedge clock**. Reset active (`io_Rst_n=0`), `io.WorkMAU=1`. All registers initialized. | IDLE (000) |
| **5** | Negedge clock. | IDLE (000) |
| **10** | **Posedge clock**. Reset deasserts (`io_Rst_n 0→1`). `io.WorkMAU 1→0`. `io.AccessMode 00→01`. **rWorkMAU captures old io.WorkMAU=1**. Reset block fires, keeping stateReg=IDLE. | IDLE (000) |
| **15** | Negedge clock. | IDLE (000) |
| **20** | **Posedge clock**. **ASSERTION FAILS HERE**. | **READ_MISS (010)** |
| **25** | Negedge clock. | READ_MISS (010) |
| **30** | End of trace. | READ_MISS (010) |

### Critical Signal Values at Failure Point (time = 20 ns)

| Signal | Value | Notes |
|---|---|---|
| `stateReg [2:0]` | `010` (READ_MISS) | Violates assertion requirement (should be IDLE) |
| `prevStateReg [2:0]` | `000` (IDLE) | Previous state was IDLE ✓ |
| `io_Rst_n` | `1` | Reset deasserted ✓ |
| `rWorkMAU` | `0` | No work registered this cycle (POST-update value) |
| `io.WorkMAU` | `0` | No work input |
| `rAccessMode [1:0]` | `01` | Write mode registered |
| `io.AccessMode [1:0]` | `01` | Write mode input |
| `rValid` | `0` | No valid access |
| `rMatch` | `0` | No match |
| `rReadDoneFromBCU_n` | `1` | Read not done |
| `rWriteDoneFromBCU_n` | `0` | Write done (active low, so done is asserted) |
| `io_Vector [5:0]` | `011001` | IDLE vector output |

---

## 4. Root Cause Analysis

### Buggy Code Location
- **File**: `controlvis.scala`
- **Line 60**: `when(rWorkMAU)` inside the IDLE state case of the state machine
- **Line 44**: `val rWorkMAU = RegNext(io.WorkMAU, false.B)` — the registered work input

### Bug Type: **Bug in the Original Design (DUT Bug)**

#### Detailed Description

The state machine at line 59–74 uses the **registered** input `rWorkMAU` (one-cycle delayed) to decide whether work is requested and whether to transition out of IDLE:

```scala
is(State.IDLE) {
  when(rWorkMAU) {          // ← USES REGISTERED (delayed) VERSION
    when(rAccessMode(0)) { ... }
    .elsewhen(!rAccessMode(0)) {
      when(rValid && rMatch) { stateReg := State.READ_HIT }
      .otherwise { stateReg := State.READ_MISS }
    }
  }
}
```

However, the assertion `idle_stays_without_work` at line 152–155 checks `!rWorkMAU` (the **same** registered signal) against `stateReg`, expecting them to be coherent:

```scala
!(prevStateReg === State.IDLE && io.Rst_n && !rWorkMAU) || (stateReg === State.IDLE)
```

#### Root Cause Mechanism (Step by Step, with Waveform Evidence)

**Step 1 — Time 0 to 10: Reset is active, io.WorkMAU is high**
At time 0 (first clock edge), `io_Rst_n=0` (reset active). The `when(!io.Rst_n)` block fires, holding `stateReg=IDLE`. `io.WorkMAU=1` throughout this period.

**Step 2 — Time 10: Reset deasserts simultaneously with WorkMAU→0**
At the clock edge at time 10:
- `io_Rst_n` transitions from 0→1
- `io.WorkMAU` transitions from 1→0
- `rWorkMAU = RegNext(io.WorkMAU, false.B)` captures `io.WorkMAU`'s **old value = 1** (before the transition)
- The `when(!io.Rst_n)` reset block fires (because `!io.Rst_n` evaluates using the pre-edge value of 0), keeping `stateReg=IDLE`

**Evidence**: `rWorkMAU` = 1 at time 10, 11, 15, 19; `io.WorkMAU` = 0 at time 10, 11, 15, 19, 20.

**Step 3 — Time 20: State machine uses stale rWorkMAU=1, assertion checks post-update rWorkMAU=0**

At the clock edge at time 20:
- **State machine evaluation** (line 59–74): Reads `rWorkMAU`. Since it is in IDLE and `rWorkMAU=1` (the OLD, pre-update value captured at time 10), it transitions to READ_MISS (via lines 67–72: read mode, not valid, not match → read miss).
- **rWorkMAU update**: Simultaneously, `rWorkMAU` captures the new `io.WorkMAU=0` (which has been 0 since time 10). So `rWorkMAU` becomes 0 after the edge.
- **Assertion check** (line 152–155): Reads:
  - `prevStateReg === State.IDLE` → **true** (pre-update value was IDLE)
  - `io_Rst_n` → **true** (reset is high)
  - `!rWorkMAU` → **true** (rWorkMAU is now 0, post-update)
  - Antecedent satisfied → consequent requires `stateReg === State.IDLE`
  - `stateReg` = READ_MISS (010) → **consequent fails!**

#### Why This Is a DUT Bug

The fundamental issue: **The state machine and the assertion disagree on which version of `rWorkMAU` to use**.

- The **state machine** uses `rWorkMAU` **before** it updates at the clock edge (the "pre-edge" value from the previous cycle). At time 20, this pre-edge value was 1 (captured at time 10), so it transitions to READ_MISS.
- The **assertion** uses `rWorkMAU` **after** it updates at the clock edge (the "post-edge" value for the current cycle). At time 20, this post-edge value is 0 (captured from io.WorkMAU which has been 0 since time 10).

This creates an inconsistency: the state machine thinks there IS work (rWorkMAU=1, pre-edge), but the assertion thinks there IS NO work (rWorkMAU=0, post-edge). The state machine transitions anyway, and the assertion flags it as a violation.

#### Root Cause Summary

The root cause is that the state machine was not designed to handle the case where `rWorkMAU` captures a **stale** work request due to the simultaneous deassertion of reset and deassertion of io.WorkMAU at time 10. The registered input `rWorkMAU` carries the old value (1) forward by one cycle, causing a spurious state transition at time 20 even though `io.WorkMAU` has been 0 for the entire preceding cycle.

### Recommended Fix

**Option A (Simplest — Use io.WorkMAU directly in IDLE transition)**:
Change line 60 from `when(rWorkMAU)` to `when(io.WorkMAU)`. This makes the state machine react to the current-cycle work input rather than a potentially stale registered version. The registered version `rWorkMAU` is still needed for other purposes (e.g., downstream logic that requires a synchronous version), but the state transition decision from IDLE should use the current input.

**Option B (Reset rWorkMAU during reset)**:
Redefine `rWorkMAU` to also reset under the `when(!io.Rst_n)` block, ensuring that when reset deasserts, `rWorkMAU` is 0 rather than whatever stale value it might have captured. However, this is harder with the current `RegNext` usage pattern.

**Option C (Pipeline the state transition)**:
Add an explicit condition that prevents the state machine from transitioning from IDLE in the first cycle after reset deassertion. This would be a more conservative approach.

The recommended fix is **Option A**, as it makes the FSM respond to the actual current input, which is the correct behavior for a control state machine.
