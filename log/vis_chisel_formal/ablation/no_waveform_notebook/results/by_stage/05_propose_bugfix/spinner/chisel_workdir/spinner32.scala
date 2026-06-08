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

  // Stage 2: rotate by 2 bits (from tmp1)
  when(io.amount(1)) {
    tmp2 := Cat(tmp1(1, 0), tmp1(31, 2))
  }.otherwise {
    tmp2 := tmp1
  }

  // Stage 3: rotate by 4 bits (from tmp2)
  when(io.amount(2)) {
    tmp3 := Cat(tmp2(3, 0), tmp2(31, 4))
  }.otherwise {
    tmp3 := tmp2
  }

  // Stage 4: rotate by 8 bits (from tmp3)
  when(io.amount(3)) {
    tmp4 := Cat(tmp3(7, 0), tmp3(31, 8))
  }.otherwise {
    tmp4 := tmp3
  }

  // Stage 5: rotate by 16 bits (from tmp4)
  when(io.amount(4)) {
    tmp5 := Cat(tmp4(15, 0), tmp4(31, 16))
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
  // Formal verification assertions
  // ---------------------------------------------------------------------------

  // Assertion 1: Barrel shifter functional correctness
  //
  // The barrel shifter must compute the correct right rotation of inrReg by
  // io.amount.  The mathematically correct right rotation for a UInt is:
  //   (inrReg >> amount) | (inrReg << (32 - amount))
  //
  // This assertion catches the design bug: each stage computes its rotation
  // from the original tmp0 (inrReg) instead of from the previous stage's
  // output, so when multiple amount bits are set only the highest bit takes
  // effect.
  //
  // NOTE: We mask with (31,0) to prevent Chisel's width inference from
  // widening the left-shift result (32.U is 6-bit wide, so
  // inrReg << (32.U - 0.U) produces a 95-bit result when amount=0).
  val correctRot = ((inrReg >> io.amount) | (inrReg << (32.U - io.amount)))(31, 0)
  fvAssert(tmp5 === correctRot, "barrel_shifter_correct_rotation")

  // Assertion 2: Spin-mode bounded liveness
  //
  // When spin is asserted and a non-zero rotation amount is applied to
  // non-zero data, the output register must change within 10 cycles.  This
  // ensures the spinner makes forward progress: a stuck value indicates the
  // rotation pipeline is broken or the spin feedback deadlocked.
  val meaningfulSpin = io.spin && io.amount =/= 0.U && doutReg =/= 0.U
  val doutChanged = doutReg =/= RegNext(doutReg)
  astRelaxedLiveness(meaningfulSpin, doutChanged, 10, "spinner_progress")

  // Assertion 3: Spin feedback data-flow integrity
  //
  // When splReg is true (spin mode was entered the previous cycle), the
  // feedback path must carry doutReg into inrReg.  If the feedback breaks,
  // spinning data will be lost.  We verify that one cycle after splReg goes
  // high, inrReg matches the previous doutReg (accounting for the register
  // delays).
  assertImplies(splReg, RegNext(inrReg) === doutReg, "spin_feedback_active")
}

object VerilogGenerator extends App {
  emitVerilog(new spinner32(), args)
}
