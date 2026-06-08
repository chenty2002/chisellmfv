# Counterexample Analysis Report: CGM-001 Goat Safety Invariant

## 1. Verification Environment

- **Top Module**: `cgw` (from `cgw.scala`)
- **Package**: `llmverify`
- **Design Description**: A formal verification model of the classic cabbage/goat/wolf river-crossing puzzle. The DUT tracks the positions (left/right side of river) of four entities: a boat, a cabbage, a goat, and a wolf. An input `io.select` chooses which passenger (if any) boards the boat for crossing. The puzzle constrains that the goat must never be left unattended with the wolf (who eats the goat) or the cabbage (which the goat eats).
- **Key Components**: Four state registers (`boat`, `cabbage`, `goat`, `wolf`) with side values (0=left, 1=right), input select (0=none, 1=cabbage, 2=goat, 3=wolf), and combinational safety/final-state outputs.
- **Formal Framework**: Chisel `Formal` with `fvAssert` for invariants and `astRelaxedLiveness` for bounded liveness.

## 2. Violated Assertion

- **Assertion Name**: `CGM2D001_Goat_must_always_be_safe_from_wolf_and_cabbage`
- **Source Location**: `chisel/extra_bench/cgw/cgw.scala`, line 61
- **Code Snippet**:
  ```scala
  // Safety condition: boat is with goat OR goat is not with wolf AND goat is not with cabbage
  io.safe := (boat === goat) || (goat =/= wolf && goat =/= cabbage)

  // CGM-001: Critical safety invariant — the goat must never be left alone
  // with the wolf or the cabbage on either side of the river.
  fvAssert(io.safe, "CGM-001 Goat must always be safe from wolf and cabbage")
  ```
- **Property Description**: The assertion checks that `io.safe` is always true. `io.safe` encodes the condition that the goat is safe from being eaten: either the boat is on the same side as the goat (providing protection), OR the goat is separated from both the wolf and the cabbage (i.e., not on the same side with either predator/prey).

## 3. Waveform Information

- **Waveform File**: `verilog/extra_bench/cgw/cgw.CGM2D001_Goat_must_always_be_safe_from_wolf_and_cabbage.fst`
- **Time Range**: 0 ns → 20 ns (2 clock cycles)
- **Clock**: period 10 ns, rising edges at times 0 ns and 10 ns

### Key Signal Values

| Signal | Time 0 (cycle 1) | Time 10 (cycle 2) |
|--------|:-:|:-:|
| `clock` | 1 (rising edge) | 1 (rising edge) |
| `reset` | 0 | 0 |
| `io_select [1:0]` | 01 (CABBAGE) | 11 (WOLF) |
| `boat` | 0 (left) | 1 (right) |
| `cabbage` | 0 (left) | 1 (right) |
| `goat` | 0 (left) | 0 (left) |
| `wolf` | 0 (left) | 0 (left) |
| `io_safe` | 1 | **0 (FAIL)** |

### Failure Point
At **time 10 ns**, immediately after the second clock rising edge, `io_safe` transitions from 1 to 0, violating the assertion `CGM2D001`.

## 4. Root Cause Analysis

### Classification: **Assertion Error** (incorrect assertion formulation)

The assertion CGM-001 uses `fvAssert(io.safe, ...)` to claim that `io.safe` must be true in **all** clock cycles for **all** possible input sequences. However, this property does **not** hold for the given design, and **should not** be expected to hold — it is fundamentally an input constraint, not a design invariant.

### Detailed Explanation

**Why the counterexample triggers the failure:**

1. **Cycle 1 (time 0):** `io_select = 01 (CABBAGE)`. The boat and cabbage start on the left side (0). Since `boat === cabbage`, the condition `io.select === passengerCabbage && boat === cabbage` is true, so the cabbage and boat both move to the right side (1). This results in:
   - Left side: goat and wolf (together — **unsafe!**)
   - Right side: boat and cabbage

2. **Cycle 2 (time 10):** The safety check evaluates: `(boat === goat) || (goat =/= wolf && goat =/= cabbage)` = `(1 === 0) || (0 =/= 0 && 0 =/= 1)` = `false || (false && true)` = `false`. The goat is on the left side with the wolf and no boat protection, which is an unsafe state in the puzzle.

**Why this is an assertion error, not a design bug:**

The design correctly models the puzzle mechanics. The classic 7-step solution (take goat → return alone → take wolf → bring goat back → take cabbage → return alone → take goat) **does work** and keeps the goat safe at every step. The design does not prevent bad moves — it faithfully models the physical puzzle where the farmer can make poor choices.

In formal verification, assertions are properties that the design should satisfy for all valid input sequences. However, `io.safe` is not a design invariant — it's a constraint on the **input sequence** (the farmer must choose moves that keep the goat safe). Using `fvAssert` here incorrectly treats an input-dependent safety condition as an unconditional design guarantee.

### Evidence from Waveform

- The counterexample sequence `select=1, select=3` (take cabbage, then try wolf) is a perfectly valid input sequence under the design's unconstrained `io.select` port, but it causes an unsafe intermediate state.
- The logic correctly computes `io_safe = false` for that state — the combinational output `io.safe` is working correctly; the issue is that this value is being asserted as an invariant when it should be an assumption.

### Recommended Fix

Change CGM-001 from an assertion to an assumption (input constraint). In the Chisel formal framework, this would mean using a statement like `assume(io.safe, ...)` instead of `fvAssert(io.safe, ...)`. This tells the formal tool to only consider input sequences where safety is maintained, which is the correct semantic for this puzzle model. The actual property to prove is CGM-002 (bounded liveness) — that a safe solution reaching the final state exists within 100 cycles.

**Affected code location**: `chisel/extra_bench/cgw/cgw.scala`, line 61
