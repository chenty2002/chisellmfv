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

  // ========== Formal Verification Assertions ==========

  // 1. Prediction stability during stall:
  //    When stall is asserted, the prediction output must not change
  assertStableWhen(io.stall, io.prediction.asUInt, "prediction_stable_during_stall")

  // 2. Prediction decode correctness:
  //    When not stalled, each prediction bit must equal the corresponding
  //    state bank comparison (state > 1.U indicates taken prediction)
  fvAssert(
    io.stall || io.prediction(0) === (state_bank0(io.inst_addr) > 1.U),
    "pred0_decode_correct"
  )
  fvAssert(
    io.stall || io.prediction(1) === (state_bank1(io.inst_addr) > 1.U),
    "pred1_decode_correct"
  )
  fvAssert(
    io.stall || io.prediction(2) === (state_bank2(io.inst_addr) > 1.U),
    "pred2_decode_correct"
  )
  fvAssert(
    io.stall || io.prediction(3) === (state_bank3(io.inst_addr) > 1.U),
    "pred3_decode_correct"
  )

  // 3. Saturating counter overflow safety:
  //    When updating with taken (increment) and the counter is already at max (3),
  //    it must not wrap around. We capture the buffer_addr per bank so the
  //    next-cycle check targets the same entry regardless of intervening updates
  //    to other banks.
  val update_addr_bank0 = RegInit(0.U(2.W))
  val update_addr_bank1 = RegInit(0.U(2.W))
  val update_addr_bank2 = RegInit(0.U(2.W))
  val update_addr_bank3 = RegInit(0.U(2.W))

  when (io.update && io.buffer_offset === 0.U) {
    update_addr_bank0 := io.buffer_addr
  }
  when (io.update && io.buffer_offset === 1.U) {
    update_addr_bank1 := io.buffer_addr
  }
  when (io.update && io.buffer_offset === 2.U) {
    update_addr_bank2 := io.buffer_addr
  }
  when (io.update && io.buffer_offset === 3.U) {
    update_addr_bank3 := io.buffer_addr
  }

  // Bank 0: when taken-update fires at max, next-cycle entry must still be 3
  // Using fvAssert with RegNext instead of assertImpliesDelay (which drops antecedent/delay in FIRRTL)
  fvAssert(
    !(io.update && io.branch_result && io.buffer_offset === 0.U && state_bank0(io.buffer_addr) === 3.U) ||
      RegNext(state_bank0(update_addr_bank0) === 3.U),
    "bank0_no_overflow_on_taken"
  )
  // Bank 1: taken-update at max
  fvAssert(
    !(io.update && io.branch_result && io.buffer_offset === 1.U && state_bank1(io.buffer_addr) === 3.U) ||
      RegNext(state_bank1(update_addr_bank1) === 3.U),
    "bank1_no_overflow_on_taken"
  )
  // Bank 2: taken-update at max
  fvAssert(
    !(io.update && io.branch_result && io.buffer_offset === 2.U && state_bank2(io.buffer_addr) === 3.U) ||
      RegNext(state_bank2(update_addr_bank2) === 3.U),
    "bank2_no_overflow_on_taken"
  )
  // Bank 3: taken-update at max
  fvAssert(
    !(io.update && io.branch_result && io.buffer_offset === 3.U && state_bank3(io.buffer_addr) === 3.U) ||
      RegNext(state_bank3(update_addr_bank3) === 3.U),
    "bank3_no_overflow_on_taken"
  )

  // 4. Saturating counter underflow safety:
  //    When updating with not-taken (decrement) and the counter is already at min (0),
  //    it must not wrap around.
  // Bank 0: not-taken update at min
  fvAssert(
    !(io.update && !io.branch_result && io.buffer_offset === 0.U && state_bank0(io.buffer_addr) === 0.U) ||
      RegNext(state_bank0(update_addr_bank0) === 0.U),
    "bank0_no_underflow_on_not_taken"
  )
  // Bank 1: not-taken update at min
  fvAssert(
    !(io.update && !io.branch_result && io.buffer_offset === 1.U && state_bank1(io.buffer_addr) === 0.U) ||
      RegNext(state_bank1(update_addr_bank1) === 0.U),
    "bank1_no_underflow_on_not_taken"
  )
  // Bank 2: not-taken update at min
  fvAssert(
    !(io.update && !io.branch_result && io.buffer_offset === 2.U && state_bank2(io.buffer_addr) === 0.U) ||
      RegNext(state_bank2(update_addr_bank2) === 0.U),
    "bank2_no_underflow_on_not_taken"
  )
  // Bank 3: not-taken update at min
  fvAssert(
    !(io.update && !io.branch_result && io.buffer_offset === 3.U && state_bank3(io.buffer_addr) === 0.U) ||
      RegNext(state_bank3(update_addr_bank3) === 0.U),
    "bank3_no_underflow_on_not_taken"
  )

  // 5. Bounded liveness: When not stalled, prediction updates are active
  fvAssert(
    io.stall || RegNext(io.prediction) =/= io.prediction || io.stall,
    "prediction_updates_when_not_stalled"
  )
}

object VerilogGenerator extends App {
  emitVerilog(new branchPredictionBuffer(), args)
}
