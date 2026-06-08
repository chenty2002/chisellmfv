# Counterexample Analysis Report: `control_signals_mutex`

## 1. Verification Environment

- **Top Module**: `vcrc32_8` (from `vcrc32_8.scala`)
- **Module Type**: CRC32 computation module with parallel CRC-32 logic
- **Key Components**:
  - `crcReg[31:0]` — 32-bit CRC register (initialized to `0xFFFFFFFF`)
  - `parallel_crc()` — combinational function computing next CRC value from current register and data input
  - Control interface: `io.reset`, `io.load`, `io.compute`, `io.clken`
- **Data Interface**: `io.data_in[7:0]` (input byte), `io.data_out[7:0]` (inverted high byte of CRC), `io.crc[31:0]`, `io.crc_ok`
- **Assertion Under Test**: `control_signals_mutex`

## 2. Violated Assertion

- **Assertion Name**: `control_signals_mutex`
- **Full Path**: `vcrc32_8.control_signals_mutex`
- **Waveform File**: `verilog/extra_bench/crc/vcrc32_8.control_signals_mutex.fst`

### Code Snippet (file `vcrc32_8.scala`, lines 116–120):

```scala
// Safety: At most one control signal (reset, load, compute) active per cycle.
// The design uses priority encoding via elsewhen; asserting mutex catches
// unintended overlapping control assertions that could indicate a protocol bug.
fvAssert(PopCount(Seq(io.reset, io.load, io.compute)) <= 1.U, "control_signals_mutex")
```

### Natural Language Description:
The assertion checks that at any given cycle, **at most one** of the three control signals (`reset`, `load`, `compute`) is asserted. If two or more are active simultaneously, the assertion fails.

## 3. Waveform Analysis

### Waveform File
- **Full Path**: `verilog/extra_bench/crc/vcrc32_8.control_signals_mutex.fst`
- **Duration**: 1 cycle (0 ns → 10 ns)

### Key Observations at Time 0 ns:

| Signal | Value |
|--------|-------|
| `vcrc32_8.io_reset` | **0** |
| `vcrc32_8.io_load` | **1** |
| `vcrc32_8.io_compute` | **1** |
| `vcrc32_8.io_clken` | 1 |
| `vcrc32_8.io_data_in [7:0]` | 0xFF |
| `vcrc32_8.io_crc [31:0]` | 0xFFFFFFFF |
| `vcrc32_8.control_signals_mutex` | **1** (assertion failure asserted) |
| `vcrc32_8.crcReg [31:0]` | 0xFFFFFFFF |

### Root Cause of Failure:
At time 0, **both `io_load` and `io_compute` are asserted simultaneously** (`io_load=1`, `io_compute=1`). This gives:
```
PopCount(Seq(io_reset=0, io_load=1, io_compute=1)) = 2 > 1
```
The assertion comparator sees 2 ≥ 2, so `PopCount > 1.U` evaluates to true, causing the assertion failure (`control_signals_mutex` goes high).

## 4. Root Cause Analysis

### Classification: **Setup Error (Missing Input Constraints)**

**Error Type**: `setup_error`

### Detailed Explanation:

The assertion `control_signals_mutex` is a **protocol-level safety property** — it verifies that at most one control signal is active per cycle. This is a correct and meaningful assertion for the CRC design's interface protocol.

**However**, the formal verification testbench does **not** include any assumptions (`fvAssume`) to constrain the input control signals to be mutually exclusive. Without such constraints, the formal solver is free to drive `io_load` and `io_compute` high simultaneously, which directly violates the assertion.

### Evidence:

1. **Waveform trace at time 0** shows `io_load=1` AND `io_compute=1`, confirming the double-assertion.
2. **No assumptions are present in the source code** — the file `vcrc32_8.scala` contains no `fvAssume` statements for the control signal mutex property.
3. **The design handles the overlap gracefully** (the `when(io.reset)...elsewhen(io.load)...elsewhen(io.compute)...otherwise` priority encoding ensures that `load` wins over `compute`), so the overlapping inputs **do not cause undefined or erroneous internal behavior** — they only violate the protocol assertion.

### Why This Is Not a DUT Bug:

The CRC computation logic (`parallel_crc`, register updates, output decoding) functions correctly under all input combinations. The priority-encoding mux in the `newCrc` assignment handles overlapping control signals deterministically:

```scala
when(io.reset) {
  newCrc := CRC_INITIAL_VALUE
}.elsewhen(io.load) {
  newCrc := Cat(crcReg(23, 0), io.data_in)
}.elsewhen(io.compute) {
  newCrc := parallel_crc(crcReg, io.data_in)
}.otherwise {
  newCrc := crcReg
}
```

When both `load` and `compute` are asserted, `load` takes priority (shifts in the data byte), and the design continues to operate correctly. The assertion is checking a protocol requirement that the environment must follow, not a design-internal invariant.

### Why This Is Not an Incorrect Assertion:

The mutex property (`PopCount <= 1`) is a valid and meaningful interface requirement. It prevents ambiguous control behavior at the module boundary. The assertion is correctly written; it simply needs corresponding assumptions to constrain the formal environment.

### Recommended Fix:

Add an assumption in the formal verification section that constrains the control signals to be mutually exclusive:

```scala
fvAssume(PopCount(Seq(io.reset, io.load, io.compute)) <= 1.U, "control_signals_mutex_assume")
```

Alternatively, the testbench top-level wrapper should include constraints that prevent the formal solver from driving overlapping control signals.
