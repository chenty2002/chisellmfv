# Counterexample Analysis Report: Peterson Mutual Exclusion

## 1. Verification Environment

- **Top Module**: `peterson` (under package `llmverify`)
- **Structure**: Implements Peterson's algorithm for mutual exclusion between two processes.
  - Two processes indexed 0 and 1, each with a program counter (`pc`) in `{L0, L1, L2, L3, L4, L5}`
  - `turn`: shared register indicating which process can enter
  - `interested(0)`, `interested(1)`: shared registers indicating process interest
  - `self`: current process being evaluated (set by `io.select`)
  - `otherIdx = ~self`: the other process index
- **Formal Framework**: `chiselFv` with `Formal` mixin; assertions use `fvAssert` and `assertImpliesDelay`

## 2. Violated Assertion

- **Full assertion name** (from waveform filename): `turn_set_to_other_at_l2`
- **Assertion source code** (peterson.scala, lines 107-112):

```scala
assertImpliesDelay(
    pc(selfIdx) === Loc.L2,
    turn === ~self,
    0,
    "turn_set_to_other_at_l2"
)
```

- **Natural language description**: "When a process's program counter is at state `L2`, the `turn` register should equal `~self` (the other process), with delay 0 (same cycle)."
- **File location**: `peterson.scala`, line 107 (the assertion `assertImpliesDelay` call)

## 3. Waveform Information

- **Full waveform path**: `verilog/extra_bench/peterson/peterson.turn_set_to_other_at_l2.fst`
- **Time range**: 0 ns to 30 ns (3 clock cycles)
- **Key time points and signal values**:

| Time (ns) | pc_0 [2:0] | pc_1 [2:0] | turn | self | interested_0 | interested_1 |
|-----------|------------|------------|------|------|--------------|--------------|
| 0         | 000 (L0)   | 000 (L0)   | 0    | 0    | 0            | 0            |
| 10        | 001 (L1)   | 000 (L0)   | 0    | 0    | 0            | 0            |
| 20        | 010 (L2)   | 000 (L0)   | 0    | 0    | 1            | 0            |
| 30        | 010 (L2)   | 000 (L0)   | 0    | 0    | 1            | 0            |

## 4. Root Cause Analysis

### Bug Type: **Incorrect Assertion** (assertion_error)

### Root Cause

The assertion uses `delay=0`, meaning it checks the property `turn === ~self` in the **same cycle** when `pc(selfIdx) === Loc.L2`. However, both `turn` and `pc` are **registers** (declared as `RegInit`). In Chisel, register assignments take effect at the **next clock edge**, not in the cycle when the assignment is computed.

### Detailed Execution Trace

The Peterson state machine at `L2` does the following (peterson.scala, lines 56-59):

```scala
is(Loc.L2) {
    turn := ~self      // register assignment — takes effect next cycle
    pc(selfIdx) := Loc.L3  // register assignment — takes effect next cycle
}
```

Timeline of events:

1. **Cycle 0 (0–10 ns)**: pc_0 = L0. The L0 case executes, scheduling `pc(selfIdx) := L1` for the next clock edge.

2. **Clock edge at 10 ns**: pc_0 becomes `L1` (001). pc_1 stays at L0.

3. **Cycle 1 (10–20 ns)**: pc_0 = L1. The L1 case executes: `interested(0) := true.B` and `pc(0) := L2` are scheduled for the next edge.

4. **Clock edge at 20 ns**: pc_0 becomes `L2` (010), `interested(0)` becomes 1. **turn is still 0** (its registered value from reset/initialization).

5. **Cycle 2 (20–30 ns)**: The assertion fires with delay=0. At this instant:
   - `pc(0)` === L2 (010) → **true**
   - `turn` === `~self` → `0 === 1` → **false**!
   - **Assertion fails**.

The L2 case is actively executing during this cycle and *will* schedule `turn := ~self` (i.e., `turn := 1`) for the **next clock edge**, but the current register value of turn is still `0` (the reset value). The assertion checks the current register value, not the value that will be registered at the next edge.

### Fix

Change the assertion delay from `0` to `1`, so that the check verifies that one cycle *after* entering L2, the `turn` register has been correctly updated:

```scala
assertImpliesDelay(
    pc(selfIdx) === Loc.L2,
    turn === ~self,
    1,
    "turn_set_to_other_at_l2"
)
```

Alternatively, the property could be expressed as an `assertImplies` (combinational) check if `turn` were made a wire rather than a register; but since the DUT correctly uses a register (which is standard for Peterson's algorithm), the correct fix is to adjust the assertion delay to `1`.
