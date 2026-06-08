# Counterexample Analysis Report: Hanoi.from_peg_valid

## 1. Verification Environment

### Top Module
- **Module**: `Hanoi` (package: `llmverify`)
- **Source**: `newHanoi.scala`
- **Generated Verilog**: `generated/Hanoi.sv`

### Key Components
- **`disc[19:0]`**: Array of 20 registers (2 bits each), each storing the peg position (A=0, B=1, C=2) of a disc. Initialized to all A (0).
- **`io_from`**: 2-bit input specifying the source peg for a move.
- **`io_to`**: 2-bit input specifying the destination peg for a move.
- **`sizeFrom`**: Wire computing the index of the smallest disc on the `from` peg (using a priority encoder from the highest index downward).
- **`sizeTo`**: Wire computing the smallest disc index on the `to` peg.
- **`legal`**: Wire indicating whether a proposed move is legal (`sizeFrom < 20` AND `sizeFrom < sizeTo`).
- **`hasBeenReset`**: Formal verification reset flag that enables assertions after one reset cycle.

### Connections
- `io.from` and `io.to` are unconstrained primary inputs to the module.
- The design implements the Tower of Hanoi logic — it tracks disc positions and determines whether a proposed move is legal.

### Design Under Test Description
The Hanoi module implements a Tower of Hanoi game state tracker. It maintains the positions of 20 discs across 3 pegs (A=0, B=1, C=2). Given a proposed move `from → to`, it computes whether the move is legal (source peg non-empty, moving disc smaller than target peg's top disc). If legal, it updates the disc position. It also computes a `done` signal indicating all discs are on peg B.

---

## 2. Violated Assertion

### Full Assertion Name
`Hanoi.from_peg_valid`

### Code Snippet

**Chisel source** (`newHanoi.scala`, line 52):
```scala
fvAssert(io.from <= 2.U, "from_peg_valid")
```

**Generated Verilog** (`generated/Hanoi.sv`, lines 242-243):
```verilog
from_peg_valid:
    assert property (@(posedge clock) disable iff (~hasBeenReset) io_from != 2'h3);
```

### Property Description
The assertion checks that the input `io.from` is a valid peg value. Since the Hanoi pegs are encoded as A=0, B=1, C=2 using 2-bit values, `io.from` must be ≤ 2. In other words, value 3 (binary `11`) is an invalid peg encoding and should never appear.

### File Location
- **Chisel**: `newHanoi.scala`, line 52
- **Verilog**: `generated/Hanoi.sv`, line 242 (label `from_peg_valid:`)

---

## 3. Waveform Information

### Waveform File
- **Full path**: `verilog/extra_bench/newhanoi/Hanoi.from_peg_valid.fst`
- **Format**: FST (Fast Signal Trace)

### Time Range
- **Duration**: 1 cycle (0 ns → 10 ns)
- **Key Time Points**:
  - `t = 0 ns`: Clock posedge, assertion check occurs
  - `t = 10 ns`: End of cycle

### Critical Signal Values at Failure Point (t = 0 ns)

| Signal | Value | Meaning |
|--------|-------|---------|
| `Hanoi.io_from [1:0]` | `11` (binary) = **3** (decimal) | Input `from` peg — **INVALID** (should be 0, 1, or 2) |
| `Hanoi.io_to [1:0]` | `11` (binary) = **3** (decimal) | Input `to` peg — also invalid |
| `Hanoi.clock` | `1` | Positive edge of clock — assertion is sampled |
| `Hanoi.:jasper_formal_reset` | `0` | Not in reset |
| `Hanoi.hasBeenReset` | `1` | System has been reset, assertion is active (not disabled) |
| `Hanoi.legal` | `0` | No legal move (because sizeFrom = 20, i.e., no disc on peg 3) |
| `Hanoi.sizeFrom [4:0]` | `10100` (binary) = **20** (decimal) | No disc found on peg 3 |
| All `disc_*` registers | `00` (binary) = 0 | All discs initialized to peg A |
| `Hanoi.from_peg_valid` (assertion monitor signal) | `1` | Assertion status signal in JasperGold FST |

### Analysis of Assertion Status Signal
The signal `Hanoi.from_peg_valid` shows value `1` at time 0. In JasperGold FST traces, this signal represents the formal tool's assertion monitor status. When `io_from = 3` at posedge clock with `hasBeenReset = 1` (assertion active), the condition `io_from != 2'h3` evaluates to **false**, causing the assertion to **fail**. The counterexample is triggered by the tool finding this violation.

---

## 4. Root Cause Analysis

### Error Category: **Setup Error** (Category 3)

### Root Cause: Missing Input Constraints (Assumptions)

The `io_from` and `io_to` inputs are **unconstrained** in the formal verification environment. The formal tool is free to assign any 2-bit value (0, 1, 2, or 3) to these inputs. Since the tool explores all possible input combinations, it trivially discovers that setting `io_from = 3` (binary `11`) violates the assertion `io.from <= 2.U`.

### Details

**Buggy Location**: `newHanoi.scala`, lines 51-55

```scala
// 1. Input peg values are valid (only A=0, B=1, C=2 allowed)
fvAssert(io.from <= 2.U, "from_peg_valid")     // Line 52 - Violated
fvAssert(io.to <= 2.U, "to_peg_valid")         // Line 53
fvAssert(io.from =/= io.to, "from_to_different") // Line 54
```

These three lines use **`fvAssert`** (assertions) to check input validity. In formal verification, inputs to a module are environment-driven — they can take any value unless constrained. To restrict the environment (inputs), we need **`fvAssume`** (assumptions), not assertions.

**What the Chisel code does**: It asserts that inputs must be valid peg values. But since there's no assumption constraining the inputs, the formal tool can assign `io_from = 3`, making the assertion fail. The tool finds this counterexample in the very first cycle.

### Evidence from Waveform

1. **`Hanoi.io_from [1:0]` = `11` (3)** at t = 0 ns and remains `11` at t = 10 ns.
2. **`Hanoi.hasBeenReset` = `1`** — the formal reset sequence has completed, so the assertion's disable condition (`~hasBeenReset`) is false, meaning the assertion is **active**.
3. **`Hanoi.clock` = `1`** — valid posedge for sampling the assertion.
4. The condition `io_from != 2'h3` evaluates to `3 != 3` = **false**, causing the assertion to fail.

### Why This Is Not a DUT Bug

The design logic (disc tracking, move legality checking, done detection) is functionally correct. If the inputs were constrained to valid values (0, 1, 2), the other assertions about legal moves, disc stability, etc., would need to be checked meaningfully. The failure is purely a verification environment configuration issue.

### Recommended Fix

Replace the `fvAssert` calls on input properties with **`fvAssume`** calls to properly constrain the formal verification environment:

```scala
// Constrain inputs to valid peg values (A=0, B=1, C=2)
fvAssume(io.from <= 2.U, "from_peg_valid_assume")
fvAssume(io.to <= 2.U, "to_peg_valid_assume")
fvAssume(io.from =/= io.to, "from_to_different_assume")
```

Or, if the intent is to verify that the design properly handles ALL input values (including invalid ones), then the assertions should be kept but the design should be modified to handle invalid inputs gracefully. The more standard approach is:
1. Use `fvAssume` to constrain inputs to valid ranges.
2. Use `fvAssert` to verify that under valid inputs, the design behaves correctly.
