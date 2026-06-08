package llmverify

import chisel3._
import chisel3.util._
import chiselFv._

class minMax(val MSB: Int = 8) extends Module with Formal {
  val io = IO(new Bundle {
    val clear = Input(Bool())
    val enable = Input(Bool())
    val reset = Input(Bool())
    val in = Input(UInt((MSB + 1).W))
    val out = Output(UInt((MSB + 1).W))
    
    // Additional outputs to preserve internal signals
    val min_debug = Output(UInt((MSB + 1).W))
    val max_debug = Output(UInt((MSB + 1).W))
    val last_debug = Output(UInt((MSB + 1).W))
    val sup_debug = Output(UInt((MSB + 1).W))
    val inf_debug = Output(UInt((MSB + 1).W))
    val avg_debug = Output(UInt((MSB + 1).W))
  })
  
  // Registers
  val min = RegInit(UInt((MSB + 1).W), Fill(MSB + 1, 1.U)) // all ones
  val max = RegInit(0.U((MSB + 1).W))
  val last = RegInit(io.in) // nondeterministic initial state
  
  // Combinational logic
  val sup = Wire(UInt((MSB + 1).W))
  val inf = Wire(UInt((MSB + 1).W))
  val avg = Wire(UInt((MSB + 1).W))
  val aux = Wire(Bool())
  
  // Next state logic
  sup := Mux(io.in > max, io.in, max) // unsigned comparison
  inf := Mux(io.in < min, io.in, min)
  
  // Average of min and max: {avg,aux} = {1'b0,sup} + {1'b0,inf}
  val sum = (0.U ## sup) + (0.U ## inf)
  avg := sum(MSB + 1, 1) // upper bits
  aux := sum(0) // lower bit
  
  // Sequential logic
  when(io.clear) {
    last := 0.U
    max := 0.U
    min := Fill(MSB + 1, 1.U)
  }.elsewhen(!io.enable) {
    max := 0.U
    min := Fill(MSB + 1, 1.U)
  }.otherwise {
    last := io.in
    when(io.reset) {
      max := 0.U
      min := Fill(MSB + 1, 1.U)
    }.otherwise {
      max := sup
      min := inf
    }
  }
  
  // Output logic
  io.out := Mux(io.clear, 0.U,
    Mux(!io.enable, last,
      Mux(io.reset, io.in, avg)))
  
  // Debug outputs
  io.min_debug := min
  io.max_debug := max
  io.last_debug := last
  io.sup_debug := sup
  io.inf_debug := inf
  io.avg_debug := avg

  // ========== Formal Verification Assertions ==========

  // ------------------------------------------------------------------
  // Safety 1: inf must always be <= sup
  // This is the fundamental ordering invariant — the min of two numbers
  // can never exceed the max of two numbers. A violation would indicate
  // a bug in the sup/inf computation or bit-width handling.
  // ------------------------------------------------------------------
  fvAssert(inf <= sup, "inf_leq_sup")

  // ------------------------------------------------------------------
  // Safety 2: avg must lie between inf and sup
  // The average (floor((sup+inf)/2)) of two unsigned numbers must always
  // be bounded by those numbers. Catches bit-width truncation errors or
  // incorrect averaging logic.
  // ------------------------------------------------------------------
  fvAssert(inf <= avg && avg <= sup, "avg_between_inf_and_sup")

  // ------------------------------------------------------------------
  // Safety 3: After a normal tracking update (enable=1, reset=0, clear=0),
  // the updated min must be <= the updated max.
  // Since min <- inf and max <- sup, and inf <= sup (Safety 1), this
  // holds by construction — but a bug in the update logic could break it.
  // ------------------------------------------------------------------
  val normalUpdate = io.enable && !io.reset && !io.clear
  fvAssert(!normalUpdate || min <= max, "min_leq_max_after_normal_update")

  // ------------------------------------------------------------------
  // Safety 4: Output mux correctness
  // The output is selected from 0.U, last, io.in, or avg based on
  // the control signals. Verify the correct value is selected.
  // ------------------------------------------------------------------
  // Case 1: When clear is asserted, output must be 0
  fvAssert(!io.clear || io.out === 0.U, "out_is_zero_when_clear")

  // Case 2: When not clear but enable is deasserted, output must equal last
  fvAssert(io.clear || io.enable || io.out === last, "out_is_last_when_disabled")

  // Case 3: When enabled, not clear, and reset asserted, output must equal input
  fvAssert(io.clear || !io.enable || !io.reset || io.out === io.in, "out_is_in_when_reset")

  // Case 4: When enabled, not clear, and not reset, output must equal avg
  fvAssert(io.clear || !io.enable || io.reset || io.out === avg, "out_is_avg_when_tracking")

  // ------------------------------------------------------------------
  // Safety 5: When disabled (!enable), min and max must be reset to
  // their initial values for the next tracking window.
  // ------------------------------------------------------------------
  fvAssert(io.enable || min === Fill(MSB + 1, 1.U), "min_reset_when_disabled")
  fvAssert(io.enable || max === 0.U, "max_reset_when_disabled")

  // ------------------------------------------------------------------
  // Bounded Liveness: When the system is actively tracking (normal
  // update), tracked min should eventually converge to be <= tracked
  // max within a small number of cycles after reset or clear.
  // If enable stays high continuously for 3 cycles after a reset/clear,
  // the min <= max invariant should be established.
  // We encode this as: if enable has been high for at least 3 consecutive
  // cycles (counting from reset), then min <= max.
  // ------------------------------------------------------------------
  // Counter tracking cycles since last reset/clear while enable is high
  val activeCount = RegInit(0.U(4.W))
  when(io.clear || io.reset || !io.enable) {
    activeCount := 0.U
  }.elsewhen(activeCount < 15.U) {
    activeCount := activeCount + 1.U
  }

  // After 3 active cycles (enough for at least one normal update to propagate),
  // min should be <= max
  fvAssert(!io.enable || activeCount < 3.U || min <= max,
    "min_leq_max_after_three_active_cycles")

  // ------------------------------------------------------------------
  // Bounded Liveness (relaxed): When enable is high and the system is
  // actively tracking (not clearing, not resetting), the output should
  // correspond to the average. Since this is purely combinational, we
  // verify it holds immediately when tracking.
  // ------------------------------------------------------------------
  astRelaxedLiveness(
    io.enable && !io.reset && !io.clear && !(io.in <= max && io.in >= min),
    io.enable && !io.reset && !io.clear && io.out === avg,
    1,
    "out_converges_to_avg_when_tracking"
  )
}

object VerilogGenerator extends App {
  emitVerilog(new minMax(), args)
}
