# Counterexample Analysis Report: `gray.init_regs`

## 1. Verification Environment

- **Top Module**: `gray` (in package `llmverify`, file `gray.scala`)
- **Module Structure**: A simple sequential circuit implementing a 3-bit XOR-based shift register:
  - Three registers: `p`, `q`, `r` (all `RegInit(0.B)`)
  - Input `io.i` feeds into `p`
  - `p` feeds into `q`
  - Output `io.z` is computed as `p ^ q ^ r`, and also feeds back into `r`
- **Formal Verification Engine**: ChiselFv / JasperGold
- **Waveform File**: `gray.init_regs.fst`

## 2. Violated Assertion

- **Assertion Name**: `init_regs` (from waveform filename `gray.init_regs.fst`)
- **Source Location**: `gray.scala`, line 35
- **Code Snippet**:
  ```scala
  // Reset initialization: all registers start at 0 after reset (cycle 0)
  assertAt(0.U, p === 0.B && q === 0.B && r === 0.B, "init_regs")
  ```
- **Generated Verilog (from compiled output)**:
  ```verilog
  init_regs: assert property (@(posedge clock) disable iff (~hasBeenReset) ~p & ~q & ~r);
  ```
- **Natural Language Property**: "After reset, all three internal registers `p`, `q`, `r` should be initialized to 0 at cycle 0."

## 3. Waveform Information

- **Full Path**: `verilog/extra_bench/gray/gray.init_regs.fst`
- **Time Range**: 0 ns → 20 ns (2 clock cycles)
- **Clock Period**: 10 ns (clock toggles at 0, 5, 10, 15 ns)

### Key Signal Values at Cycle Boundaries

| Time | Cycle | clock | p | q | r | io_i | io_z | init_regs | hasBeenReset |
|------|-------|-------|---|---|---|------|------|-----------|--------------|
| 0 ns | 0     | 1     | 0 | 0 | 0 | 1    | 0    | **1** (pass)| 1 |
| 10 ns| 1     | 1     | 1 | 0 | 0 | 1    | 1    | **0** (fail)| 1 |

### Assertion Status
- `init_regs` = 1 at cycle 0 (assertion passes)
- `init_regs` = 0 at cycle 1 (assertion **fails**)

## 4. Root Cause Analysis

### Type: ASSERTION ERROR

**The assertion encoding is incorrect for the intended check. This is not a bug in the DUT.**

#### Why This Is Not a DUT Bug

The three registers `p`, `q`, `r` are defined as `RegInit(0.B)` in source code (lines 16-18). The waveform confirms that at cycle 0 (time 0 ns), all three are indeed 0:

- `p` = 0
- `q` = 0
- `r` = 0

The registers are properly initialized. The design code is correct.

#### Why the Assertion Fails

The assertion's intent (stated in the comment on line 33) is: *"Reset initialization: all registers start at 0 after reset (cycle 0)"* — a **one-time check at cycle 0** only.

However, the generated Verilog assertion is:
```verilog
init_regs: assert property (@(posedge clock) disable iff (~hasBeenReset) ~p & ~q & ~r);
```

This is an **unconditional global invariant** — it checks `p===0 && q===0 && r===0` at **every** positive clock edge, with no cycle constraint to restrict it to only cycle 0.

#### The Sequence of Events Causing Failure

1. **Cycle 0 (time 0 ns)**: All registers are 0 after reset. The input `io_i=1` is sampled at the posedge. The assertion passes.

2. **Between cycles (time 0–10 ns)**: The DUT's sequential logic fires:
   - `p := io.i` → `p` gets the value 1 (from `io_i=1`)
   - `q := p` → `q` gets the value 0 (old value of `p`)
   - `r := io.z` → `r` gets the value 0 (old output `io_z=0`)

3. **Cycle 1 (time 10 ns)**: At this posedge:
   - `p = 1` (updated from `io_i=1`)
   - `q = 0` (still 0, unchanged from previous cycle)
   - `r = 0` (still 0, unchanged)
   - The assertion checks `~p & ~q & ~r` = `~1 & ~0 & ~0` = `0 & 1 & 1` = **0** → **FAILS**

The key observation from the waveform is that `io_i=1` at ALL time points (time 0 ns onward). Since `p` gets updated every cycle (`p := io.i`), it becomes 1 on the very first clock edge, violating the assertion at cycle 1.

#### Root Cause Summary

The `assertAt(0.U, ...)` construct should check the given condition only at cycle 0 (the initial state), but the generated Verilog assertion lacks any cycle constraint. The assertion is lowered to a simple `assert property (...)` invariant that fires at every clock cycle, making it impossible for `p` (which is assigned `io.i` combinatorially) to satisfy `p===0` beyond cycle 0.

**Incorrect Assertion SVA** (what was generated):
```verilog
assert property (@(posedge clock) disable iff (~hasBeenReset) ~p & ~q & ~r);
```
This checks the condition at EVERY cycle — too strong.

**What should have been generated** (to match the intent):
```verilog
// Check only at the first clock cycle after reset
// (Some mechanism to restrict to cycle 0 only, e.g., using $past or a cycle counter)
```

### Corrective Recommendation

The assertion should either be:
1. **Removed** if the intent was only to document the `RegInit(0.B)` initialization (which is already guaranteed by Chisel semantics), or
2. **Rewritten** to use a different verification approach if a cycle-0-specific check is truly needed (e.g., by using an auxiliary signal that tracks the first cycle after reset).
