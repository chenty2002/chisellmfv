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
  
  // Structural invariant: output z is the XOR of all three state registers
  fvAssert(io.z === (p ^ q ^ r), "z_eq_p_xor_q_xor_r")
  
  // Structural invariant: wire w is the XOR of p and q
  fvAssert(w === (p ^ q), "w_eq_p_xor_q")
  
  // Bounded liveness: if input i changes from the previous value,
  // the output z must change within 3 cycles
  // (The state propagates: i -> p in 1 cycle, p -> q in 1 cycle, 
  //  and r feeds back z, so 3 cycles covers the full state update)
  val prev_i = RegNext(io.i)
  val input_changed = io.i =/= prev_i
  val z_changed_within_3 = (io.z =/= RegNext(io.z)) | RegNext(io.z =/= RegNext(RegNext(io.z))) | RegNext(RegNext(io.z =/= RegNext(RegNext(RegNext(io.z)))))
  fvAssert(!input_changed || z_changed_within_3, "input_change_propagates_to_output_within_3")
  
  // Mutex: after reset, all registers are 0
  // This is guaranteed by RegInit, so we assert it holds
  fvAssert(!reset.asBool || (p === 0.B && q === 0.B && r === 0.B), "registers_zero_after_reset")
  
  // Stable output when input is stable for 4 cycles:
  // After 4 cycles of constant input, the output stabilizes
  // (p and q catch up to the input, and the feedback loop settles)
  val input_stable_4 = RegNext(io.i) === io.i && RegNext(RegNext(io.i)) === RegNext(io.i) && 
                        RegNext(RegNext(RegNext(io.i))) === RegNext(RegNext(io.i)) && 
                        RegNext(RegNext(RegNext(RegNext(io.i)))) === RegNext(RegNext(RegNext(io.i)))
  val output_stable = io.z === RegNext(io.z)
  fvAssert(!input_stable_4 || output_stable, "output_stable_when_input_stable_4_cycles")
}

object VerilogGenerator extends App {
  emitVerilog(new gray(), args)
}
