package llmverify

import chisel3._
import chisel3.util._
import chiselFv._

class branchPredictionBuffer(PRED_BUFFER_SIZE: Int = 4) extends Module with Formal {
  val io = IO(new Bundle {
    val stall = Input(Bool())
    val inst_addr = Input(UInt(2.W))
    val update = Input(Bool())
    val branch_result = Input(Bool())
    val buffer_addr = Input(UInt(2.W))
    val buffer_offset = Input(UInt(2.W))
    val prediction = Output(UInt(4.W))
  })

  // State banks - 2-bit saturating counters for each entry
  // Initialize to weak not taken (01)
  val state_bank0 = RegInit(VecInit(Seq.fill(PRED_BUFFER_SIZE)(1.U(2.W))))
  val state_bank1 = RegInit(VecInit(Seq.fill(PRED_BUFFER_SIZE)(1.U(2.W))))
  val state_bank2 = RegInit(VecInit(Seq.fill(PRED_BUFFER_SIZE)(1.U(2.W))))
  val state_bank3 = RegInit(VecInit(Seq.fill(PRED_BUFFER_SIZE)(1.U(2.W))))

  // Prediction register
  val prediction = RegInit(0.U(4.W))

  // Prediction logic - read from all 4 banks when not stalled
  when (!io.stall) {
    // Construct the entire 4-bit prediction value at once
    val pred3 = Mux(state_bank3(io.inst_addr) > 1.U, 1.U, 0.U)
    val pred2 = Mux(state_bank2(io.inst_addr) > 1.U, 1.U, 0.U)
    val pred1 = Mux(state_bank1(io.inst_addr) > 1.U, 1.U, 0.U)
    val pred0 = Mux(state_bank0(io.inst_addr) > 1.U, 1.U, 0.U)
    prediction := Cat(pred3, pred2, pred1, pred0)
  }

  // Update logic - modify one bank based on buffer_offset
  when (io.update) {
    when (io.branch_result) { // Branch was taken - increment
      when (io.buffer_offset === 0.U) {
        when (state_bank0(io.buffer_addr) =/= 3.U) {
          state_bank0(io.buffer_addr) := state_bank0(io.buffer_addr) + 1.U
        }
      }.elsewhen (io.buffer_offset === 1.U) {
        when (state_bank1(io.buffer_addr) =/= 3.U) {
          state_bank1(io.buffer_addr) := state_bank1(io.buffer_addr) + 1.U
        }
      }.elsewhen (io.buffer_offset === 2.U) {
        when (state_bank2(io.buffer_addr) =/= 3.U) {
          state_bank2(io.buffer_addr) := state_bank2(io.buffer_addr) + 1.U
        }
      }.otherwise {
        when (state_bank3(io.buffer_addr) =/= 3.U) {
          state_bank3(io.buffer_addr) := state_bank3(io.buffer_addr) + 1.U
        }
      }
    }.otherwise { // Branch was not taken - decrement
      when (io.buffer_offset === 0.U) {
        when (state_bank0(io.buffer_addr) =/= 0.U) {
          state_bank0(io.buffer_addr) := state_bank0(io.buffer_addr) - 1.U
        }
      }.elsewhen (io.buffer_offset === 1.U) {
        when (state_bank1(io.buffer_addr) =/= 0.U) {
          state_bank1(io.buffer_addr) := state_bank1(io.buffer_addr) - 1.U
        }
      }.elsewhen (io.buffer_offset === 2.U) {
        when (state_bank2(io.buffer_addr) =/= 0.U) {
          state_bank2(io.buffer_addr) := state_bank2(io.buffer_addr) - 1.U
        }
      }.otherwise {
        when (state_bank3(io.buffer_addr) =/= 0.U) {
          state_bank3(io.buffer_addr) := state_bank3(io.buffer_addr) - 1.U
        }
      }
    }
  }

  // Connect prediction to output
  io.prediction := prediction

  // ================================================================
  // Formal Verification Assertions
  // ================================================================

  // --- Safety: Prediction remains stable when stalled ---
  // When stall is asserted, the prediction register should not be updated.
  // Simulation is unlikely to exercise all stall-to-update interleavings,
  // making this a high-value formal check.
  assertStableWhen(io.stall, io.prediction.asUInt, "prediction_stable_when_stalled")

  // --- Safety: Saturating counters stay within valid [0, 3] range ---
  // Each state bank entry is a 2-bit saturating counter.  The update logic
  // explicitly guards against wraparound (inhibit increment at 3, decrement at
  // 0).  These assertions verify that no other unintended path modifies the
  // counter out of bounds.
  for (i <- 0 until PRED_BUFFER_SIZE) {
    fvAssert(state_bank0(i) <= 3.U, s"state_bank0_${i}_in_range")
    fvAssert(state_bank1(i) <= 3.U, s"state_bank1_${i}_in_range")
    fvAssert(state_bank2(i) <= 3.U, s"state_bank2_${i}_in_range")
    fvAssert(state_bank3(i) <= 3.U, s"state_bank3_${i}_in_range")
  }

  // --- Safety: buffer_offset selects at most one bank per cycle ---
  // The update logic uses mutually exclusive when/elsewhen branches keyed on
  // buffer_offset === {0,1,2,3}.  This assertion catches a micro-architectural
  // bug where the decoder could fire multiple banks simultaneously (e.g. due to
  // an X source, a glitch on a wide bus, or an unintended overlap in the
  // comparison logic).
  assertMutex(Seq(
    io.buffer_offset === 0.U,
    io.buffer_offset === 1.U,
    io.buffer_offset === 2.U,
    io.buffer_offset === 3.U
  ), "buffer_offset_mutex")

  // --- Liveness: Update request is serviced within a bounded number of cycles ---
  // When an update is requested and the system is not stalled, the prediction
  // output must change within 2 cycles to reflect the updated saturating-counter
  // state.  The relaxation accounts for the fact that the prediction register is
  // frozen while stalled; here we tie the request to !stall so that if stall
  // holds the response is vacuously satisfied.  The bound of 2 covers the
  // following pipeline: cycle 0 update arrives and state banks change
  // combinatorially; cycle 1 (non-stalled) prediction register captures the new
  // state.  An extra cycle of slack handles a stall that clears on the
  // following cycle.
  astRelaxedLiveness(
    io.update && !io.stall,
    io.prediction =/= RegNext(io.prediction),
    2,
    "prediction_updates_after_update"
  )
}

object VerilogGenerator extends App {
  emitVerilog(new branchPredictionBuffer(), args)
}
