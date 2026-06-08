# Counterexample Analysis Report

## 1. Verification Environment

**Top Module**: `rollercoasterNumbers` (module: rcnum16.scala:6:7)

**Key Components and Connections**:
- `rollercoasterNumbers` — The main module implementing a "rollercoaster numbers" sequence (a Collatz-like 3n+1 / n/2 algorithm)
- `ResetCounter` — A submodule that tracks time since the last reset, providing `notChaos` signal for liveness checking
- `numReg` (UInt<16>) — The current number register (uninitialized, non-deterministic initial value)
- `prevNumReg` (UInt<16>) — Previous value of numReg, captured by `prevNumReg <= numReg` at each posedge
- `prevTmp` — Computed as `{2'h0, prevNumReg} + {1'h0, prevNumReg, 1'h1}` = `3 * prevNumReg + 1`

**Design Under Test**: A hardware implementation of a rollercoaster (Collatz-like) sequence generator. Given a starting number, if odd: compute 3n+1 (with overflow → reset to 0); if even: n/2. The design intentionally uses uninitialized registers to model non-deterministic initial states.

## 2. Violated Assertion

**Full Assertion Name**: `odd_no_overflow_transition` (from waveform filename `rollercoasterNumbers.odd_no_overflow_transition.fst`)

**Code Snippet** (rcnum16.scala, lines 46-50):
```scala
  fvAssert(
    !(prevNumReg(0) && !prevOver) || (numReg === prevTmp(15,0)),
    "odd_no_overflow_transition"
  )
```

**Generated Verilog** (rollercoasterNumbers.sv):
```verilog
odd_no_overflow_transition:
    assert property (@(posedge clock) disable iff (~hasBeenReset)
                     ~(prevNumReg[0] & ~prevOver) | numReg == _prevTmp_T_2[15:0]);
```

**Natural Language Property**: At any positive clock edge, if the previous number (`prevNumReg`) was odd (bit 0 = 1) AND the computation `3*prevNumReg + 1` does NOT overflow beyond 16 bits, then the current number (`numReg`) must equal the lower 16 bits of that computation (`prevTmp[15:0]`). The assertion is disabled (masked) when `~hasBeenReset` is true (i.e., when the system has NOT been properly reset).

**File Location**: `rcnum16.scala`, lines 46-50 (primary source); `generated/rollercoasterNumbers.sv`, lines 47-49 (generated SystemVerilog)

## 3. Waveform Information

**Waveform File**: `verilog/extra_bench/rcnum_rcnum16/rollercoasterNumbers.odd_no_overflow_transition.fst`

**Time Range**: 0 ns → 10 ns (1 clock cycle; clock period = 10 ns)

**Key Time Points and Critical Signal Values**:

| Signal | Time 0 | Time 5 | Time 10 |
|--------|--------|--------|---------|
| `clock` | 1 | 0 | 1 |
| `reset` | 0 | — | — |
| `numReg [15:0]` | `0x0000` | `0x0000` | `0x0000` |
| `prevNumReg [15:0]` | `0x0AAB` | `0x0AAB` | `0x0AAB` |
| `_prevTmp_T_2 [17:0]` | `0x2002` | `0x2002` | `0x2002` |
| `odd_no_overflow_transition` | 1 | — | — |
| `hasBeenReset` | 1 | — | — |
| `hasBeenResetReg` | 1 | — | — |
| `_resetCounter_notChaos` | 1 | — | — |

**All signal values are constant throughout the entire 1-cycle trace** — no transitions occur. The clock toggles at time 5, but no register updates happen because there is no posedge within the trace (only one posedge at time 0).

## 4. Root Cause Analysis

### Buggy Code Location

**Setup Error in Formal Verification Infrastructure** — the root cause is in how the `hasBeenResetReg` register is initialized.

**File**: `generated/rollercoasterNumbers.sv`, line 42:
```verilog
initial
    hasBeenResetReg = 1'bx;
```

### Description of the Bug

The `hasBeenResetReg` register is a gating mechanism intended to disable assertions until the design has been through a proper reset cycle. In simulation, the `1'bx` (unknown/X) initial value propagates through the `disable iff (~hasBeenReset)` clause, effectively disabling the assertion. However, in formal verification, `1'bx` is treated as non-deterministic — the solver can resolve it to **either 0 or 1**.

In this counterexample, the solver chose `hasBeenResetReg = 1`. Combined with `reset = 0`, this makes:
- `hasBeenReset = (1 === 1) & (0 === 0) = 1`
- `~hasBeenReset = 0`
- `disable iff (~hasBeenReset)` → **assertion is NOT disabled** → it is active at cycle 0

### Evidence from Waveform

The assertion failure occurs because of inconsistent initial values of two independently randomized registers:

1. **`numReg [15:0] = 0x0000`** — The current number, randomly initialized to all zeros
2. **`prevNumReg [15:0] = 0x0AAB`** — The "previous" number, randomly initialized to a different value

At time 0 (posedge clock), the assertion check evaluates as follows:

```
prevNumReg[0] = 1          (0x0AAB is odd, bit 0 = 1)

_prevTmp_T_2 = {2'h0, 0x0AAB} + {1'h0, 0x0AAB, 1'h1}
             = 0x2002

_prevTmp_T_2[17:0] = 18'b00_0010_0000_0000_0010
_prevTmp_T_2[17]   = 0
_prevTmp_T_2[16]   = 0
prevOver = 0 | 0 = 0        (NO overflow)

_prevTmp_T_2[15:0] = 0x0002  (expected next number)

Assertion body:
  ~(prevNumReg[0] & ~prevOver) | (numReg == _prevTmp_T_2[15:0])
= ~(1 & 1) | (0x0000 == 0x0002)
= 0 | 0
= 0   ← ASSERTION FAILS
```

### Why this causes the assertion to fail

The design intentionally uses **uninitialized registers** (`Reg(UInt(16.W))` without a reset value) to model non-deterministic initial states. The `hasBeenReset` mechanism with `disable iff (~hasBeenReset)` is supposed to gate all assertions until after a reset. However:

1. `hasBeenResetReg` is initialized to `1'bx` (X) in the `initial` block
2. In formal verification, `1'bx` can be resolved to either 0 or 1 by the solver
3. When resolved to 1 (as in this counterexample), assertions become active from cycle 0
4. At cycle 0, `numReg` and `prevNumReg` have independent random initial values
5. The relationship checked by `odd_no_overflow_transition` cannot hold between two independent random values
6. Therefore, the assertion fails

**Error Classification**: `setup_error` — The formal verification infrastructure (specifically `hasBeenResetReg` initialization to `1'bx` instead of `1'b0`) allows the solver to enable assertions before any reset has occurred. Since the design intentionally models non-deterministic initial states, assertions MUST be disabled until after a proper reset. Changing the initial value from `1'bx` to `1'b0` would prevent this spurious counterexample by properly gating the assertions.
