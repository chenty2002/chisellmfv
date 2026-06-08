package llmverify

import chisel3._
import chisel3.util._

/**
 * A simple NxN matrix-matrix multiplication module.
 * Computes C = A * B where A, B, C are NxN matrices of UInt<W>.
 */
class MatrixMul(val N: Int = 2, val W: Int = 8) extends Module {
  val io = IO(new Bundle {
    val A = Input(Vec(N, Vec(N, UInt(W.W))))
    val B = Input(Vec(N, Vec(N, UInt(W.W))))
    val C = Output(Vec(N, Vec(N, UInt((2*W).W))))
  })

  // Compute C[i][j] = sum over k of A[i][k] * B[k][j]
  for (i <- 0 until N) {
    for (j <- 0 until N) {
      val products = for (k <- 0 until N) yield {
        io.A(i)(k) * io.B(k)(j)
      }
      io.C(i)(j) := products.reduce(_ + _)
    }
  }
}
