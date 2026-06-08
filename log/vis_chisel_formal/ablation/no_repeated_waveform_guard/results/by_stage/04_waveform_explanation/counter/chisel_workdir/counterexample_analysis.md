# Counterexample Analysis Report: `Counter.counter_sequential`

## 1. Verification Environment

- **Top Module**: `Counter` (from `counter.scala`)
- **Key Components**:
  - `CounterCell` × 3 (bit0, bit1, bit2) — each is a T-flip-flop that toggles when `carry_in` is true
  - `bit0.io.carry_in := true.B` — toggles every cycle
  - `bit1.io.carry_in := bit0.io.carry_out` — toggles when bit0 carries
  - `bit2.io.carry_in := bit1.io.carry_out` — toggles when bit1 carries
- **Design Under Test**: A 3-bit ripple counter made from three cascaded CounterCell modules. It counts 0→1→2→...→7→0.

## 2. Violated Assertion

- **Assertion Name**: `counter_sequential`
- **Filename**: `Counter.counter_sequential.fst`
- **Source Location**: `counter.scala`, line 68

### Code Snippet

```scala
val count = Cat(io.out2, io.out1, io.out0)
val prev_count = RegNext(count)

// Property 1: Counter increments by exactly 1 modulo 8 each cycle
AssertProperty(prev_count + 1.U === count, None, None, Some("counter_sequential"))
```

### Natural Language Description

The assertion checks that the counter value increments by exactly 1 (modulo 8) on every clock cycle. It does this by comparing the current `count` with the previous cycle's count (`prev_count = RegNext(count)`), and checking `prev_count + 1 === count`.

## 3. Waveform Information

- **Waveform File**: `verilog/extra_bench/counter/Counter.counter_sequential.fst`
- **Time Range**: 0 ns → 10 ns (1 cycle)
- **Key Time Points**:

| Time (ns) | Signal | Value |
|-----------|--------|-------|
| 0 | `Counter.:jasper_formal_clock` | 1 |
| 0 | `Counter.reset` | 0 |
| 0 | `Counter.:jasper_formal_reset` | 0 |
| 0 | `Counter.count [2:0]` | 000 (0) |
| 0 | `Counter.prev_count [2:0]` | 000 (0) |
| 0 | `Counter.counter_sequential` | 0 (failing) |
| 0 | `Counter.bit0.io_carry_in` | 1 |
| 0 | `Counter.bit0.io_carry_out` | 0 |
| 0 | `Counter.bit0.value` | 0 |
| 0 | `Counter.bit1.value` | 0 |
| 0 | `Counter.bit2.value` | 0 |
| 0 | `Counter.io_out0` | 0 |
| 0 | `Counter.io_out1` | 0 |
| 0 | `Counter.io_out2` | 0 |

- **Clock**: Remains at 1 throughout — no clock edge occurs in this counterexample.

## 4. Root Cause Analysis

### Classification: **Assertion Error** (incorrect formulation of the property)

### Explanation

The assertion `prev_count + 1.U === count` fails at **cycle 0** (the initial state after reset), where:

- `prev_count = RegNext(count)` — initialized to **0** (the default initial value for Chisel registers)
- `count = Cat(io.out2, io.out1, io.out0)` = 0 (all CounterCell registers initialize to `false.B`)
- Check: `prev_count + 1.U === count` → `0 + 1 === 0` → **false**

This is not a bug in the ripple counter design itself. The design is a standard 3-bit ripple counter that correctly increments by 1 on every positive clock edge:

- At reset, all bits are 0 (count = 0)
- On each clock edge, bit0 toggles, and carries propagate through the cascade
- The counter cycles through 0→1→2→...→7→0

The assertion is correct for all cycles **after the first**, but it fails at cycle 0 because:

1. `RegNext(count)` initializes to 0 (the default for `UInt` registers in Chisel)
2. `count` also starts at 0 after reset
3. Therefore `prev_count + 1` = 1, which does not equal `count` = 0 at the initial state

### Why It's an Assertion Error (Not a DUT Bug)

The ripple counter design (`CounterCell` and `Counter`) is functionally correct:
- Each `CounterCell` correctly implements a T-flip-flop that toggles when `carry_in` is true
- The carry chain (`bit0.io.carry_out → bit1.io.carry_in → bit2.io.carry_in`) correctly propagates carries for the ripple counter
- The counter would produce the sequence 0, 1, 2, ..., 7, 0 on successive clock edges

The bug is solely in the assertion formulation: it does not account for the initial state where `prev_count` and `count` are both 0.

### Suggested Fix

The assertion should be disabled on the first cycle after reset. For example:

**Option 1**: Use an initial-done flag to skip the first cycle:
```scala
val initial = RegInit(true.B)
initial := false.B
AssertProperty(prev_count + 1.U === count, None, Some(initial), Some("counter_sequential"))
```

**Option 2**: Use a disable-iff condition with the reset signal:
```scala
AssertProperty(prev_count + 1.U === count, None, Some(reset.asBool), Some("counter_sequential"))
```

**Option 3**: Use `past(count)` instead of `RegNext(count)` and add an initial guard (since `past` also returns the current value in cycle 0, a disable condition is still needed).

The key insight is that the assertion checks a relationship between the current and previous counter values, and no "previous" value exists at cycle 0, so the check must be suppressed for that cycle.
