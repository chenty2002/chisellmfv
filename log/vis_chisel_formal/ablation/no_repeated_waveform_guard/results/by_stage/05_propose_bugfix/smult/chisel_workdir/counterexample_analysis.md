# Counterexample Analysis Report: `state_drains_when_inputs_zero`

## 1. Verification Environment

- **Top Module**: `SerialCSAMult` (BITS = 32)
- **Design**: A serial carry-save adder (CSA) multiplier. It takes a 32-bit multiplicand (`io.i_raw`) and a 1-bit multiplier (`io.j_raw`) as inputs. Each cycle, it computes the AND product of `j` and `i`, accumulates partial products in sum (`s`) and carry (`c`) registers via a full-adder array, and outputs the LSB of the full-adder sum (`faS(0)`).
- **Key Internal Registers**: `s` (31-bit sum), `c` (31-bit carry), `i` (32-bit registered multiplicand), `j` (1-bit registered multiplier bit)
- **Verification Framework**: Chisel Formal (chiselFv) with `astRelaxedLiveness` and `fvAssert` assertions

## 2. Violated Assertion

- **Assertion Name**: `state_drains_when_inputs_zero` (from waveform filename `SerialCSAMult.state_drains_when_inputs_zero.fst`)
- **File**: `spm.scala`, lines ~92-101

### Code Snippet

```scala
val inputsStablyZero = i === 0.U && !j
val prevInputsStablyZero = RegNext(inputsStablyZero, false.B)
astRelaxedLiveness(
    !io.reset && inputsStablyZero && prevInputsStablyZero && (s =/= 0.U || c =/= 0.U),
    s === 0.U && c === 0.U,
    BITS + 1,
    "state_drains_when_inputs_zero"
)
```

### Property Description

When the registered inputs (`i` and `j`) have been stably zero for **two consecutive cycles** and the multiplier is not in reset, any non-zero CSA state (`s` or `c`) must drain to zero (`s === 0 && c === 0`) within `BITS + 1 = 33` cycles. With zero inputs, `andA = 0`, so the CSA acts as a right-shifter, and the state should converge to zero within at most `BITS` cycles (the +1 provides margin).

## 3. Waveform Information

- **Waveform File**: `verilog/extra_bench/smult/SerialCSAMult.state_drains_when_inputs_zero.fst`
- **Duration**: 0 ns to 380 ns (38 cycles)
- **Failure Point**: Time = 370 ns (clock cycle 37), timer value = 33 (0b100001)

### Key Time Points (all in nanoseconds)

| Time | `i` | `j` | `s` (31-bit) | `c` (31-bit) | inputsStablyZero | prevInputsStablyZero | pending | timer |
|------|-----|-----|-------------|-------------|-----------------|---------------------|---------|-------|
| 0    | 0   | 0   | 0           | 0           | 1               | 0                   | 0       | 0     |
| 10   | 0x00010000 | 1 | 0 | 0 | 0 | 1 | 0 | 0 |
| 20   | 0   | 0   | 0x00010000 (bit 16 set) | 0 | 1 | 0 | 0 | 0 |
| **30** | **0** | **0** | **0x00020000 (bit 17 set)** | **0** | **1** | **1** | **0** | **0** |
| 40   | 0x00802080 | 1 | 0x00040000 (bit 18 set) | 0 | 0 | 1 | **1** | 0 |
| 50   | ... | ... | ... | ... | 0 | 0 | 1 | 1 |
| ...  | (active computation) | ... | ... | ... | 0 | 0 | 1 | ... |
| 340  | ... | ... | ... | ... | 0 | 0 | 1 | 30 |
| 350  | 0 | 0 | ... | ... | 1 | 0 | 1 | 31 |
| 360  | 0 | 0 | ... | ... | 1 | 1 | 1 | 32 |
| 370  | 0 | 0 | 0x3FFFFFFC0 (non-zero) | 0x0000012 (non-zero) | 1 | 1 | **1** | **33=FAIL** |

### Critical Signal Values at Failure (time 370 ns)

- `s [30:0]` = `0011111111111111111111111100000` (non-zero)
- `c [30:0]` = `0000000000000000000000000010010` (non-zero)
- `pending` = 1
- `timer [5:0]` = 33 (0b100001)
- `inputsStablyZero` = 1
- `prevInputsStablyZero` = 1

## 4. Root Cause Analysis

### Error Type: **Incorrect Assertion** (`assertion_error`)

### Root Cause

The assertion `state_drains_when_inputs_zero` is **incorrectly formulated**. The `astRelaxedLiveness` property fires its trigger during a brief window where inputs pass through zero between active multiplications. Once the drain timer starts (`pending` goes high), it **never resets** regardless of new input data. When new non-zero inputs arrive, the CSA resumes normal accumulation, preventing `s` and `c` from ever draining to zero within the 33-cycle bound.

### Detailed Trace

1. **Time 0–9**: Reset is active. `i=0`, `j=0`, `s=0`, `c=0`.
2. **Time 10** (posedge clock): Reset deasserted. Input data `io_i_raw=0x00010000`, `io_j_raw=1` is captured into `i` and `j`. The CSA begins computing.
3. **Time 20** (posedge clock): Input data becomes zero (`io_i_raw` changed to 0 at time 10, `io_j_raw` changed to 0 at time 10). `i=0`, `j=0`. The CSA has been computing for one cycle: `s` becomes 0x00010000 (bit 16 set). `inputsStablyZero=1`, but `prevInputsStablyZero=0` (only one cycle of zeros). **Trigger does NOT fire.**
4. **Time 30** (posedge clock): Inputs are still zero (`i=0`, `j=0`). **`prevInputsStablyZero=1`** (two consecutive cycles of zeros). `s` is non-zero (0x00020000), `c=0`. **Trigger fires:** `!io.reset && inputsStablyZero && prevInputsStablyZero && (s != 0 || c != 0)` → TRUE.
5. **Time 40** (posedge clock): **New input data arrives**: `io_i_raw` changed to 0x00802080 at time 30, `io_j_raw` changed to 1 at time 30. `i=0x00802080`, `j=1`. The `pending` signal is latched to 1. Timer starts counting from 0. `s` and `c` begin accumulating new CSA values from the non-zero inputs.
6. **Time 40–340**: The CSA actively computes with various non-zero `io_i_raw` values and `io_j_raw=1`. `pending` remains high. Timer increments every cycle.
7. **Time 340**: `io_i_raw` and `io_j_raw` finally go to zero.
8. **Time 350** (posedge clock): `i=0`, `j=0`. `inputsStablyZero=1`. But timer = 31.
9. **Time 360** (posedge clock): `prevInputsStablyZero=1`. Timer = 32.
10. **Time 370** (posedge clock): Timer reaches **33** (0b100001 = BITS+1). `s` and `c` are still non-zero (only 2 cycles of draining have occurred since time 350). **Assertion FAILS.**

### Why the Fix is Insufficient

The assertion's comment explicitly states the intention to "prevent false firings when inputs momentarily pass through zero between active multiplications" by requiring two consecutive cycles of zero inputs. However, this **only checks stability at the trigger point**, not during the entire draining window. Once `pending` is set by `astRelaxedLiveness`, it **cannot be reset** — it stays high until `s === 0 && c === 0`, even if new input data arrives and the CSA goes back to accumulation mode.

### Buggy Code Location

**File**: `spm.scala`, lines 92–101

```scala
val inputsStablyZero = i === 0.U && !j
val prevInputsStablyZero = RegNext(inputsStablyZero, false.B)
astRelaxedLiveness(
    !io.reset && inputsStablyZero && prevInputsStablyZero && (s =/= 0.U || c =/= 0.U),
    s === 0.U && c === 0.U,
    BITS + 1,
    "state_drains_when_inputs_zero"
)
```

### Proposed Fix

The assertion needs to ensure that inputs remain stably zero **throughout the entire draining period**, not just at trigger time. One correct approach is to reformulate the property so that the liveness check is *relative to the current stable-zero condition*, e.g., using a `past`-based or `s_eventually` formulation:

**Option A**: Reset the timer whenever inputs become non-zero (e.g., using a counter that counts only consecutive zero-input cycles when s or c is non-zero):

```scala
// When inputs are stably zero and s/c are non-zero,
// counter increments each cycle. If inputs become non-zero,
// counter resets. Assert counter never reaches BITS+1.
val inputsZero = i === 0.U && !j
val draining = WireDefault(false.B)
val drainCounter = RegInit(0.U(log2Ceil(BITS+2).W))

when (io.reset) {
  drainCounter := 0.U
}.elsewhen (inputsZero && (s =/= 0.U || c =/= 0.U)) {
  drainCounter := drainCounter + 1.U
  draining := true.B
}.otherwise {
  drainCounter := 0.U
}

// When draining, the state must drain within BITS+1 cycles
when (draining && drainCounter === (BITS+1).U) {
  fvAssert(s === 0.U && c === 0.U, "state_drains_when_inputs_zero")
}
```

**Option B**: Add an implication that the assertion only applies when inputs remain stably zero throughout the bound:

This cannot be expressed directly with `astRelaxedLiveness` — a custom property with past-signal references (`past()`, `stable()`) would be needed.

### Waveform Evidence Summary

The waveform clearly shows:
- `inputsStablyZero` is 1 only during times 0, 20-39, and 350+
- The trigger fires at time 30 (inputs stably zero for 2 cycles)
- `pending` goes high at time 40 and stays high forever
- The timer runs continuously from time 40 to time 370, reaching 33
- During most of this time (time 40-340), the inputs are non-zero and the CSA is actively computing
- `s` and `c` only get 2 cycles of draining (time 350-370) before the timeout
