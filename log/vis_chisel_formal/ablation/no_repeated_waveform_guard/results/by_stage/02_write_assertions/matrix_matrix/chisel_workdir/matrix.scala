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
  when(io.row =/= io.col) {
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

  // ===== Formal Verification Assertions =====

  // Safety 1: Position must be within memory bounds for all non-diagonal accesses
  fvAssert(io.row === io.col || posn < L.U, "posn_in_bounds")

  // Safety 2: Diagonal elements always read as 1 (architectural invariant)
  fvAssert(io.row =/= io.col || io.bitOut, "diagonal_reads_one")

  // Liveness 3: Init sequence must complete within a bounded number of cycles
  assertLivenessTimer(!initOffsets, initOffsets, 50, "init_completes")
}

object VerilogGenerator extends App {
  emitVerilog(new Matrix(), args)
}