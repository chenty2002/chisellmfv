# Counterexample Analysis Report: `short.state_one_hot`

## 1. Verification Environment

- **Top Module**: `short` (Chisel module with `Formal` mixin)
- **Source File**: `short.scala` (56 lines)
- **Generated Verilog**: `short.sv` (163 lines)
- **Design Under Test**: A simple 2-state FSM with a binary-encoded state machine:
  - `ready = 0` (using `Enum(2)`)
  - `busy = 1`
  - State transitions influenced by a pseudo-random counter
- **Key Components**:
  - `state` — 1-bit register, initialized to `ready` (0)
  - `randomCounter` — 8-bit incrementing counter
  - `io.request` — output driven by `randomCounter(1)`
  - `nond_state` — pseudo-nondeterministic value = `Mux(randomCounter(0), ready, busy)`

## 2. Violated Assertion

- **Assertion Name**: `state_one_hot` (from waveform filename `short.state_one_hot.fst`)
- **Source Code** (short.scala line 39):
  ```scala
  assertOneHot(state, "state_one_hot")
  ```
- **Generated Verilog** (short.sv line 68):
  ```verilog
  state_one_hot: assert property (@(posedge clock) disable iff (~hasBeenReset) state);
  ```
- **Property Description**: "At each posedge of `clock`, when the design has been reset (`hasBeenReset` is true), `state` must be logically true (non-zero)." For a 1-bit signal, `assertOneHot(state)` is equivalent to `PopCount(state) === 1.U`, which reduces to `state === 1'b1`.

## 3. Waveform Information

- **Waveform File**: `verilog/extra_bench/short/short.state_one_hot.fst`
- **Waveform Duration**: 10 ns (1 clock cycle)
- **Key Time Points**:

| Time (ns) | clock | reset | hasBeenReset | state | state_one_hot (signal) |
|-----------|-------|-------|--------------|-------|----------------------|
| 0         | 1     | 0     | 1            | 0     | 1                    |
| 5         | 0     | 0     | —            | 0     | 1                    |
| 10        | 0     | 0     | —            | 0     | 1                    |

- **Critical Observation**: At time 0, `clock=1` (posedge), `hasBeenReset=1` (assertion enabled), and `state=0` (ready). The assertion checks `state` (non-zero), which evaluates to `0` (false), causing the assertion to fail.

## 4. Root Cause Analysis

### Root Cause Category: **Incorrect Assertion** (assertion_error)

### Bug Location
- **File**: `short.scala`, Line 39
- **Function**: Module `short`
- **Assertion**: `assertOneHot(state, "state_one_hot")`

### Description

The root cause is a **mismatch between the assertion property and the design's state encoding**.

1. **State encoding**: The Chisel code uses `Enum(2)` (line 13) to create a binary-encoded FSM:
   ```scala
   val ready :: busy :: Nil = Enum(2)
   ```
   This produces `ready = 0` and `busy = 1` — a 1-bit binary encoding.

2. **Assertion property**: The `assertOneHot` assertion (line 39) checks that `state` has **exactly one bit set**. Its semantics are:
   ```verilog
   assert property (@(posedge clock) disable iff (~hasBeenReset) state);
   ```
   For a 1-bit register, `PopCount(state) === 1.U` simplifies to `state === 1'b1`. The assertion requires `state` to be logically true (equal to 1).

3. **Violation mechanism**: The state register is initialized to `ready = 0` (binary `0`). At the first posedge clock after reset, `state = 0`, which has **zero bits set**. The `assertOneHot` check `PopCount(0.U) === 1.U` evaluates to false (`0 === 1` is false), causing the assertion to fail.

### Why This is an Assertion Error

The property `assertOneHot` is designed for **one-hot encoded** finite state machines, where each state occupies its own bit position (e.g., `ready=2'b01`, `busy=2'b10`). In a one-hot encoding, exactly one bit is always set, which is what `assertOneHot` checks.

However, the design uses a **binary encoding** (via `Enum(2)`), where `ready=0` and `busy=1`. The value `0` (ready) has zero bits set, which **cannot** satisfy a one-hot property. This is an inherent conflict — a binary-encoded FSM with a `0`-valued state can never pass a one-hot check.

### Evidence Summary

| Signal | Value | Why It Causes Failure |
|--------|-------|----------------------|
| `short.state` | 0 | Initialized to `ready` (binary 0) |
| `short.hasBeenReset` | 1 | Enables the assertion (disable condition is false) |
| `short.clock` | 1 | Posedge triggers assertion evaluation |
| Assertion check | `state` evaluates to false (0) | `PopCount(0) ≠ 1` → assertion fails |

### Recommendation

To fix this, either:

- **Option A (Change the assertion)**: Replace `assertOneHot` with a valid-state check appropriate for binary encoding, such as `assert(state === ready || state === busy)` or use `assertCoverage(state)`.

- **Option B (Change the encoding)**: Use one-hot encoding for the state machine (e.g., `ready = 1.U(2.W)`, `busy = 2.U(2.W)` or a manual one-hot encoding) so that `assertOneHot` becomes meaningful.

Option A is preferred as it preserves the current binary-encoded FSM while correcting the verification property.
