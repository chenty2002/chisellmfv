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

  // ========== Formal Verification Assertions ==========

  // Safety: computed position must always be within the memory array bounds
  // when accessing a non-diagonal element. Out-of-bounds posn would index past M.
  fvAssert(
    !(io.row =/= io.col) || (posn < L.U),
    "posn_within_memory_bounds"
  )

  // Architectural invariant: diagonal elements always read as 1 (representing
  // the implicit 1 on the diagonal of the triangular matrix representation).
  fvAssert(
    !(io.row === io.col) || (io.bitOut === true.B),
    "diagonal_read_returns_one"
  )

  // Safety: init counter must never exceed the array size N during initialization.
  // A counter overflow would mean the init FSM escaped its expected state space.
  fvAssert(
    initCounter <= N.U,
    "init_counter_bound"
  )

  // Bounded liveness: the offset initialization FSM must complete within N+2
  // cycles after reset. The FSM sequences through N states (0 through N-1) then
  // sets initOffsets, so it cannot stall indefinitely.
  astRelaxedLiveness(!initOffsets, initOffsets, N + 2, "init_completes_in_bounded_time")
}

object VerilogGenerator extends App {
  emitVerilog(new Matrix(), args)
}
