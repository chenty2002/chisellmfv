# Counterexample Analysis: `turn_flip_on_exit` Assertion Failure

## 1. Verification Environment

- **Top Module**: `dekker`
- **Design**: A Chisel model of Dekker's mutual exclusion algorithm for two processes.
- **Key Components**:
  - `c(0)`, `c(1)`: Flag registers for each process (true = not interested, false = interested/contending)
  - `turn`: Shared register deciding priority when both processes contend simultaneously
  - `self`: Register indicating which process this FSM instance is (0 or 1; determined by `io_select`)
  - `pc(0)`, `pc(1)`: Program counters for each process, encoded as 3-bit state machine values (L0–L6)
- **Clock**: Positive edge-triggered, 10 ns period
- **Reset**: Active-low reset (`:jasper_formal_reset`)

## 2. Violated Assertion

- **Assertion Name**: `turn_flip_on_exit` (from waveform filename `dekker.turn_flip_on_exit.fst`)
- **Source Location**: `dekker.scala`, line ~122

### Code Snippet

```scala
AssertProperty(!(pc(self) === L6) || (turn === ~self), None, None, Some("turn_flip_on_exit"))
```

### Property Description (Natural Language)

At every cycle:
- Either the current process (identified by `self`) is **not** in state L6 (exit/critical section cleanup)
- **Or** the `turn` register must equal the **bitwise complement** of `self` (i.e., the *other* process ID)

In other words: **When a process enters the exit state (L6), the turn should already be flipped to the other process.**

### File Location

- **File**: `dekker.scala`
- **Line**: ~122 (within the AssertProperty block at the end of the module)

## 3. Waveform Information

- **Waveform File**: `verilog/extra_bench/dekker/dekker.turn_flip_on_exit.fst`
- **Waveform Duration**: 5 cycles (0 ns → 50 ns)
- **Failure Time**: **40 ns** — the assertion signal `turn_flip_on_exit` transitions from 1 (pass) to 0 (fail) at exactly 40 ns

### Critical Signal Values at Failure Point (time = 40 ns)

| Signal | Value | Description |
|--------|-------|-------------|
| `pc(0)` | `110` (L6) | Process 0 has just entered the exit state |
| `self` | `0` | Current process is process 0 |
| `turn` | `0` | **turn has NOT been flipped yet** — still holds its initial value |
| `c(0)` | `0` (false) | Process 0's flag is low (was cleared in L1) |
| `c(1)` | `1` (true) | Process 1's flag is high (idle) |
| `io_select` | `0` | Self ID input |

### Key Timepoints (Cycle-by-Cycle Execution)

| Time (ns) | pc(0) | pc(1) | turn | self | c(0) | Event |
|-----------|-------|-------|------|------|------|-------|
| 0 | L0 | L0 | 0 | 0 | 1 | Initial state |
| 10 | L1 | L0 | 0 | 0 | 1 | Process 0 enters L1 (begin acquire) |
| 20 | L2 | L0 | 0 | 0 | **0** | Process 0 clears flag, enters L2 |
| 30 | **L5** (CS) | L0 | 0 | 0 | 0 | c(1)=1 → c(~self) is true → Process 0 enters critical section |
| 40 | **L6** | L0 | **0** | 0 | 0 | Process 0 exits CS, enters L6 **→ ASSERTION FAILS** |
| 50 | L6 | L0 | 0 | 0 | 0 | Trace ends (counterexample already found) |

## 4. Root Cause Analysis

### Root Cause Classification: **Incorrect Assertion** (`assertion_error`)

The assertion is **checking one cycle too early**. The property expects `turn === ~self` at the same cycle when `pc(self) === L6`, but the `turn` register assignment (`turn := ~self`) only takes effect at the **next** clock edge.

### Detailed Explanation

1. **What the L6 state does** (in the Chisel source, lines ~87-91):
   ```scala
   is(L6) {
     c(self) := true.B      // Clear own flag
     turn := ~self          // Flip turn to the other process
     pc(self) := L0         // Return to non-critical section
   }
   ```

2. **Timing of register updates in Chisel**: The `:=` operator on a register is a **sequential** (next-cycle) assignment. When `pc(self) === L6`, the block computes the next values of `c`, `turn`, and `pc`. These values are **committed at the next rising clock edge**, not in the same cycle.

3. **The sequence of events**:
   - At **30 ns**: pc(0)=L5 (critical section), `io_pause=0`
   - **Clock edge at 30→40 ns**: L5's logic fires `when(!io_pause) { pc(self) := L6 }`, so pc(0) becomes L6 at the 40 ns time point
   - At **40 ns**: pc(0)=L6 is **now** visible. The assertion checks `!(pc(self)===L6) || (turn===~self)`. Since `pc(self)=L6`, the first clause is false. The second clause evaluates `turn(which is 0) === ~self(which is 1)` → `0 === 1` → **false**. **Assertion fails.**
   - But the L6 state's `turn := ~self` (i.e., `turn := 1`) would only take effect at the **next** clock edge (at 50 ns).

4. **Why the assertion is wrong**: The turn flip (`turn := ~self`) is a **consequence** of being in L6, but it takes effect at the following cycle. The assertion treats `turn === ~self` as a **condition that must hold AT the same cycle when pc∈L6**, which is impossible because the register hasn't been updated yet.

### Correct Fix

The assertion should check the property **one cycle later** — i.e., in the cycle *after* the process was in L6:

```scala
// Correct assertion: after being in L6, turn should flip to ~self
AssertProperty(!past(pc(self) === L6) || (turn === ~self), None, None, Some("turn_flip_on_exit"))
```

Alternatively, if the intent is to check during L6 that the assignment's *effect* is correct, the assertion could use the *next* value of turn or check at the rising edge when pc exits L6 back to L0.

### Evidence Summary

| Evidence | Source | Supports |
|----------|--------|----------|
| `turn` = 0 at time 40 | Waveform signal `dekker.turn` | Turn hasn't flipped yet |
| `pc(0)` = L6 at time 40 | Waveform signal `dekker.pc_0 [2:0]` | Process 0 just entered L6 |
| `self` = 0 at all times | Waveform signal `dekker.self` | `~self` = 1, but `turn` = 0 |
| `turn_flip_on_exit` = 0 at time 40 | Waveform signal `dekker.turn_flip_on_exit` | Assertion fails at this exact time |
| `turn` never changes from 0 | Waveform trace `dekker.turn` | The turn flip `turn := ~self` is scheduled for the **next** cycle, but the assertion fires too early |
| L6 next-state logic | Source lines 87-91 | `turn := ~self` is a register assignment, not combinatorial |
