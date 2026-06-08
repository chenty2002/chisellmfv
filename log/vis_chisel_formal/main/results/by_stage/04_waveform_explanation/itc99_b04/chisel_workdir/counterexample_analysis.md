# Counterexample Analysis Report: `b04.restart_output_correct`

## 1. Verification Environment

- **Top Module**: `b04` (from ITC99 benchmark suite, translated to Chisel)
- **Source File**: `b04.scala` (163 lines)
- **Design Under Test**: A min/max tracking state machine with pipeline registers. The circuit processes `DATA_IN` values, tracks running minimum (`RMIN`) and maximum (`RMAX`), maintains a 4-deep pipeline (`REG1`-`REG4`), and produces `DATA_OUT` based on control signals (`RESTART`, `AVERAGE`, `ENABLE`).
- **State Machine**: Three states — `sA` (initial, one cycle), `sB` (initialization, one cycle), `sC` (running, steady state).

## 2. Violated Assertion

- **Assertion Name**: `restart_output_correct` (from waveform filename `b04.restart_output_correct.fst`)
- **File Location**: `b04.scala`, lines 117-120
- **Code**:
  ```scala
  fvAssert(
    !(stato === sC && io.RESTART) || DATA_OUT === avg(RMAX, RMIN),
    "restart_output_correct"
  )
  ```
- **Property**: When the state machine is in state `sC` and the `RESTART` signal is asserted, the output `DATA_OUT` must equal the average (signed) of `RMAX` and `RMIN`.

## 3. Waveform Information

- **Waveform File**: `verilog/extra_bench/itc99_b04/b04.restart_output_correct.fst`
- **Time Range**: 0 ns → 30 ns (3 clock cycles, clock period = 10 ns)
- **Key Time Points** (clock posedges at 0 ns, 10 ns, 20 ns, 30 ns):

| Time | Clock | stato | DATA_OUT | RMAX | RMIN | RESTART | DATA_IN |
|------|-------|-------|----------|------|------|---------|---------|
| 0 ns | posedge | sA (00) | 0 | 0 | 0 | 0 | 3 |
| 10 ns | posedge | sB (01) | 0 | 0 | 0 | 1 | 3 |
| 20 ns | posedge | sC (10) | **0** | 3 | 3 | 1 | 3 |
| 30 ns | posedge | sC (10) | 0 | 3 | 3 | 1 | 3 |

- **Failure Point**: At time **20 ns** (2nd posedge after reset), the assertion evaluates:
  - `stato === sC` → TRUE
  - `io.RESTART === 1` → TRUE
  - `DATA_OUT (0) === avg(RMAX=3, RMIN=3) = 3` → **FALSE** (assertion violation)

## 4. Root Cause Analysis

### Bug Location

- **File**: `b04.scala`
- **Line**: 65-72 (state `sB` block)
- **Code**:
  ```scala
  is(sB) {
    RMAX := io.DATA_IN
    RMIN := io.DATA_IN
    REG1 := 0.U
    REG2 := 0.U
    REG3 := 0.U
    REG4 := 0.U
    RLAST := 0.U
    DATA_OUT := 0.U        // <--- BUG: unconditionally clears DATA_OUT
    stato := sC
  }
  ```

### Bug Description

The state machine transitions through three states: `sA → sB → sC` (stay in `sC`). State `sB` is an initialization state that runs for exactly one cycle. Its purpose is to initialize `RMAX` and `RMIN` with the first `DATA_IN` value and clear all pipeline/shadow registers before entering the steady-state `sC`.

The bug is on **line 71**: `DATA_OUT := 0.U`. In state `sB`, the `DATA_OUT` register is **unconditionally cleared to 0**. This value takes effect in the **next cycle** (after the posedge clock edge). When the machine then enters `sC` on the following cycle with `io.RESTART` already high, the assertion expects `DATA_OUT` to already equal `avg(RMAX, RMIN)`, but `DATA_OUT` is still 0 from the sB clearing. The sC logic does compute `DATA_OUT := avg(RMAX, RMIN)` in the same cycle, but that assignment only updates the **next** register value—too late to satisfy the combinational assertion.

### Detailed Waveform Trace

1. **Time 0–10 ns (state sA)**:
   - `stato = sA` (00). The sA block sets next state to `sB`.
   - All registers hold their initial values (0).

2. **Time 10 ns (posedge, state sB)**:
   - Registers update: `stato.Q = sB`.
   - The sB block evaluates **next** values:
     - `RMAX.D = io.DATA_IN = 3`
     - `RMIN.D = io.DATA_IN = 3`
     - `DATA_OUT.D = 0` (cleared)
     - `stato.D = sC`
   - `io.RESTART` is already asserted (1).

3. **Time 10–20 ns (after posedge, state sC is next)**:
   - Registers update: `RMAX.Q = 3`, `RMIN.Q = 3`, `DATA_OUT.Q = 0`, `stato.Q = sC`.

4. **Time 20 ns (posedge, state sC, ASSERTION FAILURE)**:
   - The assertion checks current register values:
     - `stato.Q = sC` ✓
     - `io.RESTART = 1` ✓
     - `DATA_OUT.Q = 0` ≠ `avg(RMAX.Q=3, RMIN.Q=3) = 3` ✗ **FAIL**
   - The sC block would set `DATA_OUT.D = avg(3,3) = 3` for the NEXT cycle, but it's already too late.

### Root Cause Category: **Bug in Original Design (DUT Bug)**

The unconditional clearing of `DATA_OUT` in state `sB` (line 71) is a genuine design bug. When `io.RESTART` is asserted upon the first entry into `sC`, the old zero value from sB's clearing persists for one cycle, violating the restart property.

### Why the Bug Exists

In the original ITC99 b04 circuit, state `sB` resets all internal state before entering the main processing loop. The clearing of `DATA_OUT` to 0 is intended to provide a clean initial output. However, the assertion `restart_output_correct` expects that if `RESTART` is asserted when entering `sC` from `sB`, `DATA_OUT` should immediately reflect `avg(RMAX, RMIN)`—but the clearing in sB prevents this.

### Suggested Fix

The fix could take one of two approaches:

**Option A (Minimal fix)**: In state `sB`, conditionally compute `DATA_OUT` when `io.RESTART` is already high:
```scala
is(sB) {
  RMAX := io.DATA_IN
  RMIN := io.DATA_IN
  REG1 := 0.U
  REG2 := 0.U
  REG3 := 0.U
  REG4 := 0.U
  RLAST := 0.U
  DATA_OUT := Mux(io.RESTART, avg(io.DATA_IN, io.DATA_IN), 0.U)
  stato := sC
}
```

**Option B (Deferred clearing)**: Move the `DATA_OUT := 0.U` clearing into state `sC` as an `.otherwise` branch, so it doesn't interfere with the RESTART path:
```scala
is(sC) {
  when(io.RESTART) {
    DATA_OUT := avg(RMAX, RMIN)
  }.elsewhen(io.ENABLE) {
    // existing logic
  }.otherwise {
    DATA_OUT := RLAST
  }
  // ... rest of sC logic
}
```
(But this would require removing the unconditional `DATA_OUT := 0.U` from sB.)
