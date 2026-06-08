# Counterexample Analysis Report: rotate_rotate4

## 1. Verification Environment

- **Top module**: `rotate` (from `llmverify` package)
- **Generated Verilog** from Chisel code: `rotate4.scala`
- **Key components**:
  - `inr` (UInt(4.W)): Input register, captures `io.din` on each clock edge
  - `dout` (UInt(4.W)): Output register, stores barrel-shifted result
  - `prev_inr` (UInt(4.W)): Pipeline snapshot register, tracks previous-cycle `inr`
  - Combinational barrel shifter: `tmp1` (rotate-right by 1 if `io.amount(0)=1`), `tmp2` (rotate-right by 2 if `io.amount(1)=1`)
- **Clock**: Rising edge at times 0, 10, 20 ns
- **Reset**: Deasserted after time 0

## 2. Violated Assertion

- **Assertion name**: `dout_equals_inr_rotated_by_amount` (from waveform filename: `rotate.dout_equals_inr_rotated_by_amount.fst`)
- **File location**: `rotate4.scala`, lines 46-52
- **Code snippet**:
  ```scala
  val expected = MuxLookup(io.amount, 0.U(4.W))(Seq(
      0.U -> prev_inr,
      1.U -> Cat(prev_inr(0), prev_inr(3, 1)),
      2.U -> Cat(prev_inr(1, 0), prev_inr(3, 2)),
      3.U -> Cat(prev_inr(2, 0), prev_inr(3))
  ))
  fvAssert(dout === expected, "dout_equals_inr_rotated_by_amount")
  ```
- **Property description**: The assertion checks that `dout` (the output register) equals `prev_inr` (the previous cycle's `inr` value) rotated right by `io.amount`. This verifies the barrel shifter logic is correct across the pipeline.

## 3. Waveform Information

- **Waveform file**: `verilog/extra_bench/rotate_rotate4/rotate.dout_equals_inr_rotated_by_amount.fst`
- **Time range**: 0 ns → 30 ns (3 clock cycles)
- **Assertion failure time**: **20 ns** (the assertion signal transitions from 1→0 at time 20)

### Key signal values at critical time points:

| Signal | t=10 (posedge) | t=19 (before failure) | t=20 (posedge + failure) | t=21 |
|--------|---------------|----------------------|------------------------|------|
| `clock` | 1 (rising) | 0 | 1 (rising) | 0 |
| `io.amount [1:0]` | 11 | **11** | **01** (transitions!) | 01 |
| `io.din [3:0]` | 0110 | 0110 | 0110 | 0110 |
| `inr [3:0]` | 0110 (captures din) | 0110 | 0110 | 0110 |
| `prev_inr [3:0]` | 0000 | 0000 | **0110** (updates) | 0110 |
| `dout [3:0]` | 0000 | 0000 | **1100** (updates) | 1100 |
| `assertion` | 1 | 1 | **0** (FAIL) | 0 |

## 4. Root Cause Analysis

### Root Cause Category: **Setup Error (Test Harness)**

### Detailed Explanation

The counterexample demonstrates a **race condition caused by the input `io.amount` changing at exactly the clock edge** (time 20 ns). This creates a mismatch between the value of `io.amount` used by the DUT's combinational logic to compute `dout` and the value used by the assertion to compute the expected result.

#### Pipeline Timing Analysis

The design implements a 1-cycle pipeline:

1. **Cycle 1 (t=0 to t=10)**: After reset, `inr=0`, `dout=0`, `prev_inr=0`. Inputs: `io.amount=11`, `io.din=0110`.
2. **t=10 (Rising edge)**:
   - `inr` captures `io.din = 0110`
   - `dout` captures `tmp2` (computed with old `inr=0`, `io.amount=11`) → 0000
   - `prev_inr` captures old `inr = 0000`
   - After edge: `inr=0110, dout=0000, prev_inr=0000`, assertion holds ✓
3. **Cycle 2 (t=10 to t=20)**: `inr=0110`, `dout=0000`, `prev_inr=0000`. `io.amount=11`, `io.din=0110`.
4. **t=20 (Rising edge — FAILURE)**:
   - **`io.amount` transitions from 11→01 at exactly time 20**
   - **`dout` captures `tmp2`** computed with `inr=0110` and the **old** `io.amount=11`: rotate-right-3 of `0110` = `1100` ✓
   - **`prev_inr`** captures old `inr = 0110` ✓
   - **Assertion evaluates** `expected` using the **new** `io.amount=01`: rotate-right-1 of `prev_inr=0110` = `0011`
   - **Mismatch**: `dout=1100` ≠ `expected=0011` → **ASSERTION FAILS** ✗

#### Why This Is a Setup Error

The DUT's combinational barrel shifter correctly computes `dout` using `io.amount` as it existed **before** the clock edge (value `11`). The assertion, however, reads the **post-edge** value of `io.amount` (value `01`) for the `MuxLookup`, creating an inconsistency.

In real hardware:
- Inputs are expected to be stable **around** the clock edge (setup/hold time constraints).
- The formal test harness should constrain that inputs do not change at clock edges.

The bug is not in the DUT logic — the barrel shifter correctly rotates `0110` right by 3 to produce `1100`. The bug is not in the assertion logic — the `expected` computation is correct given the `io.amount` it reads. The bug is that the **test harness allows `io.amount` to change at the clock edge**, creating a race condition between the DUT's sequential capture and the assertion's combinational read.

#### Evidence Summary

| Evidence | Source | Implication |
|----------|--------|-------------|
| `io.amount` transitions 11→01 at t=20 | Waveform transition finder | Input changes exactly at clock edge |
| `dout=1100` = rotate-right-3 of `inr=0110` | Waveform at t=20 | DUT used `io.amount=11` (pre-edge value) |
| `expected` = rotate-right-1 of `prev_inr=0110` = `0011` | Source code analysis | Assertion uses `io.amount=01` (post-edge value) |
| `dout_equals_inr_rotated_by_amount` transitions 1→0 at t=20 | Waveform transition finder | Assertion fails at the exact moment of simultaneous input change and clock edge |

### Recommended Fix

Add an input stability constraint to the test harness to prevent `io.amount` (and ideally `io.din` too) from changing at clock edges. For example, in the formal verification environment, add:

```scala
// Constrain inputs to be stable at clock edges
when (reset) {
  // ...
} .otherwise {
  // Ensure inputs don't change at clock edges
  assume(io.amount === Past(io.amount))  // or similar constraint
}
```

Alternatively, if the design intent is that `io.amount` can change arbitrarily, the assertion could register `io.amount` to match the DUT's pipeline:

```scala
val prev_amount = RegInit(0.U(2.W))
prev_amount := io.amount
// Use prev_amount instead of io.amount in the assertion
```

However, this would mask a real timing issue, so constraining input stability is the cleaner solution.
