# Counterexample Analysis Report: `liveness_ph0_hungry_eventually_eats`

## 1. Verification Environment

- **Top Module**: `Philo4` (in `philo4.scala`, line 61)
- **Design**: Dining Philosophers ring with 4 philosopher modules connected in a ring
- **Key Components**:
  - Four `Philosopher` instances (`ph0`, `ph1`, `ph2`, `ph3`) with a 4-state machine (THINKING=00, READING=01, EATING=10, HUNGRY=11)
  - Ring topology: ph0.left=ph3, ph0.right=ph1, ph1.left=ph0, ph1.right=ph2, ph2.left=ph1, ph2.right=ph3, ph3.left=ph2, ph3.right=ph0
  - Four coin inputs (`coin0`–`coin3`) providing nondeterministic choice for state transitions
  - State outputs `st0`–`st3` exposing each philosopher's current state
- **Initial States**: ph0=READING, ph1=THINKING, ph2=THINKING, ph3=THINKING

## 2. Violated Assertion

- **Assertion Name**: `liveness_ph0_hungry_eventually_eats`
- **File Location**: `philo4.scala`, lines 141–145
- **Code**:
  ```scala
  astRelaxedLiveness(
    io.st0 === PhilosopherState.HUNGRY,
    io.st0 === PhilosopherState.EATING,
    200,
    "liveness_ph0_hungry_eventually_eats")
  ```
- **Property**: Whenever philosopher 0 (`io.st0`) is in the HUNGRY state, it must eventually transition to the EATING state within 200 clock cycles.

## 3. Waveform Information

- **Waveform File**: `Philo4.liveness_ph0_hungry_eventually_eats.fst`
- **Full Path**: `verilog/extra_bench/param_philo_philo4/Philo4.liveness_ph0_hungry_eventually_eats.fst`
- **Total Duration**: 204 cycles (2040 ns, 0→2040 ns)
- **Key Time Points**:

| Time (ns) | Event |
|-----------|-------|
| 0 | Initial state: ph0=READING, ph1=THINKING, ph2=THINKING, ph3=THINKING |
| 10 | ph0→THINKING, ph1→HUNGRY, ph2→HUNGRY, ph3→READING |
| 20 | **ph0→HUNGRY**, ph2→EATING |
| 30 | ph2→THINKING (coin2=1 → THINKING) |
| 40 | ph1→EATING, ph2→READING, ph3→THINKING |
| 60 | ph3→HUNGRY |
| 2030 | **Assertion fires**: timer=200 (0xC8), liveness signal goes to 0 |

- **State at Failure (time 2030)**:
  - `Philo4.ph0.self` = 11 (HUNGRY) — ph0 is stuck hungry
  - `Philo4.ph0.io_left` = 11 (HUNGRY — ph3 is hungry)
  - `Philo4.ph0.io_right` = 10 (EATING — ph1 is eating)
  - `Philo4.ph1.self` = 10 (EATING) — ph1 stuck in EATING
  - `Philo4.ph2.self` = 01 (READING)
  - `Philo4.ph3.self` = 11 (HUNGRY)
  - `Philo4.pending` = 1 (liveness checker active)
  - `Philo4.timer` = 0xC8 (200 decimal) — deadline reached

## 4. Root Cause Analysis

### Bug Location
- **File**: `philo4.scala`, lines 53–55
- **Buggy Code** (in `Philosopher` class, HUNGRY→EATING transition):
  ```scala
  }.elsewhen(self === PhilosopherState.HUNGRY) {
    when((io.left =/= PhilosopherState.EATING) && 
         (io.right =/= PhilosopherState.HUNGRY) &&   // ← BUG: line 54
         (io.right =/= PhilosopherState.EATING)) {
      self := PhilosopherState.EATING
    }
  }
  ```

### Description of the Bug

The transition from HUNGRY to EATING has an **incorrect condition**: `io.right =/= PhilosopherState.HUNGRY` on line 54. This requires that the philosopher's **right neighbor must NOT be in the HUNGRY state** for the philosopher to eat. This is logically wrong — a right neighbor being hungry should not prevent a philosopher from eating, since a hungry philosopher does not hold any shared resource (fork). Only a right neighbor that is **EATING** (holding the shared fork) should block eating.

The correct condition should be:
```scala
when((io.left =/= PhilosopherState.EATING) && 
     (io.right =/= PhilosopherState.EATING)) {
  self := PhilosopherState.EATING
}
```

### Evidence from Waveform

The counterexample trace shows a cascading deadlock triggered by this bug:

1. **Time 10**: ph0 transitions READING→THINKING (left neighbor ph3 is THINKING). ph1 transitions THINKING→HUNGRY (coin1=0, right neighbor ph2 is THINKING not READING).

2. **Time 20**: ph0 transitions THINKING→HUNGRY (right neighbor ph1 is HUNGRY, not READING; coin0=0). Now ph0 is HUNGRY. Its inputs are:
   - `io_left` = ph3.io_out = 01 (READING) ≠ EATING ✓
   - `io_right` = ph1.io_out = 11 (HUNGRY) — **this blocks ph0 due to line 54's `right != HUNGRY` condition**

3. **Time 20–40**: ph0 stays HUNGRY because ph1 (right neighbor) remains HUNGRY, failing the `right != HUNGRY` check at every clock edge during this interval.

4. **Time 40**: ph1 transitions HUNGRY→EATING (its right neighbor ph2 is now THINKING, so right≠HUNGRY and right≠EATING are both satisfied). But now ph0's right neighbor is EATING:
   - `io_right` = ph1.io_out = 10 (EATING) — blocks ph0 due to `right != EATING`

5. **Time 40 onward**: ph1 is stuck in EATING because its coin input (`io_coin1`) is 0 for the entire trace, and the EATING state only transitions to THINKING when `coin=1`. With coin=0, ph1 stays EATING forever.

6. **Time 40–2030**: ph0 remains HUNGRY every cycle because ph1 is EATING, and the condition `right != EATING` (line 55) is violated. The liveness timer counts from 0 to 200, and at time 2030 the assertion fires.

### Why This Is a Bug

The `right != HUNGRY` condition (line 54) creates an **unnecessary constraint** that is not required by the mutual-exclusion semantics of the dining philosophers protocol. The only constraint needed for eating is that **neither adjacent philosopher is currently EATING** (since adjacent philosophers share a fork). The right neighbor being hungry has no bearing on fork availability — a hungry philosopher is not holding any fork and is waiting to acquire forks.

### Categorization

This is a **DUT bug** (`dut_bug`): the Philosopher state machine's HUNGRY→EATING transition condition is overly restrictive in requiring `right != HUNGRY`, which introduces a deadlock scenario that violates the liveness guarantee.
