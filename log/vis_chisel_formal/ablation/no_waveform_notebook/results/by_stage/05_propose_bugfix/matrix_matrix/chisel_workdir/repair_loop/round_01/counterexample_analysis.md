# Counterexample Analysis Report: `zero_A_implies_zero_C`

## 1. Verification Environment

- **Top module**: `MatrixMul` (Chisel class in `matrix.scala`)
- **Parameters**: N=2, W=8 (2×2 matrices, 8-bit unsigned elements)
- **Components**: 
  - Input vectors `io.A` and `io.B` (each 2×2 UInt<8>)
  - Output vector `io.C` (2×2 UInt<16>)
  - 8 multiplier instances (`mult_15`, `mult_30`, `mult_45`, `mult_55`, `mult_70`, `mult_80`, `mult_90`, `mult_95`)
  - Combinational tree: `C[i][j] = Σ_k A[i][k] × B[k][j]`
- **Formal library**: Chisel `chiselFv` with `fvAssert`
- **Design description**: A combinational N×N matrix-matrix multiplier computing C = A × B

## 2. Violated Assertion

- **Assertion name**: `zero_A_implies_zero_C`
- **Code snippet** (from `matrix.scala`, lines 37–43):
  ```scala
  val aAllZero = io.A.map(row => row.map(_ === 0.U).reduce(_ && _)).reduce(_ && _)
  val cAllZero = io.C.map(row => row.map(_ === 0.U).reduce(_ && _)).reduce(_ && _)
  fvAssert(!aAllZero || cAllZero, "zero_A_implies_zero_C")
  ```
- **Property**: If matrix A is all zeros, then the output matrix C must be all zeros. Formally: `aAllZero ⇒ cAllZero`.
- **File location**: `matrix.scala`, line 41

## 3. Waveform Information

- **Waveform file**: `verilog/extra_bench/matrix_matrix/MatrixMul.zero_A_implies_zero_C.fst`
- **Time range**: 0 ns → 10 ns (1 clock cycle)
- **Key time points**: The entire waveform is stable with no transitions after time 0.

### Critical Signal Values at Failure Point (times 0 ns and 10 ns):

| Signal | Value | Interpretation |
|--------|-------|----------------|
| `io_A[0][0]` | 0x00 | Zero |
| `io_A[0][1]` | 0x00 | Zero |
| `io_A[1][0]` | 0x00 | Zero |
| `io_A[1][1]` | 0x00 | Zero |
| `io_B[0][0]` | 0x00 | Zero |
| `io_B[0][1]` | 0x00 | Zero |
| `io_B[1][0]` | 0x00 | Zero |
| `io_B[1][1]` | 0x00 | Zero |
| `aAllZero` | 1 | A is all zeros ✓ |
| `cAllZero` | 0 | C is NOT all zeros ✗ |
| `io_C[0][0]` | 0xFD7B | Non-zero (should be 0) |
| `io_C[0][1]` | 0x78E4 | Non-zero (should be 0) |
| `io_C[1][0]` | 0x7FE4 | Non-zero (should be 0) |
| `io_C[1][1]` | 0x7FE4 | Non-zero (should be 0) |
| `mult_15.left` | 0x0000 | Zero (multiplier input) |
| `mult_15.right` | 0x0000 | Zero (multiplier input) |
| `mult_15.out` | 0x00000211 | **Non-zero** despite zero inputs! |
| `mult_30.left` | 0x0000 | Zero |
| `mult_30.right` | 0x0000 | Zero |
| `mult_30.out` | 0x0000FBEA | **Non-zero** despite zero inputs! |
| All 8 multipliers' left/right | All zero | All inputs are zero |
| All 8 multipliers' out | All non-zero | All outputs are non-deterministic |

## 4. Root Cause Analysis

### Root Cause Category: **Setup Issue** (Incorrect Top Module Setup)

### Bug Location
The issue is not in the Chisel design logic itself, but in the formal verification tool setup. The multiplier primitives (`mult_*`) are instantiated as black-box modules that the formal verification tool cannot reason about.

### Description of the Issue

**What happens**: 
1. The Chisel compiler synthesizes `io.A(i)(k) * io.B(k)(j)` into dedicated hardware multiplier modules (the `mult_*` instances).
2. In the formal verification environment, these multiplier modules are treated as **black boxes** — the formal tool has no internal model for their behavior.
3. Black-box outputs are treated as **non-deterministic** (unconstrained free variables). The formal tool can assign any value to them.
4. Even though both multiplier inputs are demonstrably zero (confirmed in the waveform), the multiplier outputs can take arbitrary values (observed: 0x0211, 0xFBEA, 0x78E3, etc.).
5. These arbitrary multiplier outputs propagate through the adder tree to produce non-zero values on `io.C`.
6. Since `aAllZero=1` (correctly computed from the zero inputs) but `cAllZero=0` (because C is non-zero due to the black-box multipliers), the assertion `!aAllZero || cAllZero` evaluates to `false`.

**Evidence from waveform**:
```
mult_15.left  = 0x0000, mult_15.right  = 0x0000, mult_15.out  = 0x00000211 ← contradicts 0×0=0
mult_30.left  = 0x0000, mult_30.right  = 0x0000, mult_30.out  = 0x0000FBEA ← contradicts 0×0=0
mult_45.left  = 0x0000, mult_45.right  = 0x0000, mult_45.out  = 0x0078E3   ← contradicts 0×0=0
mult_55.left  = 0x0000, mult_55.right  = 0x0000, mult_55.out  = 0x00000001 ← contradicts 0×0=0
...
```

All 8 multipliers have zero inputs but non-zero outputs. This is the classic signature of unmodeled black-box primitives in formal verification.

### Why the Assertion Fails

The assertion `fvAssert(!aAllZero || cAllZero, "zero_A_implies_zero_C")` checks that whenever A is all zeros, C must be all zeros. Since:
- `aAllZero = 1` (A is indeed all zeros)
- `cAllZero = 0` (C is non-zero due to black-box multiplier outputs)
- The condition `!aAllZero || cAllZero` evaluates to `0`

The assertion fails.

### Fix Recommendation

To resolve this issue, the formal verification setup must provide proper models for the multiplier primitives. Options include:

1. **Provide multiplier formal models**: Supply the formal tool with Verilog implementations or cut-point models for the `mult_*` modules so the tool can compute correct results.

2. **Use RTL-level multiplication**: Ensure the Chisel compilation generates inline multiplication (`*` operator) rather than black-box multiplier instances, or provide the formal tool with the full RTL for the multipliers.

3. **Add multiplier constraints**: Add formal assumptions (`assume` statements) or cut-points that constrain the multiplier outputs to match their inputs (e.g., `assume(mult_15.out === mult_15.left * mult_15.right)`).

4. **Avoid black-box synthesis**: Configure the Chisel compiler to avoid instantiating black-box multiplier primitives when generating Verilog for formal verification.

The design logic itself (matrix multiplication algorithm and assertion properties) is correct — the failure is purely an artifact of the formal verification environment lacking multiplier models.
