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

  // Snapshot inr from the previous cycle to match dout's pipeline stage.
  // Both inr and dout update on the same clock edge, so dout holds the
  // barrel-shifted result of the *previous* cycle's inr.
  val prev_inr = RegInit(0.U(4.W))
  prev_inr := inr

  // Snapshot io.amount from the previous cycle to match the value used by
  // the combinational barrel shifter when dout was computed.
  val prev_amount = RegInit(0.U(2.W))
  prev_amount := io.amount

  // Assertion 1: Pipeline correctness
  // dout is combinational result of prev_inr (previous din) rotated by prev_amount
  // (the amount value that was active when dout was captured).
  // This verifies the barrel shifter implements correct rotate-right.
  val expected = MuxLookup(prev_amount, 0.U(4.W))(Seq(
    0.U -> prev_inr,
    1.U -> Cat(prev_inr(0), prev_inr(3, 1)),
    2.U -> Cat(prev_inr(1, 0), prev_inr(3, 2)),
    3.U -> Cat(prev_inr(2, 0), prev_inr(3))
  ))
  fvAssert(dout === expected, "dout_equals_inr_rotated_by_amount")

  // Assertion 2: PopCount preservation
  // Rotation preserves the number of 1-bits (Hamming weight),
  // verifying data integrity through the barrel shifter pipeline.
  // Use prev_inr to match dout's pipeline stage.
  fvAssert(PopCount(dout) === PopCount(prev_inr), "popcount_preserved")
}

object VerilogGenerator extends App {
  emitVerilog(new rotate(), args)
}
