# Counterexample Analysis Report: gray.core_invariant_change_in_z_equals_change_in_i

## 1. Verification Environment

- **Top Module**: `gray` (Chisel, with `Formal` trait)
- **Verification Tool**: JasperGold formal verification
- **Source File**: `gray.scala` (52 lines)
- **Design Under Test**: A sequential circuit implementing a recurrence that computes the next output as a function of past inputs and past outputs, intended to satisfy the property that "a change in output equals a change in input" (a Gray-code-like invariant).

### Key Signals and Connections

| Signal | Type | Description |
|--------|------|-------------|
| `io.i` | Input(Bool) | Current input |
| `io.z` | Output(Bool) | Current output (combinational) |
| `p` | RegInit(0.B) | `p := io.i` → stores previous input (i[t-1]) |
| `q` | RegInit(0.B) | `q := p` → stores input from 2 cycles ago (i[t-2]) |
| `r` | RegInit(0.B) | `r := io.z` → stores previous output (z[t-1]) |
| `w` | Wire(Bool) | `w := p ^ q` → combinational intermediate |

**Circuit Logic**: `io.z := w ^ r` = `p ^ q ^ r`

## 2. Violated Assertion

- **Assertion Name**: `core_invariant_change_in_z_equals_change_in_i`
- **Waveform File**: `gray.core_invariant_change_in_z_equals_change_in_i.fst`

### Code Snippet (gray.scala, lines 33-38):

```scala
// Core invariant: z[t] = i[t] ^ i[t-1] ^ z[t-1]
// With q = i[t-1] and r = z[t-1], this means:
//   io.z === io.i ^ q ^ r
// Rearranged: (io.z ^ r) === (io.i ^ q)
// In other words, a change in output equals a change in input.
fvAssert((io.z ^ r) === (io.i ^ q), "core_invariant_change_in_z_equals_change_in_i")
```

### Natural Language Description of the Property:
The assertion checks that `(io.z XOR r) === (io.i XOR q)`, i.e., the change in output from the previous cycle equals the change in input from (what was assumed to be) the previous cycle. The comment states that `q = i[t-1]` (previous input) and `r = z[t-1]` (previous output).

### File Location:
- `gray.scala`, line 38

## 3. Waveform Information

- **Waveform File**: `verilog/extra_bench/gray/gray.core_invariant_change_in_z_equals_change_in_i.fst`
- **Duration**: 1 cycle (10 ns)
- **Time Range**: 0 ns → 10 ns

### Key Signal Values (at time 0 ns):

| Signal | Value |
|--------|-------|
| `gray.io_i` | 1 |
| `gray.io_z` | 0 |
| `gray.p` | 0 |
| `gray.q` | 0 |
| `gray.r` | 0 |
| `gray.core_invariant_change_in_z_equals_change_in_i` | 1 (assertion fails) |
| `gray.clock` | 1 (→ 0 at 5 ns, no posedge in trace) |

### Computed Assertion at Time 0:
- Left side: `io.z ^ r` = `0 ^ 0` = **0**
- Right side: `io.i ^ q` = `1 ^ 0` = **1**
- Property: `0 === 1` → **FALSE** (assertion violated)

## 4. Root Cause Analysis

### Bug Location

**Primary Bug — Incorrect Assertion: `gray.scala`, line 38**

The assertion `(io.z ^ r) === (io.i ^ q)` uses signal `q` when it should use signal `p`.

### Root Cause: Two Distinct Issues

#### Issue 1: Assertion Uses Wrong Signal (Line 38 — PRIMARY BUG)

The comment in the source code **incorrectly assumes** that `q = i[t-1]` (input from the previous cycle). However, the register chain establishes:

```
p := io.i    → p stores i[t-1]  (previous input)
q := p       → q stores i[t-2]  (input from 2 cycles ago!)
r := io.z    → r stores z[t-1]  (previous output)
```

Therefore:
- `q` = **i[t-2]** (NOT i[t-1] as the comment claims)
- `p` = i[t-1] (the actual previous input)

The assertion `(io.z ^ r) === (io.i ^ q)` mathematically simplifies as follows:

```
io.z = p ^ q ^ r        (by circuit construction)
io.z ^ r = p ^ q ^ r ^ r = p ^ q
io.i ^ q = io.i ^ q

Assertion:  p ^ q === io.i ^ q
Simplifies to:  p === io.i
```

So the assertion checks that **the current input equals the previous input** (`i[t] === i[t-1]`), which is a constraint that the input must never change. This is NOT the intended invariant "change in output equals change in input."

#### Issue 2: Circuit Combinational Logic Uses Wrong Signal (Line 26 — SECONDARY BUG)

The wire `w` is computed as `w := p ^ q` (line 26). This uses `p` (previous input) instead of `io.i` (current input). The resulting circuit computes:

```
io.z = p ^ q ^ r = i[t-1] ^ i[t-2] ^ z[t-1]
```

The output does **not** depend on the current input `io.i` at all! The comment states the intended recurrence as:

```
z[t] = i[t] ^ i[t-1] ^ z[t-1]
```

To match this recurrence, the circuit should use the current input in the combinational path:
```
w := io.i ^ q    (or equivalently: io.z := io.i ^ q ^ r)
```

### Evidence from Waveform

At time 0 (initial state after reset):
- `p = 0, q = 0, r = 0` (all initialized to 0.B)
- `io_z = p ^ q ^ r = 0` (initial output)
- `io_i = 1` (input driven high)

The assertion `(io.z ^ r) === (io.i ^ q)` evaluates to:
- `(0 ^ 0) === (1 ^ 0)` = `0 === 1` = **FALSE**

This demonstrates the failure: the assertion incorrectly uses `q` (= 0, representing i[t-2]) instead of the proper `p` (= 0, representing i[t-1]), and the computation `p ^ q` in the circuit prevents the output from reflecting the current input `io_i = 1`.

### Why This Causes the Assertion to Fail

At time 0, the registers are all initialized to 0, but `io.i` is 1. The assertion computes:
- `io.z ^ r = 0 ^ 0 = 0` (no change in output because the circuit doesn't use the current input)
- `io.i ^ q = 1 ^ 0 = 1` (the XOR of current input with i[t-2] shows a change)

The mismatch `0 === 1` triggers the assertion failure. Even in steady-state operation (after several cycles), the assertion would fail whenever `i[t] ≠ i[t-1]` (i.e., whenever the input toggles), because the assertion effectively checks `i[t] === i[t-1]`.

### Classification

This is primarily an **assertion error** (incorrect_assertion): the `fvAssert` on line 38 uses `q` when the correct signal is `p`. The assertion fails because it checks the wrong invariant. 

There is also a **design bug** (dut_bug) on line 26 where `w := p ^ q` should use the current input `io.i` instead of the previous input `p` to match the intended recurrence `z[t] = i[t] ^ i[t-1] ^ z[t-1]`.

### Recommended Fix

#### Fix 1 (Circuit, line 26): Change `w := p ^ q` to `w := io.i ^ q`
This makes the circuit compute `io.z = io.i ^ q ^ r = i[t] ^ i[t-2] ^ z[t-1]`, correctly using the current input.

#### Fix 2 (Assertion, line 38): Change `q` to `p` in the assertion
The assertion should be `(io.z ^ r) === (io.i ^ p)` to check the correct invariant that the change in output equals the change in input.

Alternatively, if the circuit delay chain is intentional, the assertion should use `p`:
```scala
fvAssert((io.z ^ r) === (io.i ^ p), "core_invariant_change_in_z_equals_change_in_i")
```
This correctly checks `z[t] ^ z[t-1] === i[t] ^ i[t-1]`.
