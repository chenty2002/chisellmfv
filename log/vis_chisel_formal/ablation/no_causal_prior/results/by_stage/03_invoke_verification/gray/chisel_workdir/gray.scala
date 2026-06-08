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

  // ── Formal Verification Assertions ──

  // Safety 1: The output always equals the delayed input (p).
  // By induction: io.z = p ^ q ^ r, and since q = prev(p), r = prev(io.z),
  // the XOR chain collapses to io.z = p for all cycles.
  fvAssert(io.z === p, "io.z_equals_p")

  // Safety 2: q is the previous cycle's p (shift register chain integrity).
  fvAssert(q === RegNext(p), "q_equals_prev_p")

  // Safety 3: r is the previous cycle's output (output feedback integrity).
  fvAssert(r === RegNext(io.z), "r_equals_prev_io_z")
}

object VerilogGenerator extends App {
  emitVerilog(new gray(), args)
}
