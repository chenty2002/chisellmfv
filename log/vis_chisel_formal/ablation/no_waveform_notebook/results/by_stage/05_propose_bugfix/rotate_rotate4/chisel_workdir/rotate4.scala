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

  // ---------------------------------------------------------------------------
  // Formal verification assertions
  // ---------------------------------------------------------------------------

  // Safety 1: Correct rotation property.
  // At every cycle, compute the barrel-shift of (inr, amount) using the same
  // algorithm as the design.  dout is the registered result of this computation
  // from the previous cycle, so it must equal the previous cycle's expected
  // barrel-shift output.
  val expected_tmp1 = Mux(io.amount(0), Cat(inr(0), inr(3, 1)), inr)
  val expected_tmp2 = Mux(io.amount(1), Cat(expected_tmp1(1, 0), expected_tmp1(3, 2)), expected_tmp1)
  fvAssert(dout === RegNext(expected_tmp2), "correct_rotation")

  // Safety 2: Input register captures the data input each cycle.
  // Use explicit initialization to match inr's RegInit(0.U(4.W)).
  fvAssert(inr === RegNext(io.din, 0.U(4.W)), "input_capture")

  // Safety 3: Rotating does not change the number of set bits (popcount).
  // dout holds the rotated value of inr from the previous cycle, so its
  // popcount must match the popcount of the previous cycle's inr.
  fvAssert(PopCount(dout) === RegNext(PopCount(inr)), "popcount_invariance")
}

object VerilogGenerator extends App {
  emitVerilog(new rotate(), args)
}
