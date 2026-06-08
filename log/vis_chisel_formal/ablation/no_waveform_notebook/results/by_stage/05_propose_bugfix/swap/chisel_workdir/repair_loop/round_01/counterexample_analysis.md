# Counterexample Analysis: swap.swap_p_gets_old_m

## 1. Verification Environment

- **Top Module**: `swap` (Chisel module with `Formal` mixin)
- **Design Under Test**: A swap unit with an 8-element register array `x[0:7]`, initialized to `[0,1,2,3,4,5,6,7]`. On every clock cycle, two elements `x(p)` and `x(m)` are swapped, where:
  - `p = Mux(io_i >= 7, 7, io_i)` — clamped input index
  - `m = Mux(p === 0, 7, p-1)` — adjacent index (or wraps around at 0)
- **Key components**: Register array `x`, temporary register `tmp`, combinational `p`/`m` wires, delay registers `prev_x`, `prev_p`, `prev_m` (via `RegNext`)

## 2. Violated Assertion

- **Full Assertion Name**: `swap_p_gets_old_m`
- **Waveform File**: `swap.swap_p_gets_old_m.fst`
- **Code location**: `swap.scala`, line 57

**Chisel Source** (swap.scala:54-58):
```scala
// Property 2: Swap correctness — after the swap, x(prev_p) equals the old x(prev_m)
fvAssert(x(prev_p) === prev_x(prev_m), "swap_p_gets_old_m")
```

**Generated Verilog** (swap.sv):
```verilog
swap_p_gets_old_m:
    assert property (@(posedge clock) disable iff (~hasBeenReset)
                     _GEN[prev_p] == _GEN_0[prev_m]);
```

- **Natural Language Description**: At every positive clock edge (after reset), the current value of `x` at index `prev_p` (the previous cycle's `p`) must equal the previous cycle's value of `x` at index `prev_m` (the previous cycle's `m`). This asserts that the swap operation correctly moved the old `x(m)` value to position `p`.

## 3. Waveform Information

- **Waveform File**: `verilog/extra_bench/swap/swap.swap_p_gets_old_m.fst`
- **Time Range**: 0 ns → 10 ns (1 cycle)
- **Key Time Point**: Time 0 ns (initial state before first posedge clock)

**Critical Signal Values at Time 0:**

| Signal | Value (decimal) | Value (binary) |
|--------|-----------------|-----------------|
| `io_i` | 0 | `3'b000` |
| `p` | 0 | `3'b000` |
| `m` | 7 | `3'b111` |
| `prev_p` | **2** | `3'b010` |
| `prev_m` | **0** | `3'b000` |
| `x[0..7]` | [0,1,2,3,4,5,6,7] | — |
| `prev_x[0..7]` | [0,1,2,3,4,5,6,7] | — |
| `hasBeenReset` | 1 | — |
| `clock` | 1 (at posedge) | — |
| `reset` | 0 | — |

**Assertion Evaluation:**
- `_GEN[prev_p]` = `x[2]` = **2**
- `_GEN_0[prev_m]` = `prev_x[0]` = **0**
- **2 ≠ 0** → assertion FAILS

## 4. Root Cause Analysis

### Bug Type: **Incorrect Assertion (assertion_error)**

### Buggy Code Location
- **File**: `swap.scala`
- **Lines**: 54-58
- **Code**:
  ```scala
  val prev_x = RegNext(x)
  val prev_p = RegNext(p)
  val prev_m = RegNext(m)
  
  // Property 2: Swap correctness
  fvAssert(x(prev_p) === prev_x(prev_m), "swap_p_gets_old_m")
  ```

### Description of the Bug

The assertion `swap_p_gets_old_m` checks the swap correctness property: after swapping `x(p)` and `x(m)`, the value at position `p` should be the old value from position `m`. The assertion uses `prev_p` and `prev_m` (delayed copies of `p` and `m` from the previous cycle) to refer to the positions involved in the previous cycle's swap.

**The problem**: The assertion fires on the **very first cycle after reset**, but **no swap has occurred yet**. The registers `prev_p` and `prev_m` hold either:
1. Random initial values (from Verilog `initial` block randomization), or
2. Values captured during the reset cycle (when `x` was initialized but no swap was performed)

In this counterexample:
- `prev_p = 2` and `prev_m = 0` (random initial values)
- `x = [0,1,2,3,4,5,6,7]` and `prev_x = [0,1,2,3,4,5,6,7]` (identical because no swap has occurred)
- The assertion checks `x(2) == prev_x(0)` → `2 == 0` → **false**

**Crucially**, the pair `prev_p=2` and `prev_m=0` is **impossible** in normal operation given the design's logic:
- `m = Mux(p === 0, 7, p-1)` 
- When `p=2`, `m` must be `1`, never `0`
- This inconsistency confirms that `prev_p` and `prev_m` are carrying random/uninitialized values, not values from a genuine swap cycle.

### Why This Is an Assertion Bug (Not a Design Bug)

The **underlying swap logic is correct**. If the assertion were checked starting from the **second** cycle after reset (after at least one swap has occurred), it would pass:
- Cycle 1 (reset): `x = [0,1,2,3,4,5,6,7]`, `prev_p = p_cycle1`, `prev_m = m_cycle1`
- Cycle 2 (first swap): `x` is swapped using `p_cycle2`/`m_cycle2`, `prev_p = p_cycle2`, `prev_m = m_cycle2`
- Assertion checks `x(p_cycle1) == prev_x(m_cycle1)` — this correctly verifies the swap from cycle 1

### Evidence Summary

1. **Waveform signals** show `x == prev_x` (both are `[0,1,2,3,4,5,6,7]`), confirming no swap has occurred
2. **prev_p = 2, prev_m = 0** is an impossible (p, m) pair — the design's logic cannot produce `m=0` when `p=2`
3. The assertion fires with `hasBeenReset = 1` on the **first** active cycle, before any swap has been performed
4. The swap logic itself (`tmp := x(p); x(p) := x(m); x(m) := tmp`) is correct when actually executed

### Suggested Fix

The assertion needs a guard to skip the first cycle after reset. In Chisel, this can be done by adding an initial-pass cycle indicator:

```scala
val first_cycle = RegInit(true.B)
first_cycle := false.B

// Only check swap assertions after the first cycle
when (!first_cycle) {
  fvAssert(x(prev_p) === prev_x(prev_m), "swap_p_gets_old_m")
  fvAssert(x(prev_m) === prev_x(prev_p), "swap_m_gets_old_p")
}
```

Alternatively, the `prev_p` and `prev_m` registers could be initialized to a value that makes the assertion vacuously true (e.g., `prev_p === prev_m` and `x(prev_p) === prev_x(prev_m)` trivially), but the guard approach is cleaner.
