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
  // Initialization done flag: prevents tracking assertions from firing before
  // registers have been updated on the first clock edge, ensuring min <= max
  // after the first update (standard min=max, max=min initialization pattern).
  val init_done = RegInit(false.B)
  
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
  // Only mark init_done as true when the circuit is in a valid tracking state
  // (enable=1, clear=0, reset=0). This ensures the assertion only fires after
  // min and max have been updated with proper sup/inf values (guaranteeing min <= max).
  // When clear, !enable, or reset is active, keep init_done false to prevent
  // the tracking assertions from firing while min/max hold inconsistent values.
  when(io.clear || !io.enable || io.reset) {
    init_done := false.B
  }.otherwise {
    init_done := true.B
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

  // ===== Formal Verification Assertions =====
  // Active tracking mode: enable high, clear low, reset low, and init_done high
  // (init_done ensures registers have been updated at least once, so min <= max)
  val tracking = io.enable && !io.clear && !io.reset && init_done

  // Safety: min must never exceed max during active tracking
  fvAssert(!tracking || (min <= max), "min_less_eq_max_during_tracking")

  // Safety: sup (max of in and max) must be >= inf (min of in and min)
  fvAssert(!tracking || (sup >= inf), "sup_ge_inf_during_tracking")

  // Safety: the computed average (sup+inf>>1) must lie between inf and sup
  fvAssert(!tracking || (inf <= avg && avg <= sup), "avg_between_inf_sup_during_tracking")
}

object VerilogGenerator extends App {
  emitVerilog(new minMax(), args)
}
