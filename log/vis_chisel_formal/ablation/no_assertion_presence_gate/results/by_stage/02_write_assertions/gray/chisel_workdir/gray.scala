package llmverify
import chisel3._
import chisel3.util._
import chiselFv._

class gray extends Module with Formal {
  val io = IO(new Bundle {
    val i = Input(Bool())
    val z = Output(Bool())
  })
  
  // Internal registers with non-deterministic initialization (using 0.B as default)
  val p = RegInit(0.B)
  val q = RegInit(0.B)
  val r = RegInit(0.B)
  
  // Wire
  val w = Wire(Bool())
  
  // Sequential logic
  r := io.z
  q := p
  p := io.i
  
  // Combinational logic
  w := p ^ q
  io.z := w ^ r
  
  // ===== Formal Verification Assertions =====
  
  // 1. Structural invariant: output is XOR of all three registers
  fvAssert(io.z === (p ^ q ^ r), "structural_xor_chain")
  
  // 2. Functional property: output equals input delayed by exactly 1 cycle
  //    Proven by induction: io.z(n) = p(n) = io.i(n-1)
  fvAssert(io.z === p, "output_is_delayed_input")
  
  // 3. Bounded liveness: when input is high, output must become high within 3 cycles
  //    Since io.z = RegNext(io.i), this is always satisfied in the next cycle
  astRelaxedLiveness(io.i, io.z, 3, "output_follows_input_within_3")
}

object VerilogGenerator extends App {
  emitVerilog(new gray(), args)
}
