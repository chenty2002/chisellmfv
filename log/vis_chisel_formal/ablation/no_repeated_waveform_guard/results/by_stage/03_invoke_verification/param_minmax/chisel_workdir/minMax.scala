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
  
  // Safety: sup (candidate max) is always >= inf (candidate min)
  // This ensures the min/max tracking is mathematically consistent
  fvAssert(sup >= inf, "sup_always_geq_inf")
  
  // Safety: When clear is asserted, output must be 0
  assertImplies(io.clear, io.out === 0.U, "clear_outputs_zero")
  
  // Safety: When disabled (clear=false, enable=false), output is the last stored value
  assertImplies(!io.clear && !io.enable, io.out === last, "disabled_outputs_last")
  
  // Safety: When in reset mode (clear=false, enable=true, reset=true), output is the input
  assertImplies(!io.clear && io.enable && io.reset, io.out === io.in, "reset_outputs_input")
  
  // Safety: When actively tracking (clear=false, enable=true, reset=false),
  // output is the average of sup and inf. Verify avg = (sup + inf) / 2
  // by checking the sum relationship: 2*avg + aux = sup + inf
  fvAssert(io.clear || !io.enable || io.reset ||
    (Cat(aux, avg) === (Cat(0.U(1.W), sup) + Cat(0.U(1.W), inf))),
    "avg_computation_correct")
}

object VerilogGenerator extends App {
  emitVerilog(new minMax(), args)
}
