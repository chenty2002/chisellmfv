# Counterexample Analysis Report

## 1. Verification Environment

### Top Module
- **Module**: `controlvis` (from `controlvis.scala`)
- **Formal Framework**: chiselFv with `Formal` trait

### Key Components
- **stateReg**: 3-bit state register implementing a 6-state FSM (IDLE, READ_HIT, READ_MISS, READ_DATA, WRITE_HIT, WRITE_MISS)
- **Input registers (`rWorkMAU`, `rAccessMode`, `rMatch`, `rValid`, etc.)**: Registered versions of inputs using `RegNext`
- **vector [5:0]**: Control output register driven combinatorially by state
- **Outputs**: Individual bits of `vector` drive `io_Write`, `io_BCURequest_n`, `io_BCUWriteRequest_n`, `io_BCUDataOE`, `io_CacheDataSelect`, `io_MAUNotReady_n`

### Connections
- State definitions: IDLE=0, READ_HIT=1, READ_MISS=2, READ_DATA=3, WRITE_HIT=4, WRITE_MISS=5
- Inputs are registered via `RegNext` before being used in the FSM logic
- Clock period is 10 ns (rising edges at 0 ns, 10 ns, 20 ns)

## 2. Violated Assertion

- **Assertion Name**: `read_hit_to_idle`
- **Waveform File**: `controlvis.read_hit_to_idle.fst`

### Chisel Source (controlvis.scala, lines 148-151)
```scala
assertImpliesDelay(stateReg === State.READ_HIT,
                   stateReg === State.IDLE,
                   1,
                   "read_hit_to_idle")
```

### Intended Property (from Chisel semantics)
If `stateReg` equals READ_HIT (3'h1) at cycle N, then after 1 cycle (at cycle N+1), `stateReg` must equal IDLE (3'h0).

### Generated SystemVerilog (generated/controlvis.sv)
```verilog
read_hit_to_idle:
    assert property (@(posedge clock) disable iff (~hasBeenReset)
                     ~(|stateReg));
```
This translates to: "stateReg must always be 0 (IDLE) at every clock cycle." — **This is incorrect.**

## 3. Waveform Information

- **Waveform File**: `verilog/extra_bench/silver-mau/controlvis.read_hit_to_idle.fst`
- **Time Range**: 0 ns → 30 ns (3 clock cycles)
- **Clock Edges**: Rising at 0 ns, 10 ns, 20 ns

### Critical Signal Timeline

| Time (ns) | Clock | stateReg | io_WorkMAU | io_AccessMode | rWorkMAU | rAccessMode | rMatch | rValid | Assertion |
|-----------|-------|----------|------------|---------------|----------|-------------|--------|--------|-----------|
| 0         | ↑     | 000 (IDLE) | 1         | 01            | 0        | 00          | 0      | 0      | 1 (pass) |
| 10        | ↑     | 000 (IDLE) | 1         | 01            | 1        | 01          | 1      | 1      | 1 (pass) |
| 20        | ↑     | 100 (WRITE_HIT) | 1     | 01            | 1        | 01          | 1      | 1      | **0 (FAIL)** |

### Assertion Failure
The `controlvis.read_hit_to_idle` signal transitions from 1 to 0 at time 20 ns.

## 4. Root Cause Analysis

### The Bug is in the Assertion Generation (assertion_error)

**Location**: The `assertImpliesDelay` function in the chiselFv library generates incorrect SystemVerilog assertions.

**Evidence**:

1. **Correct Chisel assertion** (controlvis.scala:148-151):
   ```scala
   assertImpliesDelay(stateReg === State.READ_HIT,
                      stateReg === State.IDLE,
                      1,
                      "read_hit_to_idle")
   ```
   This should produce an SVA with implication and delay: `(stateReg == 3'h1) |=> (stateReg == 3'h0)`.

2. **Incorrect generated SVA** (generated/controlvis.sv):
   ```verilog
   read_hit_to_idle:
       assert property (@(posedge clock) disable iff (~hasBeenReset)
                        ~(|stateReg));
   ```
   This reduces to: `stateReg == 3'h0` — i.e., the state register must always be IDLE at every clock cycle. There is no implication, no antecedent, and no delay.

3. **Same error affects all `assertImpliesDelay` assertions**: The generated Verilog shows that ALL `assertImpliesDelay` assertions produce incorrect simplified assertions:
   - `read_data_to_idle`: `~(|stateReg)` (should be implication from READ_DATA to IDLE)
   - `write_hit_to_idle`: `~(|stateReg)` (should be implication from WRITE_HIT to IDLE)
   - `write_miss_to_idle`: `~(|stateReg)` (should be implication from WRITE_MISS to IDLE)
   - `idle_remains_idle`: `~(|stateReg)` (should be implication with condition)
   - `reset_goes_to_idle`: `~(|stateReg)` (should be implication from reset)

4. **Counterexample demonstrates the broken assertion**: The assertion `~(|stateReg)` fails when the FSM legitimately transitions from IDLE to WRITE_HIT at time 20 ns. This transition is correct behavior:
   - At cycle 1 (time 10 ns): IDLE state, rWorkMAU=1, rAccessMode[0]=1 (write), rMatch=1, rValid=1 → should transition to WRITE_HIT
   - At cycle 2 (time 20 ns): stateReg becomes WRITE_HIT (3'h100) → correct next state
   - The broken assertion `~(|stateReg)` fails because stateReg ≠ 0

5. **Design behavior is correct**: The FSM's transition from IDLE to WRITE_HIT under (write, hit) conditions is exactly what the state machine specification describes. The state machine correctly implements:
   ```scala
   is(State.IDLE) {
       when(rWorkMAU) {
           when(rAccessMode(0)) { // write
               when(rValid && rMatch) { // write hit
                   stateReg := State.WRITE_HIT  // line 63
               }
           }
       }
   }
   ```
   And from WRITE_HIT (line 85-87), the transition back to IDLE happens when `!rWriteDoneFromBCU_n`.

### Conclusion
The chiselFv library's `assertImpliesDelay` macro is not generating correct SystemVerilog assertions with implication and delay semantics. Instead, it generates simplified assertions that do not capture the intended temporal properties, causing false failures on legitimate design behavior.

**Category**: assertion_error — the assertion generation is incorrect, not the design.
