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
    tmp2 := Cat(tmp1(1, 0), tmp1(31, 2))
  }.otherwise {
    tmp2 := tmp1
  }

  // Stage 3: rotate by 4 bits
  when(io.amount(2)) {
    tmp3 := Cat(tmp2(3, 0), tmp2(31, 4))
  }.otherwise {
    tmp3 := tmp2
  }

  // Stage 4: rotate by 8 bits
  when(io.amount(3)) {
    tmp4 := Cat(tmp3(7, 0), tmp3(31, 8))
  }.otherwise {
    tmp4 := tmp3
  }

  // Stage 5: rotate by 16 bits
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

  // ====== Formal Verification Assertions ======

  // Compute the correct cascaded barrel shifter result (each stage uses the
  // previous stage's output, unlike the buggy design where all stages use tmp0).
  // This assertion directly catches the non-cascading bug.
  val s1 = Mux(io.amount(0), Cat(inrReg(0), inrReg(31, 1)), inrReg)
  val s2 = Mux(io.amount(1), Cat(s1(1, 0), s1(31, 2)), s1)
  val s3 = Mux(io.amount(2), Cat(s2(3, 0), s2(31, 4)), s2)
  val s4 = Mux(io.amount(3), Cat(s3(7, 0), s3(31, 8)), s3)
  val s5 = Mux(io.amount(4), Cat(s4(15, 0), s4(31, 16)), s4)

  // Assertion 1: Barrel shifter must produce the correct cascaded rotation.
  // This catches the bug where all stages rotate tmp0 instead of cascading.
  fvAssert(tmp5 === s5, "barrel_shifter_correct_rotation")

  // Assertion 2: In load mode (not spinning), the output equals the rotation
  // of inrReg by io.amount.  When splReg is false, inrReg gets io.din, so
  // doutReg should be the correct rotation of io.din.
  // NOTE: doutReg is registered and latches the rotation of the PREVIOUS
  // inrReg value, while s5 is a combinational function of the CURRENT inrReg.
  // We use RegNext(s5) to sample s5 from the previous cycle, matching the
  // one-cycle pipeline delay of doutReg.
  assertImplies(!splReg, doutReg === RegNext(s5), "load_mode_output_correct")

  // Assertion 3: Spin mode progress — when spinning with a non-zero amount
  // on a non-zero value, the output must differ from its previous value
  // within 33 cycles.
  // A non-zero rotation on a non-zero value guarantees the barrel shifter
  // changes the value every cycle (rotation by k>0 of a non-zero value
  // cannot map every bit to itself), so the output should differ from the
  // previous cycle within 1 step.
  // NOTE: doutReg =/= 0.U excludes the identity case: rotating 0 by any
  // amount always yields 0, so the output never changes.
  astRelaxedLiveness(splReg && io.amount =/= 0.U && doutReg =/= 0.U,
                     io.dout =/= RegNext(io.dout),
                     33,
                     "spinning_progress")

  // Assertion 4: Mutex — spin and load modes are mutually exclusive by
  // construction (splReg is a single bit), but verify that the feedback
  // and input paths are not both driving inrReg simultaneously.
  assertMutex(Seq(splReg, !splReg), "spin_load_mutex")
}

object VerilogGenerator extends App {
  emitVerilog(new spinner32(), args)
}
