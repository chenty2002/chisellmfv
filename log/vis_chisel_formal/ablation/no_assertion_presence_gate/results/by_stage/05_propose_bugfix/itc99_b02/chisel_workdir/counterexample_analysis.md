# Counterexample Analysis Report

## 1. Verification Environment

- **Top module**: `b02` (Chisel module extending `Module with Formal`)
- **Design**: ITC99 b02 benchmark — a simple 7-state FSM (states A through G) with a single input `LINEA` and single output `U`
- **Key components**:
  - `stato` (3-bit state register, `RegInit(StateA = 0.U)`)
  - `U_reg` (output register, asserted only in StateE)
  - `io.LINEA` (input control signal)
  - `RegNext(stato)` (delayed version of stato, implemented as `REG [2:0]` in Verilog)
- **Formal assertions**: Several `fvAssert` calls checking safety and transition properties

## 2. Violated Assertion

- **Assertion name**: `state_changes_every_cycle` (from waveform filename `b02.state_changes_every_cycle.fst`)
- **Source file**: `b02.scala`, line 90
- **Code snippet**:
  ```scala
  // SAFETY 3: FSM always makes progress — the state must change every cycle
  // (every state has a non-self transition; RegNext(stato) always differs)
  fvAssert(stato =/= RegNext(stato), "state_changes_every_cycle")
  ```
- **Property description**: The assertion checks that the FSM state `stato` is always different from its value in the previous cycle (`RegNext(stato)`). Since every state in the FSM has a transition to a different state, this property should hold for every transition after initialization.

## 3. Waveform Information

- **Waveform file**: `verilog/extra_bench/itc99_b02/b02.state_changes_every_cycle.fst`
- **Time range**: 0 ns → 10 ns (1 clock cycle)
- **Clock**: Single positive edge at time 0 ns, falling edge at 5 ns, stays low through 10 ns (end of simulation)

### Key Signal Values

| Signal | t=0 ns | t=5 ns | t=10 ns |
|--------|--------|--------|---------|
| `b02.clock` | 1 | 0 | 0 |
| `b02.stato [2:0]` | **000** | **000** | **000** |
| `b02.REG [2:0]` (RegNext(stato)) | **000** | **000** | **000** |
| `b02.REG_1 [2:0]` | 000 | 000 | 000 |
| `b02.hasBeenReset` | 1 | 1 | 1 |
| `b02.state_changes_every_cycle` | 1 | 1 | 1 |
| `b02.io_LINEA` | 1 | — | 1 |

At **every time point**, `stato` == `REG` (both are `000`), making the assertion `stato =/= RegNext(stato)` evaluate to **false**.

## 4. Root Cause Analysis

### Category: **Assertion Error (incorrect specification)**

### The Bug

The assertion `fvAssert(stato =/= RegNext(stato), "state_changes_every_cycle")` does **not** account for the initial cycle after reset/initialization.

### Why the Assertion Fails

1. **`stato`** is declared as `RegInit(StateA)` on line 28, which initializes it to `StateA = 0.U` (binary `000`).

2. **`RegNext(stato)`** creates an uninitialized register that delays `stato` by one cycle. In Chisel, uninitialized registers default to `0` (all bits zero).

3. At **cycle 0** (the first cycle), both `stato` and `RegNext(stato)` hold the value `000`:
   - `stato = 000` (initialized to StateA)
   - `RegNext(stato) = 000` (default register initialization)

4. Therefore, `stato =/= RegNext(stato)` evaluates to `000 =/= 000` = **false**, causing the assertion to fail on the very first cycle.

### Why the Design Is Actually Correct

The FSM's transition logic is bug-free — every state unconditionally transitions to a different state:
- StateA → StateB
- StateB → StateC or StateF
- StateC → StateD or StateG
- StateD → StateE
- StateE → StateB
- StateF → StateG
- StateG → StateE or StateA

After the first clock edge (cycle 1 onward), `stato` would change to StateB (001) while `RegNext(stato)` would hold StateA (000), making the property hold. However, the formal tool's counterexample exposes the cycle-0 failure before any state transition can occur.

### Evidence from Waveform

- At t=0 ns: `b02.stato [2:0] = 000`, `b02.REG [2:0] = 000` → **equal** (assertion violation)
- At t=5 ns: `b02.stato [2:0] = 000`, `b02.REG [2:0] = 000` → **equal** (still violating)
- At t=10 ns: `b02.stato [2:0] = 000`, `b02.REG [2:0] = 000` → **equal** (still violating)
- `b02.clock` only has one positive edge (at t=0), so no register update occurs during the simulated window
- The signal `b02.state_changes_every_cycle` is `1` throughout, indicating the assertion condition `stato =/= RegNext(stato)` is being evaluated and found false

### The Fix

The assertion needs to be gated to exclude the initial cycle. One common approach is to use the `hasBeenReset` signal (which is available from the `Formal` trait):

```scala
// Fixed: only check state changes after reset is complete
fvAssert(!hasBeenReset || stato =/= RegNext(stato), "state_changes_every_cycle")
```

Alternatively, the assertion could be written with an explicit initial-condition guard:

```scala
// Alternative: skip the first cycle
fvAssert(RegNext(hasBeenReset) && stato =/= RegNext(stato), "state_changes_every_cycle")
```

