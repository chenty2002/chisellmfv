# Counterexample Analysis Report: `swap.multiset_preserved`

## 1. Verification Environment

- **Top Module**: `swap` (class `swap` in `swap.scala`)
- **Key Components**:
  - `x[0..7]`: Register array of 8 unsigned integers (3-bit), initialized to `[0,1,2,3,4,5,6,7]`
  - `tmp`: 3-bit register, initialized to `0`
  - `p`: Input-derived pointer (`p = min(io.i, Nm1)`)
  - `m`: p-1 (or Nm1 if p=0)
  - Combinational swap logic: `p = Mux(io.i >= Nm1.U, Nm1.U, io.i)` and `m = Mux(p === 0.U, Nm1.U, (p - 1.U))`
- **Verification Setup**: The module is clocked with a single input `io.i`, and the swap operation is triggered every cycle (via `when(true.B)`).

## 2. Violated Assertion

- **Assertion Name**: `multiset_preserved` (from waveform filename: `swap.multiset_preserved.fst`)
- **Code Snippet** (lines 93-96 of `swap.scala`):

```scala
val x_xor = x.reduce(_ ^ _)
val prev_x_xor = RegNext(x_xor)
AssertProperty(x_xor === prev_x_xor, None, None, Some("multiset_preserved"))
```

- **Property**: The XOR of all elements in register array `x` must remain invariant across cycles. Since any permutation (swap) preserves the multiset of values, the XOR should never change.
- **File Location**: `swap.scala`, line 96.

## 3. Waveform Information

- **Waveform File**: `verilog/extra_bench/swap/swap.multiset_preserved.fst`
- **Time Range**: 0 ns → 20 ns (2 cycles)
- **Key Time Points**:
  - **t=0 ns (initial/reset state)**:
    - `io.i` = 7, `p` = 7, `m` = 6
    - `x` = `[0, 1, 2, 3, 4, 5, 6, 7]`
    - `tmp` = 0
    - `x_xor` = 0, `prev_x_xor` = 0
    - `multiset_preserved` = 1 (passing)
  - **t=10 ns (first posedge clock — swap executes)**:
    - `x(6)` = 0 (was 6), `x(7)` = 6 (was 7)
    - `tmp` = 7 (captured old `x(7)`)
    - `x` = `[0, 1, 2, 3, 4, 5, 0, 6]`
    - `x_xor` = 7 (0d111), `prev_x_xor` = 0
    - `multiset_preserved` = 0 (**failing** — XOR changed from 0 to 7)

## 4. Root Cause Analysis

### Buggy Code Location

**File**: `swap.scala`, line 29:

```scala
val tmp = RegInit(0.U(K.W))
```

And the swap logic on lines 49-52:

```scala
when(true.B) {
    tmp := x(p)
    x(p) := x(m)
    x(m) := tmp
}
```

### Description of the Bug

The variable `tmp` is declared as a **`Reg`** (sequential register), when it should be a **`Wire`** (combinational signal). In Chisel (and hardware in general), all register assignments in a `when` block read their **old** values before any are updated. This means that the statement `x(m) := tmp` reads the **old value of `tmp` from the previous cycle**, not the newly assigned `x(p)` value from the current cycle.

### Evidence from Waveform

| Time | `tmp` | `x(6)` | `x(7)` | Expected correct `x(6)` |
|------|-------|--------|--------|------------------------|
| 0    | 0     | 6      | 7      | —                      |
| 10   | 7     | **0**  | 6      | **7**                  |

- At t=0: `tmp` = 0 (reset value)
- At t=10 (after clock edge with `p=7, m=6`):
  1. `tmp := x(7)` → `tmp` = 7 ✓
  2. `x(7) := x(6)` → `x(7)` = 6 ✓
  3. `x(6) := tmp` → `x(6)` = **0** ✗ (reads OLD `tmp` = 0 from reset, not the newly assigned 7)

### Why This Causes the Assertion to Fail

After the incorrect swap:
- New `x` = `[0, 1, 2, 3, 4, 5, 0, 6]`
- `x_xor` = `0 ⊕ 1 ⊕ 2 ⊕ 3 ⊕ 4 ⊕ 5 ⊕ 0 ⊕ 6` = **7**
- `prev_x_xor` = **0** (old XOR before the swap)

Since `x_xor (7) ≠ prev_x_xor (0)`, the assertion `multiset_preserved` fails.

Note: If `tmp` were a `Wire`, the assignment `x(m) := tmp` would read the newly driven value (the old `x(p)`) in the same cycle, and the swap would complete correctly in one cycle (x(6) would become 7 instead of 0, preserving the XOR).

### Error Classification

**Type**: `dut_bug` — The design has a genuine logic error. The temporary storage for the swap operation is incorrectly implemented as a sequential register (`Reg`) instead of a combinational wire (`Wire`), causing the swap to corrupt data for one cycle.

### Fix

Change `tmp` from a `Reg` to a `Wire`:

```scala
// Before (buggy):
val tmp = RegInit(0.U(K.W))

// After (correct):
val tmp = Wire(UInt(K.W))
```

Then the swap logic becomes properly combinational within the `when` block, reading the newly assigned `tmp` value in `x(m) := tmp`.
