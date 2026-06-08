package llmverify

import chisel3._
import chisel3.util._
import chiselFv._

class rotate extends Module with Formal {
  val io = IO(new Bundle {
    val amount = Input(UInt(2.W))
    val din = Input(UInt(4.W))
    val dout = Output(UInt(4.W))
  })

  // Registers
  val inr = RegInit(0.U(4.W))
  val dout = RegInit(0.U(4.W))

  // Combinational logic for barrel shifter
  val tmp0 = inr
  val tmp1 = Mux(io.amount(0), Cat(tmp0(0), tmp0(3, 1)), tmp0)  // Rotate right by 1 if amount[0] = 1
  val tmp2 = Mux(io.amount(1), Cat(tmp1(1, 0), tmp1(3, 2)), tmp1)  // Rotate right by 2 if amount[1] = 1

  // Sequential logic on clock edge
  dout := tmp2
  inr := io.din

  // Output assignment
  io.dout := dout

  // --------------------------------------------------------------------------
  // Formal verification assertions
  // --------------------------------------------------------------------------

  // Safety: Rotation is a permutation of bits, so PopCount is always preserved
  // by the barrel shifter combinational logic.
  fvAssert(PopCount(inr) === PopCount(tmp2), "rotate_preserves_popcount")

  // Safety: Rotating by zero is the identity function.
  assertImplies(io.amount === 0.U, tmp2 === inr, "rotate_by_zero_is_identity")

  // Bounded liveness: If the stored rotate-source register (inr) is non-zero,
  // the output must become non-zero within 2 cycles.  Rotation of a non-zero
  // value is always non-zero because rotation is a bijection over the 16
  // possible 4-bit values; the pipeline has a 1-cycle latency (inr → barrel
  // → dout), so a bound of 2 comfortably covers the propagation delay.
  astRelaxedLiveness(inr =/= 0.U, io.dout =/= 0.U, 2,
    "non_zero_stored_input_gives_non_zero_output")
}

object VerilogGenerator extends App {
  emitVerilog(new rotate(), args)
}
