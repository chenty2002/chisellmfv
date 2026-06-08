# Counterexample Analysis Report: bakery_good_bakery

## 1. Verification Environment

- **Top Module**: `bakery` (in `good_bakery.scala`)
- **Module Type**: Chisel module with `Formal` mixin for formal verification assertions
- **Key Components**:
  - 3 processes (0, 1, 2) implementing a mutual exclusion protocol inspired by Lamport's Bakery algorithm
  - Each process has a program counter (`pc`), ticket flag (`ticket`), choosing flag (`choosing`), loop counter (`j`), and defer register (`defer`)
  - A single-select mechanism: `selReg` selects which process advances each cycle based on `io_select` input
  - `io_pause` input can stall processes at the CS gate (L9) and exit gate (L11)
- **Design Under Test**: A 3-process mutual exclusion implementation using boolean tickets and a snapshot-based deferral mechanism to establish ordering between processes

## 2. Violated Assertion

- **Assertion Name**: `MutualExclusion_at_most_one_process_in_CS`
- **Waveform File**: `bakery.MutualExclusion_at_most_one_process_in_CS.fst`
- **Code Snippet** (good_bakery.scala lines 157-158):
  ```scala
  val in_cs = (0 to HIPROC).map(i => pc(i) === Loc.L10)
  assertMutex(in_cs, "MutualExclusion_at_most_one_process_in_CS")
  ```
- **Property Description**: At most one process can be in the critical section (state L10) at any time. This is the fundamental safety property of any mutual exclusion algorithm.
- **File Location**: `good_bakery.scala`, lines 157-158

## 3. Waveform Information

- **Waveform File**: `verilog/extra_bench/bakery_good_bakery/bakery.MutualExclusion_at_most_one_process_in_CS.fst`
- **Failure Time**: 550 ns (cycle 55)
- **Key Time Points**:
  - **360 ns**: Process 1 enters critical section (L10)
  - **370 ns**: Process 1 exits CS (L11); ALL three defer registers are cleared to `000` (incorrectly)
  - **410 ns**: Process 0 in L7 checking process 2 — condition false due to cleared defer bits, proceeds
  - **440 ns**: Process 0 enters L9 (gate), process 2 reaches j > HIPROC
  - **530 ns**: Process 2 enters L9 (gate)
  - **540 ns**: Process 0 enters L10 (critical section)
  - **550 ns**: Process 2 enters L10 (critical section) **→ Assertion Fails (both pc_0 and pc_2 = L10)**
- **Critical Signal Values at Failure (time 550 ns)**:
  - `pc_0` = `1001` (L10 = critical section)
  - `pc_2` = `1001` (L10 = critical section)
  - `pc_1` = `1010` (L11)
  - `ticket_0` = 1, `ticket_2` = 1
  - `defer_0` = `000`, `defer_2` = `000` (all cleared!)
  - `selReg` = `10` (process 2 selected)

## 4. Root Cause Analysis

### Bug Location
- **File**: `good_bakery.scala`
- **Function**: `clearBit` (lines 52–63)
- **Bug Type**: DUT design bug — incorrect bitmask literal widths in Chisel

### Bug Description

The `clearBit` helper function is intended to clear a single bit at the given index from a multi-bit vector. It uses Chisel bitstring literals as masks:

```scala
def clearBit(in: UInt, index: UInt): UInt = {
    val result = Wire(UInt(in.getWidth.W))
    when(index === 0.U) {
      result := in & ~"b001".U    // BUG: "b001".U has width 1
    }.elsewhen(index === 1.U) {
      result := in & ~"b010".U    // BUG: "b010".U has width 2
    }.elsewhen(index === 2.U) {
      result := in & ~"b100".U    // OK: "b100".U has width 3
    }.otherwise {
      result := in
    }
    result
}
```

**The Bug**: In Chisel, `"bXXX".U` creates a literal with the **minimum** width needed to represent the value:
- `"b001".U` → 1-bit value `1` → `~1'b1 = 1'b0` → extended to `3'b000` → clears ALL bits
- `"b010".U` → 2-bit value `2` → `~2'b10 = 2'b01` → extended to `3'b001` → clears bits 1 and 2
- `"b100".U` → 3-bit value `4` → `~3'b100 = 3'b011` → extended to `3'b011` → correctly clears only bit 2

So when `clearBit` is called with `index = 1` (as happens when process 1 exits CS), the mask becomes `3'b001` instead of the intended `3'b101`, clearing **both** bits 1 and 2 instead of just bit 1.

### Evidence from Waveform

1. **Before the bug triggers** (time 360 ns):
   - `defer_0` = `110` (bit 2 = 1: process 0 recorded that process 2 had a ticket)
   - `defer_2` = `010` (bit 1 = 1: process 2 recorded that process 1 had a ticket)
   - These defer bits encode the ordering between processes

2. **At time 370 ns** (process 1 exits CS via L10):
   - Process 1 calls `clearBit(defer(0), 1)` and `clearBit(defer(2), 1)`
   - **Expected**: `defer_0` = `110 & 101` = `100`, `defer_2` = `010 & 101` = `000`
   - **Actual**: `defer_0` = `000`, `defer_2` = `000` (both incorrect!)
   - The ordering information about process 2 (bit 2) in `defer_0` is **destroyed**

3. **Later, process 0 checks process 2 in L7** (time ~410 ns):
   - The L7 condition to wait is: `ticket(k) && (defSelK || (!defKSel && (k < selUInt)))`
   - With `defer_0` = `000`: `defSelK` = bit 2 of defer(0) = **0** (should have been 1)
   - Condition evaluates to **false**, so process 0 proceeds past process 2
   - If defer(0) had been correctly `100`: `defSelK` = 1, condition would be **true**, and process 0 would **wait** for process 2

4. **At time 540–550 ns**:
   - Process 0 enters L10 (CS) at 540 ns
   - Process 2 enters L10 (CS) at 550 ns
   - Both processes simultaneously in the critical section → **assertion fails**

### Why This Causes the Assertion to Fail

The `clearBit` bug corrupts the defer registers, which are the ONLY mechanism for establishing priority ordering between processes (since tickets are boolean, not numbered). Once the defer ordering is lost, both processes believe they have priority and enter the CS simultaneously, violating mutual exclusion.

### Correct Fix

The bitmask literals should be explicitly widened to match the input width:
```scala
def clearBit(in: UInt, index: UInt): UInt = {
    val result = Wire(UInt(in.getWidth.W))
    when(index === 0.U) {
      result := in & ~"b001".U(3.W)    // Fix: specify width
    }.elsewhen(index === 1.U) {
      result := in & ~"b010".U(3.W)    // Fix: specify width
    }.elsewhen(index === 2.U) {
      result := in & ~"b100".U(3.W)    // Already works due to width ≥ 3
    }.otherwise {
      result := in
    }
    result
}
```

Or more robustly, paramaterize the width:
```scala
val mask = (~(1.U << index))(in.getWidth - 1, 0)
result := in & mask
```
