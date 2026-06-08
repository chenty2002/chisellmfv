# Counterexample Analysis Report: free_progress Assertion Failure

## 1. Verification Environment

- **Top module**: `buffer_alloc` (from `bufferAlloc.scala`)
- **Design**: A buffer allocator managing 16 buffer slots with allocation and free capabilities
- **Key components**:
  - `busy` (Vec of 16 registers): tracks which buffers are in use
  - `count` (5-bit register): popcount of busy buffers
  - `alloc`, `free`, `free_addr` (registers): registered versions of inputs
  - Priority encoder: finds first free buffer for allocation
- **Connections**: Raw inputs (`io.alloc_raw`, `io.free_raw`, `io.free_addr_raw`) are registered on clock edges

## 2. Violated Assertion

- **Assertion name**: `free_progress` (from waveform filename `buffer_alloc.free_progress.fst`)
- **Location**: `bufferAlloc.scala`, line 85
- **Code snippet**:
  ```scala
  // Liveness 6: when a free request targets a busy buffer, that buffer becomes
  // non-busy within 3 cycles (the register update takes effect next cycle).
  astRelaxedLiveness(free && busy(free_addr), !busy(free_addr), 3, "free_progress")
  ```
- **Property description**: When a free request (`free=1`) targets a buffer that is currently busy (`busy(free_addr)=1`), that buffer should become non-busy (`!busy(free_addr)=1`) within 3 clock cycles.

## 3. Waveform Information

- **Waveform file**: `verilog/extra_bench/bufal_bufferAlloc/buffer_alloc.free_progress.fst`
- **Duration**: 7 cycles (0 ns → 70 ns), each cycle = 10 ns
- **Key time points** (all in nanoseconds):
  - **Time 0**: Reset. `io_free_raw=1`, `io_free_addr_raw=8` (0x8), `io_alloc_raw=1`
  - **Time 10** (Cycle 1): `free=1`, `free_addr=8`, `alloc=1`, `io_alloc_addr=0`
  - **Time 20** (Cycle 2): **TRIGGER FIRES** — `free=1`, `free_addr=0`, `busy_0=1`, `alloc=1`, `io_alloc_addr=1`
  - **Time 30** (Cycle 3): `free=0`, `free_addr=1`, `busy_0=0` (freed ✓), `busy_1=1` (allocated), `pending=1`
  - **Time 40** (Cycle 4): `timer=1`, `free_addr=1`, `busy_1=1`, `pending=1`
  - **Time 50** (Cycle 5): `timer=2`, `free=1`, `free_addr=1`, `busy_1=1`, `pending=1`
  - **Time 60** (Cycle 6): `timer=3`, `free_progress=0` → **ASSERTION FAILS**
- **Failure point**: Time 60 (timer reaches bound of 3)

## 4. Root Cause Analysis

### Bug Type: **Incorrect Assertion** (assertion_error)

### Root Cause: The `free_progress` liveness assertion fails because `free_addr` can change during the liveness checking window, causing the assertion to check the wrong buffer address.

### Detailed Explanation:

The assertion `astRelaxedLiveness(free && busy(free_addr), !busy(free_addr), 3, "free_progress")` has a fundamental flaw: **both the trigger condition and the target condition use the current value of `free_addr`, but `free_addr` can change between cycles.**

Here is the sequence of events:

| Cycle | Time | Event |
|-------|------|-------|
| 1 | 10 | `free=1`, `free_addr=8` (registered from inputs). `busy_8=0`, so no trigger. |
| 2 | 20 | `free=1`, `free_addr=0` (free address changed to 0). `busy_0=1` (just allocated in this cycle). **Trigger fires**: `free && busy(free_addr)` = `1 && busy(0)` = 1. The design schedules `busy(0) := false.B` for next cycle. Also `alloc=1` schedules `busy(1) := true.B`. |
| 3 | 30 | **Design correctly frees buffer 0**: `busy_0=0`. But `free_addr=1` (changed!). The liveness target `!busy(free_addr)` checks `!busy(1)` = 0 because buffer 1 was just allocated. The assertion incorrectly sees the target as unmet, even though buffer 0 was freed correctly. |
| 4 | 40 | `timer=1`, `free_addr=1`, `busy_1=1`. Target `!busy(1)` = 0. |
| 5 | 50 | `timer=2`, `free=1` again, `free_addr=1`, `busy_1=1`. Target still 0. |
| 6 | 60 | `timer=3`, assertion fails. |

### Why This Is an Assertion Bug

The design correctly implements the intended behavior:
- When `free=1` at time 20 with `free_addr=0`, it schedules `busy(0) := false.B`
- At time 30, `busy_0` becomes 0 — the buffer is successfully freed

But the assertion checks `!busy(free_addr)` where `free_addr` has changed from 0 to 1. The assertion should **latch the address** at the time the trigger fires and check that specific address, not the current value of `free_addr`.

### The Same Bug Exists in `alloc_progress`

The `alloc_progress` assertion has the identical issue:
```scala
astRelaxedLiveness(alloc && !io.nack, busy(io.alloc_addr), 3, "alloc_progress")
```
Here `io.alloc_addr` (combinational priority encoder output) can change between cycles when the trigger fires and when the target is checked, because the allocation changes the busy set which feeds back into the priority encoder.

### How to Fix

The assertion needs to capture (latch) the `free_addr` value when the trigger first fires, and then check that specific latched address in the target. For example:

```scala
val free_addr_latched = RegEnable(free_addr, free && busy(free_addr))
astRelaxedLiveness(free && busy(free_addr), !busy(free_addr_latched), 3, "free_progress")
```

Or more simply, since the design correctly frees the buffer in one cycle, the bound of 1 would also work, though the real fix is to latch the address.

### Evidence Summary

The waveform clearly shows:
1. **Time 20**: `free=1`, `free_addr=0`, `busy_0=1` — trigger fires
2. **Time 30**: `busy_0=0` — **buffer 0 correctly freed** (one cycle later)
3. **Time 30**: `free_addr=1`, `busy_1=1` — but the assertion checks `!busy(1)=0`, missing the successful free
4. The assertion's timer (0→1→2→3) counts up because the target condition `!busy(free_addr)` is never satisfied, even though the actual freed buffer (buffer 0) is correctly non-busy from time 30 onward.
