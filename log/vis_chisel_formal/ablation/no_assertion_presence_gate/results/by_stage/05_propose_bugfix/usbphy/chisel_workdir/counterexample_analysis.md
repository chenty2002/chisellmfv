# Counterexample Analysis Report: usb_rst_eventually_asserted

## 1. Verification Environment

- **Top module**: `usb_phy` (in package `llmverify`)
- **Module structure**: The `usb_phy` module instantiates two sub-modules:
  - `usb_tx_phy`: Placeholder TX PHY (outputs driven to zero)
  - `usb_rx_phy`: Placeholder RX PHY (outputs `LineState` driven to `0.U`)
- **Key components and connections**:
  - `io.rst` (Bool): System reset input — toggles frequently in the counterexample
  - `io.LineState_o` (UInt(2.W)): USB line state from RX PHY — driven to `0` (SE0) by the placeholder
  - `usb_rst_reg` (Reg(Bool)): Sticky register that indicates USB reset has been detected
  - `rst_cnt` (Reg(UInt(5.W))): 5-bit counter that increments toward 31 to trigger USB reset assertion
  - `fs_ce` (Bool): Clock enable, tied to `true.B` in this design
- **Design under test**: A USB PHY module responsible for detecting USB reset conditions on the bus and generating the `usb_rst` output signal. The counter increments when `io.rst` is high, `io.LineState_o` is SE0 (0), and `usb_rst_reg` has not yet been set.

## 2. Violated Assertion

- **Full assertion name**: `usb_rst_eventually_asserted`
- **Assertion type**: `astRelaxedLiveness` — checks that a condition occurs within a bounded number of cycles
- **Code snippet** (from `usb_phy.scala`, lines ~171–176):

```scala
astRelaxedLiveness(
    io.rst && io.LineState_o === 0.U && !usb_rst_reg,
    usb_rst_reg,
    40,
    "usb_rst_eventually_asserted"
)
```

- **Natural language property**: *Whenever the system reset (`io.rst`) is active, the USB line is in SE0 state (`LineState_o === 0`), and the USB reset has not yet been detected (`usb_rst_reg` is false), the USB reset register (`usb_rst_reg`) should become true within 40 clock cycles.*
- **File location**: `/chisel/extra_bench/usbphy/usb_phy.scala`, lines 171–176

## 3. Waveform Information

- **Work directory**: `chisel/extra_bench/usbphy`
- **Waveform file**: `verilog/extra_bench/usbphy/usb_phy.usb_rst_eventually_asserted.fst`
- **Time range**: 0 ns to 420 ns (42 cycles)
- **Clock period**: 10 ns

### Key Time Points

| Time (ns) | Event |
|-----------|-------|
| 0 | `pending` = 0, `timer` = 0, formal checker starts monitoring |
| 10 | `pending` → 1 (precondition first seen true: `io.rst=1 && LineState=0 && !usb_rst_reg`) |
| 20 | `timer` begins incrementing (timer=1 at time 20) |
| 10–400 | `rst_cnt` increments only 16 times (from 0 → 16) across 42 cycles |
| 400 | `timer` = 0b100111 = 39, `rst_cnt` = 0b10000 = 16 |
| 410 | **FAILURE**: `usb_rst_eventually_asserted` → 0 |

### Signals at Failure Point (time = 410 ns)

| Signal | Value | Interpretation |
|--------|-------|----------------|
| `usb_phy.usb_rst_eventually_asserted` | **0** | **ASSERTION FAILED** |
| `usb_phy.timer [5:0]` | 0b101000 = **40** | 40 cycles elapsed since liveness check started |
| `usb_phy.rst_cnt [4:0]` | 0b10000 = **16** | Counter only reached 16, far short of 31 |
| `usb_phy.io_rst` | **1** | System reset is active |
| `usb_phy.io_LineState_o [1:0]` | **00** (SE0) | Line is in SE0 state |
| `usb_phy.usb_rst_reg` | **0** | USB reset NOT yet asserted |
| `usb_phy.pending` | **1** | Precondition still active |

## 4. Root Cause Analysis

### Bug Classification: **Assertion Error (assertion_error)**

The assertion's bound of 40 cycles is **too tight** for the counter-based logic it monitors.

### Buggy Assertion Location

**File**: `chisel/extra_bench/usbphy/usb_phy.scala`, lines 171–176

```scala
astRelaxedLiveness(
    io.rst && io.LineState_o === 0.U && !usb_rst_reg,
    usb_rst_reg,
    40,    // ← THIS BOUND IS TOO SMALL
    "usb_rst_eventually_asserted"
)
```

### Description of the Bug

The assertion assumes that `usb_rst_reg` will become true within **40 absolute clock cycles** after the precondition is met. However, the USB reset detection circuit uses a counter (`rst_cnt`) that only increments when ALL of the following conditions are true simultaneously:

```scala
// From usb_phy.scala, lines ~119–125:
when(io.LineState_o =/= 0.U) {
    rst_cnt := 0.U
}.elsewhen(io.rst && !usb_rst_reg && fs_ce) {
    rst_cnt := rst_cnt + 1.U
}
```

The counter requires **31 increments** (to reach terminal value 31) to trigger `usb_rst_reg`, but it only increments when `io.rst` is high. When `io.rst` goes low, the counter **pauses** (holds its value). This is an intentional design choice (per the code comment: *"When io.rst goes low the counter pauses (holds value) rather than resetting, preventing SE0 cycle-count loss from system-reset glitches"*).

### Evidence from Waveform

The waveform shows that `io.rst` toggles frequently, being high on only ~17 of the 42 clock cycles (time 0 to time 410):

| Clock edges where `io.rst=1` | Clock edges where `io.rst=0` |
|---|---|
| 0, 10, 30, 50, 90, 150, 220, 230, 270, 280, 300, 340, 350, 360, 380, 390, 410 | 20, 40, 60, 70, 80, 100, 110–140, 160, 170–210, 240, 250, 260, 290, 310, 320, 330, 370, 400 |

**Total high cycles**: ~17 out of 41 clock cycles (time 10 to time 410) = ~41% duty cycle.

**Result**: The `rst_cnt` only reaches 16 by time 400, needing 15 more high cycles to reach 31. But the liveness timer has already reached 40 cycles and the assertion fails.

### Why the Bound Fails

- The counter needs **31 cycles with `io.rst` high** to reach the terminal value.
- If `io.rst` is low for even a single cycle, the counter stalls.
- The 40-cycle bound is insufficient because it assumes 31 consecutive increments are possible, but the formal tool's stimulus can (correctly, from a formal perspective) interleave low-`io.rst` cycles.
- Worst case: if `io.rst` is low for N cycles out of every 40, the bound needs to be at least 31 + N cycles where N is the maximum number of low cycles the tool can inject.
- A safe bound would be **31 + (maximum number of cycles io.rst can be low within the window)**. Since the formal tool can drive `io.rst` arbitrarily, a bound of 40 is provably insufficient.

### Fix Recommendation

**Option A**: Increase the bound to a much larger value, e.g., `100` or more, to account for the counter pausing.

**Option B**: Restructure the assertion to count only the cycles where `io.rst` is actually high, e.g., using a different property formulation that measures elapsed high cycles rather than absolute cycles.

**Option C**: Add a constraint that `io.rst` must stay high continuously while the USB line is in SE0 state (which would reflect realistic system behavior), then the 40-cycle bound would be adequate.
