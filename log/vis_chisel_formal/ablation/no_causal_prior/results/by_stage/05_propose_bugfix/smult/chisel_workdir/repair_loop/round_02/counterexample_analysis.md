
# Smult Counterexample Analysis Report

## 1. Verification Environment

- **Top Module**: `SerialCSAMult` (from `spm.scala`)
- **Configuration**: BITS = 32
- **Design Under Test**: A serial carry-save adder (CSA) multiplier that processes one bit per cycle. It uses three registers (`s`, `c`, `i`, `j`) and a CSA to perform multiplication serially. The output `io.o` is the LSB of the CSA sum (`faS(0)`).
- **Key Components**:
  - `i`: Registered multiplicand (updated every cycle from `io.i_raw`)
  - `j`: Registered multiplier bit (updated every cycle from `io.j_raw`)
  - `s`, `c`: Sum and carry registers for the CSA
  - `faS`, `faC`: Combinational CSA sum and carry outputs
  - `io.o`: Output bit = `faS(0)`
- **Formal Assumptions**: Uses Chisel-FV's `astRelaxedLiveness` primitive for bounded liveness checking.

## 2. Violated Assertion

- **Assertion Name**: `output_eventually_low_when_busy`
- **Waveform Filename**: `SerialCSAMult.output_eventually_low_when_busy.fst`
- **Source Location**: `spm.scala`, lines 80–87

```scala
val i_nonzero = io.i_raw.orR
astRelaxedLiveness(
    !io.reset && i_nonzero,
    !io.reset && i_nonzero && !io.o,
    BITS + 2,
    "output_eventually_low_when_busy"
)
```

- **Property Description**: When the design is not in reset and a non-zero multiplicand (`io.i_raw`) is presented, the output `io.o` should eventually go low within at most `BITS + 2 = 34` clock cycles.

More precisely, the `astRelaxedLiveness(start, end, bound)` primitive asserts:
> Whenever `start` becomes true, `end` must become true within `bound` clock cycles.

Where:
- **start** = `!io.reset && i_nonzero` — reset is de-asserted and input is non-zero
- **end** = `!io.reset && i_nonzero && !io.o` — reset is low, input is non-zero, AND output is low

## 3. Waveform Information

- **Waveform File**: `verilog/extra_bench/smult/SerialCSAMult.output_eventually_low_when_busy.fst`
- **Total Duration**: 370 ns (37 clock cycles at 10 ns period)
- **Failure Time**: 360 ns (the assertion signal `SerialCSAMult.output_eventually_low_when_busy` transitions from 1→0 at t=360 ns)
- **Bound Overflow**: The timer reaches `100010` (binary) = 34 (decimal) = BITS + 2 at t=360 ns, which is exactly when the bound expires.

### Key Time Points

| Time (ns) | io_reset | io_o | io_i_raw (hex) | io_i_raw ≠ 0? | io_j_raw | pending | timer |
|-----------|----------|------|-----------------|---------------|----------|---------|-------|
| 0         | 1        | 0    | 0x2273A341      | Yes           | 1        | 0       | 0     |
| 10        | 0        | 1    | 0xD3B8BDF6      | Yes           | 1        | 0       | 0     |
| 20        | 0        | 0    | 0x00000000      | **No**        | 1        | 1       | 0     |
| 30        | 0        | 1    | 0xC05E4D97      | Yes           | 1        | 1       | 1     |
| 40        | 0        | 0    | 0x00000000      | **No**        | 1        | 1       | 2     |
| 60        | 0        | 1    | 0x300F5775      | Yes           | 1        | 1       | 4     |
| 70        | 0        | 0    | 0x00000000      | **No**        | 1        | 1       | 5     |
| 90        | 0        | 1    | 0x00000000      | **No**        | 0        | 1       | 7     |
| 100       | 1        | 0    | 0x03C13CD9      | Yes           | 1        | 1       | 8     |
| 130       | 0        | 0    | 0x00000000      | **No**        | 0        | 1       | 11    |
| 170       | 0        | 0    | 0x00000000      | No            | 1        | 1       | 15    |
| 310       | 0        | 0    | 0x00000000      | No            | 0        | 1       | 29    |
| 340       | 0        | 0    | 0x00000000      | No            | 0        | 1       | 32    |
| **360**   | 0        | 0    | 0x00000000      | **No**        | 0        | 1       | **34** |

## 4. Root Cause Analysis

### Classification: ❌ Incorrect Assertion (assertion_error)

### The Bug

The assertion's **end condition** is too restrictive. It requires three simultaneous conditions to declare success:

```scala
end = !io.reset && i_nonzero && !io.o
         ^          ^            ^
     reset low  input ≠ 0   output low
```

The problem is the `i_nonzero` (i.e., `io.i_raw.orR`) requirement within the end condition. Looking at the waveform evidence:

| Time | io_i_raw | io_o | `i_nonzero && !io.o` | Satisfies End? |
|------|----------|------|---------------------|---------------|
| 10   | Non-zero | 1    | T && F = F          | ❌ |
| 20   | Zero     | 0    | F && T = F          | ❌ |
| 30   | Non-zero | 1    | T && F = F          | ❌ |
| 40   | Zero     | 0    | F && T = F          | ❌ |
| 60   | Non-zero | 1    | T && F = F          | ❌ |
| 70   | Zero     | 0    | F && T = F          | ❌ |
| ...  | ...      | ...  | ...                 | ❌ |

**The end condition `!io.reset && i_nonzero && !io.o` is NEVER satisfied throughout the entire 37-cycle trace.** Whenever `io_o` is low (0), `io_i_raw` is always zero, making `i_nonzero` false. And whenever `io_i_raw` is non-zero, `io_o` is always 1.

This is not a coincidence — it reflects the design's behavior: the CSA sum's LSB (which drives `io.o`) is determined by the registered values of `i` and `j` and the internal CSA state. The output bit is 1 when the serial multiplier is actively producing a 1-bit partial sum. When `io_i_raw` transitions to 0, the DUT's registered `i` becomes 0, `andA` becomes 0, and the CSA then produces a 0 LSB. So `io_o` goes low *precisely because* the input became zero.

### Why This Occurs

The multiplier registers `io.i_raw` into `i` every cycle (`i := io.i_raw`). When `io_i_raw` is non-zero, the multiplier is actively shifting and the output bit can be 1. When `io_i_raw` becomes 0 (which happens frequently in the waveform at times 20, 40, 70, 90, 130, etc.), the output goes low, but then `i_nonzero` is also false, so the end condition still fails.

The key insight: **when the output goes low, the input has already become zero**, so the requirement "input is non-zero AND output is low" can never be met.

### Proposed Fix

Remove `i_nonzero` from the **end** condition of the assertion. The property should simply be: "When a non-zero input is presented, the output should eventually go low (within BITS+2 cycles)."

```scala
astRelaxedLiveness(
    !io.reset && i_nonzero,   // start: non-zero input presented
    !io.reset && !io.o,        // end: output goes low
    BITS + 2,
    "output_eventually_low_when_busy"
)
```

The `start` condition remains unchanged — it triggers monitoring when a non-zero input arrives. But the `end` condition drops the input liveness requirement and only checks that the output eventually goes low, which correctly captures the intended liveness property: a multiplier that has been given work to do must eventually complete (output goes low).
