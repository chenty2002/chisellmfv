# Counterexample Analysis: usb_rst_stable Assertion Failure

## 1. Verification Environment

- **Top Module**: `usb_phy` (in package `llmverify`)
- **File**: `usb_phy.scala` (198 lines)
- **Key Components**:
  - `usb_phy` - Main top-level module with USB reset detection logic
  - `usb_tx_phy` - TX PHY submodule (placeholder implementation)
  - `usb_rx_phy` - RX PHY submodule (placeholder implementation)
- **Design Description**: A USB PHY controller that detects USB reset conditions. When the line state is SE0 (LineState_o == 0) and external reset (io.rst) is asserted for 32 consecutive cycles, it asserts `usb_rst_reg` to signal a USB reset event. The `usb_rst_reg` stays set until `io.rst` is deasserted.

## 2. Violated Assertion

- **Assertion Name**: `usb_rst_stable`
- **File**: `usb_phy.scala`, line 107
- **Code**:
  ```scala
  assertStableWhen(io.rst, usb_rst_reg.asUInt, "usb_rst_stable")
  ```
- **Intended Property** (per comment on lines 105-106):
  > "Once usb_rst_reg is set, it stays set until external reset (io.rst goes low). If usb_rst_reg is true and io.rst stays true, usb_rst_reg must remain true."
- **What the assertion actually checks**: `usb_rst_reg` must not change at ANY time when `io.rst` is true. This is stricter than intended.

## 3. Waveform Information

- **Waveform File**: `verilog/extra_bench/usbphy/usb_phy.usb_rst_stable.fst`
- **Duration**: 330 ns (33 cycles at 10 ns/cycle)
- **Key Time Points**:

| Time (ns) | io_rst | rst_cnt | usb_rst_reg | usb_rst_stable | Event |
|-----------|--------|---------|-------------|----------------|-------|
| 0         | 1      | 0       | 0           | 1              | Initial state, counting starts |
| 10        | 1      | 1       | 0           | 1              | Counter increments |
| ...       | 1      | ...     | 0           | 1              | Counter continues incrementing |
| 310       | 1      | 31      | 0           | 1              | Counter reaches terminal count |
| 320       | 1      | 31      | **1**       | **0**          | usb_rst_reg set; assertion FAILS |
| 330       | 1      | 31      | 1           | 0              | Assertion remains failed |

## 4. Root Cause Analysis

### Category: **Incorrect Assertion**

### Bug Location
- **File**: `usb_phy.scala`, **line 107**
- **Module**: `usb_phy`

### Description
The assertion `assertStableWhen(io.rst, usb_rst_reg.asUInt, "usb_rst_stable")` is incorrectly formulated. The `assertStableWhen(condition, signal)` primitive checks that `signal` does not change whenever `condition` is true. However, the design intentionally transitions `usb_rst_reg` from 0 to 1 while `io.rst` is true — this is the legitimate detection of a USB reset condition.

### Design Behavior (lines 78-87 of usb_phy.scala)
```scala
when(!io.rst) {
  rst_cnt := 0.U
  usb_rst_reg := false.B
}.elsewhen(io.LineState_o =/= 0.U) {
  rst_cnt := 0.U
}.elsewhen(!usb_rst_terminal && fs_ce) {
  rst_cnt := rst_cnt + 1.U
}.elsewhen(usb_rst_terminal) {
  usb_rst_reg := true.B        // <--- This transition happens while io.rst is true
}
```

### Evidence from Waveform
1. `io_rst` remains 1 (true) throughout all 330 ns.
2. `io_LineState_o` remains 0 (SE0 condition) throughout.
3. `rst_cnt` increments from 0 to 31 over 32 cycles (0–310 ns).
4. At **time 310 ns**: `rst_cnt` = 31 (terminal count reached), `usb_rst_reg` = 0, `usb_rst_stable` = 1 (assertion still holds).
5. At **time 320 ns** (next clock edge): `usb_rst_reg` transitions to 1 (correct design behavior: terminal count reached, so USB reset is flagged). Simultaneously, `usb_rst_stable` drops to 0 — assertion fails.
6. `io_rst` remains 1 at time 320 ns, causing the `assertStableWhen(io.rst, ...)` to trigger because `usb_rst_reg` changed while the condition was true.

### Why this is an Incorrect Assertion
The comment on lines 105-106 clearly states the intended property:
> "Once usb_rst_reg is set, it stays set until external reset (io.rst goes low)."

This describes a **once-set, stays-set** property that should only become active **after** `usb_rst_reg` transitions to 1. However, the implemented assertion uses `assertStableWhen(io.rst, ...)` which checks stability for **all** cycles where `io.rst` is true, including cycles before `usb_rst_reg` has been set. This prevents the legitimate 0→1 transition.

### Fix
The assertion condition should include `usb_rst_reg` so that stability is only required once the register has been set:

```scala
// Corrected: once usb_rst_reg is set, it must stay set as long as io.rst is true
assertStableWhen(io.rst && usb_rst_reg, usb_rst_reg.asUInt, "usb_rst_stable")
```

This corrected version matches the intended property described in the comment: the transition from 0 to 1 is allowed, but after that, `usb_rst_reg` must remain true while `io.rst` is true. It is cleared back to false only when `io.rst` goes low (as per the `when(!io.rst)` clause on line 78-80).
