# Counterexample Analysis Report: `param_treearb_treearb4`

## 1. Verification Environment

- **Top Module**: `TreeArb4` (in `treearb4.scala`)
- **Design Structure**:
  - Four `Proc` modules (P0-P3) representing processors cycling through states: idle → request → lock → release
  - Two level-0 `ArbitCell` modules (C0_0 for P0/P1, C0_1 for P2/P3) that arbitrate between pairs of processors
  - One level-1 `ArbitCell` (C1_0) that arbitrates between the two level-0 cells
  - The root cell (C1_0) initially holds the token and passes it down through the tree
- **Key Connections**:
  - Processors request the lock via `io.req`, receive acks via `io.ack`
  - ArbitCells use a token-passing scheme with fairness (prevLeft register)
  - The formal verification environment resets all registers and runs cycles

## 2. Violated Assertion

- **Full Assertion Name**: `mutex_lock` (derived from waveform filename `TreeArb4.mutex_lock.fst`)
- **Code Snippet** (treearb4.scala, lines 222-225):
  ```scala
  // === SAFETY: Mutual exclusion on lock state ===
  // At most one processor may hold the lock (own the token) at any time.
  // This is the fundamental arbiter invariant.
  assertOneHot(Cat(p3_lock, p2_lock, p1_lock, p0_lock), "mutex_lock")
  ```
- **Natural Language Description**: The assertion checks that **exactly one** of the four processors is in the `Phase.lock` state at any time.
- **File Location**: `treearb4.scala`, line 225

## 3. Waveform Information

- **Waveform File**: `verilog/extra_bench/param_treearb_treearb4/TreeArb4.mutex_lock.fst`
- **Time Range**: 0 ns → 10 ns (1 clock cycle)
- **Key Time Points**:
  - **Time 0 ns** (reset): All signals are evaluated
- **Critical Signal Values at Failure Point (time 0 ns)**:
  | Signal | Value | Meaning |
  |--------|-------|---------|
  | `TreeArb4.p0_lock` | 0 | P0 is NOT in lock state |
  | `TreeArb4.p1_lock` | 0 | P1 is NOT in lock state |
  | `TreeArb4.p2_lock` | 0 | P2 is NOT in lock state |
  | `TreeArb4.p3_lock` | 0 | P3 is NOT in lock state |
  | `Cat(p3_lock, p2_lock, p1_lock, p0_lock)` | 4'b0000 | Zero hot bits |
  | `TreeArb4.mutex_lock` (assertion output) | 1 | **Assertion FAILED** (failing high) |

## 4. Root Cause Analysis

### Bug Category: **Incorrect Assertion** (`assertion_error`)

### Root Cause
The assertion on line 225 uses `assertOneHot`, which verifies that **exactly one** of the bits in the `Cat` vector is high. However, the property described in the comment (line 223) and the correct arbiter invariant is **"at most one processor may hold the lock"** — which permits the state where **zero** processors hold the lock.

### Why This Fails
1. At time 0 (reset), all processor modules start in the `Phase.idle` state (`reqReg` is initialized to `Phase.idle` on line 28).
2. Since `Phase.lock` is a distinct value from `Phase.idle`, all four `p*_lock` signals evaluate to `false` (0).
3. This makes `Cat(p3_lock, p2_lock, p1_lock, p0_lock) = 4'b0000`, which has **0 hot bits**.
4. The `assertOneHot` primitive requires **exactly 1 hot bit**, causing the assertion to fail.

### Evidence from Source Code Comparison
- **Line 225 (BUGGY)**: `assertOneHot(...)` — requires exactly 1 hot bit
- **Line 230 (CORRECT)**: `assertOneHot0(Cat(ack3, ack2, ack1, ack0), "mutex_ack")` — correctly uses `assertOneHot0` for a nearly identical mutual-exclusion property on ack signals
- **Line 223 (COMMENT)**: "At most one processor may hold the lock" — explicitly states the correct property

### Fix
Change line 225 from:
```scala
assertOneHot(Cat(p3_lock, p2_lock, p1_lock, p0_lock), "mutex_lock")
```
to:
```scala
assertOneHot0(Cat(p3_lock, p2_lock, p1_lock, p0_lock), "mutex_lock")
```

This change matches:
1. The comment on line 223 ("at most one")
2. The sibling assertion on line 230 which correctly uses `assertOneHot0`
3. The fundamental arbiter invariant: no processor may hold the lock when the token hasn't been granted yet, or when all processors are in idle/release states
