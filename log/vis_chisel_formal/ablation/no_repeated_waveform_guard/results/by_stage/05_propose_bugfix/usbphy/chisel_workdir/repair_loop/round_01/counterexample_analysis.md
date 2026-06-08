# Counterexample Analysis Report: usb_rst_implies_cnt_31

## 1. Verification Environment

- **Top Module**: `usb_phy` (in package `llmverify`)
- **Design Structure**: The USB PHY module contains:
  - `usb_tx_phy` instance (`i_tx_phy`) — placeholder TX PHY
  - `usb_rx_phy` instance (`i_rx_phy`) — placeholder RX PHY
  - USB reset generation logic with `rst_cnt` counter and `usb_rst_reg` register
- **Key Components**: 
  - `rst_cnt` (RegInit, 5-bit): Counter that increments when SE0 (LineState=0) persists
  - `usb_rst_reg` (RegInit, 1-bit): Register that captures when `rst_cnt` reaches 31
  - `io.usb_rst`: Output driven by `usb_rst_reg`
  - `io.rst`: System reset input (active high) — when deasserted (low), resets `rst_cnt`

## 2. Violated Assertion

- **Assertion Name**: `usb_rst_implies_cnt_31`
- **Waveform File**: `usb_phy.usb_rst_implies_cnt_31.fst`
- **Code Location**: `usb_phy.scala`, line 95

```scala
// Safety 3: Once usb_rst is asserted, rst_cnt must be 31 in the same cycle
// (The counter reaching 31 is what triggers the reset flag)
fvAssert(!io.usb_rst || (rst_cnt === 31.U), "usb_rst_implies_cnt_31")
```

- **Property Description**: If `io.usb_rst` is asserted (high), then `rst_cnt` must equal 31 in the same cycle. This captures the invariant that the USB reset signal is only ever set when the counter has reached its terminal count of 31.

## 3. Waveform Information

- **Waveform File**: `verilog/extra_bench/usbphy/usb_phy.usb_rst_implies_cnt_31.fst`
- **Time Range**: 0 ns – 330 ns (33 clock cycles)
- **Failure Time**: 320 ns (the assertion signal `usb_rst_implies_cnt_31` transitions from 1→0 at this point)

### Key Signal Values at Failure Point (t=320 ns)

| Signal | Value | Description |
|--------|-------|-------------|
| `usb_phy.io_usb_rst` | 1 | USB reset output is asserted |
| `usb_phy.rst_cnt [4:0]` | 00000 (0) | Counter is zero — **inconsistent!** |
| `usb_phy.io_rst` | 0 | System reset is deasserted |
| `usb_phy.usb_rst_reg` | 1 | Inner usb_rst register is set |
| `usb_phy.r [4:0]` | 11111 (31) | Previous-cycle value of some related signal |

### Timeline of Key Events

| Time (ns) | Clock | Event |
|-----------|-------|-------|
| 290 | Rising edge | `rst_cnt` = 29, `io.rst` = 1, `io.usb_rst` = 0 |
| 300 | Rising edge | `rst_cnt` = 30, `io.rst` = 1 |
| 310 | Rising edge | `rst_cnt` = 31 (increments from 30). `io.rst` deasserts (1→0) **at this same time** |
| 310–315 | | `rst_cnt` = 31, `io.rst` = 0, `usb_rst_reg` = 0 |
| 320 | Rising edge | **CRITICAL**: `rst_cnt` resets to 0 (due to `!io.rst`), but `usb_rst_reg` becomes 1 (reads old `rst_cnt`=31). Assertion fails immediately after. |

## 4. Root Cause Analysis

### Bug Classification: **Bug in the Original Design (DUT Bug)**

### Root Cause Description

The bug is a **register update race condition** in the USB reset generation logic (`usb_phy.scala`, lines 74–83).

```scala
// Lines 74-83 — Buggy logic
when(!io.rst) {           // ← rst_cnt resets when system reset deasserts
    rst_cnt := 0.U
}.elsewhen(io.LineState_o =/= 0.U) {
    rst_cnt := 0.U
}.elsewhen(!usb_rst_reg && fs_ce) {
    rst_cnt := rst_cnt + 1.U
}

usb_rst_reg := (rst_cnt === 31.U)   // ← reads rst_cnt's OLD value
io.usb_rst := usb_rst_reg
```

Both `rst_cnt` and `usb_rst_reg` are Chisel `RegInit` registers (sequential elements). When `io.rst` deasserts (goes low) in the same clock cycle that `rst_cnt` has reached 31, the following happens at the clock edge:

1. **`rst_cnt`** is assigned `0.U` because the `when(!io.rst)` condition is true.
2. **`usb_rst_reg`** is assigned `(rst_cnt === 31.U)` which evaluates using the **old** (pre-update) value of `rst_cnt`, which is 31. So `usb_rst_reg` becomes true.

After the clock edge, `rst_cnt` = 0 but `usb_rst_reg` = 1 (and thus `io.usb_rst` = 1). This violates the assertion: `io.usb_rst` is high but `rst_cnt` is 0, not 31.

### Evidence from Waveform

- At t=310: `rst_cnt` = 31 (just incremented). `io.rst` transitions 1→0.
- At t=320 (rising clock edge): `rst_cnt` becomes 0 (`!io.rst` took effect). Simultaneously, `usb_rst_reg` becomes 1 (captured the old `rst_cnt`=31 comparison). The resulting state has `io.usb_rst`=1 and `rst_cnt`=0, which is logically inconsistent.

### Why This Is a Bug

The design intent is clear: `usb_rst_reg` should only be asserted when `rst_cnt` has counted up to 31 under normal conditions (SE0 persisting while `io.rst` is high). When the system reset `io.rst` deasserts, the counter should reset AND the USB reset flag should not be spuriously asserted. The current code fails to coordinate these two updates, creating a one-cycle window where the state is inconsistent.

### Fix

The fix should ensure that when `!io.rst` deasserts (reset condition), `usb_rst_reg` is also cleared. Additionally, the comparison for `usb_rst_reg` should use the **next** value of `rst_cnt` (the value that will be stored after the when-block assignment), not the current value. One possible fix:

```scala
val next_rst_cnt = WireInit(rst_cnt)
when(!io.rst) {
    next_rst_cnt := 0.U
}.elsewhen(io.LineState_o =/= 0.U) {
    next_rst_cnt := 0.U
}.elsewhen(!usb_rst_reg && fs_ce) {
    next_rst_cnt := rst_cnt + 1.U
}

rst_cnt := next_rst_cnt
usb_rst_reg := (next_rst_cnt === 31.U)
io.usb_rst := usb_rst_reg
```

This ensures `usb_rst_reg` compares against the **same value** that `rst_cnt` will become, eliminating the race condition.
