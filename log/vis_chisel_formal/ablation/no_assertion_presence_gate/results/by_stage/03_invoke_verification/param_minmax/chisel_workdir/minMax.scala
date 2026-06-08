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
  
  // --- Safety: sup is always >= inf ---
  // sup = max(io.in, max) and inf = min(io.in, min)
  // Mathematically: max(io.in, max) >= io.in >= min(io.in, min) => sup >= inf
  // This guards against incorrect min/max comparison or MUX logic.
  fvAssert(sup >= inf, "sup_ge_inf")
  
  // --- Safety: Output correctness ---
  // Property 1: When clear is asserted, output must be zero
  fvAssert(!io.clear || io.out === 0.U, "clear_output_zero")
  
  // Property 2: When not clear and enable is false, output must be last
  fvAssert(io.clear || io.enable || io.out === last, "disabled_output_last")
  
  // Property 3: When not clear, enabled, and reset is asserted, output must be io.in
  fvAssert(io.clear || !io.enable || !io.reset || io.out === io.in, "reset_output_in")
  
  // Property 4: When not clear, enabled, and not reset, output must be avg
  fvAssert(io.clear || !io.enable || io.reset || io.out === avg, "normal_output_avg")
  
  // --- Bounded Liveness: last register tracks io.in when enabled ---
  // When enable is true, not reset, and not clear, last should capture io.in
  // in the very next cycle.  We use RegNext(io.in) to capture the previous
  // cycle's io.in value for comparison at time T+1.
  assertNextStepWhen(
    io.enable && !io.reset && !io.clear,
    last === RegNext(io.in),
    "last_stores_previous_in")
}

object VerilogGenerator extends App {
  emitVerilog(new minMax(), args)
}
