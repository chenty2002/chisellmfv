# Counterexample Analysis Report: cgw.always_safe

## 1. Verification Environment

- **Top Module**: `cgw` (package `llmverify`)
- **Source File**: `chisel/extra_bench/cgw/cgw.scala`
- **Verilog Output Directory**: `verilog/extra_bench/cgw/`
- **Waveform File**: `cgw.always_safe.fst`

### Design Description

The DUT models the classic **cabbage/goat/wolf river-crossing puzzle**. A man must transport a cabbage, a goat, and a wolf across a river using a boat that can carry only one passenger besides the man. The puzzle constraints are:
- The wolf will eat the goat if left unattended together
- The goat will eat the cabbage if left unattended together

The module takes a `io.select` input (2-bit UInt) to choose which passenger (if any) to take across, and tracks the positions (left=0, right=1) of the boat, cabbage, goat, and wolf via registers.

## 2. Violated Assertion

- **Assertion Name**: `always_safe` (from waveform filename `cgw.always_safe.fst`)
- **File**: `cgw.scala`, line 70
- **Code**:
  ```scala
  // Safety invariant: the state must always be safe.
  fvAssert(io.safe, "always_safe")
  ```
- **Property Description**: The assertion checks that `io.safe` is always true, meaning the current puzzle configuration never leaves the goat unattended with the wolf, or the cabbage unattended with the goat. The safety condition is defined at line 50:
  ```scala
  io.safe := (boat === goat) || (goat =/= wolf && goat =/= cabbage)
  ```

## 3. Waveform Information

- **Waveform File**: `verilog/extra_bench/cgw/cgw.always_safe.fst`
- **Time Range**: 0 ns → 20 ns, 2 clock cycles
- **Clock**: posedge at t=0, negedge at t=5, posedge at t=10, negedge at t=15
- **Key Time Points**:

### t=0 ns (Initial State — all on left side, safe)
| Signal | Value | Meaning |
|--------|-------|---------|
| `io_select` | `01` (binary) = `passengerCabbage` | Man selects the cabbage |
| `boat` | `0` = `sideLeft` | Boat on left |
| `cabbage` | `0` = `sideLeft` | Cabbage on left |
| `goat` | `0` = `sideLeft` | Goat on left |
| `wolf` | `0` = `sideLeft` | Wolf on left |
| `io_safe` | `1` | Safe: boat with goat |
| `always_safe` | `1` | Assertion holds |

### t=10 ns (After posedge — man takes cabbage, leaving goat with wolf)
| Signal | Value | Meaning |
|--------|-------|---------|
| `io_select` | `01` = `passengerCabbage` | Same input persists |
| `boat` | `1` = `sideRight` | Boat moved to right |
| `cabbage` | `1` = `sideRight` | Cabbage moved to right |
| `goat` | `0` = `sideLeft` | Goat still on left |
| `wolf` | `0` = `sideLeft` | Wolf still on left |
| `io_safe` | **`0`** | **UNSAFE: goat and wolf together on left** |
| `always_safe` | **`0`** | **Assertion FAILS** |

## 4. Root Cause Analysis

### Root Cause Classification: **setup_error**

### The Issue

The counterexample demonstrates a **valid puzzle move that results in an unsafe state**:

1. **Initial state (t=0)**: All entities (man, boat, cabbage, goat, wolf) are on the left side. The state is safe because the boat (with the man) is with the goat.

2. **Transition (posedge at t=5→10)**: Input `io_select = passengerCabbage` is selected. Since `boat === cabbage` (both on left), the condition on line 38 fires:
   ```scala
   when(io.select === passengerCabbage && boat === cabbage) {
     cabbage := Mux(cabbage === sideRight, sideLeft, sideRight)
   }
   ```
   Both the cabbage and the boat flip to the right side (`sideRight`).

3. **Result (t=10)**: The man and cabbage are on the right side, while the **goat and wolf remain on the left side, unattended**. The wolf eats the goat, making `io.safe = 0`.

### Why This Is a Setup Error, Not a DUT Bug

The DUT **correctly models the puzzle state machine**:
- The transition logic properly implements the "boat carries selected passenger" rule
- The safety computation correctly identifies unsafe configurations
- The assertion fires on an input sequence that is a valid action in the puzzle description

### Why There Is No DUT Bug

The design faithfully models the puzzle: the man *can* choose to take the cabbage first, which is a legal (but losing) move. The DUT is not buggy—it correctly reflects what happens in the real puzzle scenario.

### Why It Is Not an Assertion Error

The assertion `fvAssert(io.safe, "always_safe")` is a **semantically meaningful property** for this puzzle verification: we want to verify that the man can always keep the configuration safe. However, to prove this property, the inputs must be constrained.

### The Actual Root Cause

The **formal verification setup lacks input constraints** (assumptions) on `io.select`. To prove the "always_safe" invariant, the environment should constrain `io.select` to only allow moves that preserve safety. Without such constraints, the formal tool can freely choose any input, including one that leads to an unsafe state (as seen here: selecting passengerCabbage when goat and wolf are on the same side).

A proper setup would use `fvAssume` to restrict `io.select` to passengers on the same side as the boat (which the DUT already checks) **and** to prevent moves that would create an unsafe state. For example:

```scala
// Assume only valid passengers (same side as boat) can be selected
fvAssume(io.select === passengerNone || 
         (io.select === passengerCabbage && cabbage === boat) ||
         (io.select === passengerGoat && goat === boat) ||
         (io.select === passengerWolf && wolf === boat))
```

Even with this constraint, the counterexample still holds because `passengerCabbage` with `cabbage === boat` is a valid input per the DUT's own logic. The deeper issue is that the assertion `always_safe` is not an invariant of the state transition system—it is a **temporal safety property** that must hold across the transition, not at individual states in isolation. To properly verify the puzzle, one would need to either:
- Add assumptions that only allow moves that result in a safe next state, or
- Use a bounded model-checking approach to search for a complete solution path
