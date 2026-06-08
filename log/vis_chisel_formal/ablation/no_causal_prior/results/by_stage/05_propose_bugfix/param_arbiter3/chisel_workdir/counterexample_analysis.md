# Counterexample Analysis Report

## 1. Verification Environment

- **Top Module**: `Main` (in `arbiter3.scala`)
- **Design Under Test**: A round-robin token-passing arbiter with three clients (A, B, C), each served by a `Controller` instance, with a round-robin `Arbiter` selecting the active controller.
- **Key Components**:
  - `Controller` (×3): Handles per-client state machine (IDLE → READY → BUSY) and token passing logic.
  - `Arbiter`: Round-robin selector cycling through A → B → C.
  - `Client` (×3): Generates pseudo-random requests using an LFSR.
  - **Connections**: Each `Controller.io.pass_token` feeds into the `active` signal for the arbiter. All three `io.pass_token` signals are wired to top-level outputs.

## 2. Violated Assertion

- **Assertion Name**: `pass_token_mutex` (extracted from waveform filename `Main.pass_token_mutex.fst`)
- **File Location**: `arbiter3.scala`, lines 196–197
- **Code Snippet**:
  ```scala
  // At most one pass_token can be high at any time (token passes atomically)
  fvAssert(PopCount(Seq(io.pass_tokenA, io.pass_tokenB, io.pass_tokenC)) <= 1.U, "pass_token_mutex")
  ```
- **Property Description**: The mutual exclusion property ensures that at most one controller's `pass_token` output is high at any given time. This guarantees the token is passed atomically and never duplicated.

## 3. Waveform Information

- **Waveform File**: `verilog/extra_bench/param_arbiter3/Main.pass_token_mutex.fst`
- **Time Range**: 0 ns → 10 ns (1 clock cycle)
- **Key Time Points**:
  - **t = 0 ns** (reset / initial state): All three `passTokenReg` registers are `1` (true). All three `io_pass_token` outputs are `1`.
  - **t = 5 ns**: Same values persist — all three `io_pass_token` signals remain `1`.
  - **t = 10 ns**: Same — no change.
- **Critical Signal Values at Failure Point (t = 0 ns)**:
  | Signal | Value |
  |--------|-------|
  | `Main.controllerA.passTokenReg` | 1 |
  | `Main.controllerB.passTokenReg` | 1 |
  | `Main.controllerC.passTokenReg` | 1 |
  | `Main.controllerA.io_pass_token` | 1 |
  | `Main.controllerB.io_pass_token` | 1 |
  | `Main.controllerC.io_pass_token` | 1 |
  | `Main.io_pass_tokenA` | 1 |
  | `Main.io_pass_tokenB` | 1 |
  | `Main.io_pass_tokenC` | 1 |
  | `Main.pass_token_mutex` | 1 (assertion violation detected) |

## 4. Root Cause Analysis

### Category: Bug in the Original Design (DUT Bug)

### Buggy Code Location
- **File**: `arbiter3.scala`
- **Line**: 55
- **Module**: `Controller` class

### Bug Description

```scala
val passTokenReg = RegInit(true.B)   // Line 55 — BUG: initialized to true.B for ALL controllers
```

The `passTokenReg` register in the `Controller` module is initialized to `true.B` for every instance. Because three `Controller` instances are created (`controllerA`, `controllerB`, `controllerC`), all three registers start with the value `1` (true) after reset. This means all three controllers output `io.pass_token = true` simultaneously, directly violating the mutual exclusion property.

### Expected Behavior

Only **one** controller should hold the token at any time. At reset, exactly one controller should have `passTokenReg = true.B` (e.g., `controllerA`), while the other two should have `passTokenReg = false.B`. The token should then be passed around the round-robin ring.

### Why This Causes the Assertion to Fail

The assertion `PopCount(Seq(io.pass_tokenA, io.pass_tokenB, io.pass_tokenC)) <= 1.U` evaluates to `PopCount(1, 1, 1) = 3`, which is **not** `<= 1`. The violation occurs immediately at time 0 (reset), before any clock cycles, because the initial register values break the invariant.

### How the Token Passing is Designed to Work

The `Controller` state machine manages `passTokenReg` as follows:

1. **IDLE state** (when selected + no request): `passTokenReg := true.B` — passes the token onward.
2. **IDLE state** (when not selected): `passTokenReg := false.B` — clears the token.
3. **BUSY → IDLE transition** (when request finishes): `passTokenReg := true.B` — passes the token after service completes.

The token passing mechanism assumes only one controller has `passTokenReg = true` at any time, but the `RegInit(true.B)` initialization breaks this assumption by giving the token to all three controllers simultaneously.

### Proposed Fix

Change the `passTokenReg` initialization so that exactly one controller starts with the token. The cleanest approach is to add a constructor parameter:

```scala
class Controller(initToken: Boolean = false) extends Module {
  ...
  val passTokenReg = RegInit(initToken.B)
  ...
}
```

Then instantiate with:
```scala
val controllerA = Module(new Controller(true))  // Token starts with A
val controllerB = Module(new Controller(false))
val controllerC = Module(new Controller(false))
```

Alternatively, use a `pass_token` input to the `Controller` for external token injection, or implement a proper token-ring initialization at the `Main` level.
