# Counterexample Analysis Report: usb_rst_consistent

## 1. Verification Environment

### Top Module
- **Module**: `usb_phy` (package: `llmverify`)
- **File**: `usb_phy.scala`
- **Structure**: The `usb_phy` module instantiates two submodules:
  - `usb_tx_phy` (placeholder implementation)
  - `usb_rx_phy` (placeholder implementation)
- **Key Components**:
  - `rst_cnt` (5-bit register): USB reset counter, counts cycles in SE0 state
  - `usb_rst_reg` (1-bit register): USB reset flag, asserts when counter reaches 31
  - `io.rst` (input): External reset signal (active high)
  - `io.LineState_o` (2-bit output): Line state from RX PHY (0 = SE0)
  - `usb_rst_terminal` (combinational): `rst_cnt === 31.U`
- **Description**: The USB PHY generates a USB reset signal (`usb_rst_reg`) after detecting 32 consecutive cycles (0 to 31) of SE0 (Single-Ended Zero, `LineState_o === 0`) while external reset (`io.rst`) is asserted.

## 2. Violated Assertion

### Assertion Name
- `usb_rst_consistent` (from waveform filename `usb_phy.usb_rst_consistent.fst`)

### Code Snippet
```scala
// File: usb_phy.scala, Line ~166
fvAssert(!usb_rst_reg || rst_cnt === 31.U, "usb_rst_consistent")
```

### Property Description
The assertion states: **If `usb_rst_reg` is asserted (true), then `rst_cnt` must equal 31**. In other words, the USB reset flag should only be set when the reset counter is at its terminal count. This ensures consistency between the counter value and the reset register.

### File Location
- **File**: `usb_phy.scala`
- **Line**: ~166 (the assertion definition)

## 3. Waveform Information

### Waveform File
- **Path**: `verilog/extra_bench/usbphy/usb_phy.usb_rst_consistent.fst`
- **Duration**: 0 ns → 330 ns (33 clock cycles at 10 ns period)

### Key Time Points

| Time (ns) | Event |
|-----------|-------|
| 0 | Initial state: io_rst=1, rst_cnt=0, usb_rst_reg=0, LineState=0 |
| 0-300 | rst_cnt counts from 0 to 30 (one per cycle, enabled by fs_ce=true) |
| **310** | **Clock rising edge: rst_cnt becomes 31 (11111), io_rst falls to 0** |
| **320** | **Clock rising edge: rst_cnt becomes 0, usb_rst_reg becomes 1 → ASSERTION FAILURE** |

### Critical Signal Values at Failure Point (time = 320 ns)

| Signal | Value |
|--------|-------|
| `usb_phy.rst_cnt [4:0]` | `00000` (0) |
| `usb_phy.usb_rst_reg` | `1` |
| `usb_phy.io_rst` | `0` |
| `usb_phy.io_LineState_o [1:0]` | `00` (SE0) |
| `usb_phy.rst_cnt_next [4:0]` | `00000` (0) |

### Assertion Evaluation at Failure
```
!usb_rst_reg || rst_cnt === 31.U
= !1 || 0 === 31
= 0 || 0
= 0  → FALSE (assertion violated)
```

## 4. Root Cause Analysis

### Buggy Code Location
- **File**: `usb_phy.scala`
- **Lines**: ~149-159 (the USB reset generation logic block)
- **Buggy construct**: The unconditional register assignment `usb_rst_reg := usb_rst_terminal` on line ~159

### Bug Description

The bug is a **register assignment override** caused by an unconditional `:=` assignment placed outside the `when`-`elsewhen` chain, which silently overrides the conditional reset inside the `when(!io.rst)` block.

**Relevant source code** (simplified from `usb_phy.scala`):

```scala
when(!io.rst) {
    rst_cnt := 0.U
    usb_rst_reg := false.B          // (A) Reset usb_rst_reg when io.rst goes low
}.elsewhen(io.LineState_o =/= 0.U) {
    rst_cnt := 0.U
}.elsewhen(!usb_rst_terminal && fs_ce) {
    rst_cnt := rst_cnt + 1.U
}

usb_rst_reg := usb_rst_terminal     // (B) UNCONDITIONAL - overrides (A)!
```

In Chisel's generated Verilog, when a register has both a conditional assignment (inside a `when` block) and an unconditional assignment (outside), both appear as non-blocking assignments in the same `always` block. The Verilog rule for non-blocking assignments is: **the last assignment to the same register wins**. Since the unconditional assignment `usb_rst_reg := usb_rst_terminal` appears *after* the `when` block's `usb_rst_reg := false.B`, it overrides the conditional reset. The effective logic becomes:

```
usb_rst_reg = usb_rst_terminal   ← ALWAYS, reset is ignored!
```

### Failure Sequence (Step-by-Step)

1. **Time 0-300**: `rst_cnt` counts from 0 to 30. `io_rst = 1`, `LineState = 0`. `usb_rst_reg = 0`.

2. **Time 310** (rising clock edge):
   - `rst_cnt` transitions to 31 (11111) — terminal count reached.
   - `io_rst` transitions from 1 to 0 at the same clock edge.
   - **Combinational logic evaluation**:
     - `usb_rst_terminal = (rst_cnt === 31) = 1` (true, because rst_cnt is now 31).
     - The `when(!io.rst)` block fires (since io_rst = 0).
     - Inside the block: `rst_cnt_next = 0` and attempts `usb_rst_reg := false.B`.
     - **BUT** the unconditional `usb_rst_reg := usb_rst_terminal` (value = 1) **overrides** the conditional assignment, so `usb_rst_reg` gets 1, not 0.

3. **Time 320** (rising clock edge):
   - `rst_cnt` updates to 0 (from `rst_cnt_next` that was computed at time 310).
   - `usb_rst_reg` updates to 1 (from the overridden unconditional assignment at time 310).
   - **Result**: `usb_rst_reg = 1` but `rst_cnt = 0` — the assertion `!usb_rst_reg || rst_cnt === 31.U` evaluates to `false`.

### Why the Assertion Fails

The assertion requires that whenever `usb_rst_reg` is true, `rst_cnt` must equal 31. At time 320, `usb_rst_reg` is true (set unconditionally at time 310), but `rst_cnt` is 0 (properly reset by the `when(!io.rst)` block). This inconsistency violates the property.

### Classification
- **Error Type**: **Bug in the Original Design (DUT Bug)**
- **Root Cause Category**: Register assignment logic error — unconditional assignment overrides conditional reset

### Suggested Fix

Remove the unconditional assignment and place the `usb_rst_reg` update inside the `when`-`elsewhen` chain so that the reset condition has proper priority:

```scala
when(!io.rst) {
    rst_cnt := 0.U
    usb_rst_reg := false.B
}.elsewhen(io.LineState_o =/= 0.U) {
    rst_cnt := 0.U
}.elsewhen(!usb_rst_terminal && fs_ce) {
    rst_cnt := rst_cnt + 1.U
}.elsewhen(usb_rst_terminal) {
    usb_rst_reg := true.B
}
```

This ensures that:
1. When `io.rst` goes low: `usb_rst_reg` is properly cleared to `false.B` (condition 1 fires first).
2. When the counter reaches 31 under normal conditions: `usb_rst_reg` is set to `true.B` (condition 4 fires).
3. There is no overriding unconditional assignment that could corrupt the register value.
