# Counterexample Analysis Report: `reset.st0_follows_sel0_one_cycle_delay`

## 1. Verification Environment

- **Top Module**: `reset` (Chisel class extending `Module with Formal`)
- **Generated Verilog**: `chisel/extra_bench/reset/generated/reset.v`
- **Waveform File**: `verilog/extra_bench/reset/reset.st0_follows_sel0_one_cycle_delay.fst`
- **Source File**: `reset.scala` (57 lines)

**Key Components**:
- Three registers: `st0`, `st1`, `st2` (each `RegInit(0.U(1.W))`)
- Input: `io.sel` (2-bit UInt) — selects update control
- Output: `io.st` (3-bit UInt) — concatenation of `{st2, st1, st0}`
- ResetCounter module: tracks cycles since system reset via `timeSinceReset` and `flag`
- `hasBeenReset` / `hasBeenResetReg`: state indicators for reset completion

**Connections**:
- `st0 := io.sel(0)` — st0 follows bit 0 of the select input
- `st1 := ~st1` — st1 toggles every cycle
- `st2 := io.sel(1) | st2` — st2 is sticky (once set, stays set)
- `io.st := Cat(st2, st1, st0)` — output reflects internal state

## 2. Violated Assertion

- **Assertion Name**: `st0_follows_sel0_one_cycle_delay` (from waveform filename)
- **Full Path in Source**: `reset.scala`, lines 43–46
- **Code Snippet**:
  ```scala
  // Safety: st0 follows sel(0) with exactly one cycle delay
  // Use past() instead of raw RegNext to avoid first-cycle failure:
  // past() guards with timeSinceReset >= 1.U, ensuring RegNext has
  // captured at least one meaningful sel(0) value before the check.
  past(io.sel(0), 1) { prev =>
    fvAssert(st0 === prev, "st0_follows_sel0_one_cycle_delay")
  }
  ```
- **Description**: Asserts that register `st0` equals the value of `io.sel(0)` from the **previous** clock cycle (one-cycle delay). The `past()` function is documented to guard against first-cycle failures by checking `timeSinceReset >= 1.U`.

## 3. Waveform Information

- **Waveform File**: `verilog/extra_bench/reset/reset.st0_follows_sel0_one_cycle_delay.fst`
- **Duration**: 1 cycle (0 ns → 10 ns)
- **Only Time Point (0 ns, initial state before any clock edge)**:

| Signal | Value | Meaning |
|--------|-------|---------|
| `reset.io_sel [1:0]` | `11` | `io.sel(0) = 1`, `io.sel(1) = 1` |
| `reset.st0` | `0` | st0 register (RegInit, initialized to 0) |
| `reset.r_1` | `1` | RegNext of `io.sel(0)` — delayed value for past(sel(0), 1) |
| `reset.st0_follows_sel0_one_cycle_delay` | `1` | **Assertion FAILED** (1 = failure) |
| `reset.resetCounter.timeSinceReset [31:0]` | `0` | Zero cycles elapsed since reset |
| `reset.resetCounter.flag` | `1` | Guard signal — **incorrectly true** when timeSinceReset = 0 |
| `reset.resetCounter.notChaos` | `1` | Design not in chaotic state |
| `reset.hasBeenReset` | `1` | Reset state indicator |
| `reset.pending` | `0` | Timer pending flag |
| `reset.nextPending` | `1` | Next value of pending (will become 1 after clock edge) |
| `reset.timer [1:0]` | `00` | Timer value |
| `reset._nextTimer_T_2 [1:0]` | `01` | Next timer value (1) |
| `reset.clock` | `1` | Clock is high at the evaluation point |

## 4. Root Cause Analysis

### Root Cause Category: **Setup Error** — ResetCounter flag is incorrectly asserted at time 0, defeating the `past()` guard

### Bug Location
The bug is in the **ResetCounter module's `flag` signal generation** (inside the chiselFv `Formal` trait infrastructure). The `flag` signal is the guard that `past()` relies on to prevent first-cycle assertion evaluation.

### Description of the Bug

The `past()` function is designed to prevent first-cycle assertion failures by wrapping the assertion body in a guard condition. Per the source code comments:

> *"past() guards with timeSinceReset >= 1.U, ensuring RegNext has captured at least one meaningful sel(0) value before the check."*

The guard should only allow the assertion to fire when `timeSinceReset >= 1`, i.e., after at least one clock cycle has elapsed since reset. 

However, the waveform reveals a **contradiction** at time 0:

| Signal | Actual Value | Expected Value (for proper guard) |
|--------|-------------|-----------------------------------|
| `reset.resetCounter.timeSinceReset [31:0]` | `0` | `0` |
| `reset.resetCounter.flag` | `1` | **`0`** (since 0 >= 1 is false) |
| Guard condition `(flag == 1)` | **Passes** | Should **block** |

At time 0:
- `timeSinceReset = 0` (zero cycles have elapsed since reset — correct initial value)
- `flag = 1` (guard claims enough cycles have elapsed — **INCORRECT**)
- Because `flag = 1`, the `past()` guard passes, and the assertion evaluates:
  - `st0 (= 0) === r_1 (= 1)` → `0 === 1` → **FALSE** → assertion fails

### Why This Is a Setup Error (Not a DUT Bug or Assertion Error)

1. **The design logic is correct**: `st0 := io.sel(0)` correctly captures bit 0 of the select input. After one clock cycle, `st0` would become `1`, matching the previous `sel(0)=1`.

2. **The assertion is correct**: The check `st0 === prev` is the right way to verify one-cycle-delay behavior. If evaluated after the first cycle, `st0` would equal the previous `sel(0)` value.

3. **The setup is broken**: The `resetCounter.flag` signal is `1` at time 0 even though `timeSinceReset = 0`. This means:
   - The `flag` is **not** computed as `timeSinceReset >= 1.U` (which would give `0`)
   - The `flag` is likely computed as `!reset` or `timeSinceReset >= 0.U` (always true)
   - OR the `timeSinceReset` counter and the `flag` combinational logic are out of sync in the initial state

### Evidence Summary

The complete chain of causation:

1. At time 0 (initial state before any clock edge), `timeSinceReset = 0` — no cycles have elapsed
2. Despite this, `resetCounter.flag = 1` — the guard incorrectly signals "ready"
3. The `past()` guard passes, calling the assertion body with `r_1 = 1` (uninitialized RegNext of `io.sel(0)`)
4. The assertion checks `st0 (0) === r_1 (1)` → **false**
5. Assertion `st0_follows_sel0_one_cycle_delay` fails at time 0

### Recommended Fix

The `resetCounter.flag` signal must be `0` when `timeSinceReset < 1` (i.e., before the first clock edge after reset). The flag should only become `1` after at least one cycle has elapsed. This requires fixing the `ResetCounter` module's `flag` computation — ensuring the relationship is:
```
flag := timeSinceReset >= depth.U
```
where `depth = 1` for `past(signal, 1)`. With this fix, the guard would block the assertion at time 0, and the assertion would only fire starting from cycle 1, where `st0` would have captured the first value of `io.sel(0)`.
