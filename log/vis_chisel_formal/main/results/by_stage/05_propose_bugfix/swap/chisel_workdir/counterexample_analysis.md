# Counterexample Analysis Report: `swap` — sum_invariant Failure

## 1. Verification Environment

- **Top module**: `swap` (Chisel, `swap.scala`)
- **Module parameters**: `K = 3` (3-bit values), `Nm1 = 7` (indices 0..7)
- **Key components**:
  - `x` — `Vec(8, UInt(3.W))` register array, initialized to `[0,1,2,3,4,5,6,7]`
  - `tmp` — 3-bit register, initialized to `0`
  - `p`, `m` — combinational wires computed from input `io.i`
  - `sum = x.reduce(_ + _)` — sum of all elements (mod 8)
  - `prev_sum` — register holding the previous cycle's sum
- **Purpose**: The design performs a swap of array elements `x(p)` and `x(m)` every cycle. The swap should preserve the multiset of values, hence the sum should be invariant.

## 2. Violated Assertion

- **Assertion name**: `sum_invariant` (from waveform filename `swap.sum_invariant.fst`)
- **Property**: `prev_sum === sum` — the sum of all array elements should remain unchanged after each swap. Since every cycle swaps two entries, the multiset (and thus the sum) is preserved.
- **Source location**: `swap.scala`, lines 49–50:
  ```scala
  val sum = x.reduce(_ + _)
  val prev_sum = RegNext(sum)
  fvAssert(prev_sum === sum, "sum_invariant")
  ```

## 3. Waveform Information

- **Waveform file**: `verilog/extra_bench/swap/swap.sum_invariant.fst`
- **Time range**: 0 ns → 20 ns (2 cycles)
- **Key time points**:
  - **t = 0 ns**: System initial state. `sum_invariant = 1` (passing). `x = [0,1,2,3,4,5,6,7]`, `sum = 4`, `prev_sum = 4`.
  - **t = 9 ns**: Just before the first posedge clock. `sum_invariant = 1` (still passing).
  - **t = 10 ns**: After posedge clock. **`sum_invariant = 0` (FAILURE)**. `x(6) = 0`, `x(7) = 6`, `tmp = 7`, `sum = 5`, `prev_sum = 4`.

| Signal | t=0 | t=9 | t=10 |
|--------|:---:|:---:|:----:|
| `x(0)` | 000 | 000 | 000 |
| `x(1)` | 001 | 001 | 001 |
| `x(2)` | 010 | 010 | 010 |
| `x(3)` | 011 | 011 | 011 |
| `x(4)` | 100 | 100 | 100 |
| `x(5)` | 101 | 101 | 101 |
| `x(6)` | 110 | 110 | **000** |
| `x(7)` | 111 | 111 | **110** |
| `tmp`  | 000 | 000 | **111** |
| `sum`  | 100 | 100 | **101** |
| `prev_sum` | 100 | 100 | 100 |
| `sum_invariant` | 1 | 1 | **0** |

## 4. Root Cause Analysis

### Bug Location
- **File**: `swap.scala`
- **Line 30**: `val tmp = RegInit(0.U(K.W))`
- **Bug type**: **Design bug** — `tmp` is declared as a **register** (`RegInit`) when it should be a **wire** (`Wire`).

### Description of the Bug

The swap logic in `swap.scala` (lines 41–44) is:
```scala
when(true.B) {
  tmp := x(p)
  x(p) := x(m)
  x(m) := tmp
}
```

In this context, `p = io.i = 7` and `m = p - 1 = 6`. The intended swap is:
1. Save `x(7) = 7` into `tmp`
2. Copy `x(6) = 6` into `x(7)` 
3. Copy the saved value (= 7) into `x(6)`

Since `tmp` is a **register**, all three assignments occur **simultaneously** at the clock edge. In Verilog/Chisel semantics, a register read always returns the **old** (pre-update) value. Therefore:

- `tmp := x(p)` writes `7` to the `tmp` register (for the *next* cycle)
- `x(m) := tmp` reads the **old** `tmp` value, which is `0` (from reset initialization), and writes `0` into `x(6)`

**Result**: After the clock edge, `x(6) = 0` instead of the expected `7`. The multiset is corrupted: value `7` is duplicated (in `tmp` and nowhere in the array), and value `0` has a duplicate, while value `6` is missing from the array.

### Evidence from Waveform

| Signal | t=9 (before clock) | t=10 (after clock) | Expected after clock |
|--------|:------------------:|:------------------:|:--------------------:|
| `x(6)` | 6 (110) | **0 (000)** — BUG! | 7 (111) |
| `x(7)` | 7 (111) | 6 (110) — correct | 6 (110) |
| `tmp`  | 0 (000) | 7 (111) | 7 (111) |

The wavefrom cleanly shows:
- `x(7)` correctly got `x(6)`'s old value (6)
- `tmp` correctly captured `x(7)`'s old value (7), but this takes effect for the *next* cycle
- `x(6)` got the **old** `tmp` value (0) instead of the newly-captured value (7)

### Why the Assertion Fails

Before the swap: `sum = 0+1+2+3+4+5+6+7 = 28 mod 8 = 4`
After the swap (with bug): `sum = 0+1+2+3+4+5+0+6 = 21 mod 8 = 5`
`prev_sum = 4 ≠ 5 = sum` → **Assertion fails**

### Fix

Change `tmp` from a register to a wire on line 30 of `swap.scala`:

```scala
// Before (buggy):
val tmp = RegInit(0.U(K.W))

// After (fixed):
val tmp = Wire(UInt(K.W))
```

This makes `tmp` a combinational signal that immediately reflects `x(p)`, so when `x(m) := tmp` is evaluated, it correctly receives the saved value of `x(p)` rather than a stale register value.

### Error Classification

**Category: Bug in the Original Design (DUT bug)** — The `tmp` signal is incorrectly declared as a register (`RegInit`) instead of a wire (`Wire`), causing the swap operation to corrupt the array.
