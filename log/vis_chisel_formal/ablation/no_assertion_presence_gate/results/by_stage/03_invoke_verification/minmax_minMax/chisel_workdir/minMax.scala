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

  // ====== Formal Verification Assertions ======

  // Safety 1: sup = max(io.in, max), so sup must always be >= max
  fvAssert(sup >= max, "sup_ge_max")

  // Safety 2: inf = min(io.in, min), so inf must always be <= min
  fvAssert(inf <= min, "inf_le_min")

  // Safety 3: sup = max(io.in, max) >= io.in, so sup must always be >= io.in
  fvAssert(sup >= io.in, "sup_ge_in")

  // Safety 4: inf = min(io.in, min) <= io.in, so inf must always be <= io.in
  fvAssert(inf <= io.in, "inf_le_in")

  // Safety 5: avg is the integer average of sup and inf: avg = (sup + inf) >> 1
  // Replicate the same widened sum computation used in the design to avoid
  // width-inference issues with bit extraction on an inline sum expression.
  val assert_sum = (0.U ## sup) + (0.U ## inf)
  fvAssert(avg === assert_sum(MSB + 1, 1), "avg_is_half_sum_sup_inf")
}

object VerilogGenerator extends App {
  emitVerilog(new minMax(), args)
}
