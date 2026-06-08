# Counterexample Analysis Report: `controlvis.read_miss_requests_bcu`

## 1. Verification Environment

- **Benchmark**: `silver-mau`
- **Top Module**: `controlvis` (Chisel/Formal design)
- **Source File**: `controlvis.scala` (167 lines)
- **Key Components**:
  - `stateReg` (RegInit[UInt(3.W)]): State machine register holding the current state (IDLE=000, READ_HIT=001, READ_MISS=010, READ_DATA=011, WRITE_HIT=100, WRITE_MISS=101)
  - `vector` (RegInit[UInt(6.W)]): Register encoding all output control signals (Write, BCURequest_n, BCUWriteRequest_n, BCUDataOE, CacheDataSelect, MAUNotReady_n)
  - Input synchronizers: `rRst_n`, `rWorkMAU`, `rAccessMode`, `rMatch`, `rValid`, `rReadDoneFromBCU_n`, `rWriteDoneFromBCU_n` (all `RegNext`)
  - Output assignments: `io.Write := vector(5)`, `io.BCURequest_n := vector(4)`, etc.
- **Design Description**: A cache controller state machine that processes read/write requests based on MAU signals. It transitions through states (IDLE, READ_HIT, READ_MISS, READ_DATA, WRITE_HIT, WRITE_MISS) and generates control outputs via the `vector` encoding.

## 2. Violated Assertion

- **Assertion Name**: `read_miss_requests_bcu` (from waveform filename `controlvis.read_miss_requests_bcu.fst`)
- **Code Snippet** (from `controlvis.scala`, line 130):
  ```scala
  // READ_MISS: BCURequest_n must be low (active) because the MAU requests the BCU for a cache fill
  fvAssert(!(stateReg === State.READ_MISS) || !io.BCURequest_n, "read_miss_requests_bcu")
  ```
- **Property Description**: When the state machine is in the `READ_MISS` state, the `BCURequest_n` output signal must be low (active, i.e., `0`). This is because a read miss requires requesting data from the Bus Control Unit (BCU), so the request line must be asserted.
- **File Location**: `controlvis.scala`, line 130

## 3. Waveform Information

- **Waveform File**: `verilog/extra_bench/silver-mau/controlvis.read_miss_requests_bcu.fst`
- **Waveform Duration**: 30 ns (3 clock cycles)
- **Key Time Points**:

| Time (ns) | Event |
|-----------|-------|
| 0 | Rising clock edge. Reset active (`io_Rst_n=0`). `stateReg` initialized to IDLE (000). `vector` initialized to 011001 (IDLE encoding). |
| 5 | Clock falling edge |
| 10 | Rising clock edge. Reset released (`io_Rst_n` transitions 0→1). Inputs active: `io_WorkMAU=1`, `io_Match=1`, `io_Valid=1`, `io_AccessMode=01`. `rWorkMAU` captures `1`, but `rMatch`, `rValid`, `rAccessMode` capture their old pre-transition values (0, 0, 00). `stateReg` stays at IDLE (reset block fires). |
| 15 | Clock falling edge |
| **20** | **Rising clock edge. ASSERTION FAILURE.** `stateReg` transitions to READ_MISS (010). `vector` stays at 011001 (IDLE encoding). `io_BCURequest_n=1` (inactive, since `vector(4)=1`). Assertion: `!(READ_MISS) || !(1)` = `false \|\| false` = **false** → **FAILS** |

- **Critical Signal Values at Failure Point (time=20 ns)**:

| Signal | Value | Interpretation |
|--------|-------|---------------|
| `stateReg` | `010` (0x2) | `READ_MISS` state |
| `vector` | `011001` (0x19) | IDLE encoding (should be `001000` for READ_MISS) |
| `io_BCURequest_n` | `1` | BCU request inactive (should be `0`) |
| `io_BCUWriteRequest_n` | `1` | (bit 3 of 011001 = 1) |
| `io_BCUDataOE` | `0` | (bit 2 of 011001 = 0) |
| `io_CacheDataSelect` | `0` | (bit 1 of 011001 = 0) |
| `io_MAUNotReady_n` | `1` | (bit 0 of 011001 = 1) |

## 4. Root Cause Analysis

### Bug Location

- **File**: `controlvis.scala`
- **Line 44**: `val vector = RegInit("b011001".U(6.W))`
- **Component**: The `vector` signal declaration

### Description of the Bug

The `vector` signal is declared as a **register** (`RegInit`), when it should be a **combinational wire** (`Wire` or `WireInit`). This causes a one-cycle lag between `stateReg` changes and the corresponding update to the `vector` encoding of control outputs.

**Why this is a bug:**

In Chisel, `RegInit` creates a register that only updates on the positive clock edge. The `vector` next-value assignment block (lines 101–108) uses a `when/elsewhen/otherwise` chain based on the **current value** of `stateReg`:

```scala
when(stateReg === State.IDLE) {
    vector := "b011001".U
}.elsewhen(stateReg === State.READ_MISS) {
    vector := "b001000".U
  // ...
}
```

At every clock edge, **both** `stateReg` and `vector` update simultaneously from their respective next-value logic:
- `stateReg`'s next value is computed from the `r*` synchronizer registers (state machine logic)
- `vector`'s next value is computed from the **current** (old) `stateReg` value

This means:
1. At the clock edge where `stateReg` transitions from IDLE to READ_MISS, `vector`'s next value was computed from `stateReg=IDLE`, so it gets the IDLE encoding (`011001`).
2. Only at the *following* clock edge would `vector` compute its next value from `stateReg=READ_MISS` and update to the READ_MISS encoding (`001000`).

### Evidence from Waveform

- **Time 0 → 20**: `stateReg = IDLE` (000), `vector = 011001` (IDLE encoding). Consistent.
- **Time 20**: Clock edge. `stateReg` transitions to `READ_MISS` (010). But `vector` clocks in `011001` (computed from old stateReg=IDLE). **Inconsistent!**
- **Time 20 → 30**: `stateReg = READ_MISS` (010), but `vector = 011001` (WRONG - should be 001000 for READ_MISS).
- Signal `io_BCURequest_n = vector(4) = 1` (inactive), when it should be `0` (active) for the READ_MISS state.

The `vector` register never updates to the READ_MISS encoding within the available 30 ns because:
- At time 20: `vector` clocks in IDLE encoding (computed from old stateReg)
- The next opportunity would be at time 30, but the assertion fails at time 20 before that can happen

### Why This Causes the Assertion to Fail

The assertion `!(stateReg === State.READ_MISS) || !io.BCURequest_n` evaluates at time 20:

1. `stateReg === State.READ_MISS` → `true` (stateReg is 010)
2. `!(true)` → `false`
3. `io.BCURequest_n` → `1` (because `vector` still has IDLE encoding `011001` where bit 4 = 1)
4. `!(1)` → `false`
5. `false || false` → `false` → **ASSERTION FAILURE**

### Fix

Change line 44 from:
```scala
val vector = RegInit("b011001".U(6.W))
```
to:
```scala
val vector = WireInit("b011001".U(6.W))
```

This makes `vector` a combinational signal. With a `Wire`, `vector`'s value is continuously driven by the `when/elsewhen/otherwise` block based on the *current* value of `stateReg`. When `stateReg` transitions to READ_MISS, `vector` immediately becomes `001000`, `io_BCURequest_n = 0`, and the assertion passes.

**Alternative consideration**: If a one-cycle pipeline delay on the control outputs was intentional, then the assertion itself would be wrong (category 2: incorrect assertion). However, the state machine documentation states that vector encodes control outputs for the *current* state, and there is no functional reason to delay BCURequest_n by a cycle. The testbench also expects combinational outputs. Therefore, this is a **DUT bug** (category 1).

### Assertion Failure Category: **dut_bug**
