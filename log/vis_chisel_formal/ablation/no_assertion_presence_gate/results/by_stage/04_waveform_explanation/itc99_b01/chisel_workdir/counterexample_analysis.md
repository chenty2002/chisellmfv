# Counterexample Analysis Report: itc99_b01

## 1. Verification Environment

- **Top Module**: `b01` (from `b01.scala`)
- **Design Under Test**: A finite state machine (FSM) with 8 states, implementing a simple datapath controller
- **Inputs**: `io.LINE1`, `io.LINE2` (Bool)
- **Outputs**: `io.OUTP` (Bool), `io.OVERFLW` (Bool)
- **Clock**: Period 10ns (5ns high, 5ns low), rising edges at 0, 10, 20, 30, 40, 50 ns
- **Reset**: Active-low (`jasper_formal_reset=0` at time 0 only)
- **State Encoding**: a=000, b=001, c=010, e=011, f=100, g=101, wf0=110, wf1=111

## 2. Violated Assertion

- **Assertion Name**: `OVERFLW_indicates_state_e` (from filename `b01.OVERFLW_indicates_state_e.fst`)
- **Location**: `b01.scala`, line 119
- **Code**:
  ```scala
  AssertProperty(io.OVERFLW === (stato === b01State.e), None, None, Some("OVERFLW_indicates_state_e"))
  ```
- **Natural Language Description**: The output signal `io.OVERFLW` should be asserted (true) exactly when the FSM is in state `e`.

## 3. Waveform Information

- **Waveform File**: `verilog/extra_bench/itc99_b01/b01.OVERFLW_indicates_state_e.fst`
- **Failure Time**: 40 ns (5th rising clock edge)
- **Key Signal Values at Time 40 ns**:

| Signal | Value | Meaning |
|--------|-------|---------|
| `clock` | 1 | Rising edge (time 40) |
| `stato [2:0]` | 011 (3) | **FSM just entered state `e`** |
| `io_LINE1` | 1 | Input high |
| `io_LINE2` | 1 | Input high |
| `io_OVERFLW` | 0 | **OVERFLW is false** |
| `overflwReg` | 0 | Register driving OVERFLW is false |
| `REG [2:0]` | 110 (6) | Previous state was `wf0` |
| `OVERFLW_indicates_state_e` | **0** | **Assertion failed** (transitioned from 1→0 at time 40) |

## 4. Root Cause Analysis

### Root Cause Category: **assertion_error** — Incorrect assertion timing

### Detailed Explanation

#### The Problem

The assertion checks a **combinational equivalence** between `io.OVERFLW` and `(stato === e)`:
```scala
io.OVERFLW === (stato === b01State.e)
```

However, `io.OVERFLW` is driven by `overflwReg`, a **registered** (sequential) signal:
```scala
val overflwReg = RegInit(false.B)
io.OVERFLW := overflwReg
```

In Chisel, register updates take effect **at the next clock edge**. The logic that assigns to `overflwReg` is evaluated in the **current** state and its value is loaded into the register **one cycle later**.

#### Trace of the Failure

The FSM follows this sequence leading to the failure at time 40:

| Time (ns) | Clock Edge | State Before → After | `overflwReg` Assignment | `overflwReg` Value After Cycle |
|-----------|------------|---------------------|------------------------|-------------------------------|
| 0         | Rising     | Reset → a (000)     | State a: `:= false.B`  | 0 (reset) |
| 10        | Rising     | a → f (100)          | State a: `:= false.B`  | 0 |
| 20        | Rising     | f → c (010)          | State f: `:= false.B`  | 0 |
| 30        | Rising     | c → wf0 (110)        | State c: `:= false.B`  | 0 |
| **40**    | **Rising** | **wf0 → e (011)**    | **State wf0: `:= false.B`** | **0 ← FAILURE** |

At time 40, the FSM transitions from `wf0` to `e`. The value loaded into `overflwReg` at this clock edge is computed from **state wf0's logic**, which sets `overflwReg := false.B`. So when `stato` becomes `e` at time 40, `overflwReg` is still `0`.

The assertion checks `0 === (stato === e)` → `0 === 1` → **FALSE**.

#### Why It Can Never Pass

Even in subsequent cycles when `overflwReg` does get set to `true.B` by state `e`'s logic, this happens **after** the FSM has left state `e`:

```scala
is(b01State.e) {
  when(io.LINE1 & io.LINE2) {
    stato := b01State.f  // ← transitions OUT of state e
  }.otherwise {
    stato := b01State.b
  }
  overflwReg := true.B   // ← gets loaded at the NEXT clock edge
}
```

In the failing scenario at time 40:
- `stato = e`, `LINE1=1`, `LINE2=1` → next state is `f`
- `overflwReg := true.B` is computed for the **next** clock edge (time 50)
- At time 50: `stato = f`, `overflwReg = 1` (loaded with `true.B`)
- Now `io.OVERFLW = 1` but `(stato === e) = 0` → assertion still fails!

The assertion can **never** be satisfied because the register pipeline introduces a one-cycle delay between the state condition and the output signal.

#### Neither a DUT Bug Nor a Setup Error

- **Not a DUT bug**: The FSM logic correctly sets `overflwReg := true.B` in state `e` and `false.B` in all other states. This is functionally correct behavior for a registered output.
- **Not a setup error**: The TestTop binds directly to the `b01` module with standard clock/reset and free inputs. No missing constraints or unrealistic stimulus.
- **Assertion error**: The assertion checks a combinational property but the design uses a register, creating a timing mismatch.

### Corrected Assertion

The assertion should account for the register's one-cycle delay:

```scala
// OVERFLW goes high in the cycle AFTER stato=e
AssertProperty(io.OVERFLW === RegNext(stato === b01State.e), None, None, Some("OVERFLW_indicates_state_e"))
```

This checks that `io.OVERFLW` is true exactly one cycle after the FSM was in state `e`, which matches the register pipeline behavior of `overflwReg`.
