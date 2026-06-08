# Counterexample Analysis Report: `strltl_peterson`

## 1. Verification Environment

### Top Module Name and Structure
- **Top Module**: `Peterson` (Chisel class with `Formal` mixin)
- **Submodule**: `Buechi` — a Büchi automaton used for liveness verification
- **Contains**: `resetCounter` for formal reset tracking

### Key Components and Connections
| Signal | Width | Description |
|--------|-------|-------------|
| `io_select` | 3 bits | External input selecting which processor to simulate |
| `self` | 3 bits | Register tracking the currently-executing processor ID |
| `pc(i)` | 3 bits (Loc enum) | Program counter for processor i (L0..L7) |
| `interested(i)` | 1 bit | Interested flag for processor i |
| `turn` | 3 bits | Peterson's turn variable |
| `io_pause` | 1 bit | Pause signal that stalls processor at L0 or L6 |

### Design Under Test
The design implements a multi-processor Peterson mutual exclusion protocol with 8 processor slots (0–7). A single controller iterates through processors: register `self` selects which processor is being simulated. The state machine in `switch(pc(self))` executes the Peterson protocol for the selected processor.

### Clock and Reset
- Clock period: 10 ns (posedge at times 5, 15; negedge at 10, 20)
- Reset: handled via `resetCounter` and `hasBeenReset` register; initial state is post-reset

## 2. Violated Assertion

### Assertion Name
`interested0_true_in_critical_path`

Derived from waveform filename: `Peterson.interested0_true_in_critical_path.fst`

### Code Snippet (lines 228–233 of `mppLTLM1.scala`)
```scala
// When a processor is in the critical path (L1 through L6), its interested flag must be true.
fvAssert(
  !(pc(0) === Loc.L1 || pc(0) === Loc.L2 || pc(0) === Loc.L3 ||
    pc(0) === Loc.L4 || pc(0) === Loc.L5 || pc(0) === Loc.L6) || interested(0),
  "interested0_true_in_critical_path"
)
```

### Natural Language Description
**Property**: Whenever processor 0's program counter is in any of the critical path states (L1 through L6), its `interested` flag must be set to `true`.

### File Location
- **File**: `mppLTLM1.scala`
- **Line**: ~228–233

## 3. Waveform Information

### Waveform File
`verilog/extra_bench/strltl_peterson/Peterson.interested0_true_in_critical_path.fst`

### Key Time Points
| Time (ns) | Event |
|-----------|-------|
| 0 | Initial state: `pc(0)=L0`, `self=0`, `interested(0)=0`, `io_pause=0`, `io_select=7` |
| 5 | First clock posedge: register updates committed |
| 10 | Second clock posedge: assertion fails (`interested0_true_in_critical_path` drops from 1→0) |
| 15 | Third clock posedge: assertion still violated |

### Critical Signal Values at Failure Point (t=10 ns)
| Signal | Value | Interpretation |
|--------|-------|---------------|
| `interested0_true_in_critical_path` | **0** | **Assertion FAILS** |
| `pc_0 [2:0]` | `001` = **L1** | Processor 0 in critical path (should have interested=1) |
| `interested_0` | **0** | Interested flag is FALSE (violates the property) |
| `self [2:0]` | `111` = **7** | Controller now simulating processor 7 |
| `io_select [2:0]` | `111` = **7** | External input selects processor 7 |
| `io_pause` | **1** | Pause active, preventing processor 7 from advancing |
| `turn [2:0]` | `000` = **0** | Turn variable (unrelated to the bug) |

## 4. Root Cause Analysis

### Buggy Code Location
- **File**: `mppLTLM1.scala`
- **Lines**: 177–220 (the main state machine in `switch(pc(self))`)

### Description of the Bug

The core issue is in the processor simulation loop. The state machine processes one processor at a time, selected by the `self` register. The `self` register is updated **every cycle** from `io_select` (lines 173–175):

```scala
when(io.select > HIPROC.U) {
  self := 0.U
}.otherwise {
  self := io.select
}
```

The problem occurs when `self` changes **between two consecutive states of a single processor's protocol execution**. In the circuit generated from this Chisel code, when `self` transitions from 0 to 7, processor 0's execution is abandoned mid-protocol:

**Cycle 0** (posedge at time 5, evaluating values from time 0):
- `self_old = 0`, `pc(0) = L0`, `io_pause = 0`
- **L0 case evaluated**: `pc(0) := L1` (processor 0 enters critical path)
- **Self update**: `self := 7` (because `io_select = 7`)

**After Cycle 0** (time 10):
- `self = 7`, `pc(0) = L1`, `interested(0) = 0`
- The state machine now evaluates `switch(pc(7))`, **not** `switch(pc(0))`
- `pc(7) = L0` with `io_pause = 1`, so processor 7 stays at L0
- **Processor 0's L1 case is never executed** — `interested(0) := true.B` is **never assigned**

**After Cycle 1** (time 15):
- Same situation: `self = 7`, `pc(7) = L0` (stalled by pause)
- `pc(0)` remains stuck at L1, `interested(0)` remains stuck at 0
- **Assertion violation persists indefinitely**

### Root Cause Category: **DUT Bug**

This is a genuine design bug in the `Peterson` module. The defect is:

> **When the `self` register changes, the state machine abandons the previously-selected processor mid-execution. If the previous processor had transitioned from L0→L1 (or any forward state), the corresponding `interested` flag assignment (which occurs in the next state) is never executed. This creates a persistent inconsistent state where `pc(i)` is in the critical path but `interested(i)` is false.**

The fundamental design flaw is that the two sequential operations:
1. `pc(self) := L1` (at L0, moving into critical path)
2. `interested(self) := true.B` (at L1, setting interested flag)

are **split across two consecutive cycles**, but `self` can change between them, leaving the system in an inconsistent state.

### Why This Causes the Assertion to Fail

The assertion `interested0_true_in_critical_path` checks:
```
!(pc(0) in {L1,L2,L3,L4,L5,L6}) || interested(0)
```

At time 10, `pc(0) = L1` is true and `interested(0) = 0` is false, so the left side of `||` is false and the right side is false, making the whole expression false — the assertion is violated.

### Additional Note: Test Setup

The test setup (`io_select = 7` constant, `io_pause = 1` after cycle 1) is a valid stimulus for a multi-processor Peterson system. The design should correctly handle switching between processors without leaving stale state. This is not a setup error — the design must be fixed to ensure that when a processor starts executing the critical path protocol, its interested flag is set atomically regardless of `self` changes.
