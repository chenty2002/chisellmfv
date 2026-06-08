# Counterexample Analysis: Matrix.write_sets_shadow_valid

## 1. Verification Environment

- **Top Module**: `Matrix` (class in package `llmverify`)
- **Module Parameters**: `MSB = 2`, `N = 8`, `L = 28`
- **Key Components**:
  - `M` — 28-bit memory array storing matrix bits
  - `offset` — 8-element offset array for triangular-storage position computation
  - `posn` — position register for accessing the linearized storage
  - `shadowValid` / `shadowData` — shadow registers tracking write history for write-read consistency checking
- **Design Under Test**: A triangular matrix storage system that maps 2D matrix coordinates (row, col) to linear positions in a 1D memory array. The design supports read/write operations and includes formal verification assertions.

## 2. Violated Assertion

- **Assertion Name**: `write_sets_shadow_valid` (from waveform filename `Matrix.write_sets_shadow_valid.fst`)
- **Code Snippet** (matrix.scala, lines 112–117):

```scala
// 6. SAFETY: Write sets shadowValid.
//    After any write to a non-diagonal element, the corresponding shadow
//    valid flag must be set, ensuring the write-read consistency check
//    becomes active.
assertImplies(!io.r_w && io.row =/= io.col,
    shadowValid(posn),
    "write_sets_shadow_valid")
```

- **Natural Language Property**: Every time a write occurs to a non-diagonal matrix element (`!io.r_w && io.row =/= io.col`), the corresponding shadow-valid flag at the computed position `posn` must be true.
- **File Location**: `matrix.scala`, lines 115–117

## 3. Waveform Information

- **Waveform File**: `verilog/extra_bench/matrix_matrix/Matrix.write_sets_shadow_valid.fst`
- **Duration**: 0 ns to 10 ns (1 cycle)
- **Key Time Point**: 0 ns (posedge of first clock cycle after reset)

### Critical Signal Values at Time 0 ns:

| Signal | Value | Meaning |
|--------|-------|---------|
| `Matrix.io_r_w` | 0 | Write operation |
| `Matrix.io_row [2:0]` | 000 (0) | Row address |
| `Matrix.io_col [2:0]` | 100 (4) | Column address |
| `Matrix.posn [4:0]` | 00000 (0) | Computed linear position (old value from reset) |
| `Matrix.shadowValid_0` | 0 | Shadow valid flag at position 0 is FALSE |
| `Matrix.shadowValid_1`–`27` | 0 | All other shadow valid flags are FALSE |
| `Matrix.write_sets_shadow_valid` | 1 | **Assertion fails** (1 = assertion error in formal tools) |

### Assertion Evaluation at Time 0:

```
premise = !io_r_w && io_row != io_col = !0 && (0 != 4) = true && true = true
conclusion = shadowValid(posn) = shadowValid(0) = 0 (false)
assertImplies(true, false) = assert(false) → FAILS!
```

## 4. Root Cause Analysis

### Root Cause Category: **Incorrect Assertion** (`assertion_error`)

### Buggy Code Location

**File**: `matrix.scala`, lines 115–117
```scala
assertImplies(!io.r_w && io.row =/= io.col,
    shadowValid(posn),
    "write_sets_shadow_valid")
```

### Description of the Bug

The assertion has a **timing mismatch**: it checks `shadowValid(posn)` (a register) in the **same cycle** that `shadowValid(posn)` is being written to. Since `shadowValid` is declared as a `RegInit` register:

```scala
val shadowValid = RegInit(VecInit(Seq.fill(L)(false.B)))
```

its assignment `shadowValid(posn) := true.B` (lines 101–103) uses **non-blocking semantics**—the updated value only becomes visible at the **next clock cycle**. However, the assertion evaluates `shadowValid(posn)` as a combinational read of the register's **current-cycle value**, which is still the initial value (`false`/0).

### Detailed Trace

1. **Cycle 0 (After Reset)**: All shadow valid registers are initialized to `false` (0).
2. **Cycle 0 (Posedge)**: The inputs `io_r_w=0, io_row=0, io_col=4` drive a write to non-diagonal element `(0,4)`.
3. **Same Cycle (Combinational Logic)**:
   - The `when(!io.r_w && io.row =/= io.col)` block executes:
     - `shadowValid(posn) := true.B` (scheduled for next cycle)
     - `shadowData(posn) := io.bitIn` (scheduled for next cycle)
   - The assertion evaluates: `assert(!premise || shadowValid(posn))`
     - `shadowValid(posn)` reads the **current** register value (still `0`/false because the update hasn't propagated yet)
     - `assert(!true || false)` → `assert(false)` → **FAILURE**
4. **Cycle 1 (Next Posedge)**: The register update would make `shadowValid(0) = true`, but the assertion already failed at cycle 0.

### Why This Is Not a DUT Bug

The design logic is correct. On every write to a non-diagonal element, `shadowValid(posn)` is correctly assigned `true.B`. The write-read consistency assertion (`write_read_consistency`) properly checks `shadowValid` on subsequent read operations, where the register has already been updated.

### Suggested Fix

The assertion should check the shadow-valid flag on the **next cycle** after the write, not the same cycle. A correct formulation would be:

```scala
// Check that writes to non-diagonal elements set shadow valid
// Use a one-cycle delayed version since registers update at the next posedge
val wasWrittenNonDiag = RegNext(!io.r_w && io.row =/= io.col, false.B)
val prevPosn = RegNext(posn)
fvAssert(!wasWrittenNonDiag || shadowValid(prevPosn),
    "write_sets_shadow_valid")
```

This checks that one cycle after a write, the corresponding shadow-valid flag has been properly set to `true`. Alternatively, the assertion could be restructured to use combinational wires for the property check, but the register-based approach aligns with the design's sequential semantics.
