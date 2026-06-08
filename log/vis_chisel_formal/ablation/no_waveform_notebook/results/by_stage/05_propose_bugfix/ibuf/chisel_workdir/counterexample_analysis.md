# Counterexample Analysis Report: `slot0_forward_progress`

## 1. Verification Environment

- **Top Module**: `iqc` (Instruction Queue Controller)
- **Source File**: `iqc.scala` (202 lines)
- **Design Description**: An instruction queue controller with 3 slots (0, 1, 2). Instructions are loaded from 2 dispatch ports, stored in queue slots, and issued to 2 execution units. The design tracks slot validity, instruction age (for ordering), and manages load/issue arbitration.
- **Key Components**:
  - `valid[2:0]` — which slots are occupied
  - `qAge[2:0]` — relative age ranking among occupied slots
  - Load logic — allocates incoming instructions to free slots
  - Issue logic — selects instructions for execution units 0 and 1
  - Forward progress timers — detect stalled instructions

## 2. Violated Assertion

- **Assertion Name**: `slot0_forward_progress`
- **Waveform File**: `iqc.slot0_forward_progress.fst`
- **Code Snippet** (iqc.scala, lines 182–190):
  ```scala
  val issueTimer0 = RegInit(0.U(4.W))
  when(io.flush(0)) {
      issueTimer0 := 0.U
  } .elsewhen(valid(0) && io.opsReady(0) && !(issue0_0 || issue1_0)) {
      issueTimer0 := issueTimer0 + 1.U
  } .otherwise {
      issueTimer0 := 0.U
  }
  AssertProperty(issueTimer0 < 10.U, "slot0_forward_progress")
  ```
- **Property Description**: If slot 0 is valid, its operands are ready, and it is not currently being issued to any execution unit, then it should issue within 10 cycles. A counter (`issueTimer0`) counts consecutive cycles in this state; if it reaches 10, the assertion fires.
- **File Location**: `iqc.scala`, line 190

## 3. Waveform Information

- **Waveform Path**: `verilog/extra_bench/ibuf/iqc.slot0_forward_progress.fst`
- **Duration**: 120 ns (12 cycles)
- **Failure Time**: 110 ns (cycle 11)
- **Key Time Points**:
  | Time (ns) | Event |
  |-----------|-------|
  | 0         | `io_iqLoads = 10` (disp port 1 valid), `load0_1 = 1`, slot 0 is loaded |
  | 10        | `valid(0)` becomes 1 (slot 0 occupied), `io_opsReady = 001` (opsReady(0)=1), `io_iqLoads = 00` |
  | 20        | `issueTimer0 = 1` — starts counting because slot 0 is valid, ops are ready, but no issue |
  | 30–100    | `issueTimer0` increments 2→3→4→5→6→7→8→9 |
  | 110       | `issueTimer0 = 10` (0xA), assertion `slot0_forward_progress` goes to 0 (fires) |

- **Critical Signal Values at Failure**:
  - `iqc.issueTimer0 [3:0]` = `1010` (10)
  - `iqc.io_exeReady [1:0]` = `00` (both execution units NOT ready — **root cause**)
  - `iqc.io_opsReady [2:0]` = `001` (opsReady(0) = 1)
  - `iqc.valid [2:0]` = `111` (all slots occupied)
  - `iqc.io_flush [2:0]` = `000` (no flush)
  - `iqc.io_issue0 [2:0]` = `000` (no issue to unit 0)
  - `iqc.io_issue1 [2:0]` = `000` (no issue to unit 1)
  - `iqc.issue0_0` = `0`, `iqc.issue1_0` = `0` (slot 0 never issues)

## 4. Root Cause Analysis

### Root Cause Category: **Setup Issue** (Incorrect Top Module / Test Harness Configuration)

#### Detailed Analysis

The assertion failure is caused by the **`io_exeReady` input being stuck at `00` for the entire 120 ns waveform**. Both execution units are never ready to accept an instruction.

**Why this causes the assertion to fail:**

1. **Issue logic dependency on `exeReady`**: Both issue signals for slot 0 require their respective `exeReady` bit to be high:
   - `issue0_0 = io.exeReady(0) & io.opsReady(0) & valid(0) & ...` (line 47)
   - `issue1_0 = io.exeReady(1) & io.opsReady(0) & valid(0) & ...` (line 55)
   
   Since `io.exeReady(0) = 0` and `io.exeReady(1) = 0` at all times, both `issue0_0` and `issue1_0` are permanently `0`.

2. **Timer never resets**: The forward progress timer `issueTimer0` increments when `valid(0) && io.opsReady(0) && !(issue0_0 || issue1_0)` is true. Since `issue0_0` and `issue1_0` can never be 1 (due to exeReady being 0), and slot 0 stays valid with opsReady=1, the timer increments every cycle.

3. **Timer reaches 10**: The timer increments from 0 at time 0 to 10 at time 110, triggering the assertion `issueTimer0 < 10.U`.

**Why this is a setup issue, not a DUT bug:**

The `exeReady` input represents whether the execution pipeline is ready to accept a new instruction. In a realistic hardware environment, execution units periodically assert `exeReady` when they can accept work. The DUT's issue logic correctly respects `exeReady` — it will not issue when the execution unit is not ready. This is correct behavior.

The problem is that the **formal test harness does not constrain `exeReady`** to ever be high. Without a constraint like `s_eventually io.exeReady(0) || io.exeReady(1)` or a periodic assertion of exeReady, the formal solver can trivially set `exeReady = 00` forever, creating a counterexample that is unrealistic but valid from the solver's perspective.

**Evidence from waveform:**
```
iqc.io_exeReady [1:0]: 00 → (no transitions in entire 0–120 ns range)
```

All other signals in the design behave correctly — the load logic properly loads slot 0, the valid bit correctly tracks occupancy, the issue logic correctly waits for exeReady, and the timer correctly counts stalled cycles. The only issue is the unconstrained exeReady input.

### Possible Fixes

1. **Add constraints on `exeReady` in the test harness**: Constrain `exeReady` to be periodically asserted (e.g., at least once every N cycles, or assert it whenever a slot is valid and ready).

2. **Modify the assertion to account for `exeReady`**: Add `exeReady` to the timer increment condition, e.g.:
   ```scala
   .elsewhen(valid(0) && io.opsReady(0) && io.exeReady.orR && !(issue0_0 || issue1_0)) {
   ```
   This would only count cycles where the execution unit is actually ready to accept work.
