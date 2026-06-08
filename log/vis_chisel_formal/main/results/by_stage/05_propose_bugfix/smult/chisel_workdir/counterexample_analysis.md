# Counterexample Analysis Report: `csa_invariant` Assertion Failure

## 1. Verification Environment

- **Top Module**: `SerialCSAMult` (in `spm.scala`)
- **Design**: A 32-bit serial carry-save adder (CSA) multiplier that computes the product one bit at a time using a carry-save adder core
- **Key Components**:
  - `s` (31-bit sum register) and `c` (31-bit carry register): CSA accumulator registers
  - `i` (32-bit multiplicand register) and `j` (1-bit multiplier bit register): Input registers
  - `andA` = `Fill(32, j) & i`: Partial product for the current multiplier bit
  - `andA_trunc` = `andA(30, 0)`: 31-bit truncated partial product
  - `faS` = `c ^ s ^ andA_trunc`: Full-adder sum output (31 bits)
  - `faC` = `(c & s) | (c & andA_trunc) | (s & andA_trunc)`: Full-adder carry output (31 bits)
- **Connections**: Inputs `io.i_raw` and `io.j_raw` are registered into `i` and `j` on every clock edge unconditionally. The `s` and `c` registers update conditionally: during reset they are forced to 0, otherwise they capture new CSA values. Output `io.o` equals `faS(0)`.

## 2. Violated Assertion

- **Assertion Name**: `csa_invariant`
- **Waveform File**: `SerialCSAMult.csa_invariant.fst`
- **Full Assertion (spm.scala, line 62-65)**:

```scala
fvAssert(
    c.asUInt + s.asUInt + andA_trunc.asUInt === faS.asUInt + (faC.asUInt << 1),
    "csa_invariant"
)
```

- **Property Description**: For each bit position in the carry-save adder, the full-adder identity holds: `a + b + cin = sum + 2*cout`. Aggregated across all bits, this means `c + s + andA_trunc = faS + 2*faC`. This should be a tautology because `faS` and `faC` are purely combinational functions of `c`, `s`, and `andA_trunc` (specifically `faS = c ^ s ^ andA_trunc` and `faC = (c&s)|(c&andA_trunc)|(s&andA_trunc)`).

## 3. Waveform Information

- **Waveform Path**: `verilog/extra_bench/smult/SerialCSAMult.csa_invariant.fst`
- **Duration**: 30 ns (3 clock cycles, period = 10 ns)
- **Key Timing**:
  - **Time 0 ns** (rising edge): `io.reset=1`, `s=0`, `c=0`, assertion **PASSES** (1)
  - **Time 10 ns** (rising edge): `io.reset=0`, `s=0`, `c=0`, `i=0xE7FFFFFB`, `j=1`, `andA_trunc=0x67FFFFDB`, `faS=0x67FFFFDB`, `faC=0`, assertion **PASSES** (1)
  - **Time 20 ns** (rising edge): `io.reset=1`, `s=0x73FFFFFFD` (non-zero, old computed value), `c=0`, `i=0x8D200042`, `j=1`, `andA_trunc=0x1A400042`, `faS=0x3EDFFEDF`, `faC=0x02400040`, assertion **FAILS** (0 → 0)
- **Critical Signal Values at Failure (time 20 ns)**:

| Signal | Value (binary) | Value (hex) |
|--------|---------------|-------------|
| `s[30:0]` | `1110011111111111111111111111101` | `0x73FFFFFFD` |
| `c[30:0]` | `0000000000000000000000000000000` | `0x00000000` |
| `andA_trunc[30:0]` | `0001101001000000000000001000010` | `0x1A400042` |
| `faS[30:0]` | `1111110110111111111111110111111` | `0x3EDFFEDF` |
| `faC[30:0]` | `0000001001000000000000001000000` | `0x02400040` |
| `i[31:0]` | `10001101001000000000000001000010` | `0x8D200042` |
| `io_reset` | `1` | `1` |

## 4. Root Cause Analysis

### 4.1 Root Cause Category: **Assertion Error** (Incorrect Assertion)

The assertion `csa_invariant` is **missing a reset guard**. It is checked unconditionally on every clock cycle, including clock cycles where `io.reset` is asserted.

### 4.2 Description of the Bug

The CSA invariant property `c + s + andA_trunc = faS + 2*faC` is mathematically guaranteed when all signals (`c`, `s`, `andA_trunc`) are consistent. However, during the clock cycle where `io.reset` transitions from low to high, the following transient occurs at the rising edge:

1. **`i` and `j` update unconditionally** (lines 33-34 of spm.scala):
   ```scala
   i := io.i_raw
   j := io.j_raw
   ```
   These registers always capture their inputs on every clock edge regardless of reset.

2. **`s` and `c` are conditioned on reset** (lines 47-52):
   ```scala
   when(io.reset) {
       s := 0.U
       c := 0.U
   }.otherwise {
       s := Cat(andA(BITS-1), faS(BITS-2, 1))
       c := faC
   }
   ```
   These registers only capture new values based on the reset condition.

3. **At the rising edge** (time 20 ns), the formal tool evaluates the assertion using signal values that are in a **mixed transient state**:
   - `s` still shows its **old** non-zero value (`0x73FFFFFFD`) — it was loaded at time 10 during normal operation
   - `c` shows zero (correct either way)
   - `i` shows the **new** `io_i_raw` value (`0x8D200042`) — already updated
   - `j` shows the new `io_j_raw` value (1) — already updated
   - `andA_trunc`, `faS`, `faC` are **combinational** and recompute from the mixed values (old `s` + new `andA_trunc`)

4. This creates an **inconsistent snapshot** where the CSA invariant computation uses values from different time domains (old register output + new combinational input), causing the assertion to fail.

### 4.3 Evidence from Waveform

- **At time 10** (reset=0, normal operation): `s=0`, `c=0`, `i=0xE7FFFFFB`, `j=1`, `andA_trunc=0x67FFFFDB`, `faS=0x67FFFFDB`. The identity holds: `0 + 0 + 0x67FFFFDB = 0x67FFFFDB + 0` ✓

- **At time 20** (reset=1, transient): `s=0x73FFFFFFD` (OLD value from cycle 1), `c=0`, `i=0x8D200042` (NEW value), `andA_trunc=0x1A400042` (computed from NEW `i`). The `faS` and `faC` are computed from OLD `s` and NEW `andA_trunc`, creating a mismatch with the identity.

- Crucially, at time 0 (also reset=1): `s=0`, `c=0`, the assertion passes because both `s` and `c` are zero after the initial `RegInit`. The failure only occurs when `s` has a non-zero old value while `io.reset` simultaneously transitions high.

### 4.4 The Fix

The assertion `csa_invariant` should be gated by `!io.reset` to avoid evaluation during the reset transient. The corrected code (spm.scala, around lines 61-65) would be:

```scala
when(!io.reset) {
    fvAssert(
        c.asUInt + s.asUInt + andA_trunc.asUInt === faS.asUInt + (faC.asUInt << 1),
        "csa_invariant"
    )
}
```

This pattern is already correctly used by the companion assertion `reset_clears_s_c` (line 68):
```scala
fvAssert(!io.reset || (s === 0.U && c === 0.U), "reset_clears_s_c")
```

The `reset_clears_s_c` assertion properly guards against reset: "either reset is not asserted, OR s and c are zero." The `csa_invariant` assertion is missing this guard and is therefore incorrect.

### 4.5 Why This Is Not a Design Bug

The CSA multiplier design (the `SerialCSAMult` class) correctly implements the serial multiplier algorithm. The combinational CSA logic correctly computes `faS` and `faC` from the current `c`, `s`, and `andA_trunc`. During normal operation (reset=0), the CSA invariant holds for every cycle. The only reason the assertion fails is the lack of a reset guard, causing evaluation during a transient mixed-signal state.
