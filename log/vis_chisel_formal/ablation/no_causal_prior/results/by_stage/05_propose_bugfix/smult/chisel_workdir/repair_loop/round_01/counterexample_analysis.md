# Counterexample Analysis Report: `reset_clears_s_and_c`

## 1. Verification Environment

### Top Module
- **Module**: `SerialCSAMult` (spm.scala:6)
- **Parameters**: BITS = 32
- **Clock**: Posedge, period = 10 ns
- **Top-level Reset**: Never asserted (`reset = 0` throughout)

### Key Components
| Component | Width | Description |
|-----------|-------|-------------|
| `s` | [30:0] | Sum register (carry-save adder sum) |
| `c` | [30:0] | Carry register (carry-save adder carry) |
| `i` | [31:0] | Registered multiplicand |
| `j` | 1 | Registered multiplier bit |
| `hasBeenResetReg` | 1 | Initialization tracking register |
| `hasBeenReset` | 1 | `hasBeenResetReg === 1'h1 & reset === 1'h0` |

### Design Description
The `SerialCSAMult` is a serial multiplier using a carry-save adder (CSA). It multiplies a 32-bit multiplicand `i` by a single-bit multiplier `j` each cycle, accumulating the partial product into the `s` and `c` registers. The multiplier processes one bit per clock cycle, computing:
- `andA_trunc = Fill(BITS, j) & i[BITS-2:0]` — partial product
- `faS = c ^ s ^ andA_trunc` — CSA sum output
- `faC = (c & s) | (c & andA_trunc) | (s & andA_trunc)` — CSA carry output
- Next `s = {andA[BITS-1], faS[BITS-2:1]}` (shifted right by 1)
- Next `c = faC`

## 2. Violated Assertion

### Assertion Name
`reset_clears_s_and_c`

### Source Code Location
**File**: `spm.scala`, line 58  
**Code**:
```scala
assertNextStepWhen(io.reset, s === 0.U && c === 0.U, "reset_clears_s_and_c")
```

### Comment in Source
```scala
// Assertion 1: Reset clears internal state
// When io.reset is asserted, s and c must be zero in the next cycle.
```

### Generated SystemVerilog (line ~90 of generated/SerialCSAMult.sv)
```verilog
reset_clears_s_and_c:
    assert property (@(posedge clock) disable iff (~hasBeenReset)
                     s == 31'h0 & c == 31'h0);
```

### Intended Property (Natural Language)
**Intended**: "When `io.reset` is asserted, then in the **next** cycle, `s` and `c` must both be zero."

**Actual Property Checked**: "At every clock cycle (after initialization), `s` must equal 0 AND `c` must equal 0."

## 3. Waveform Information

### Waveform File
`verilog/extra_bench/smult/SerialCSAMult.reset_clears_s_and_c.fst`

### Time Range
0 ns to 30 ns (3 clock cycles)

### Key Time Points and Signal Values

| Time (ns) | Clock | s [30:0] | c [30:0] | i [31:0] | j | io_reset | io_i_raw [31:0] | Assertion Value |
|-----------|-------|----------|----------|----------|---|----------|-----------------|-----------------|
| 0 (Cycle 0) | posedge | 0 | 0 | 0 | 0 | 0 | 0x00000004 | 1 (PASS) |
| 10 (Cycle 1) | posedge | 0 | 0 | 4 | 1 | 0 | 0xFFFFFFFF | 1 (PASS) |
| 20 (Cycle 2) | posedge | **2** | 0 | 0xFFFFFFFF | 1 | 0 | 0xFFFFFFFF | **0 (FAIL)** |

### Failure Point
At time 20 ns (Cycle 2, posedge clock):
- `s = 0b0000000000000000000000000000010` (decimal 2)
- `c = 0`
- `io_reset = 0` (never asserted throughout the entire trace)
- Condition `s == 0 && c == 0` evaluates to **false** because `s = 2`

## 4. Root Cause Analysis

### Error Classification: **Incorrect Assertion (assertion_error)**

### Detailed Analysis

#### The Bug
The generated SystemVerilog assertion **dropped both the antecedent condition (`io_reset`) and the "next-cycle" timing** specified by the Chisel `assertNextStepWhen` function. The actual SVA property:

```verilog
assert property (@(posedge clock) disable iff (~hasBeenReset)
                 s == 31'h0 & c == 31'h0);
```

...is an **unconditional invariant** requiring `s == 0 && c == 0` at every clock cycle. This is clearly not what the user intended.

#### Correct SVA Should Be
The intended property, "When `io.reset` is asserted, s and c must be zero in the **next** cycle," requires:

```verilog
assert property (@(posedge clock) disable iff (~hasBeenReset)
                 io_reset |=> s == 31'h0 && c == 31'h0);
```

Where `|=>` is the **non-overlapping implication operator** — if `io_reset` is true at cycle N, then `s == 0 && c == 0` must hold at cycle N+1.

#### Why This Causes a Failure

The multiplier design is **correct** and behaves as expected:

1. **Cycle 0** (posedge @ 0ns): 
   - Pre-state: `s=0, c=0, i=0, j=0, io_reset=0`
   - Since `io_reset=0`, the multiplier computes: `s <= {j & i[31], faS[30:1]} = {0&0, 0[30:1]} = 0`, `c <= faC = 0`
   - `i <= io_i_raw = 4`, `j <= io_j_raw = 1`
   - Assertion passes (s=0, c=0 — coincidence, not because of reset)

2. **Cycle 1** (posedge @ 10ns):
   - Pre-state: `s=0, c=0, i=4, j=1, io_reset=0`
   - Since `io_reset=0`, multiplier computes: `andA_trunc = {31{1}} & 4 = 4`, `faS = 0^0^4 = 4`, `faC = 0`
   - `s <= {1&0, 4[30:1]} = {0, 2} = 2`, `c <= 0`
   - `i <= 0xFFFFFFFF`, `j <= 1`
   - Assertion passes (s=0, c=0 — still coincidence)

3. **Cycle 2** (posedge @ 20ns):
   - Pre-state: `s=2, c=0, i=0xFFFFFFFF, j=1, io_reset=0`
   - **s = 2** ≠ 0 → assertion **FAILS**
   - This is **expected behavior**: the multiplier has started computing and `s` naturally becomes non-zero as it accumulates partial products

#### Root Cause Summary

The `assertNextStepWhen` function from the ChiselFv library did not generate the correct SystemVerilog assertion property. The generated SVA is missing the `io_reset` antecedent and the `|=>` (next-cycle implication) operator. As a result, the assertion checks an impossible invariant — that `s` and `c` are always zero — which fails as soon as the multiplier does any real computation.

The design (DUT) itself is bug-free: it correctly accumulates partial products in `s` and `c` when `io_reset` is deasserted, and correctly clears them when `io_reset` is asserted. The failure is purely an artifact of an incorrectly generated assertion.

### Bug Location
- **File**: `spm.scala`, line 58
- **Root Cause**: `assertNextStepWhen(io.reset, ...)` → Generated SV assertion drops the `io_reset` antecedent and `|=>` timing, producing an unconditional `s == 0 && c == 0` check at every cycle
- **Evidence from Waveform**: `io_reset = 0` at all time points (0, 10, 20 ns), yet the assertion fails when `s` becomes 2 at time 20 ns during normal multiplier operation
