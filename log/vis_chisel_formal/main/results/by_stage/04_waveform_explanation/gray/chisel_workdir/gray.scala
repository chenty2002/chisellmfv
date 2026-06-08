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

  // ===== Formal Assertions =====

  // Safety: output is always the XOR of the three registers p, q, r
  fvAssert(io.z === (p ^ q ^ r), "output_xor_definition")

  // Reset initialization: all registers start at 0 after reset (cycle 0)
  assertAt(0.U, p === 0.B && q === 0.B && r === 0.B, "init_regs")

  // Bounded liveness: after 3 cycles of holding input i=0, output z must be 0
  val i_d1 = RegNext(io.i, 0.B)
  val i_d2 = RegNext(i_d1, 0.B)
  fvAssert(!(io.i === 0.B && i_d1 === 0.B && i_d2 === 0.B) || io.z === 0.B, "output_zero_after_three_zeros")

  // Bounded liveness: after 3 cycles of holding input i=1, output z must be 1
  fvAssert(!(io.i === 1.B && i_d1 === 1.B && i_d2 === 1.B) || io.z === 1.B, "output_one_after_three_ones")

  // Relaxed liveness: when input transitions from 0 to 1, output reaches 1 within 3 cycles
  val i_rise = io.i && !i_d1
  astRelaxedLiveness(i_rise, io.z === 1.B, 3, "output_high_on_rise")

  // Relaxed liveness: when input transitions from 1 to 0, output reaches 0 within 3 cycles
  val i_fall = !io.i && i_d1
  astRelaxedLiveness(i_fall, io.z === 0.B, 3, "output_low_on_fall")
}

object VerilogGenerator extends App {
  emitVerilog(new gray(), args)
}
