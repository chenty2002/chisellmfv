# Counterexample Analysis Report: `iqc.entry0_progress`

## 1. Verification Environment

- **Top Module**: `iqc` (package `llmverify`)
- **Source File**: `iqc.scala` (138 lines)
- **Design Under Test**: An instruction queue controller (IQC) with 3 entries that manages instruction dispatch, issue, and flush logic. The design tracks which entries are valid, their relative ages (`qAge`), and which entries have their operands ready.
- **Key Components**:
  - `valid` (3-bit register): Tracks which entries hold valid instructions
  - `qAge` (3-bit register): Age-priority matrix for 3 entries
  - Issue selection logic: Routes ready entries to execution units (0 and 1)
  - Load logic: Routes incoming instructions to free entries
  - Liveness monitor: Timer that fires if a ready entry does not issue within 8 cycles

## 2. Violated Assertion

- **Full Assertion Name**: `entry0_progress`
- **Assertion Kind**: Liveness/progress (`astRelaxedLiveness`)

### Code Snippet (iqc.scala, lines 125-131):
```scala
astRelaxedLiveness(
  valid(0) && io.opsReady(0),           // trigger condition
  issue0_0 || issue1_0 || !valid(0) || !io.opsReady(0),  // target condition
  8,                                      // bound (cycles)
  "entry0_progress"
)
```

### Property Description:
If a valid entry (entry 0) has its operands ready (`io.opsReady(0)` is high), then within 8 cycles, one of the following must occur:
1. The entry issues to an execution unit (`issue0_0 || issue1_0`)
2. The entry becomes invalid (`!valid(0)`)
3. The operands are no longer ready (`!io.opsReady(0)`)

### File Location:
- `iqc.scala`, line 125-131

## 3. Waveform Information

- **Waveform File**: `verilog/extra_bench/ibuf/iqc.entry0_progress.fst`
- **Waveform Duration**: 0 ns to 110 ns (11 cycles)
- **Clock Period**: 10 ns

### Timeline of Key Events:

| Time (ns) | Cycle | Event |
|-----------|-------|-------|
| 0 | 0 | System reset. `valid=000`, `flush=010` (entry 1 flush active), `exeReady=00`, `opsReady=000`, `iqLoads=11` |
| 10 | 1 | `valid(0)` becomes 1 (entry 0 loaded). `opsReady(0)` becomes 1. `nv0=1`. Timer starts counting. `exeReady` remains `00`. |
| 10-90 | 1-9 | Timer increments each cycle (1→7). `valid(0)` stays 1. `opsReady(0)` stays 1. `exeReady` stays `00`. `flush(0)` stays `0`. No issue signals ever fire. |
| 80 | 8 | `flush` deasserts to `000` briefly |
| 90 | 9 | `flush=010` again (entry 1 flush). `valid=011` (entry 1 becomes valid briefly). |
| 100 | 10 | `valid=001` (entry 1 flushed out). Timer reaches `1000` (8 cycles elapsed). **Assertion failure triggers.** |
| 110 | 11 | Assertion violation confirmed at endpoint. |

### Critical Signal Values at Failure Point (time = 100 ns / cycle 10):

| Signal | Value | Meaning |
|--------|-------|---------|
| `valid[0]` | 1 | Entry 0 still valid |
| `opsReady[0]` | 1 | Entry 0 operands still ready |
| `exeReady[1:0]` | 00 | **Neither execution unit ever ready** |
| `flush[0]` | 0 | Entry 0 never flushed |
| `issue0[2:0]` | 000 | No entry issues to exe unit 0 |
| `issue1[2:0]` | 000 | No entry issues to exe unit 1 |
| `timer[3:0]` | 1000 (8) | Timer expired after 8 cycles |
| `pending` | 1 | Liveness monitor active |
| `nv0` | 1 | Next valid(0) remains 1 |

## 4. Root Cause Analysis

### Error Classification: **Setup Error (Incorrect Top Module Configuration)**

### Root Cause:

The `io.exeReady` input signal is **completely unconstrained** — it remains `00` for the entire 11-cycle simulation. This means neither execution unit ever signals readiness to accept an instruction.

### Why This Causes the Assertion to Fail:

The issue logic for entry 0 requires `exeReady` to issue:

```scala
val issue0_0 = io.exeReady(0) & io.opsReady(0) & valid(0) & ...
val issue1_0 = io.exeReady(1) & io.opsReady(0) & valid(0) & ...
```

Since `io.exeReady(0) = 0` and `io.exeReady(1) = 0` at all times:
- `issue0_0` can never become true
- `issue1_0` can never become true

The three escape conditions for the liveness assertion are all blocked:
1. **Entry cannot issue** (because exeReady is never asserted)
2. **Entry cannot become invalid** (because `io.flush(0)` stays 0 throughout, and the entry stays valid)
3. **opsReady(0) never goes low** (it stays 1 from cycle 1 onwards)

Without any way to satisfy the target condition, the timer monotonically increments to 8 and the assertion fails.

### Evidence:

1. **`io.exeReady` trace**: `iqc.io_exeReady [1:0]` has only one change at time 0 (from 0 to 0) — it is `00` for the entire simulation.
2. **No issue signals**: Both `iqc.io_issue0 [2:0]` and `iqc.io_issue1 [2:0]` remain `000` throughout — no entries ever issue.
3. **No flush for entry 0**: `iqc.io_flush [2:0]` is `010` at times 0, 80-90, meaning `flush(0)` is always 0 — entry 0 cannot be flushed.
4. **Timer expires**: `iqc.timer [3:0]` increments from 0 to 8 by time 100 ns, confirming the 8-cycle bound was reached.

### The Fix:

The formal verification setup for the `iqc` module must add constraints on the `exeReady` input. Specifically, the environment should either:
1. Assert `exeReady` within a reasonable time to allow instructions to issue, or
2. Arrange for `flush(0)` to eventually clear entry 0 if no execution unit is available, or
3. Assert `opsReady(0)` to de-assert when operands become unavailable.

Without such constraints, the liveness property is vacuously impossible to satisfy — this is a testbench/environment bug, not a design bug.
