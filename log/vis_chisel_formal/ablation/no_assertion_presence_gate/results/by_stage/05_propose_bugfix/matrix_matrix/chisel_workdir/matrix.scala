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

  // ──────────────────────────────────────────────
  // Formal Verification Assertions
  // ──────────────────────────────────────────────

  // 1. SAFETY: Memory bounds check.
  //    When accessing a non-diagonal element (row =/= col), the computed position
  //    must be strictly less than L to avoid out-of-bounds access on M.
  fvAssert(io.row === io.col || posn < L.U, "posn_in_bounds")

  // 2. SAFETY: Diagonal read property.
  //    Elements on the diagonal (row === col) are defined to be 1, so bitOut
  //    must be asserted whenever row equals col.
  assertImplies(io.row === io.col, io.bitOut, "diagonal_read_one")

  // 3. LIVENESS: Initialization state machine progress.
  //    The offset-initialization FSM (initOffsets) must become true within
  //    N+3 cycles after reset. The FSM takes at most N+1 cycles (states 0..N),
  //    with a small margin for the register update pipeline.
  astRelaxedLiveness(!initOffsets, initOffsets, N + 3, "init_eventually_completes")

  // 4. SAFETY: Initialization counter stays within valid range.
  //    During initialization, the counter (0 to N) must not exceed N.
  fvAssert(initOffsets || initCounter <= N.U, "init_counter_never_exceeds_N")

  // 5. SAFETY: Write-read consistency using shadow tracking.
  //    After a write to a non-diagonal position, reading back the same
  //    position (with no intervening writes) must return the written data.
  //    Shadow register tracks the last-written data per position.
  val shadowValid = RegInit(VecInit(Seq.fill(L)(false.B)))
  val shadowData  = RegInit(VecInit(Seq.fill(L)(false.B)))

  when(!io.r_w && io.row =/= io.col) {
    shadowValid(posn) := true.B
    shadowData(posn)  := io.bitIn
  }

  // When reading from a position that has been written at least once,
  // the memory value must match the shadow (i.e. the last written value),
  // assuming no other writes to that position have been lost.
  // We check: if shadowValid(posn) is true, then M(posn) === shadowData(posn)
  // during read operations (r_w = 1 or write-read coherence).
  // Condition: reading a position that was previously written to.
  // Since writes happen on the same cycle as the posn computation,
  // the M register is updated at the end of the cycle. On a subsequent
  // read of the same position, M(posn) must reflect the last written data.
  val readingPreviouslyWrittenPosn = io.r_w && io.row =/= io.col && shadowValid(posn)
  fvAssert(!readingPreviouslyWrittenPosn || M(posn) === shadowData(posn),
    "write_read_consistency")

  // 6. SAFETY: Write sets shadowValid.
  //    After any write to a non-diagonal element, the corresponding shadow
  //    valid flag must be set, ensuring the write-read consistency check
  //    becomes active.
  //    Note: shadowValid is a register updated with non-blocking assignment,
  //    so the new value is only visible starting the next cycle. We snapshot
  //    the write condition and position on the cycle of the write and check
  //    one cycle later that the shadow flag was properly updated.
  val wasWrittenNonDiag = RegNext(!io.r_w && io.row =/= io.col, false.B)
  val prevPosn = RegNext(posn)
  fvAssert(!wasWrittenNonDiag || shadowValid(prevPosn),
    "write_sets_shadow_valid")
}

object VerilogGenerator extends App {
  emitVerilog(new Matrix(), args)
}
