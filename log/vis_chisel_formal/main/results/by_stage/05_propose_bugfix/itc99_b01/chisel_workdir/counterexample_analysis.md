# Counterexample Analysis Report: `overflow_when_in_state_e`

## 1. Verification Environment

- **Benchmark**: itc99_b01
- **Top Module**: `b01` (Chisel), instantiated as `b01` in Verilog
- **Module Type**: FSM-based sequential circuit with overflow detection
- **Design Structure**:
  - `stato` — 3-bit state register (FSM with 8 states: a, b, c, e, f, g, wf0, wf1)
  - `prevStato` — previous state snapshot (used for formal assertions)
  - `outpReg` — output data register
  - `overflwReg` — overflow flag register
  - `io_LINE1`, `io_LINE2` — boolean inputs
  - `io_OUTP`, `io_OVERFLW` — boolean outputs
  - `hasBeenReset` / `hasBeenResetReg` — reset tracking signals
  - `resetCounter` — reset stabilization counter
- **Inputs**: `io_LINE1` and `io_LINE2` (both tied to 1 in the counterexample)

## 2. Violated Assertion

- **Full Assertion Name**: `overflow_when_in_state_e`
- **Waveform File**: `b01.overflow_when_in_state_e.fst`
- **Source Code** (b01.scala, lines 121–123):
  ```scala
  // Safety 2: When the PREVIOUS state was 'e', the overflow flag must be asserted.
  fvAssert(prevStato =/= b01State.e || overflwReg, "overflow_when_in_state_e")
  ```
- **Property**: "If the previous state was `e` (the overflow state), then the `overflwReg` flag must be asserted."
- **File Location**: `chisel/extra_bench/itc99_b01/b01.scala`, line 123

## 3. Waveform Information

- **Waveform File**: `verilog/extra_bench/itc99_b01/b01.overflow_when_in_state_e.fst`
- **Duration**: 1 cycle (10 ns)
- **Time Range**: 0 ns → 10 ns
- **Failure Time**: Time 0 ns (the only active cycle edge)

### Critical Signal Values at Failure Point (t = 0 ns)

| Signal | Value | Meaning |
|--------|-------|---------|
| `b01.clock` | 1 | Positive clock edge |
| `b01.reset` | 0 | Reset not asserted |
| `b01.stato [2:0]` | `000` (0) | State = `a` |
| `b01.prevStato [2:0]` | `011` (3) | Previous state = `e` |
| `b01.overflwReg` | 0 | Overflow flag not set |
| `b01.overflow_when_in_state_e` | 1 | Assertion FAILED (1 = asserted/failing) |
| `b01.io_LINE1` | 1 | Input high |
| `b01.io_LINE2` | 1 | Input high |
| `b01.io_OUTP` | 0 | Output data low |
| `b01.io_OVERFLW` | 0 | Overflow output low |
| `b01.hasBeenReset` | 1 | Reset cycle completed |
| `b01.hasBeenResetReg` | 1 | Reset cycle completed |

## 4. Root Cause Analysis

### Classification: **Assertion Error (initialization artifact)**

The assertion itself is logically correct, but it does not account for the initialization cycle where `prevStato` is deliberately initialized to state `e` (value 3) while `overflwReg` is initialized to `false.B`.

### Buggy Location

**b01.scala, line 108–111:**
```scala
// Previous state snapshot for formal assertions (init to e to avoid
// matching stato's init value a, which would cause a false failure on cycle 0)
val prevStato = RegNext(stato, b01State.e)
```

### Root Cause Description

The root cause is a **designer-introduced initialization conflict** between two formal assertions:

1. **Assertion `no_self_loop_state_machine`** (line 127): `stato =/= prevStato`
   - `stato` initializes to `a` (0)
   - To prevent this assertion from failing on cycle 0, `prevStato` must NOT be `a`
   - It was initialized to `e` (3) to satisfy this

2. **Assertion `overflow_when_in_state_e`** (line 123): `prevStato =/= e || overflwReg`
   - `overflwReg` initializes to `false` (0)
   - At cycle 0: `prevStato` = `e` (3), so `prevStato =/= e` is **false**
   - `overflwReg` = 0, so the disjunction is **false**
   - The assertion **fails**

3. **Assertion `overflow_only_in_state_e`** (line 116): `!overflwReg || prevStato === e`
   - At cycle 0: `!overflwReg` = `true`, so this assertion passes

### Why It Fails at Cycle 0

The Chisel `RegNext(stato, b01State.e)` creates a register that holds `b01State.e` at cycle 0 before any clock edge. Simultaneously, `overflwReg` is initialized to `false.B`. The combination of `prevStato=e` and `overflwReg=0` violates the assertion `prevStato =/= e || overflwReg` on the very first cycle.

### Evidence from Waveform

- At `t=0`: `prevStato` = `011` (binary) = 3 (decimal) = `b01State.e`
- At `t=0`: `overflwReg` = 0
- At `t=0`: `b01.overflow_when_in_state_e` = 1 (assertion firing high = violation detected)

The waveform shows NO state transitions — the entire trace is a single snapshot at time 0, confirming this is purely an initialization issue.

### Fix Recommendation

Change the initialization value of `prevStato` from `b01State.e` to `b01State.b` (value 1), which satisfies ALL assertions on cycle 0:

- **Assertion 4** (`stato =/= prevStato`): `stato=a (0) =/= prevStato=b (1)` → **true** ✓
- **Assertion 2** (`prevStato =/= e || overflwReg`): `prevStato=b (1) =/= e (3)` → **true** ✓
- **Assertion 1** (`!overflwReg || prevStato === e`): `!overflwReg=true` → **true** ✓

**Fix** (b01.scala, line 111):
```scala
// Change from:
val prevStato = RegNext(stato, b01State.e)
// To:
val prevStato = RegNext(stato, b01State.b)
```

Any state value **other than `a`** (which would break assertion 4) and **other than `e`** (which would break assertion 2) would work. `b01State.b` is a natural choice since it is the first valid "previous state" in the FSM transition sequence (state `a` transitions to either `b` or `f`).
