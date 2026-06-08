# Counterexample Analysis Report: `control_mutex` Assertion Failure

## 1. Verification Environment

- **Top Module**: `vcrc32_8` (package `llmverify`)
- **Generated Verilog**: `chisel/extra_bench/crc/generated/`
- **Waveform File**: `verilog/extra_bench/crc/vcrc32_8.control_mutex.fst`
- **Design Under Test**: A parallel CRC-32 computation engine with 8-bit data input, supporting `reset`, `load`, and `compute` control signals with clock-gated register updates.

### Key Components
- `crcReg`: 32-bit CRC register (RegInit, initialized to `0xFFFFFFFF`)
- `parallel_crc()`: Combinational parallel CRC-32 function operating on upper 8 bits of CRC register XOR'd with input data
- Control logic: Priority-encoded `when/elsewhen` chain for `reset` → `load` → `compute` → `hold`
- Clock enable gating via `io.clken`

### Connections
- Inputs: `clken`, `reset`, `load`, `compute` (Bool), `data_in` (UInt<8>)
- Outputs: `data_out` (UInt<8>), `crc_ok` (Bool), `crc` (UInt<32>)

---

## 2. Violated Assertion

- **Assertion Name**: `control_mutex`
- **Waveform File**: `vcrc32_8.control_mutex.fst`

### Code Snippet
From `vcrc32_8.scala`, line 99:
```scala
assertMutex(Seq(io.reset, io.load, io.compute), "control_mutex")
```

### Property Description
The assertion checks that at most one of the three control signals — `io.reset`, `io.load`, and `io.compute` — is asserted (`true`) at any given time. These signals are mutually exclusive, meaning the design expects them never to be active simultaneously.

### File Location
- **File**: `vcrc32_8.scala`
- **Line**: 99

---

## 3. Waveform Information

- **Full Path**: `verilog/extra_bench/crc/vcrc32_8.control_mutex.fst`
- **Duration**: 1 cycle (0 ns → 10 ns)
- **Failure Detected**: At time **0 ns** (immediately at start of simulation)

### Critical Signal Values at Time 0 ns

| Signal | Value | Interpretation |
|--------|-------|----------------|
| `vcrc32_8.io_reset` | 0 | Not asserted |
| `vcrc32_8.io_load` | **1** | Asserted — loading mode |
| `vcrc32_8.io_compute` | **1** | Asserted — compute mode |
| `vcrc32_8.io_clken` | 0 | Clock enable off |
| `vcrc32_8.io_data_in [7:0]` | 0x00 | No input data |
| `vcrc32_8.io_data_out [7:0]` | 0x00 | Output |
| `vcrc32_8.io_crc_ok` | 0 | CRC not matched |
| `vcrc32_8.io_crc [31:0]` | 0xFFFFFFFF | CRC register value |
| `vcrc32_8.crcReg [31:0]` | 0xFFFFFFFF | CRC register (initial value) |
| `vcrc32_8.control_mutex` | **1** | Assertion violation signal active |
| `vcrc32_8._atMostOne_T_2 [1:0]` | **10** (binary) | Indicates 2 of the 3 mutex signals are active |

The same signal values persist at time 5 ns (mid-cycle), confirming the violation is not a glitch but a sustained input condition.

---

## 4. Root Cause Analysis

### Classification: **Setup Error** — Missing Input Constraints

### Description

The formal verification environment lacks assumptions (constraints) on the input control signals `io.load` and `io.compute`. The counterexample shows both signals driven to `1` simultaneously at time 0, directly violating the `assertMutex` property.

### Root Cause Details

**What happens in the counterexample:**
At time 0 ns:
1. `io.load = 1` and `io.compute = 1` are both asserted simultaneously.
2. `io.reset = 0` (not asserted).
3. The assertion `assertMutex(Seq(io.reset, io.load, io.compute), "control_mutex")` fires because two signals in the mutex group are active at once.
4. The `_atMostOne_T_2 [1:0]` signal equals `2` (binary `10`), confirming that exactly 2 of the 3 signals are active.

**Why this is not a design bug:**
The design code (lines 74-82) uses a priority-encoded `when/elsewhen/otherwise` chain:
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
This priority encoding resolves the conflict deterministically (load takes priority over compute), so the design does not produce undefined behavior when both inputs are high. However, the design does **not** contain any logic to actively prevent or detect the simultaneous assertion of load and compute — they are external inputs.

**Why this is not an incorrect assertion:**
The `assertMutex` is a legitimate safety property that captures a real design requirement: control signals should be mutually exclusive in a properly sequenced system. The comment on line 96-98 explicitly states this intent.

**The actual problem — missing formal constraints:**
In formal verification, input signals are unconstrained by default. Without assumptions (e.g., `assume(io.load === !io.compute)`) that restrict the input space, the formal tool can freely choose any combination of inputs, including the illegal `load=1, compute=1` combination. The fix is to add formal assumptions that constrain the inputs to respect the mutex property, OR to verify that the assertion is intended to be checked on the control logic (if these signals were generated internally rather than being top-level inputs).

### Evidence from Waveform

1. At time 0 ns: `io_load = 1`, `io_compute = 1`, `io_reset = 0` — both load and compute are asserted.
2. `control_mutex = 1` — assertion violation is triggered.
3. `_atMostOne_T_2 [1:0] = 10` (binary) — 2 out of 3 mutex signals active.
4. No assume/constraint signals found in the waveform, confirming the absence of input constraints.

### Recommendation

Add `assume` constraints in the formal verification setup to restrict the input control signals, for example:
```scala
assume(Mux(io.reset, !io.load && !io.compute, !io.load || !io.compute))
```
Or alternatively, add individual mutual-exclusion assumptions:
```scala
assume(!io.reset || (!io.load && !io.compute))
assume(!io.load || (!io.reset && !io.compute))
assume(!io.compute || (!io.reset && !io.load))
```

These assumptions would constrain the formal tool to only explore stimulus sequences where the mutex property holds on the inputs, allowing it to focus on verifying other properties of the design.
