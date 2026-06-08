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

  // ===== Formal Verification Assertions =====

  // Invariant 1: Minimum must never exceed maximum (most critical property)
  fvAssert(min <= max, "min_leq_max")

  // Invariant 2: sup is always at least the current max (monotonic tracking)
  fvAssert(sup >= max, "sup_geq_max")

  // Invariant 3: inf is always at most the current min (monotonic tracking)
  fvAssert(inf <= min, "inf_leq_min")

  // Invariant 4: sup is never less than the current input
  fvAssert(sup >= io.in, "sup_geq_in")

  // Invariant 5: inf is never greater than the current input
  fvAssert(inf <= io.in, "inf_leq_in")

  // Invariant 6: When in active tracking mode (enable && !clear && !reset),
  // the output must be the average of sup and inf
  val trackingMode = io.enable && !io.clear && !io.reset
  fvAssert(!trackingMode || io.out === avg, "output_is_avg_in_tracking_mode")

  // Invariant 7: Bounded liveness — when enable is asserted (tracking active),
  // the system makes progress by updating last to the current input
  // within the same cycle (combinational path). We assert that after n cycles
  // of maintained enable, last reflects the most recent in.
  // Use a relaxed liveness: if enable is high and we are not clearing/resetting,
  // last should eventually equal io.in (it does immediately by construction).
  astRelaxedLiveness(trackingMode, last === io.in, 2, "last_updates_when_tracking")
}

object VerilogGenerator extends App {
  emitVerilog(new minMax(), args)
}
