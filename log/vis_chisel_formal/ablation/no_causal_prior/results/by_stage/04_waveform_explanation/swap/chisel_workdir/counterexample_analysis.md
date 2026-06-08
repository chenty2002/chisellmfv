# Counterexample Analysis Report: `swap` Benchmark

## 1. Verification Environment

- **Top Module**: `swap` (package llmverify)
- **Design Under Test**: A swap module that takes an input index `io.i` (3-bit, range 0-7), selects two indices `p` and `m` (where `p = clamp(io.i, 0, 7)` and `m = p-1` if `p>0` else `Nm1=7`), and swaps the values at registers `x(p)` and `x(m)` on each clock cycle.
- **Key Components**:
  - Register array `x[0..7]` initialized to `[0,1,2,3,4,5,6,7]`
  - Register `tmp` initialized to 0, captures `x(p)` before swap
  - Combinational wires `p` and `m` derived from `io.i`
- **Connections**: The DUT has no sub-modules beyond the `resetCounter` module inserted by the formal framework. The single input `io.i` drives the swap indices.

## 2. Violated Assertion

- **Assertion Name** (from waveform filename): `tmp_equals_x_p`
- **Full Waveform File**: `verilog/extra_bench/swap/swap.tmp_equals_x_p.fst`
- **Source File**: `swap.scala`, Line 49
- **Code Snippet**:
  ```scala
  // Safety: tmp always captures the value at x(p) before the swap.
  // This is guaranteed by the register update semantics, but we assert
  // that tmp takes the value of x(p) after the combinational path settles.
  fvAssert(tmp === x(p), "tmp_equals_x_p")
  ```
- **Property Description**: The assertion checks that register `tmp` is equal to the value stored in register array `x` at index `p` in the same clock cycle.

## 3. Waveform Information

- **Waveform File**: `verilog/extra_bench/swap/swap.tmp_equals_x_p.fst`
- **Time Range**: 0 ns → 10 ns (1 clock cycle; clock period = 10 ns)
- **Failure Point**: Time 0 ns (immediately after reset, at the first clock edge)
- **Critical Signal Values at Time 0**:

| Signal | Value (Binary) | Value (Decimal) |
|--------|----------------|-----------------|
| `swap.tmp [2:0]` | `000` | 0 |
| `swap.p [2:0]` | `100` | 4 |
| `swap.m [2:0]` | `011` | 3 |
| `swap.io_i [2:0]` | `100` | 4 |
| `swap.x_0 [2:0]` | `000` | 0 |
| `swap.x_1 [2:0]` | `001` | 1 |
| `swap.x_2 [2:0]` | `010` | 2 |
| `swap.x_3 [2:0]` | `011` | 3 |
| `swap.x_4 [2:0]` | `100` | 4 |
| `swap.x_5 [2:0]` | `101` | 5 |
| `swap.x_6 [2:0]` | `110` | 6 |
| `swap.x_7 [2:0]` | `111` | 7 |

- The assertion result signal `swap.tmp_equals_x_p` is asserted `1` at time 0 (the formal tool reports the assertion failing — the `1` value may indicate the tool's evaluation state, but the counterexample was produced because the assertion condition `tmp === x(p)` evaluates to `false`).

## 4. Root Cause Analysis

### Error Category: **Incorrect Assertion** (`assertion_error`)

### Bug Location
**File**: `swap.scala`, Line 49
```scala
fvAssert(tmp === x(p), "tmp_equals_x_p")
```

### Description of the Bug

The assertion `tmp === x(p)` is **fundamentally incorrect** — it checks a relationship that can **never hold** given the swap logic's behavior.

#### Why the Assertion Can Never Be Satisfied

The sequential logic (lines 31-35) executes on every clock cycle:

```scala
when(true.B) {
  tmp := x(p)    // tmp gets the OLD value of x(p)
  x(p) := x(m)   // x(p) gets the OLD value of x(m)
  x(m) := tmp    // x(m) gets the OLD value of x(p) (via tmp)
}
```

This is a classic swap operation using non-blocking register assignments. After each clock edge:

1. **`tmp` stores the value that was at `x(p)` *before* the swap** (the old `x(p)`)
2. **`x(p)` stores the value that was at `x(m)` *before* the swap** (due to `x(p) := x(m)`)
3. **`x(m)` stores the old value of `x(p)`** (via `x(m) := tmp`)

Therefore, in any given clock cycle, the assertion `tmp === x(p)` is checking:
- **`old_x(p) === old_x(m)`**

This would require `x(p) == x(m)` before the swap, which in turn requires `p == m`. However, the design guarantees `p != m` (line 44: `fvAssert(p =/= m, "distinct_indices")`). When `p > 0`, `m = p-1`; when `p = 0`, `m = Nm1 = 7`. So p and m are always distinct indices.

#### Evidence from Waveform (Time 0)

At time 0 (the initial state after reset):

- `tmp` = 0 (register `tmp` is initialized to `0.U` on line 20)
- `p` = 4 (since `io.i = 4`, and `io.i >= 7 ? 7 : io.i` gives 4)
- `x(p)` = `x(4)` = 4 (register array `x` is initialized to `[0,1,2,3,4,5,6,7]`)
- **`tmp` (0) ≠ `x(4)` (4)** → Assertion fails

At any subsequent cycle, the same problem persists: after the swap, `tmp` holds the old `x(p)` while `x(p)` now holds the old `x(m)`. Since `p != m`, these are different values.

### Why the Assertion Is Incorrect

The comment on line 46-48 says:
> *"tmp always captures the value at x(p) before the swap. This is guaranteed by the register update semantics, but we assert that tmp takes the value of x(p) after the combinational path settles."*

This comment is misleading. The assertion checks `tmp === x(p)` **in the same cycle**, but:

- **`tmp`** is a register that was **just updated** with the **old `x(p)`** (from before the swap)
- **`x(p)`** is a register that was **just updated** with the **old `x(m)`** (from before the swap)

The assertion `tmp === x(p)` effectively checks `old_x(p) === old_x(m)`, which is false when `p != m`.

### How to Fix

There are several options:

1. **Remove the assertion**: The property "tmp captures x(p) before the swap" is inherently guaranteed by register semantics and doesn't need formal verification.

2. **Use the `past()` operator** (if available): `fvAssert(tmp === past(x(p)))` — but this just verifies register update semantics, which is trivial.

3. **Replace with a meaningful property**: Verify that after the swap completes, the array values are correct — e.g., `x(p) === old(x(m))` and `x(m) === old(x(p))`, but these are also just register semantics.

The assertion in its current form (`tmp === x(p)`) is fundamentally unsound and cannot be satisfied under any valid input sequence.
