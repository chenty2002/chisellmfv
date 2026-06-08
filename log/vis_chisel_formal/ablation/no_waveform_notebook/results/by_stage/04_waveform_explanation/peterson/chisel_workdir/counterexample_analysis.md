# Counterexample Analysis Report: peterson.liveness_process0

## 1. Verification Environment

### Top Module
- **Module name**: `peterson` (from file `peterson.scala`)
- **Type**: Chisel module with `Formal` trait for formal verification

### Key Components
- **Registers**:
  - `interested` (Vec(2, Bool())): Whether each process is interested in entering CS
  - `turn` (UInt(1.W)): Whose turn it is (0 or 1)
  - `self` (UInt(1.W)): Currently selected process (updated by `io.select`)
  - `pc` (Vec(2, Loc())): Program counters for both processes
- **Inputs**: `io.select` (which process to execute), `io.pause` (pause execution)
- **Outputs**: Exposed internal state for verification

### Design Under Test
The design attempts to implement Peterson's mutual exclusion algorithm for two processes using a **single state machine** that time-multiplexes between processes. The `self` register (driven by `io.select`) determines which process's program counter the switch statement operates on.

## 2. Violated Assertion

### Assertion Name
`liveness_process0` (waveform file: `peterson.liveness_process0.fst`)

### Code Snippet
From `peterson.scala`, lines 88-89:
```scala
// Liveness 4: Bounded progress - when a process is waiting at L3,
// it must eventually enter the critical section (L4) within 20 cycles.
astRelaxedLiveness(pc(0) === Loc.L3, pc(0) === Loc.L4, 20, "liveness_process0")
```

### Property Description
**Bounded liveness**: When process 0 is waiting at location L3 (spin-waiting to enter the critical section), it must enter the critical section (L4) within 20 clock cycles. This property should hold for Peterson's algorithm, which guarantees starvation-freedom.

### File Location
- File: `peterson.scala`
- Line: 89

## 3. Waveform Information

### Waveform File
`verilog/extra_bench/peterson/peterson.liveness_process0.fst`

### Time Range
0 ns → 250 ns (25 clock cycles at 10 ns/cycle)

### Critical Time Points

| Time (ns) | Event |
|-----------|-------|
| 0 | Reset. All signals initial: pc(0)=L0, pc(1)=L0, self=0, turn=0, interested=[0,0], io_select=0, io_pause=0 |
| 10 | pc(0) transitions L0→L1 (process 0 starts) |
| 20 | pc(0): L1→L2, interested(0)=1. **io_select changes from 0→1** |
| 30 | pc(0): L2→L3, turn=~self=~0=1. **self register updates to 1**. State machine now operates on process 1. pc(0) abandoned at L3. |
| 40 | pc(1): L0→L1. io_pause=1. |
| 50 | pc(1): L1→L2, interested(1)=1 |
| 60 | pc(1): L2→L3, turn=~self=~1=0 |
| 60+ | **Both processes stuck at L3**. pc(0)=011, pc(1)=011, self=1, turn=0, interested=[1,1] |
| 240 | Assertion `liveness_process0` goes low (failure). Timer reached 20 (binary 10100). |

### Signal Values at Failure (time 240ns)
| Signal | Value |
|--------|-------|
| `peterson.pc_0 [2:0]` | `011` (L3) |
| `peterson.pc_1 [2:0]` | `011` (L3) |
| `peterson.self` | `1` |
| `peterson.turn` | `0` |
| `peterson.interested_0` | `1` |
| `peterson.interested_1` | `1` |
| `peterson.io_select` | `1` |
| `peterson.io_pause` | `1` |
| `peterson.liveness_process0` | `0` (failed) |

## 4. Root Cause Analysis

### Bug Location
- **File**: `peterson.scala`
- **Lines**: 36-72 (the state machine logic)
- **Root Cause**: The design uses a **single state machine** indexed by `self` to model two concurrent processes. When `io.select` changes, the previously executing process is abandoned mid-execution.

### Description of the Bug

The design fundamentally fails to implement Peterson's algorithm correctly. The critical flawed logic is:

```scala
self := io.select
val selfIdx = self
val otherIdx = ~self

switch(pc(selfIdx)) {
  // ... state transitions for pc(selfIdx) only
}
```

The state machine operates on **only one process at a time**, determined by `selfIdx = self`. When `io.select` changes, the state machine switches to the other process, **abandoning the current process at its current program counter**. The abandoned process's PC is never updated again until `io.select` switches back (which it never does in this counterexample).

### Evidence from Waveform

**Phase 1 (times 0-30ns): Process 0 runs, select=0**
1. Time 0: Initial state. `self=0`, state machine operates on `pc(0)`.
2. Time 10: `pc(0)` transitions L0→L1 (no pause).
3. Time 20: `pc(0)` transitions L1→L2, `interested(0)=1`. `io_select` becomes 1.
4. Time 30: `pc(0)` transitions L2→L3, `turn = ~self = ~0 = 1`. **self register updates to 1**.

**Phase 2 (times 30-60ns): Process 1 runs, select=1**
5. Time 30: `self=1`, `selfIdx=1`. State machine now operates on `pc(1)`, which is still at L0 (reset value). **Process 0's pc stays at L3 forever**.
6. Time 40: `pc(1)` transitions L0→L1.
7. Time 50: `pc(1)` transitions L1→L2, `interested(1)=1`.
8. Time 60: `pc(1)` transitions L2→L3, `turn = ~self = ~1 = 0`.

**Phase 3 (times 60-240ns): Both stuck**
9. **Process 0** at L3 with `self=0`: would need `!interested(1) || (turn === 0)` = `(!1 || (0===0))` = **true** to proceed. **It could enter CS, but the state machine never runs process 0 again.**

10. **Process 1** at L3 with `self=1`: needs `!interested(0) || (turn === 1)` = `(!1 || (0===1))` = **false** to proceed. It is legitimately stuck because the other process (process 0) is interested and the turn belongs to process 0.

11. From time 60 onwards, both PC values remain unchanged. Process 0 is abandoned at L3 (the state machine only runs process 1), and process 1 cannot progress past L3 because the turn bit favors process 0 but process 0 never gets to run.

12. The timer reaches 20 cycles (at time 240ns), triggering the assertion failure.

### Why the Assertion Fails

The assertion `astRelaxedLiveness(pc(0) === Loc.L3, pc(0) === Loc.L4, 20, "liveness_process0")` checks that process 0 reaches L4 within 20 cycles of entering L3. Process 0 enters L3 at time 30ns but **never reaches L4** because the state machine switches to process 1 at time 30ns and never returns. The timer counts to 20 cycles and the assertion fires.

### Error Classification
**Type: `dut_bug`** — This is a genuine bug in the original design. The design incorrectly implements Peterson's algorithm by using a single state machine indexed by `self` to model two concurrent processes. In a correct Peterson's algorithm implementation, both processes must have independent state machines that run concurrently, not time-multiplexed through a single state machine.
