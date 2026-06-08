# Counterexample Analysis Report: `load_compute_mutex` Assertion Failure

## 1. Verification Environment

- **Top Module**: `vcrc32_8` (from `vcrc32_8.scala`)
- **Generated Verilog**: `chisel/extra_bench/crc/generated/`
- **Waveform File**: `verilog/extra_bench/crc/vcrc32_8.load_compute_mutex.fst`
- **Design Under Test**: A 32-bit CRC computation module with parallel CRC calculation, supporting load, compute, and reset operations with a clock enable.

### Key Components
- **`crcReg`**: 32-bit CRC register (initialized to `0xFFFFFFFF`)
- **`parallel_crc()`**: Combinational function computing parallel CRC-32
- **Control signals**: `io.load`, `io.compute`, `io.reset`, `io.clken` (all inputs)
- **`io.data_in`**: 8-bit data input

## 2. Violated Assertion

- **Assertion Name**: `load_compute_mutex` (from waveform filename `vcrc32_8.load_compute_mutex.fst`)
- **File Location**: `vcrc32_8.scala`, line 111
- **Code Snippet**:
  ```scala
  // --- Safety: Mutex on control signals ---
  // load and compute should not be asserted simultaneously (reset has priority encoding)
  assertMutex(Seq(io.load, io.compute), "load_compute_mutex")
  ```
- **Property Description**: The assertion checks that `io.load` and `io.compute` are mutually exclusive — at most one of the two control signals may be asserted at any given time. This is a standard safety invariant for control signals that select between different operating modes.

## 3. Waveform Information

- **Waveform Duration**: 1 cycle (0 ns → 10 ns)
- **Time Range Analyzed**: 0 ns

### Critical Signal Values at Time 0 ns

| Signal | Value | Description |
|--------|-------|-------------|
| `vcrc32_8.io_load` | `1` | Load control signal is asserted |
| `vcrc32_8.io_compute` | `1` | Compute control signal is asserted |
| `vcrc32_8.io_reset` | `1` | Reset signal is also asserted |
| `vcrc32_8.io_clken` | `1` | Clock enable is asserted |
| `vcrc32_8.io_data_in [7:0]` | `0xFF` | Data input is 0xFF |
| `vcrc32_8.crcReg [31:0]` | `0xFFFFFFFF` | CRC register holds initial value |
| `vcrc32_8._atMostOne_T_1 [1:0]` | `10` (binary 2) | Popcount of {load, compute} = 2 (both active) |
| `vcrc32_8.load_compute_mutex` | `1` | Assertion failure indicator |

### Key Observation
**All signals remain constant throughout the entire waveform** (0 ns to 10 ns, no transitions). Both `io_load` and `io_compute` are high (1) for the entire cycle, directly violating the mutex property.

## 4. Root Cause Analysis

### Root Cause Category: **Incorrect Top Module Setup** (setup_error)

### Explanation

The assertion `assertMutex(Seq(io.load, io.compute), "load_compute_mutex")` asserts that `io.load` and `io.compute` should never be simultaneously high. The counterexample shows both signals = 1 at time 0.

However, `io.load` and `io.compute` are **primary inputs** to the module — they come from external stimulus, not from internal logic. The DUT itself does not generate these signals; it only reacts to them. The priority encoding in the `when` block handles the case where both are asserted (reset takes priority, then load, then compute), but the assertion enforces the interface contract that load and compute should be mutually exclusive at the protocol level.

### Why This Is a Setup Error

The verification environment (test harness / Formal top module) must constrain the input signals to satisfy the assertion. Specifically, the inputs must be constrained so that:

```
past(!io.load || !io.compute)   // load and compute never both high
```

Or more precisely for the mutex property:
```
$past(io.load + io.compute <= 1)
```

Without these constraints, the formal solver can freely assign both `io.load` and `io.compute` to `1` simultaneously, producing an uninteresting counterexample that does not reflect a real design bug.

### Additional Evidence

The fact that `io_reset` is also high at time 0 further indicates that the verification environment is not properly constraining the input space. The `assertMutex` assertion is independently valid — it correctly captures the mutual-exclusion requirement for control signals. But the test harness fails to constrain the inputs to prevent both from being high at once.

## 5. Recommendations

### Fix: Add Input Constraints to the Test Harness

The verification environment should constrain `io.load` and `io.compute` to be mutually exclusive, for example by adding formal assumptions:

```scala
// In the test harness or top module:
assumeMutex(Seq(io.load, io.compute), "assume_load_compute_mutex")
```

This tells the formal solver: "Only consider scenarios where load and compute are not simultaneously high." With this constraint, the solver will only explore valid input sequences, and any remaining assertion failures would indicate actual design bugs.

Alternatively, if the design intentionally allows both signals to be high (with priority encoding handling it gracefully), the assertion could be removed or modified. However, given the design semantics (load vs compute are distinct operation modes), the mutex property is well-justified and should be kept.
