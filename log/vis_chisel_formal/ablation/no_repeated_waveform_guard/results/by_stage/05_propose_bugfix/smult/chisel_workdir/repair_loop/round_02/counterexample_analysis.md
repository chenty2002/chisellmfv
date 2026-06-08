# Counterexample Analysis Report: SerialCSAMult.state_drains_when_inputs_zero

## 1. Verification Environment

- **Top Module**: `SerialCSAMult` (within package `llmverify`)
- **Parameterization**: `BITS = 32`
- **Structure**: The module implements a serial carry-save adder (CSA) multiplier. It contains four registers:
  - `s` (30 bits): Sum register (partial product accumulator)
  - `c` (30 bits): Carry register
  - `i` (32 bits): Registered multiplicand (feeds from `io.i_raw`)
  - `j` (1 bit): Registered multiplier bit (feeds from `io.j_raw`)
- **Key Signals**: `io.i_raw`/`io.j_raw` (inputs), `i`/`j` (registered inputs), `andA = Fill(BITS, j) & i`, `faS` (CSA sum), `faC` (CSA carry)
- **Dataflow**: Each cycle, if `j`=1, `i` is added to the partial sum using a CSA (s, c). The sum is right-shifted by 1 each cycle (`s := Cat(andA(BITS-1), faS(BITS-2, 1))`, `c := faC`).

## 2. Violated Assertion

- **Assertion Name**: `state_drains_when_inputs_zero`
- **Waveform Filename**: `SerialCSAMult.state_drains_when_inputs_zero.fst`
- **Location**: `spm.scala`, lines 85-92
- **Code**:
  ```scala
  astRelaxedLiveness(
      !io.reset && i === 0.U && !j && (s =/= 0.U || c =/= 0.U),
      s === 0.U && c === 0.U,
      BITS + 1,
      "state_drains_when_inputs_zero"
  )
  ```
- **Property Description**: When the registered inputs `i` and `j` are both zero (`i === 0.U && !j`), the reset is not active, and the accumulator state is non-zero (`s =/= 0.U || c =/= 0.U`), then within `BITS + 1 = 33` cycles, the state must drain to zero (`s === 0.U && c === 0.U`). The reasoning is that with zero inputs, `andA = 0`, so the CSA acts as a right-shifter that converges to zero.

## 3. Waveform Information

- **Waveform File**: `verilog/extra_bench/smult/SerialCSAMult.state_drains_when_inputs_zero.fst`
- **Total Duration**: 37 cycles (0–370 ns)
- **Key Time Points**:

| Time (ns) | Cycle | i | j | s | c | Event |
|-----------|-------|---|---|---|---|-------|
| 0 | 0 | 0 | 0 | 0 | 0 | Initial state |
| 10 | 1 | 6 | 1 | 0 | 0 | Multiplication 6×1 starts |
| **20** | **2** | **0** | **0** | **3** | **0** | **Antecedent FIRES** — i=0, j=0, s=3≠0 |
| 30 | 3 | 0x4000 | 1 | 1 | 0 | New inputs arrive — drain interrupted |
| 40–160 | 4–16 | 0 | 1 | 0x2000→... | 1 | CSA shifts (0x4000×1 computation) |
| 170 | 17 | 1 | 1 | 1 | 0 | New input i=1 arrives |
| 180 | 18 | 0x8000 | 1 | 0 | 1 | New input i=0x8000, carry-out=1 |
| 190–330 | 19–33 | 0 | 1 | 0x4000→...→1 | 0 | CSA shifts (0x8000×1 computation) |
| 340 | 34 | 4 | 1 | 0 | 1 | New input i=4 arrives, c=1 |
| **350** | **35** | **0** | **1** | **2** | **0** | **Bound check** (33 cycles after t=20) — s=2≠0 |
| **360** | **36** | **0** | **1** | **1** | **0** | **Assertion FAILS** |

- **Failure Point**: Time 360 ns (cycle 36). The assertion signal `state_drains_when_inputs_zero` transitions from `1` to `0` at this time.

## 4. Root Cause Analysis

### Classification: Assertion Error (Incorrect Antecedent)

### Root Cause

The assertion's antecedent is too weak. It only checks that **at one instant** (the current clock cycle) the registered inputs `i` and `j` are zero. However, `i` and `j` are simple registered copies of `io.i_raw` and `io.j_raw` that update on **every** clock cycle with no enable or hold mechanism. They can — and do — change in subsequent cycles.

In the counterexample:

1. **Time 10**: Multiplication `6 × 1` begins (`i=6, j=1`). After one cycle, `s=3` (partial sum).
2. **Time 20**: Inputs are briefly zero (`i=0, j=0`). The antecedent fires because `i=0 && !j && s=3≠0`. The drain of `s=3` begins (shifts to `1`).
3. **Time 30**: New non-zero inputs arrive (`io.i_raw=0x4000, io.j_raw=1`). The registers update to `i=0x4000, j=1`. The CSA starts computing `0x4000 × 1`, **interrupting the drain** of the previous state.
4. **Thereafter**: Multiple multiplications execute sequentially (`0x4000×1`, `1×1`, `0x8000×1`, `4×1`), keeping `s` and `c` non-zero.
5. **Time 350** (33 cycles after the antecedent): `s=2, c=0` — the drain has been interrupted long ago and the bound has been exceeded.

### Why This Happens

The source code at `spm.scala` lines 36-37 shows:
```scala
val i = RegInit(0.U(BITS.W))
val j = RegInit(false.B)
// ...
i := io.i_raw
j := io.j_raw
```

The registers `i` and `j` are **unconditionally updated** every cycle. There is no enable signal (`valid`, `ready`, `busy`, or similar) that would freeze the registered inputs during the drain period. As soon as any non-zero `io.i_raw` or `io.j_raw` arrives, the registered inputs change and the CSA begins computing a new product, overwriting the draining state.

### The Intent vs. Reality

The assertion comment (lines 76-84) correctly reasons:
> "With zero inputs, andA = 0, so the CSA acts as a right-shifter... After at most BITS cycles the state converges to 0"

But this reasoning is only valid if inputs **remain** zero for the entire drain window. The circuit does not enforce this — inputs can (and in this counterexample do) change every cycle.

### Suggested Fix

The assertion should ensure input stability by using `Past` or `stable` to verify that inputs have been zero for more than one consecutive cycle before asserting the drain property. For example:

```scala
astRelaxedLiveness(
    !io.reset && 
    io.i_raw === 0.U && !io.j_raw && 
    Past(io.i_raw === 0.U) && Past(!io.j_raw) &&
    (s =/= 0.U || c =/= 0.U),
    s === 0.U && c === 0.U,
    BITS + 1,
    "state_drains_when_inputs_zero"
)
```

This checks that the **raw inputs** (`io.i_raw`/`io.j_raw`) have been zero for **two consecutive cycles** before starting the liveness check. This prevents the assertion from firing in cases where inputs only momentarily pass through zero between active multiplications.

Alternatively, the property could use the stable operator or track a counter that ensures inputs have been zero long enough for the drain to complete.
