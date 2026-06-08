# Counterexample Analysis Report: vcrc32_8.ctrl_signals_mutex

## 1. Verification Environment

- **Top Module**: `vcrc32_8` (from package `llmverify`)
- **Source File**: `vcrc32_8.scala` (144 lines)
- **Design**: A parallel CRC-32 computation engine that computes CRC on 8-bit data inputs. The module has four control inputs (`reset`, `load`, `compute`, `clken`) and produces a 32-bit CRC output along with a `crc_ok` status signal.
- **Key Components**:
  - `crcReg`: 32-bit register holding the current CRC value (initialized to `0xFFFFFFFF`)
  - `parallel_crc()`: Combinational function computing the next CRC from current CRC and input data
  - Priority-encoded control logic: `reset` > `load` > `compute` in the `when`/`elsewhen` chain
- **Assertion Under Test**: `assertMutex(Seq(io.reset, io.load, io.compute), "ctrl_signals_mutex")`

## 2. Violated Assertion

- **Full Assertion Name**: `ctrl_signals_mutex` (derived from waveform filename: `vcrc32_8.ctrl_signals_mutex.fst`)
- **File Location**: `vcrc32_8.scala`, line ~130
- **Code Snippet**:
  ```scala
  // Safety 1: Mutex — reset, load, and compute are mutually exclusive.
  // The priority encoding means simultaneous assertions silently ignore lower‑priority requests,
  // which is almost certainly a bug.
  assertMutex(Seq(io.reset, io.load, io.compute), "ctrl_signals_mutex")
  ```
- **Property Description**: At any point in time, at most one of the three control signals (`io.reset`, `io.load`, `io.compute`) may be asserted high. All three are mutually exclusive.

## 3. Waveform Information

- **Waveform File**: `verilog/extra_bench/crc/vcrc32_8.ctrl_signals_mutex.fst`
- **Time Range**: 0 ns → 10 ns (1 cycle duration, no clock transitions detected)
- **Assertion Failure Time**: 0 ns (immediate failure at the start of simulation)
- **Key Signal Values at Failure Point (time = 0 ns)**:

| Signal | Value |
|--------|-------|
| `vcrc32_8.io_reset` | 0 |
| `vcrc32_8.io_load` | **1** |
| `vcrc32_8.io_compute` | **1** |
| `vcrc32_8.io_clken` | 1 |
| `vcrc32_8.io_data_in [7:0]` | 0xFF |
| `vcrc32_8.crcReg [31:0]` | 0xFFFFFFFF |
| `vcrc32_8.clock` | 1 |
| `vcrc32_8.ctrl_signals_mutex` | **1 (failure)** |
| `vcrc32_8._atMostOne_T_2 [1:0]` | 2'b10 (indicates 2 signals active) |

## 4. Root Cause Analysis

### Root Cause: Setup Error — Missing Input Constraints (Test Harness Issue)

- **Category**: `setup_error`
- **Buggy Location**: The formal verification test harness does not constrain the input control signals to be mutually exclusive.

### Detailed Explanation

The assertion `assertMutex(Seq(io.reset, io.load, io.compute), ...)` checks that the three control inputs `reset`, `load`, and `compute` are mutually exclusive — no two may be high simultaneously.

At time 0 ns in the waveform, both `io_load` and `io_compute` are high (value `1`), while `io_reset` is low. This directly violates the mutex property, causing the assertion to fire immediately.

**Why this is a setup error, not a DUT bug:**

1. **`reset`, `load`, and `compute` are input signals** — they are declared as `Input(Bool())` in the module's IO bundle. The design itself cannot control or enforce mutual exclusivity on these signals; they are driven by the external environment.

2. **The design handles simultaneous inputs gracefully via priority encoding**:
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
   The `when`/`elsewhen` chain establishes a clear priority: `reset` > `load` > `compute`. Even if two control signals are simultaneously asserted, the design has deterministic behavior.

3. **The formal tool freely chooses input combinations**: Without explicit constraints on the inputs (e.g., `assume(!io.reset || !io.load)`), the formal verification tool can drive any combination of the three control signals. It naturally chooses a violating combination (`load=1, compute=1`) to demonstrate that the assertion can be violated.

4. **The assertion should be accompanied by input constraints**: In formal verification, properties about input signal relationships should be expressed either as:
   - **Assumptions** (using `assume` or `assumeMutex`) — telling the tool "only consider input sequences where these are mutually exclusive"
   - Or an **assertion** backed by explicit constraints that prevent the violating input combinations

### Evidence Summary

The waveform shows at time 0:
- `io_load` = 1, `io_compute` = 1 (both high simultaneously)
- `io_reset` = 0
- The assertion `ctrl_signals_mutex` fires with value 1 (failure)
- All other signals appear stable

The counterexample is produced on the very first cycle with no clock transitions, confirming this is a purely combinational violation driven entirely by the unrestricted input choices.

### Suggested Fix

Add input constraints to the formal verification environment to ensure the three control signals are mutually exclusive. For example, in the test harness (TestTop or equivalent), add:

```scala
// Constrain inputs to be mutually exclusive
assume((!io.reset || (!io.load && !io.compute)) &&
       (!io.load || (!io.reset && !io.compute)) &&
       (!io.compute || (!io.reset && !io.load)))
```

Or, if using chiselFv, replace the assertion with an assumption:

```scala
// The mutex is a constraint on the environment, not a property of the design
// (the design handles overlapping inputs via priority encoding)
assumeMutex(Seq(io.reset, io.load, io.compute), "ctrl_signals_mutex")
```

This ensures the formal tool only explores input combinations where the control signals are truly mutually exclusive, allowing the remaining assertions (load correctness, compute correctness, reset correctness, etc.) to be verified meaningfully.
