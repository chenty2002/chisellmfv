# Counterexample Analysis Report: state_progress_every_cycle

## 1. Verification Environment

- **Top Module**: `b01` (Chisel module)
- **Source File**: `b01.scala`
- **Design Under Test**: A finite state machine implementing the ITC'99 b01 benchmark. The module has:
  - Inputs: `io.LINE1`, `io.LINE2` (Bool)
  - Outputs: `io.OUTP`, `io.OVERFLW` (Bool)
  - Internal state register `stato` (3-bit, 8 states: `a, b, c, e, f, g, wf0, wf1`)
  - Output registers `outpReg`, `overflwReg`
- **Formal Tool**: JasperGold / Chisel LTL assertions
- **Waveform File**: `verilog/extra_bench/itc99_b01/b01.state_progress_every_cycle.fst`

## 2. Violated Assertion

- **Full Assertion Name**: `state_progress_every_cycle`
- **Code Snippet** (from `b01.scala`, lines 112-117):

```scala
  // Bounded liveness / progress: the FSM state must change every cycle.
  // No state in this machine has a self-loop; every state unconditionally
  // transitions to a different state each cycle, guaranteeing forward progress.
  AssertProperty(RegNext(stato) =/= stato, None, None, Some("state_progress_every_cycle"))
```

- **Natural Language Description**: The FSM state must change every cycle. The property `RegNext(stato) =/= stato` checks that the current state is not equal to the previous cycle's state, which would only be true if the state machine is making forward progress.

- **File Location**: `b01.scala`, lines 114-115

## 3. Waveform Information

- **Full Waveform Path**: `verilog/extra_bench/itc99_b01/b01.state_progress_every_cycle.fst`
- **Total Duration**: 1 cycle (10 ns)
- **Time Range**: 0 ns → 10 ns
- **Key Time Point**: 0 ns (the only time point evaluated)

### Critical Signal Values at Time 0 ns

| Signal | Value | Description |
|--------|-------|-------------|
| `b01.state_progress_every_cycle` | 0 | Assertion output (0 = FAIL) |
| `b01.stato [2:0]` | 000 (binary) = `a` | Current FSM state |
| `b01.REG_1 [2:0]` | 000 (binary) = `a` | `RegNext(stato)` — previous cycle's state |
| `b01.:jasper_formal_reset` | 0 | Reset is de-asserted |
| `b01.:jasper_formal_clock` | 1 | Clock is high |
| `b01.io_LINE1` | 0 | Primary input 1 |
| `b01.io_LINE2` | 0 | Primary input 2 |
| `b01.io_OUTP` | 0 | Output |
| `b01.io_OVERFLW` | 0 | Overflow output |
| `b01.outpReg` | 0 | Output register |
| `b01.overflwReg` | 0 | Overflow register |
| `b01.REG` | 0 | `RegNext(stato === b01State.e)` (1-bit) |

## 4. Root Cause Analysis

### Classification: **Assertion Error** (Incorrect Assertion)

The assertion property is conceptually correct — the FSM indeed changes state every cycle and has no self-loops. However, the **encoding** of the assertion using `RegNext(stato) =/= stato` causes a false negative on the very first cycle after reset.

### Root Cause

**Buggy Code Location**: `b01.scala`, line 114-115

```scala
AssertProperty(RegNext(stato) =/= stato, None, None, Some("state_progress_every_cycle"))
```

**Bug Description**: The assertion `RegNext(stato) =/= stato` fails at time 0 because:

1. `stato` is initialized with `RegInit(b01State.a)` — this gives it an initial value of `a` (0x0 = 3'b000)
2. `RegNext(stato)` creates an implicit register with **no explicit initial value**, which defaults to 0 (3'b000 = state `a`)
3. Therefore, at cycle 0 (immediately after reset is de-asserted), both `stato` and `RegNext(stato)` hold the identical value `000` (state `a`)
4. The inequality `RegNext(stato) =/= stato` evaluates to **false**, causing the assertion to fail

**Evidence from Waveform**:
- `b01.stato [2:0]` = `000` (state `a`) at time 0
- `b01.REG_1 [2:0]` = `000` (`RegNext(stato)`) at time 0
- Both are identical, so `RegNext(stato) =/= stato` is false

**Why This Is Incorrect**: The assertion should only be checked **after the first cycle completes**. After cycle 0, the FSM always transitions to a different state:
- From `a`: goes to `b` (if not both inputs) or `f` (if both inputs)
- No state in this machine has a self-loop, so forward progress is guaranteed

### Proposed Fix

The assertion needs a disable condition (reset-like) that skips the first cycle. One correct approach:

**Option 1**: Add a disable condition using a one-cycle initial flag:

```scala
val initReg = RegInit(true.B)
initReg := false.B
AssertProperty(RegNext(stato) =/= stato, None, Some(initReg), Some("state_progress_every_cycle"))
```

This adds a disable condition `initReg` that is true only during the first cycle, preventing the assertion from being checked at time 0 when both `RegNext(stato)` and `stato` are the same.

**Option 2**: Use `past(stato)` instead of `RegNext(stato)`, if the LTL library provides proper first-cycle handling for `past()`:

```scala
import chisel3.ltl._
AssertProperty(past(stato) =/= stato, None, None, Some("state_progress_every_cycle"))
```

**Option 3**: Use `RegNext(stato, b01State.a)` with an explicit initial value for the delay register to make it different from the state's initial value on the first cycle. However, this doesn't help since `stato` also starts at `a`.

The most robust and clear fix is **Option 1** — adding a disable condition that masks the initial cycle.
