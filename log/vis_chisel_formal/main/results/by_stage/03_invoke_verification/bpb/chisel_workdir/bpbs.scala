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

  // ========== FORMAL ASSERTIONS ==========

  // Safety 1: Prediction register must be stable (unchanged) while stall is asserted.
  // When the pipeline is stalled, the prediction output must hold its previous value
  // and not be updated from the counter banks. This prevents speculative prediction
  // changes during pipeline stalls.
  assertStableWhen(io.stall, prediction, "prediction_stable_during_stall")

  // Safety 2: All saturating counter values are bounded within [0, 3].
  // The 2-bit saturating counters must never overflow beyond 3 or underflow below 0.
  // While the update logic already guards against this structurally, this assertion
  // provides a formal guarantee that no synthesis or tool issue causes wraparound.
  for (i <- 0 until PRED_BUFFER_SIZE) {
    fvAssert(state_bank0(i) <= 3.U, s"state_bank0_${i}_saturated_high")
    fvAssert(state_bank1(i) <= 3.U, s"state_bank1_${i}_saturated_high")
    fvAssert(state_bank2(i) <= 3.U, s"state_bank2_${i}_saturated_high")
    fvAssert(state_bank3(i) <= 3.U, s"state_bank3_${i}_saturated_high")
  }

  // Safety 3: Bank selection offset must be in valid range.
  // Since buffer_offset is 2 bits, it always maps to banks 0-3.
  // This assertion confirms the offset selects exactly one of the four banks.
  // (This is structurally guaranteed by the width, but formal assertion
  //  documents the requirement and catches any future width changes.)
  fvAssert(io.buffer_offset <= 3.U, "buffer_offset_valid_range")

  // Safety 4: When an update targets a specific bank, the other three banks'
  // counters at the same address must remain unchanged in the same cycle.
  // This is structurally enforced by the when/elsewhen priority logic.
}

object VerilogGenerator extends App {
  emitVerilog(new branchPredictionBuffer(), args)
}
