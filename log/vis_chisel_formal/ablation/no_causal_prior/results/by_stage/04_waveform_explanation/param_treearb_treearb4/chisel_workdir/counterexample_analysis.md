# Counterexample Analysis Report

## 1. Verification Environment

| Property | Value |
|----------|-------|
| **Top Module** | `TreeArb4` (chisel/extra_bench/param_treearb_treearb4/treearb4.scala) |
| **Structure** | 2-level tree arbiter: 4 `Proc` modules → 2 `ArbitCell` modules (C0_0, C0_1) → 1 root `ArbitCell` (C1_0) |
| **Key Components** | `Proc` (processor that cycles through idle→request→lock→release), `ArbitCell` (arbiter cell with token-holding logic) |
| **Design Purpose** | Token-ring arbiter with mutual exclusion — at most one processor holds the lock at any time |

## 2. Violated Assertion

**Assertion Name**: `root_no_request`

**Code Snippet** (treearb4.scala, lines 264-271):
```scala
// --- Safety: Root cell never outputs request or release (since it has no parent) ---
// Since C1_0 is the top cell (topCell=1), it should never report Phase.request
// (it always holds the token initially) and Phase.release is meaningless without a parent.
fvAssert(
    io.xr_root =/= Phase.request,
    "root_no_request"
)
```

**Property Description**: The root cell (C1_0) is the top of the arbiter tree and has no parent (its `io.xa` is tied to 0). Therefore, it should never output `Phase.request` (binary `01`) on its `io.xr` output, because there is no parent to grant it the token.

**File Location**: `treearb4.scala`, line 268

## 3. Waveform Information

| Property | Value |
|----------|-------|
| **Waveform File** | `verilog/extra_bench/param_treearb_treearb4/TreeArb4.root_no_request.fst` |
| **Time Range** | 0 ns → 30 ns (3 cycles) |
| **Violation Time** | 20 ns (second clock cycle) |
| **Key Time Points** | 0 ns (initial), 10 ns (cycle 1), 20 ns (cycle 2, violation) |

### Signal Values at Key Time Points

**At 10 ns (cycle 1)**:
| Signal | Value |
|--------|-------|
| `C1_0.holdToken` | 1 (holds token) |
| `C1_0.io.urLeft` | 01 (Phase.request, from C0_0) |
| `C1_0.io.urRight` | 01 (Phase.request, from C0_1) |
| `io.xr_root` | 00 (Phase.idle) — OK |
| `C1_0.io.uaLeft` | 1 (grants token to C0_0) |
| `C0_0.io.xa` | 1 (receives token) |

All 4 processors are in Phase.request.

**At 20 ns (cycle 2, violation point)**:
| Signal | Value |
|--------|-------|
| **`io.xr_root`** | **01 (Phase.request) — ASSERTION VIOLATED** |
| `C1_0.holdToken` | 0 (token granted away) |
| `C1_0.io.urLeft` | 00 (Phase.idle — C0_0 got token, stopped requesting) |
| `C1_0.io.urRight` | 01 (Phase.request — C0_1 still requesting) |
| `C1_0.io.xa` | 0 (no parent to grant token) |
| `C1_0.io.topCell` | 1 |
| `C0_0.io.xr` | 00 (Phase.idle) |
| `C0_1.io.xr` | 01 (Phase.request) |
| `C0_0.holdToken` | 1 (C0_0 now has token) |
| `C0_1.holdToken` | 0 |

## 4. Root Cause Analysis

### Bug Classification: **DUT Bug**

The bug is in the `io.xr` output computation in the `ArbitCell` module.

### Buggy Code Location

**File**: `treearb4.scala`, lines 157-160

```scala
io.xr := Mux(!holdToken && requesting, Phase.request,          // <-- BUG: line 157
         Mux(childOwns, Phase.lock,                              // line 158
         Mux(holdToken && !io.topCell && (mustGiveParent || !requesting), Phase.release,  // line 159
             Phase.idle)))                                       // line 160
```

### Bug Description

The first condition of the MUX tree (`!holdToken && requesting`) does **not** account for the case where the cell is the top cell (has no parent). When:
- `holdToken = 0` (the token has been granted to a child), and
- `requesting = 1` (some child is still requesting)

The cell unconditionally outputs `Phase.request`. For a non-root cell, this is correct — it propagates the request upward to its parent. However, for the root cell (`topCell=1`), there is no parent to grant the token back (`io.xa` is tied to 0), so outputting `Phase.request` is meaningless and violates the assertion.

Note that the **third** condition (Phase.release) already has the `!io.topCell` guard, but the **first** condition (Phase.request) does not — this is an inconsistency in the design.

### Detailed Failure Trace

1. **Time 0 (initial)**: All processors in `Phase.idle`. Root cell C1_0 holds the token (`holdToken=1`). All outputs are `Phase.idle`.

2. **Time 10 (cycle 1)**: All 4 processors transition to `Phase.request`. Both C0_0 and C0_1 propagate this request upward (`io.xr = Phase.request`). C1_0 sees both children requesting while holding the token, so it grants the token to the left child (C0_0) via `io.uaLeft=1` (since `prevLeft=0`, it's left's turn). The token grant is registered at the next clock edge.

3. **Time 20 (cycle 2)**: The token is now with C0_0 (`holdToken=1`). C0_0 stops requesting (`io.xr = Phase.idle`). C0_1 is still in `Phase.request` because it didn't get the token (`io.xr = Phase.request`). C1_0 now has `holdToken=0` and `requesting=1` (right child still requesting). The condition `!holdToken && requesting` evaluates to true, and `C1_0.io.xr` becomes `Phase.request` — **assertion violated**.

### Why This Happens

The root cell can only get the token back when a child releases. When C0_0 finishes its work, P0 transitions to `Phase.lock` → `Phase.release` → `Phase.idle`. Then C0_0 will see `Phase.release` and eventually output `Phase.release` upward. When C1_0 sees `C0_0.io.xr === Phase.release`, its `holdToken` goes back to 1. However, during the intermediate period (between granting the token and receiving the release), C1_0 is in a state where it incorrectly outputs `Phase.request`.

### Fix

Add `!io.topCell` to the first MUX condition:

```scala
io.xr := Mux(!holdToken && requesting && !io.topCell, Phase.request,
         Mux(childOwns, Phase.lock,
         Mux(holdToken && !io.topCell && (mustGiveParent || !requesting), Phase.release,
             Phase.idle)))
```

This ensures the root cell never outputs `Phase.request`, defaulting to `Phase.idle` (or `Phase.lock` if a descendant holds the lock) when it doesn't have the token.
