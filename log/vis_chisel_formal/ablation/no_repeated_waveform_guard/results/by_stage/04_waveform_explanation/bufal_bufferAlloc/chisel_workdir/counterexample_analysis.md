# Counterexample Analysis: buffer_alloc.buffers_eventually_freed

## 1. Verification Environment

- **Top module**: `buffer_alloc` (package `llmverify`)
- **Structure**: A 16-entry buffer allocator with priority encoder, count tracking, and busy vector
- **Key components**:
  - `busy(0..15)`: Registers tracking which buffers are allocated
  - `count`: 5-bit counter tracking total number of allocated buffers
  - `alloc`: Registered allocation request input
  - `free` / `free_addr`: Registered free request input/address
  - Priority encoder: Finds first free buffer for allocation
- **Design purpose**: Allocates unique buffer IDs to allocation requests, tracks busy buffers, and supports freeing buffers back to the pool

## 2. Violated Assertion

- **Assertion name**: `buffers_eventually_freed` (from waveform filename `buffer_alloc.buffers_eventually_freed.fst`)
- **Code snippet** (bufferAlloc.scala, line 81):
  ```scala
  astRelaxedLiveness(count === 16.U, count =/= 16.U, 100, "buffers_eventually_freed")
  ```
- **Natural language description**: When all 16 buffers become busy (`count === 16`), at least one buffer must be freed within 100 clock cycles (`count =/= 16.U` must become true within 100 cycles). This is a bounded liveness property.
- **File location**: `bufferAlloc.scala`, line 81

## 3. Waveform Information

- **Waveform file**: `verilog/extra_bench/bufal_bufferAlloc/buffer_alloc.buffers_eventually_freed.fst`
- **Waveform duration**: 0 ns → 1190 ns (119 cycles)
- **Key time points**:
  - **Time 10 ns**: `alloc` goes high (first posedge), starting sequential allocation of buffers 0-15
  - **Time 10-170 ns**: `count` increments each cycle from 0 → 16, one buffer allocated per cycle
  - **Time 170 ns**: `count = 16` (all buffers busy), `alloc` goes low, `free` goes low
  - **Time 170-1170 ns**: `count` stays at 16; the 100-cycle liveness timer runs
  - **Time 1180 ns**: Timer expired, assertion fires — no buffer was freed within 100 cycles

- **Critical signal values at failure point (time 1180 ns)**:
  - `count [4:0]` = `10000` (16 — all buffers busy)
  - `busy_0` through `busy_15` = all `1` (every buffer is allocated)
  - `alloc` = `0` (no new allocation requests)
  - `free` = `0` (no free requests)
  - `io_free_raw` = `0` (last went to 0 at time 160 ns)
  - `io_alloc_raw` = `0` (last went to 0 at time 160 ns)
  - `pending` = `1` (timer has expired, assertion about to fail)
  - `timer [6:0]` = `1100100` (100 — timer reached limit)

## 4. Root Cause Analysis

### Classification: Setup Error (incorrect test environment constraints)

### Root Cause

The formal verification **testbench does not constrain the free input signals** to ensure they target already-allocated buffers. The tool's input generator provides free requests that always target **unallocated** buffers, so the `count` never decrements.

### Detailed Trace

The `count` is updated by the following logic (bufferAlloc.scala, line 65):
```scala
count := count + (alloc && !io.nack).asUInt - (free && busy(free_addr)).asUInt
```

The count only decrements when `free && busy(free_addr)` is true — i.e., a free request targets a buffer that is currently busy.

**Sequence of free events and their (in)effectiveness:**

| Time | free | free_addr | free_addr targets | busy(free_addr) | Effect |
|------|------|-----------|-------------------|----------------|--------|
| 10-20 | 1 | 15 | Buffer 15 | 0 (not allocated yet) | No count decrement |
| 30-40 | 1 | 2 | Buffer 2 | 0 (allocated at time 30, but used same cycle) | No count decrement |
| 40-50 | 1 | 4 | Buffer 4 | 0 (not allocated until time 50) | No count decrement |
| 70-80 | 1 | 7 | Buffer 7 | 0 (not allocated until time 80) | No count decrement |
| 150-170 | 1 | 15 | Buffer 15 | 0 (not allocated until time 160) | No count decrement |

**Every single free request** targets a buffer that is **not yet busy** at the time of the request, so the condition `free && busy(free_addr)` is always `0` and the count monotonically increases from 0 to 16.

After time 170 ns:
- `io_free_raw` stays at `0` (last went to 0 at time 160 ns, never returns to 1)
- All 16 buffers are busy
- No further free requests arrive
- The liveness timer counts 100 cycles and expires

### Why This Is a Setup Error

The **DUT logic is correct**: freeing a buffer that is already free is correctly treated as a no-op (the count does not decrement). This is sensible hardware behavior.

The **assertion is correct**: it is a reasonable liveness property — when all buffers are busy, the system should eventually free one.

The **testbench setup is flawed**: the formal tool's input space for `io_free_raw` and `io_free_addr_raw` is unconstrained, allowing the tool to:
1. Provide free requests for wrong (unallocated) addresses that have no effect
2. Stop providing free requests entirely once all buffers are busy

### Required Fix

To fix this, **environment constraints (assumptions) must be added** to the formal testbench to constrain the free inputs. Two types of constraints are needed:

1. **Valid free target**: Free requests should only target already-allocated buffers:
   ```scala
   when(io_free_raw) {
     fvAssume(busy(io_free_addr_raw), "free_addr_must_be_busy")
   }
   ```

2. **No starvation** (optional, depending on property strictness): The environment should ensure that when all buffers are busy, a valid free request eventually arrives:
   ```scala
   fvAssume(!(count === 16.U) || io_free_raw, "free_when_all_busy")  // or a more refined constraint
   ```

Without these constraints, the liveness property is trivially violable by an adversarial environment.
