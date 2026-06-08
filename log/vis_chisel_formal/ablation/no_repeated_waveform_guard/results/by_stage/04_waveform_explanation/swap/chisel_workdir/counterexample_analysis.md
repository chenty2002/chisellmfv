# Counterexample Analysis Report: `swap_values_correct`

## 1. Verification Environment

- **Top Module**: `swap` (Class: `swap`, Package: `llmverify`)
- **File**: `chisel/extra_bench/swap/swap.scala`
- **Parameters**: `K = 3` (data width), `Nm1 = 7` (max index, array size = 8)
- **Key Components**:
  - `x[0..7]`: 8-element register array initialized to `[0,1,2,3,4,5,6,7]`
  - `tmp`: temp register (initialized to 0)
  - `p`, `m`: wire index selectors
  - `prev_xm`, `prev_tmp`: delay registers for swap verification
- **Swapping Logic** (every cycle): `tmp := x(p); x(p) := x(m); x(m) := tmp`
- **Index Computation**: `p = min(io_i, 7)`, `m = (p == 0) ? 7 : (p-1)`

## 2. Violated Assertion

- **Assertion Name**: `swap_values_correct`
- **Waveform Filename**: `swap.swap_values_correct.fst`
- **Source Location**: `swap.scala`, lines 76–82

```scala
// Lines 71-82
val prev_xp = RegNext(x(p))   // x(p) after swap = old x(m)
val prev_xm = RegNext(x(m))   // x(m) after swap = old tmp
val prev_tmp = RegNext(tmp)   // tmp after swap = old x(p)

val p_stable = RegNext(p) === p
val m_stable = RegNext(m) === m
val stable = p_stable && m_stable

// With stable indices, x(p) gets the old x(m) and x(m) gets the old tmp
AssertProperty(
  stable |-> Sequence(x(p) === prev_xm && x(m) === prev_tmp),
  None, None, Some("swap_values_correct")
)
```

- **Natural Language Description**: When `p` and `m` are stable (unchanged from the previous cycle), the swap operation should have completed cleanly: the value at `x(p)` should equal the previous `x(m)` (captured in `prev_xm`), and the value at `x(m)` should equal the previous `tmp` (captured in `prev_tmp`).

## 3. Waveform Information

- **Waveform File**: `verilog/extra_bench/swap/swap.swap_values_correct.fst`
- **Duration**: 1 cycle (0 ns → 10 ns)
- **Failure Point**: Time 0 ns (the initial state)

### Critical Signal Values at Time 0 ns

| Signal | Value | Meaning |
|--------|-------|---------|
| `swap.io_i [2:0]` | `000` (0) | Input index = 0 |
| `swap.p [2:0]` | `000` (0) | `p = Mux(0 >= 7, 7, 0) = 0` |
| `swap.m [2:0]` | `111` (7) | `m = Mux(0 === 0, 7, -1) = 7` |
| `swap.tmp [2:0]` | `000` (0) | Initial temp register value |
| `swap.x_0 [2:0]` | `000` (0) | Initial value of x(0) |
| `swap.x_7 [2:0]` | `111` (7) | Initial value of x(7) |
| `swap.p_stable_REG [2:0]` | `000` (0) | `RegNext(p) = 0`, equals `p` → `p_stable = true` |
| `swap.m_stable_REG [2:0]` | `111` (7) | `RegNext(m) = 7`, equals `m` → `m_stable = true` |
| `swap.prev_xm [2:0]` | `000` (0) | `RegNext(x(m)) = RegNext(x(7)) = 0` (reset value) |
| `swap.prev_tmp [2:0]` | `000` (0) | `RegNext(tmp) = 0` (reset value) |
| `swap.swap_values_correct` | `0` | Assertion FAILED |

### Assertion Evaluation at Time 0

```
stable = p_stable && m_stable = (0===0) && (7===7) = true
Antecedent (stable) is TRUE → consequent must hold.

Consequent check:
  x(p) === prev_xm  →  x(0) === prev_xm  →  0 === 0  ✓  (PASS)
  x(m) === prev_tmp →  x(7) === prev_tmp →  7 === 0  ✗  (FAIL)
```

## 4. Root Cause Analysis

### Classification: **Incorrect Assertion** (assertion_error)

### Bug Location
- **File**: `swap.scala`
- **Lines**: 76–82 (the `AssertProperty` call with `stable |-> ...`)

### Description of the Bug

The assertion `swap_values_correct` uses `stable` as its antecedent, where `stable = RegNext(p) === p && RegNext(m) === m`. At the **initial state** (time 0), both `p` and `m` are naturally "stable" because no clock cycle has elapsed to change them — `RegNext(p)` equals `p` and `RegNext(m)` equals `m` — making the antecedent `true`. However, **no swap has actually occurred yet** at this point, so the consequent expectations are invalid.

Specifically:
- `prev_tmp = RegNext(tmp)` holds its **reset value** (0) at the initial state.
- But `x(m) = x(7) = 7` (its initial value from `RegInit`).
- The assertion expects `x(m) === prev_tmp`, i.e., `7 === 0`, which is false.

The assertion was **intended** to check that after a swap has occurred under stable indices, the values end up correctly. However, it does not guard against the **initial pre-swap cycle** where no swap has happened yet. The antecedent `stable` is satisfied at time 0 without any actual swapping having taken place.

### Evidence from Waveform

1. At time 0, `swap_values_correct = 0` (assertion failed immediately in the first cycle).
2. All signals hold their initial/reset values:
   - `x = [0, 1, 2, 3, 4, 5, 6, 7]` (unmodified, no swap has executed)
   - `tmp = 0` (reset value)
   - `prev_tmp = 0` (reset value, never captured a prior `x(p)`)
   - `prev_xm = 0` (reset value, never captured a prior `x(m)`)
3. `m_stable_REG = 7` equals `m = 7`, making `m_stable = true`.
4. `p_stable_REG = 0` equals `p = 0`, making `p_stable = true`.
5. Therefore `stable = true`, triggering the consequent check.
6. The consequent fails because `x(7) = 7 ≠ prev_tmp = 0`.

### Why the Design is Not at Fault

The DUT's swapping logic is correct:
- `tmp := x(p) = x(0) = 0` (no net change to tmp)
- `x(p) := x(m) = x(7) = 7`
- `x(m) := tmp = 0`
- After one clock cycle, `x(0) = 7` and `x(7) = 0`, which is a proper swap.

The assertion simply fires **too early** (before any swap has occurred).

### Proposed Fix

The assertion should guard against the initial cycle by ensuring that `stable` has been true for at least one prior cycle before checking the swap result. This can be done by using `RegNext(stable)` (i.e., `Past(stable)`) instead of `stable` as the antecedent:

```scala
// Fix: Only check after at least one cycle of stable p,m
AssertProperty(
  RegNext(stable) |-> Sequence(x(p) === prev_xm && x(m) === prev_tmp),
  None, None, Some("swap_values_correct")
)
```

With this fix, the assertion would only trigger **after** the first clock cycle where `stable` was true (i.e., at least one swap cycle has completed), giving the DUT time to execute the swap before its correctness is checked.
