# Counterexample Analysis Report: branchPredictionBuffer.prediction_stable_when_stalled

## 1. Verification Environment

| Item | Description |
|------|-------------|
| **Top Module** | `branchPredictionBuffer` (in package `llmverify`) |
| **Source File** | `bpbs.scala` (138 lines) |
| **Design Under Test** | A branch prediction buffer with 4 state banks (2-bit saturating counters), indexed by instruction address and buffer address. The design supports a stall input to freeze prediction and state-update inputs for training the predictor. |
| **Key Components** | 4 state bank register arrays (`state_bank0`–`state_bank3`, each with 4 entries of 2-bit saturating counters), a `prediction` register (4-bit), and combinational update/prediction logic. |
| **Formal Tool** | JasperGold via Chisel-FV |

## 2. Violated Assertion

| Item | Value |
|------|-------|
| **Assertion Name** | `prediction_stable_when_stalled` |
| **Waveform File** | `branchPredictionBuffer.prediction_stable_when_stalled.fst` |

### Code Snippet (from `bpbs.scala`)

```scala
// === Around line 110 ===
assertStableWhen(io.stall, io.prediction.asUInt, "prediction_stable_when_stalled")
```

### Property Description
The assertion `assertStableWhen(io.stall, io.prediction.asUInt)` checks that **whenever `io.stall` is asserted (high), the value of `io.prediction` remains stable** — i.e., it does not change from its value in the previous cycle. This is a safety property ensuring that the prediction output is frozen during stalled cycles.

### File Location
- **File**: `bpbs.scala`
- **Line**: ~110 (the assertStableWhen call)

## 3. Waveform Information

| Item | Value |
|------|-------|
| **Waveform File** | `verilog/extra_bench/bpb/branchPredictionBuffer.prediction_stable_when_stalled.fst` |
| **Total Duration** | 30 ns (3 clock cycles; clock period = 10 ns) |
| **Clock Edges** | Posedges at 0 ns, 10 ns, 20 ns |
| **Failure Time** | **20 ns** (the assertion signal `prediction_stable_when_stalled` transitions from 1→0 at this time) |

### Key Signal Values

| Time (ns) | io_stall | io_prediction [3:0] | io_inst_addr [1:0] | state_bank3_0 [1:0] | state_bank2_1 [1:0] | Event |
|-----------|----------|---------------------|---------------------|---------------------|---------------------|-------|
| 0 | 0 | 0000 | 00 | 01 | 01 | Initial state, posedge |
| 5 | 0 | 0000 | 00 | 01 | 01 | Mid-cycle 0 |
| **10** | **0** | **0000** | **00** | **10** | **01** | **Posedge cycle 1; state update from cycle 0 takes effect** |
| 15 | 0 | 0000 | 00 | 10 | 01 | Mid-cycle 1 |
| **20** | **1** | **1000** | **00** | **10** | **00** | **❌ FAIL: stall=1, prediction changed from 0000→1000** |
| 25 | 1 | 1000 | 00 | 10 | 00 | Mid-cycle 2 |

**Failure Observation**: At time 20 ns, `io_stall` transitions to 1 while `io_prediction` simultaneously changes from `0000` to `1000`. The assertion fires because prediction changed during a stalled cycle.

## 4. Root Cause Analysis

### Buggy Code Location

**File**: `bpbs.scala`, lines 47–59
**Module**: `branchPredictionBuffer`

```scala
44:  // Prediction register
45:  val prediction = RegInit(0.U(4.W))
46:
47:  // Prediction logic - read from all 4 banks when not stalled
48:  when (!io.stall) {
49:     // Construct the entire 4-bit prediction value at once
50:     val pred3 = Mux(state_bank3(io.inst_addr) > 1.U, 1.U, 0.U)
51:     val pred2 = Mux(state_bank2(io.inst_addr) > 1.U, 1.U, 0.U)
52:     val pred1 = Mux(state_bank1(io.inst_addr) > 1.U, 1.U, 0.U)
53:     val pred0 = Mux(state_bank0(io.inst_addr) > 1.U, 1.U, 0.U)
54:     prediction := Cat(pred3, pred2, pred1, pred0)
55:  }
56:
57:  // Connect prediction to output
58:  io.prediction := prediction
```

### Root Cause Category: **Bug in the Original Design**

### Description of the Bug

The bug is a **one-cycle-lag timing issue** in how the prediction register interacts with the stall signal.

**How the design works:**
1. The `prediction` register is updated at each clock posedge **only when `!io.stall` is true** (line 48).
2. The register computes a **new value** based on the current state banks and `io.inst_addr`.
3. The new value appears on `io.prediction` (combinatorially connected via line 58) **one cycle later** (standard register behavior).

**The problem:**
When `io_stall` transitions from 0 to 1 at time 20 ns (posedge cycle 2), the `prediction` register holds the value computed during cycle 1 (when stall was still 0). This new value (`1000`) becomes visible at cycle 2 **at the same time** that `io_stall` becomes high. The assertion sees:

- **Cycle 1** (time 10): `io_stall=0`, `io_prediction=0000`
- **Cycle 2** (time 20): `io_stall=1`, `io_prediction=1000`

Since `io_prediction` changed (`0000` → `1000`) while `io_stall=1`, the assertion fires.

### Step-by-Step Execution Trace

**Cycle 0 (posedge at 0 ns, updates visible at 10 ns):**
- `io_stall = 0` → `!io_stall = true` → prediction updates
- `io_branch_result = 1` (taken), `io_buffer_offset = 11` → bank 3, `io_buffer_addr = 00`
- `state_bank3(0)` increments from `01` → `10`
- **State bank update takes effect** (visible at time 10)
- Prediction computation uses **old** state banks (before update): all at `01` → all `pred` bits = 0
- `prediction` ← `0000` (same as before, no visible change)

**Cycle 1 (posedge at 10 ns, updates visible at 20 ns):**
- `io_stall = 0` → `!io_stall = true` → prediction updates
- `io_branch_result = 0` (not taken), `io_buffer_offset = 10` → bank 2, `io_buffer_addr = 01`
- `state_bank2(1)` decrements from `01` → `00`
- **State bank update takes effect** (visible at time 20)
- Prediction computation uses **new** state banks (after cycle 0 update): `state_bank3(0) = 10 > 1` → `pred3 = 1`
- `prediction` ← `1000`

**Cycle 2 (posedge at 20 ns — ASSERTION CHECK):**
- `io_stall = 1` → `!io_stall = false` → prediction holds
- `io_prediction = 1000` (value computed in cycle 1, now visible)
- **Assertion check**: `io_stall=1` AND `io_prediction` changed from previous value (`0000` at time 10) → **FAIL**

### Why This Is a Real Design Bug

The intent of the stall signal is to **freeze the prediction output** during stalled cycles. However, the current design allows the prediction value computed in the last non-stalled cycle to "leak through" into the first stalled cycle. This happens because:

1. The `when (!io.stall)` guard prevents the register from being **overwritten** during stall, but
2. The register already received its new value at the **previous** posedge (when stall was 0), and
3. That new value becomes observable on `io.prediction` **exactly** when stall goes high.

### Proposed Fix

The fix is to **separate the combinatorial prediction computation from the registered prediction** so that `io.prediction` uses the frozen register value when stalled:

```scala
// Move prediction computation outside the when block
val pred3 = Mux(state_bank3(io.inst_addr) > 1.U, 1.U, 0.U)
val pred2 = Mux(state_bank2(io.inst_addr) > 1.U, 1.U, 0.U)
val pred1 = Mux(state_bank1(io.inst_addr) > 1.U, 1.U, 0.U)
val pred0 = Mux(state_bank0(io.inst_addr) > 1.U, 1.U, 0.U)
val next_prediction = Cat(pred3, pred2, pred1, pred0)

// Register the prediction only when not stalled
val prediction = RegInit(0.U(4.W))
when (!io.stall) {
  prediction := next_prediction
}

// Output: frozen register value when stalled, combinational result otherwise
io.prediction := Mux(io.stall, prediction, next_prediction)
```

This ensures:
- **When stalled**: `io.prediction` outputs the frozen register value (the last prediction from before stall)
- **When not stalled**: `io.prediction` outputs the fresh combinational result computed from current state banks
- The `prediction` register still only updates when not stalled, preserving the original update semantics
