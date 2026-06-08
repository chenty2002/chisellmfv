# Counterexample Analysis Report: `Main.ackImpliesReq`

## 1. Verification Environment

- **Top module**: `Main` (defined in `arbiter.scala`, line 134)
- **Structure**: The `Main` module instantiates three clients (`Client`), three controllers (`Controller`), and one `Arbiter` module. The system implements a token-passing arbiter that grants access to one client at a time.
- **Key connections**:
  - `clientX.io.req` → `controllerX.io.req`
  - `clientX.io.ack` ← `controllerX.io.ack`
  - `arbiterModule.io.sel` → `controllerX.io.sel` (shared selection line)
  - `activeWire = controllerA.io.passToken || controllerB.io.passToken || controllerC.io.passToken` → `arbiterModule.io.active`
  - The arbiter cycles through clients A→B→C→A when active, outputting `sel = Mux(active, state, Selection.X)`.
- **Design purpose**: A round-robin arbiter where clients randomly request tokens, the arbiter selects them, and the controller acknowledges them via a token-passing protocol.

## 2. Violated Assertion

- **Assertion name** (from waveform filename): `ackImpliesReq`
- **Full assertion code** (lines 220–225 of `arbiter.scala`):

```scala
AssertProperty(
  !(io.ackA && !io.reqA) &&
  !(io.ackB && !io.reqB) &&
  !(io.ackC && !io.reqC),
  None, None, Some("ackImpliesReq")
)
```

- **Natural language description**: "Acknowledge only when the corresponding client has an active request. The controller must not grant a token to a client that did not ask for one."
- **File location**: `arbiter.scala`, lines 220–225

## 3. Waveform Information

- **Waveform file**: `verilog/extra_bench/arbiter_arbiter/Main.ackImpliesReq.fst`
- **Time range**: 0 ns to 70 ns (7 cycles at 10 ns period)
- **Failure time**: The assertion `ackImpliesReq` goes from `1` to `0` at **time 60 ns** and remains `0` at time 70 ns.

### Critical signal values at failure point (time 60 ns):

| Signal | Value |
|--------|-------|
| `Main.io_ackC` | 1 |
| `Main.io_reqC` | 0 |
| `Main.io_ackA` | 0 |
| `Main.io_reqA` | 1 |
| `Main.io_ackB` | 0 |
| `Main.io_reqB` | 1 |
| `Main.ackImpliesReq` | 0 (violation) |

The violation is specifically on **clientC**: `ackC=1` while `reqC=0`.

## 4. Root Cause Analysis

### Bug Type: **DUT Bug** — the Controller's BUSY-state transition is one cycle too slow

### Buggy code location

**File**: `arbiter.scala`, lines 94–99  
**Module**: `Controller` class  
**State**: `ControllerState.BUSY`

```scala
is(ControllerState.BUSY) {
  when(!io.req) {
    state := ControllerState.IDLE
    ackReg := false.B
    passTokenReg := true.B
  }
}
```

### Description of the bug

The controller's BUSY state waits for `!io.req` (the client's request dropping) before transitioning back to IDLE and deasserting `ackReg`. However, `io.req` is a **register output** from the Client module (`reqReg`). When both the Client and Controller update their registers on the same clock edge, the Controller samples the **old value** of `req` (still `1`), preventing the transition. This creates a one-cycle window where `ack` remains asserted (`1`) after `req` has already been deasserted (`0`), violating the assertion `!(ack && !req)`.

### Detailed trace of the bug (complete timeline for clientC)

| Time (ns) | Posedge | clientC.state | clientC.reqReg | controllerC.state | controllerC.ackReg | io_ackC | io_reqC | Assertion OK? |
|-----------|---------|---------------|----------------|-------------------|--------------------|---------|---------|---------------|
| 0–10 | — | NO_REQ (00) | 0 | IDLE (00) | 0 | 0 | 0 | ✓ |
| 10–20 | time=10 | NO_REQ (00) | 0 | IDLE (00) | 0 | 0 | 0 | ✓ |
| **20** | ✓ | NO_REQ→**REQ (01)** | 0→**1** | IDLE (00) | 0 | 0 | 0→1 | ✓ |
| 20–30 | — | REQ (01) | 1 | IDLE (00) | 0 | 0 | 1 | ✓ |
| **30** | ✓ | REQ (01) | 1 | IDLE→**READY (01)** | 0 (no change yet) | 0 | 1 | ✓ |
| 30–40 | — | REQ (01) | 1 | READY (01) | 0 | 0 | 1 | ✓ |
| **40** | ✓ | REQ (01) | 1 | READY→**BUSY (10)** | 0→**1** | 0→1 | 1 | ✓ |
| 40–50 | — | REQ (01) | 1 | BUSY (10) | 1 | 1 | 1 | ✓ |
| **50** | ✓ | REQ→**HAVE_TOKEN (10)** | 1 | BUSY (10) | 1 | 1 | 1 | ✓ |
| 50–60 | — | HAVE_TOKEN (10) | 1 | BUSY (10) | 1 | 1 | 1 | ✓ |
| **60** | ✓ | HAVE_TOKEN→**NO_REQ (00)** | **1→0** | **BUSY (10) [stays!]** | **1 [stays!]** | **1** | **1→0** | **✗ FAIL** |
| 60–70 | — | NO_REQ (00) | 0 | BUSY (10) | 1 | 1 | 0 | **✗ FAIL** |

### Why the controller stays in BUSY at the critical edge

At posedge **60 ns**, the following register updates occur simultaneously (Chisel/Verilog non-blocking assignment semantics):

1. **ClientC** (in HAVE_TOKEN state): because `randChoice=1` (LSB of `randCounter[7:0]=00000101` before increment), it executes `reqReg := false.B` and `state := NO_REQ`.
2. **ControllerC** (in BUSY state): evaluates `!io.req` by reading `clientC.reqReg`. Due to **register semantics**, it reads the **old value** (still `1`), so `!io.req` evaluates to `false`, and **no transition occurs** — `ackReg` stays `1`.
3. **After the edge**: `io_reqC` shows `0` (the new value of `reqReg`), but `io_ackC` remains `1` (controller still in BUSY). This violates `!(io.ackC && !io.reqC)`.

The controller only sees `io.req=0` at the **next** posedge (70 ns), where it finally transitions BUSY→IDLE and deasserts `ackReg`. But the damage is done: the assertion fails for two full cycles (60 ns and 70 ns).

### Why this is a real design bug (not an assertion error)

The handshake protocol requires that the controller deassert `ack` in the **same cycle** that the client deasserts `req`. Since both are register outputs, the controller cannot detect the falling edge of `req` on the same cycle. The invariant `ack → req` is a fundamental safety property of the token-passing protocol — granting a token to a client that has no pending request is a protocol violation that could lead to spurious grants and loss of the token.

### Proposed fix

The controller should not wait for `!io.req` in the BUSY state to deassert `ack`. Instead, once the controller has asserted `ack` (in READY→BUSY transition), it should release it automatically:

**Option 1**: Transition out of BUSY unconditionally (simplest):
```scala
is(ControllerState.BUSY) {
  state := ControllerState.IDLE
  ackReg := false.B
  passTokenReg := true.B
}
```

**Option 2**: Use a one-shot counter to keep BUSY for only one cycle:
```scala
is(ControllerState.BUSY) {
  state := ControllerState.IDLE
  ackReg := false.B
  passTokenReg := true.B
}
```

Either fix ensures that `ack` is asserted for exactly one cycle and is deasserted before the client can drop `req`, preserving the `ack → req` invariant.
