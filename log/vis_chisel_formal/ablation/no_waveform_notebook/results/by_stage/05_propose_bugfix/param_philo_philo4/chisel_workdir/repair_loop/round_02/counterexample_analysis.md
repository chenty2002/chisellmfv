# Counterexample Analysis Report: `liveness_ph0_hungry_eventually_eats`

## 1. Verification Environment

### Top Module Structure
- **Top Module**: `Philo4` (extends `Module with Formal`)
- **Design Under Test**: Four `Philosopher` modules (`ph0`, `ph1`, `ph2`, `ph3`) arranged in a ring topology (classic dining philosophers)
- **Key Connections**:
  - `ph0` left ← `ph3.io.out` , right ← `ph1.io.out`
  - `ph1` left ← `ph0.io.out` , right ← `ph2.io.out`
  - `ph2` left ← `ph1.io.out` , right ← `ph3.io.out`
  - `ph3` left ← `ph2.io.out` , right ← `ph0.io.out`
- **Initial States**: `ph0` starts as READING (01), `ph1`/`ph2`/`ph3` start as THINKING (00)
- **Nondeterminism**: Each philosopher has an external `coin` input controlling transitions

### Philosopher State Encoding
| State    | Value |
|----------|-------|
| THINKING | 00    |
| READING  | 01    |
| EATING   | 10    |
| HUNGRY   | 11    |

## 2. Violated Assertion

- **Assertion Name**: `liveness_ph0_hungry_eventually_eats`
- **File Location**: `philo4.scala`, lines 155-159
- **Code Snippet**:
  ```scala
  astRelaxedLiveness(
    io.st0 === PhilosopherState.HUNGRY,
    io.st0 === PhilosopherState.EATING,
    200,
    "liveness_ph0_hungry_eventually_eats")
  ```
- **Property Description**: Whenever philosopher 0 (`io.st0`) enters the HUNGRY state, it must eventually enter the EATING state within 200 clock cycles. This is a bounded liveness (progress) property.

## 3. Waveform Information

- **Waveform File**: `verilog/extra_bench/param_philo_philo4/Philo4.liveness_ph0_hungry_eventually_eats.fst`
- **Duration**: 204 cycles (0–2040 ns)
- **Clock Period**: 10 ns (rising edges at 0, 10, 20, 30, ...)

### Key Time Points and Signal Values

| Time (ns) | ph0 (st0) | ph1 (st1) | ph2 (st2) | ph3 (st3) | Event Description |
|-----------|-----------|-----------|-----------|-----------|-------------------|
| 0         | READING(01) | THINKING(00) | THINKING(00) | THINKING(00) | Initial/reset state |
| 10        | THINKING(00) | HUNGRY(11) | THINKING(00) | READING(01) | ph0 left READING (left=THINKING); ph1 became hungry |
| 20        | **HUNGRY(11)** | EATING(10) | READING(01) | THINKING(00) | ❌ ph0 becomes hungry; ph1 starts eating (blocks right fork) |
| 60        | HUNGRY(11) | EATING(10) | READING(01) | HUNGRY(11) | ph3 becomes hungry |
| 70        | HUNGRY(11) | EATING(10) | READING(01) | **EATING(10)** | ❌ ph3 starts eating (blocks left fork). **Both forks of ph0 are now taken** |
| 130       | HUNGRY(11) | THINKING(00) | READING(01) | EATING(10) | ph1 stops eating (right fork free), but ph3 still eating (left fork still blocked) |
| 140       | HUNGRY(11) | READING(01) | THINKING(00) | EATING(10) | ph1→READING; ph2→THINKING |
| 150       | HUNGRY(11) | READING(01) | HUNGRY(11) | EATING(10) | ph2 becomes hungry |
| 200–2000  | HUNGRY(11) | READING(01) | HUNGRY(11) | EATING(10) | **System is stuck** — no state changes for ~185 cycles |
| 2020      | HUNGRY(11) | READING(01) | HUNGRY(11) | EATING(10) | liveness signal still 1 (bound not yet expired? but actually bound is exceeded) |
| **2030**  | **HUNGRY(11)** | READING(01) | HUNGRY(11) | THINKING(00) | **Assertion fails** (value→0). ph3 finally stops eating, but too late! |

### Failure Point
- **Time**: 2030 ns (cycle 203)
- **Assertion Signal**: `Philo4.liveness_ph0_hungry_eventually_eats` transitions from 1→0 at time 2030
- **ph0 State**: Still HUNGRY (11) — has been hungry for 201 cycles without eating

## 4. Root Cause Analysis

### Bug Classification: **Design Bug (dut_bug)**

### Root Cause: The Philosopher module has no bound on how long it can stay in the EATING state

The critical transition logic in the `Philosopher` module (`philo4.scala`, lines 42-44):

```scala
.elsewhen(self === PhilosopherState.EATING) {
    when(io.coin) {
      self := PhilosopherState.THINKING
    }.otherwise {
      self := PhilosopherState.EATING   // <-- indefinite EATING when coin=0
    }
}
```

When `coin = 0`, the philosopher **remains in EATING indefinitely** with no upper bound. There is no timeout or counter mechanism to force a transition.

### Causal Chain Leading to Assertion Failure

1. **Cycle 0** (time 0): `ph0` starts as READING; `ph3` starts as THINKING.
2. **Cycle 1** (time 10): `ph0` transitions THINKING (since left neighbor `ph3` is THINKING). `ph3` transitions READING (since right neighbor `ph0` was READING).
3. **Cycle 2** (time 20): `ph0` becomes **HUNGRY** (since `io.right=ph1=HUNGRY`, not READING, and `coin0=0`). `ph1` becomes **EATING** (since both neighbors not EATING). This is the starting point of the liveness timer.
4. **Cycle 2–13** (time 20–130): `ph1` remains **EATING** (coin1=0), blocking `ph0`'s right fork.
5. **Cycle 7** (time 70): `ph3` becomes **EATING** (became HUNGRY at time 60, then both neighbors not EATING). This blocks `ph0`'s **left fork**.
6. **Cycle 13** (time 130): `ph1` finally stops eating (coin1=1 at time 120). But `ph3` is **still EATING** and will remain so for 186 more cycles.
7. **Cycles 13–203** (time 130–2030): `ph3` stays EATING because `coin3=0` continuously from time 50 to time 2020. `ph3.io_out` at time 70 is EATING(10), `ph0.io_left`=10(EATING), blocking ph0's HUNGRY→EATING transition.
8. **Cycle 203** (time 2030): At the rising edge, `ph3` finally transitions to THINKING (coin3=1 at time 2020). However, **ph0 has now been HUNGRY for 201 cycles**, exceeding the 200-cycle liveness bound. The assertion fails.

### Deadlock-like Stable State (cycles 15–202)

From time 150 to time 2000, the system is locked in a stable configuration:

| Philosopher | State    | Why? |
|-------------|----------|------|
| **ph0**     | HUNGRY   | Blocked by ph3=EATING (left fork held) |
| **ph1**     | READING  | Left neighbor ph0=HUNGRY (not THINKING), so READING condition `io.left===THINKING` is false — stays READING |
| **ph2**     | HUNGRY   | Blocked by ph3=EATING (right fork held) |
| **ph3**     | EATING   | coin3=0 → stays EATING indefinitely |

This is effectively a **starvation scenario**: philosopher 3 hogs both shared forks (with ph0 and ph2) for an unbounded period, starving its neighbors.

### Fix Recommendation

The Philosopher module needs a **bounded eating duration** mechanism. Two approaches:

**Approach A — Fairness Constraints on Coin Inputs** (less invasive):
Add formal assumptions that each coin input must eventually be asserted (fairness), preventing the solver from holding coin=0 indefinitely. This would be done in the `Philo4` top module using `astAssume` or similar constructs.

**Approach B — Design-Level Timeout** (more robust):
Modify the `Philosopher` module to include a counter that limits the maximum time spent in EATING. For example:
```scala
val eatCounter = RegInit(0.U(8.W))
// ... in EATING state handling:
when(self === PhilosopherState.EATING) {
  eatCounter := eatCounter + 1.U
  when(eatCounter >= maxEatCycles.U || io.coin) {
    self := PhilosopherState.THINKING
    eatCounter := 0.U
  }
}
```

Approach A is simpler and addresses the verification gap. Approach B makes the design itself starvation-free regardless of external inputs.

### Buggy Code Location

- **File**: `philo4.scala`
- **Lines**: 42–44 (EATING state transition in `Philosopher` class)
- **Bug**: No bound on EATING duration when `coin=0`, allowing indefinite fork holding
