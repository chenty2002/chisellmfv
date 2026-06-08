package llmverify
import chisel3._
import chisel3.util._
import chiselFv._

class minMax extends Module with Formal {
  val io = IO(new Bundle {
    val clear = Input(Bool())
    val enable = Input(Bool())
    val reset = Input(Bool())
    val in = Input(UInt(128.W))
    val out = Output(UInt(128.W))
  })
  
  // Internal registers
  val min = RegInit("hFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF".U(128.W))  // all ones
  val last = RegInit(0.U(128.W))  // nondeterministic in Verilog, using 0
  val max = RegInit(0.U(128.W))
  
  // Combinational logic
  val sup = Mux(io.in > max, io.in, max)  // unsigned comparison
  val inf = Mux(io.in < min, io.in, min)  // unsigned comparison
  
  // Average calculation with carry (aux)
  val sum = Cat(0.U(1.W), sup) + Cat(0.U(1.W), inf)
  val avg = sum(127, 0)  // lower 128 bits
  val aux = sum(128)     // carry bit
  
  // Shadow registers for next-cycle assertion checks
  val prev_sup = RegNext(sup)
  val prev_inf = RegNext(inf)
  
  // Not-first-cycle guard: registers have sentinel initial values (min=0xFFFF..., max=0)
  // that violate assertions at cycle 0. Formal tools may also treat RegNext init as
  // nondeterministic, so gate all post-update assertions past the first cycle.
  val notFirstCycle = RegNext(true.B, false.B)
  
  // Sequential logic
  when(io.clear) {
    last := 0.U
    max := 0.U
    min := "hFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF".U
  }.elsewhen(!io.enable) {
    max := 0.U
    min := "hFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF".U
  }.otherwise {
    last := io.in
    when(io.reset) {
      max := 0.U
      min := "hFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF".U
    }.otherwise {
      max := sup
      min := inf
    }
  }
  
  // Output logic
  io.out := Mux(io.clear, 0.U,
    Mux(!io.enable, last,
      Mux(io.reset, io.in, avg)))

  // ========== Formal Verification Assertions ==========

  val normal_update = io.enable && !io.clear && !io.reset

  // Safety 1: min must never exceed max (core invariant of the tracker)
  // Gated by RegNext(normal_update) because min/max registers are updated via
  // non-blocking assignments and hold old (sentinel) values during the first
  // active cycle after an idle or clear cycle. Using RegNext checks one cycle
  // after normal_update fires, when registers have actually taken their new values.
  // Also gated by notFirstCycle to skip cycle 0 where sentinel values violate.
  assertImplies(RegNext(normal_update) && notFirstCycle, min <= max, "min_leq_max")

  // Safety 2: after clear, min/max are properly re-initialized
  // Use RegNext to check in the cycle AFTER clear, so registers have updated.
  assertImplies(RegNext(io.clear),
    min === "hFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF".U && max === 0.U,
    "clear_initializes_min_max")

  // Safety 3: when enable is active and reset fires (but not clearing),
  // min/max are reset to initial values
  // Check the register values AFTER they have been updated by the reset.
  assertImplies(RegNext(io.enable && io.reset && !io.clear),
    min === "hFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF".U && max === 0.U,
    "reset_reinitializes_min_max")

  // Safety 4: when enable is active and neither clear nor reset,
  // max is set to sup and min is set to inf (update works correctly)
  // Check in the next cycle against the captured previous sup/inf values.
  // Gate with notFirstCycle to avoid initial-state mismatch where
  // min=0xFFFF...FFFF and prev_inf=0.
  assertImplies(RegNext(normal_update) && notFirstCycle,
    max === prev_sup && min === prev_inf,
    "normal_update_tracks_sup_inf")

  // Safety 5: sup is the max of io.in and the old max
  assertImplies(normal_update,
    sup >= max && sup >= io.in,
    "sup_is_max_of_in_and_old_max")

  // Safety 6: inf is the min of io.in and the old min
  assertImplies(normal_update,
    inf <= min && inf <= io.in,
    "inf_is_min_of_in_and_old_min")

  // Safety 7: output correctness - clear produces zero
  assertImplies(io.clear, io.out === 0.U, "clear_outputs_zero")

  // Safety 8: output correctness - when idle (!enable and !clear), output is last seen value
  assertImplies(!io.enable && !io.clear, io.out === last, "idle_outputs_last")

  // Safety 9: output correctness - when enable and reset (not clear), output is io.in
  assertImplies(io.enable && io.reset && !io.clear, io.out === io.in, "reset_outputs_input")

  // Safety 10: output correctness - when actively tracking (enable, no reset, no clear),
  // output is the computed average
  assertImplies(normal_update, io.out === avg, "active_outputs_avg")

  // Safety 11: avg = (sup + inf) / 2 (the lower 128 bits of the sum)
  fvAssert(Cat(aux, avg) === sum, "avg_is_floor_of_sup_plus_inf_over_two")

  // Liveness 12: bounded liveness - when enable is high and not clearing/resetting,
  // after exactly 1 cycle the registers update to reflect the new min/max
  // Replaced assertNextStepWhen with assertImplies(RegNext(...)) to avoid
  // the double-registering semantics that caused a one-cycle-late check.
  // Gate with notFirstCycle to avoid initial-state register mismatch.
  assertImplies(RegNext(normal_update) && notFirstCycle,
    max === prev_sup && min === prev_inf,
    "liveness_max_min_update_next_cycle")
}

object VerilogGenerator extends App {
  emitVerilog(new minMax(), args)
}
