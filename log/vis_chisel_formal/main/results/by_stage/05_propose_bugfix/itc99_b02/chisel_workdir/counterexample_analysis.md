# Counterexample Analysis Report — itc99_b02

## 1. Verification Environment

- **Top Module**: `b02` (in `package llmverify`)
- **Source File**: `b02.scala` (118 lines)
- **Design Under Test**: A finite state machine (FSM) derived from the ITC99 b02 benchmark — a sequence detector with a single-bit serial input `LINEA` and a single-bit output `U`.
- **Key Components**:
  - `stato` — 3-bit state register (7 valid states: A=0, B=1, C=2, D=3, E=4, F=5, G=6)
  - `U_reg` — output register (drives `io.U`)
  - `lineaHighCycles` — 3-bit counter tracking consecutive cycles with `LINEA=1` (reset when `LINEA=0`)
  - `notFirstCycle` — flag to skip the first-cycle deadlock check
  - `hasBeenReset` / `hasBeenResetReg` — reset tracking signals

## 2. Violated Assertion

- **Assertion Name**: `U_reg_should_assert_within_8_cycles_after_reset` (from waveform filename)
- **Code Snippet** (lines 97–112 of `b02.scala`):

```scala
// Bounded liveness: After reset, the FSM should assert U=1 within a bounded
// number of cycles. ...
// The bound of 12 is the LCM of the loop length (4 cycles) and the fairness
// window (6 cycles), guaranteeing alignment between LINEA=0 and StateG.
val lineaHighCycles = RegInit(0.U(3.W))
when(io.LINEA) {
  lineaHighCycles := lineaHighCycles + 1.U
}.otherwise {
  lineaHighCycles := 0.U
}
assume(lineaHighCycles < 6.U, "LINEA fairness: must go low every 6 cycles")
astRelaxedLiveness(!reset.asBool, U_reg, 12, "U_reg should assert within 8 cycles after reset")
```

- **Property in Natural Language**: "After reset deassertion, the output `U_reg` must assert (become true) within 12 cycles."
- **Fairness Assumption**: `LINEA` must go low at least once every 6 consecutive cycles (`lineaHighCycles < 6`).
- **File Location**: `b02.scala`, lines 97–112

## 3. Waveform Information

- **Waveform File**: `verilog/extra_bench/itc99_b02/b02.U_reg_should_assert_within_8_cycles_after_reset.fst`
- **Time Range**: 0 ns → 140 ns (14 clock cycles, 10 ns period)
- **Clock**: Rising edges at 0, 10, 20, …, 130 ns
- **Reset**: 0 (deasserted) throughout the trace
- **Assertion Failure**: The assertion signal goes from `1` (passing) to `0` (failing) at **130 ns** (cycle 13)

### Critical Signal Values at Each Clock Cycle (posedge)

| Time (ns) | stato [2:0] | State | LINEA | U_reg | lineaHighCycles | Next State |
|-----------|-------------|-------|-------|-------|-----------------|------------|
| 0         | 000         | A     | 1     | 0     | 000             | A→B        |
| 10        | 001         | B     | 1     | 0     | 001             | B→F        |
| 20        | 101         | F     | 1     | 0     | 010             | F→G        |
| 30        | 110         | G     | 1     | 0     | 011             | G→A        |
| 40        | 000         | A     | 1     | 0     | 100             | A→B        |
| 50        | 001         | B     | **0** | 0     | 101             | B→**C**    |
| 60        | 010         | C     | 1     | 0     | 000             | C→G        |
| 70        | 110         | G     | 1     | 0     | 001             | G→A        |
| 80        | 000         | A     | 1     | 0     | 010             | A→B        |
| 90        | 001         | B     | 1     | 0     | 011             | B→F        |
| 100       | 101         | F     | **0** | 0     | 100             | F→G        |
| 110       | 110         | G     | 1     | 0     | 000             | G→A        |
| 120       | 000         | A     | **0** | 0     | 001             | A→B        |
| 130       | 001         | B     | **0** | 0     | 000             | B→**C**    |

**U_reg remains 0 at all 14 clock rising edges. StateE (100) is never reached.**

## 4. Root Cause Analysis

### Classification: **assertion_error** — the assertion bound is insufficient

### Bug Type: The bounded-liveness assertion uses a bound of 12 cycles that is fundamentally insufficient to guarantee `U_reg` will assert, even under the fairness constraint (`LINEA` goes low every 6 cycles). The adversary (formal solver) can exploit the phase relationship between the 4-cycle FSM loop and the 6-cycle fairness window to indefinitely avoid reaching StateE.

### Explanation of the FSM and the Vulnerability

The FSM can reach StateE (where `U_reg=1`) through three possible paths:

| Path | Sequence | Requires |
|------|----------|----------|
| (a)  | A→B→C→D→E | LINEA=0 at **both** StateB **and** StateC |
| (b)  | A→B→C→G→E | LINEA=0 at StateB, LINEA=1 at StateC, **LINEA=0 at StateG** |
| (c)  | A→B→F→G→E | LINEA=1 at StateB, **LINEA=0 at StateG** |

Paths (b) and (c) both require `LINEA=0` **while the FSM is in StateG**.

The fairness constraint (`lineaHighCycles < 6`) only guarantees that `LINEA=0` occurs somewhere within every 6-cycle window — it does NOT specify *when* in that window. The adversary can choose to place `LINEA=0` when the FSM is in a "safe" state (A, B, or F) and `LINEA=1` when in the "dangerous" StateG.

### Evidence from the Counterexample

In this trace, the adversary places `LINEA=0` exclusively during safe states:

| Cycle | Time | FSM State | LINEA | Effect |
|-------|------|-----------|-------|--------|
| 5     | 50   | B (safe)  | 0     | B→C (starts path toward G, but... ) |
| 6     | 60   | C         | 1     | C→G (not C→D→E) |
| 7     | 70   | **G**     | **1** | **G→A (not G→E!)** — low cycle was "wasted" |
| 10    | 100  | F (safe)  | 0     | F→G unconditionally — low cycle wasted again |
| 11    | 110  | **G**     | **1** | **G→A again** |
| 12    | 120  | A (safe)  | 0     | A→B unconditionally — low cycle wasted |
| 13    | 130  | B (safe)  | 0     | B→C, but no StateE yet |

At **every** occurrence of StateG (cycles 30, 70, 110), `LINEA` is **1**, preventing the G→E transition. The `LINEA=0` events (cycles 50, 100, 120, 130) all occur when the FSM is in states A, B, or F — states where `LINEA=0` cannot directly lead to StateE.

### Why the LCM Reasoning is Flawed

The comment in the source code (lines 97–104) claims:

> "The bound of 12 is the LCM of the loop length (4 cycles) and the fairness window (6 cycles), guaranteeing alignment between LINEA=0 and StateG."

This reasoning assumes that `LINEA=0` occurs at a **fixed periodic position** within the 6-cycle window. Under formal verification, however, the solver treats `LINEA` as an *adversarial* input that can choose WHEN to go low within each 6-cycle window. The solver observes the current FSM state and decides:

- If state is **G**: set `LINEA=1` (prevent G→E)
- If state is **A, B, or F**: and `lineaHighCycles` is about to reach 6: set `LINEA=0` (satisfy the fairness constraint without enabling StateE)

This adversarial strategy can avoid StateE **indefinitely**, meaning no finite bound on the liveness assertion can guarantee `U_reg=1`. The fairness constraint is necessary but not sufficient.

### Corrective Action

To fix the assertion, one of the following approaches is needed:

1. **Strengthen the fairness assumption**: Require `LINEA=0` to occur at least once every N cycles **and** require it to stay low for 2 consecutive cycles (to ensure the FSM can traverse C→D→E or G→E). Alternatively, require that `LINEA` is low while the FSM is in StateG at least occasionally.

2. **Remove the bounded-liveness assertion entirely**: For a sequence-detector FSM, bounded liveness is not the right property unless the input is sufficiently constrained. The design is correct for its intended purpose — it outputs `U=1` when the correct input sequence is detected.

3. **Use an unbounded liveness property**: If the formal tool supports it, use `astLiveness` (unbounded) instead of `astRelaxedLiveness` (bounded), since the FSM should eventually reach StateE under fair input — but this also requires a stronger fairness constraint.

### Conclusion

The counterexample does **not** reveal a bug in the original design. The FSM implements the correct ITC99 b02 sequence-detector behavior. Instead, the assertion `astRelaxedLiveness(!reset.asBool, U_reg, 12, ...)` is **too strong** — the bound of 12 cycles is insufficient because the adversarial input can always avoid the `LINEA=0` alignment with StateG by choosing when to assert `LINEA=0` within the 6-cycle fairness window. This is an **assertion error** category.
