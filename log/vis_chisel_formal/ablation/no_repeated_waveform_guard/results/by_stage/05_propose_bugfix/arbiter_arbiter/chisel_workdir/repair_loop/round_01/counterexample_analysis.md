# Counterexample Analysis Report: Arbiter Token-Passing Violation

## 1. Verification Environment

- **Top Module**: `Main` (in `arbiter.scala`)
- **Waveform File**: `Main.onehot0_passToken3A_at_most_one_passToken_active_at_a_time.fst`
- **Design Structure**:
  - 3 × `Client` modules (A, B, C): generate random requests
  - 3 × `Controller` modules (A, B, C): manage token handshake with clients
  - 1 × `Arbiter` module: cycles through clients A → B → C in round-robin
  - Connections: Clients → Controllers → Arbiter, with an `activeWire` derived from `passToken` signals driving the arbiter
- **Formal Tool**: Chisel Formal Verification (chiselFv)
- **Clock**: Single shared clock, reset de-asserted throughout the trace

## 2. Violated Assertion

- **Full Assertion Name**: `onehot0_passToken: at most one passToken active at a time`
- **Code Snippet** (from `arbiter.scala`, lines ~195–197):

  ```scala
  // Safety: At most one controller passes the token at a time (one-hot-0: could be zero)
  // The token must be exclusive to guarantee correct round-robin handoff
  assertOneHot0(Cat(io.passTokenC, io.passTokenB, io.passTokenA), "onehot0_passToken: at most one passToken active at a time")
  ```

- **Property Description**: The `assertOneHot0` property checks that at most one of the three `io.passToken` bits is active (`true.B`) at any given cycle. Values of `000` (no token) and `001`, `010`, `100` (exactly one token) are legal; values with two or three bits set (e.g., `111`) are illegal. This ensures mutual exclusion of the token in the round-robin protocol.

- **File Location**: `arbiter.scala`, line 197

## 3. Waveform Information

- **Waveform Path**: `verilog/extra_bench/arbiter_arbiter/Main.onehot0_passToken3A_at_most_one_passToken_active_at_a_time.fst`
- **Duration**: 1 cycle (0 ns → 10 ns)
- **Key Time Points**:

| Time (ns) | Signal | Value |
|-----------|--------|-------|
| 0 | `Main.io_passTokenA` | 1 |
| 0 | `Main.io_passTokenB` | 1 |
| 0 | `Main.io_passTokenC` | 1 |
| 0 | `Main.controllerA.passTokenReg` | 1 |
| 0 | `Main.controllerB.passTokenReg` | 1 |
| 0 | `Main.controllerC.passTokenReg` | 1 |
| 10 | `Main.controllerA.passTokenReg` | 1 |
| 10 | `Main.controllerB.passTokenReg` | 1 |
| 10 | `Main.controllerC.passTokenReg` | 1 |

- **Reset Status**: `Main.reset = 0` (de-asserted), all controller resets = 0 at both time 0 and time 10
- **Controller States**: All three controllers in `IDLE` state (`00`) at both time 0 and time 10

**Critical Observation**: None of the three `passTokenReg` signals ever change value throughout the entire 10ns trace. They remain constant at `1`.

## 4. Root Cause Analysis

### Root Cause: Incorrect Initialization of `passTokenReg` in the Controller

**Buggy Location**: `arbiter.scala`, line 46 — `Controller` class

```scala
class Controller(id: Selection.Type) extends Module {
  val io = IO(new Bundle {
    val req = Input(Bool())
    val ack = Output(Bool())
    val sel = Input(Selection())
    val passToken = Output(Bool())
  })

  val state = RegInit(ControllerState.IDLE)
  val ackReg = RegInit(false.B)
  val passTokenReg = RegInit(true.B)   // ← BUG: initializes ALL three controllers with the token
  ...
}
```

### Description of the Bug

The `passTokenReg` register in every `Controller` instance is initialized to `true.B`. Since three controller instances are created (one for each client A, B, C), all three start with their `passTokenReg` set to `true.B` simultaneously at power-on / reset.

In a correct token-passing arbiter, the token must be exclusive — at most one controller should hold the token at any time. Initializing all three to `true.B` means all three simultaneously believe they possess the token, which directly violates the `onehot0` mutual-exclusion assertion.

### Evidence from Waveform

1. **Time 0 ns**: `Main.controllerA.passTokenReg = 1`, `Main.controllerB.passTokenReg = 1`, `Main.controllerC.passTokenReg = 1` — all three token registers are high simultaneously (3 of 3 bits set, violating onehot0 which allows at most 1).
2. **Time 10 ns**: Same values — no transitions occur, confirming the registers hold their initial `true.B` values throughout the trace.
3. **Top-level outputs**: `Main.io_passTokenA = 1`, `Main.io_passTokenB = 1`, `Main.io_passTokenC = 1` at both time 0 and time 10, confirming the violation is propagated to the top-level assertion check.

### Why This Violates the Assertion

The `assertOneHot0` check computes `Cat(io.passTokenC, io.passTokenB, io.passTokenA)` which at time 0 evaluates to `3'b111`. Since `onehot0` requires at most one bit to be set, the value `3'b111` (three bits set) is a clear violation. The assertion fails immediately at time 0.

### Correction

The fix is to initialize only one controller with the token, or initialize all to `false.B` and use a separate initialization protocol. For example:

```scala
// Option 1: Only controller A gets the token initially
val passTokenReg = RegInit(if (id == Selection.A) true.B else false.B)

// Option 2: Initialize all to false and use a startup mechanism
val passTokenReg = RegInit(false.B)
```

**Error Classification**: **DUT bug** — the design incorrectly initializes all controllers with the token, violating the mutual-exclusion property of the token-passing protocol.
