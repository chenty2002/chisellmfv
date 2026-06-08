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

  // ---------------------------------------------------------------------------
  // Formal verification assertions
  // ---------------------------------------------------------------------------

  // Core invariant: z[t] = i[t] ^ i[t-1] ^ z[t-1]
  // With q = i[t-1] and r = z[t-1], this means:
  //   io.z === io.i ^ q ^ r
  // Rearranged: (io.z ^ r) === (io.i ^ q)
  // In other words, a change in output equals a change in input.
  fvAssert((io.z ^ r) === (io.i ^ q), "core_invariant_change_in_z_equals_change_in_i")

  // When input is stable (same as previous cycle), output must be stable too.
  // i[t] === q  (i[t-1])  =>  z[t] === r  (z[t-1])
  fvAssert(!(io.i === q) || (io.z === r), "output_stable_when_input_stable")

  // When input toggles, output must toggle as well.
  // i[t] =/= q  (i[t-1])  =>  z[t] =/= r  (z[t-1])
  fvAssert(!(io.i =/= q) || (io.z =/= r), "output_toggles_when_input_toggles")
}

object VerilogGenerator extends App {
  emitVerilog(new gray(), args)
}
