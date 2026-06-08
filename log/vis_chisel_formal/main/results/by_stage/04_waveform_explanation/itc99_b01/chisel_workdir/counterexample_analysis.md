# Counterexample Analysis Report: itc99_b01

## 1. Verification Environment

- **Top Module Name:** `b01`
- **Source File:** `chisel/extra_bench/itc99_b01/b01.scala`
- **Structure:** The `b01` module implements a finite state machine (FSM) with 8 states (a, b, c, e, f, g, wf0, wf1) defined via ChiselEnum. It has two inputs (`LINE1`, `LINE2`) and two registered outputs (`OUTP`, `OVERFLW`).
- **Key Components:**
  - `stato` (3-bit register): FSM state register
  - `outpReg` (1-bit register): Output data register
  - `overflwReg` (1-bit register): Overflow flag register
  - Inputs `io_LINE1`, `io_LINE2`: Control inputs driving state transitions
  - Outputs `io_OUTP`, `io_OVERFLW`: Registered outputs

## 2. Violated Assertion

- **Assertion Name (from waveform filename):** `overflow_only_in_state_e`
- **Waveform File:** `verilog/extra_bench/itc99_b01/b01.overflow_only_in_state_e.fst`
- **Code Snippet** (b01.scala, lines 107-108):
  ```scala
  // Safety 1: Overflow flag is only asserted in the overflow state (state 'e')
  fvAssert(!overflwReg || stato === b01State.e, "overflow_only_in_state_e")
  ```
- **Natural Language Property:** "The overflow flag (`overflwReg` / `io_OVERFLW`) must never be asserted (high) unless the FSM is currently in state `e`." In other words, whenever `overflwReg` is 1, the state must be `e`.
- **File Location:** `b01.scala`, lines 107–108

## 3. Waveform Information

- **Full Path:** `verilog/extra_bench/itc99_b01/b01.overflow_only_in_state_e.fst`
- **Duration:** 0 ns → 60 ns (6 clock cycles)
- **Clock:** Rising edges at 0, 10, 20, 30, 40, 50 ns (10 ns period, 50% duty cycle)
- **Failure Point:** At **t = 50 ns** (rising edge of cycle 6), the assertion signal `b01.overflow_only_in_state_e` transitions from 1 to 0.

### Key Signal Values at Failure Point (t = 50 ns)

| Signal | Value | Description |
|--------|-------|-------------|
| `b01.stato [2:0]` | `100` (state **f**) | Current FSM state |
| `b01.overflwReg` | `1` | Overflow flag (registered) |
| `b01.io_LINE1` | `1` | Input LINE1 |
| `b01.io_LINE2` | `1` | Input LINE2 |
| `b01.io_OVERFLW` | `1` | Output OVERFLW |
| `b01.overflow_only_in_state_e` | `0` | **ASSERTION FAILED** |

### State Encoding Reference

| Binary | State |
|--------|-------|
| 000 | a |
| 001 | b |
| 010 | c |
| 011 | **e** |
| 100 | **f** |
| 101 | g |
| 110 | wf0 |
| 111 | wf1 |

### Full Signal Timeline

| Time (ns) | Clock Edge | stato (before→after) | overflwReg | LINE1 | LINE2 | Event |
|-----------|-----------|----------------------|-----------|-------|-------|-------|
| 0 | Rising | (init) → a(000) | 0 | 1 | 1 | Initialization; next state = f (since L1 & L2) |
| 10 | Rising | a → f(100) | 0 | 0 | 0 | State f: !(L1\|L2) → next = c |
| 20 | Rising | f → c(010) | 0 | 0 | 1 | State c: !(L1&L2) → next = wf0 |
| 30 | Rising | c → wf0(110) | 0 | 1 | 1 | State wf0: L1&L2 → next = e |
| 40 | Rising | wf0 → e(011) | 0 | 1 | 1 | **State e entered**; overflwReg next = 1; stato next = f |
| **50** | **Rising** | **e → f(100)** | **0→1** | 1 | 1 | **ASSERTION FAILS**: overflwReg=1 but stato=f≠e |

## 4. Root Cause Analysis

### Bug Type: **DUT Bug** — Register-based overflow flag creates a one-cycle timing mismatch

### Buggy Code Location

**File:** `b01.scala`, lines 34–60 (the `switch(stato)` block, specifically the `is(b01State.e)` case)

### Description of the Bug

The `overflwReg` is declared as a **register** (`RegInit(false.B)` on line 18) and updated inside the FSM's combinatorial state-decoding `switch` block. When the FSM is in state **e** (binary `011`), the `is(b01State.e)` block (lines 48–55) performs two assignments:

```scala
is(b01State.e) {
  when(io.LINE1 & io.LINE2) {
    stato := b01State.f   // next state = f
  }.otherwise {
    stato := b01State.b
  }
  outpReg := io.LINE1 ^ io.LINE2
  overflwReg := true.B    // overflow flag set to true
}
```

These are **next-state** assignments to registers. On the next rising clock edge (t = 50 ns):

1. `stato` **updates** from state e (011) to state f (100)
2. `overflwReg` **updates** from 0 to 1 (the value computed while in state e)

At t = 50 ns, after the update, the circuit is in a state where `overflwReg = 1` but `stato = f ≠ e`. This violates the assertion `!overflwReg || stato === b01State.e` which evaluates to `!1 || (f === e)` = `0 || 0` = `0` (fail).

### Why This Is a Design Bug

The root cause is that `overflwReg` is a **registered** output that is set to `true.B` in state e, but by the time the register updates to `1`, the state has already transitioned to a different state (f). The overflow flag remains high for one extra cycle after leaving state e.

This creates a one-clock-cycle window where `overflwReg` is asserted while the FSM is not in state e. The property `overflow_only_in_state_e` is a reasonable safety invariant that the design should satisfy, and the current implementation with `overflwReg` as a register fails to uphold it.

### How to Fix

**Option 1 (Recommended — make overflow combinatorial):** Replace the register with a combinatorial assignment:

```scala
// Remove overflwReg register and wire io.OVERFLW directly
io.OVERFLW := (stato === b01State.e)
```

This ensures `io.OVERFLW` tracks the state exactly, turning high only when in state e.

**Option 2 (Modify the assertion):** If the registered behavior is intentional (e.g., matching the original ITC'99 b01 Verilog spec), the assertion should be adjusted to check the previous state:

```scala
val prevStato = RegNext(stato)
fvAssert(!overflwReg || prevStato === b01State.e, "overflow_only_in_state_e")
```

However, Option 1 is the safer fix as it maintains the intended safety property.

### Evidence from Waveform

- At **t = 40 ns** (rising edge, entering state e): `stato = 011` (e), `overflwReg = 0`, assertion passes.
- At **t = 45–49 ns** (in state e): `stato = 011` (e), `overflwReg` remains 0 throughout the entire clock cycle. The next-state computation sets `overflwReg_next = 1` and `stato_next = f`.
- At **t = 50 ns** (rising edge, exiting state e): `stato` updates to `100` (f), `overflwReg` updates to `1`. The assertion now sees `overflwReg = 1` with `stato = f ≠ e` and fails.

The causal chain is clear: state e's logic block sets both `stato := f` and `overflwReg := true.B` simultaneously. The register updates happen concurrently on the clock edge, and the assertion samples after the update, finding the mismatch.
