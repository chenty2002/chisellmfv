# Counterexample Analysis Report: `mutex_pass_tokens` Assertion Failure

## 1. Verification Environment

### Top Module
- **Module**: `Main` (in `llmverify` package)
- **Source File**: `chisel/extra_bench/param_arbiter3/arbiter3.scala`, line 130

### Structure
The `Main` module instantiates the following components:
| Component | Instance | Class | Key Role |
|-----------|----------|-------|----------|
| **Controller A** | `controllerA` | `Controller` | Handles arbitration for client A |
| **Controller B** | `controllerB` | `Controller` | Handles arbitration for client B |
| **Controller C** | `controllerC` | `Controller` | Handles arbitration for client C |
| **Arbiter** | `arbiter` | `Arbiter` | Round-robin selection of controllers |
| **Client A** | `clientA` | `Client` | Generates pseudo-random requests for controller A |
| **Client B** | `clientB` | `Client` | Generates pseudo-random requests for controller B |
| **Client C** | `clientC` | `Client` | Generates pseudo-random requests for controller C |

### Connections
- Controller `io.id` is set to `Selection.A`, `Selection.B`, `Selection.C` respectively
- Controller `io.sel` comes from the arbiter's round-robin selection output
- Controller `io.req` comes from the corresponding client's request
- Arbiter `io.active` is true when any controller's `pass_token` is asserted
- The arbiter cycles through A→B→C→A when active

---

## 2. Violated Assertion

### Assertion Name
`Main.mutex_pass_tokens`

### Code Snippet (arbiter3.scala, line 201)
```scala
// At most one controller can pass the token at a time
assertMutex(Seq(io.pass_tokenA, io.pass_tokenB, io.pass_tokenC), "mutex_pass_tokens")
```

### Property Description
The mutual exclusion property requires that **at most one** of the three `pass_token` signals is asserted at any time. This is a critical safety property for the token-passing round-robin scheme: only one controller should hold or pass the token at any given moment.

### File Location
- **File**: `chisel/extra_bench/param_arbiter3/arbiter3.scala`
- **Line**: 201

---

## 3. Waveform Information

### Waveform File
`verilog/extra_bench/param_arbiter3/Main.mutex_pass_tokens.fst`

### Time Range
0 ns → 10 ns (1 clock cycle)

### Key Findings at Time 0 ns (Immediately After Reset)

| Signal | Value | Meaning |
|--------|-------|---------|
| `Main.mutex_pass_tokens` | **1** | Assertion is **VIOLATED** at time 0 |
| `Main.controllerA.io_pass_token` | **1** | Controller A claims to have the token |
| `Main.controllerB.io_pass_token` | **1** | Controller B claims to have the token |
| `Main.controllerC.io_pass_token` | **1** | Controller C claims to have the token |
| `Main.controllerA.passTokenReg` | **1** | A's token register initialized to true |
| `Main.controllerB.passTokenReg` | **1** | B's token register initialized to true |
| `Main.controllerC.passTokenReg` | **1** | C's token register initialized to true |
| `Main._atMostOne_T_6 [1:0]` | **11** (binary 3) | Count of asserted pass_tokens = 3 |
| `Main.controllerA.state [1:0]` | **00** (IDLE) | All controllers in IDLE state |
| `Main.controllerB.state [1:0]` | **00** (IDLE) | All controllers in IDLE state |
| `Main.controllerC.state [1:0]` | **00** (IDLE) | All controllers in IDLE state |
| `Main.controllerA.io_req` | **0** | No pending requests |
| `Main.controllerB.io_req` | **0** | No pending requests |
| `Main.controllerC.io_req` | **0** | No pending requests |

These values persist at times 5 ns and 10 ns without any changes throughout the single-cycle waveform.

---

## 4. Root Cause Analysis

### Error Classification
- **Category**: **Bug in the Original Design (DUT Bug)**

### Buggy Code Location
- **File**: `chisel/extra_bench/param_arbiter3/arbiter3.scala`
- **Line**: 31
- **Module**: `Controller` class definition

### Buggy Code
```scala
val passTokenReg = RegInit(true.B)  // Line 31 — ALL controllers start with token!
```

### Root Cause Description

**All three `Controller` instances are initialized to hold the token simultaneously.**

The `Controller` class (lines 20-63) defines a `passTokenReg` register that tracks whether the controller currently possesses the arbitration token. On line 31, this register is initialized to `true.B` for **every** instance of the class.

Since all three controllers (`controllerA`, `controllerB`, `controllerC`) are identical instantiations of the same `Controller` class (lines 147-149):
```scala
val controllerA = Module(new Controller)
val controllerB = Module(new Controller)
val controllerC = Module(new Controller)
```

They **all** start with `passTokenReg = true.B` after reset, causing all three `io.pass_token` outputs to be asserted simultaneously (value `1` each at time 0).

### How the Software Logic Works (and Fails)

The controller's FSM logic in `IDLE` state (lines 39-50) is designed to eventually resolve token possession:
```
IDLE state:
  when isSelected (arbiter selected this controller):
    when io.req (client has a request):
      → passTokenReg := false.B   (give up token, move to READY)
    otherwise:
      → passTokenReg := true.B    (hold the token)
  otherwise (not selected):
    → passTokenReg := false.B     (give up token)
```

- If selected and client requests: pass token, go to READY
- If selected and no request: hold the token (stay in IDLE)
- If NOT selected: give up the token

At time 0, the arbiter's `io.sel` is `00` (Selection.A), so:
- Controller A is selected (`isSelected = true`), but `io.req = 0` → holds token (stays `true`)
- Controller B is **not** selected → should set `passTokenReg := false.B`
- Controller C is **not** selected → should set `passTokenReg := false.B`

**However**, the assertion `mutex_pass_tokens` is a *combinational* property checked at **all** times, including time 0 (immediately after reset). At time 0, before any clock edge, all three registers still hold their initial value of `true.B`, so the mutual exclusion property is violated at the very first timestep.

### Why the Assertion Fails Immediately

The `assertMutex` function (from the `chiselFv` library) generates a check that at most one of its inputs is true at any time. The generated circuit computes the sum of active pass_tokens (visible as `Main._atMostOne_T_6 [1:0] = 11` = 3) and asserts it is ≤ 1. Since 3 > 1, the assertion is violated at time 0.

### Evidence Summary from Waveform

| Evidence | Value |
|----------|-------|
| All 3 `passTokenReg` registers at time 0 | All `1` |
| All 3 `io_pass_token` outputs at time 0 | All `1` |
| Count of active pass_tokens (`_atMostOne_T_6`) | `3` (binary `11`) |
| Assertion `mutex_pass_tokens` status | Violated (`1`) |
| All controllers in IDLE state with no req | Confirmed |

### Proposed Fix

Only **one** controller should start with the token. The fix is to make the initial value of `passTokenReg` configurable via a constructor parameter:

**Option 1**: Add a `startWithToken` parameter to the `Controller` class:
```scala
class Controller(startWithToken: Boolean = false) extends Module {
  ...
  val passTokenReg = RegInit(startWithToken.B)
  ...
}
```
Then instantiate with:
```scala
val controllerA = Module(new Controller(true))   // Only A starts with token
val controllerB = Module(new Controller(false))  // B starts without token
val controllerC = Module(new Controller(false))  // C starts without token
```

**Option 2** (simpler): Override the register initial value in the `Main` module after reset, but this is less clean.

The key insight is that in a token-passing round-robin arbiter, exactly one controller must begin with the token to satisfy mutual exclusion at time 0.
