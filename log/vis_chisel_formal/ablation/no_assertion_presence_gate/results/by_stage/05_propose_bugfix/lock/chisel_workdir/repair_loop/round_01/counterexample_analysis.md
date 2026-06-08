# Counterexample Analysis Report: `lock.position_stable_when_no_net_movement`

## 1. Verification Environment

- **Top Module**: `lock` (from `lock.scala`)
- **Generated Verilog**: `generated/lock.sv`
- **Waveform File**: `verilog/extra_bench/lock/lock.position_stable_when_no_net_movement.fst`
- **Key Components**:
  - `position` (5-bit register) — tracks the lock mechanism position
  - `state` (2-bit register) — FSM controlling lock states (0→1→2→3)
  - `upReg` / `downReg` — latched input signals
  - `r` (5-bit delay register) — used by the assertion to store previous cycle's position
  - `io_up` / `io_down` — primary inputs (button presses)
- **Assertion Library**: Chisel `Formal` with `assertStableWhen`

## 2. Violated Assertion

- **Assertion Name** (from filename): `position_stable_when_no_net_movement`
- **Code Location**: `lock.scala`, line 68
- **Source Code Snippet**:
  ```scala
  // Safety: Position is unchanged when neither direction is uniquely pressed
  // (both up/down true or both false). This guards against unintended drift.
  assertStableWhen(!(io.up ^ io.down), position, "position_stable_when_no_net_movement")
  ```
- **Generated Verilog** (lock.sv, lines 54-57):
  ```verilog
  position_stable_when_no_net_movement:
    assert property (@(posedge clock) disable iff (~hasBeenReset)
                     io_up ^ io_down | position == r);
  ```
- **Natural Language Description**: At every positive clock edge, when there is **no net movement** (i.e., both buttons pressed or both released, so `io_up ^ io_down` is false), the current position must equal the position from the previous cycle (stored in delay register `r`). In other words, position must not change when there is no exclusive button press.

## 3. Waveform Information

- **Full Waveform Path**: `verilog/extra_bench/lock/lock.position_stable_when_no_net_movement.fst`
- **Time Range**: 0 ns to 20 ns (2 clock cycles)
- **Clock Period**: 10 ns (posedges at times 0, 10, 20)
- **Key Time Points**:

| Time (ns) | clock | io_up | io_down | position [4:0] | r [4:0] | io_up ^ io_down | position==r | Assertion |
|-----------|-------|-------|---------|----------------|---------|----------------|-------------|-----------|
| 0         | 1     | 1     | 0       | 0 (00000)      | 0 (00000) | 1 (net movement) | 1 (true) | **PASS** |
| 10        | 1     | 0     | 0       | 1 (00001)      | 0 (00000) | 0 (no net movement) | 0 (false) | **FAIL** |
| 20        | 0     | 0     | 0       | 1 (00001)      | N/A       | 0 (no net movement) | N/A | (beyond failure) |

- **Failure Point**: Time = 10 ns (posedge of clock cycle 1)

## 4. Root Cause Analysis

### Bug Classification: **Assertion Error (Incorrect Assertion)**

The assertion `assertStableWhen(!(io.up ^ io.down), position, ...)` is **incorrectly formulated** for this sequential design. The property does not account for the inherent one-cycle pipeline delay between input sampling and position register update.

### Detailed Explanation

**How the DUT works**:
The position register updates at each positive clock edge based on the inputs from the **previous** cycle:
```scala
when(io.up && !io.down) {
  position := position + 1.U    // Updates one cycle after io_up is sampled
}.elsewhen(io.down && !io.up) {
  position := position - 1.U    // Updates one cycle after io_down is sampled
}
```

**How the assertion works**:
The `assertStableWhen` library method creates a delay register `r` that stores `position` at each posedge. The assertion checks: `io_up ^ io_down | position == r` — i.e., "either there's net movement NOW, or position hasn't changed since last cycle."

**The timing mismatch (why the assertion fails)**:

1. **Cycle 0 (time 0)**: `io_up=1`, `io_down=0` → net movement detected → `position` is scheduled to increment from 0 to 1 (non-blocking assignment). The delay register `r` is also updated: `r <= position` gets the old value `0`.

2. **Between cycles**: Position computes to `0 + 1 = 1` in combinational logic. Register outputs still show old values.

3. **Cycle 1 (time 10)**: At the posedge:
   - `position` updates to `1` (the increment scheduled in cycle 0 takes effect)
   - `io_up` changes from `1` to `0`, and `io_down` stays at `0`
   - `r` holds `0` (the previous cycle's position, `0`)
   - The assertion evaluates: `io_up ^ io_down = 0 ^ 0 = 0` (no net movement) AND `position(1) == r(0)` = false → assertion **FAILS**

**Why this is an assertion error, not a DUT bug**:

The DUT behaves correctly: when a user presses `up` exclusively for one cycle, the position increments by 1 on the next cycle. This is standard sequential logic behavior. The assertion, however, incorrectly expects position to remain **instantaneously** stable the moment inputs change, ignoring the one-cycle register delay.

In a real hardware design:
- `io_up=1, io_down=0` at cycle 0 → position should be 1 by cycle 1 (correct DUT behavior)
- At cycle 1 both inputs are 0 → position is now 1, which is correct (it already responded to the earlier press)

The assertion **falsely flags** this legitimate sequence as a violation because it expects the position to be __already__ settled at the exact clock edge where the inputs transition from (1,0) to (0,0), which is impossible for edge-triggered registers that sample inputs and update outputs simultaneously.

### Suggested Fix

The assertion should account for the one-cycle delay. Two possible fixes:

**Option A**: Defer the stability check by one cycle using a delayed condition:
```scala
val no_net_movement = !(io.up ^ io.down)
val prev_no_net_movement = RegNext(no_net_movement)
// Only check stability when BOTH current and previous cycle have no net movement
assert(!(prev_no_net_movement && no_net_movement) | (position === RegNext(position)))
```

**Option B**: Use a simpler temporal assertion to check position stability across cycles where inputs are stable:
```scala
// Position should only change when there's net movement
assert(RegNext(position) === position | io.up ^ io.down)
```
This allows position to change between cycles only when there was net movement in at least one of the two adjacent cycles.
