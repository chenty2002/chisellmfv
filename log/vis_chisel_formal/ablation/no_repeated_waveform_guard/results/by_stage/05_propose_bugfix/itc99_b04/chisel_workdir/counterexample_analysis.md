# Counterexample Analysis Report: `average_data_out`

## 1. Verification Environment

- **Top Module**: `b04` (Chisel design)
- **Work Directory**: `chisel/extra_bench/itc99_b04/`
- **Generated Verilog**: `generated/b04.sv`
- **Source File**: `b04.scala` (218 lines)
- **Waveform File**: `verilog/extra_bench/itc99_b04/b04.average_data_out.fst`

### Key Components

| Component | Description |
|-----------|-------------|
| `stato` | 2-bit state register: sA=00, sB=01, sC=10 |
| `DATA_OUT` | 8-bit data output register |
| `RMAX`/`RMIN` | Max/min data trackers |
| `REG1`–`REG4` | Data shift registers |
| `RLAST` | Last data input register |
| avg() | Signed average of two 8-bit numbers |
| resetCounter | Formal reset sequencer |

### Control Signals

- `io.RESTART`: When asserted in sC, DATA_OUT gets avg(RMAX,RMIN)
- `io.ENABLE`: Enables normal data processing
- `io.AVERAGE`: When ENABLE=1 and AVERAGE=1, DATA_OUT gets REG4; when AVERAGE=0, gets avg(DATA_IN, REG4)
- Default (in sC, !RESTART, !ENABLE): DATA_OUT gets RLAST

## 2. Violated Assertion

**Assertion Name**: `average_data_out`

### Source Code (b04.scala, lines 182–185)

```scala
assertImpliesDelay(
    io.ENABLE && io.AVERAGE && !io.RESTART,
    DATA_OUT === RegNext(REG4), 1, "average_data_out"
)
```

### Natural Language Description

> When `io.ENABLE` is high AND `io.AVERAGE` is high AND `io.RESTART` is low, then 1 cycle later `DATA_OUT` MUST equal the value of `REG4` from the previous cycle (`RegNext(REG4)`).

### Property Semantics

The assertion is a **conditional timed property**:
- **Antecedent** (condition): `io.ENABLE && io.AVERAGE && !io.RESTART` at cycle N
- **Consequent** (property): `DATA_OUT === RegNext(REG4)` at cycle N+1

If the antecedent is **never true**, the assertion should **pass vacuously** — no failure is possible.

### Generated Verilog (b04.sv)

```verilog
// Line 129: declaration
reg  [7:0] REG_12;

// Line 259: shadow register update (UNCONDITIONAL)
REG_12 <= REG4;  // b04.scala:184:25

// Lines 127-128: assertion (UNCONDITIONAL)
average_data_out:
    assert property (@(posedge clock) disable iff (~hasBeenReset) DATA_OUT == REG_12);
```

## 3. Waveform Information

**Full path**: `verilog/extra_bench/itc99_b04/b04.average_data_out.fst`

**Duration**: 0–40 ns (4 clock cycles, period=10ns)

### State Machine Trace

| Time (ns) | Posedge | stato | Event |
|-----------|---------|-------|-------|
| 0 | Yes | 00 (sA) | Initial/reset |
| 10 | Yes | 01 (sB) | Transition to sB; io.RESTART=1, io.ENABLE=0, io.AVERAGE=1 |
| 20 | Yes | 10 (sC) | Transition to sC; io.RESTART→0, io.ENABLE→1, io.AVERAGE→0 |
| 30 | Yes | 10 (sC) | Stays in sC; assertion FAILS here |

### Critical Signal Values at Failure Point (time=30ns)

| Signal | Value (binary) | Value (hex) |
|--------|---------------|-------------|
| **DATA_OUT** | `00000010` | **0x02** |
| **REG_12** | `00000000` | **0x00** ← REG4 from previous cycle |
| REG4 | `00000000` | 0x00 |
| REG_13 | `00000010` | 0x02 ← expected avg(DATA_IN, REG4) |
| io_DATA_IN | `10000100` | 0x84 |
| io_ENABLE | `1` | — |
| io_AVERAGE | `0` | — |
| io_RESTART | `0` | — |
| stato | `10` | sC |

### Antecedent Truth Evaluation

| Cycle (time) | io.ENABLE | io.AVERAGE | io.RESTART | ANTECEDENT | Description |
|-------------|-----------|------------|------------|------------|-------------|
| 0ns | 0 | 1 | 1 | `0 && 1 && 0 = 0` | Initial |
| 10ns | 0 | 1 | 1 | `0 && 1 && 0 = 0` | sB |
| 20ns | 1 | 0 | 0 | `1 && 0 && 1 = 0` | sC start |
| 30ns | 1 | 0 | 0 | `1 && 0 && 1 = 0` | sC |

**The antecedent `io.ENABLE && io.AVERAGE && !io.RESTART` is NEVER true in the entire trace.**

## 4. Root Cause Analysis

### Classification: **Assertion Error** (incorrect assertion generation)

### Bug Location

The bug is in the **ChiselFv library's `assertImpliesDelay` macro implementation**, which generates the Verilog for timed assertions. The macro is invoked at **b04.scala line 184**, but the error is in the macro's Verilog generation logic.

### Bug Description

The `assertImpliesDelay(condition, property, 1, name)` macro is supposed to generate a conditional assertion that checks:
> When `condition` is true at cycle N, then `property` must hold at cycle N+1.

However, the generated Verilog:

1. **Creates an unconditional shadow register**: `REG_12 <= REG4;` — updates REG_12 with REG4 at **every cycle**, without gating on the antecedent.

2. **Creates an unconditional assertion**: `assert property DATA_OUT == REG_12;` — checks equality at **every cycle**, without any implication operator.

The correct implementation should either:
- **Option A**: Gate the shadow register update on the antecedent:
  ```verilog
  if (antecedent) REG_12 <= REG4;
  ```
- **Option B**: Use Verilog's property implication operator with delay:
  ```verilog
  assert property (antecedent |-> ##1 DATA_OUT == REG_12);
  ```

### Why This Causes the Assertion to Fail

At time 30ns (posedge):
1. **stato = sC** → the FSM processes data in normal mode
2. **io.ENABLE = 1, io.AVERAGE = 0, io.RESTART = 0** → the selected DATA_OUT computation is:
   ```
   DATA_OUT := avg(io.DATA_IN, REG4) = avg(0x84, 0x00) = 0x02
   ```
3. **REG_12** holds the REG4 value from the previous cycle (time 20ns): **0x00**
4. The unconditional assertion checks: **DATA_OUT (0x02) == REG_12 (0x00) → FALSE**
5. **Assertion fires!**

However, this failure is **spurious** because:
- The antecedent `io.ENABLE && io.AVERAGE && !io.RESTART` was **never true**
- At the time of failure, the system was operating under the **different** condition `io.ENABLE=1, io.AVERAGE=0, !io.RESTART`, which selects `avg(io.DATA_IN, REG4)` — not `REG4` — for DATA_OUT
- The assertion should have **passed vacuously** since its enabling condition never triggered

### Evidence Summary

1. **Waveform** shows the antecedent is always false (AVERAGE=0 when ENABLE=1)
2. **Verilog** shows the assertion is unconditional (`DATA_OUT == REG_12` with no `|->` implication)
3. **Verilog** shows the shadow register is unconditional (`REG_12 <= REG4` with no `if` guard)
4. The failure occurs at time 30ns because DATA_OUT equals the avg result (0x02), not REG4 (0x00), which is correct behavior under the `ENABLE=1, AVERAGE=0` control path

### Corrective Actions

Fix the `assertImpliesDelay` macro in the ChiselFv library so that for non-zero delays:
- The shadow register update is **gated by the antecedent condition**, OR
- The assertion uses the proper Verilog implication operator with delay

This will ensure that the assertion only checks `DATA_OUT == RegNext(REG4)` when `io.ENABLE && io.AVERAGE && !io.RESTART` was actually true, and passes vacuously otherwise.
