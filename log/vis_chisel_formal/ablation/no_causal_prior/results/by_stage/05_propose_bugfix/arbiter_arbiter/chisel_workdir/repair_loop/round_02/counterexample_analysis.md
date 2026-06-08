# Counterexample Analysis Report: mutex_passTokens

## 1. Verification Environment

- **Top module**: `Main` (in `arbiter.scala`, line 115)
- **Structure**:
  - `Main` instantiates three `Client` modules (`clientA`, `clientB`, `clientC`) that generate random requests
  - Three `Controller` modules (`controllerA`, `controllerB`, `controllerC`) that handle handshake between clients and arbiter using a token-passing protocol
  - One `Arbiter` module (`arbiterModule`) that cycles through clients A→B→C→A when the token is active
  - The `activeWire` signal (OR of all three controllers' passToken outputs) controls whether the arbiter cycles
- **Purpose**: Round-robin arbiter with token-based mutual exclusion — only one controller should hold the "pass token" at any time, ensuring only one client gets serviced

## 2. Violated Assertion

- **Full assertion name**: `mutex_passTokens` (from waveform filename `Main.mutex_passTokens.fst`)
- **Source location**: `arbiter.scala`, line 223
- **Code snippet**:
  ```scala
  // ---- Safety: Mutual Exclusion of PassTokens ----
  // At most one controller should be passing the token forward.
  // Multiple passTokens would indicate conflicting token ownership.
  assertMutex(Seq(io.passTokenA, io.passTokenB, io.passTokenC), "mutex_passTokens")
  ```
- **Property description**: At most one of the three `passToken` signals (`io.passTokenA`, `io.passTokenB`, `io.passTokenC`) may be high (`true.B`) at any point in time. All three being high simultaneously violates the token-based mutual exclusion protocol.

## 3. Waveform Information

- **Waveform file**: `verilog/extra_bench/arbiter_arbiter/Main.mutex_passTokens.fst`
- **Time range**: 0 ns → 10 ns (1 cycle)
- **Key time point**: 0 ns (immediately after reset)

### Critical signal values at time 0 ns:

| Signal | Value |
|--------|-------|
| `Main.io_passTokenA` | 1 (true) |
| `Main.io_passTokenB` | 1 (true) |
| `Main.io_passTokenC` | 1 (true) |
| `Main.controllerA.passTokenReg` | 1 (true) |
| `Main.controllerB.passTokenReg` | 1 (true) |
| `Main.controllerC.passTokenReg` | 1 (true) |
| `Main.controllerA.state [1:0]` | 00 (IDLE) |
| `Main.controllerB.state [1:0]` | 00 (IDLE) |
| `Main.controllerC.state [1:0]` | 00 (IDLE) |
| `Main.io_reqA` | 0 |
| `Main.io_reqB` | 0 |
| `Main.io_reqC` | 0 |
| `Main.io_ackA` | 0 |
| `Main.io_ackB` | 0 |
| `Main.io_ackC` | 0 |
| `Main.hasBeenResetReg` | 1 |
| `Main.mutex_passTokens` | 1 (assertion violated) |

**All three `passToken` signals are high at time 0**, which directly violates the mutual exclusion property.

## 4. Root Cause Analysis

### Bug Location
- **File**: `arbiter.scala`
- **Line**: 69
- **Module/Class**: `Controller` (lines 59-105)

### Bug Description

**The initialization of `passTokenReg` is incorrect.** On line 69:

```scala
val passTokenReg = RegInit(true.B)
```

All three `Controller` instances (`controllerA`, `controllerB`, `controllerC`) initialize their `passTokenReg` to `true.B`. This means that immediately after reset, all three controllers simultaneously claim to hold the pass token, violating the mutual exclusion property `assertMutex(Seq(io.passTokenA, io.passTokenB, io.passTokenC), "mutex_passTokens")`.

The token-based protocol requires that **exactly one** controller holds the token at any given time. The token is then passed around according to the round-robin arbiter's selection:
- When a controller is **selected** and its client **has a request**: the controller consumes the token (`passTokenReg := false.B`, enters READY→BUSY to acknowledge)
- When a controller is **selected** and its client **has no request**: the controller passes the token forward (`passTokenReg := true.B`, signaling the arbiter to continue)
- When a controller is **not selected**: it does not pass the token (`passTokenReg := false.B`)

### Evidence from Waveform

At time 0 ns (post-reset, before any clock edge):
- `Main.io_passTokenA` = 1, `Main.io_passTokenB` = 1, `Main.io_passTokenC` = 1 — **all three are high**
- `Main.controllerA.passTokenReg` = 1, `Main.controllerB.passTokenReg` = 1, `Main.controllerC.passTokenReg` = 1 — **all three registers initialized to true**
- All controllers are in IDLE state (00), no requests or acks active

The three passToken signals are a direct wire from each controller's `passTokenReg` (line 72: `io.passToken := passTokenReg`), and the `RegInit(true.B)` on line 69 sets all three to `true.B` at reset.

### Why the Assertion Fails

The `assertMutex` function checks pairwise mutual exclusion: for any pair (i, j), it asserts `!(passToken_i && passToken_j)`. With all three passToken signals high at time 0, every pair `(A,B)`, `(A,C)`, `(B,C)` violates the check, causing the assertion to fail immediately after reset.

### Fix

Line 69 should be changed to initialize only one controller with the token. For example:

```scala
val passTokenReg = RegInit(if (id == Selection.A) true.B else false.B)
```

This ensures that only `controllerA` starts with the token (`passTokenReg = true.B`), while `controllerB` and `controllerC` start without it (`passTokenReg = false.B`). The token will then propagate through the system as the arbiter cycles through the controllers in round-robin order.

### Error Classification
- **Error type**: `dut_bug` — the Controller design has a real bug in its initialization logic
