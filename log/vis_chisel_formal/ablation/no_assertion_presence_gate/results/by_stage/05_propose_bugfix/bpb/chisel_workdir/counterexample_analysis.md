# Counterexample Analysis Report: bank0_no_overflow_on_taken

## 1. Verification Environment

- **Top Module**: `branchPredictionBuffer`
- **Source File**: `bpbs.scala` (202 lines)
- **Generated Verilog**: `branchPredictionBuffer.sv` (428 lines)
- **Design**: A branch prediction buffer with 4 banks (bank0-bank3) of 2-bit saturating counters arranged in a 4×4 grid. Each counter is initialized to `01` (weakly not-taken). The buffer supports simultaneous prediction lookup (reading all banks) and update (writing one bank selected by `buffer_offset`). Updates saturate: increment stops at 3 (taken) and decrement stops at 0 (not-taken).
- **Key Components**:
  - `state_bank0` through `state_bank3`: Each is a Vec of 4 × 2-bit saturating counters (RegInit to 1.U)
  - `prediction`: a 4-bit register holding the prediction output
  - `update_addr_bank0` through `update_addr_bank3`: registers that capture the `buffer_addr` per bank on each update, used in the overflow/underflow assertions

## 2. Violated Assertion

- **Assertion Name**: `bank0_no_overflow_on_taken`
- **Waveform File**: `branchPredictionBuffer.bank0_no_overflow_on_taken.fst`

### Chisel Source (lines 130–136):
```scala
assertImpliesDelay(
  io.update && io.branch_result && io.buffer_offset === 0.U && state_bank0(io.buffer_addr) === 3.U,
  state_bank0(update_addr_bank0) === 3.U,
  1,
  "bank0_no_overflow_on_taken"
)
```

### Intended Property (in natural language):
> **IF** at any cycle the following conditions all hold:
> - `io.update` is asserted
> - `io.branch_result` is asserted (taken branch)
> - `io.buffer_offset === 0` (selecting bank 0)
> - `state_bank0(io.buffer_addr) === 3` (the counter is already at max)
>
> **THEN** after exactly 1 cycle, the counter at `state_bank0(update_addr_bank0)` must still be 3 (no wraparound/overflow).

### Generated Verilog (lines 132–134):
```verilog
wire [1:0] _GEN_3 = _GEN_2[update_addr_bank0];  // state_bank0[update_addr_bank0]
bank0_no_overflow_on_taken:
    assert property (@(posedge clock) disable iff (~hasBeenReset) &_GEN_3);
```

This Verilog assertion is **INCORRECT**: it reduces to checking `&state_bank0[update_addr_bank0] == 1` at every clock cycle unconditionally, which is equivalent to `state_bank0[update_addr_bank0] == 3`.

## 3. Waveform Information

- **Waveform File**: `verilog/extra_bench/bpb/branchPredictionBuffer.bank0_no_overflow_on_taken.fst`
- **Time Range**: 0 ns → 10 ns (1 clock cycle)

### Key Signal Values (time = 0 ns):

| Signal | Value | Meaning |
|--------|-------|---------|
| `reset` | 0 | Not in reset |
| `hasBeenReset` | 1 | Assertion enabled (disable iff condition is false) |
| `io_stall` | 1 | Stall asserted |
| `io_update` | 1 | Update asserted |
| `io_branch_result` | 1 | Branch taken |
| `io_buffer_addr [1:0]` | 11 (3) | Buffer address = 3 |
| `io_buffer_offset [1:0]` | 11 (3) | Buffer offset = 3 (bank3, not bank0!) |
| `update_addr_bank0 [1:0]` | 00 (0) | Reset value (no bank0 update occurred) |
| `state_bank0_0 [1:0]` | 01 (1) | Counter at address 0 = 1 (not at max=3) |
| `_GEN_3 [1:0]` | 01 | state_bank0[update_addr_bank0] = state_bank0[0] = 01 |
| `bank0_no_overflow_on_taken` | 1 | Assertion status signal |

### Critical Observation:
The assertion property `assert property (&_GEN_3)` evaluates `&2'b01 = 0` (false) at the posedge clock. However, the generated assertion is structurally incorrect — it lacks the antecedent condition, implication operator, and delay.

## 4. Root Cause Analysis

### Category: **Incorrect Assertion** (assertion_error)

### Root Cause: `assertImpliesDelay` compilation failure

The Chisel code calls `assertImpliesDelay(antecedent, consequent, 1, "bank0_no_overflow_on_taken")`, which is intended to generate an SVA property of the form:

```systemverilog
antecedent |-> ##1 consequent
```

However, the FIRRTL compilation pipeline **incorrectly lowers** this call. The generated Verilog retains **only the consequent expression** (`state_bank0(update_addr_bank0) === 3.U`, reduced to `&_GEN_3`) while **dropping**:
1. **The antecedent**: `io.update && io.branch_result && io.buffer_offset === 0.U && state_bank0(io.buffer_addr) === 3.U`
2. **The implication operator**: `|->`
3. **The cycle delay**: `##1`

### Evidence from the codebase:

Compare the correct compilation of `fvAssert` (which uses simple `|` implication):

**Chisel code (line 90):**
```scala
fvAssert(
  io.stall || io.prediction(0) === (state_bank0(io.inst_addr) > 1.U),
  "pred0_decode_correct"
)
```

**Generated Verilog (lines 92–93):**
```verilog
pred0_decode_correct:
    assert property (@(posedge clock) disable iff (~hasBeenReset)
                     io_stall | prediction[0] == _GEN_2[io_inst_addr][1]);
```

This compiles **correctly** — the `||` becomes a proper `|` (implication), both operands are present.

Compare with the broken `assertImpliesDelay`:

**Chisel code (lines 130–136):**
```scala
assertImpliesDelay(
  io.update && io.branch_result && io.buffer_offset === 0.U && state_bank0(io.buffer_addr) === 3.U,
  state_bank0(update_addr_bank0) === 3.U,
  1,
  "bank0_no_overflow_on_taken"
)
```

**Generated Verilog (lines 132–134):**
```verilog
wire [1:0] _GEN_3 = _GEN_2[update_addr_bank0];  // only the consequent operand survived
bank0_no_overflow_on_taken:
    assert property (@(posedge clock) disable iff (~hasBeenReset) &_GEN_3);
```

The antecedent `io.update && io.branch_result && io.buffer_offset === 0.U && state_bank0(io.buffer_addr) === 3.U` is **completely absent**. The delay (`1` cycle) was also dropped.

### Why the assertion fails:

The buggy generated assertion `assert property (&_GEN_3)` checks at every cycle whether `state_bank0[update_addr_bank0] === 3`. But:
- At reset, `update_addr_bank0` is initialized to 0
- At reset, `state_bank0(0)` is initialized to `01` (value 1, not 3)
- Therefore `&(01) = 0` at the very first posedge clock
- The assertion fails immediately as a **vacuous failure** because the antecedent guard (which would normally prevent evaluation when the conditions aren't met) is missing

The DUT logic itself is **correct** — the saturating counter update logic properly guards against overflow with `when (state_bank0(io.buffer_addr) =/= 3.U)` before incrementing. The bug is entirely in the assertion generation.

### Affected assertions (all also broken):
All 8 assertions using `assertImpliesDelay` suffer from the same compilation bug:
- `bank0_no_overflow_on_taken` (waveform analyzed)
- `bank1_no_overflow_on_taken`
- `bank2_no_overflow_on_taken`
- `bank3_no_overflow_on_taken`
- `bank0_no_underflow_on_not_taken`
- `bank1_no_underflow_on_not_taken`
- `bank2_no_underflow_on_not_taken`
- `bank3_no_underflow_on_not_taken`

### Resolution:
Replace each `assertImpliesDelay(antecedent, consequent, 1, name)` call with an equivalent `fvAssert` that explicitly creates the temporal check using `RegNext`:

```scala
// Instead of:
// assertImpliesDelay(
//   io.update && io.branch_result && io.buffer_offset === 0.U && state_bank0(io.buffer_addr) === 3.U,
//   state_bank0(update_addr_bank0) === 3.U,
//   1,
//   "bank0_no_overflow_on_taken"
// )

// Use:
fvAssert(
  !(io.update && io.branch_result && io.buffer_offset === 0.U && state_bank0(io.buffer_addr) === 3.U) ||
    RegNext(state_bank0(update_addr_bank0) === 3.U),
  "bank0_no_overflow_on_taken"
)
```

This avoids the broken `assertImpliesDelay` and manually encodes the one-cycle delay using `RegNext`, which the FIRRTL pipeline handles correctly.
