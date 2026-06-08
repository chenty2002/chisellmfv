# Counterexample Analysis Report: `lock_eventually_opens_from_combo_start`

## 1. Verification Environment

- **Top Module**: `lock` (Chisel module with `Formal` mixin)
- **Key Components**:
  - `position`: 5-bit register tracking the lock dial position (0–31)
  - `state`: 2-bit register (values 0–3) implementing a 4-state lock sequence machine
  - `upReg` / `downReg`: registers latching the `io.up` / `io.down` inputs
  - `freezePosition`: combinational signal preventing position overshoot at transition points
  - `pending`: formal liveness tracker from `astRelaxedLiveness`
- **Design**: A combination lock that requires the user to dial through three specific positions sequentially:
  1. State 0 → 1: position=12, press UP
  2. State 1 → 2: position=21, press DOWN
  3. State 2 → 3: position=15, press UP (opens lock)

## 2. Violated Assertion

- **Assertion Name**: `lock_eventually_opens_from_combo_start` (from waveform filename `lock.lock_eventually_opens_from_combo_start.fst`)
- **File**: `lock.scala`, lines 148–151
- **Code**:
  ```scala
  astRelaxedLiveness(
    state === 0.U && position === 12.U && upReg,
    io.open,
    100,
    "lock_eventually_opens_from_combo_start"
  )
  ```
- **Property**: If the antecedent fires (`state === 0.U && position === 12.U && upReg`), then within 100 cycles, `io.open` must become true (`state === 3.U`).

## 3. Waveform Information

- **Waveform File**: `verilog/extra_bench/lock/lock.lock_eventually_opens_from_combo_start.fst`
- **Duration**: 114 cycles (0 ns → 1140 ns)
- **Antecedent Fires**: **time 120 ns** (cycle 12)
  - `state [1:0]` = `00`, `position [4:0]` = `01100` (12), `upReg` = 1
- **Key Timeline**:

| Time (ns) | state | position | upReg | io_up | io_down | Event |
|-----------|-------|----------|-------|-------|---------|-------|
| 110 | 0 | 11 | 1 | 1 | 0 | Normal UP movement toward 12 |
| 120 | **0** | **12** | **1** | 0 | 0 | **Antecedent fires!** freezePosition=1, state→1 on next edge |
| 130 | 1 | 12 | 0 | 1 | 0 | Enter state 1; io_up=1 → upReg becomes 1 for next cycle |
| 140 | 1 | 13 | **1** | 0 | 0 | **Bug trigger**: upReg=1 → `when(upReg)` fires → state→0 |
| 150 | 0 | 13 | 0 | 1 | 0 | Back to state 0, position only reached 13 |
| 1000 | 0 | 28 | 0 | 1 | 1 | Still in state 0, io_open=0 |
| ... | 0 | ... | ... | ... | ... | **io_open NEVER becomes 1** (only 1 transition ever in entire trace) |

- **Critical Observation**: `lock.state` only transitions **3 times total** in the entire 1140 ns trace: 0→1 at time 130, 1→0 at time 150. State **never reaches 2 or 3**.
- **io_open**: **Always 0** throughout entire simulation.

## 4. Root Cause Analysis

### Buggy Code Location
**File**: `lock.scala`, lines 81–87 (state `1.U` logic in the `switch` block)

```scala
is(1.U) {
  when(upReg) {
    state := 0.U
  }.elsewhen(position === 21.U && downReg) {
    state := 2.U
  }
}
```

### Description of the Bug

The state machine logic in state 1 contains a **fatal design flaw**: the `when(upReg)` reset-to-state-0 condition is too aggressive. It triggers on **any** occurrence of upReg=1, but upReg is set to 1 whenever the user presses the UP button (`io.up && !io.down`). To navigate the position from 12 toward the target 21 (required to enter state 2), the user MUST press UP repeatedly. Each UP press sets upReg=1, which immediately resets the state back to 0, making it **structurally impossible** to ever reach position 21 from state 1.

### Cycle-by-Cycle Evidence

1. **Time 110→120**: `io_up=1` increments position from 11 to 12. `upReg` becomes 1 (latched from io_up=1). The antecedent fires: state=0, position=12, upReg=1.

2. **Time 120→130**: freezePosition=1 prevents position from overshooting 12. State machine sees `state=0 && position=12 && upReg` → transitions to state 1. `upReg` becomes 0 (io_up was 0 at time 120).

3. **Time 130→140**: Now in state 1 at position 12. To move toward 21, `io_up=1` is applied. This correctly increments position to 13 (freezePosition is false). **But** `upReg` becomes 1 (latched from io_up=1). On the next cycle (time 140), the state machine sees `state=1 && upReg=1` and the `when(upReg)` branch fires, resetting state to 0.

4. **Time 150 onward**: Back to state 0, position 13. The state machine is now stuck in a loop between state 0 and state 1, oscillating on every UP press, **never** reaching state 2 or 3.

### Why This Causes the Assertion to Fail

The liveness assertion `astRelaxedLiveness` asserts that within 100 cycles of the antecedent (state=0, position=12, upReg=1), `io.open` must become true. But the design bug makes it impossible for the lock to ever open — the state machine can never progress past state 1 because any UP press (needed to advance position) triggers an immediate reset. Since `io.open` is `state === 3.U`, and state never reaches 2 or 3, `io.open` stays 0 forever, violating the liveness property.

### Classification

**Category**: **DUT Bug** (Bug in the Original Design)

The state machine logic in state 1's `when(upReg)` reset condition is incorrect. The design should not reset to state 0 simply because the user pressed UP — that press is necessary to increment the position toward the state-2 entry condition (position=21, down). A correct design would either:
- Remove the `when(upReg)` reset (allowing UP movement toward 21 without interference), or
- Use a more specific reset condition (e.g., only reset on UP when position is already at 21 but downReg is not asserted), or
- Track whether the position is moving toward the target and only allow reset on back-direction.
