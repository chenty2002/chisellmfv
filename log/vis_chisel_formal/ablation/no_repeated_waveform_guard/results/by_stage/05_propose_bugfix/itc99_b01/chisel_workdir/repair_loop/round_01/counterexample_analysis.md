# Counterexample Analysis Report: `overflw_high_in_state_e`

## 1. Verification Environment

- **Top module**: `b01` in package `llmverify`
- **Generated Verilog**: `chisel/extra_bench/itc99_b01/generated/`
- **Waveform file**: `verilog/extra_bench/itc99_b01/b01.overflw_high_in_state_e.fst`
- **Design under test**: An 8-state finite state machine (FSM) with states `a, b, c, e, f, g, wf0, wf1`. The FSM takes two inputs (`LINE1`, `LINE2`) and produces two outputs (`OUTP`, `OVERFLW`). The `OVERFLW` output is driven by a register `overflwReg`, which should be high only when in state `e`.

## 2. Violated Assertion

- **Assertion name**: `overflw_high_in_state_e` (from filename: `b01.overflw_high_in_state_e.fst`)
- **File**: `b01.scala`, line 112
- **Code snippet**:
  ```scala
  // Safety 1: OVERFLW is only asserted when in state e
  fvAssert(stato =/= b01State.e || overflwReg, "overflw_high_in_state_e")
  ```
- **Property description**: The assertion checks that whenever the FSM is in state `e`, the `overflwReg` register (which drives `io.OVERFLW`) must be high (`true.B`). Logically: `!(stato == e) || overflwReg`.

## 3. Waveform Information

- **Full path**: `verilog/extra_bench/itc99_b01/b01.overflw_high_in_state_e.fst`
- **Time range**: 0 ns to 50 ns (5 clock cycles, period 10 ns)
- **Clock edge times** (posedge): 0 ns, 10 ns, 20 ns, 30 ns, 40 ns, 50 ns
- **Failure time**: 40 ns — assertion `overflw_high_in_state_e` drops from `1` to `0`

### Critical signal values at key time points:

| Time (ns) | stato (3-bit) | FSM State | overflwReg | LINE1 | LINE2 | Assertion |
|-----------|---------------|-----------|------------|-------|-------|-----------|
| 0         | 000           | a         | 0          | 1     | 1     | 1 (pass)  |
| 10        | 100           | f         | 0          | 0     | 0     | 1 (pass)  |
| 20        | 010           | c         | 0          | 1     | 0     | 1 (pass)  |
| 30        | 110           | wf0       | 0          | 1     | 1     | 1 (pass)  |
| **40**    | **011**       | **e**     | **0**      | 1     | 1     | **0 (FAIL)** |

## 4. Root Cause Analysis

### Bug Location

- **File**: `b01.scala`
- **Buggy code**: Lines 93-107, specifically the `is(b01State.wf0)` and `is(b01State.wf1)` blocks
- **Bug type**: Design bug (`dut_bug`)

### Description of the Bug

The `overflwReg` register is a registered output (`RegInit(false.B)`). Its value is updated at each positive clock edge based on the **current state's** combinational logic. The assertion requires that whenever `stato === e`, the `overflwReg` is high.

However, for the **first cycle after entering state `e`**, the value of `overflwReg` was computed by the **previous state's** logic (either `wf0` or `wf1`), both of which set `overflwReg := false.B`. The state `e` block's assignment `overflwReg := true.B` only takes effect at the **next** clock edge, creating a one-cycle window where the assertion is violated.

### Detailed Trace

**Cycle at time 30-40** (stato = wf0):
- Current state: `wf0` (110)
- Inputs: `LINE1=1`, `LINE2=1`
- Executed code (line 96-101):
  ```scala
  is(b01State.wf0) {
    when(io.LINE1 & io.LINE2) {     // 1 & 1 = true
      stato := b01State.e            // next stato = e
    }
    ...
    overflwReg := false.B            // *** BUG: overflwReg set to false ***
  }
  ```
- **At clock posedge (40 ns)**: `stato` becomes `e` (011), `overflwReg` becomes `0`

**Cycle at time 40-50** (stato = e):
- Current state: `e` (011)
- `overflwReg` is `0` (from previous state's assignment)
- **Assertion violation**: `stato === e` but `overflwReg === 0`
- Executed code (line 55-60):
  ```scala
  is(b01State.e) {
    ...
    overflwReg := true.B             // This takes effect at NEXT clock (50 ns)
  }
  ```
- **At clock posedge (50 ns)**: `overflwReg` would finally become `1`, but the assertion has already fired at 40 ns

### Root Cause Summary

The FSM design sets `overflwReg := false.B` unconditionally in every state except `e`. When entering state `e` from either `wf0` or `wf1`, the `overflwReg` is loaded with `false` (the value computed by the previous state's logic), and the `true.B` assignment within state `e`'s block is deferred by one clock cycle.

This is the same issue that affects state `e` transitions from both predecessor states:
- `wf0` → `e` (when `LINE1 & LINE2`)
- `wf1` → `e` (when `LINE1 | LINE2`)

### Fix

The fix is to move the `overflwReg` assignments inside the `when`/`otherwise` branches for the `wf0` and `wf1` states so that the transition to state `e` sets `overflwReg := true.B` instead of `false.B`:

**In `is(b01State.wf0)` (line 96-101):**
```scala
is(b01State.wf0) {
  when(io.LINE1 & io.LINE2) {
    stato := b01State.e
    overflwReg := true.B          // FIX: Set high when entering state e
  }.otherwise {
    stato := b01State.a
    overflwReg := false.B         // Keep low when returning to reset state
  }
  outpReg := io.LINE1 ^ io.LINE2
}
```

**In `is(b01State.wf1)` (line 103-108):**
```scala
is(b01State.wf1) {
  when(io.LINE1 | io.LINE2) {
    stato := b01State.e
    overflwReg := true.B          // FIX: Set high when entering state e
  }.otherwise {
    stato := b01State.a
    overflwReg := false.B         // Keep low when returning to reset state
  }
  outpReg := ~(io.LINE1 ^ io.LINE2)
}
```

This ensures that `overflwReg` is already `true.B` when the FSM transitions into state `e`, eliminating the one-cycle mismatch.
