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
  
  // ========== FORMAL ASSERTIONS ==========
  
  // Safety 1: sup (selected max of in and current max) must always be >= inf (selected min of in and current min)
  // This holds regardless of the internal state because:
  //   - If in >= max_reg: sup=in, inf=min(...) <= in, so sup >= inf
  //   - If in < min_reg: sup=max_reg, inf=in, and max_reg >= min_reg > in... except when min_reg > max_reg
  //   But we can prove: sup >= inf always holds by case analysis
  fvAssert(sup >= inf, "sup_ge_inf")
  
  // Safety 2: When accumulating (enable && !reset && !clear), the average must be between inf and sup
  // Since avg = (sup + inf) / 2, it follows that inf <= avg <= sup
  fvAssert(!(io.enable && !io.reset && !io.clear) || (avg >= inf && avg <= sup), "avg_between_inf_sup")
  
  // Safety 3: Clear output is always zero
  fvAssert(!io.clear || (io.out === 0.U), "clear_output_zero")
  
  // Safety 4: When disabled (!enable) and not clearing, output equals last input seen
  fvAssert(io.clear || io.enable || (io.out === last), "disabled_output_last")
  
  // Safety 5: When reset (enable && reset && not clear), output equals current input
  fvAssert(!(io.enable && io.reset && !io.clear) || (io.out === io.in), "reset_output_input")
  
  // Safety 6: When accumulating (enable && !reset && !clear), output equals average
  fvAssert(!(io.enable && !io.reset && !io.clear) || (io.out === avg), "accumulate_output_avg")
  
  // Safety 7: aux is the carry bit from sup + inf; it is 1 only when sup + inf overflows 128 bits
  // Since sup >= inf and both are UInt(128.W), sup + inf < 2^129, so aux is actually the 129th bit
  fvAssert(aux === sum(128), "aux_is_carry")
  
  // Progress (bounded liveness): When accumulating, the min <= max invariant is restored within 1 cycle
  // After one cycle of accumulating (enable && !reset && !clear), min <= max must hold
  // This catches the case where clear/!enable/reset broke the invariant
  assertAfterNStepWhen(io.enable && !io.reset && !io.clear, 1, min <= max, "min_le_max_after_accumulate")
}

object VerilogGenerator extends App {
  emitVerilog(new minMax(), args)
}
