# Counterexample Analysis: swap.swap_values_correct

## 1. Verification Environment

### Top Module
- **Module**: `swap` (from `swap.scala`)
- **Parameters**: K=3, Nm1=7
- **Total signals**: 42 (including 8-element register array x[0..7], combinational p/m, formal assertion registers)

### Key Components and Connections
- **Input**: `io_i` (3-bit unsigned, selects swap index)
- **Register array**: `x[0..7]` — initial values [0, 1, 2, 3, 4, 5, 6, 7]
- **Combinational logic**:
  - `p = Mux(io_i >= 7, 7, io_i)` — clamp index to [0,7]
  - `m = Mux(p === 0, 7, p-1)` — the "other" index (circular pair)
- **Swap logic** (synchronous, posedge clock):
  - `tmp := x(p)` — capture old x(p)
  - `x(p) := x(m)` — x(p) gets old x(m)
  - `x(m) := tmp` — x(m) gets old x(p)
- **Assertion registers** (RegNext, no reset):
  - `prev_xm = RegNext(x(m))` — captures old x(m) after swap
  - `prev_tmp = RegNext(tmp)` — captures old x(p) after swap
  - `REG = RegNext(stable)` — delayed stability indicator

### Design Under Test Description
The `swap` module swaps the values at positions `p` and `m` in the register array `x` every cycle. The swap uses a temporary register `tmp` to hold `x(p)` before overwriting it. The formal properties verify that after a completed swap, `x(p)` equals the previous `x(m)`, `x(m)` equals the previous `tmp` (which held the previous `x(p)`), and the multiset of values is preserved.

---

## 2. Violated Assertion

### Assertion Name
`swap_values_correct` (from waveform filename `swap.swap_values_correct.fst`)

### File Location
- **Source**: `swap.scala`, lines 96–99

### Code Snippet
```scala
AssertProperty(
  RegNext(stable) |-> Sequence(x(p) === prev_xm && x(m) === prev_tmp),
  None, None, Some("swap_values_correct")
)
```

### Generated Verilog Assertion (line 88 of `generated/swap.sv`)
```verilog
swap_values_correct: assert property (REG |-> _GEN[p] == prev_xm & _GEN[m] == prev_tmp);
```

### Property Description
**When `REG` (RegNext(stable)) is true** (i.e., p and m have been stable for at least one full cycle), then after the swap completes:
- `x(p)` must equal the previous value of `x(m)` (captured in `prev_xm`)
- `x(m)` must equal the previous value of `tmp` (captured in `prev_tmp`)

This verifies that the swap operation correctly exchanges the values at indices p and m.

---

## 3. Waveform Information

### Waveform File
`verilog/extra_bench/swap/swap.swap_values_correct.fst`

### Time Range
0 ns → 10 ns (1 clock cycle)

### Key Time Points and Signal Values

| Time | Signal | Value | Hex |
|------|--------|-------|-----|
| 0 ns | `swap.REG` | 1 | — |
| 0 ns | `swap.reset` | 0 | — |
| 0 ns | `swap.clock` | 1 | — |
| 0 ns | `swap.io_i [2:0]` | `001` | 0x1 |
| 0 ns | `swap.p [2:0]` | `001` | 0x1 |
| 0 ns | `swap.m [2:0]` | `000` | 0x0 |
| 0 ns | `swap.x_0 [2:0]` | `000` | 0x0 |
| 0 ns | `swap.x_1 [2:0]` | `001` | 0x1 |
| 0 ns | `swap.prev_xm [2:0]` | `000` | 0x0 |
| 0 ns | `swap.prev_tmp [2:0]` | `000` | 0x0 |
| 0 ns | `swap.p_stable_REG [2:0]` | `000` | 0x0 |
| 0 ns | `swap.m_stable_REG [2:0]` | `000` | 0x0 |

### Signal States at Failure Point (0 ns)

All signals remain constant across the entire trace (0 ns → 10 ns). No clock edge occurs between time 0 and the failure.

**Assertion evaluation at time 0 ns:**
```
REG |-> _GEN[p] == prev_xm & _GEN[m] == prev_tmp
1   |-> _GEN[1] == 0       & _GEN[0] == 0
1   |-> 1       == 0       & 0       == 0
1   |-> false              & true
1   |-> false
→ ASSERTION FAILS
```

---

## 4. Root Cause Analysis

### Buggy Location
- **File**: `swap.scala`
- **Line**: 97 (the `RegNext(stable)` inside the `AssertProperty` call)
- **Also**: Lines 73–74, 86–87 (all `RegNext`-based registers with no explicit initialization)

### Root Cause Type: **Assertion Error / Setup Error (Class 2/3)**

The registers `prev_xm`, `prev_tmp`, `p_stable_REG`, `m_stable_REG`, and `REG` are all created using `RegNext(...)` in Chisel, which generates registers **without reset initialization**. In formal verification, such uninitialized registers can be assigned **any value** by the formal solver — they are treated as free variables.

#### The Problem Chain

1. **`REG` is uninitialized**: `RegNext(stable)` (line 97) produces a register with no reset. The formal tool chooses `REG = 1` at time 0.

2. **The designer's assumption is violated**: The comment on lines 90–95 states:
   > "Use RegNext(stable) to skip the initial reset cycle where p and m appear 'stable' trivially [...] RegNext(stable) ensures at least one cycle of stable p,m has elapsed before checking the swap result."

   This assumes `RegNext(stable)` starts at 0 (as it would in simulation). However, the formal tool can set it to any initial value, and chose 1.

3. **Premature activation**: With `REG = 1` in the initial state (before any clock edge), the assertion's antecedent is true, forcing evaluation of the consequent. But no swap has occurred yet — `prev_xm = 0` and `prev_tmp = 0` are just the uninitialized/initial register values, not meaningful swapped values.

4. **Mismatch**: At time 0:
   - `p = 1`, `m = 0`
   - `x(1) = 1`, `x(0) = 0`
   - `prev_xm = 0` (should equal x(1) = 1 after a proper swap, but swap hasn't happened)
   - `x(1) === prev_xm` → `1 === 0` → **false**

#### Why This Is Not a DUT Logic Bug

The actual swap logic (`x(p) := x(m)`, `x(m) := tmp`) is correct. After one clock cycle with the given inputs (io_i=1 → p=1, m=0), the swap would correctly exchange `x(1)` and `x(0)`. The failure is entirely due to the assertion activating in the **pre-swap** initial state because the `RegNext` gate register is uninitialized.

### Required Fix

The fix must ensure that `REG` (the stability-gating register) is **properly initialized to 0** so the assertion is not checked before at least one cycle of execution.

**Option A — Use `RegInit` (recommended):**

Replace lines 86–88 and 96–98 in `swap.scala`:

```scala
// Before (lines 86-88):
val p_stable = RegNext(p) === p
val m_stable = RegNext(m) === m
val stable = p_stable && m_stable

// Before (line 97):
RegNext(stable) |-> Sequence(x(p) === prev_xm && x(m) === prev_tmp)
```

```scala
// After:
val p_stable = RegNext(p) === p
val m_stable = RegNext(m) === m
val stable = p_stable && m_stable

// Initialize REG explicitly to 0:
val REG = RegInit(false.B)
REG := stable

AssertProperty(
  REG |-> Sequence(x(p) === prev_xm && x(m) === prev_tmp),
  None, None, Some("swap_values_correct")
)
```

**Option B — Add a reset guard to the assertion:**

```scala
AssertProperty(
  !reset.asBool |-> (RegNext(stable) |-> Sequence(x(p) === prev_xm && x(m) === prev_tmp)),
  None, None, Some("swap_values_correct")
)
```

**Note**: Option A is more robust because it also prevents false activation from upstream uninitialized `RegNext(p)` and `RegNext(m)` registers. Option B only masks the problem at reset without addressing the underlying register initialization.

### Additional Signals to Fix (Optional but Recommended)

The same initialization issue affects `prev_xm` (line 73), `prev_tmp` (line 74), `p_stable_REG` (line 86), and `m_stable_REG` (line 87). While they happened to evaluate to their expected 0 values in this particular counterexample, they could also be assigned arbitrary values by the formal solver in other scenarios. Changing them to `RegInit(0.U(k.W))` with explicit assignment would be more robust.
