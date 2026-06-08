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

  // Track whether at least one tracking update has been performed since the
  // last fresh start (clear, disable, or reset).  After the first tracked
  // update the registers hold a pair of real observed samples, so min <= max.
  val fresh_start = io.clear || !io.enable || io.reset
  val tracking = io.enable && !io.clear && !io.reset
  val tracked_once = RegInit(false.B)
  when(tracking) {
    tracked_once := true.B
  }
  when(fresh_start) {
    tracked_once := false.B
  }

  // Core safety: once we have completed at least one tracking update, the
  // running minimum must never exceed the running maximum.
  fvAssert(!tracked_once || (min <= max), "min_leq_max_after_tracking")

  // Correctness of min-candidate mux: inf (candidate new min) must always be
  // less than or equal to the current min register.  A reversed comparison
  // (io.in > min) would violate this.
  fvAssert(inf <= min, "inf_leq_min")

  // Correctness of max-candidate mux: sup (candidate new max) must always be
  // greater than or equal to the current max register.  A reversed comparison
  // (io.in < max) would violate this.
  fvAssert(sup >= max, "sup_geq_max")

  // Bounded liveness: once tracking becomes active, the tracked_once flag
  // must be set within 2 cycles (it becomes true on the next clock edge when
  // tracking is asserted, or it may already be true from a previous active
  // period).
  astRelaxedLiveness(tracking, tracked_once, 2,
    "tracked_flag_set_within_two_cycles_of_tracking")
}

object VerilogGenerator extends App {
  emitVerilog(new minMax(), args)
}
