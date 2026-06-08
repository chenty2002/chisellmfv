# Counterexample Analysis Report: iqc.slot0_progress_valid_ready_issued_within_16

## 1. Verification Environment

- **Top module**: `iqc` (Instruction Queue Controller)
- **Source file**: `iqc.scala` (package `llmverify`)
- **Components**: 
  - 3-entry instruction queue with `valid` bits and age tracking (`qAge`)
  - Dual-issue logic (2 execution units) with oldest-first arbitration
  - Dual-dispatch (2 dispatch ports) with low-index preference
  - Operand readiness (`opsReady`) and execution unit readiness (`exeReady`) as inputs
  - Formal verification using `chiselFv` with `astRelaxedLiveness` assertions
- **No TestTop/harness found in waveform** — the inputs appear to be unconstrained formal variables, meaning the formal tool can freely choose any value for `opsReady`, `exeReady`, `flush`, and `iqLoads` without any fairness restrictions.

## 2. Violated Assertion

**Full assertion name**: `slot0_progress_valid_ready_issued_within_16`

**Code snippet** (iqc.scala, lines 110-114):
```scala
astRelaxedLiveness(
    valid(0) & io.opsReady(0),
    io.issue0(0) | io.issue1(0) | !valid(0),
    16,
    "slot0_progress_valid_ready_issued_within_16"
)
```

**Natural language description**:  
If slot 0 of the instruction queue holds a valid instruction AND its operands are ready (`valid(0) & io.opsReady(0)`), then within 16 cycles, one of the following must happen:
- The instruction is issued to execution unit 0 (`io.issue0(0)`)
- The instruction is issued to execution unit 1 (`io.issue1(0)`)
- The instruction becomes invalid/flushed (`!valid(0)`)

**File location**: `iqc.scala`, lines 110-114

## 3. Waveform Information

- **Waveform file**: `verilog/extra_bench/ibuf/iqc.slot0_progress_valid_ready_issued_within_16.fst`
- **Duration**: 190 ns (19 cycles at 10 ns/cycle)
- **Failure time**: 180 ns (timer value reaches 16, exceeding the 16-cycle bound)

### Key time points and signal values

| Time (ns) | Cycle | valid[2:0] | opsReady[2:0] | exeReady[1:0] | flush[2:0] | issue0[2:0] | pending | timer[4:0] | Event |
|-----------|-------|------------|---------------|---------------|------------|-------------|---------|------------|-------|
| 0         | 0     | 000        | 000           | 00            | 010        | 000         | 0       | 00000      | Initial state; flush(1)=1 |
| 10        | 1     | **001**    | **001**       | 00            | 010        | 000         | 0       | 00000      | **Trigger fires**: valid(0)=1, opsReady(0)=1; nextPending=1 |
| 20        | 2     | 001        | 001           | 00            | 010        | 000         | **1**   | 00000      | pending registered |
| 30        | 3     | 001        | 001           | 00            | 010        | 000         | 1       | **00001**  | timer starts counting |
| 40        | 4     | 001        | 001           | 00            | 010        | 000         | 1       | 00010      | timer increments |
| 50-90     | 5-9   | 001        | 001           | 00            | 010        | 000         | 1       | 00011-00111| valid&opsReady both 1, but **exeReady(0)=0 throughout** |
| **100**   | **10**| 001        | **000**       | 00            | 010        | 000         | 1       | 01000      | **opsReady(0) DE-ASSERTS!** |
| 120       | 12    | 001        | 000           | 00            | **000**    | 000         | 1       | 01010      | flush de-asserts globally |
| 130       | 13    | 111        | 010           | **01**        | 000        | 010         | 1       | 01011      | exeReady(0)=1 but opsReady(0)=0 (slot 1 ready, not slot 0) |
| 150       | 15    | 001        | 000           | 00            | 000        | 000         | 1       | 01101      | exeReady goes back to 0 |
| 170       | 17    | 001        | 000           | 00            | 000        | 000         | 1       | 01111      | Still pending, no issue |
| **180**   | **18** | **011**    | 000           | 00            | 000        | 000         | 1       | **10000**  | **ASSERTION FAILS**: timer=16 |

## 4. Root Cause Analysis

### Classification: **Setup Error (TestTop configuration — missing input constraints)**

### Problem summary

The counterexample is caused by a **lack of fairness constraints on the `io_opsReady` input**. The formal tool (which drives `io_opsReady` as a free/unconstrained input) chooses to de-assert `opsReady(0)` from 1 to 0 at time 100 ns, stranding slot 0 and causing the liveness assertion to fail.

### Detailed explanation

The liveness property checks: if `valid(0) & opsReady(0)` fires, then within 16 cycles the instruction must be issued or invalidated. In the counterexample:

1. **At time 10 (cycle 1)**: `valid(0)=1` and `opsReady(0)=1` — the trigger fires, starting the 16-cycle countdown.

2. **At times 10-90 (cycles 1-9)**: Both `valid(0)` and `opsReady(0)` remain true, but **`exeReady(0)=0`** throughout this entire period. The issue logic requires `io.exeReady(0)` to be 1 for issuing (see lines 47-48 of `iqc.scala`):
   ```scala
   val issue0_0 = io.exeReady(0) & io.opsReady(0) & valid(0) & ...
   ```
   Since `exeReady(0)=0`, no issue can happen even though the instruction is ready. The design **correctly** prevents issue when the execution unit is not ready.

3. **At time 100 (cycle 10)**: **`opsReady(0)` de-asserts from 1 to 0** while `valid(0)` remains 1. This is the critical event. Now the instruction cannot be issued even if `exeReady(0)` becomes available, because the issue logic also requires `io.opsReady(0)`.

4. **At time 130 (cycle 13)**: `exeReady(0)` finally becomes 1, but `opsReady(0)=0`, so the instruction still cannot be issued.

5. **At time 180 (cycle 18)**: The 5-bit timer overflows to 16 (`10000`), and the assertion fails.

### Why this is a setup error (not a design bug)

- **The design issue logic is correct**: It correctly gates issue on `opsReady(0)`, `exeReady(0)`, `valid(0)`, age-based priority, and mutual exclusion. All these are proper semantics for an instruction queue.

- **The assertion is reasonable**: If an instruction's operands are ready AND it's valid, then within a bounded time it should be issued or flushed.

- **The environment is unrealistic**: In a real processor, once an instruction's operands become ready (`opsReady(i)=1`), they **stay ready** until the instruction is issued / consumed. The signal `opsReady` represents data readiness from a wakeup logic or reservation station — it does not spontaneously de-assert. The formal tool, however, is free to toggle this signal arbitrarily because no constraint/assumption is provided.

### Required fix

Add an **assumption** in the formal verification environment that constrains `opsReady` to be monotonic / stable when a valid instruction is not issued. For example:

```scala
// Once opsReady(i) is true for a valid slot that hasn't been issued,
// opsReady(i) stays true until the instruction is issued.
fvAssume(
  (valid(0) & io.opsReady(0) & ~(io.issue0(0) | io.issue1(0))) 
    |-> next(io.opsReady(0)),
  "opsReady0_stable_until_issue"
)
```

Alternatively, a simpler constraint that captures the intended behavior:

```scala
// If opsReady was true and the instruction wasn't issued, opsReady stays true
fvAssume(
  past(io.opsReady(0) & valid(0) & ~(io.issue0(0) | io.issue1(0))) 
    |-> io.opsReady(0),
  "opsReady0_no_spontaneous_deassert"
)
```

Or more broadly, constrain that `opsReady` does not glitch/spontaneously de-assert:

```scala
// Fairness: opsReady stays asserted once true until consumed
for (i <- 0 until 3) {
  fvAssume(
    past(io.opsReady(i) & valid(i) & ~(io.issue0(i) | io.issue1(i))) |-> io.opsReady(i),
    s"opsReady${i}_stable_until_issue"
  )
}
```

This assumption reflects real hardware behavior and would prevent the spurious counterexample while allowing the real liveness property to be verified.

### Correctness of the design logic

Note that the design logic itself is **not buggy**. Let me verify:

- `nv0 = ~io.flush(0) & (valid(0) & ~(issue0_0 | issue1_0) | io.load0.orR)` (line 60): When `issue0_0=0`, `issue1_0=0`, `load0.orR=0`, and `flush(0)=0`, then `nv0 = valid(0)`. This means `valid(0)` stays 1 — correct, the valid bit should not spontaneously clear.

- `issue0_0 = io.exeReady(0) & io.opsReady(0) & valid(0) & ...` (line 47): Requires both `exeReady(0)` AND `opsReady(0)` — correct, both conditions must be satisfied for issue.

The design correctly implements an instruction queue that waits for both operand readiness and execution unit availability. The failure occurs solely because the test environment allows `opsReady` to de-assert spontaneously, which no real hardware would do.
