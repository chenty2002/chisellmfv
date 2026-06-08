# Counterexample Analysis Report

## 1. Verification Environment
- **Top module name**: `swap`
- **Design parameters**: K=3 (3-bit width), Nm1=7 (array indices 0-7)
- **Key components**: 
  - Register array `x[0:7]` initialized to values [0,1,2,3,4,5,6,7]
  - Temporary register `tmp` for swap operation
  - Combinational logic for indices `p` and `m`
  - Sequential swap logic on clock edge
- **Design purpose**: Implements a swap operation between array elements `x[p]` and `x[m]` where `p` is derived from input `i` and `m = p-1` (with wrap-around)

## 2. Violated Assertion
- **Full assertion name**: `x[p]_should_equal_previous_x[m]_after_swap`
- **Assertion code**: 
  ```scala
  fvAssert(x(p) === x_prev(m), "x[p] should equal previous x[m] after swap")
  ```
- **Property description**: After the swap operation, the value at index `p` should equal the previous value that was at index `m`
- **File location**: `swap.scala`, line 58

## 3. Waveform Information
- **Waveform file**: `/home/chenty/llm/TileLinkLLM/verilog/extra_bench/swap/swap.x5Bp5D_should_equal_previous_x5Bm5D_after_swap.fst`
- **Time range**: 0 ns → 10 ns (1 cycle)
- **Key time points**: 0 ns (initial state), 5 ns (after clock edge)
- **Critical signal values at failure point (5 ns)**:
  - `swap.io_i = 010` (decimal 2)
  - `swap.p = 010` (decimal 2) 
  - `swap.m = 001` (decimal 1)
  - `swap.x_2 = 010` (decimal 2)
  - `swap.x_1 = 001` (decimal 1)
  - `swap.x_prev_1 = 001` (decimal 1)
  - `swap.x_prev_2 = 010` (decimal 2)
  - `swap.tmp = 000` (decimal 0)
  - `swap.x5Bp5D_should_equal_previous_x5Bm5D_after_swap = 1` (assertion passes)

## 4. Root Cause Analysis

### Analysis of the Counterexample
The waveform shows that the assertion `x[p] === x_prev(m)` is actually **PASSING** (value = 1) throughout the simulation. However, this indicates a potential issue with the formal verification setup or assertion logic rather than a design bug.

### Key Observations
1. **Input and indices**: `io_i = 2`, so `p = 2` and `m = 1` (correct wrap-around logic)
2. **Array values**: 
   - At time 0: `x[1] = 1`, `x[2] = 2` (initial values)
   - At time 5: `x[1] = 1`, `x[2] = 2` (unchanged)
3. **Previous values**: `x_prev[1] = 1`, `x_prev[2] = 2`
4. **Assertion check**: `x[2] (2) === x_prev[1] (1)` → This should FAIL, but the assertion shows as PASSING

### The Bug: Incorrect Assertion Logic
The bug is in the **assertion itself**, not the design. The assertion `x(p) === x_prev(m)` is checking the wrong condition for a proper swap operation.

**What the assertion should check**: After a swap between indices `p` and `m`:
- `x[p]` should equal the previous value of `x[m]` 
- `x[m]` should equal the previous value of `x[p]`

**What the current assertion checks**: `x[p] === x_prev[m]` - This is correct in principle, but there's a timing issue.

### Root Cause: Timing Mismatch in Assertion
The issue is that `x_prev` is defined as `RegNext(x)`, which means `x_prev` holds the values from the **previous clock cycle**. However, the swap operation happens in the **same clock cycle** as the assertion check.

Looking at the design:
```scala
val x_prev = RegNext(x)
// Sequential logic
when(true.B) {
  tmp := x(p)
  x(p) := x(m)  
  x(m) := tmp
}
// Assertion
fvAssert(x(p) === x_prev(m), ...)
```

The problem is that at the time the assertion is evaluated:
- `x(p)` has already been updated to the new value
- `x_prev(m)` contains the value of `x[m]` from the previous cycle, not the current cycle before the swap

### Correct Fix
The assertion should use the values from **before** the swap in the current cycle, not from the previous cycle. One approach is to capture the pre-swap values in the same cycle:

```scala
val x_before_swap = Wire(Vec(Nm1 + 1, UInt(K.W)))
x_before_swap := x  // Capture current values before swap

// Sequential logic
when(true.B) {
  tmp := x(p)
  x(p) := x(m)
  x(m) := tmp
}

// Assertion using pre-swap values
fvAssert(x(p) === x_before_swap(m), "x[p] should equal previous x[m] after swap")
fvAssert(x(m) === x_before_swap(p), "x[m] should equal previous x[p] after swap")
```

### Error Type
**assertion_error** - The assertion logic is incorrect due to timing mismatch between when values are captured and when the swap operation occurs.