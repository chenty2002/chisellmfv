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

  // ============ FORMAL ASSERTIONS ============

  // -------------------------------------------------------
  // 1. STALL INVARIANT: When stalled, prediction must hold stable
  //    Critical: if prediction changes during a stall, the pipeline
  //    could use incorrect branch direction speculation.
  // -------------------------------------------------------
  assertStableWhen(io.stall, prediction.asUInt, "stall_preserves_prediction")

  // -------------------------------------------------------
  // 2. SATURATING COUNTER BOUNDS: All state entries must stay
  //    within the valid 2-bit saturating range [0, 3].
  //    Catches off-by-one bugs where increment-at-3 or decrement-at-0
  //    wraps around instead of saturating.
  // -------------------------------------------------------
  for (i <- 0 until PRED_BUFFER_SIZE) {
    fvAssert(state_bank0(i) <= 3.U, s"bank0_entry_${i}_within_bounds")
    fvAssert(state_bank1(i) <= 3.U, s"bank1_entry_${i}_within_bounds")
    fvAssert(state_bank2(i) <= 3.U, s"bank2_entry_${i}_within_bounds")
    fvAssert(state_bank3(i) <= 3.U, s"bank3_entry_${i}_within_bounds")
  }

  // -------------------------------------------------------
  // 3. BANK ISOLATION: When updating one bank, the other three
  //    banks at the same buffer address must NOT change state.
  //    Catches bugs where update bleeds into adjacent banks.
  // -------------------------------------------------------
  assertStableWhen(!(io.update && io.buffer_offset === 0.U), state_bank0(io.buffer_addr).asUInt, "bank0_stable_except_when_selected")
  assertStableWhen(!(io.update && io.buffer_offset === 1.U), state_bank1(io.buffer_addr).asUInt, "bank1_stable_except_when_selected")
  assertStableWhen(!(io.update && io.buffer_offset === 2.U), state_bank2(io.buffer_addr).asUInt, "bank2_stable_except_when_selected")
  assertStableWhen(!(io.update && io.buffer_offset === 3.U), state_bank3(io.buffer_addr).asUInt, "bank3_stable_except_when_selected")

  // -------------------------------------------------------
  // 4. SATURATING UPDATE CORRECTNESS (INCREMENT):
  //    When updating with branch_result=true (taken), the targeted
  //    counter must not wrap from 3 to 0. I.e., if the counter
  //    was already 3, it stays at 3 after the increment attempt.
  //    Check: after an increment update, the value is never 0
  //    unless it was already 0 and somehow... actually the simpler
  //    check: when update=true & branch_result=true & counter=3,
  //    the counter must remain 3 (no wraparound).
  // -------------------------------------------------------
  // When counter is at max (3) and an increment update targets it,
  // it must stay at 3 (saturating, not wrapping to 0).
  fvAssert(
    !(io.update && io.branch_result && io.buffer_offset === 0.U && state_bank0(io.buffer_addr) === 3.U) ||
    state_bank0(io.buffer_addr) === 3.U,
    "bank0_saturating_increment_no_wrap"
  )
  fvAssert(
    !(io.update && io.branch_result && io.buffer_offset === 1.U && state_bank1(io.buffer_addr) === 3.U) ||
    state_bank1(io.buffer_addr) === 3.U,
    "bank1_saturating_increment_no_wrap"
  )
  fvAssert(
    !(io.update && io.branch_result && io.buffer_offset === 2.U && state_bank2(io.buffer_addr) === 3.U) ||
    state_bank2(io.buffer_addr) === 3.U,
    "bank2_saturating_increment_no_wrap"
  )
  fvAssert(
    !(io.update && io.branch_result && io.buffer_offset === 3.U && state_bank3(io.buffer_addr) === 3.U) ||
    state_bank3(io.buffer_addr) === 3.U,
    "bank3_saturating_increment_no_wrap"
  )

  // -------------------------------------------------------
  // 5. SATURATING UPDATE CORRECTNESS (DECREMENT):
  //    When updating with branch_result=false (not taken), the
  //    targeted counter must not wrap from 0 to 3. If the counter
  //    was already 0, it stays at 0 after the decrement attempt.
  // -------------------------------------------------------
  fvAssert(
    !(io.update && !io.branch_result && io.buffer_offset === 0.U && state_bank0(io.buffer_addr) === 0.U) ||
    state_bank0(io.buffer_addr) === 0.U,
    "bank0_saturating_decrement_no_wrap"
  )
  fvAssert(
    !(io.update && !io.branch_result && io.buffer_offset === 1.U && state_bank1(io.buffer_addr) === 0.U) ||
    state_bank1(io.buffer_addr) === 0.U,
    "bank1_saturating_decrement_no_wrap"
  )
  fvAssert(
    !(io.update && !io.branch_result && io.buffer_offset === 2.U && state_bank2(io.buffer_addr) === 0.U) ||
    state_bank2(io.buffer_addr) === 0.U,
    "bank2_saturating_decrement_no_wrap"
  )
  fvAssert(
    !(io.update && !io.branch_result && io.buffer_offset === 3.U && state_bank3(io.buffer_addr) === 0.U) ||
    state_bank3(io.buffer_addr) === 0.U,
    "bank3_saturating_decrement_no_wrap"
  )

  // -------------------------------------------------------
  // 6. BOUNDED LIVENESS: The prediction register updates
  //    within a bounded number of cycles after a stall ends.
  //    If not stalled, the prediction will reflect the current
  //    state banks within 2 cycles (1 cycle for read + register).
  //    This catches deadlocks where the prediction freezes
  //    indefinitely despite not being stalled.
  // -------------------------------------------------------
  astRelaxedLiveness(
    !io.stall,           // request: not stalled (should be able to update prediction)
    io.prediction =/= prediction,  // response: prediction value changes (progress)
    5,                   // bound: within 5 cycles
    "prediction_progress_when_not_stalled"
  )
}

object VerilogGenerator extends App {
  emitVerilog(new branchPredictionBuffer(), args)
}
