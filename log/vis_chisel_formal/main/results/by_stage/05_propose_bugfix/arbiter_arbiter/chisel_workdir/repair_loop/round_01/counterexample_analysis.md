# Counterexample Analysis Report: `mutex_passToken` Assertion Failure

## 1. Verification Environment

- **Top Module**: `Main` (from `arbiter.scala`)
- **Key Components**:
  - `clientA`, `clientB`, `clientC` (3 × `Client` modules — generate requests pseudo-randomly)
  - `controllerA`, `controllerB`, `controllerC` (3 × `Controller` modules — handle handshake between client and arbiter, output `passToken` when the controller is ready to grant the token)
  - `arbiterModule` (1 × `Arbiter` module — round-robin selection of clients)
- **Design Under Test**: A round-robin arbiter with three clients. The arbiter cycles through clients, each with a controller that manages token passing, acknowledgment handshake, and state transitions (IDLE → READY → BUSY → IDLE).

## 2. Violated Assertion

- **Assertion Name**: `mutex_passToken` (from waveform filename `Main.mutex_passToken.fst`)
- **Code Snippet** (from `arbiter.scala`, lines ~208-209):

```scala
  // ----- SAFETY: Mutual Exclusion of Token Passing -----
  // At most one controller passes the token at a time.
  // Multiple simultaneous passTokens would corrupt the round-robin order.
  assertMutex(Seq(io.passTokenA, io.passTokenB, io.passTokenC), "mutex_passToken")
```

- **Property Description**: At most one of `io.passTokenA`, `io.passTokenB`, `io.passTokenC` should be `true` at any time. Multiple simultaneous passTokens would corrupt the round-robin ordering.
- **File Location**: `arbiter.scala`, lines 206–209.

## 3. Waveform Information

- **Waveform File**: `verilog/extra_bench/arbiter_arbiter/Main.mutex_passToken.fst`
- **Time Range**: 0 ns → 10 ns (1 cycle)
- **Key Time Points**:
  - **Time 0 ns**: All signals are at their initial/reset values. The assertion fails immediately at time 0.

| Signal | Value at Time 0 |
|---|---|
| `Main.io_passTokenA` | 1 |
| `Main.io_passTokenB` | 1 |
| `Main.io_passTokenC` | 1 |
| `Main.controllerA.passTokenReg` | 1 |
| `Main.controllerB.passTokenReg` | 1 |
| `Main.controllerC.passTokenReg` | 1 |
| `Main.hasBeenReset` | 1 |
| `Main.reset` | 0 |
| `Main.controllerA.state [1:0]` | 00 (IDLE) |
| `Main.controllerB.state [1:0]` | 00 (IDLE) |
| `Main.controllerC.state [1:0]` | 00 (IDLE) |
| `Main.io_sel [1:0]` | 00 (Selection.A) |
| `Main.io_active` | 1 |

- **Critical Observation**: All three `passToken` signals are 1 throughout the entire waveform (no transitions), confirming the violation is present at the initial state and persists.

## 4. Root Cause Analysis

### Bug Location

- **File**: `arbiter.scala`
- **Line**: 91
- **Module**: `Controller` class
- **Line of Code**:
  ```scala
  val passTokenReg = RegInit(true.B)
  ```

### Description of the Bug

All three `Controller` instances (`controllerA`, `controllerB`, `controllerC`) initialize their `passTokenReg` register to `true.B` at reset. This means that immediately after reset (time 0), all three controllers drive `passToken = true` simultaneously.

The mutual exclusion property (`assertMutex`) requires that at most one `passToken` signal is high at any time. With all three at `true`, the assertion is violated from the very first cycle.

### Evidence from Waveform

1. `Main.controllerA.passTokenReg` = 1 at time 0
2. `Main.controllerB.passTokenReg` = 1 at time 0
3. `Main.controllerC.passTokenReg` = 1 at time 0
4. `Main.io_passTokenA` = 1, `Main.io_passTokenB` = 1, `Main.io_passTokenC` = 1 at time 0

All three signals are constant at `1` for the entire waveform duration (0–10 ns), with zero transitions.

### Why This Causes the Assertion to Fail

The `assertMutex(Seq(io.passTokenA, io.passTokenB, io.passTokenC), "mutex_passToken")` call generates a check that at most one of the three boolean signals is true at any cycle. With all three `passToken` signals starting at `true` due to `RegInit(true.B)` in every controller, the assertion is falsified at time 0.

### Error Classification

**Error Type**: **DUT Bug** — The design has a genuine bug in the initialization of `passTokenReg`. The fix should ensure that only one controller (e.g., `controllerA`) initializes `passTokenReg` to `true.B`, while the other two initialize to `false.B`. This ensures the mutual exclusion property holds at reset.

### Suggested Fix

Modify the `Controller` class to accept an initial value for `passTokenReg`, or parameterize it via a constructor argument:

```scala
class Controller(id: Selection.Type, initPassToken: Boolean = false) extends Module {
  // ...
  val passTokenReg = RegInit(initPassToken.B)
  // ...
}
```

Then instantiate only the first controller with `initPassToken = true`:

```scala
val controllerA = Module(new Controller(Selection.A, true))
val controllerB = Module(new Controller(Selection.B, false))
val controllerC = Module(new Controller(Selection.C, false))
```

Alternatively, keep `passTokenReg` initialization as shown in the existing code, but differentiate so that only `Controller(Selection.A)` uses `true.B` and the others use `false.B`.
