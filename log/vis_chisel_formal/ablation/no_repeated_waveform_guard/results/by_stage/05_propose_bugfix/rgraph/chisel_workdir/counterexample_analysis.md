# Counterexample Analysis Report: `rgraph.mode_stays_one`

## 1. Verification Environment

| Item | Description |
|------|-------------|
| **Top Module** | `rgraph` (Chisel module with `Formal` mixin) |
| **Source File** | `rgraph.scala`, package `llmverify` |
| **Key Components** | `cnt` (12-bit counter), `mode` (1-bit state register), `io.i`/`io.o` (I/O bundle) |
| **Design Description** | The design implements a down-counter with a mode register. When `mode=0`, the counter increments. When `mode=1` and `io.i=1` and `cnt≠0`, the counter decrements. The output `io.o` is high when `cnt=0`. The `mode` register transitions from 0 to 1 when `mode=0 && io.i=1`. There is NO logic to transition `mode` back from 1 to 0. |
| **Reset Counter** | `resetCounter` module starts with `flag=1`, `count=0`, `notChaos=1` at time 0, meaning `hasBeenReset` is asserted immediately from cycle 0. |

## 2. Violated Assertion

**Full Assertion Name**: `mode_stays_one`

**Assertion Source Code** (rgraph.scala, lines 32-33):
```scala
// 1. Mode monotonicity: once mode becomes 1, it stays 1 forever (never resets to 0)
assertImpliesDelay(mode === 1.U, mode === 1.U, 1, "mode_stays_one")
```

**Property Being Checked**: `mode === 1.U |=> ##1 mode === 1.U`

In natural language: "If `mode` equals 1 at any clock cycle, then `mode` MUST also equal 1 at the **next** clock cycle." This property ensures mode monotonicity — once `mode` becomes 1, it should stay 1 forever.

## 3. Waveform Information

| Item | Value |
|------|-------|
| **Waveform File** | `verilog/extra_bench/rgraph/rgraph.mode_stays_one.fst` |
| **Time Range** | 0 ns → 10 ns (1 clock cycle, period=10ns) |
| **Clock Events** | Rising edge at 0ns, falling edge at 5ns. Next rising edge at 10ns is NOT captured (trace truncated). |

### Critical Signal Values Throughout the Trace

| Signal | Time 0ns | Time 5ns | Time 10ns |
|--------|----------|----------|-----------|
| `rgraph.clock` | 1 (rising edge) | 0 (falling edge) | 0 |
| `rgraph.mode` | 0 | 0 | 0 |
| `rgraph.io_i` | 1 | 1 | 1 |
| `rgraph.cnt [11:0]` | 0x000 | 0x000 | 0x000 |
| `rgraph.io_o` | 1 | 1 | 1 |
| `rgraph.mode_stays_one` | 1 | 1 | 1 |
| `rgraph.hasBeenReset` | 1 | 1 | 1 |
| `rgraph.hasBeenResetReg` | 1 | 1 | 1 |
| `rgraph.reset` | 0 | 0 | 0 |

**Key Observation**: All signals are **completely stable** across the entire trace. There are zero transitions on `mode`, `cnt`, `io_i`, `io_o`, or any other signal. The only transition is the clock falling from 1→0 at 5ns.

## 4. Root Cause Analysis

### Classification: **Setup Error / Trace Truncation Issue**

#### Detailed Analysis

**The assertion is correct.** The property `mode === 1.U |=> ##1 mode === 1.U` correctly captures the design's intended mode-monotonicity behavior.

**The design is correct.** Examining the Chisel source code (rgraph.scala, lines 21-28):

```scala
when(mode === 0.U) {
    cnt := cnt + 1.U
}.otherwise {
    when(io.i && (cnt =/= 0.U)) {
        cnt := cnt - 1.U
    }
}

when(mode === 0.U && io.i) {
    mode := 1.U
}
```

The `mode` register is only assigned in the clause `when(mode === 0.U && io.i) { mode := 1.U }`. There is NO `elsewhen`, `.otherwise`, or other assignment that can set `mode` back to 0. Once `mode` transitions from 0 to 1, it remains 1 indefinitely. The design is **correct** — the assertion should always pass.

**The trace does not demonstrate a failure.** Throughout the 1-cycle trace:
- `mode = 0` at all times
- Since the premise `mode === 1.U` is false at the rising edge (time 0), the implication `mode === 1.U |=> ##1 mode === 1.U` is **vacuously true** (passing)
- The `mode_stays_one` assertion checker output is `1` at all times, consistent with a passing assertion

**Root Cause**: The waveform trace is truncated to only 1 clock cycle (0–10ns). The trace shows the initial state where `mode=0`, but does not show the subsequent cycles where:
1. `mode` would transition to `1` (at cycle 1, because `io_i=1` enables the transition)
2. The assertion premise would become active
3. The assertion would be meaningfully tested (and verified to pass)

### Why This Is Not a Bug in the Design or Assertion

| Category | Assessment |
|----------|------------|
| **Design Bug** | ❌ No — the design correctly implements monotonic `mode` without a reset-to-0 path |
| **Assertion Error** | ❌ No — `assertImpliesDelay(mode === 1.U, mode === 1.U, 1, "mode_stays_one")` correctly checks mode monotonicity |
| **Setup Error** | ✅ The trace is truncated to 1 cycle, insufficient to exercise the assertion's premise or demonstrate any meaningful failure |

### Additional Note on Other Assertions

The design also contains two additional assertions:

1. **`mode_transitions_on_input`**: `assertNextStepWhen(mode === 0.U && io.i, mode === 1.U, ...)` — Checks that when `mode=0` and `io=1`, mode becomes 1 next cycle. Given `io_i=1` and `mode=0` throughout the trace, this assertion's premise is active at cycle 0. The conclusion `mode === 1.U` would be checked at cycle 1, which is outside the trace window, suggesting the trace truncation also affects this assertion.

2. **`output_equals_cnt_is_zero`**: `fvAssert(io.o === (cnt === 0.U), ...)` — Checks that `io.o` equals `(cnt === 0)`. At all times in the trace, `cnt=0` and `io_o=1`, so `1 === (0 === 0)` = `1 === 1` = true. This assertion passes correctly.
