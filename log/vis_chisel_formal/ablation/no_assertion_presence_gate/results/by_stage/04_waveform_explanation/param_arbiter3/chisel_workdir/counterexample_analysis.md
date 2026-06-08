# Counterexample Analysis Report: `mutex_pass_token`

## 1. Verification Environment

- **Top Module**: `Main` (defined in `arbiter3.scala`, line 165)
- **Structure**: The `Main` module instantiates:
  - 3 `Controller` modules (`controllerA`, `controllerB`, `controllerC`)
  - 1 `Arbiter` module (`arbiter`)
  - 3 `Client` modules (`clientA`, `clientB`, `clientC`)
- **Connections**: Each controller is connected to one client (via `ack`/`req`), and all controllers receive the same `sel` from the arbiter. The arbiter's `active` signal is the OR of the three controllers' `pass_token` outputs.
- **Design Description**: A round-robin token-passing arbiter with three clients. The arbiter cycles through selections A→B→C, and the corresponding controller grants the token (acknowledgment) to its client.

## 2. Violated Assertion

- **Full Assertion Name**: `mutex_pass_token`
- **Location**: `arbiter3.scala`, line 224
- **Code Snippet**:
  ```scala
  // Safety: At most one controller passes the token at any time
  // (token integrity — the token must be in exactly one place)
  assertMutex(Seq(io.pass_tokenA, io.pass_tokenB, io.pass_tokenC), "mutex_pass_token")
  ```
- **Property Description**: The assertion checks mutual exclusion on the `pass_token` signals of all three controllers. At any given time, at most one controller should have `pass_token = true` — i.e., the token should reside in exactly one place.

## 3. Waveform Information

- **Waveform File**: `verilog/extra_bench/param_arbiter3/Main.mutex_pass_token.fst`
- **Time Range**: 0 ns → 10 ns (1 cycle)
- **Key Observations at Time 0 ns**:

| Signal | Value |
|--------|-------|
| `Main.controllerA.passTokenReg` | 1 (true) |
| `Main.controllerB.passTokenReg` | 1 (true) |
| `Main.controllerC.passTokenReg` | 1 (true) |
| `Main.controllerA.io_pass_token` | 1 (true) |
| `Main.controllerB.io_pass_token` | 1 (true) |
| `Main.controllerC.io_pass_token` | 1 (true) |
| `Main.io_pass_tokenA` | 1 (true) |
| `Main.io_pass_tokenB` | 1 (true) |
| `Main.io_pass_tokenC` | 1 (true) |
| `Main.mutex_pass_token` | 1 (assertion triggered) |

The signal values remain unchanged between 0 ns and 5 ns, indicating this is a single-cycle counterexample that fails immediately at the first clock cycle.

## 4. Root Cause Analysis

### Buggy Code Location

**File**: `arbiter3.scala`
**Line**: 39 (inside `class Controller`)
**Code**:
```scala
val passTokenReg = RegInit(true.B)
```

### Description of the Bug

The `Controller` module's `passTokenReg` register is initialized to `true.B` (line 39) in **all three** controller instances. Since there is no mechanism to ensure that only one controller has this initial value, **all three controllers** start with `pass_token = true` simultaneously.

This directly violates the mutual exclusion assertion `assertMutex(Seq(io.pass_tokenA, io.pass_tokenB, io.pass_tokenC), "mutex_pass_token")`, which requires that at most one controller's `pass_token` be true at any time.

### Evidence from Waveform

At time 0 ns (the initial/reset state):
- `Main.controllerA.passTokenReg = 1`
- `Main.controllerB.passTokenReg = 1`
- `Main.controllerC.passTokenReg = 1`

All three `passTokenReg` registers (and consequently all three `io.pass_token` outputs) are high simultaneously, causing the assertion failure from the very first cycle.

### Why This Causes the Assertion to Fail

The `assertMutex` primitive checks that at most one of its inputs is true at any time. With all three `pass_token` signals being true at reset, the mutex condition `(pass_tokenA + pass_tokenB + pass_tokenC) <= 1` evaluates to `3 <= 1`, which is false. The assertion is violated immediately.

### Error Classification

**Type**: `dut_bug` — a genuine bug in the design.

### Suggested Fix

Only one controller should have `passTokenReg` initialized to `true.B`. The other two controllers should initialize to `false.B`. For example, the `Main` module could parameterize the controllers, or a single token controller could be designated:

```scala
// In Controller class — make initialization configurable:
class Controller(val initToken: Boolean = false) extends Module {
  ...
  val passTokenReg = RegInit(initToken.B)
  ...
}

// In Main:
val controllerA = Module(new Controller(true))  // starts with token
val controllerB = Module(new Controller(false))
val controllerC = Module(new Controller(false))
```

Alternatively, use a dedicated token register in the `Main` module that is handed to exactly one controller at startup.
