# Counterexample Analysis Report: dekker.SA4_turn_one_hot

## 1. Verification Environment

- **Top Module**: `dekker` (Chisel module, compiled to SystemVerilog)
- **Key Components**:
  - `c[0]`, `c[1]` (bool): Flags indicating process interest in critical section
  - `turn` (1-bit): Whose turn it is (0 = process 0, 1 = process 1)
  - `self` (1-bit): Which process this hardware instance acts as (0 or 1)
  - `pc[0]`, `pc[1]` (3-bit): Program counters for each process (values L0–L6)
  - `io_select`: Selects which process identity this instance takes
  - `io_pause`: Pauses the selected process at certain states
- **Design Under Test**: A hardware implementation of Dekker's mutual exclusion algorithm for two processes. The selected process (determined by `self`, which mirrors `io_select`) executes a state machine through locations L0–L6 while the other process's state is tracked via the `pc` register array.

## 2. Violated Assertion

- **Assertion Name**: `SA4_turn_one_hot`
- **Waveform File**: `verilog/extra_bench/dekker/dekker.SA4_turn_one_hot.fst`

### Source Code (dekker.scala, line 108)
```scala
// SA3: turn register is always 0 or 1 (width is 1 bit, but explicit check)
assertOneHot(turn, "SA4_turn_one_hot")
```

### Generated SystemVerilog (generated/dekker.sv, line 50)
```systemverilog
SA4_turn_one_hot: assert property (@(posedge clock) disable iff (~hasBeenReset) turn);
```

### Property Description
The assertion checks that `turn` has exactly one bit set. For a 1-bit signal, this means `turn !== 0` (i.e., `turn === 1.U`). The assertion is disabled when `hasBeenReset` is false (i.e., during reset).

### File Location
- **Chisel source**: `dekker.scala`, line 108
- **Generated Verilog**: `generated/dekker.sv`, line 50

## 3. Waveform Information

- **Waveform File**: `verilog/extra_bench/dekker/dekker.SA4_turn_one_hot.fst`
- **Duration**: 1 cycle (0 ns – 10 ns)
- **Clock edges**: posedge at 0 ns and 10 ns

### Critical Signal Values at Failure Point (posedge at 0 ns)

| Signal | Value | Meaning |
|--------|-------|---------|
| `dekker.turn` | 0 | Turn register is 0 (not one-hot) |
| `dekker.hasBeenReset` | 1 | Reset completed, assertion is active |
| `dekker.self` | 1 | Process 1 is the selected process |
| `dekker.io_pause` | 1 | Pause is asserted |
| `dekker.io_select` | 1 | Selects process 1 identity |
| `dekker.pc_0 [2:0]` | 000 (L0) | Process 0 at location L0 |
| `dekker.pc_1 [2:0]` | 000 (L0) | Process 1 at location L0 |
| `dekker.c_0` | 1 | Process 0 flag = true (not interested) |
| `dekker.c_1` | 1 | Process 1 flag = true (not interested) |
| `dekker.SA4_turn_one_hot` | 1 | Assertion monitor signal (violation detected) |

### Key Time Points
- **0 ns (posedge clock)**: First evaluation point. `turn = 0`, `hasBeenReset = 1` → assertion violated.
- **10 ns (posedge clock)**: Second evaluation point. `turn` still 0 → assertion violated again. No state changes occurred because `io_pause = 1` prevents progress.

## 4. Root Cause Analysis

### Bug Classification: **Incorrect Assertion** (assertion_error)

### Nature of the Bug

The assertion `assertOneHot(turn, "SA4_turn_one_hot")` on a 1-bit `UInt` signal is semantically incorrect for the intended check.

The Chisel `assertOneHot` method checks that **exactly one bit** of the signal is asserted. For a 1-bit signal:
- `turn = 0` → 0 bits set → **violates** one-hot (fails)
- `turn = 1` → 1 bit set → **satisfies** one-hot (passes)

However, in Dekker's algorithm, the `turn` register is a variable that can be **either 0 or 1**, both of which are valid:
- `turn = 0` → it is process 0's turn
- `turn = 1` → it is process 1's turn

The register is initialized to `0` via `RegInit(0.U(1.W))` (line 35 of `dekker.scala`), and only changes when a process exits the critical section (L6), where `turn := ~self` (line 85). The `turn` register is **legitimately 0** at the start, and remains 0 until some process reaches the critical section and exits it.

### Evidence from Waveform

1. At time 0 ns: `turn = 0`, `hasBeenReset = 1` (reset complete, assertion active)
2. Both processes are at L0 (`pc_0 = 000`, `pc_1 = 000`) and cannot progress because `io_pause = 1` blocks the transition from L0 to L1
3. Since no process can reach L6 (critical section), the `turn` register is never updated from its initial value of 0
4. At every posedge clock (0 ns, 10 ns), the assertion checks `turn` (boolean true means `turn != 0`), which fails since `turn = 0`

### Why the Assertion is Incorrect

The comment in the source code explicitly states:
```scala
// SA3: turn register is always 0 or 1 (width is 1 bit, but explicit check)
```

This confirms the programmer's **intent** was to verify that `turn` is always either 0 or 1 (i.e., within the valid range for a 1-bit signal). However, `assertOneHot` does not check "is 0 or 1"—it checks "is exactly one bit set," which for a 1-bit value is equivalent to checking `turn === 1`.

Since a 1-bit `UInt` can **only** hold 0 or 1 by construction, the range check is trivially always satisfied. The assertion `assertOneHot` is the wrong verification primitive for this purpose.

### Suggested Fix

Replace the assertion with a correct property or remove it entirely. Since a 1-bit signal is inherently constrained to values 0 and 1, no assertion is needed to validate its range. If the intent was to check that `turn` is never unknown (X), a different mechanism would be needed. The simplest fix is to remove line 108:

```scala
// Remove this line:
// assertOneHot(turn, "SA4_turn_one_hot")
```

Or, if the intent is to ensure `turn` is well-defined (non-X), use a different check compatible with the formal verification framework.
