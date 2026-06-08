# Counterexample Analysis Report: `gray` — `input_change_propagates_to_output_within_3`

## 1. Verification Environment

- **Top module**: `gray` (package `llmverify`)
- **Source file**: `gray.scala` (86 lines)
- **Design under test**: A Gray-code-style sequential circuit with three state registers (`p`, `q`, `r`). The circuit computes `io.z := p ^ q ^ r`, where `p := io.i`, `q := p`, and `r := io.z`. This creates a linear-feedback structure where the input `io.i` propagates through the registers over successive cycles.
- **Key components**:
  - `p`, `q`, `r`: three `RegInit(0.B)` state registers
  - `w := p ^ q` (intermediate wire)
  - `io.z := w ^ r` (output)
  - Monitor logic with `prev_i`, `mon_active`, `mon_cnt` for bounded-liveness checking
  - Additional assertions: structural invariants (`z_eq_p_xor_q_xor_r`, `w_eq_p_xor_q`), reset invariant (`registers_zero_after_reset`), stable-output invariant (`output_stable_when_input_stable_4_cycles`)

## 2. Violated Assertion

- **Assertion name** (from waveform filename): `input_change_propagates_to_output_within_3`
- **Full path**: `fvAssert(!(mon_active && mon_cnt >= 3.U), "input_change_propagates_to_output_within_3")`
- **Location**: `gray.scala`, line 62
- **Property (natural language)**: If the input `io.i` changes from its previous value, then the output `io.z` must change (i.e., an actual toggle must be observed at the output) within 3 clock cycles. The monitor uses a 2-bit counter (`mon_cnt`) that counts up while `mon_active` is true; it resets `mon_active` early if an output toggle is detected. The assertion fails if the counter reaches 3 before an output change occurs.
- **Monitor logic** (lines 48-58):
  ```scala
  val prev_i = RegNext(io.i)
  val input_changed = io.i =/= prev_i
  val mon_active = RegInit(false.B)
  val mon_cnt    = RegInit(0.U(2.W))
  when(input_changed) {
    mon_active := true.B
    mon_cnt    := 0.U
  } .elsewhen(mon_active) {
    mon_cnt := mon_cnt + 1.U
    when(io.z =/= RegNext(io.z)) {
      mon_active := false.B
    }
  }
  fvAssert(!(mon_active && mon_cnt >= 3.U), "input_change_propagates_to_output_within_3")
  ```

## 3. Waveform Information

- **Waveform file**: `verilog/extra_bench/gray/gray.input_change_propagates_to_output_within_3.fst`
- **Waveform duration**: 5 cycles (50 ns)
- **Clock edges** (rising): times 0, 10, 20, 30, 40 ns
- **Failure time**: 40 ns (5th clock cycle, assertion transitions from 1→0)

### Critical Signal Values

| Signal | Time 0 | Time 10 | Time 20 | Time 30 | Time 40 |
|--------|--------|---------|---------|---------|---------|
| `gray.io_i` | 0 | 0 | 0 | 0 | 0 |
| `gray.prev_i` | **1** | **0** | 0 | 0 | 0 |
| `gray.mon_active` | 0 | **1** | 1 | 1 | 1 |
| `gray.mon_cnt [1:0]` | 00 | 00 | 01 | 10 | **11** |
| `gray.io_z` | 0 | 0 | 0 | 0 | 0 |
| `gray.p` | 0 | 0 | 0 | 0 | 0 |
| `gray.q` | 0 | 0 | 0 | 0 | 0 |
| `gray.r` | 0 | 0 | 0 | 0 | 0 |
| `gray.w` | 0 | 0 | 0 | 0 | 0 |
| `gray.REG` (RegNext(io.z)) | 0 | 0 | 0 | 0 | 0 |
| `gray.io_z_0` | 0 | 0 | 0 | 0 | 0 |
| `gray.input_change_propagates_to_output_within_3` | 1 | 1 | 1 | 1 | **0 (FAIL)** |

## 4. Root Cause Analysis

### Buggy Code Location

**File**: `gray.scala`, **line 48**
```scala
val prev_i = RegNext(io.i)
```

### Classification

**Type**: `assertion_error` — the assertion's monitor does not properly handle the non-deterministic initial value of `RegNext(io.i)` at time 0.

### Description of the Bug

The register `prev_i` is declared as `RegNext(io.i)` — a `RegNext` with **no reset value**. In Chisel formal verification, registers initialized with `RegNext` (without a reset value) get a **non-deterministic initial value** at time 0 (before the first clock edge). This is standard formal semantics: uninitialized registers can be `0` or `1` from the solver's perspective.

At time 0 of this counterexample:
1. `io.i = 0` (the driven input)
2. `prev_i = 1` (non-deterministic initial value chosen by the solver)
3. `input_changed = io.i =/= prev_i = 0 =/= 1 = true` **(spurious!)**

Because `input_changed` evaluates to `true` at time 0, the `when(input_changed)` block activates the bounded-liveness monitor:
- `mon_active` becomes `true`
- `mon_cnt` resets to `0`

However, **no actual input change has occurred** — `io.i` stays at `0` throughout the entire trace (and `prev_i` updates to `0` after the first clock edge, matching `io.i` thereafter). Since the input never really changes, the internal registers `p`, `q`, `r` all stay at `0`, and the output `io.z` stays at `0` permanently. The output-change detector (`io.z =/= RegNext(io.z)`) never fires because both `io.z` and `RegNext(io.z)` remain `0`.

Consequently, the monitor timer increments each cycle:
- Cycle 2 (time 20): `mon_cnt = 1`
- Cycle 3 (time 30): `mon_cnt = 2`
- Cycle 4 (time 40): `mon_cnt = 3`

At time 40, `mon_active && mon_cnt >= 3.U` evaluates to `true`, and the assertion `!(mon_active && mon_cnt >= 3.U)` fails.

### Why this is an Assertion Error (not a DUT Bug)

The actual DUT logic (`p := io.i`, `q := p`, `r := io.z`, `io.z := p ^ q ^ r`) is functioning correctly — the circuit maintains the structural invariants as evidenced by the other passing assertions. The failure is purely in the **monitor that checks the bounded-liveness property**, which is triggered spuriously by the non-deterministic initial value of an uninitialized `RegNext` register.

### Suggested Fix

Two complementary approaches:

**Option A — Add a reset value to `prev_i`** (addresses the root cause):
```scala
val prev_i = RegNext(io.i, 0.B)
```
This ensures `prev_i` initializes deterministically to `0`. However, this alone is insufficient if `io.i` could be `1` at time 0 (the monitor would still trigger on the first cycle). It should be combined with Option B.

**Option B — Guard the monitor with a reset/deassert condition** (recommended):
```scala
val prev_i = RegNext(io.i, 0.B)
val input_changed = io.i =/= prev_i

val mon_active = RegInit(false.B)
val mon_cnt    = RegInit(0.U(2.W))

when(reset.asBool) {           // ← ADD: reset disables the monitor
  mon_active := false.B
  mon_cnt    := 0.U
} .elsewhen(input_changed) {
  mon_active := true.B
  mon_cnt    := 0.U
} .elsewhen(mon_active) {
  mon_cnt := mon_cnt + 1.U
  when(io.z =/= RegNext(io.z)) {
    mon_active := false.B
  }
}
```

Or more simply, use the framework-provided `hasBeenReset` signal:
```scala
val prev_i = RegNext(io.i)
when(past(hasBeenReset)) {     // only check after reset is complete
  // ... monitor logic ...
}
```

The key insight is that **`RegNext` without a reset value is non-deterministic at time 0**, and any assertion monitor that compares a `RegNext` value to its input at time 0 will see a spurious mismatch. The monitor must be gated to only operate after the initial state has settled.
