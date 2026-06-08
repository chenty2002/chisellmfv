# Counterexample Analysis Report

## 1. Verification Environment

### Top Module
- **Top module**: `usb_phy` (from `usb_phy.scala`)
- **File**: `chisel/extra_bench/usbphy/usb_phy.scala`

### Key Components
- **`usb_phy`**: Main module containing USB reset detection logic, TX PHY, and RX PHY
- **`usb_tx_phy`**: Placeholder TX PHY module (no meaningful logic)
- **`usb_rx_phy`**: Placeholder RX PHY module (drives all outputs to 0, including `LineState`)

### Design Structure
The `usb_phy` module implements USB reset detection:
- A 5-bit counter (`rst_cnt`) counts clock cycles when:
  - Main reset `io.rst` is asserted (high)
  - Line state `io.LineState_o` is 0 (idle/SE0 state)
  - USB reset register `usb_rst_reg` is not yet asserted
  - Clock enable `fs_ce` is high
- A register `usb_rst_reg` is set based on `(rst_cnt === 31.U)` to signal that 31 SE0 cycles have been counted (USB reset condition)
- `io.usb_rst` outputs the registered USB reset signal
- `reset_wire = io.rst & ~usb_rst_reg` gates resets for the sub-modules

### Key Connections
- `io.LineState_o` comes from `i_rx_phy.io.LineState` — the placeholder RX PHY drives this to `0.U`
- `fs_ce` (clock enable) is hardwired to `true.B`
- `io.rst` (main reset) is driven to `1` throughout the simulation (no glitching)

## 2. Violated Assertion

### Assertion Name
**`usb_rst_reg_definition`** (extracted from waveform filename: `usb_phy.usb_rst_reg_definition.fst`)

### Code Snippet
From `usb_phy.scala`, **line 91**:

```scala
fvAssert(usb_rst_reg === (rst_cnt === 31.U), "usb_rst_reg_definition")
```

### Natural Language Description
The assertion states that **at all times**, the register `usb_rst_reg` must be **combinatorially equal** to the boolean expression `(rst_cnt === 31.U)`. In other words, if `rst_cnt` currently has value 31, then `usb_rst_reg` must be high; otherwise, `usb_rst_reg` must be low.

### File Location
- **Path**: `chisel/extra_bench/usbphy/usb_phy.scala`
- **Line**: 91

## 3. Waveform Information

### Full Path
`verilog/extra_bench/usbphy/usb_phy.usb_rst_reg_definition.fst`

### Time Range and Key Time Points

| Time (ns) | Clock | `rst_cnt` | `usb_rst_reg` | `usb_rst_reg_definition` (assertion) | Event |
|-----------|-------|-----------|---------------|--------------------------------------|-------|
| 0         | 1     | 0         | 0             | 1 (pass)                             | Initial state |
| 10        | 1     | 1         | 0             | 1 (pass)                             | rst_cnt=1 |
| 20        | 1     | 2         | 0             | 1 (pass)                             | rst_cnt=2 |
| ...       | ...   | ...       | ...           | ...                                  | ... |
| 300       | 1     | 30        | 0             | 1 (pass)                             | rst_cnt=30 |
| **310**   | **1** | **31**    | **0**         | **0 (FAIL)**                         | **Assertion failure** |
| 315       | 0     | 31        | 0             | 0 (fail)                             | After failure |
| 320       | 0     | 31        | 0             | 0 (fail)                             | End of simulation |

### Critical Signal Values at Failure Point (time 310 ns)
- `clock` = 1 (rising edge)
- `rst_cnt [4:0]` = `11111` (31 decimal)
- `usb_rst_reg` = 0
- `usb_rst_reg_definition` = 0 (**assertion failed**)
- `io_rst` = 1
- `io_LineState_o [1:0]` = 00 (SE0/idle state)
- `io_TxValid_i` = 0

## 4. Root Cause Analysis

### Root Cause Category: **Incorrect Assertion (Assertion Error)**

The primary root cause is an **assertion timing mismatch**: the assertion checks a **combinational equality** between two **registered signals**, but the relationship has an inherent **one-cycle pipeline delay** that makes the assertion fail on the cycle when `rst_cnt` transitions to 31.

### Detailed Explanation

#### How the signals behave (non-blocking assignment semantics)

Both `rst_cnt` and `usb_rst_reg` are Chisel `RegInit` registers:

```scala
val rst_cnt     = RegInit(0.U(5.W))     // Line 33
val usb_rst_reg = RegInit(false.B)       // Line 34
```

They update **simultaneously** at each rising clock edge using non-blocking assignments:

```scala
usb_rst_reg := (rst_cnt === 31.U)        // Line 82 — samples OLD value of rst_cnt

// Counter logic (Lines 74-80):
when(!io.rst) {
    rst_cnt := 0.U
}.elsewhen(io.LineState_o =/= 0.U) {
    rst_cnt := 0.U
}.elsewhen(!usb_rst_reg && fs_ce) {
    rst_cnt := rst_cnt + 1.U             // samples OLD value of rst_cnt and usb_rst_reg
}
```

#### Cycle-by-cycle trace showing the failure

At each rising clock edge, both registers read their old values and compute new values:

| Cycle | Time | Old `rst_cnt` | New `rst_cnt` | Old `usb_rst_reg` | New `usb_rst_reg` | (rst_cnt===31) |
|-------|------|---------------|---------------|-------------------|-------------------|----------------|
| 0     | 0    | —             | 0             | —                 | 0                 | false          |
| 1     | 10   | 0             | 1             | 0                 | 0 (0===31=false)  | false          |
| 2     | 20   | 1             | 2             | 0                 | 0 (1===31=false)  | false          |
| ...   | ...  | ...           | ...           | ...               | ...               | ...            |
| 30    | 300  | 29            | 30            | 0                 | 0 (29===31=false) | false          |
| **31** | **310** | **30**    | **31**        | **0**             | **0 (30===31=false)** | **true**   |
| 32    | 320  | 31            | *would wrap*  | 0                 | **1 (31===31=true)**   | false*         |

**At time 310 (cycle 31):**
1. `rst_cnt` reads old value 30, computes `30 + 1 = 31` → new value is 31
2. `usb_rst_reg` reads old value of `rst_cnt` (which is **30**), computes `30 === 31 = false` → new value is still **0**
3. The assertion checks: `usb_rst_reg (0) === (rst_cnt (31) === 31.U) (true)` → **`0 === 1` → FAIL!**

The root issue is that **both registers sample their inputs at the same clock edge**. When `rst_cnt` becomes 31, `usb_rst_reg` couldn't have "seen" the new value of `rst_cnt` yet — it only sees the old value (30) and stays low. The assertion, however, evaluates the **new** `rst_cnt` (31) against the **old** `usb_rst_reg` (0), creating a one-cycle mismatch.

#### Why this is an assertion error (not a pure design bug)

The assertion at line 91:

```scala
fvAssert(usb_rst_reg === (rst_cnt === 31.U), "usb_rst_reg_definition")
```

writes the property as if `usb_rst_reg` were a **wire** (combinational signal), but `usb_rst_reg` is defined as a **register** (line 34). A registered signal always lags one clock cycle behind its combinational input. The assertion should account for this delay.

#### Secondary observation: the design has an additional subtle issue

Beyond the assertion problem, the design exhibits unintended behavior due to the register timing:

1. At cycle 31 (time 310): `rst_cnt` = 31, `usb_rst_reg` = 0
2. At cycle 32 (time 320): Since `!usb_rst_reg` is still true (old value was 0), `rst_cnt` increments: `31 + 1 = 32`, which **wraps to 0** (5-bit counter)
3. `usb_rst_reg` would finally become 1 at cycle 32 (old `rst_cnt` was 31), but at cycle 33 it would go back to 0 (old `rst_cnt` is 0)

This makes `usb_rst_reg` behave as a **one-cycle pulse** rather than a sticky reset signal, which is likely not the intended behavior for a USB reset register.

### Summary

| Issue | Type | Location |
|-------|------|----------|
| **Assertion `usb_rst_reg_definition` fails** because it checks a combinational equality between two registered signals that have a one-cycle pipeline delay | **Assertion Error** | Line 91: `fvAssert(usb_rst_reg === (rst_cnt === 31.U), ...)` |
| Design's `usb_rst_reg` becomes a 1-cycle pulse instead of a sticky reset due to register timing | **Design Bug** (secondary) | Line 82: `usb_rst_reg := (rst_cnt === 31.U)` |

### Recommended Fix

The assertion should account for the register delay. For example:

```scala
// Option 1: Check at the next cycle after rst_cnt reaches 31
fvAssert(RegNext(usb_rst_reg) === (RegNext(rst_cnt) === 31.U), "usb_rst_reg_definition")

// Option 2: Use past() to align the comparison properly
// Or simply check that usb_rst_reg eventually reflects the counter
```

Additionally, if the design intends `usb_rst_reg` to be a sticky USB reset signal, the design should use a set-reset pattern:

```scala
// Fix design to make usb_rst_reg sticky once asserted
when(rst_cnt === 31.U) {
    usb_rst_reg := true.B
}
```
