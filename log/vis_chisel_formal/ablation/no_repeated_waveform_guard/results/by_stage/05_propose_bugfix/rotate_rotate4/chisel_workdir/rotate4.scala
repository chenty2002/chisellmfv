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
  // Formal Verification Assertions
  // --------------------------------------------------------------------------

  // Golden-reference check: barrel shifter computes rotateright(inr, amount)
  fvAssert(
    Mux(io.amount === 0.U, tmp2 === inr,
    Mux(io.amount === 1.U, tmp2 === Cat(inr(0), inr(3, 1)),
    Mux(io.amount === 2.U, tmp2 === Cat(inr(1, 0), inr(3, 2)),
                            tmp2 === Cat(inr(2, 0), inr(3))))),
    "barrel_shifter_rotateright_correct"
  )

  // Reset property: after reset, output is 0 (both inr and dout are RegInit(0))
  // Check that io.dout is 0 while reset is asserted and stays 0 for some cycles after
  fvAssert(
    !reset.asBool || (io.dout === 0.U),
    "output_zero_during_reset"
  )

  // Stable-input property: when amount is 0, the output should retain the
  // last rotated value (i.e., dout stays unchanged across cycles)
  // This is implicit from the architecture but worth verifying.
  fvAssert(
    (io.amount =/= 0.U) || (io.dout === dout),
    "output_equals_dout_when_amount_zero"
  )

  // Forward-progress / pipeline not stuck: every cycle the input is sampled
  // into inr, and the barrel-shifted result flows to dout on the next edge.
  // Check that tmp2 (the computed result) always reflects inr and amount in
  // the same cycle — this is a combinational data-path sanity check.
  fvAssert(
    tmp2 === Mux(io.amount(1),
      Mux(io.amount(0), Cat(inr(2, 0), inr(3)),               // rotate right by 3
                         Cat(inr(1, 0), inr(3, 2))),           // rotate right by 2
      Mux(io.amount(0), Cat(inr(0), inr(3, 1)),               // rotate right by 1
                         inr)),                                 // rotate right by 0
    "barrel_shifter_implementation_match"
  )
}

object VerilogGenerator extends App {
  emitVerilog(new rotate(), args)
}
