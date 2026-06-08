package llmverify

import chisel3._
import chisel3.util._
import chisel3.ltl._
import chisel3.ltl.Sequence._

class spinner32 extends Module {
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

  // ============================================================
  // Formal Verification Assertions
  // ============================================================

  // A1: Barrel shifter must produce correct rotate-right result.
  // ROR(x, n) = (x >> n) | (x << (WIDTH - n)).
  //
  // This assertion catches a critical chaining bug: stages 2-5 all
  // operate on tmp0 directly instead of the previous stage's result.
  // For example, with amount=5 (binary 00101), stage1 produces
  // ROR(tmp0,1) in tmp1, but stage3 reads tmp0 and produces ROR(tmp0,4)
  // in tmp3, OVERRIDING the stage1 result. The correct behavior is:
  // stage2 should chain from tmp1, stage3 from tmp2, etc.
  // The expected formula below computes the correct ROR and asserts
  // that the actual barrel shifter output tmp5 matches.
  val expectedRor = (tmp0 >> io.amount) | (tmp0 << (32.U - io.amount))
  AssertProperty(tmp5 === expectedRor, "barrel_shifter_correct_ror")

  // A2: Data-path pipeline integrity.
  // doutReg is directly assigned from tmp5 at each clock edge.
  // Assert that the shifter output is always available at doutReg
  // after one cycle (registered), so dout always reflects the most
  // recent rotation of inrReg by io.amount.
  AssertProperty(doutReg === tmp5, "dout_equals_shifter_output")
  // Note: This holds after the first clock after reset because
  // doutReg is RegInit(0) and tmp5 could be non-zero immediately.
  // The assertion is checked after reset deassertion in formal tools.

  // A3: Bounded liveness - when io.spin is asserted (spin mode) and
  // io.amount selects a non-zero rotation, the barrel shifter output
  // should differ from its input (tmp0) because rotating a value by
  // a non-zero amount changes it (unless the value is all-zeros).
  // We check: (io.spin && amount != 0 && tmp0 != 0) implies tmp5 != tmp0.
  val amountNonZero = io.amount.orR
  val tmpNonZero = tmp0.orR
  AssertProperty(
    !(io.spin && amountNonZero && tmpNonZero) || (tmp5 =/= tmp0),
    "spin_nonzero_rotation_changes_value"
  )
}

object VerilogGenerator extends App {
  emitVerilog(new spinner32(), args)
}
