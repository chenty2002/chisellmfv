# Counterexample Analysis Report: `usb_rst_consistent` Failure

## 1. Verification Environment

- **Top Module**: `usb_phy` (Chisel, in `usb_phy.scala`)
- **Structure**: The `usb_phy` module contains:
  - `usb_tx_phy` submodule (placeholder implementation)
  - `usb_rx_phy` submodule (placeholder implementation)
  - USB reset detection logic with a 5-bit counter (`rst_cnt`)
  - A registered output signal (`usb_rst_reg`) indicating USB reset completion
- **Key Connections**:
  - `io.rst` (Bool): External reset input (active low — when false, external reset is asserted)
  - `io.LineState_o` (UInt(2.W)): USB line state; value 0 indicates SE0 (single-ended zero) condition
  - `rst_cnt` (UInt(5.W)): 5-bit counter that tracks how long the line has been in SE0
  - `usb_rst_reg` (Bool): Registered output that asserts when rst_cnt reaches 31
  - `fs_ce`: Clock enable (hardwired to `true.B` in this test)

## 2. Violated Assertion

- **Assertion Name**: `usb_rst_consistent`
- **Waveform Filename**: `usb_phy.usb_rst_consistent.fst`
- **File**: `usb_phy.scala`, **Line 87**
- **Code Snippet**:
  ```scala
  // Safety: usb_rst_reg is only true when the counter has reached the terminal count
  fvAssert(!usb_rst_reg || rst_cnt === 31.U, "usb_rst_consistent")
  ```
- **Property**: "If `usb_rst_reg` is asserted (true), then `rst_cnt` must equal 31 in the same cycle."
- **Logical Form**: `usb_rst_reg → (rst_cnt === 31.U)`

## 3. Waveform Information

- **Waveform File**: `verilog/extra_bench/usbphy/usb_phy.usb_rst_consistent.fst`
- **Duration**: 330 ns (33 clock cycles @ 10 ns period)
- **Failure Time**: **t = 320 ns**
- **Key Time Points and Signal Values**:

| Time (ns) | `rst_cnt [4:0]` | `usb_rst_reg` | `io_rst` | `io_LineState_o` | `usb_rst_consistent` |
|-----------|-----------------|---------------|----------|------------------|----------------------|
| 0         | 00000 (0)       | 0             | 1        | 00 (SE0)         | 1                    |
| 10–290    | 00001–11101     | 0             | 1        | 00 (SE0)         | 1                    |
| 300       | 11110 (30)      | 0             | 1        | 00 (SE0)         | 1                    |
| **310**   | **11111 (31)**  | **0**         | **0**    | 00 (SE0)         | 1 (still passing)    |
| **320**   | **00000 (0)**   | **1**         | **0**    | 00 (SE0)         | **0 (FAIL)**         |

## 4. Root Cause Analysis

### Category: **Bug in the Original Design (DUT bug)**

### Bug Location

**File**: `usb_phy.scala`, **Lines 76–81** (USB reset counter logic)

```scala
when(!io.rst) {
    rst_cnt := 0.U
}.elsewhen(io.LineState_o =/= 0.U) {
    rst_cnt := 0.U
}.elsewhen(!usb_rst_reg && fs_ce) {    // <--- BUG: uses registered signal
    rst_cnt := rst_cnt + 1.U
}
```

And **Line 83**:

```scala
usb_rst_reg := (rst_cnt === 31.U)        // <--- register update
```

### Detailed Explanation of the Bug

The root cause is a **one-cycle timing mismatch** between `rst_cnt` reaching its terminal value and `usb_rst_reg` reflecting that condition.

The critical sequence of events:

1. **Counting phase (t=0 to t=300):** With `io.rst=1` and `io.LineState_o=00` (SE0), the counter `rst_cnt` increments by 1 each cycle because `!usb_rst_reg` is true. `usb_rst_reg` remains 0.

2. **t=300–310 (Cycle where rst_cnt=30):** `rst_cnt` is 30. The counter condition `!usb_rst_reg && fs_ce` evaluates to true (since `usb_rst_reg` is still 0), so the next value of `rst_cnt` is computed as 30 + 1 = 31. Meanwhile, `usb_rst_reg` input is `(30 === 31)` = false.

3. **t=310 (Clock edge):** `rst_cnt` updates to 31. At this same moment, `io.rst` transitions from 1 to 0.

4. **t=310–320 (Cycle where rst_cnt=31):** 
   - The `when` block evaluates: `!io.rst` is now true (io_rst=0), so `rst_cnt_next = 0` (the counter is reset).
   - `usb_rst_reg` input = `(rst_cnt === 31.U)` = `(31 === 31)` = **true**.

5. **t=320 (Clock edge):**
   - `rst_cnt` becomes **0** (reset by `io.rst` going low).
   - `usb_rst_reg` becomes **1** (latched from the comparison `31 === 31` in the previous cycle).
   - **Assertion check**: `!usb_rst_reg || rst_cnt === 31.U` evaluates to `!1 || 0 === 31` = `0 || 0` = **0 → FAIL**.

### Why This Bug Exists

The counting condition uses `!usb_rst_reg`, which is the **registered** version of the terminal count. Since `usb_rst_reg` is a register, it only updates **one clock cycle after** `rst_cnt` reaches 31. This means:

- In the cycle where `rst_cnt` becomes 31, `usb_rst_reg` is **still false** (the old value).
- The counter continues incrementing (or in this case, gets reset by `io.rst` going low).
- In the next cycle, `usb_rst_reg` finally becomes true, but `rst_cnt` is no longer 31.
- The assertion is violated because `usb_rst_reg` and `rst_cnt` are out of sync.

**Even without `io.rst` going low**, the same bug manifests: when `rst_cnt=31` and `usb_rst_reg=0`, the counter would continue to `31+1=32`, which wraps to 0 (5-bit overflow). Then at t=320, `usb_rst_reg` becomes 1 while `rst_cnt` is 0 — the same assertion failure.

### Fix

Replace the registered signal `usb_rst_reg` with the **combinational** terminal count in the counting condition. The counter should stop incrementing when `rst_cnt` reaches 31, not when `usb_rst_reg` is set. Compare using the condition `rst_cnt =/= 31.U` (or equivalently `rst_cnt < 31.U`) instead of `!usb_rst_reg`:

**Original (buggy):**
```scala
}.elsewhen(!usb_rst_reg && fs_ce) {
    rst_cnt := rst_cnt + 1.U
}
```

**Fixed:**
```scala
}.elsewhen(rst_cnt =/= 31.U && fs_ce) {
    rst_cnt := rst_cnt + 1.U
}
```

Or alternatively, introduce a combinational intermediate signal:

```scala
val usb_rst_terminal = rst_cnt === 31.U   // combinational
...
}.elsewhen(!usb_rst_terminal && fs_ce) {
    rst_cnt := rst_cnt + 1.U
}
usb_rst_reg := usb_rst_terminal
```

Either fix ensures the counter stops incrementing **immediately** when `rst_cnt` reaches 31, preventing the one-cycle drift that causes the assertion violation.
