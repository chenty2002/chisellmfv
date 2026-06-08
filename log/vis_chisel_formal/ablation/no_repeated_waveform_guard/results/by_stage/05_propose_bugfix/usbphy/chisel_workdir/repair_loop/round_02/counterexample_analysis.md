# Counterexample Analysis Report: `rst_cnt_stable_during_usb_rst`

## 1. Verification Environment

- **Top Module**: `usb_phy` (class in `usb_phy.scala`)
- **Test Harness**: `usb_phy` module instantiated directly with formal verification bindings
- **Key Components**:
  - `rst_cnt` (5-bit counter, Reg): Counts cycles during USB reset conditions
  - `usb_rst_reg` (1-bit Reg): Set when `rst_cnt` reaches 31, indicates USB reset is asserted
  - `io.usb_rst` (output): Combinational alias of `usb_rst_reg`
  - `next_rst_cnt` (Wire): Combines `rst_cnt` with increment/reset logic to avoid race conditions
  - `io.rst` (input): System reset signal (always 1 in this trace)
  - `io.LineState_o` (2-bit output): Received line state (0 = SE0, always 0 in this trace)
  - `fs_ce` (Bool): Clock enable (always 1 in this trace)
- **Design Under Test**: USB PHY reset generation circuit that counts 31 cycles of SE0 (LineState_o == 0) before asserting `io.usb_rst`

## 2. Violated Assertion

- **Assertion Name** (from waveform filename): `rst_cnt_stable_during_usb_rst`
- **Waveform File**: `usb_phy.rst_cnt_stable_during_usb_rst.fst`
- **Source Code** (file: `usb_phy.scala`, lines 95-97):

```scala
// Safety 4: When io.usb_rst is asserted and io.rst remains asserted,
// rst_cnt must stay at 31 (stable, no further counting)
assertStableWhen(io.usb_rst && io.rst, rst_cnt, "rst_cnt_stable_during_usb_rst")
```

- **Natural Language Description**: The assertion checks that whenever both `io.usb_rst` and `io.rst` are asserted (high), the `rst_cnt` signal must be stable—i.e., its value must not change between consecutive clock cycles. This is intended to ensure that after the USB reset counter reaches 31 (triggering `usb_rst`), the counter stops incrementing while the system reset remains active.

- **File Location**: `usb_phy.scala`, lines 95–97

## 3. Waveform Information

- **Waveform File Path**: `verilog/extra_bench/usbphy/usb_phy.rst_cnt_stable_during_usb_rst.fst`
- **Waveform Duration**: 0 ns to 320 ns (32 clock cycles at 10 ns period)
- **Key Time Points**:
  - **t = 0–305 ns**: Assertion holds (`rst_cnt_stable_during_usb_rst = 1`)
  - **t = 310 ns**: Assertion fails (`rst_cnt_stable_during_usb_rst → 0`)
  - **Clock period**: 10 ns (cycle boundaries at 0, 10, 20, ... 310, 320)

### Critical Signal Values at Failure Point (t = 310 ns)

| Signal | Value at t=310 | Previous (t=300) | Change? |
|---|---|---|---|
| `usb_phy.rst_cnt [4:0]` | `11111` (31) | `11110` (30) | **Changed** |
| `usb_phy._next_rst_cnt_T [4:0]` | `11111` (31) | `11110` (30) | **Changed** |
| `usb_phy.usb_rst_reg` | `1` | `0` | **Changed** |
| `usb_phy.io_usb_rst` | `1` | `0` | **Changed** |
| `usb_phy.io_rst` | `1` | `1` | Stable |
| `usb_phy.io_LineState_o [1:0]` | `00` | `00` | Stable |

## 4. Root Cause Analysis

### Error Classification: **Incorrect Assertion** (assertion_error)

The assertion is overly strict and flags a false positive on the transition cycle where `io.usb_rst` first becomes asserted.

### Detailed Explanation

**How the circuit works (per the source code, lines 76–86):**

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

The design intentionally uses a `Wire` (`next_rst_cnt`) to compute the *next* counter value. Both `rst_cnt` and `usb_rst_reg` are registered and updated on the same clock edge. The code comment (lines 73–75) explicitly states this was done to *avoid a race condition when io.rst deasserts in the same cycle that rst_cnt reaches 31*.

**Sequence of events leading to the failure:**

1. **Cycle 30 (t=300–310)**: `rst_cnt = 30`, `next_rst_cnt = 30+1 = 31`, `usb_rst_reg = 0`
   - At the clock edge (t=310), both `rst_cnt` and `usb_rst_reg` are updated:
     - `rst_cnt` latches the wire value: 31
     - `usb_rst_reg` latches `(next_rst_cnt === 31) = (31 === 31) = 1`

2. **At t=310 (after clock edge)**: 
   - `rst_cnt = 31`, `io.usb_rst = usb_rst_reg = 1`, `io.rst = 1`
   - Condition `io.usb_rst && io.rst` = `1 && 1` = **true**
   - `assertStableWhen` checks: `rst_cnt === Past(rst_cnt, 1)`
   - `Past(rst_cnt, 1)` = value from previous cycle (t=300–310) = 30
   - Check: `31 === 30` → **FALSE** → Assertion fires!

3. **Subsequent cycles (t=310+)**:
   - `rst_cnt` would stay at 31 (since `!usb_rst_reg = 0`, counter stops incrementing)
   - So the stable condition would hold from cycle 32 onward

**Why the assertion is wrong:**

The `assertStableWhen` check uses a $past-based stability check: at every cycle where the condition is true, the signal must equal its value from the previous cycle. This is violated on the *very first* cycle that `io.usb_rst` becomes true, because `rst_cnt` transitions from 30 to 31 in the same cycle that `io.usb_rst` transitions from 0 to 1.

The design's behavior is *intentional and correct*: reaching 31 is what triggers `usb_rst`. The value assertion at line 93 (`fvAssert(!io.usb_rst || (rst_cnt === 31.U), "usb_rst_implies_cnt_31")`) **passes** because at t=310, `io.usb_rst = 1` and `rst_cnt = 31`. This confirms the design's value semantics are correct.

**Three pieces of evidence confirm this is an assertion error:**

1. **The design comment** explicitly explains the race-condition-avoidance technique that causes `rst_cnt` and `io.usb_rst` to update simultaneously.
2. **Sibling assertion passes**: `fvAssert(!io.usb_rst || (rst_cnt === 31.U), ...)` checks the value constraint and is satisfied.
3. **The stability violation is one-shot**: After t=310, `rst_cnt` stays at 31 (as `usb_rst_reg = 1` blocks further increments), so the assertion would hold in all subsequent cycles.

### Buggy Code Location

The bug is in the assertion itself, not the design:

- **File**: `usb_phy.scala`, **Line 96**
- **Code**: `assertStableWhen(io.usb_rst && io.rst, rst_cnt, "rst_cnt_stable_during_usb_rst")`

### Proposed Fix

Replace the overly strict `assertStableWhen` with a conditional stability check that accounts for the transition cycle. For example:

```scala
// Check stability only after usb_rst has already been asserted for at least one cycle
fvAssert(!(io.usb_rst && io.rst && past(io.usb_rst && io.rst)) || 
         (rst_cnt === past(rst_cnt)), "rst_cnt_stable_during_usb_rst")
```

This checks: *if both io.usb_rst AND io.rst were true last cycle AND are true this cycle, then rst_cnt must not have changed.* This correctly skips the transition cycle (cycle 31) and checks stability from cycle 32 onward.

Alternatively, since `fvAssert(!io.usb_rst || (rst_cnt === 31.U), ...)` already guarantees the correct value, the stability assertion could simply be removed or replaced with a check that `rst_cnt` stays at 31 while `io.usb_rst` is asserted.
