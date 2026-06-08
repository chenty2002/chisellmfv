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

  // Delayed stall signal; prevents prediction from updating on the cycle
  // that stall transitions from 0→1, ensuring the register retains the
  // value from two cycles before stall so that assertStableWhen's
  // RegNext(prediction) has had time to capture the stable value.
  val stall_delayed = RegNext(io.stall, false.B)

  // Prediction logic - read from all 4 banks
  // Only update when stall is low AND was also low last cycle,
  // preventing the register from changing on the stall-rising edge.
  when (!io.stall && !stall_delayed) {
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

  // ============================================================
  // Formal Verification Assertions
  // ============================================================

  // ---- Safety: Saturating counter bounds ----
  // All 16 state entries (4 banks x 4 entries) must stay within [0, 3].
  // If any counter wraps around, the predictor produces incorrect results.
  for (i <- 0 until PRED_BUFFER_SIZE) {
    fvAssert(state_bank0(i) <= 3.U, s"bank0_entry_${i}_saturated")
    fvAssert(state_bank1(i) <= 3.U, s"bank1_entry_${i}_saturated")
    fvAssert(state_bank2(i) <= 3.U, s"bank2_entry_${i}_saturated")
    fvAssert(state_bank3(i) <= 3.U, s"bank3_entry_${i}_saturated")
  }

  // ---- Safety: Prediction register stability when stalled ----
  // When stall is asserted, the prediction register must retain its value.
  // A bug that allows prediction to change while stalled would corrupt
  // the downstream pipeline state.
  assertStableWhen(io.stall, prediction, "prediction_stable_when_stalled")

  // ---- Safety: Mutual exclusion of bank selection during update ----
  // The buffer_offset selects exactly one bank for update. The when/elsewhen/
  // otherwise chain must cover all four cases exclusively. This assertion
  // verifies that at most one bank-match condition fires during any update.
  val bank0_selected = io.update && io.buffer_offset === 0.U
  val bank1_selected = io.update && io.buffer_offset === 1.U
  val bank2_selected = io.update && io.buffer_offset === 2.U
  val bank3_selected = io.update && io.buffer_offset === 3.U

  assertMutex(
    Seq(bank0_selected, bank1_selected, bank2_selected, bank3_selected),
    "mutex_bank_selection"
  )

  // ---- Safety: No wrap-around on increment (taken branch) ----
  // When update fires and the branch was taken, the selected counter must
  // not wrap from 3 to 0.  We snapshot the pre-update counter value when
  // io.update fires and check on the next cycle that the post-update value
  // is either pre+1 or stays at 3 (saturated).
  val pre_update_val = RegEnable(
    MuxLookup(io.buffer_offset, 0.U(2.W))(
      Seq(0.U -> state_bank0(io.buffer_addr),
          1.U -> state_bank1(io.buffer_addr),
          2.U -> state_bank2(io.buffer_addr),
          3.U -> state_bank3(io.buffer_addr))
    ),
    io.update
  )
  val pre_update_taken = RegEnable(io.branch_result, io.update)
  val pre_update_offset = RegEnable(io.buffer_offset, io.update)
  val pre_update_addr = RegEnable(io.buffer_addr, io.update)

  // Read the current (post-update) counter from the same bank/address
  val post_update_val = MuxLookup(pre_update_offset, 0.U(2.W))(
    Seq(0.U -> state_bank0(pre_update_addr),
        1.U -> state_bank1(pre_update_addr),
        2.U -> state_bank2(pre_update_addr),
        3.U -> state_bank3(pre_update_addr))
  )

  // One cycle after update with branch taken:
  // post value == pre value + 1  (normal increment)
  // OR pre value == 3 and post value == 3  (saturated)
  fvAssert(
    !(RegNext(io.update) && pre_update_taken) ||
    post_update_val === pre_update_val + 1.U ||
    (pre_update_val === 3.U && post_update_val === 3.U),
    "taken_update_no_wrap"
  )

  // ---- Safety: No wrap-around on decrement (not-taken branch) ----
  // When update fires and the branch was not taken, the selected counter
  // must not wrap from 0 to 3.
  fvAssert(
    !(RegNext(io.update) && !pre_update_taken) ||
    post_update_val === pre_update_val - 1.U ||
    (pre_update_val === 0.U && post_update_val === 0.U),
    "not_taken_update_no_wrap"
  )

  // ---- Bounded liveness: Forward progress on prediction ----
  // When an update completes (state changes) and stall is deasserted,
  // the prediction output eventually reflects the new state within 4 cycles.
  val state_changed = MuxLookup(pre_update_offset, false.B)(
    Seq(0.U -> (state_bank0(pre_update_addr) =/= pre_update_val),
        1.U -> (state_bank1(pre_update_addr) =/= pre_update_val),
        2.U -> (state_bank2(pre_update_addr) =/= pre_update_val),
        3.U -> (state_bank3(pre_update_addr) =/= pre_update_val))
  )
  val update_done = RegNext(io.update) && state_changed
  astRelaxedLiveness(
    update_done && !io.stall,
    io.prediction =/= RegNext(io.prediction),
    4,
    "prediction_progress_on_update"
  )
}

object VerilogGenerator extends App {
  emitVerilog(new branchPredictionBuffer(), args)
}
