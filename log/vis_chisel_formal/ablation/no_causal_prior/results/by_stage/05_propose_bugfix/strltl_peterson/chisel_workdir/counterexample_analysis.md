# Counterexample Analysis: `p0_interested_set_after_L1`

## 1. Verification Environment

- **Top module**: `Peterson` (mppLTLM1.scala:129)
- **Design**: A Peterson's algorithm implementation for mutual exclusion among 8 processes (though only 3 are actively used). Each process has a program counter (`pc[0..7]`), an `interested` flag, a `turn` variable, and iteration variable `j`.
- **Key components**:
  - `Peterson` (main module) — implements the multi-process mutual exclusion protocol with states L0–L7
  - `Buechi` — a Büchi automaton used for liveness/fairness checking
  - `ResetCounter` — provides `notChaos` signal that gates assertions during reset stabilization
- **I/O**: `io_select [2:0]` (selects which process to schedule), `io_pause` (pauses state transitions), `io_pc0/1/2` (program counters), `io_interested0`, `io_turn`

## 2. Violated Assertion

- **Assertion name**: `p0_interested_set_after_L1`
- **Waveform filename**: `Peterson.p0_interested_set_after_L1.fst`

### Chisel Source (mppLTLM1.scala:283-284)
```scala
assertNextStepWhen(pc(self) === Loc.L1 && self === 0.U, interested(0),
  "p0_interested_set_after_L1")
```

### Generated SystemVerilog (Peterson.sv)
```verilog
p0_interested_set_after_L1:
    assert property (@(posedge clock) disable iff (~hasBeenReset) interested_0);
```

### Intended Property (Natural Language)
"When the currently scheduled process has `self=0` and its program counter is at location `L1`, then **in the next cycle** `interested(0)` must be set to true."

### Actual Compiled Property
"`interested_0` must **always** be true (after reset)."

## 3. Waveform Information

- **Waveform file**: `verilog/extra_bench/strltl_peterson/Peterson.p0_interested_set_after_L1.fst`
- **Time range**: 0 ns – 10 ns (2 cycles)
- **Key signal values**:

| Signal | t=0 ns | t=10 ns |
|--------|--------|---------|
| `Peterson.p0_interested_set_after_L1` (assertion) | 1 (FIRING) | 1 (FIRING) |
| `Peterson.interested_0` | 0 | 0 |
| `Peterson.pc_0 [2:0]` | 0 (L0) | 0 (L0) |
| `Peterson.self [2:0]` | 0 | 0 |
| `Peterson.io_pause` | 1 | 1 |
| `Peterson.io_select [2:0]` | 7 | 7 |
| `Peterson.hasBeenReset` | 1 | 1 |

## 4. Root Cause Analysis

### Error Classification: **assertion_error** (Incorrect Assertion Compilation)

The assertion property was **incorrectly compiled** from Chisel to SystemVerilog. The FIRRTL-to-Verilog compilation pipeline (firtool) lost the antecedent condition and the timing delay, producing a far stricter property than intended.

### Detailed Explanation

**The Intended Compilation Chain:**

The Chisel function `assertNextStepWhen` (Formal.scala:~168) calls `assertAfterNStepWhen(cond, 1, asert, msg)`, which expands to:

```scala
when(delayedBool(cond && notChaos, n=1, sticky=false)) {
    fvAssert(asert, msg)
}
```

This internally generates nested `when` blocks:
```
when(delayedBool((pc(self) === L1 && self === 0) && notChaos, 1, false)) {
    when(notChaos) {
        AssertProperty(interested_0, "p0_interested_set_after_L1")
    }
}
```

The `delayedBool` function (Formal.scala:~50) creates a 1-bit pipe register that delays the condition by one cycle. The intended behavior is:
1. When `pc(self) === L1 && self === 0 && notChaos` is true in cycle N
2. The pipe register captures this and outputs it in cycle N+1
3. In cycle N+1, `fvAssert(interested_0)` fires, checking that `interested_0` is true

**What Actually Compiled:**

In the generated Verilog (Peterson.sv), only the innermost assertion survived:

```verilog
p0_interested_set_after_L1:
    assert property (@(posedge clock) disable iff (~hasBeenReset) interested_0);
```

Key evidence that the antecedent was lost:
1. **No delay pipe register exists** in the generated Verilog. `waveform_find_signals` confirmed no `pipe` or `delay` signals in the `Peterson` scope.
2. **`notChaos` is marked as unused** in the `ResetCounter` instantiation:
   ```verilog
   ResetCounter resetCounter (
       .clk            (clock),
       .reset          (reset),
       .timeSinceReset (/* unused */),
       .notChaos       (/* unused */)
   );
   ```
3. **Comparing with correctly-compiled assertions**: Assertions using `fvAssert` directly (e.g., `fvAssert(!(pc(0) === Loc.L6) || interested(0), ...)`) compile correctly because they embed the condition in the property expression itself. But `assertNextStepWhen` wraps `fvAssert` in an outer `when` block that firtool cannot translate into SVA syntax, so it is silently dropped.

**Why the Counterexample Fails:**

At both t=0 and t=10:
- `interested_0 = 0` — the register starts at 0 after reset
- `pc_0 = 0` (L0) — process 0 is idle, not at L1; the antecedent `pc(self) === L1` is FALSE
- `io_pause = 1` — the system is paused, preventing any state transitions

The generated assertion incorrectly requires `interested_0` to always be true, even when the process is idle at L0 and paused. The original intended assertion only checks `interested(0)` one cycle after `pc(self) === L1 && self === 0`, which never occurs in this counterexample.

### Root Cause Summary

| Component | Detail |
|-----------|--------|
| **Bug location** | `assertNextStepWhen` / `assertAfterNStepWhen` in `Formal.scala` (lines ~161-168) |
| **Bug mechanism** | The `when(delayedBool(...))` wrapper around `fvAssert(AssertProperty(...))` is not preserved during FIRRTL-to-SystemVerilog compilation. The outer `when` condition is dropped, leaving only the unconditional assertion. |
| **Effect** | All 6 assertions using `assertNextStepWhen` (lines 283-296 in mppLTLM1.scala) compile to unconditional properties that always fire regardless of the antecedent condition and timing. |
| **Evidence** | (1) No delay pipe registers in generated Verilog, (2) `notChaos` marked unused, (3) The compiled assertion has no antecedent — just `interested_0`, (4) `interested_0=0` with `pc_0=L0` and `io_pause=1` proves the design did nothing wrong. |

### Affected Assertions (all compiled incorrectly)
1. `p0_interested_set_after_L1` — unconditional `interested_0`
2. `p1_interested_set_after_L1` — unconditional `interested_1`
3. `p2_interested_set_after_L1` — unconditional `interested_2`
4. `p0_interested_cleared_after_L7` — unconditional `~interested_0`
5. `p1_interested_cleared_after_L7` — unconditional `~interested_1`
6. `p2_interested_cleared_after_L7` — unconditional `~interested_2`

### Recommended Fix

The `assertNextStepWhen` / `assertAfterNStepWhen` functions in `Formal.scala` need to be rewritten to embed the delayed antecedent directly into the SVA property expression rather than using a `when` block. For example, by using a combinational shift register that is explicitly referenced in the assertion property:

```scala
def assertNextStepWhen(cond: Bool, asert: Bool, msg: String = ""): Unit = {
    val prevCond = RegNext(cond && notChaos, false.B)
    fvAssert(!prevCond || asert, msg)
}
```

This would compile to:
```verilog
assert property (@(posedge clock) disable iff (~hasBeenReset) !prevCond || interested_0);
```

which correctly captures the implication with the 1-cycle delay entirely within the property expression.
