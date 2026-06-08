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

  // Formal Verification Assertions

  // Assertion 1: Pipeline correctness
  // dout is combinational result of inr (previous din) rotated by io.amount
  // This verifies the barrel shifter implements correct rotate-right
  val expected = MuxLookup(io.amount, 0.U(4.W))(Seq(
    0.U -> inr,
    1.U -> Cat(inr(0), inr(3, 1)),
    2.U -> Cat(inr(1, 0), inr(3, 2)),
    3.U -> Cat(inr(2, 0), inr(3))
  ))
  fvAssert(dout === expected, "dout_equals_inr_rotated_by_amount")

  // Assertion 2: PopCount preservation
  // Rotation preserves the number of 1-bits (Hamming weight),
  // verifying data integrity through the barrel shifter pipeline
  fvAssert(PopCount(dout) === PopCount(inr), "popcount_preserved")
}

object VerilogGenerator extends App {
  emitVerilog(new rotate(), args)
}
