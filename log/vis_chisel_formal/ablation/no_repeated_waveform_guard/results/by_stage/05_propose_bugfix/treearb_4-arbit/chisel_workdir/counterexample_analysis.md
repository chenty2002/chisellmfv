# Counterexample Analysis Report: `leaf_request_propagates_to_top`

## 1. Verification Environment

### Top Module
- **Module**: `main` (from `arbit.scala`)
- **Structure**: A 2-level tree arbiter with one top cell (C0) and two intermediate cells (C1, C2), each serving two leaf processors (P1-P4).
- **Connections**:
  - `C0` (topCell=true): connects to C1 (left) and C2 (right). Its `io.xr` is the top-level output `sr`.
  - `C1` (topCell=false): connects to P1 (left) and P2 (right). Its `io.xr` feeds C0's `urLeft`.
  - `C2` (topCell=false): connects to P3 (left) and P4 (right). Its `io.xr` feeds C0's `urRight`.
  - `P1-P4`: simple processor models that transition between idle→request→lock→release states.
- **Design under test**: A tree arbiter using a token-passing protocol, where each `arbitCell` has a `holdToken` register that controls whether it has the right to grant to children.

## 2. Violated Assertion

- **Full assertion name**: `leaf_request_propagates_to_top` (from waveform filename `main.leaf_request_propagates_to_top.fst`)
- **Code snippet** (`arbit.scala`, lines 261-266):
  ```scala
  assertImplies(
    io.ur1 === HandShakeType.request || io.ur2 === HandShakeType.request ||
    io.ur3 === HandShakeType.request || io.ur4 === HandShakeType.request,
    io.sr === HandShakeType.request || io.sr === HandShakeType.lock,
    "leaf_request_propagates_to_top"
  )
  ```
- **Natural language description**: If any leaf processor (P1-P4) is in the `request` state, the top-level output `sr` must be either `request` or `lock` (not `idle`). This ensures that requests always propagate up the tree arbiter to the top.
- **File location**: `arbit.scala`, lines 261-266.

## 3. Waveform Information

- **Waveform file**: `verilog/extra_bench/treearb_4-arbit/main.leaf_request_propagates_to_top.fst`
- **Time range**: 0 ns to 90 ns (9 clock cycles, 10 ns period)
- **Key time points**:
  - **t=0 ns**: Reset. All signals initialized. All leaves idle (00). C0.holdToken=1, C1.holdToken=0, C2.holdToken=0.
  - **t=70 ns**: All leaves still idle. All xr outputs idle (00). All grants inactive.
  - **t=80 ns (posedge, cycle 9)**: All four leaves (ur1-ur4) transition to `request` (01). **Assertion violation occurs here.**
  - **t=80-85 ns**: Values remain stable throughout the remainder of the trace.

- **Critical signal values at failure point (t=80 ns)**:
  | Signal | Value | Meaning |
  |--------|-------|---------|
  | `io.ur1 [1:0]` | 01 (request) | Leaf P1 is requesting |
  | `io.ur2 [1:0]` | 01 (request) | Leaf P2 is requesting |
  | `io.ur3 [1:0]` | 01 (request) | Leaf P3 is requesting |
  | `io.ur4 [1:0]` | 01 (request) | Leaf P4 is requesting |
  | `C0.io_urLeft [1:0]` | 01 (request) | C1 propagates request to C0 ✓ |
  | `C0.io_urRight [1:0]` | 01 (request) | C2 propagates request to C0 ✓ |
  | `C0.io_xr [1:0]` (== `io.sr`) | **00 (idle)** | **FAILS to propagate!** ✗ |
  | `C0.holdToken` | 1 | C0 holds the token (never cleared) |
  | `C1.io_xr [1:0]` | 01 (request) | C1 correctly propagates ✓ |
  | `C2.io_xr [1:0]` | 01 (request) | C2 correctly propagates ✓ |
  | `C0.io_uaLeft` | 1 | C0 grants to left child (C1) |

## 4. Root Cause Analysis

### Buggy Code Location
- **File**: `arbit.scala`
- **Module**: `arbitCell` (line 49)
- **Lines 83-93** — the `xr` combinational logic:

```scala
// Combinational logic for xr
when(!holdToken && (io.urLeft === HandShakeType.request || io.urRight === HandShakeType.request)) {
    io.xr := HandShakeType.request        // Case A: propagate request upward
}.elsewhen(childOwns) {
    io.xr := HandShakeType.lock           // Case B: child holds lock
}.elsewhen(holdToken && ((mustGiveParent || 
                           !(io.urLeft === HandShakeType.request || io.urRight === HandShakeType.request)) && 
                           !io.topCell)) {
    io.xr := HandShakeType.release        // Case C: release token to parent
}.otherwise {
    io.xr := HandShakeType.idle           // Case D: default idle
}
```

### Description of the Bug

The bug is in the `xr` combinational logic of `arbitCell`. The first condition (Case A) requires `!holdToken` for requests to propagate upward through `xr`. However, when the top cell (C0) starts with `holdToken = 1` (initialized as `RegInit(io.topCell)`), it **never** propagates requests upward, causing `sr` (which equals `C0.io.xr`) to stay `idle` even when leaves are requesting.

**How it manifests in the waveform:**

1. **Initial state**: After reset, C0.holdToken = 1 (top cell starts with the token). C1.holdToken = 0, C2.holdToken = 0.
2. **t=80 ns**: All four leaf processors simultaneously enter `request` state.
3. **t=80 ns (C1, C2)**: Since C1.holdToken = 0 and C2.holdToken = 0, the condition `!holdToken && (leftReq || rightReq)` is true, so C1.io.xr = request (01) and C2.io.xr = request (01). ✅
4. **t=80 ns (C0)**: C0 sees requests from both children (urLeft=request, urRight=request). However, C0.holdToken = 1, so Condition A fails (`!1 && ...` = false). Condition B fails (no child in lock state). Condition C fails because `!io.topCell` is false. Falls through to **default → idle**. ❌
5. **Result**: `io.sr = C0.io.xr = 00 (idle)` while all four leaves are requesting → **assertion violation**.

**Root cause category**: This is a **Bug in the Original Design** (`dut_bug`).

### Why This Is a Design Bug (Not an Assertion Error)

The assertion's intent — "a request should always propagate up the tree" — is a correct and fundamental arbiter property. The token-passing mechanism in `arbitCell` is designed so that:

- **Non-top cells** (C1, C2) with `holdToken=0` correctly propagate requests upward via `xr = request`.
- **Non-top cells** with `holdToken=1` correctly suppress upward propagation (they need to grant to a child first, which is correct).
- **The top cell** (C0) should always propagate requests upward through `sr` because there is no parent to wait for — `sr` IS the final arbiter output. But the logic incorrectly applies the same `!holdToken` guard, causing `sr` to stay idle when the top cell holds the token.

The token eventually gets passed to a child (at t=80, C0.io_uaLeft = 1, granting to C1), which would clear C0.holdToken to 0 on the **next** clock edge (t=90). But the assertion requires the propagation to happen **immediately** (combinationally), not after a cycle delay. This one-cycle bubble is a genuine design flaw.

### Proposed Fix

Modify the `xr` combinational logic in `arbitCell` (lines 83-93 of `arbit.scala`) to allow the top cell to propagate requests even when `holdToken=1`. For example, add a new condition before the existing cases:

```scala
// Combinational logic for xr
when(io.topCell && holdToken && 
     (io.urLeft === HandShakeType.request || io.urRight === HandShakeType.request)) {
    io.xr := HandShakeType.request        // Top cell always propagates requests
}.elsewhen(!holdToken && ...) {
    // existing cases...
}
```

Or more simply, modify the first condition to include the top cell:

```scala
when((!holdToken || io.topCell) && 
     (io.urLeft === HandShakeType.request || io.urRight === HandShakeType.request)) {
    io.xr := HandShakeType.request
}.elsewhen(...)
```

This ensures the top cell's `sr` output always reflects incoming requests from children, regardless of whether the top cell currently holds the token.
