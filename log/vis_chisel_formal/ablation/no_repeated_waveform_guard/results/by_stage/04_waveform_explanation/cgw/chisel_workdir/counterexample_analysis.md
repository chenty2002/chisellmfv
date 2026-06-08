# Counterexample Analysis Report: cgw_safety_invariant Failure

## 1. Verification Environment

- **Design**: The design models the classic cabbage/goat/wolf river crossing puzzle. A man must transport a cabbage, a goat, and a wolf across a river using a boat, while ensuring the goat is never left alone with the wolf (wolf eats goat) or the cabbage (goat eats cabbage).
- **Top module**: `cgw` (from file `cgw.scala`)
- **Key components and signals**:
  - `io.select` (2-bit input): Selects the passenger to transport (`00`: none, `01`: cabbage, `10`: goat, `11`: wolf)
  - `boat`, `cabbage`, `goat`, `wolf`: State registers tracking which side each entity is on (`0`: sideLeft, `1`: sideRight)
  - `io.safe`: Output indicating whether the current state is safe
  - `io.finalState`: Output true when all entities are on the right side

## 2. Violated Assertion

- **Assertion name**: `cgw_safety_invariant`
- **Code snippet** (line 72 of `cgw.scala`):
  ```scala
  fvAssert(io.safe, "cgw_safety_invariant")
  ```
- **Property description**: The system must never enter an unsafe state where the goat is left alone with the wolf or the cabbage without the boat (the man) present to supervise. In other words, either the boat (man) must be with the goat, or the goat must be separated from both the wolf and the cabbage.
- **File location**: `cgw.scala`, line 72

## 3. Waveform Information

- **Waveform file**: `verilog/extra_bench/cgw/cgw.cgw_safety_invariant.fst`
- **Time range**: 0 ns to 20 ns (2 clock cycles)
- **Key time points and signal values**:

### Time 0 ns (initial state, before clock edge)

| Signal | Value | Meaning |
|--------|-------|---------|
| `cgw.io_select [1:0]` | `00` | passengerNone selected |
| `cgw.boat` | `0` | sideLeft |
| `cgw.cabbage` | `0` | sideLeft |
| `cgw.goat` | `0` | sideLeft |
| `cgw.wolf` | `0` | sideLeft |
| `cgw.io_safe` | `1` | Safe ✓ (boat is with goat) |
| `cgw.cgw_safety_invariant` | `1` | Assertion holds ✓ |

### Time 10 ns (after first clock edge)

| Signal | Value | Meaning |
|--------|-------|---------|
| `cgw.io_select [1:0]` | `00` | passengerNone still selected |
| `cgw.boat` | `1` | **sideRight** (boat crossed!) |
| `cgw.cabbage` | `0` | sideLeft (unchanged) |
| `cgw.goat` | `0` | sideLeft (unchanged) |
| `cgw.wolf` | `0` | sideLeft (unchanged) |
| `cgw.io_safe` | `0` | **UNSAFE** ✗ |
| `cgw.cgw_safety_invariant` | `0` | **ASSERTION FAILED** ✗ |

## 4. Root Cause Analysis

### Root cause type: **Bug in the original design (DUT bug)**

### Bug location
**File**: `cgw.scala`, **lines 43–47**, the **boat movement logic**:

```scala
when(io.select === passengerNone ||
     (io.select === passengerCabbage && cabbage === boat) ||
     (io.select === passengerGoat && goat === boat) ||
     (io.select === passengerWolf && wolf === boat)) {
  boat := Mux(boat === sideRight, sideLeft, sideRight)
}
```

### Description of the bug

The boat movement logic allows the boat to move without any passenger (`io.select === passengerNone`) **unconditionally** — it never checks whether moving the boat alone would leave the goat unattended with the wolf or the cabbage on either bank.

In the classic puzzle, the man (boat) cannot simply leave the bank without ensuring no dangerous pair is left alone. The boat movement logic should include a **safety guard** that prevents the boat from leaving if doing so would create an unsafe state.

### Evidence from waveform

The counterexample trace shows the following sequence:

1. **Cycle 0** (time 0 ns): All entities (boat, cabbage, goat, wolf) are on sideLeft. The input `io.select` is set to `passengerNone` (`00`). The state is safe because the boat (man) is with the goat: `boat === goat` evaluates to true, so `io.safe = 1`.

2. **Clock edge** (between time 0 and time 10): The boat update logic triggers because `io.select === passengerNone` is true. The boat toggles from `sideLeft` (0) to `sideRight` (1). Since no passenger is selected, `cabbage`, `goat`, and `wolf` all remain on `sideLeft` (0).

3. **Cycle 1** (time 10 ns): The boat is now on `sideRight` (1) while the goat, wolf, and cabbage remain on `sideLeft` (0). The safety condition evaluates as:
   - `io.safe := (boat === goat) || (goat =/= wolf && goat =/= cabbage)`
   - `= (1 === 0) || (0 =/= 0 && 0 =/= 0)`
   - `= false || (false && false)`
   - `= false` → **UNSAFE**

### Why this causes the assertion to fail

The assertion `fvAssert(io.safe, "cgw_safety_invariant")` checks that the system is always in a safe state. Because the design allows the boat to move alone (`passengerNone`) from the initial state, the goat is left unattended with both the wolf and the cabbage on the left bank. This violates the core rule of the puzzle: the goat cannot be left alone with the wolf (which would eat it) or the cabbage (which the goat would eat).

### Proposed fix

The boat movement logic for the `passengerNone` case should include a safety check. Before allowing the boat to move alone, verify that the resulting state would be safe. Specifically, the boat should only be allowed to leave a bank unaccompanied if the goat is not left alone with the wolf or the cabbage on that bank:

```scala
when(io.select === passengerNone) {
  val nextBoat = Mux(boat === sideRight, sideLeft, sideRight)
  // Check safety after the boat moves: either the boat is with the goat,
  // OR the goat is separated from both wolf and cabbage
  val safeAfterMove = (nextBoat === goat) || (goat =/= wolf && goat =/= cabbage)
  when(safeAfterMove) {
    boat := nextBoat
  }
}.otherwise {
  when(
    (io.select === passengerCabbage && cabbage === boat) ||
    (io.select === passengerGoat && goat === boat) ||
    (io.select === passengerWolf && wolf === boat)
  ) {
    boat := Mux(boat === sideRight, sideLeft, sideRight)
  }
}
```

Alternatively, the `passengerNone` case could simply be removed (the man must always take some passenger), but the classic puzzle does allow crossing alone — it's just a matter of when it is safe to do so.
