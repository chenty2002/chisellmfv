# Counterexample Analysis Report: `DetTransition_L10_to_L11_p0`

## 1. Verification Environment

- **Top module**: `bakery` (from `good_bakery.scala`)
- **Module structure**: Implements the Lamport's Bakery mutual exclusion algorithm for 3 processes (HIPROC=2)
- **Key components**:
  - Per-process state: `pc` (program counter, enum Loc), `ticket`, `choosing`, `j` (loop counter), `defer` (deferred ticket snapshot)
  - Global: `selReg` (selected process register), `io_select` (input), `io_pause` (input)
- **Assertion framework**: Chisel `chiselFv` library with `assertImpliesDelay`, `fvAssert`, etc.

## 2. Violated Assertion

- **Assertion name**: `DetTransition_L10_to_L11_p0` (from waveform filename `bakery.DetTransition_L10_to_L11_p0.fst`)
- **Source location**: `good_bakery.scala` line 223
- **Code snippet**:
  ```scala
  // L10 -> L11: Exit critical section, release ticket
  assertImpliesDelay(selected && (pc(sel) === Loc.L10),
    pc(sel) === Loc.L11, 1, s"DetTransition_L10_to_L11_p${sel}")
  ```
- **Intended property**: When process `sel` is selected (`selReg === sel.U`) AND its program counter is at L10 (critical section exit), then after exactly 1 clock cycle, its program counter must transition to L11.

## 3. Waveform Information

- **Waveform file**: `verilog/extra_bench/bakery_good_bakery/bakery.DetTransition_L10_to_L11_p0.fst`
- **Time range**: 0 ns → 10 ns (1 clock cycle)
- **State at time 0** (posedge clock):
  | Signal | Value | Meaning |
  |--------|-------|---------|
  | `bakery.pc_0` | `0000` (0) | pc(0) = Loc.L1 |
  | `bakery.pc_1` | `0000` (0) | pc(1) = Loc.L1 |
  | `bakery.pc_2` | `0000` (0) | pc(2) = Loc.L1 |
  | `bakery.selReg` | `00` (0) | selReg = 0 |
  | `bakery.io_select` | `11` (3) | io_select = 3 (> HIPROC=2) |
  | `bakery.io_pause` | `1` | pause = true |
  | `bakery.ticket_0/1/2` | `0` | All tickets = false |
  | `bakery.choosing_0/1/2` | `0` | All choosing = false |
  | `bakery.hasBeenReset` | `1` | Reset has occurred |
  | `bakery.reset` | `0` | Not in reset |
  | `bakery.DetTransition_L10_to_L11_p0` | `1` | Assertion failure signal |

## 4. Root Cause Analysis

### Root Cause Type: **Incorrect Assertion** (bug in the `assertImpliesDelay` library function)

### Detailed Analysis

#### The assertion compilation chain

The Chisel source uses `assertImpliesDelay` with delay `n=1`:

```scala
assertImpliesDelay(antecedent = (selected && pc(sel) === Loc.L10),
                   consequent = (pc(sel) === Loc.L11),
                   n = 1,
                   msg = "DetTransition_L10_to_L11_p0")
```

This calls into the `chiselFv` library (`Formal.scala` line 213-220):

```scala
def assertImpliesDelay(antecedent: Bool, consequent: Bool, n: Int, msg: String = "")
                      (implicit sourceInfo: SourceInfo): Unit = {
    requireNonNegative(n, "n")
    if (n == 0) {
      assertImplies(antecedent, consequent, msg)
    } else {
      when(delayedBool(antecedent && notChaos, n, sticky = false)) {
        fvAssert(consequent, msg)
      }
    }
}
```

The implementation:
1. Creates a `delayedBool` shift-register pipeline that delays the antecedent by `n` cycles.
2. Wraps `fvAssert(consequent)` inside a `when(delayed_result)` block, intending to only check the consequent when the antecedent was true `n` cycles ago.

But `fvAssert` calls:
```scala
def fvAssert(cond: Bool, msg: String = ""): Unit = {
    when(notChaos) {
      AssertProperty(cond, msg)
    }
}
```

#### Why the `when` gating is ineffective

The critical issue: **Chisel's `when` blocks do NOT affect `AssertProperty` statements** because `AssertProperty` generates SystemVerilog `assert property` declarations, which are **concurrent (declarative) statements**, not procedural statements. The FIRRTL compiler and firtool emit these as module-level concurrent assertions that are NOT gated by the `when` conditions.

**Evidence from generated Verilog** (line ~223):

```verilog
wire       _req_exit_T = pc_0 == 4'hA;   // pc_0 == Loc.L11
DetTransition_L10_to_L11_p0:
    assert property (@(posedge clock) disable iff (~hasBeenReset) _req_exit_T);
```

The assertion evaluates `_req_exit_T` (pc_0 == L11) **unconditionally at every clock cycle**. There is:
- ❌ **No antecedent check**: The antecedent `(selReg === 0) && (pc_0 === L10)` is entirely absent from the generated assertion.
- ❌ **No delay logic**: The 1-cycle delay pipeline created by `delayedBool` is present in the circuit (as a register) but the `when` block that gates the assertion on this delayed signal has no effect.
- ✅ Only the **consequent** `pc_0 == L11` is checked, and it's checked at every posedge clock.

Compare with other DetTransition assertions:
```verilog
// L1→L2: checks pc_0 == 4'h1 (L2) unconditionally
DetTransition_L1_to_L2_p0:
    assert property (@(posedge clock) disable iff (~hasBeenReset) _GEN);
// L2→L3: checks pc_0 == 4'h2 (L3) unconditionally
DetTransition_L2_to_L3_p0:
    assert property (@(posedge clock) disable iff (~hasBeenReset) _GEN_0);
// ... etc.
```

#### Why the assertion fails

At time 0 (the only time point in this 1-cycle trace):
- `hasBeenReset` = 1 → `~hasBeenReset` = 0 → the assertion is **not disabled**.
- `pc_0` = 0000 = Loc.L1 (the reset state).
- The assertion checks `pc_0 == 4'hA` (Loc.L11), which is `false`.
- The assertion **fails immediately** because it expects `pc_0` to always be L11, but pc_0 is at L1 (the initial state).

No sequence of inputs could make this assertion pass in its current form, because it requires `pc_0 == L11` at every clock cycle from the very first cycle.

#### Correct behavior that was intended

The assertion was intended to produce SystemVerilog equivalent to:
```verilog
assert property (@(posedge clock) disable iff (~hasBeenReset)
    (selReg == 2'h0 && pc_0 == 4'h9) |=> (pc_0 == 4'hA));
```

This would check: "If at cycle t, the process is selected AND at L10, then at cycle t+1 it must be at L11." This is a correct property that the design would satisfy.

#### The real bug

The bug is in `assertImpliesDelay` in `chiselFv/Formal.scala` (lines 213-220). The function correctly computes a delayed version of the antecedent using `delayedBool`, but then attempts to use `when(delayed_result)` to gate the `fvAssert(consequent)` call. Since `AssertProperty` is unaffected by Chisel `when` blocks, the consequent ends up being asserted unconditionally.

**Fix direction**: Replace the `when(delayedBool(...)) { fvAssert(consequent) }` pattern with inline gating that incorporates the delayed antecedent into the assertion condition itself:
```scala
val ant_delayed = delayedBool(antecedent && notChaos, n, sticky = false)
fvAssert(!ant_delayed || consequent, msg)
```
This would generate: `assert property (@(posedge clock) ... !delayed_antecedent || consequent)`, which is equivalent to the correct implication `delayed_antecedent |-> consequent`.

### Summary

| Aspect | Detail |
|--------|--------|
| **Bug Location** | `chiselFv/src/main/scala/chiselFv/Formal.scala`, `assertImpliesDelay` method (lines 213-220) |
| **Bug Type** | Assertion compilation error — `when` block cannot gate `AssertProperty` |
| **Effect** | All `assertImpliesDelay` assertions compile to unconditional consequent checks |
| **Evidence** | Generated Verilog shows `DetTransition_L10_to_L11_p0` checks `pc_0 == L11` unconditionally; the antecedent `selected && pc_0 == L10` and 1-cycle delay are missing |
