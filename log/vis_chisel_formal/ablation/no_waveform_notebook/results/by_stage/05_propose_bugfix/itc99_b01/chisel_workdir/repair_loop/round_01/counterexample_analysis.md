# Counterexample Analysis Report: `OVERFLW_only_in_state_e`

## 1. Verification Environment

### Top Module
- **Module**: `b01` (Chisel module extending `Module with Formal`)
- **Source**: `chisel/extra_bench/itc99_b01/b01.scala`
- **Generated Verilog**: `chisel/extra_bench/itc99_b01/generated/`

### Design Description
The `b01` module implements an 8-state finite state machine (states: `a`, `b`, `c`, `e`, `f`, `g`, `wf0`, `wf1`) with two inputs (`LINE1`, `LINE2`) and two outputs (`OUTP`, `OVERFLW`). The FSM transitions based on input combinations, computing `OUTP` as either XOR or XNOR of the inputs depending on the current state. The `OVERFLW` output is intended to be asserted only when the machine is in state `e`.

### Key Components
| Signal | Type | Description |
|--------|------|-------------|
| `stato` | `RegInit(b01State.a)` [2:0] | Current state register |
| `overflwReg` | `RegInit(false.B)` | Registered overflow output |
| `outpReg` | `RegInit(false.B)` | Registered output for `OUTP` |
| `io.LINE1` | Input `Bool()` | Primary input 1 |
| `io.LINE2` | Input `Bool()` | Primary input 2 |
| `io.OVERFLW` | Output `Bool()` | Overflow indicator (= `overflwReg`) |
| `is_e` | Combinational | `stato === b01State.e` |

### State Encoding (ChiselEnum order)
| State | Binary | Decimal |
|-------|--------|---------|
| a | 000 | 0 |
| b | 001 | 1 |
| c | 010 | 2 |
| e | 011 | 3 |
| f | 100 | 4 |
| g | 101 | 5 |
| wf0 | 110 | 6 |
| wf1 | 111 | 7 |

---

## 2. Violated Assertion

### Assertion Name
`OVERFLW_only_in_state_e` (extracted from waveform filename `b01.OVERFLW_only_in_state_e.fst`)

### Code Snippet
```scala
// File: b01.scala, lines 48-49
// Safety 2: OVERFLW is only asserted when in state e
// If OVERFLW is high, we must be in state e
fvAssert(!io.OVERFLW || is_e, "OVERFLW_only_in_state_e")
```

### Property Description
The assertion states that whenever `io.OVERFLW` is high (true), the FSM must currently be in state `e`. Formally: `io.OVERFLW ⇒ is_e`, i.e., the overflow flag should only be asserted when the machine is in the `e` state.

---

## 3. Waveform Information

### Waveform File
`verilog/extra_bench/itc99_b01/b01.OVERFLW_only_in_state_e.fst`

### Time Range
0 ns → 60 ns (6 clock cycles, clock period = 10 ns, rising edges at 0, 10, 20, 30, 40, 50 ns)

### Assertion Failure Point
- **Time**: 50 ns (rising edge of clock cycle 5)
- **Assertion signal** (`b01.OVERFLW_only_in_state_e`): transitions from `1` (passing) to `0` (failing) at time 50 ns

### Critical Signal Values at Key Time Points

| Time (ns) | `stato [2:0]` | State | `is_e` | `overflwReg` | `io_OVERFLW` | `io_LINE1` | `io_LINE2` | Event |
|-----------|---------------|-------|--------|-------------|-------------|-----------|-----------|-------|
| 0 | 000 | a | 0 | 0 | 0 | 1 | 1 | Reset/initial |
| 10 | 100 | f | 0 | 0 | 0 | 0 | 0 | a→f (LINE1&LINE2) |
| 20 | 010 | c | 0 | 0 | 0 | 0 | 1 | f→c (!LINE1&!LINE2) |
| 30 | 110 | wf0 | 0 | 0 | 0 | 1 | 1 | c→wf0 (LINE1&LINE2) |
| 40 | 011 | **e** | **1** | 0 | 0 | 0 | 0 | wf0→e (LINE1&LINE2) |
| 45 | 011 | **e** | **1** | 0 | 0 | 0 | 0 | Mid-cycle in state e |
| **50** | **001** | **b** | **0** | **1** | **1** | 0 | 0 | **ASSERTION FAILS** |

### Signal Traces at Failure (Time 50 ns)
- `b01.stato [2:0]` = `001` (state **b**)
- `b01.is_e` = `0`
- `b01.io_OVERFLW` = `1`
- `b01.overflwReg` = `1`
- `b01.io_LINE1` = `0`
- `b01.io_LINE2` = `0`

---

## 4. Root Cause Analysis

### Classification: **Bug in the Original Design (DUT Bug)**

### Bug Location
- **File**: `chisel/extra_bench/itc99_b01/b01.scala`
- **Line 86**: `overflwReg := true.B` inside `is(b01State.e)` block
- **Affected Module**: `b01`

### Bug Description

The `overflwReg` is a **register** (`RegInit(false.B)` at line 15), meaning its value appears at the output **one clock cycle after** it is assigned. The `OVERFLW` output (`io.OVERFLW`) is driven directly by `overflwReg`.

In state `e`, the code sets `overflwReg := true.B` (line 86). This causes `overflwReg` to become `1` on the **next** clock edge. However, on that same clock edge, the state register `stato` transitions away from `e` to the next state (`b` in this counterexample, or `f` if LINE1&LINE2 is true). Since `is_e` is a **combinational** decode of `stato`, it immediately becomes `0` once the state transitions.

This creates a **one-cycle mismatch**: the `OVERFLW` output goes high exactly one cycle after leaving state `e`, violating the assertion that `OVERFLW` should only be asserted when in state `e`.

### Detailed Failure Trace

1. **Cycle 3 (time 30–40)**: State = `wf0` (110), inputs `LINE1=1, LINE2=1`. Since `LINE1 & LINE2` is true, the next state is set to `e`.

2. **Cycle 4 (time 40–50)**: State becomes `e` (011), `is_e=1`. At this point:
   - Inputs are `LINE1=0, LINE2=0` → `!(LINE1 & LINE2)` is true → next state = `b`
   - `overflwReg := true.B` is executed (line 86) — this is scheduled for the next clock edge
   - `io_OVERFLW` is still `0` (the previous value of `overflwReg`)

3. **Cycle 5 (time 50–60)**: Clock edge at 50 ns:
   - `stato` updates from `e` (011) to `b` (001) — `is_e` becomes `0`
   - `overflwReg` updates from `0` to `1` (the `true.B` assigned in previous cycle)
   - `io_OVERFLW` becomes `1`
   - **Assertion violation**: `io_OVERFLW=1` but `is_e=0` → `(!io.OVERFLW || is_e)` = `(0 || 0)` = `false`

### Why the Bug Exists

The design uses a **registered** output (`overflwReg`) for the overflow flag, but the intended behavior is that `OVERFLW` should be **asserted only while in state `e`**. Because the assignment `overflwReg := true.B` only appears in the `is(b01State.e)` switch branch, the register captures `true` at the clock edge when leaving state `e`, not while in it.

All other states explicitly set `overflwReg := false.B` (lines 77, 95, 104, 113, 122, 131, 140), so on the **following** cycle after leaving `e`, `overflwReg` correctly returns to `0`. But the damage is done in the cycle immediately after leaving `e`.

### Fix Suggestion (DUT Fix)

Make `io.OVERFLW` a **combinational** output derived directly from the state, rather than a registered value. For example, replace the registered output with:

```scala
// Instead of: io.OVERFLW := overflwReg  (line 20)
io.OVERFLW := is_e
```

Or, if a registered output is required for timing, the assignment should ensure `overflwReg` is cleared **before** the state transition takes effect:

```scala
// In state e, use a Wire instead
val overflowWire = WireDefault(false.B)
overflowWire := is_e  // combinational
io.OVERFLW := overflowWire
```

### Evidence Summary

| Evidence | Source |
|----------|--------|
| `io_OVERFLW=1` at time 50 ns | Waveform trace |
| `is_e=0` at time 50 ns | Waveform trace |
| `stato=001(b)` at time 50 ns | Waveform trace |
| `overflwReg := true.B` only in state e | b01.scala line 86 |
| `overflwReg` is `RegInit(false.B)` | b01.scala line 15 |
| `io.OVERFLW := overflwReg` (registered connection) | b01.scala line 20 |
| Assertion fails at clock edge when state leaves e | Waveform time 50 ns |
