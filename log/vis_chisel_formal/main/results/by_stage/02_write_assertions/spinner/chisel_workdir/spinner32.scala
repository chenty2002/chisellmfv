package llmverify

import chisel3._
import chisel3.util._
import chiselFv._

class spinner32 extends Module with Formal {
  val io = IO(new Bundle {
    val spin = Input(Bool())
    val amount = Input(UInt(5.W))
    val din = Input(UInt(32.W))
    val dout = Output(UInt(32.W))
  })

  // Internal registers
  val doutReg = RegInit(0.U(32.W))
  val inrReg = RegInit(0.U(32.W))
  val splReg = RegInit(false.B)

  // Barrel shifter stages
  val tmp0 = Wire(UInt(32.W))
  val tmp1 = Wire(UInt(32.W))
  val tmp2 = Wire(UInt(32.W))
  val tmp3 = Wire(UInt(32.W))
  val tmp4 = Wire(UInt(32.W))
  val tmp5 = Wire(UInt(32.W))

  tmp0 := inrReg

  // Stage 1: rotate by 1 bit
  when(io.amount(0)) {
    tmp1 := Cat(tmp0(0), tmp0(31, 1))
  }.otherwise {
    tmp1 := tmp0
  }

  // Stage 2: rotate by 2 bits
  when(io.amount(1)) {
    tmp2 := Cat(tmp0(1, 0), tmp0(31, 2))
  }.otherwise {
    tmp2 := tmp1
  }

  // Stage 3: rotate by 4 bits
  when(io.amount(2)) {
    tmp3 := Cat(tmp0(3, 0), tmp0(31, 4))
  }.otherwise {
    tmp3 := tmp2
  }

  // Stage 4: rotate by 8 bits
  when(io.amount(3)) {
    tmp4 := Cat(tmp0(7, 0), tmp0(31, 8))
  }.otherwise {
    tmp4 := tmp3
  }

  // Stage 5: rotate by 16 bits
  when(io.amount(4)) {
    tmp5 := Cat(tmp0(15, 0), tmp0(31, 16))
  }.otherwise {
    tmp5 := tmp4
  }

  // Sequential logic
  when(splReg) {
    inrReg := doutReg
  }.otherwise {
    inrReg := io.din
  }
  
  doutReg := tmp5
  splReg := io.spin

  // Output assignment
  io.dout := doutReg

  // ---------------------------------------------------------------------------
  // Formal Verification Assertions
  // ---------------------------------------------------------------------------

  // 1. Rotation correctness (combinational):
  //    tmp5 must equal a full rotateRight of inrReg by io.amount.
  //    This catches the barrel shifter cascade bug where stages 2-5 read
  //    from tmp0 (= inrReg) instead of the previous stage's output, so
  //    only the highest-priority amount bit takes effect instead of
  //    composing all rotation amounts.
  val shiftAmt = io.amount
  val rotateRightExpected = (inrReg >> shiftAmt) | (inrReg << (32.U - shiftAmt))
  fvAssert(tmp5 === rotateRightExpected, "rotation_correctness")

  // 2. Spin-mode register update correctness (sequential):
  //    When splReg (registered io.spin) is asserted, the *next* value of
  //    inrReg must equal the current doutReg (the output register value
  //    at the time the spin signal was active).
  val doutRegDelayed = RegNext(doutReg)
  assertImpliesDelay(splReg, inrReg === doutRegDelayed, 1, "spin_mode_update")

  // 3. Load-mode register update correctness (sequential):
  //    When splReg is deasserted, the *next* value of inrReg must equal
  //    the current io.din.
  val dinDelayed = RegNext(io.din)
  assertImpliesDelay(!splReg, inrReg === dinDelayed, 1, "load_mode_update")
}

object VerilogGenerator extends App {
  emitVerilog(new spinner32(), args)
}
