# Counterexample Analysis Report: `buffer_alloc.alloc_progress`

## 1. Verification Environment

- **Top Module**: `buffer_alloc` (from `llmverify` package)
- **Design Under Test**: A 16-entry buffer allocator with `alloc` and `free` ports
- **Key Components**:
  - `busy[0..15]`: 16 flip-flops tracking which buffers are allocated
  - `count`: 5-bit counter tracking number of allocated buffers
  - `alloc`, `free`, `free_addr`: registered input signals
  - `io.nack`: asserted when `alloc` is high but all 16 buffers are busy
  - `io.alloc_addr`: priority encoder output selecting the lowest free buffer
- **Stimulus**: Formal verification tool drives `io.alloc_raw`, `io.free_raw`, and `io.free_addr_raw` as free inputs (no constraints limiting their behavior)

## 2. Violated Assertion

- **Assertion Name**: `alloc_progress` (from waveform file: `buffer_alloc.alloc_progress.fst`)
- **File**: `bufferAlloc.scala`, line 84
- **Code**:
  ```scala
  astRelaxedLiveness(alloc && count < 16.U, io.nack || count_increased || !alloc, 4, "alloc_progress")
  ```
- **Supporting signals** (line 80–81):
  ```scala
  val prev_count = RegNext(count)
  val count_increased = count > prev_count
  ```
- **Comment** (line 76–78):
  ```scala
  // Bounded liveness: when alloc is requested and buffers are free,
  // the request makes progress within 4 cycles (either count increases,
  // nack fires, or alloc request is serviced/deasserted)
  ```
- **Property Description (expected)**: Whenever an allocation is requested (`alloc = true`) and free buffers exist (`count < 16`), then within 4 clock cycles, either:
  1. A nack fires (all buffers busy), or
  2. The count increases (a net new buffer was allocated), or
  3. The allocation is serviced (a buffer was successfully allocated), or
  4. The allocation request is deasserted (`alloc = false`)

- **Property Description (actual code)**: Only checks (1), (2), and (4) — missing the "alloc serviced" case (3).

## 3. Waveform Information

- **Waveform File**: `verilog/extra_bench/bufal_bufferAlloc/buffer_alloc.alloc_progress.fst`
- **Duration**: 9 cycles (0–90 ns)
- **Failure Point**: Time = 80 ns (timer reaches value 4, `alloc_progress` transitions from 1 to 0)

### Key signal timeline:

| Time (ns) | alloc | free | free_addr | count | prev_count | count_incr | io.nack | busy[0] | busy[1] | timer | alloc_progress |
|-----------|-------|------|-----------|-------|------------|------------|---------|---------|---------|-------|----------------|
| 0         | 0     | 0    | 0         | 0     | 0          | false      | 0       | 0       | 0       | 000   | 1              |
| 10        | 1     | 1    | 1111      | 0     | 0          | false      | 0       | 0       | 0       | 000   | 1              |
| 20        | 1     | 1    | 0000      | 1     | 0          | true       | 0       | 1       | 0       | 000   | 1              |
| 30        | 1     | 1    | 0001      | 1     | 1          | false      | 0       | 0       | 1       | 000   | 1              |
| 40        | 1     | 1    | 0000      | 1     | 1          | false      | 0       | 1       | 0       | 000   | 1              |
| 50        | 1     | 1    | 0001      | 1     | 1          | false      | 0       | 0       | 1       | 001   | 1              |
| 60        | 1     | 1    | 0000      | 1     | 1          | false      | 0       | 1       | 0       | 010   | 1              |
| 70        | 1     | 1    | 0001      | 1     | 1          | false      | 0       | 0       | 1       | 011   | 1              |
| **80**    | 1     | 1    | 0000      | 1     | 1          | false      | 0       | 1       | 0       | **100** | **0**        |

### Input signals (driven by formal tool):
- `io.alloc_raw`: always 1 (constant high)
- `io.free_raw`: always 1 (constant high)
- `io.free_addr_raw`: alternates 1111 → 0000 → 0001 → 0000 → 0001 → ... (toggles between 0 and 1 after first cycle)

## 4. Root Cause Analysis

### Error Type: **Assertion Error**

The bug is in the assertion's **progress condition** — it is missing the "alloc serviced" clause that the comment itself describes.

### Evidence

**A. Comment vs. Code Mismatch** (lines 76–84 of `bufferAlloc.scala`):

The comment explicitly states the progress condition should include **"alloc request is serviced/deasserted"** (emphasis on "serviced"):

```scala
// the request makes progress within 4 cycles (either count increases,
// nack fires, or alloc request is serviced/deasserted)
```

But the actual assertion only implements the **"deasserted"** part (`!alloc`):

```scala
astRelaxedLiveness(alloc && count < 16.U, io.nack || count_increased || !alloc, 4, "alloc_progress")
```

There is no `(alloc && !io.nack)` clause to capture the "serviced" case.

**B. Waveform evidence of the false failure:**

1. **Trigger condition stays true**: `alloc = 1` and `count = 1 < 16` throughout (time 20–80). The formal tool drives alloc permanently high and count is always 1 because exactly one buffer is freed and one is allocated each cycle.

2. **Progress condition never fires**:
   - `io.nack = 0` always (count ≠ 16)
   - `count_increased = false` after time 20 (count stays at 1 because `alloc && !io.nack` and `free && busy(free_addr)` cancel out)
   - `!alloc = 0` always (alloc is permanently 1)

3. **Yet allocation IS being serviced every cycle**: At every cycle from time 20 onward, `alloc && !io.nack = 1 && 1 = 1`, so `busy(io.alloc_addr) := true.B` executes. The priority encoder picks the lowest free buffer (alternating between buffer 0 and buffer 1 as they are freed and re-allocated). The count stays at 1 because each alloc frees one buffer and allocates another.

4. **Timer increments to failure**: Since the progress condition is never satisfied, the `astRelaxedLiveness` timer counts from 0 (time 40) → 1 (50) → 2 (60) → 3 (70) → 4 (80), causing the assertion to fail at time 80.

**C. Why this is not a DUT bug:**

The DUT correctly handles the scenario where alloc and free fire simultaneously every cycle:
- It frees the buffer specified by `free_addr`
- It allocates the lowest free buffer via the priority encoder
- The count accurately reflects the number of busy buffers (count = PopCount(busy))
- The design's **other assertions all pass**: `count_eq_popcount`, `count_upper_bound`, `alloc_addr_is_free`, `no_false_nack`

**D. Why this is not a setup error:**

While the formal tool drives `alloc_raw = 1` and `free_raw = 1` permanently, this is a legal input combination. The DUT should handle sustained alloc/free traffic. There are no input constraints (e.g., `assume(alloc_raw)` or `assume(!alloc_raw || ...)`) to restrict this behavior, so the tool is free to explore this valid scenario.

### Root Cause

**Line 84 of `bufferAlloc.scala`**: The `astRelaxedLiveness` progress condition is missing the `(alloc && !io.nack)` clause. When `alloc` stays asserted and a simultaneous `free` prevents `count` from increasing, the assertion fails despite the DUT correctly servicing every allocation request.

### Recommended Fix

Change the assertion on line 84 to include the "alloc serviced" condition:

```scala
astRelaxedLiveness(alloc && count < 16.U, io.nack || count_increased || (alloc && !io.nack) || !alloc, 4, "alloc_progress")
```

This adds `(alloc && !io.nack)` — meaning "allocation request was serviced (a buffer was successfully allocated)" — to the progress condition. This matches the intent described in the comment ("alloc request is serviced/deasserted") and correctly captures the steady-state scenario where alloc and free are balanced.
