# Counterexample Analysis Report: `sA_to_sC_in_2_cycles`

## 1. Verification Environment

- **Top Module**: `b04` (from ITC99 benchmark b04)
- **Source File**: `chisel/extra_bench/itc99_b04/b04.scala`
- **Design Description**: The b04 module is a finite state machine with states sA (0), sB (1), and sC (2). It processes an 8-bit data stream, maintains running min/max values (RMAX, RMIN), and implements a 4-stage shift register (REG1→REG2→REG3→REG4). The normal FSM progression is: sA → sB → sC, after which it stays in sC (steady state).
- **Key Components**:
  - `stato`: 2-bit state register (`RegInit(sA)`)
  - `RMAX`, `RMIN`: Running max/min registers
  - `REG1-REG4`: 4-stage shift register pipeline for data history
  - `DATA_OUT`: Output computation register
  - Inputs: `io.RESTART`, `io.AVERAGE`, `io.ENABLE`, `io.DATA_IN[7:0]`

## 2. Violated Assertion

- **Assertion Name**: `sA_to_sC_in_2_cycles`
- **Waveform File**: `b04.sA_to_sC_in_2_cycles.fst`
- **Code Location**: `b04.scala`, lines 120-130

**Code Snippet**:

```scala
// Bounded liveness: FSM progression sA -> sB -> sC within 2 cycles
// After reset, stato starts at sA; it must reach sC within 2 cycles
// assertImpliesDelay(stato === sA, stato === sC, 2, "sA_to_sC_in_2_cycles")
// Manual replacement: when sA fires, sC must hold within the next 2 cycles (##[0:2])
// Check at time t: if stato===sA at t-2, then stato===sC at t-2, t-1, or t
{
  val a_sA = stato === sA
  val c_sC = stato === sC
  val a_sA_d2 = RegNext(RegNext(a_sA))
  val c_sC_d2 = RegNext(RegNext(c_sC))
  val c_sC_d1 = RegNext(c_sC)
  fvAssert(!a_sA_d2 || c_sC_d2 || c_sC_d1 || c_sC, "sA_to_sC_in_2_cycles")
}
```

**Property Description**: If the FSM was in state sA two cycles ago (a_sA_d2 is true), then it must be in state sC at some point within the last two cycles — either now (c_sC), one cycle ago (c_sC_d1), or two cycles ago (c_sS_d2).

## 3. Waveform Information

- **Full Path**: `verilog/extra_bench/itc99_b04/b04.sA_to_sC_in_2_cycles.fst`
- **Waveform Duration**: 10 ns (1 cycle)
- **Key Time Points**:

| Time (ns) | Signal | Value |
|-----------|--------|-------|
| 0 | `clock` | 1 |
| 5 | `clock` | 0 |
| 0-10 | `stato [1:0]` | `00` (sA) — never changes |
| 0-10 | `a_sA_d2` | 1 — remains 1 throughout |
| 0-10 | `c_sC` | 0 — never changes |
| 0-10 | `c_sC_d1` | 0 — never changes |
| 0-10 | `c_sC_d2` | 0 — never changes |
| 0-10 | `sA_to_sC_in_2_cycles` | 1 — assertion is violated (signal is high) |
| 0-10 | `hasBeenReset` | 1 — design has been reset |
| 0-10 | `reset` | 0 — reset is inactive |
| 0-10 | `io_ENABLE` | 0 — constant, no active enable |
| 0-10 | `io_RESTART` | 0 — constant |
| 0-10 | `io_AVERAGE` | 0 — constant |
| 0-10 | `io_DATA_IN [7:0]` | `11111111` — constant |

## 4. Root Cause Analysis

### Root Cause Category: **Incorrect Assertion (assertion_error)**

### Detailed Explanation

The assertion uses uninitialized pipeline registers (`RegNext(RegNext(...))`) to implement the temporal check. In Chisel, `RegNext` without a reset value creates registers with arbitrary initial values in formal verification. The formal solver is free to choose any initial state for these registers, and it exploits this freedom to create a spurious counterexample.

**The specific issue:**

1. **Uninitialized pipeline registers**: The variables `a_sA_d2`, `c_sC_d2`, and `c_sC_d1` are created via `RegNext()` without reset values:
   ```scala
   val a_sA_d2 = RegNext(RegNext(a_sA))  // No reset value
   val c_sC_d2 = RegNext(RegNext(c_sC))  // No reset value
   val c_sC_d1 = RegNext(c_sC)           // No reset value
   ```

2. **Arbitrary initial values**: In formal verification, these registers can start at any value. The solver chooses:
   - `a_sA_d2 = 1` (arbitrary assignment exploits the assertion)
   - `c_sC_d2 = 0`, `c_sC_d1 = 0`, `c_sC = 0` (stato never leaves sA because no clock rising edge occurs)

3. **Assertion fires spuriously**: With `a_sA_d2 = 1` and no `c_sC` being true, the assertion condition evaluates to:
   ```
   !a_sA_d2 || c_sC_d2 || c_sC_d1 || c_sC
   = !1 || 0 || 0 || 0
   = 0 || 0 || 0 || 0
   = 0  → ASSERTION FAILS
   ```

4. **No clock edge occurs**: The clock trace shows only two changes (time 0: 1, time 5: 0) — it starts high and falls, with no rising edge. Without a rising clock edge, no state updates happen, so the FSM never progresses from sA to sB to sC. However, this is actually secondary to the root cause — the primary issue is the uninitialized pipeline registers firing the assertion before any clock edge.

### Why the Fix is Needed

The assertion pipeline registers need reset values. They should be tied to the same reset as the rest of the design. The proper fix is to initialize the assertion pipeline registers with known values when reset is asserted:

**Option 1**: Add reset values to the pipeline registers:
```scala
val a_sA_d2 = RegInit(false.B)
val c_sC_d2 = RegInit(false.B)  
val c_sC_d1 = RegInit(false.B)
a_sA_d2 := RegNext(RegNext(a_sA))
c_sC_d2 := RegNext(RegNext(c_sC))
c_sC_d1 := RegNext(c_sC)
```

**Option 2**: Use the existing `hasBeenReset` signal to gate the assertion, so it only checks after the design and pipeline have stabilized:
```scala
fvAssert(!hasBeenReset || !a_sA_d2 || c_sC_d2 || c_sC_d1 || c_sC, "sA_to_sC_in_2_cycles")
```

Without this fix, the formal tool will always find a spurious counterexample by setting the uninitialized pipeline registers to arbitrary values that trigger the assertion.
