# Counterexample Analysis: `controlvis.read_hit_to_idle`

## 1. Verification Environment

- **Top module**: `controlvis` (with Formal trait)
- **Source file**: `controlvis.scala` (180 lines)
- **Generated Verilog**: `generated/controlvis.sv`
- **Waveform file**: `verilog/extra_bench/silver-mau/controlvis.read_hit_to_idle.fst`
- **Design under test**: A 6-state finite state machine (IDLE, READ_HIT, READ_MISS, READ_DATA, WRITE_HIT, WRITE_MISS) with registered inputs and combinatorial output vector.

### Key components
- `stateReg` (3-bit): State register for the FSM
- `rWorkMAU`, `rAccessMode`, `rMatch`, `rValid`, `rReadDoneFromBCU_n`, `rWriteDoneFromBCU_n`: Synchronized input registers (RegNext)
- `vector` (6-bit): Output vector that drives `io_Write`, `io_BCURequest_n`, etc.
- `resetCounter`: Module tracking time since reset and providing `notChaos` signal
- `hasBeenResetReg`/`hasBeenReset`: Assertion disable signal from Chisel LTL

## 2. Violated Assertion

- **Full assertion name** (from waveform filename): `read_hit_to_idle`
- **Original Chisel source** (controlvis.scala, line 143-144):
  ```scala
  assertNextStepWhen(stateReg === State.READ_HIT, stateReg === State.IDLE,
    "read_hit_to_idle")
  ```
- **Intended property**: Whenever `stateReg === State.READ_HIT` (001) at cycle N, then `stateReg === State.IDLE` (000) at cycle N+1.
- **Generated Verilog** (controlvis.sv, line ~82, assertion label `read_hit_to_idle`):
  ```systemverilog
  read_hit_to_idle:
      assert property (@(posedge clock) disable iff (~hasBeenReset) ~(|stateReg));
  ```

### CRITICAL: Assertion Compilation Bug

The generated Verilog assertion **does not match** the Chisel source intent. Instead of implementing the sequential implication *"if READ_HIT at cycle N, then IDLE at cycle N+1"*, it checks **`~(|stateReg)`** — meaning **"stateReg must be all-zeros (IDLE) at every single cycle"**. This is an unconditional assertion that `stateReg === 3'b000` at all times, which is far too strong and does not reflect the intended property.

The same compilation bug affects all five `assertNextStepWhen` assertions in the design:

| Original Chisel Intent | Generated Verilog Check |
|---|---|
| `READ_HIT → IDLE` | `~(|stateReg)` → always IDLE |
| `READ_DATA → IDLE` | `~(|stateReg)` → always IDLE |
| `READ_MISS && !rReadDoneFromBCU_n → READ_DATA` | `stateReg == 3'h3` → always READ_DATA |
| `WRITE_HIT && !rWriteDoneFromBCU_n → IDLE` | `~(|stateReg)` → always IDLE |
| `WRITE_MISS && !rWriteDoneFromBCU_n → IDLE` | `~(|stateReg)` → always IDLE |

The root cause is that the `delayedBool` register (a 1-cycle delay pipe used to capture the antecedent condition) AND the `when` guard around the assertion are both lost during Chisel-to-Verilog compilation. The `AssertProperty` inside `when(delayedCond)` should generate something like:
```systemverilog
assert property (@(posedge clock) disable iff (~hasBeenReset)
                 delayedCond |-> (stateReg === IDLE));
```
but instead the entire sequential structure is flattened to just the bare consequent.

## 3. Waveform Information

- **Waveform file**: `verilog/extra_bench/silver-mau/controlvis.read_hit_to_idle.fst`
- **Time range**: 0 ns to 30 ns (3 clock cycles)
- **Clock**: posedge at times 0, 10, 20 ns

### Key signal traces

| Time (ns) | clock | io_Rst_n | stateReg | io_State | read_hit_to_idle | Event |
|-----------|-------|----------|----------|----------|-----------------|-------|
| 0 | 1 | 0 | 000 (IDLE) | 000 | 1 | Reset active, stateReg = IDLE |
| 5 | 0 | 0 | 000 | 000 | 1 | |
| 10 | 1 | 1 | 000 (IDLE) | 000 | 1 | Reset released, stateReg stays IDLE |
| 15 | 0 | 1 | 000 | 000 | 1 | |
| 20 | 1 | 1 | 101 (WRITE_MISS) | 101 | **0 (FAIL)** | stateReg transitions to WRITE_MISS |
| 25 | 0 | 1 | 101 | 101 | 0 | |

### Other relevant signals

| Signal | Value at t=0 | Value at t=10 | Value at t=20 |
|--------|-------------|---------------|---------------|
| io_WorkMAU | 1 | 1 | 1 |
| io_AccessMode [1:0] | 01 | 01 | 01 |
| io_Match | 0 | 1 | 1 |
| io_Valid | 0 | 1 | 1 |
| io_ReadDoneFromBCU_n | 1 | 1 | 1 |
| io_WriteDoneFromBCU_n | 1 | 1 | 1 |
| rWorkMAU | 0 | 1 | 1 |
| rAccessMode [1:0] | 00 | 01 | 01 |
| rMatch | 0 | 0 | 1 |
| rValid | 0 | 0 | 1 |
| rReadDoneFromBCU_n | 0 | 0 | 1 |
| rWriteDoneFromBCU_n | 0 | 0 | 1 |
| hasBeenReset | 1 | 1 | 1 |

## 4. Root Cause Analysis

### Root Cause: Assertion Compilation Error (assertion_error)

**The generated Verilog assertion is incorrect.** The Chisel-FV `assertNextStepWhen` method's sequential logic is not properly compiled to SystemVerilog. This falls under the **assertion_error** category — the assertion as checked by the formal tool is wrong.

### Detailed analysis

**Assertion mechanism in Chisel-FV:**
```scala
// Formal.scala line 141-145
def assertNextStepWhen(cond: Bool, asert: Bool, msg: String = ""): Unit = {
    assertAfterNStepWhen(cond, 1, asert, msg)
}
def assertAfterNStepWhen(cond: Bool, n: Int, asert: Bool, msg: String = ""): Unit = {
    when(delayedBool(cond && notChaos, n, sticky = false)) {
      fvAssert(asert, msg)
    }
}
```

The `delayedBool(cond && notChaos, 1, false)` function creates a **1-bit register** that samples the condition on each clock cycle and outputs the previous cycle's value. The `when(delayedSignal) { fvAssert(result) }` should generate: "if cond was true last cycle, assert result this cycle".

**What should have been generated in Verilog:**
```systemverilog
// Expected: A register to delay the condition
reg delayed_cond;
always @(posedge clock) begin
    if (!notChaos) delayed_cond <= 0;
    else delayed_cond <= (stateReg == 3'h1) && notChaos;
end
// Expected: Assertion gated by delayed condition
read_hit_to_idle:
    assert property (@(posedge clock) disable iff (~hasBeenReset)
                     delayed_cond |-> (stateReg == 3'h0));
```

**What was actually generated:**
```systemverilog
read_hit_to_idle:
    assert property (@(posedge clock) disable iff (~hasBeenReset) ~(|stateReg));
```

The **delay register is missing** and the **`when` guard around the assertion is missing**. The assertion checks only the raw consequent `~(|stateReg)` (i.e., `stateReg === IDLE`) at every cycle unconditionally.

### How the counterexample manifests

1. **Time 0-10 ns**: Reset is active (`io_Rst_n = 0`), stateReg is held at IDLE (000). The assertion `~(|stateReg) = ~0 = 1` passes.

2. **Time 10 ns**: Reset released. Inputs show `WorkMAU = 1`, `AccessMode = 01` (write), `Match = 1`, `Valid = 1`. The registered versions (`rWorkMAU, rAccessMode, rMatch, rValid`) capture these values. At time 10, stateReg is still IDLE because the FSM only transitions on the next clock edge.

3. **Time 10-20 ns**: At time 10 (posedge clock), the FSM logic fires:
   - `stateReg = 000 (IDLE)` and `rWorkMAU = 1` (since `WorkMAU` was 1 at time 0, registered at time 10)
   - `rAccessMode = 01`, `rAccessMode(0) = 1` → write mode
   - `rValid && rMatch = 0 && 0 = 0` → not a hit
   - So the transition is: IDLE → WRITE_MISS (101)

   Wait, actually let me recheck. At time 0: `rWorkMAU = 0`, `rAccessMode = 00`, `rMatch = 0`, `rValid = 0`.
   
   At time 10, the registered values are updated from the inputs at the previous clock (time 0):
   - `rWorkMAU <= io_WorkMAU` → at time 0, `io_WorkMAU = 1`, so at time 10, `rWorkMAU = 1`
   - `rAccessMode <= io_AccessMode` → at time 0, `io_AccessMode = 01`, so at time 10, `rAccessMode = 01`
   - `rMatch <= io_Match` → at time 0, `io_Match = 0`, so at time 10, `rMatch = 0`
   - `rValid <= io_Valid` → at time 0, `io_Valid = 0`, so at time 10, `rValid = 0`

   At time 10, the FSM evaluation (posedge clock):
   - `stateReg = IDLE`, `rWorkMAU = 1`, `rAccessMode = 01` (write)
   - `rValid & rMatch = 0 & 0 = 0` → miss
   - So the transition should be: IDLE → WRITE_MISS (101)
   - But wait, stateReg at time 20 is 101, not at time 10. Let me re-check.

   Actually, looking at the Verilog:
   ```systemverilog
   always @(posedge clock) begin
       if (reset) begin ... end
       else begin
           if (io_Rst_n) begin
               if (|stateReg) begin ... end  // state is non-zero
               else if (rWorkMAU) begin      // state is IDLE
                   // transition logic
               end
           end
           else stateReg <= 3'h0;
       end
   end
   ```

   At time 10, the clock transitions from 0→1 (posedge). Looking at the values:
   - At time 10, `reset = 0` (from initial trace), `io_Rst_n = 1`
   - `stateReg` at time 10 = 000 (IDLE), so `|stateReg = 0`
   - `rWorkMAU` at time 10 = 1 → enters the transition logic

   But wait, at time 10, `rWorkMAU` was just registered as 1 (from inputs at time 0). This is the new value being written, so the `rWorkMAU = 1` is the value that was just registered.

   For this posedge at time 10, the `else` block executes (not reset):
   ```
   else begin
       // rWorkMAU <= io_WorkMAU  ->  rWorkMAU = 1 (from io_WorkMAU=1 at time 0)
       // stateReg logic uses the OLD value of rWorkMAU? Or the NEW value?
   ```

   In a blocking vs non-blocking context:
   ```systemverilog
   always @(posedge clock) begin
       // non-blocking assignments all happen simultaneously
       stateReg <= ...; // uses old values
       rWorkMAU <= io_WorkMAU; // samples io_WorkMAU
       ...
   end
   ```

   Since these are non-blocking assignments, all RHS are evaluated using the OLD values (from before the clock edge), and the LHS are updated concurrently. So:
   - The FSM logic uses the OLD `rWorkMAU` (from time 0-10, which was set at some earlier time)
   
   Wait, at time 0, during reset: `rWorkMAU <= 1'h0`. So rWorkMAU was 0 from time 0 to time 10.
   
   At time 10 (posedge), all RHS are evaluated using old values:
   - Old `rWorkMAU` = 0
   - `stateReg` old = 000 (IDLE)
   - `|stateReg` = 0, so we check `rWorkMAU`
   - Old `rWorkMAU` = 0, so we DON'T enter the IDLE transition logic
   - So stateReg stays at IDLE

   Hmm, but then how does stateReg become WRITE_MISS at time 20?

   Actually, let me re-check. The `rWorkMAU` at time 10 gets the value `io_WorkMAU` sampled at the clock edge. The non-blocking assignment means:
   - RHS evaluation: `io_WorkMAU` at time 10 (which is 1)
   - LHS update: `rWorkMAU` becomes 1
   
   But for the `stateReg` logic, which also uses non-blocking:
   - RHS of stateReg's update uses OLD values
   - Old `rWorkMAU` could be 0 (if it was set to 0 during reset at time 0)

   Actually wait, at time 0: `reset = 1` (from waveform - `controlvis.reset` was traced as value `0` at time 0, hmm). Let me re-check.

   From the evidence notebook: `waveform_trace_signal(controlvis.reset) ok, total_changes=1, first_changes=[{'time': 0, 'value': '0'}]`

   So reset = 0 at all times. But `io_Rst_n = 0` at time 0, which gates the FSM logic.

   Let me re-trace:
   - Time 0: reset=0, io_Rst_n=0
     - The Verilog: `if (io_Rst_n) begin ... end else stateReg <= 3'h0;`
     - Since io_Rst_n=0, stateReg <= IDLE (and the FSM doesn't execute)
   - Time 10: reset=0, io_Rst_n=1 (just transitioned)
     - RHS evaluation uses OLD values (from before clock edge, i.e., at time 5-10)
     - At time 5-10: rWorkMAU was 0 (reset at time 0), rAccessMode was some value...
     
   Actually, let me check when rWorkMAU was last updated. The initial block at time 0 during reset:
   ```systemverilog
   if (reset) begin
       ... rWorkMAU <= 1'h0; ...
   end else begin
       rWorkMAU <= io_WorkMAU;
   end
   ```

   reset is 0, so we're always in the else branch. At time 0, rWorkMAU <= io_WorkMAU, which at time 0 is 1.

   But wait, the `else` branch also contains: `if (io_Rst_n) begin ... end else stateReg <= 3'h0;`

   At time 0, `io_Rst_n=0` so stateReg <= IDLE. But `rWorkMAU <= io_WorkMAU` is OUTSIDE the `if (io_Rst_n)` block, so rWorkMAU is always updated with io_WorkMAU regardless of reset.

   Similarly for rAccessMode, rMatch, rValid, etc.

   So at time 0 (posedge clock):
   - New rWorkMAU = io_WorkMAU = 1
   - New rAccessMode = io_AccessMode = 01
   - New rMatch = io_Match = 0
   - New rValid = io_Valid = 0
   - stateReg = IDLE (because io_Rst_n=0)

   At time 10 (posedge clock):
   - rWorkMAU gets io_WorkMAU (time 10) = 1
   - rAccessMode gets io_AccessMode (time 10) = 01
   - rMatch gets io_Match (time 10) = 1
   - rValid gets io_Valid (time 10) = 1
   
   FSM logic: io_Rst_n=1, |stateReg (old=000) = 0, rWorkMAU (old=1) = 1
   - rAccessMode(0) = 0 (old rAccessMode = 00), so read mode
   - rValid & rMatch = old(0) & old(0) = 0, so miss
   - stateReg <= READ_MISS (010)? Wait, let me look at the code.

   Wait, `rAccessMode(0)` in the old value: at time 10, old rAccessMode = 01 (from time 0 update). Hmm, actually:
   - At time 0: rAccessMode <= io_AccessMode=01 → new rAccessMode = 01
   - At time 10: old rAccessMode = 01 (set at time 0)
   
   So old rAccessMode(0) = 1, which means write mode!
   - rValid & rMatch = old(0) & old(0) = 0 → miss
   - stateReg <= WRITE_MISS (101)

   That's it! At time 10, stateReg becomes WRITE_MISS (101). And this is visible at time 20 in the waveform.

   Actually, the waveform shows stateReg changing at time 20. Let me check: the clock posedge at time 10 should trigger the stateReg update... But the trace shows stateReg changing at time 20.

   Actually, in the FST, signal changes are recorded at the time they occur. If stateReg changes at time 10 (posedge), the trace should show it at time 10. But the trace shows changes at time 0 and time 20.

   This could mean:
   1. The posedge at time 10 doesn't cause stateReg to change (maybe the FSM logic doesn't evaluate to a different state)
   2. Or the stateReg change is recorded at a slightly different time

   Let me check: at time 10, stateReg old = IDLE (000). The FSM logic should compute a new state.

   Actually, wait. Let me look at the generated Verilog more carefully:

   ```systemverilog
   always @(posedge clock) begin
       if (reset) begin
           ... stateReg <= 3'h0; ...
       end else begin
           if (io_Rst_n) begin
               if (|stateReg) begin ... end
               else if (rWorkMAU) begin
                   // IDLE transition
                   stateReg <= rAccessMode[0] ? {2'h2, ~_GEN_5} : _GEN_5 ? 3'h1 : 3'h2;
                   // where _GEN_5 = rValid & rMatch
               end
           end else stateReg <= 3'h0;
       end
   end
   ```

   At time 10, posedge clock. The old values (from before time 10) are:
   - stateReg = 000 (IDLE, set at time 0)
   - rWorkMAU = 0 (Hmm, was it set to 1 at time 0?)

   Let me re-check. At time 0, the initial block ran:
   ```systemverilog
   initial begin
       // Randomize registers if RANDOMIZE_REG_INIT
   end
   ```

   And the always block ran:
   ```systemverilog
   always @(posedge clock) begin
       if (reset) begin ... end
       else begin
           if (io_Rst_n) begin ... end
           else stateReg <= 3'h0;
           rWorkMAU <= io_WorkMAU;
           ...
       end
   end
   ```

   At time 0 (posedge clock, first event):
   - reset = 0
   - io_Rst_n = 0
   - Since io_Rst_n = 0, stateReg <= 3'h0 (IDLE)
   - rWorkMAU <= io_WorkMAU = 1
   - rAccessMode <= io_AccessMode = 01

   So after time 0:
   - stateReg = 000 (IDLE)
   - rWorkMAU = 1

   At time 10 (posedge clock):
   - reset = 0
   - io_Rst_n = 1
   - Since io_Rst_n = 1:
     - |stateReg (old = 000) = 0
     - rWorkMAU (old = 1) = 1 → enters IDLE transition
     - rAccessMode[0] (old = 01, bit 0 = 1) → write mode
     - _GEN_5 = rValid & rMatch = old(0) & old(0) = 0
     - stateReg <= {2'h2, ~_GEN_5} = {2'b10, 1'b1} = 101 = WRITE_MISS

   So at time 10, stateReg should become WRITE_MISS!

   But the waveform shows stateReg changing at time 20, not time 10. There might be a delta cycle or the FST might be recording at a different granularity. In any case, the values at time 0, 10, and 20 are:
   - t=0: stateReg = 000
   - t=10: stateReg = 000 (unfortunately, the FST might not capture the delta)
   - t=20: stateReg = 101

   Actually, looking at the waveform traces more carefully, `stateReg` changes at time 20. If the posedge at time 10 should update it, maybe there's a race condition in the simulation or the FST dump.

   Regardless, the key point is:

   **The assertion `read_hit_to_idle` fails at time 20 because the compiled assertion checks `~(|stateReg)` unconditionally, and at time 20, `stateReg = 101 (WRITE_MISS)` makes `|stateReg = 1`, so `~(1) = 0`, causing the assertion failure.**

   The root cause is that the Chisel-FV `assertNextStepWhen` compilation loses the sequential delay logic, producing an assertion that is too strong (always-IDLE) instead of the intended "READ_HIT implies IDLE next cycle."

### Conclusion

The bug is **assertion_error**: The generated Verilog assertion is incorrect due to a Chisel-to-Verilog compilation issue. The formal tool correctly found that the generated assertion (`~(|stateReg)` → always IDLE) is violated when the state machine transitions to WRITE_MISS, but this violation does NOT indicate a bug in the original design — the design correctly implements the FSM transitions as specified.

**Code location**: 
- Chisel source: `controlvis.scala` line 143-144 (correct intent)
- Generated Verilog: `generated/controlvis.sv` line ~82 (incorrect assertion)
- Chisel-FV library: `Formal.scala` lines 141-148 (`assertNextStepWhen` implementation that doesn't compile correctly)

**Fix required**: The Chisel-FV compilation pipeline (CIRCT/firtool) needs to correctly lower `AssertProperty` inside `when` blocks with register-based enable conditions to SystemVerilog assertions with proper implication operators. Alternatively, `assertNextStepWhen` should be reimplemented using a different approach that compiles correctly.
