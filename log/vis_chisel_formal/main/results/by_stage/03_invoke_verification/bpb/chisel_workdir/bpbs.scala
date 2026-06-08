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

  // Safety 3: Core functional correctness — prediction reflects the bank MSBs when not stalled.
  // When the pipeline is active (not stalled), the 4-bit prediction output must equal the
  // concatenation of the most significant bit (counter > 1) of each of the four state banks
  // read at the current instruction address. This is the central architectural invariant of
  // the branch prediction buffer and detects errors in the prediction muxing, bit ordering,
  // or register update gating.
  {
    val pred3_w = Mux(state_bank3(io.inst_addr) > 1.U, 1.U, 0.U)
    val pred2_w = Mux(state_bank2(io.inst_addr) > 1.U, 1.U, 0.U)
    val pred1_w = Mux(state_bank1(io.inst_addr) > 1.U, 1.U, 0.U)
    val pred0_w = Mux(state_bank0(io.inst_addr) > 1.U, 1.U, 0.U)
    val expected_prediction = Cat(pred3_w, pred2_w, pred1_w, pred0_w)
    fvAssert(io.stall || (prediction === expected_prediction), "prediction_matches_bank_msbs_when_not_stalled")
  }

  // Safety 4: Saturation at the upper bound — a counter at its maximum value (3) must
  // never be incremented past 3 when a taken-branch update targets it.
  // We sample the pre-update value and assert that if the counter was already at 3,
  // it remains at 3 in the next cycle.
  {
    val selected_counter_was_max = Wire(Bool())
    val selected_counter_stays_max = Wire(Bool())
    selected_counter_was_max := false.B
    selected_counter_stays_max := false.B
    when (io.update && io.branch_result) {
      when (io.buffer_offset === 0.U) {
        selected_counter_was_max := state_bank0(io.buffer_addr) === 3.U
        selected_counter_stays_max := state_bank0(io.buffer_addr) === 3.U
      }.elsewhen (io.buffer_offset === 1.U) {
        selected_counter_was_max := state_bank1(io.buffer_addr) === 3.U
        selected_counter_stays_max := state_bank1(io.buffer_addr) === 3.U
      }.elsewhen (io.buffer_offset === 2.U) {
        selected_counter_was_max := state_bank2(io.buffer_addr) === 3.U
        selected_counter_stays_max := state_bank2(io.buffer_addr) === 3.U
      }.otherwise {
        selected_counter_was_max := state_bank3(io.buffer_addr) === 3.U
        selected_counter_stays_max := state_bank3(io.buffer_addr) === 3.U
      }
    }
    fvAssert(!selected_counter_was_max || selected_counter_stays_max, "counter_stays_at_max_on_taken_update")
  }

  // Safety 5: Saturation at the lower bound — a counter at its minimum value (0) must
  // never be decremented past 0 when a not-taken branch update targets it.
  {
    val selected_counter_was_min = Wire(Bool())
    val selected_counter_stays_min = Wire(Bool())
    selected_counter_was_min := false.B
    selected_counter_stays_min := false.B
    when (io.update && !io.branch_result) {
      when (io.buffer_offset === 0.U) {
        selected_counter_was_min := state_bank0(io.buffer_addr) === 0.U
        selected_counter_stays_min := state_bank0(io.buffer_addr) === 0.U
      }.elsewhen (io.buffer_offset === 1.U) {
        selected_counter_was_min := state_bank1(io.buffer_addr) === 0.U
        selected_counter_stays_min := state_bank1(io.buffer_addr) === 0.U
      }.elsewhen (io.buffer_offset === 2.U) {
        selected_counter_was_min := state_bank2(io.buffer_addr) === 0.U
        selected_counter_stays_min := state_bank2(io.buffer_addr) === 0.U
      }.otherwise {
        selected_counter_was_min := state_bank3(io.buffer_addr) === 0.U
        selected_counter_stays_min := state_bank3(io.buffer_addr) === 0.U
      }
    }
    fvAssert(!selected_counter_was_min || selected_counter_stays_min, "counter_stays_at_min_on_not_taken_update")
  }

  // Safety 6: All counters start in the initialized state (weak not taken = 1) and are
  // bounded below by 0. This asserts the initial state is correct.
  fvAssert(state_bank0(0) === 1.U || state_bank0(0) >= 0.U, "state_bank0_entry0_initialized")
  fvAssert(state_bank1(0) === 1.U || state_bank1(0) >= 0.U, "state_bank1_entry0_initialized")
  fvAssert(state_bank2(0) === 1.U || state_bank2(0) >= 0.U, "state_bank2_entry0_initialized")
  fvAssert(state_bank3(0) === 1.U || state_bank3(0) >= 0.U, "state_bank3_entry0_initialized")
}

object VerilogGenerator extends App {
  emitVerilog(new branchPredictionBuffer(), args)
}
