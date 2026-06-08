# Counterexample Analysis Report: `cgw.safe_invariant__goat_not_eaten`

## 1. Verification Environment

- **Top Module**: `cgw` (in package `llmverify`, file `cgw.scala`)
- **Design**: A hardware model of the classic "Cabbage, Goat, and Wolf" river-crossing puzzle.
  - **State**: Four registers track positions (left=0/right=1) of the boat, cabbage, goat, and wolf.
  - **Input**: `io.select` (2-bit) selects which passenger (if any) to transport: 0=None, 1=Cabbage, 2=Goat, 3=Wolf.
  - **Outputs**: `io.safe` indicates whether the goat is safe from being eaten; `io.finalState` indicates all entities are on the right bank.
  - The boat and a selected passenger (if any) switch sides each cycle, provided the passenger is on the same side as the boat.
- **Formal Tool**: Chisel formal verification with `chiselFv` library, running under a bounded model checker.

## 2. Violated Assertion

- **Assertion Name**: `safe_invariant__goat_not_eaten`
- **Assertion Statement** (line 78 of `cgw.scala`):
  ```scala
  fvAssert(io.safe, "safe_invariant__goat_not_eaten")
  ```
- **Property Description**: Asserts that `io.safe` must **always** be true (an invariant). The `io.safe` signal is defined as:
  ```scala
  io.safe := (boat === goat) || (goat =/= wolf && goat =/= cabbage)
  ```
  This is true when either (a) the farmer (boat) is with the goat, or (b) the goat is separated from both the wolf and the cabbage.
- **File Location**: `cgw.scala`, line 78

## 3. Waveform Information

- **Waveform File**: `verilog/extra_bench/cgw/cgw.safe_invariant__goat_not_eaten.fst`
- **Time Range**: 0 ns → 20 ns (2 clock cycles)
- **Clock**: Rising edge at time 0 ns and time 10 ns
- **Reset**: Low (inactive) throughout the entire trace

### Key Signal Values

| Signal | Time 0 ns | Time 5 ns | Time 10 ns | Time 15 ns |
|--------|-----------|-----------|------------|------------|
| `cgw.io_select [1:0]` | 00 (None) | 00 (None) | 00 (None) | 00 (None) |
| `cgw.boat` | 0 (left) | 0 (left) | **1 (right)** | 1 (right) |
| `cgw.goat` | 0 (left) | 0 (left) | 0 (left) | 0 (left) |
| `cgw.wolf` | 0 (left) | 0 (left) | 0 (left) | 0 (left) |
| `cgw.cabbage` | 0 (left) | 0 (left) | 0 (left) | 0 (left) |
| `cgw.io_safe` | 1 | 1 | **0 (FAIL)** | 0 |
| `cw.safe_invariant__goat_not_eaten` | 1 | 1 | **0 (FAIL)** | 0 |

### Sequence of Events

1. **Time 0 ns** (initial, posedge): All entities are on the left bank. `io.select = 00` (None). `io.safe = 1` (safe — boat is with goat on left).
2. **Time 0–10 ns**: The design evaluates the combinatorial logic. Since `io.select === passengerNone`, the boat's update condition is true, so `boat` becomes `Mux(boat === sideRight, sideLeft, sideRight) = Mux(left? right:left) = sideRight = 1`.
3. **Time 10 ns** (posedge): The registers are updated. **`boat` transitions from 0 to 1 (left to right).** The goat, wolf, and cabbage remain on the left. This leaves the **goat alone with the wolf** (and the cabbage) on the left bank without the farmer. The safety signal becomes `io.safe = 0`.
4. The assertion `safe_invariant__goat_not_eaten` fails at time 10 ns.

## 4. Root Cause Analysis

### Error Type: **Incorrect Assertion** (assertion_error)

**The assertion `safe_invariant__goat_not_eaten` is fundamentally incorrect** because it asserts that `io.safe` must hold for **all possible input sequences**. This is not the intended semantics for this puzzle model.

### Why This is an Assertion Error (not a DUT Bug)

1. **The design correctly models the puzzle**: The classic puzzle allows the farmer to make bad choices. The farmer can cross the river alone (select=None), leaving the goat with the wolf — which is a valid (if unwise) move in the real puzzle. The design faithfully implements every possible state transition.

2. **The "restrictive version" restriction is about passenger-boat co-location, not safety**: The boat-move guard checks:
   ```scala
   when(io.select === passengerNone || 
        (io.select === passengerCabbage && cabbage === boat) ||
        (io.select === passengerGoat && goat === boat) ||
        (io.select === passengerWolf && wolf === boat)) {
     boat := Mux(boat === sideRight, sideLeft, sideRight)
   }
   ```
   This only restricts moves to cases where the selected passenger shares the boat's side. It does **not** prevent unsafe post-move configurations.

3. **The puzzle is a reachability/solvability problem, not an invariant problem**: The meaningful formal property for this puzzle is "there exists a sequence of inputs that reaches the final state while always keeping the goat safe." This is exactly what the **`astRelaxedLiveness`** assertion (line 87) checks:
   ```scala
   astRelaxedLiveness(!(reset.asBool), io.finalState, 30,
                      "final_state_reachable_within_30_cycles")
   ```

4. **Counterexample demonstrates the error**: The formal tool trivially violates the invariant by choosing `io.select=00` (None) on the first cycle. This is a perfectly legal input that the design correctly processes — the boat moves alone, the goat is left with the wolf, and `io.safe` goes low. This does not indicate a design bug; it simply shows that bad inputs produce bad states.

### What a Correct Assertion Would Look Like

The assertion should be removed or replaced with a **constraint** that nondeterministic inputs are limited to "safe" moves, or the assertion should check a **transition property** such as: "if the current state is safe, the next state after any allowed transition is also safe" — but this would require additional design logic to prevent unsafe transitions.

### Buggy Code Location

**File**: `cgw.scala`, **line 78**
```scala
fvAssert(io.safe, "safe_invariant__goat_not_eaten")
```

The assertion incorrectly assumes `io.safe` is always true regardless of the input sequence. In reality, `io.safe` reflects the safety of the current puzzle state, which depends on the chosen inputs. A universal invariant here is inappropriate.

### Waveform Evidence Summary

The waveform trace shows:
- **Time 0**: All entities left (0), boat left (0), io_select=0 (None), io_safe=1 ✓
- **Time 10**: Boat right (1), all items left (0), io_select=0 (None) throughout, io_safe=0 ✗

The boat moved from left to right while `io_select` was 0 (None) because the condition `io.select === passengerNone` is always true for this input value. This is the expected behavior per the design specification. The assertion failure is caused by the assertion being too strong, not by a bug in the DUT.
