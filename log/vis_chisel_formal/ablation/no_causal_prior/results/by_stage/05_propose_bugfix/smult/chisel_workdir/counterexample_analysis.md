# Counterexample Analysis Report: SerialCSAMult.output_eventually_low_when_busy

## 1. Verification Environment

- **Top module name**: `SerialCSAMult` (from `spm.scala`)
- **Module type**: Serial carry-save adder (CSA) multiplier
- **Key components**:
  - `s` (31-bit): Sum register of the CSA accumulator
  - `c` (31-bit): Carry register of the CSA accumulator
  - `i` (32-bit): Registered multiplicand input
  - `j` (1-bit): Registered multiplier bit input
  - `faS` / `faC`: Combinational CSA logic producing sum and carry outputs
- **Inputs**: `io.reset` (Bool), `io.i_raw` (UInt[32]), `io.j_raw` (Bool)
- **Output**: `io.o` (Bool) — serial LSB of the CSA sum
- **Formal engine**: JasperGold with ChiselFv `astRelaxedLiveness` checker

## 2. Violated Assertion

- **Full assertion name**: `output_eventually_low_when_busy` (from waveform filename `SerialCSAMult.output_eventually_low_when_busy.fst`)
- **File**: `spm.scala`, lines 85–91
- **Code snippet**:
  ```scala
  val i_nonzero = io.i_raw.orR
  astRelaxedLiveness(
    !io.reset && i_nonzero,
    !io.reset && !io.o,
    BITS + 2,
    "output_eventually_low_when_busy"
  )
  ```
- **Property description**: This is a **relaxed bounded liveness** assertion. When `io.reset` is deasserted AND the raw multiplicand input `io.i_raw` is non-zero (trigger condition), then within `BITS + 2 = 34` clock cycles, the output `io.o` should become 0 while `io.reset` remains deasserted (target condition).

## 3. Waveform Information

- **Waveform file**: `verilog/extra_bench/smult/SerialCSAMult.output_eventually_low_when_busy.fst`
- **Time range**: 0 ns → 370 ns (37 clock cycles, clock period = 10 ns)
- **Assertion failure time**: 360 ns

### Key Signal Values at Failure (t = 360 ns):

| Signal | Value |
|--------|-------|
| `output_eventually_low_when_busy` | `0` (assertion failed) |
| `timer [5:0]` | `100010` (34 decimal = BITS+2) |
| `pending` | `1` |
| `nextPending` | `1` |
| `io_reset` | `1` |
| `io_o` | `0` (but target requires `!io.reset && !io.o` — irrelevant because `io_reset=1`) |
| `io_i_raw [31:0]` | `0` |
| `io_j_raw` | `0` |
| `i [31:0]` | `0` |
| `j` | `0` |
| `s [30:0]` | `0` |
| `c [30:0]` | `0` |

### Critical Observation: io_o NEVER goes low when io_reset=0

Across the entire 370 ns trace, `io_o` is **always** `1` whenever `io_reset=0`, and `io_o` is `0` ONLY when `io_reset=1`. The target condition `!io.reset && !io.o` is **never satisfied** in the entire trace.

| Time Range | io_reset | io_o | Target (!io.reset && !io.o) |
|-----------|----------|------|------|
| 0–10 | 1 | 0 | false (io_reset=1) |
| 10–50 | 0 | 1 | false (io_o=1) |
| 50–60 | 1 | 0 | false (io_reset=1) |
| 60–90 | 0 | 1 | false (io_o=1) |
| 90–100 | 1 | 0 | false (io_reset=1) |
| 100–110 | 0 | 1 | false (io_o=1) |
| 110–120 | 1 | 0 | false (io_reset=1) |
| 120–170 | 0 | 1 | false (io_o=1) |
| ... | ... | ... | ... |
| 340–350 | 0 | 1 | false (io_o=1) |
| 350–370 | 1 | 0 | false (io_reset=1) |

The liveness timer increments monotonically from cycle 1 (t=30) to cycle 34 (t=360), at which point it exceeds the `BITS+2` bound and the assertion fails.

## 4. Root Cause Analysis

### Classification: Setup Error (Invalid io_reset Stimulus Constraints)

### The Problem

The assertion fails because the formal verification environment does not constrain `io.reset` to behave like a real hardware reset signal. Specifically:

1. **`io.reset` toggles unrealistically frequently**: Instead of being asserted briefly at power-on and then deasserted permanently, `io.reset` toggles every 1–5 clock cycles throughout the trace. The longest consecutive non-reset window is only **5 cycles** (t=120–170 ns).

2. **The computation requires 34 consecutive non-reset cycles**: The serial CSA multiplier processes one bit position per cycle. With `BITS=32`, the multiplication needs at least 32 cycles to accumulate all partial products and shift out the result (plus 2 more cycles for liveness margin, as specified by `BITS+2`). The assertion's bound of 34 cycles is unreachable when `io.reset` is asserted every few cycles.

3. **`.otherwise` path never accumulates meaningful state**: When `io.reset=1`, the `when(io.reset)` block in the design resets both `s` and `c` to 0. Each time `io.reset` goes back to 0, the computation restarts from zero. In the subsequent short non-reset window, the output `io.o = faS(0)` is driven by the current LSB of the CSA sum, which the formal tool can keep at `1` by choosing adversarial inputs.

4. **Trigger uses `io.i_raw.orR` instead of the registered `i.orR`**: The assertion trigger condition uses `io.i_raw.orR` (the raw input), which changes every cycle. However, the actual computation uses the registered value `i` (latched at each clock edge). Since `io.i_raw` changes every cycle and is usually non-zero, the trigger re-asserts each time `io.reset` goes low, restarting the liveness counter.

### Analysis from Trace

At t=10, `io_reset` transitions from 1 to 0 at the clock edge. The DUT samples `io_reset=1`, so the reset path assigns `s := 0, c := 0`. The registered `i` latches `10000001100000001111111111100011` (from the time-0 value of `io_i_raw`).

Immediately after the edge, the combinational output computes:
- `andA = i & Fill(32, j)` where `i = 10000001100000001111111111100011, j = 1`
- `andA_trunc = i(30:0) = 0000001100000001111111111100011`
- `faS = c ^ s ^ andA_trunc = 0 ^ 0 ^ andA_trunc = andA_trunc`
- `io_o = faS(0) = 1` (the LSB of `i` is 1)

In every subsequent clock cycle where `io_reset=0`, the CSA sum `faS(0)` remains `1`, preventing the target condition from ever being met. When `io_reset` eventually goes to `1` (e.g., at t=50), the target `!io.reset && !io.o` becomes false anyway because `io.reset=1`. By the time the timer reaches 34 (t=360), the assertion checker determines a violation.

### Why This Is a Setup Error, Not a Design Bug

The design's behavior under a realistic reset scenario (one brief reset pulse, then stable deassertion for 34+ cycles) would allow the CSA computation to progress through all bit positions, eventually producing a `0` at the LSB output. The signal `io_o` is simply `faS(0)`, the LSB of the CSA sum, which toggles as the computation processes bits. Given sufficient consecutive cycles, this bit will eventually be `0`.

The design assumes that `io.reset` is a conventional synchronous reset that is asserted briefly and then deasserted permanently. The formal tool exploits the absence of this constraint, creating an adversarial scenario where `io.reset` is toggled frequently to prevent the computation from making progress.

### Recommended Fix

Add constraints to the test harness to restrict `io.reset` behavior:

1. **Constrain `io.reset` to stabilize after initial assertion**: `io.reset` should be asserted for a few initial cycles, then deasserted and stay deasserted for the remainder of the verification.

2. **Alternatively, fix the assertion** to be tolerant of intermediate resets, e.g., by checking that progress is made within each contiguous non-reset window:

   ```
   // The output should eventually go low within BITS+2 consecutive 
   // non-reset cycles if a non-zero multiplicand was loaded before reset deasserted
   ```

3. **Consider using `i.orR` (registered value) instead of `io.i_raw.orR`** for the trigger condition, since the computation uses the registered `i`, not the raw input.

### Affected Code

**File**: `spm.scala`, lines 85–91
**Assertion trigger**:
```scala
val i_nonzero = io.i_raw.orR
```
The trigger should ideally reference the registered multiplicand `i` instead of `io.i_raw`, and additional constraints on `io.reset` are needed.
