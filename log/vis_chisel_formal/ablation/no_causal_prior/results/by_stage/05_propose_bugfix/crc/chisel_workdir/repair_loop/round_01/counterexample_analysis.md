# Counterexample Analysis Report: vcrc32_8.control_mutex

## 1. Verification Environment

- **Top Module**: `vcrc32_8` (Chisel, compiled to Verilog)
- **Module Structure**: A CRC-32 computation engine with parallel CRC-32 calculation
- **Key I/O**:
  - `io.reset` (Input): Reset signal to initialize CRC register
  - `io.load` (Input): Load signal to shift in data byte
  - `io.compute` (Input): Compute signal to run CRC calculation
  - `io.clken` (Input): Clock enable
  - `io.data_in` (Input, 8-bit): Input data byte
  - `io.crc` (Output, 32-bit): CRC register value
  - `io.data_out` (Output, 8-bit): Inverted high byte of CRC
  - `io.crc_ok` (Output): Indicator when CRC matches expected remainder
- **Key Internal State**: `crcReg` (32-bit register holding CRC value)

## 2. Violated Assertion

- **Full Assertion Name**: `control_mutex`
- **Waveform Filename**: `vcrc32_8.control_mutex.fst`
- **Source File**: `vcrc32_8.scala`, lines 133-137
- **Assertion Code**:
  ```scala
  fvAssert(
    PopCount(Seq(io.reset, io.load, io.compute)) <= 1.U,
    "control_mutex"
  )
  ```
- **Natural Language Description**: At most one of the control signals (`io.reset`, `io.load`, `io.compute`) may be active at any given time. Simultaneous assertion is considered a design error in the driving logic.

## 3. Waveform Information

- **Waveform File**: `verilog/extra_bench/crc/vcrc32_8.control_mutex.fst`
- **Time Range**: 0 ns → 10 ns (1 cycle)

| Time | Signal | Value |
|------|--------|-------|
| 0 ns | `vcrc32_8.io_reset` | 0 |
| 0 ns | `vcrc32_8.io_load` | **1** |
| 0 ns | `vcrc32_8.io_compute` | **1** |
| 0 ns | `vcrc32_8.io_clken` | 0 |
| 0 ns | `vcrc32_8.control_mutex` | **1** (assertion violation) |
| 0 ns | `vcrc32_8.crcReg [31:0]` | 0xFFFFFFFF |

All signals are constant throughout the single cycle (no transitions). At time 0 (and the entire cycle), both `io_load` and `io_compute` are simultaneously asserted to `1`.

## 4. Root Cause Analysis

### Nature of the Issue: Incorrect Assertion (Assertion Error)

The assertion `control_mutex` checks the mutual exclusivity of the input control signals `io.reset`, `io.load`, and `io.compute`. This property is intended to verify that the **driving logic** (the environment feeding signals into the CRC module) respects the mutual exclusion contract.

The comment in the source code on line 131 explicitly states:
> *"Simultaneous assertion is a design error in the driving logic."*

This confirms the property is about the **environment's behavior**, not about the correctness of the CRC design's internal logic. The design itself has no control over its inputs — it simply reads `io.reset`, `io.load`, and `io.compute` as they are driven from outside.

### Why the Assertion Fails

In formal verification, without any **assumptions** (constraints) on the inputs, the formal tool is free to drive any combination of input values. The counterexample shows exactly this: the formal solver chose to assert both `io_load` (1) and `io_compute` (1) simultaneously, which violates the mutual exclusion property.

### The Bug

The appropriate construct for this check would be **`fvAssume`** (an assumption/constraint), not **`fvAssert`** (an assertion):

- **`fvAssume`** would constrain the formal solver to only consider input combinations where reset/load/compute are mutually exclusive, narrowing the verification to realistic input scenarios.
- **`fvAssert`** as used here incorrectly tries to "prove" that the design guarantees something about its external inputs, which is impossible since the design has no agency over those signals.

### Location of the Issue

- **File**: `vcrc32_8.scala`
- **Line**: 135
- **Code**: `fvAssert(PopCount(Seq(io.reset, io.load, io.compute)) <= 1.U, "control_mutex")`

### Recommended Fix

Change `fvAssert` to `fvAssume` to turn the assertion into an input constraint:

```scala
fvAssume(
  PopCount(Seq(io.reset, io.load, io.compute)) <= 1.U,
  "control_mutex"
)
```

This will constrain the formal verification to only consider environments where the control signals are well-behaved (mutually exclusive), while the other assertions (`crcReg_stable_when_clken_low`, `reset_sets_initial_value_next_cycle`, `load_updates_crc_correctly`, `compute_changes_crc`) continue to verify the design's internal correctness under these realistic input conditions.
