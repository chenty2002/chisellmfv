# Counterexample Analysis Report: spinner32.spinning_progress

## 1. Verification Environment

- **Top Module**: `spinner32`
- **Design Under Test**: A 32-bit barrel shifter with spin and load modes
- **Key Components**:
  - `doutReg` (32-bit register): Output register holding the rotated value
  - `inrReg` (32-bit register): Input register to the barrel shifter, fed either from `doutReg` (spin mode) or `io.din` (load mode)
  - `splReg` (1-bit register): Mode selector; when true → spin mode (feedback), when false → load mode (input from io.din)
  - Barrel shifter with 5 cascade stages rotating by 1, 2, 4, 8, and 16 bits
  - Timer/counter for liveness checking
- **Connections**:
  - In spin mode (`splReg=1`): `inrReg := doutReg` (feedback), `doutReg := rotate(inrReg, io.amount)`
  - In load mode (`splReg=0`): `inrReg := io.din`, `doutReg := rotate(inrReg, io.amount)`
  - Output: `io.dout := doutReg`

## 2. Violated Assertion

- **Full Assertion Name**: `spinning_progress`
- **Waveform File**: `spinner32.spinning_progress.fst`
- **Code Location**: `spinner32.scala`, line 107

### Code Snippet (lines 103–108, spinner32.scala):

```scala
  // Assertion 3: Spin mode progress — when spinning with a non-zero amount,
  // the output must differ from its previous value within 33 cycles.
  // Non-zero rotation guarantees the barrel shifter changes the value every
  // cycle (a rotation by k>0 cannot map every bit to itself), so the
  // output should differ from the previous cycle within 1 step.
  astRelaxedLiveness(splReg && io.amount =/= 0.U,
                     io.dout =/= RegNext(io.dout),
                     33,
                     "spinning_progress")
```

### Property Description:
When `splReg` is true (spin mode) AND `io.amount` is non-zero, the assertion expects that within 33 cycles, the output `io.dout` must differ from its value in the previous cycle (`io.dout =/= RegNext(io.dout)`). This is based on the assumption that a non-zero rotation on any value will necessarily produce a different result.

## 3. Waveform Information

- **Waveform File Path**: `verilog/extra_bench/spinner/spinner32.spinning_progress.fst`
- **Time Range**: 0 ns → 360 ns (36 cycles at 10 ns/cycle)
- **Failure Time**: 350 ns (cycle 35)

### Key Signal Values at Failure Time (t=350 ns):

| Signal | Value | Description |
|--------|-------|-------------|
| `spinner32.spinning_progress` | **0** | Assertion failure (dropped from 1) |
| `spinner32.timer [5:0]` | `100001` (33) | Timer reached timeout limit |
| `spinner32.pending` | 1 | Timer started and active |
| `spinner32.splReg` | 0 | Spin mode (high earlier, now transitioning) |
| `spinner32.io_amount [4:0]` | `01011` (11) | Non-zero rotation amount |
| `spinner32.io_dout [31:0]` | All zeros | Output never changed |
| `spinner32.doutReg [31:0]` | All zeros | Internal output register |
| `spinner32.inrReg [31:0]` | All zeros | Input to barrel shifter |
| `spinner32.tmp5 [31:0]` | All zeros | Barrel shifter output |

### Timeline of Events:

| Time (ns) | Cycle | Event |
|-----------|-------|-------|
| 0 | 0 | `io_spin` = 1 (spin mode asserted) |
| 10 | 1 | `splReg` becomes 1 (registered), `nextPending` = 1 (combinational: `splReg && io.amount≠0`) |
| 20 | 2 | `pending` becomes 1 (assertion timer starts) |
| 30 | 3 | `timer` begins counting (timer = 1) |
| 30–340 | 3–34 | `timer` increments each cycle: 1, 2, 3, ..., 32 |
| 350 | 35 | `timer` = 33 (timeout), assertion `spinning_progress` drops to 0 |

Throughout all 36 cycles:
- `io.dout` remains **0** (never changes)
- `io.dout =/= RegNext(io.dout)` is **never true**

## 4. Root Cause Analysis

### Error Category: **assertion_error**

The assertion is incorrectly written — it does not account for the case where the value being rotated is zero.

### Root Cause Description

**The assertion assumes that a non-zero rotation on ANY value will change the value, but this is false when the value is zero.**

The barrel shifter's behavior is mathematically correct: rotating the 32-bit value `0` by any amount always yields `0`. This is a fundamental property of bitwise rotation — `rotate_left(0, k) = 0` for any `k`.

In this counterexample:
1. The design starts with `doutReg = 0` and `inrReg = 0` (initialized to 0)
2. At cycle 1, `splReg` becomes 1, entering spin mode
3. In spin mode, `inrReg := doutReg` (feedback), so `inrReg` gets 0
4. The barrel shifter computes `rotate(0, io.amount) = 0`, so `doutReg` remains 0
5. This repeats every cycle — the output never changes from 0
6. The assertion timer waits 33 cycles for `io.dout =/= RegNext(io.dout)`, but it never happens

The comment in the code ("Non-zero rotation guarantees the barrel shifter changes the value every cycle") is only correct for **non-zero values**. When the rotated value is 0, the rotation is an identity operation.

### Evidence from Waveform

1. **`spinner32.doutReg [31:0]` stays 0 throughout** — only 1 change recorded (at time 0, set to 0), never changes thereafter.
2. **`spinner32.inrReg [31:0]` stays 0 throughout** — same, initialized to 0 and never changes.
3. **`spinner32.tmp5 [31:0]` stays 0 throughout** — barrel shifter output, always 0.
4. **`spinner32.io_dout [31:0]` stays 0 throughout** — output register, always 0.
5. **`spinner32.timer [5:0]` counts from 0 to 33** (time 0→350 ns), confirming the assertion timer runs to completion.
6. **`spinner32.spinning_progress` drops from 1 to 0 at t=350 ns** — assertion failure at the exact timeout point.

### Fix Recommendation

The assertion's start condition should also require that the value being rotated is non-zero. Since the value being rotated at the time the start condition fires is the value in `inrReg` (or equivalently `doutReg` since in spin mode `inrReg := doutReg`), the fix is:

```scala
// Fix: Add condition that the rotated value is non-zero
astRelaxedLiveness(splReg && io.amount =/= 0.U && inrReg =/= 0.U,
                   io.dout =/= RegNext(io.dout),
                   33,
                   "spinning_progress")
```

Or alternatively, using `doutReg` (which is semantically equivalent since `inrReg := doutReg` when `splReg = 1`):

```scala
astRelaxedLiveness(splReg && io.amount =/= 0.U && doutReg =/= 0.U,
                   io.dout =/= RegNext(io.dout),
                   33,
                   "spinning_progress")
```

This correctly excludes the trivially-non-changing case where rotating 0 never produces a different value, while still catching any genuine barrel shifter bugs where a non-zero value with non-zero rotation fails to produce a change within the timeout.
