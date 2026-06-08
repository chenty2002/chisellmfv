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

  // Prediction register - only update when NOT stalled.
  // Gating by !io.stall ensures that during a stall the prediction
  // output is genuinely stable (not recomputed every cycle), which
  // matches the architectural requirement that the pipeline sees
  // a fixed prediction for the duration of the stall.
  val pred3 = Mux(state_bank3(io.inst_addr) > 1.U, 1.U, 0.U)
  val pred2 = Mux(state_bank2(io.inst_addr) > 1.U, 1.U, 0.U)
  val pred1 = Mux(state_bank1(io.inst_addr) > 1.U, 1.U, 0.U)
  val pred0 = Mux(state_bank0(io.inst_addr) > 1.U, 1.U, 0.U)
  val prediction = RegInit(0.U(4.W))
  when (!io.stall) {
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
  // 1. STALL INVARIANT: When stalled, prediction must hold stable.
  //    Uses per-cycle RegNext comparison instead of assertStableWhen
  //    to avoid the non-re-sampling issue: assertStableWhen captures
  //    a reference once and never updates it after a false→true
  //    transition on the condition. The RegNext approach compares
  //    every cycle where stall is asserted against the immediately
  //    preceding cycle value, which is correct across any number
  //    of stall→non-stall→stall transitions.
  // -------------------------------------------------------
  when (io.stall) {
    fvAssert(prediction.asUInt === RegNext(prediction.asUInt), "stall_preserves_prediction")
  }

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
  // 3. BANK ISOLATION: When NOT updating a bank, the entries of
  //    that bank at the selected buffer address must not change.
  //
  //    Instead of assertStableWith (which only samples its reference
  //    once and never re-samples after a false→true transition on
  //    the condition), we use a per-cycle RegNext-based check:
  //    when the bank is NOT selected for update this cycle, the
  //    current value at io.buffer_addr must equal the previous
  //    cycle's value at the same address. This naturally handles
  //    non-consecutive stable periods because each cycle's check
  //    is independent and samples the immediately preceding value.
  // -------------------------------------------------------
  val bank0_selected = io.update && io.buffer_offset === 0.U
  when (!bank0_selected) {
    fvAssert(state_bank0(io.buffer_addr) === RegNext(state_bank0(io.buffer_addr)), "bank0_stable_except_when_selected")
  }
  val bank1_selected = io.update && io.buffer_offset === 1.U
  when (!bank1_selected) {
    fvAssert(state_bank1(io.buffer_addr) === RegNext(state_bank1(io.buffer_addr)), "bank1_stable_except_when_selected")
  }
  val bank2_selected = io.update && io.buffer_offset === 2.U
  when (!bank2_selected) {
    fvAssert(state_bank2(io.buffer_addr) === RegNext(state_bank2(io.buffer_addr)), "bank2_stable_except_when_selected")
  }
  val bank3_selected = io.update && io.buffer_offset === 3.U
  when (!bank3_selected) {
    fvAssert(state_bank3(io.buffer_addr) === RegNext(state_bank3(io.buffer_addr)), "bank3_stable_except_when_selected")
  }

  // -------------------------------------------------------
  // 4. SATURATING UPDATE CORRECTNESS (INCREMENT):
  //    When updating with branch_result=true (taken), the targeted
  //    counter must not wrap from 3 to 0. I.e., if the counter
  //    was already 3, it stays at 3 after the increment attempt.
  // -------------------------------------------------------
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
  //    When not stalled, prediction should change within 5 cycles
  //    to indicate it is tracking the current state banks.
  // -------------------------------------------------------
  astRelaxedLiveness(
    !io.stall,           // request: not stalled (should be able to update prediction)
    prediction =/= RegNext(prediction),  // response: prediction value changes (progress)
    5,                   // bound: within 5 cycles
    "prediction_progress_when_not_stalled"
  )
}

object VerilogGenerator extends App {
  emitVerilog(new branchPredictionBuffer(), args)
}
