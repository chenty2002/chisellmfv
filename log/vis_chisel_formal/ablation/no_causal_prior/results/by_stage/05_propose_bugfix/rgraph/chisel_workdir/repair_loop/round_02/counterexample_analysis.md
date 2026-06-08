# Counterexample Analysis Report

## 1. Verification Environment

- **Top Module**: `rgraph` (Chisel class)
- **Generated Verilog**: `verilog/extra_bench/rgraph/generated/`
- **Waveform File**: `verilog/extra_bench/rgraph/rgraph.cnt_eventually_reaches_zero.fst`
- **Structure**: The design consists of a 12-bit counter `cnt`, a 1-bit mode register, input `io.i` (bool), output `io.o` (bool), and a reset counter submodule.
  - **cnt**: 12-bit register, increments when `mode=0`, decrements when `mode=1 && io.i && cnt>0`
  - **mode**: 1-bit register, transitions from 0→1 when `mode=0 && io.i`, monotonic (never goes back)
  - **io.o**: Combinational output, equals `(cnt === 0.U)`
  - **Liveness checker**: Embedded via `astRelaxedLiveness`, using internal `pending` and `timer [12:0]` signals

## 2. Violated Assertion

- **Assertion Name**: `cnt_eventually_reaches_zero`
- **Property Type**: Bounded relaxed liveness (`astRelaxedLiveness`)
- **Code Snippet** (from `rgraph.scala`, line 39):
  ```scala
  astRelaxedLiveness(mode === 1.U && cnt > 0.U && io.i, cnt === 0.U, 4096, "cnt_eventually_reaches_zero")
  ```
- **Description**: If the antecedent `(mode === 1.U && cnt > 0.U && io.i)` holds at some point, then the consequent `(cnt === 0.U)` must become true within 4096 cycles.
- **File Location**: `rgraph.scala`, line 39

## 3. Waveform Information

- **Full Path**: `verilog/extra_bench/rgraph/rgraph.cnt_eventually_reaches_zero.fst`
- **Duration**: 0 ns → 41000 ns (4100 cycles)
- **Failure Time**: **40990 ns** (cycle 4099)
- **Key Time Points**:

| Time (ns) | Cycle | cnt | mode | io.i | pending | timer | Event |
|-----------|-------|-----|------|------|---------|-------|-------|
| 0 | 0 | 0 | 0 | 0 | 0 | 0 | Initial state |
| 10 | 1 | 1 | 0 | 1 | 0 | 0 | cnt increments (mode=0), io.i rises |
| 20 | 2 | 2 | **1** | 1 | 0 | 0 | mode becomes 1 (antecedent true here) |
| 30 | 3 | **1** | 1 | **0** | **1** | 0 | cnt decrements to 1, io.i drops, pending set |
| 40 | 4 | 1 | 1 | 0 | 1 | 1 | timer starts counting |
| ... | ... | 1 | 1 | 0 | 1 | ... | cnt stuck at 1, timer increments |
| 40990 | 4099 | **1** | 1 | 0 | **1** | **4096** | **Assertion fails (timer=4096, cnt≠0)** |

- **Critical Signals at Failure (time=40990)**:
  - `rgraph.cnt [11:0]` = `000000000001` (decimal 1)
  - `rgraph.mode` = `1`
  - `rgraph.io_i` = `0`
  - `rgraph.pending` = `1`
  - `rgraph.timer [12:0]` = `1000000000000` (decimal 4096 = bound)
  - `rgraph.cnt_eventually_reaches_zero` = `0` (assertion evaluates to false)

## 4. Root Cause Analysis

### Root Cause Category: **DUT Bug**

### Buggy Code Location
- **File**: `rgraph.scala`, lines 18-21
- **Module**: `class rgraph`
- **Buggy Logic**:
  ```scala
  when(mode === 0.U) {
      cnt := cnt + 1.U
  }.otherwise {
      when(io.i && (cnt =/= 0.U)) {
        cnt := cnt - 1.U
      }
  }
  ```

### Description of the Bug
The counter decrement in `mode=1` is gated by `io.i`:
```scala
when(io.i && (cnt =/= 0.U)) {
    cnt := cnt - 1.U
}
```

This means that when `io.i` goes **low** before `cnt` reaches 0, the decrement stalls permanently. The counter becomes stuck at a non-zero value indefinitely.

### Evidence from Waveform

1. **Cycle 2 (time 20)**: The antecedent fires. `mode=1`, `cnt=2`, `io.i=1`. At this point, the decrement starts. At the next posedge (time 30), cnt correctly decrements to 1.

2. **Cycle 3 (time 30)**: Immediately after decrementing to 1, `io.i` falls to 0. Now the condition `io.i && (cnt =/= 0.U)` becomes `0 && 1 = 0`, so no further decrement occurs.

3. **Cycles 4-4099**: From time 30 onward, `cnt` remains stuck at `1`, `io.i` stays `0`, `mode` stays `1`. The `pending` signal is set at time 30 (liveness checker registered the antecedent), and the `timer` counts from 0 to 4096. After 4096 cycles of waiting for `cnt` to reach 0, the assertion fails.

### Why This Causes the Assertion to Fail
The `astRelaxedLiveness` assertion promises that once `mode=1 && cnt>0 && io.i` is true, `cnt` will reach 0 within 4096 cycles. However, because the decrement logic depends on `io.i` being high on every cycle (not just the first), the environment can de-assert `io.i` after just one decrement, leaving `cnt` stuck at 1. The design cannot complete the countdown to 0 without `io.i` being continuously asserted.

### Fix
Remove the `io.i` dependency from the decrement condition in mode 1. The counter should decrement unconditionally when `mode=1` and `cnt > 0`:

```scala
when(mode === 0.U) {
    cnt := cnt + 1.U
}.otherwise {
    when(cnt =/= 0.U) {
      cnt := cnt - 1.U
    }
}
```

This ensures that once the system enters mode 1, the counter monotonically decrements to 0 regardless of subsequent `io.i` behavior.
