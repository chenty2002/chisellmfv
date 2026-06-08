# Counterexample Analysis Report: `swap.sum_preserved_under_swap`

## 1. Verification Environment

- **Top Module**: `swap` (from `swap.scala`)
- **Key Components**:
  - **Register array** `x[0:7]` — initialized to `[0,1,2,3,4,5,6,7]`
  - **Register** `tmp` — initialized to `0`
  - **Wire** `p` — equals `Mux(io_i >= 7, 7, io_i)`
  - **Wire** `m` — equals `Mux(p === 0, 7, p-1)`
- **Design Description**: The `swap` module maintains an array `x[0:7]` and performs a swap of `x(p)` and `x(m)` every cycle. The swap is implemented using a temporary register `tmp`.
- **Stimulus**: Input `io_i` is driven to `4` throughout the trace.

## 2. Violated Assertion

- **Assertion Name**: `sum_preserved_under_swap`
- **Full Path**: `swap.sum_preserved_under_swap`
- **Code Snippet** (from `swap.scala` lines 69–72):
  ```scala
  val sum = x.reduce(_ + _)
  val prev_sum = RegNext(sum)
  when (!first_cycle) {
    fvAssert(sum === prev_sum, "sum_preserved_under_swap")
  }
  ```
- **Natural Language Description**: The sum of all elements in the `x` array must remain unchanged from one cycle to the next. Swapping two elements should preserve the multiset of values, hence the sum.
- **File Location**: `swap.scala`, line 72

## 3. Waveform Information

- **Waveform File**: `verilog/extra_bench/swap/swap.sum_preserved_under_swap.fst`
- **Time Range**: 0 ns → 20 ns (2 clock cycles)
- **Key Time Points**:

### Time 0 ns (Initial state after reset)
| Signal | Value | Meaning |
|--------|-------|---------|
| `swap.x_3 [2:0]` | `011` (3) | x(3) = 3 |
| `swap.x_4 [2:0]` | `100` (4) | x(4) = 4 |
| `swap.tmp [2:0]` | `000` (0) | tmp = 0 (register initialized) |
| `swap._sum_T_12 [2:0]` | `100` (4) | sum = 0+1+2+3+4+5+6+7 = 28 ≡ 4 (mod 8) |
| `swap.prev_sum [2:0]` | `100` (4) | previous sum = 4 |
| `swap.p [2:0]` | `100` (4) | p = io_i = 4 |
| `swap.m [2:0]` | `011` (3) | m = p-1 = 3 |
| `swap.sum_preserved_under_swap` | `1` | Assertion passes |

### Time 10 ns (After first posedge clock, after swap)
| Signal | Value | Meaning |
|--------|-------|---------|
| `swap.x_3 [2:0]` | `000` (0) | x(3) = **0** (WRONG — should be 4) |
| `swap.x_4 [2:0]` | `011` (3) | x(4) = 3 (correct — got old x(3)) |
| `swap.tmp [2:0]` | `100` (4) | tmp = 4 (got old x(4), but too late) |
| `swap._sum_T_12 [2:0]` | `000` (0) | sum = 0+1+2+0+3+5+6+7 = 24 ≡ 0 (mod 8) |
| `swap.prev_sum [2:0]` | `100` (4) | previous sum = 4 (unchanged) |
| `swap.sum_preserved_under_swap` | `0` | **ASSERTION FAILS** — sum changed! |

## 4. Root Cause Analysis

### Classification: **Bug in the Original Design (DUT Bug)**

### Buggy Code Location
- **File**: `swap.scala`, line 21
- **Code**: `val tmp = RegInit(0.U(K.W))`
- The temporary variable `tmp` is declared as a **register** (`RegInit`) instead of a **wire** (`Wire`).

### Description of the Bug

The swap logic on lines 33–35 reads as follows:

```scala
when(true.B) {
  tmp := x(p)    // tmp gets the OLD value of x(p)
  x(p) := x(m)   // x(p) gets the OLD value of x(m)
  x(m) := tmp    // x(m) gets the OLD value of tmp
}
```

In Chisel (and hardware description languages generally), all assignments in a `when` block (or `always @(posedge clock)` block in Verilog) are evaluated from the **previous** state and committed **simultaneously**. This means:

1. `tmp := x(p)` reads the old `x(p)` **before** this cycle's updates
2. `x(p) := x(m)` reads the old `x(m)` **before** this cycle's updates
3. `x(m) := tmp` reads the **old** `tmp` value **before** this cycle's updates

On the **first cycle after reset**:
- Old `tmp` = 0 (its initialized value)
- Old `x(4)` = 4, old `x(3)` = 3
- New `tmp` = 4 (old x(4)) — correctly captures x(p)
- New `x(4)` = 3 (old x(3)) — correctly receives x(m)
- New `x(3)` = 0 (old tmp) — **INCORRECTLY receives 0 instead of 4!**

### Why the Bug Causes the Assertion Failure

The intended swap (`x(3)` ↔ `x(4)`) should transform:
```
x = [0, 1, 2, 3, 4, 5, 6, 7]  →  [0, 1, 2, 4, 3, 5, 6, 7]
```

But the actual buggy swap produces:
```
x = [0, 1, 2, 0, 3, 5, 6, 7]
```

The value `4` is lost in `tmp` and never written into the array, while `x(3)` is overwritten with `0`.

**Sum before**: 0+1+2+3+4+5+6+7 = **28**
**Sum after**: 0+1+2+0+3+5+6+7 = **24**

Since the sum changes (even when computed modulo 8: 4 → 0), the assertion `sum === prev_sum` correctly fails, detecting the data corruption.

### The Root Cause Issue: `tmp` Should Be a Wire

The swap operation requires a **combinational** temporary to hold the value of `x(p)` while `x(p)` and `x(m)` are being updated in the same cycle. Using a register causes the temporary to be "one cycle behind" — the value stored in `tmp` in cycle N is the old `x(p)` from cycle N-1, not the current one.

**Fix**: Change line 21 from:
```scala
val tmp = RegInit(0.U(K.W))
```
to:
```scala
val tmp = Wire(UInt(K.W))
```

With a `Wire`, the combinational read of `x(p)` is captured immediately, and both `x(p)` and `x(m)` can be updated correctly in the same cycle.
