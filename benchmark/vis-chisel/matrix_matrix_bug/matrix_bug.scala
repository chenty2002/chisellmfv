package llmverify

import chisel3._
import chisel3.util._

class matrix(MSB: Int = 2, N: Int = 8, L: Int = 28) extends Module {
  val io = IO(new Bundle {
    val row = Input(UInt((MSB + 1).W))
    val col = Input(UInt((MSB + 1).W))
    val r_w = Input(Bool()) // 1: read, 0: write
    val bitIn = Input(Bool())
    val bitOut = Output(Bool())
  })
  
  // Precomputed offsets for each row in the linear array
  // offset[j] stores the starting position for row j in the linear array
  val offset = Wire(Vec(N, UInt((MSB * 2 + 1).W)))
  
  // Position register for addressing the matrix
  val posn = Reg(UInt((MSB * 2 + 1).W))
  
  // Matrix storage - lower triangle only (diagonal excluded)
  val M = Mem(L, Bool())
  
  // Initialize offsets - precompute the starting positions for each row
  // For a symmetric matrix, we only store the lower triangle
  // offset[1] = 0, offset[2] = 1, offset[3] = 1+2 = 3, offset[4] = 1+2+3 = 6, etc.
  offset(0) := 0.U // Not used but defined for completeness
  var currentPos = 0.U
  for (j <- 1 until N) {
    offset(j) := currentPos
    if (j != N - 1) {
      currentPos = currentPos + j.U
    }
  }
  
  // Initialize matrix to all zeros on reset
  // Note: In Chisel, we don't have initial blocks like Verilog
  // The matrix will be initialized to 0 by default in most FPGA implementations
  
  // Position calculation logic
  val rowNotEqualCol = io.row =/= io.col
  val rowLessCol = io.row < io.col
  
  // Calculate the position in the linear array for the given row and column
  val calculatedPosn = Mux(rowNotEqualCol,
    Mux(rowLessCol,
      offset(io.col) + io.row,
      offset(io.row) + io.col
    ),
    0.U // Don't care for diagonal
  )
  
  // Sequential logic for writes and position update
  when(rowNotEqualCol) {
    posn := calculatedPosn
    when(!io.r_w) {
      M.write(calculatedPosn, io.bitIn)
    }
  }.otherwise {
    posn := 0.U
  }
  
  // Combinational output
  // Diagonal elements always return 1, off-diagonal elements read from matrix
  io.bitOut := Mux(io.row === io.col, true.B, M.read(posn))
}

object VerilogGenerator extends App {
  emitVerilog(new matrix(), args)
}