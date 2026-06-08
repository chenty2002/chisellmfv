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

  // ========== FORMAL ASSERTIONS ==========

  // --- Safety: Core min/max invariants ---

  // sup is the max of io.in and the tracked max, so it must be >= both
  fvAssert(sup >= io.in, "sup_ge_in")
  fvAssert(sup >= max, "sup_ge_max")

  // inf is the min of io.in and the tracked min, so it must be <= both
  fvAssert(inf <= io.in, "inf_le_in")
  fvAssert(inf <= min, "inf_le_min")

  // The candidate max must always be >= the candidate min.
  // Formal proof: sup = max(io.in, max) >= io.in >= min(io.in, min) = inf
  fvAssert(sup >= inf, "sup_ge_inf")

  // --- Safety: Average computation correctness ---
  // Verify that avg = (sup + inf) / 2 (floor division)
  val sum_check = (0.U ## sup) + (0.U ## inf)
  val avg_check = sum_check(MSB + 1, 1)
  fvAssert(avg === avg_check, "avg_eq_sup_plus_inf_div2")

  // --- Safety: Output selection correctness ---
  // In each control mode, the output must match the expected value
  fvAssert(!io.clear || io.out === 0.U,                                    "clear_out_zero")
  fvAssert(io.clear || io.enable || io.out === last,                        "disabled_out_last")
  fvAssert(io.clear || !io.enable || !io.reset || io.out === io.in,        "reset_out_in")
  fvAssert(io.clear || !io.enable || io.reset || io.out === avg,           "tracking_out_avg")

  // --- Bounded liveness: tracking mode produces avg output ---
  // When the module is actively tracking (enable && !reset && !clear), the output
  // must converge to avg (combinational, so within 1 cycle)
  astRelaxedLiveness(io.enable && !io.reset && !io.clear, io.out === avg, 1, "tracking_produces_avg")
}

object VerilogGenerator extends App {
  emitVerilog(new minMax(), args)
}
