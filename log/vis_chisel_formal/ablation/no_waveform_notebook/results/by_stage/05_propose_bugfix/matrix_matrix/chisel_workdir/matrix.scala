package llmverify

import chisel3._
import chisel3.util._
import chiselFv._

/**
 * A simple NxN matrix-matrix multiplication module.
 * Computes C = A * B where A, B, C are NxN matrices of UInt<W>.
 */
class MatrixMul(val N: Int = 2, val W: Int = 8) extends Module with Formal {
  val io = IO(new Bundle {
    val A = Input(Vec(N, Vec(N, UInt(W.W))))
    val B = Input(Vec(N, Vec(N, UInt(W.W))))
    val C = Output(Vec(N, Vec(N, UInt((2*W).W))))
  })

  // Inline shift-and-add multiplier to avoid black-box multiplier
  // primitives that formal tools cannot reason about.
  private def mul(a: UInt, b: UInt): UInt = {
    val w = a.getWidth
    // For each bit of b: if set, add a shifted left by the bit position
    (0 until w).map(i => Mux(b(i), a << i.U, 0.U((2*w).W))).reduce(_ + _)
  }

  // Compute C[i][j] = sum over k of A[i][k] * B[k][j]
  for (i <- 0 until N) {
    for (j <- 0 until N) {
      val products = for (k <- 0 until N) yield {
        mul(io.A(i)(k), io.B(k)(j))
      }
      io.C(i)(j) := products.reduce(_ + _)
    }
  }

  // ─── Formal Verification Assertions ─────────────────────────────────

  // ── Safety 1: Zero matrix property ──
  // If matrix A is all zeros, then the output C must be all zeros.
  val aAllZero = io.A.map(row => row.map(_ === 0.U).reduce(_ && _)).reduce(_ && _)
  val cAllZero = io.C.map(row => row.map(_ === 0.U).reduce(_ && _)).reduce(_ && _)
  fvAssert(!aAllZero || cAllZero, "zero_A_implies_zero_C")

  // If matrix B is all zeros, then the output C must be all zeros.
  val bAllZero = io.B.map(row => row.map(_ === 0.U).reduce(_ && _)).reduce(_ && _)
  fvAssert(!bAllZero || cAllZero, "zero_B_implies_zero_C")

  // If both A and B are all zeros, then C must be all zeros (redundant but explicit).
  fvAssert(!(aAllZero && bAllZero) || cAllZero, "zero_A_and_B_implies_zero_C")

  // ── Safety 2: Identity matrix property ──
  // When A is the N×N identity matrix (diagonal = 1, off-diagonal = 0),
  // the output C must equal B element-wise (zero-extended to (2*W).W).
  val aIsIdentity = (for (i <- 0 until N; j <- 0 until N) yield {
    if (i == j) io.A(i)(j) === 1.U else io.A(i)(j) === 0.U
  }).reduce(_ && _)

  for (i <- 0 until N; j <- 0 until N) {
    fvAssert(
      !aIsIdentity || io.C(i)(j) === io.B(i)(j),
      s"identity_A_C_eq_B_${i}_${j}"
    )
  }

  // ── Safety 3: Symmetric identity ──
  // When B is the N×N identity matrix, the output C must equal A (zero-extended).
  val bIsIdentity = (for (i <- 0 until N; j <- 0 until N) yield {
    if (i == j) io.B(i)(j) === 1.U else io.B(i)(j) === 0.U
  }).reduce(_ && _)

  for (i <- 0 until N; j <- 0 until N) {
    fvAssert(
      !bIsIdentity || io.C(i)(j) === io.A(i)(j),
      s"identity_B_C_eq_A_${i}_${j}"
    )
  }

  // ── Safety 4: Output boundedness ──
  // Each output element C[i][j] equals sum_k(A[i][k] * B[k][j]).
  // Because the output has (2*W) bits while the true
  // sum may need (2*W + log2(N)) bits, we assert that when
  // all input values are small enough the output matches
  // the exact mathematical result (no overflow truncation).
  //
  // A[i][k] < 2^(W-1) and B[k][j] < 2^(W-1) guarantees each
  // product < 2^(2W-2); then N such products < N * 2^(2W-2).
  // For N=2, W=8 → max sum = 2 * 2^14 = 2^15 = 32768 < 2^16, so
  // the result fits in (2*W)=16 bits without overflow.
  val inputLowBound = 1.U << (W - 1).U
  val inputsSmall = (io.A.map(row => row.map(_ < inputLowBound).reduce(_ && _)).reduce(_ && _) &&
                     io.B.map(row => row.map(_ < inputLowBound).reduce(_ && _)).reduce(_ && _))

  for (i <- 0 until N; j <- 0 until N) {
    val trueSum = (0 until N).map(k => mul(io.A(i)(k), io.B(k)(j))).reduce(_ +& _)
    fvAssert(!inputsSmall || io.C(i)(j) === trueSum,
             s"no_overflow_when_inputs_small_${i}_${j}")
  }
}
