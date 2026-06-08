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

  // P1: Barrel shifter must produce the correct rotate-right of inrReg by io.amount.
  // The correct rotate-right of a 32-bit value by N is: (value >> N) | (value << (32-N)).
  // This assertion catches the cascading bug where stages read from tmp0 instead of
  // the previous stage's output, causing only the most-significant set bit to take effect.
  val shiftLeftAmt = Mux(io.amount === 0.U, 0.U, 32.U - io.amount)
  val expectedRot = (inrReg >> io.amount) | (inrReg << shiftLeftAmt)
  fvAssert(tmp5 === expectedRot, "barrel_shifter_correctness")

  // P2: Rotation by 0 is identity — when amount is 0, the final stage output must
  // equal the input value (inrReg). This is a special case of P1 but made explicit
  // as a basic sanity check that also exercises the pass-through paths of every stage.
  fvAssert(!(io.amount === 0.U) || tmp5 === inrReg, "rotate_by_0_identity")

  // P3: When not in spin mode (splReg is false, meaning io.spin was false last cycle),
  // inrReg is loaded from io.din and doutReg will reflect the rotation of io.din
  // on the next cycle. This assertion checks that when spin is deasserted and amount is 0,
  // the data flows through unchanged after one pipeline cycle.
  // (splReg was set to io.spin last cycle, so splReg=false means we just finished a
  //  non-spinning cycle, and doutReg holds the rotated version of whatever was in inrReg.)
  // For the 2-cycle load-and-rotate path: when spin goes low and stays low, after 2 cycles
  // the output doutReg should equal rotate(din, amount) from 2 cycles ago.
  // We use a simpler cross-check: when not spinning (splReg=false) and amount=0,
  // the doutReg should eventually equal io.din after 2 cycles.
  assertAfterNStepWhen(!io.spin && io.amount === 0.U, 2, io.dout === io.din, "load_then_rotate_by_0")

  // P4: When not spinning and the data is loaded, the output must be a deterministic
  // function of the input and amount.  Specifically, when splReg is false, inrReg gets
  // din, and one cycle later doutReg gets rotate(inrReg, amount).  So we can assert
  // that after the load, doutReg === rotate(io.din, io.amount).
  // Use a delayed-implication check: when spin goes low (load phase), 2 cycles later
  // the output must be the rotation of the din sampled at the load time.
  // For simplicity, assert that if amount is constant, the pipeline behaves correctly:
  // when splReg is false (not spinning, just loaded din into inrReg), then on the next
  // clock edge doutReg becomes rotate(inrReg, amount). Since inrReg's new value is io.din,
  // after the clock edge doutReg should equal rotate(io.din, io.amount).
  // We assert this for all amount values by checking the combinational relation:
  // doutReg (current) should be the rotation of the inrReg from last cycle.
  // Since we can't refer to "last cycle's inrReg" directly in a combinational assertion,
  // we instead check the liveness that spin mode makes progress.
  // 

  // P5: Progress in spin mode — when spin is asserted and amount is non-zero, the
  // output doutReg should eventually stop being 0 (assuming we started from non-zero
  // initial state or loaded non-zero data). This catches a stuck-at-zero condition.
  // Use a relaxed liveness check: doutReg should eventually become non-zero after spin
  // is asserted, unless the system is reset or amount is 0.
  // astRelaxedLiveness request: spin is active and amount != 0 and the pipeline is loaded
  // astRelaxedLiveness response: doutReg != 0 (indicating rotation happened) OR we were reset
  // Bound: 10 cycles should be sufficient for the data to propagate through the feedback loop
  astRelaxedLiveness(
    io.spin && io.amount =/= 0.U && inrReg =/= 0.U,
    io.dout =/= 0.U,
    10,
    "spin_progress_nonzero_data"
  )
}

object VerilogGenerator extends App {
  emitVerilog(new spinner32(), args)
}
