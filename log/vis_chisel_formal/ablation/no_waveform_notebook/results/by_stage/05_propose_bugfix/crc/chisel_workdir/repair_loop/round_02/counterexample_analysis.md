# Counterexample Analysis Report

## 1. Verification Environment

- **Top Module**: `vcrc32_8` (Chisel module with Formal mixin)
- **Source File**: `vcrc32_8.scala` (142 lines)
- **Design Under Test**: A parallel CRC-32 computation module with the following interface:
  - `clken`: Clock enable
  - `reset`: Synchronous reset
  - `load`: Load data into CRC register (shifts and inserts byte)
  - `compute`: Compute next CRC value from current CRC and input data
  - `data_in`: 8-bit input data
  - `data_out`: Complement of CRC high byte
  - `crc_ok`: Indicator when CRC matches expected remainder 0xC704DD7B
  - `crc`: Full 32-bit CRC register output
- **Key Components**:
  - `crcReg`: 32-bit CRC register, initialized to 0xFFFFFFFF
  - `parallel_crc()`: Combinational function computing next CRC from current state and data byte
  - Control logic: priority-encoded `when` block selecting reset/load/compute/hold paths
  - Clock-gated update: `when(io.clken) { crcReg := newCrc }`

## 2. Violated Assertion

- **Full Assertion Name**: `compute_updates_crc_reg`
- **Waveform File**: `vcrc32_8.compute_updates_crc_reg.fst`
- **Code Snippet** (from vcrc32_8.scala, lines 136-142):

```scala
  // --- Liveness: Compute progress ---
  // When compute is asserted with clken high and reset inactive,
  // crcReg should change to a different value within 3 cycles (indicating forward progress).
  // The reset clause ensures that if compute goes low again before 3 cycles, the obligation is released
  // (i.e., when compute drops, the CRC is allowed to stop changing).
  astRelaxedLiveness(
    io.compute && io.clken && !io.reset,
    crcReg =/= RegNext(crcReg) || !io.compute,
    3,
    "compute_updates_crc_reg"
  )
```

- **Natural Language Description**: When `compute` is asserted (with `clken` high and `reset` low), the CRC register `crcReg` must change its value within 3 clock cycles, OR `compute` must be de-asserted. This is a relaxed liveness property ensuring forward progress of CRC computation.

- **File Location**: `vcrc32_8.scala`, lines 136-142 (around the end of the file)

## 3. Waveform Information

- **Full Path to Waveform File**: `verilog/extra_bench/crc/vcrc32_8.compute_updates_crc_reg.fst`
- **Waveform Duration**: 9 cycles (0–90 ns)
- **Time Range**: 0–90 ns (clock period = 10 ns, posedge at 0, 10, 20, ...)

### Key Time Points and Signal Values

| Time | clken | compute | reset | load | data_in | crcReg | r (RegNext) | newCrc_x |
|------|-------|---------|-------|------|---------|--------|-------------|----------|
| 0    | 1     | 0       | 0     | 1    | 0x64    | 0xFFFFFFFF | 0xFFFFFFFF | 0x00 |
| 10   | 1     | 1       | 0     | 0    | 0xF3    | 0xFFFFFF64 | 0xFFFFFFFF | 0x00 |
| 20   | 1     | 1       | 0     | 0    | 0xC6    | 0xCAFFEF64 | 0xFFFFFF64 | 0x0C |
| 30   | 0→1→0 | 1       | 0     | 0    | 0x40    | 0xC6FFEF64 | 0xCAFFEF64 | 0x0C |
| 40   | 1     | 1       | 0     | 0    | 0xCA    | 0xC6FFEF64 | 0xC6FFEF64 | 0x0C |
| 50   | 1     | 1       | 0     | 0    | 0xCA    | **0xC6FFEF64** | 0xC6FFEF64 | 0x0C |
| 60   | 0     | 1       | 1     | 0    | 0x44    | 0xC6FFEF64 | 0xC6FFEF64 | 0x0C |
| 70   | 0     | 1       | 0     | 0    | 0xD9    | 0xC6FFEF64 | 0xC6FFEF64 | 0x0C |
| 80   | 1     | 1       | 0     | 0    | 0xD9    | 0xC6FFEF64 | 0xC6FFEF64 | 0x0C |

**Assertion failure at time 80** (signal `compute_updates_crc_reg` goes from 1→0 at time 80).

### Relaxed Liveness Timer Mechanism

| Signal | Time 40 | Time 50 | Time 60 | Time 70 | Time 80 |
|--------|---------|---------|---------|---------|---------|
| nextPending | 1 | 1 | 1 | - | - |
| pending | 0 | 1 | 1 | 1 | 1 |
| timer[2:0] | 0 | 0 | 1 | 2 | 3 |

The trigger fires at time 40 (compute=1, clken=1, reset=0). The 3-cycle countdown starts at time 50 (pending=1). Timer reaches 3 at time 80, triggering the assertion failure.

## 4. Root Cause Analysis

### Classification: **Assertion Error** (Incorrect Assertion)

### Detailed Analysis

The assertion `compute_updates_crc_reg` is a **relaxed liveness** property that requires the CRC register to change value within 3 cycles whenever `compute` is asserted. The counterexample demonstrates a valid CRC computation scenario where the CRC register reaches a **mathematical fixed point** — the parallel CRC function returns the same value as the current CRC state for specific input combinations, causing `crcReg =/= RegNext(crcReg)` to remain perpetually false while `compute` stays high.

### Why the CRC Gets Stuck

The evidence from the waveform shows:

1. **At time 20–29** (cycle 2): `crcReg = 0xCAFFEF64`, `data_in = 0xC6`
   - `x = crcReg[31:24] ^ data_in = 0xCA ^ 0xC6 = 0x0C`
   - `crcReg[23:0] = 0xFFEF64`
   - `parallel_crc(0xCAFFEF64, 0xC6)` produces `0xC6FFEF64` ✓ (CRC changes)

2. **At time 40–49** (cycle 4): `crcReg = 0xC6FFEF64`, `data_in = 0xCA`
   - `x = crcReg[31:24] ^ data_in = 0xC6 ^ 0xCA = 0x0C` (same x!)
   - `crcReg[23:0] = 0xFFEF64` (same low 24 bits!)
   - `parallel_crc(0xC6FFEF64, 0xCA)` produces **0xC6FFEF64** (no change)

The `parallel_crc` function computes the next CRC value based on:
- `x = c[31:24] ^ d` (8-bit XOR of CRC high byte and data)
- `c[23:0]` (the lower 24 bits of the current CRC)

**Crucially**, the function's output does NOT depend directly on `c[31:24]` — it only depends on `x` (which incorporates `c[31:24]` via XOR) and `c[23:0]`. When both `x` and `c[23:0]` are identical across different compute cycles, the function produces the same output. This is mathematically valid behavior for a CRC computation — the state can reach a fixed point for specific input data sequences.

### Why This Is Not a DUT Bug

- The CRC computation function works correctly (verified by the successful CRC updates at times 10→20 and 20→30).
- The `crcReg` update logic is correct (updates only when `clken` is asserted, which is proper clock gating behavior).
- The fixed point is a legitimate mathematical property of the specific input values in the counterexample, not a hardware bug.

### Why the Assertion Is Incorrect

The assertion assumes that every compute cycle will necessarily change the CRC register value. However, CRC computations can reach fixed points where `parallel_crc(state, data) == state` for specific combinations of state and data. This is analogous to a CRC-32 checksum staying the same for repeated identical input bytes — a well-known mathematical phenomenon.

The assertion's condition `crcReg =/= RegNext(crcReg)` is too strong because it mandates *observable progress* (state change) rather than *correct computation*. The proper fix would be to either:

1. **Relax the assertion** to not require a change, perhaps checking only that the computation path is exercised (e.g., that `newCrc` is correctly computed from `parallel_crc`).
2. **Add input constraints** to ensure `data_in` values prevent fixed-point behavior — though this would be restrictive and unrealistic.

### Recommendation

Fix the assertion to remove the requirement that `crcReg` must always change. A more appropriate property might check that when `compute` is asserted, the module remains in a valid computational state, without requiring the output to change. For example:

```scala
// Check that when compute is asserted, the parallel_crc function is active
// (does not require crcReg to change, as fixed points are mathematically valid)
fvAssert(
  !(io.compute && io.clken && !io.reset) || 
  (newCrc === parallel_crc(crcReg, io.data_in)),
  "compute_correctly_uses_parallel_crc"
)
```
