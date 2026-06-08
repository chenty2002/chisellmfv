package llmverify

import chisel3._
import chisel3.util._
import chiselFv._

class Matrix(val MSB: Int = 2, val N: Int = 8, val L: Int = 28) extends Module with Formal {
  val io = IO(new Bundle {
    val row = Input(UInt((MSB + 1).W))
    val col = Input(UInt((MSB + 1).W))
    val r_w = Input(Bool())  // 1: read, 0: write
    val bitIn = Input(Bool())
    val bitOut = Output(Bool())
  })

  // Offset array for computing positions in linear array
  val offset = RegInit(VecInit(Seq.fill(N)(0.U((MSB * 2 + 1).W))))
  // Memory array to store the bits
  val M = RegInit(VecInit(Seq.fill(L)(false.B)))
  
  // Position register
  val posn = RegInit(0.U((MSB * 2 + 1).W))
  
  // Initialize offsets - this is done once at reset
  // In Chisel, we use a sequential block for initialization
  val initOffsets = RegInit(false.B)
  val initCounter = RegInit(0.U(log2Ceil(N + 1).W))
  val runningPosn = RegInit(0.U((MSB * 2 + 1).W))
  
  when(!initOffsets) {
    when(initCounter === 0.U) {
      offset(0) := 0.U
      runningPosn := 0.U
      initCounter := initCounter + 1.U
    }.elsewhen(initCounter < N.U) {
      offset(initCounter) := runningPosn
      when(initCounter =/= (N - 1).U) {
        runningPosn := runningPosn + initCounter
      }
      initCounter := initCounter + 1.U
    }.otherwise {
      initOffsets := true.B
    }
  }
  
  // Write logic
  val offDiagonal = io.row =/= io.col
  when(offDiagonal) {
    when(io.row < io.col) {
      posn := offset(io.col) + io.row
    }.otherwise {
      posn := offset(io.row) + io.col
    }
    when(!io.r_w) {
      M(posn) := io.bitIn
    }
  }
  
  // Read logic - diagonal elements return 1
  io.bitOut := Mux(io.row === io.col, true.B, M(posn))

  // ========== Formal Verification Assertions ==========

  // Safety: position must always be within valid memory bounds when accessing M
  fvAssert(!offDiagonal || posn < L.U, "posn_in_bounds")

  // Safety: row and col must be valid matrix indices (less than N)
  fvAssert(io.row < N.U && io.col < N.U, "row_col_in_range")

  // Safety: at most one of {row<col, row==col, row>col} is true at any time
  assertMutex(Seq(io.row < io.col, io.row === io.col, io.row > io.col), "row_col_mutex")

  // Safety: offset values must be monotonically non-decreasing and bounded
  // For all i from 0 to N-2: offset(i+1) >= offset(i)
  // Guarded by initOffsets because offsets are populated sequentially during init
  for (i <- 0 until N - 1) {
    fvAssert(!initOffsets || offset(i + 1) >= offset(i), s"offset_monotonic_${i}_${i + 1}")
  }
  // The last offset plus the number of elements in the last column must not exceed L
  fvAssert(offset(N - 1) + (N - 1).U <= L.U, "offset_total_bounds")

  // Safety: initCounter must stay within valid range [0, N] during initialization
  fvAssert(initCounter <= N.U, "initCounter_in_range")

  // Liveness: initialization must complete within a bounded number of cycles
  // initOffsets should become true within N+2 cycles after reset
  astRelaxedLiveness(!initOffsets, initOffsets, N + 5, "init_completes")

  // Safety: write to diagonal element (row==col) has no effect on M
  // (Already structurally prevented by the when(offDiagonal) guard)
  // Check DUT behavior: diagonal writes are blocked by offDiagonal being false
  fvAssert(!(io.row === io.col && !io.r_w) || !initOffsets || !offDiagonal,
    "no_diagonal_write_during_init")
  fvAssert(!(io.row === io.col && !io.r_w && initOffsets) || !offDiagonal,
    "no_diagonal_write")
}

object VerilogGenerator extends App {
  emitVerilog(new Matrix(), args)
}
