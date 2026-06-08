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

  // Safety 1: min must never exceed max (core invariant of the tracker)
  fvAssert(min <= max, "min_leq_max")

  // Safety 2: after clear, min/max are properly re-initialized
  assertImplies(io.clear,
    min === "hFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF".U && max === 0.U,
    "clear_initializes_min_max")

  // Safety 3: when enable is active and reset fires (but not clearing),
  // min/max are reset to initial values
  assertImplies(RegNext(io.enable && io.reset && !io.clear),
    RegNext(min === "hFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF".U && max === 0.U),
    "reset_reinitializes_min_max")

  // Safety 4: when enable is active and neither clear nor reset,
  // max is set to sup (sup works correctly)
  val normal_update = io.enable && !io.clear && !io.reset
  assertImplies(normal_update,
    max === sup && min === inf,
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
  assertNextStepWhen(normal_update,
    max === sup && min === inf,
    "liveness_max_min_update_next_cycle")
}

object VerilogGenerator extends App {
  emitVerilog(new minMax(), args)
}
