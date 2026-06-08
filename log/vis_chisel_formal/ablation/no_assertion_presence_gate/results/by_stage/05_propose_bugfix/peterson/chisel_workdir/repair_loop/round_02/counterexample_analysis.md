# Counterexample Analysis Report: `peterson.L3_to_L4_when_other_not_interested_p0`

## 1. Verification Environment

**Top Module**: `peterson` (from `peterson.scala`)
**Top Module Structure**:
- The top module `peterson` implements Peterson's mutual exclusion algorithm for 2 processes.
- It extends `Module with Formal`, enabling the use of chiselFv formal assertion constructs.
- Inputs: `io.select` (Bool), `io.pause` (Bool)
- Outputs: `io.interested` (Vec(2, Bool)), `io.turn` (Bool), `io.self` (Bool), `io.pc` (Vec(2, Loc))
- Key internal state: `interested` (2-bit flag register), `turn` (1-bit turn register), `self` (1-bit process selection register), `pc` (2× 3-bit program-counter state registers using `Loc` enum with values L0–L5)

**Key Components and Connections**:
- `self` tracks which process is currently active (updated every cycle from `io.select`)
- `otherIdx = ~self` determines the other process
- The state machine (switch on `pc(selfIdx)`) implements the standard Peterson's algorithm:
  - L0 → L1: start (when not paused)
  - L1 → L2: set interested flag
  - L2 → L3: set turn to other process
  - L3 → L4: wait until `!interested(otherIdx) || (turn === self)` (enter critical section)
  - L4 → L5: leave critical section (when not paused)
  - L5 → L0: clear interested flag

**Design Under Test**: The `peterson` module with its state machine and formal verification assertions.

## 2. Violated Assertion

**Full Assertion Name**: `L3_to_L4_when_other_not_interested_p0`

**Code Snippet** (from `peterson.scala`, lines 99-100):

```scala
assertImpliesDelay((pc(0) === Loc.L3) && !interested(1) && (self === 0.U), pc(0) === Loc.L4, 1, "L3_to_L4_when_other_not_interested_p0")
```

**File Location**: `peterson.scala`, lines 99-100

**Natural Language Description**: 
When process 0 is at the entry protocol (location L3), process 1 is NOT interested in entering the critical section, AND the active process selector `self` is 0 (so process 0 is controlling the state machine), then process 0 must transition to the critical section (location L4) on the **very next cycle**.

The property is correct because when the premise holds:
- `pc(0) === L3` — process 0 is waiting at the entry protocol
- `!interested(1)` — process 1 is not interested
- `self === 0` — process 0 is the active process

The state machine transition at L3 checks `when(!interested(otherIdx) || (turn === self))`. With `selfIdx = self = 0`, `otherIdx = ~self = 1`, the condition simplifies to `when(!interested(1) || (turn === 0))`. Since `!interested(1)` is true (part of premise), the guard evaluates to `true`, and `pc(0) := Loc.L4` must fire in the next cycle.

## 3. Waveform Information

**Waveform File**: `verilog/extra_bench/peterson/peterson.L3_to_L4_when_other_not_interested_p0.fst`

**Time Range**: 0 ns → 10 ns (1 clock cycle at 10 ns period)

**Key Time Points and Signal Values**:

| Signal | Time 0 | Time 5 | Time 10 |
|--------|--------|--------|---------|
| `peterson.pc_0 [2:0]` | 000 (L0) | 000 (L0) | 000 (L0) |
| `peterson.pc_1 [2:0]` | 000 (L0) | 000 (L0) | 000 (L0) |
| `peterson.interested_0` | 0 | 0 | 0 |
| `peterson.interested_1` | 0 | 0 | 0 |
| `peterson.self` | 0 | 0 | 0 |
| `peterson.turn` | 0 | 0 | 0 |
| `peterson.io_select` | 0 | 0 | 0 |
| `peterson.io_pause` | 0 | 0 | 0 |
| `peterson.reset` | 0 | 0 | 0 |
| `peterson.hasBeenResetReg` | 1 | 1 | 1 |
| **`peterson.L3_to_L4_when_other_not_interested_p0`** | **1** | **1** | **1** |

**Critical Observation**: The assertion failure signal `L3_to_L4_when_other_not_interested_p0` is **1 (assertion failure) at ALL time points**, while all design-under-test signals remain at their reset/initial values (pc(0)=L0, pc(1)=L0, interested=0, etc.). The premise of the assertion is **never** true in this counterexample trace.

## 4. Root Cause Analysis

### Classification: **Assertion Error** (Incorrect assertion infrastructure)

### Root Cause: Uninitialized delay register in `assertImpliesDelay`

The counterexample reveals a classic formal verification initialization issue in the `assertImpliesDelay` function from the chiselFv library.

**Detailed Explanation**:

The `assertImpliesDelay(premise, conclusion, 1, ...)` call should generate logic equivalent to:

```scala
val premiseDelayed = RegNext(premise)  // 1-cycle delay
assert(premiseDelayed ===> conclusion)
```

The `RegNext` construct in Chisel, when used **without an explicit initial value**, creates a register whose initial state is **symbolic/unconstrained** in formal verification. The formal solver is free to choose any initial value for this register (0 or 1) when constructing a counterexample.

In this counterexample:
1. The actual `premise` = `(pc(0) === L3) && !interested(1) && (self === 0.U)` is **false** at all time points (pc(0) = L0, not L3).
2. However, the **delayed premise register** (the internal register created inside `assertImpliesDelay`) has an unconstrained initial value.
3. The formal solver chooses **initial value = 1** for this delayed premise register at time 0.
4. This makes the assertion check: `1 |-> (pc(0) === L4)` which evaluates to `(pc(0) === L4)` = false (since pc(0)=L0 at time 0).
5. Result: **spurious assertion failure at time 0**, even though the premise is never actually true.

**Why it is not a DUT bug**: The state machine is working correctly — both processes are at L0 (idle state), interested flags are 0, no process is trying to enter the critical section. There is no way to observe a violation because the premise is never satisfied.

**Why it is not a setup error**: The testbench correctly connects inputs, and all DUT signals are properly constrained. The issue lies entirely within the assertion infrastructure.

**Evidence from Waveform**:
- All DUT signals remain at their initial values (pc(0)=L0, interested(0)=0, etc.) throughout the entire trace
- No signal ever takes a value that would satisfy the premise: `(pc(0)===L3)` is always false
- The assertion failure signal stays at 1 from time 0, before any clock edge has occurred to allow the premise to propagate through the delay register
- The `hasBeenReset` signal is 1, confirming the system is past reset

### Recommended Fix

The fix should modify the `assertImpliesDelay` implementation in the chiselFv library to properly initialize its internal delay register. Specifically, instead of using:

```scala
RegNext(premise)  // Uninitialized — symbolic in formal
```

it should use:

```scala
RegInit(false.B)  // Properly initialized to false
RegNext(premise) when ... or simply:
RegNext(premise, false.B)  // Explicit initial value
```

Alternatively, if modifying the library is not possible, the assertion can be guarded with `when(hasBeenReset)` to avoid evaluation during the initial uninitialized state:

```scala
when (hasBeenReset) {
  assertImpliesDelay((pc(0) === Loc.L3) && !interested(1) && (self === 0.U), pc(0) === Loc.L4, 1, "L3_to_L4_when_other_not_interested_p0")
}
```

But this is a workaround; the proper fix is in the `assertImpliesDelay` implementation.
