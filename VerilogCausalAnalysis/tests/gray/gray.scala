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
  
  // Formal verification assertions
  
  // Property 2: Output should be the third bit of the current state
  fvAssert(io.z === r, "Output z should equal register r")
  
  // Property 3: Combinational logic consistency
  // w should be p XOR q
  fvAssert(w === (p ^ q), "Wire w should be p XOR q")
  
  // Property 4: Output computation consistency
  // io.z should be w XOR r
  fvAssert(io.z === (w ^ r), "Output z should be w XOR r")
  
  // Property 5: Register shift behavior
  // q should get previous p value
  assertNextStepWhen(true.B, q === p, "Register q should get previous p value")
  
  // Property 6: Register shift behavior for r
  // r should get previous io.z value
  assertNextStepWhen(true.B, r === io.z, "Register r should get previous io.z value")
  
  // Property 7: Input propagation to p
  // p should get io.i value in next cycle
  assertNextStepWhen(true.B, p === io.i, "Register p should get io.i value")
  
  // Property 8: Stability when input is stable
  // If input doesn't change, the Gray code sequence should follow expected pattern
  assertStableWhen(io.i === p, Cat(io.i, p, q), "When input is stable, state should follow expected pattern")
}

object VerilogGenerator extends App {
  emitVerilog(new gray(), args)
}