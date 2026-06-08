# Counterexample Analysis Report: `usb_phy.usb_rst_requires_max_cnt`

## 1. Verification Environment

### Top Module
- **Module**: `usb_phy` (class in `usb_phy.scala`, line 7)
- **Formal framework**: Chisel `Formal` with `fvAssert`, `assertImpliesDelay`, `assertMutex`, `astRelaxedLiveness`
- **Generated Verilog**: `chisel/extra_bench/usbphy/generated/`

### Key Components
| Component | Type | Description |
|-----------|------|-------------|
| `rst_cnt` | Reg(5.W) | 5-bit counter (0–31) that counts SE0 cycles |
| `usb_rst_reg` | Reg(Bool) | Register that asserts when rst_cnt reaches 31 |
| `i_tx_phy` | Module(`usb_tx_phy`) | TX PHY (placeholder) |
| `i_rx_phy` | Module(`usb_rx_phy`) | RX PHY (placeholder); drives `io.LineState_o` |
| `fs_ce` | `true.B` | Hardwired clock enable (always active) |

### Key Connections
- `io.rst` (Input, active-low: `true` = no reset) drives the counter reset condition
- `io.LineState_o` (Output, from `i_rx_phy`) indicates SE0 state when `0`
- `io.usb_rst` (Output) = `usb_rst_reg`
- `reset_wire` = `io.rst & ~usb_rst_reg` (used by sub-modules, not by counter logic)

---

## 2. Violated Assertion

### Assertion Name
`usb_rst_requires_max_cnt` (from waveform filename `usb_phy.usb_rst_requires_max_cnt.fst`)

### Code Snippet
```scala
// File: usb_phy.scala, line 91
fvAssert(!usb_rst_reg || rst_cnt === 31.U, "usb_rst_requires_max_cnt")
```

### Natural Language Description
**"Whenever the USB reset register (`usb_rst_reg`) is asserted (high), the reset counter (`rst_cnt`) must be at its maximum value of 31."** This ensures that the USB reset signal is only generated when the SE0 line condition has persisted for the required number of cycles (31).

### File Location
- **Path**: `usb_phy.scala`
- **Line**: 91

---

## 3. Waveform Information

### Waveform File
- **Full path**: `verilog/extra_bench/usbphy/usb_phy.usb_rst_requires_max_cnt.fst`
- **Time range**: 0 ns → 330 ns (33 cycles)
- **Clock period**: 10 ns (rising edges at 0, 10, 20, ..., 310, 320)

### Critical Time Points

| Time (ns) | Event |
|-----------|-------|
| 0 | Initial state: `rst_cnt=0`, `usb_rst_reg=0`, `io_rst=1` (no reset), `LineState_o=0` (SE0), assertion passes |
| 0–300 | `rst_cnt` counts up 0→30 (increments each rising clock edge) |
| **310** | **Clock rising edge**: `rst_cnt` → 31 (`11111`); `io_rst` → 0 (external reset asserts); `usb_rst_reg` stays 0 (captures `(rst_cnt===31)` from previous state where rst_cnt=30 → false) |
| 315 | `rst_cnt=31`, `usb_rst_reg=0`, `io_rst=0` |
| **320** | **Clock rising edge - FAILURE POINT**: `usb_rst_reg` → 1 (captures `(rst_cnt===31)=true`), `rst_cnt` → 0 (cleared by `!io.rst`), **assertion fails** (`!1 || 0===31` = false) |

### Signal Values at Failure Point (time = 320 ns)

| Signal | Value | Meaning |
|--------|-------|---------|
| `usb_phy.rst_cnt [4:0]` | `00000` | Counter is 0 |
| `usb_phy.usb_rst_reg` | `1` | USB reset register is asserted |
| `usb_phy.io_rst` | `0` | External reset active |
| `usb_phy.io_LineState_o [1:0]` | `00` | Line is in SE0 state |
| `usb_phy.usb_rst_requires_max_cnt` | `0` | **Assertion fails** |

---

## 4. Root Cause Analysis

### Buggy Code Location
- **File**: `usb_phy.scala`
- **Lines**: 74–80
- **Module**: `usb_phy` (main module)

### The Bug

The counter `rst_cnt` is incremented in the `when` block (lines 74–80):

```scala
when(!io.rst) {
    rst_cnt := 0.U
}.elsewhen(io.LineState_o =/= 0.U) {
    rst_cnt := 0.U
}.elsewhen(!usb_rst_reg && fs_ce) {
    rst_cnt := rst_cnt + 1.U
}
```

The increment condition on line 78 (`!usb_rst_reg && fs_ce`) **does not check whether the counter has already reached its maximum value (31)**. This causes a classic one-cycle-latency race:

1. **The counter reaches 31** (e.g., at time 310 ns, rising clock edge).
2. **At the next clock edge** (time 320 ns), the RHS of register assignments are evaluated simultaneously:
   - For `usb_rst_reg := (rst_cnt === 31.U)`: RHS = `true` (because `rst_cnt` is 31 at the time of evaluation, BEFORE register updates).
   - For `rst_cnt`: the condition `!usb_rst_reg && fs_ce` evaluates to `true` (because `usb_rst_reg` is **still 0** — its old value before the clock edge — and `fs_ce` is `true.B`). So `rst_cnt` gets `rst_cnt + 1.U = 32 → wraps to 0` (5-bit counter).
3. **After register updates**: `usb_rst_reg=1` but `rst_cnt=0`. The assertion `!usb_rst_reg || rst_cnt === 31.U` evaluates to `false`.

In this specific counterexample, the external reset `io.rst` also goes low at time 310, causing `rst_cnt` to be cleared to 0 through the first `when` condition (`!io.rst`). However, even **without** the external reset, the same failure would occur due to the counter wrap-around described above.

### Why This Is a DUT Bug

The design intent is to count SE0 cycles up to 31, then assert `usb_rst_reg` and stop. The counter should **saturate** at 31, not wrap around. The failure is a genuine design error because:

- The counter **should** stop incrementing once it reaches 31, ensuring `usb_rst_reg` stays asserted while `rst_cnt` remains 31.
- The current logic allows `rst_cnt` to increment past 31 in the same cycle that `usb_rst_reg` becomes active, due to the one-cycle delay between evaluating `(rst_cnt === 31.U)` and the actual register update of `usb_rst_reg`.

### Evidence from Waveform

| Time | `rst_cnt` | `usb_rst_reg` | Clock Edge | Description |
|------|-----------|---------------|------------|-------------|
| 300 | `11110` (30) | 0 | Rising | Counter reaches 30 |
| 310 | `11111` (31) | 0 | Rising | Counter reaches 31; `io_rst` also goes low |
| 315 | `11111` (31) | 0 | — | Counter holds at 31; usb_rst_reg still 0 |
| **320** | **`00000` (0)** | **1** | **Rising** | **usb_rst_reg becomes 1, but rst_cnt is 0 → assertion fails** |

### Proposed Fix

The counter should saturate at 31 by adding a terminal-count check to the increment condition:

**Option 1** — Modify the `elsewhen` condition on line 78:
```scala
.elsewhen(!usb_rst_reg && fs_ce && rst_cnt < 31.U) {
    rst_cnt := rst_cnt + 1.U
}
```

**Option 2** — Use a saturating mux:
```scala
.elsewhen(!usb_rst_reg && fs_ce) {
    rst_cnt := Mux(rst_cnt === 31.U, 31.U, rst_cnt + 1.U)
}
```

Either fix ensures that when `rst_cnt` reaches 31, it stays at 31 in the subsequent cycle, maintaining consistency with `usb_rst_reg` which becomes 1 at the same time.

### Error Classification
- **Error Type**: **dut_bug** — The counter logic in the DUT has a genuine bug where `rst_cnt` fails to saturate at its maximum value, causing an assertion violation.
