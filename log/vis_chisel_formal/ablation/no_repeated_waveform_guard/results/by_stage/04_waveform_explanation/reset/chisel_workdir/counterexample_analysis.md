# Counterexample Analysis Report: `reset.st1_toggles_every_cycle`

## 1. Verification Environment

- **Top Module**: `reset`
- **Design Under Test**: A simple state machine with three state bits (`st0`, `st1`, `st2`), where `st1` toggles every cycle via `st1 := ~st1`
- **Key Components**:
  - `st1`: Register initialized to 0, toggled every cycle
  - `REG`: `RegNext(st1)` — captures the previous cycle's value of `st1`
  - `hasBeenReset` / `hasBeenResetReg`: Mechanism intended to gate assertions until after the first reset
  - `ResetCounter`: Module providing `notChaos` flag that gates assertion checking
- **Clock**: posedge clock at 10 ns period

## 2. Violated Assertion

- **Assertion Name**: `st1_toggles_every_cycle` (from waveform filename: `reset.st1_toggles_every_cycle.fst`)
- **Source File**: `reset.scala`
- **Assertion Code**:

```scala
// In the reset module:
val st1 = RegInit(0.U(1.W))
// ...
st1 := ~st1
// ...
fvAssert(st1 === ~RegNext(st1), "st1_toggles_every_cycle")
```

- **Generated Verilog** (in `generated/reset.sv`):

```verilog
st1_toggles_every_cycle:
    assert property (@(posedge clock) disable iff (~hasBeenReset) st1 == ~REG);
```

- **Property Description**: On every positive clock edge (when the design has been properly reset), `st1` must be the bitwise complement of its own value from the previous cycle. In other words, `st1` must toggle every cycle.

## 3. Waveform Information

- **Waveform File**: `verilog/extra_bench/reset/reset.st1_toggles_every_cycle.fst`
- **Time Range**: 0 ns → 10 ns (1 clock cycle)

### Critical Signal Values at Failure Point (time 0 ns, posedge clock):

| Signal | Value | Description |
|--------|-------|-------------|
| `reset.st1` | 0 | Current state bit st1 |
| `reset.REG` | 0 | RegNext(st1) — previous cycle's st1 |
| `reset.hasBeenReset` | 1 | `hasBeenResetReg === 1 && reset === 0` |
| `reset.reset` | 0 | Global reset signal (not asserted) |
| `reset.clock` | 1 | Rising edge of clock |
| `reset.hasBeenResetReg` | 1 | Formal tool chose X→1 initialization |
| `reset.st1_toggles_every_cycle` | 1 | Assertion evaluated (failing) |

### Assertion Check at time 0:
```
disable iff (~hasBeenReset) = disable iff (0) → assertion ACTIVE
st1 == ~REG → 0 == ~0 → 0 == 1 → FALSE → ASSERTION FAILURE
```

## 4. Root Cause Analysis

### Category: **Incorrect Assertion (`assertion_error`)**

The assertion `st1 === ~RegNext(st1)` fails on the very first cycle because both `st1` and `RegNext(st1)` are initialized to 0, and the assertion immediately checks that they are complements. This is impossible on cycle 0.

### Detailed Explanation

#### The Bug

The property `st1 === ~RegNext(st1)` is intended to verify that `st1` toggles every cycle. The toggle logic `st1 := ~st1` ensures:

- Cycle N: `st1 = V`
- Cycle N+1: `st1 = ~V`
- So: `st1 === ~RegNext(st1)` holds for all N ≥ 1

**However, on Cycle 0 (the very first cycle):**
- `st1` = 0 (RegInit(0.U(1.W)))
- `REG = RegNext(st1)` = 0 (initialized to an independent random value; in this case coincidentally 0)
- Check: `st1(0) == ~REG(0)` → `0 == ~0` → `0 == 1` → **FALSE → FAILURE**

The assertion checks the CURRENT state at the posedge, before any non-blocking assignments take effect. At time 0, both registers hold their initial values (both 0), so `st1 == ~REG` evaluates to `0 == 1`, which is false.

#### Why the `disable iff (~hasBeenReset)` Guard Doesn't Help

The `hasBeenReset` mechanism is supposed to disable assertions until after the design has been through a proper reset cycle:

```verilog
reg hasBeenResetReg;
initial hasBeenResetReg = 1'bx;          // X-initialized
wire hasBeenReset = hasBeenResetReg === 1'h1 & reset === 1'h0;

always @(posedge clock) begin
    if (reset) begin
        hasBeenResetReg <= 1'h1;          // Set during reset
        ...
    end
    ...
end
```

The assertion uses: `disable iff (~hasBeenReset)` — disabled when `hasBeenReset` is false.

**The problem**: At time 0, the formal tool initializes `hasBeenResetReg` from its X (unknown) initial value. The tool chose `hasBeenResetReg = 1`, and since `reset = 0`, this makes:
- `hasBeenReset = 1 & 1 = 1`
- `~hasBeenReset = 0`
- **Assertion is NOT disabled → it fires immediately**

This premature enabling means the assertion is checked on the very first cycle, before the toggle logic has had a chance to run even once.

#### Why Even Proper Reset Wouldn't Fix This

If `reset` were asserted for one cycle at time 0:
- During reset: `st1 <= 0`, `REG <= old_st1` (pre-reset random value)
- After reset: `st1 = 0`, `REG = old_st1` (random, e.g., 0 or 1)
- `hasBeenReset = 1`
- Assertion check: `st1 == ~REG → 0 == ~old_st1`
  - If old_st1 = 0: `0 == 1` → **FAIL** (50% chance)
  - If old_st1 = 1: `0 == 0` → PASS (50% chance)

Even with proper reset, the assertion has a 50% probability of failing due to the random initial value of `st1` being captured into `REG`.

#### Why the `past()` API Would Work Correctly

The ChiselFV framework provides a `past()` API that is properly guarded:

```scala
def past[T <: Data](value: T, n: Int)(block: T => Any): Unit = {
    when(notChaos && timeSinceReset >= n.U) {
      block(Delay(value, n))
    }
}
```

If the assertion had been written as:
```scala
past(st1, 1) { prev =>
    fvAssert(st1 === ~prev, "st1_toggles_every_cycle")
}
```

The `timeSinceReset >= 1.U` guard would ensure the assertion is only checked after at least one cycle has elapsed since reset, avoiding the first-cycle failure.

### Buggy Code Location

- **File**: `reset.scala`
- **Line**: The assertion `fvAssert(st1 === ~RegNext(st1), "st1_toggles_every_cycle")`
- **Problem**: The assertion is checked immediately at cycle 0, before the toggle logic can establish the expected relationship between `st1` and `RegNext(st1)`. The `fvAssert` wrapper's `when(notChaos)` guard is insufficient because `notChaos` can be active at time 0 (as seen in the waveform: `resetCounter.notChaos = 1`).

### Fix Recommendation

Replace the raw `fvAssert` with a properly delayed assertion using the `past()` API:

```scala
past(st1, 1) { prev =>
    fvAssert(st1 === ~prev, "st1_toggles_every_cycle")
}
```

This ensures the assertion is only evaluated after at least one cycle of non-chaos (i.e., after reset), giving `RegNext(st1)` / `REG` a chance to capture a meaningful previous value of `st1`.
