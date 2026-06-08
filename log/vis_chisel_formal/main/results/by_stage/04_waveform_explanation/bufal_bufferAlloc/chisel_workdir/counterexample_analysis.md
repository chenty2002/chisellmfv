# Counterexample Analysis Report: `buffer_alloc.alloc_progress`

## 1. Verification Environment

- **Top module**: `buffer_alloc` (Chisel module with `Formal` mixin)
- **Source file**: `bufferAlloc.scala`
- **Generated Verilog**: `generated/buffer_alloc.v`
- **Waveform**: `verilog/extra_bench/bufal_bufferAlloc/buffer_alloc.alloc_progress.fst`

### Key Components

| Component | Description |
|-----------|-------------|
| `busy(0..15)` | 16-bit register vector tracking which buffers are occupied |
| `count` (5-bit) | Tracks number of currently busy buffers |
| `alloc` (1-bit) | Registered input from `io.alloc_raw` |
| `free` (1-bit) | Registered input from `io.free_raw` |
| `free_addr` (4-bit) | Registered input from `io.free_addr_raw` |
| `io.nack` | Output: goes high when `alloc && (count === 16.U)` |
| `io.alloc_addr` | Output: priority-encoded address of first free buffer |

### Design Behavior

- **Allocation**: When `alloc` is true and `io.nack` is false (i.e., buffer available), `busy(io.alloc_addr)` is set and `count` is incremented
- **Deallocation**: When `free` is true, `busy(free_addr)` is cleared and `count` is decremented
- **Nack**: Only fires when all 16 buffers are busy (`count === 16`)

---

## 2. Violated Assertion

- **Assertion name**: `alloc_progress`
- **Assertion type**: `astRelaxedLiveness` (bounded liveness)
- **Location**: `bufferAlloc.scala`, line ~80

### Code Snippet

```scala
// Bounded liveness: when alloc is requested and buffers are free,
// the request makes progress within 4 cycles (either alloc completes or nack fires)
astRelaxedLiveness(alloc && count < 16.U, io.nack || !alloc, 4, "alloc_progress")
```

### Property in Natural Language

> **If** an allocation is active (`alloc` is true) **and** there are free buffers (`count < 16`), **then** within the next 4 clock cycles, either a nack fires (`io.nack` becomes true) OR the allocation request is withdrawn (`!alloc` becomes true).

### Semantic Meaning

`astRelaxedLiveness(a, c, n, name)` means: whenever antecedent `a` holds at some cycle, consequent `c` must hold at some cycle within the next `n` cycles (inclusive).

---

## 3. Waveform Information

- **Waveform file**: `verilog/extra_bench/bufal_bufferAlloc/buffer_alloc.alloc_progress.fst`
- **Duration**: 70 ns (7 clock cycles)
- **Clock period**: 10 ns
- **Failure time**: 60 ns (timer reaches 4 cycles, exceeding bound)

### Critical Signal Timeline

| Time (ns) | Clock Cycle | alloc | count | pending | timer | io.nack | io.alloc_raw | io.free_raw | alloc_progress |
|-----------|-------------|-------|-------|---------|-------|---------|--------------|-------------|---------------|
| 0 (cyc 0) | Rising | 0 | 0 | 0 | 000 | 0 | 1 | 0 | 1 |
| 10 (cyc 1) | Rising | 1 | 0 | 0 | 000 | 0 | 1 | 0 | 1 |
| 20 (cyc 2) | Rising | 1 | 1 | 1 | 000 | 0 | 1 | 0 | 1 |
| 30 (cyc 3) | Rising | 1 | 2 | 1 | 001 | 0 | 1 | 0 | 1 |
| 40 (cyc 4) | Rising | 1 | 3 | 1 | 010 | 0 | 1 | 0 | 1 |
| 50 (cyc 5) | Rising | 1 | 4 | 1 | 011 | 0 | 1 | 1 | 1 |
| **60 (cyc 6)** | **Rising** | **1** | **5** | **1** | **100** | **0** | **1** | **1** | **0** ⚠️ |

### Busy Bit Progression

| Busy Bit | Set at Time |
|----------|-------------|
| `busy_0` | 20 ns (cyc 2) |
| `busy_1` | 30 ns (cyc 3) |
| `busy_2` | 40 ns (cyc 4) |
| `busy_3` | 50 ns (cyc 5) |
| `busy_4` | 60 ns (cyc 6) |

---

## 4. Root Cause Analysis

### Classification: **Assertion Error** (incorrect assertion formulation)

### The Bug

The bounded liveness assertion `astRelaxedLiveness(alloc && count < 16.U, io.nack || !alloc, 4, "alloc_progress")` is **incorrectly formulated**. The consequent `io.nack || !alloc` cannot be satisfied within the 4-cycle bound given the design's behavior.

### Why the Consequent Can Never Be Satisfied

1. **`io.nack` never fires within 4 cycles**: `io.nack := alloc && (count === 16.U)`. Since count starts at 0 and increments by at most 1 per cycle, reaching 16 takes **at least 16 cycles**, far exceeding the 4-cycle bound.

2. **`!alloc` never becomes true**: `alloc := io.alloc_raw`. The input `io.alloc_raw` stays high (1) throughout the entire trace. Since `alloc` simply follows this registered input, it can never deassert while the external stimulus keeps `io.alloc_raw` asserted.

3. **Antecedent is level-sensitive**: The condition `alloc && count < 16.U` is a level-sensitive condition that holds continuously from cycle 1 onward. The `astRelaxedLiveness` construct fires every cycle the antecedent is true, creating a chain of overlapping liveness obligations. Each new cycle resets the timer, but the combined effect means the consequent must hold within 4 cycles of ANY cycle where alloc is true — and since alloc never goes low, the assertion fails at cycle 6 (when timer reaches 4).

### Evidence from Waveform

| Signal | Value at Failure (60 ns) | Why It Matters |
|--------|-------------------------|----------------|
| `alloc` | 1 (always 1 after cycle 1) | `!alloc` (part of consequent) is always false |
| `io.nack` | 0 (always 0) | nack never fires because count = 5 < 16 |
| `count` | 5 (incrementing each cycle) | Shows allocation progress IS being made |
| `timer` | 100 (binary = 4 decimal) | Exceeded the 4-cycle bound |
| `pending` | 1 | Liveness monitoring has been active |

### Why the Assertion Is Incorrect

The design is a **burst allocation unit** where `alloc` is a level-sensitive signal that can remain high across multiple cycles to allocate buffers one per cycle. Each cycle that `alloc` is true and a buffer is free, a new buffer is allocated — this IS progress.

The assertion incorrectly uses:
- **`!alloc`** (request withdrawn) as a progress indicator, but for a burst-level request, the request should NOT be withdrawn while there are more allocations to do
- **`io.nack`** (all buffers full) as an alternative, but this takes 16 cycles to occur

A correct liveness property would check that **progress is made** (e.g., count changes, busy bits are set) within a bounded number of cycles, rather than checking that the request terminates or all buffers fill up.

### How to Fix

The assertion should be modified. Two approaches:

**Option A**: Change the antecedent to fire only on the **rising edge** of `alloc` (single request, not burst):
```scala
val prev_alloc = RegNext(alloc)
astRelaxedLiveness(!prev_alloc && alloc && count < 16.U, io.nack || !alloc, 4, "alloc_progress")
```

**Option B**: Change the consequent to check that **count progresses** (burst-mode progress check):
```scala
val prev_count = RegNext(count)
val count_increased = count > prev_count
astRelaxedLiveness(alloc && count < 16.U, io.nack || count_increased, 4, "alloc_progress")
```

Option A is more appropriate if the alloc signal is intended to be a pulsed request (high for one cycle to request one buffer). Option B is appropriate if the design supports burst allocation. Based on the design's ability to allocate one buffer per cycle while `alloc` stays high, Option B better matches the intent.
