package withw.alu

import chisel3._
import chisel3.util._
import chisel3.util.HasBlackBoxInline
import _root_.circt.stage.ChiselStage

import java.nio.file.{Files, Paths}

/** One Wit-HW ALU source variant.
  *
  * Each override represents the effective behavior of an injected source bug.
  * The overflow calculation deliberately continues to use the selected opcode
  * and the variant's actual y value, including when ADD and SUB are swapped.
  */
final case class AluVariant(
  name: String,
  swapAddSub: Boolean = false,
  defaultValue: Int = 0,
  zeroOverride: Option[Boolean] = None,
  overflowOverride: Option[Boolean] = None
) {
  require(defaultValue >= 0 && defaultValue <= 0xff)
}

object AluVariants {
  val all: Seq[AluVariant] = Seq(
    AluVariant("alu"),
    // Bug 1: opcodes 0000 and 0001 perform subtraction and addition, respectively.
    AluVariant("alu_buggy_1", swapAddSub = true),
    // Bug 2: all unlisted opcodes produce one instead of zero.
    AluVariant("alu_buggy_2", defaultValue = 1),
    // Bug 3: zero is low for every result.
    AluVariant("alu_buggy_3", zeroOverride = Some(false)),
    // Bug 4: overflow is low for every operation.
    AluVariant("alu_buggy_4", overflowOverride = Some(false)),
    // Bug 5: zero is high for every result.
    AluVariant("alu_buggy_5", zeroOverride = Some(true)),
    // Bug 6: overflow is high for every operation.
    AluVariant("alu_buggy_6", overflowOverride = Some(true))
  )
}

/** Synthesizable four-state shell for source-level case/if semantics.
  *
  * Chisel's ordinary mux/equality lowering propagates X, while the source
  * procedural case falls through on an X/Z opcode and its procedural if takes
  * the else branch on an unknown condition. Keeping this small reviewed shell
  * inline preserves those observable behaviors, including division by zero.
  */
final class AluFourStateOutputsIO extends Bundle {
  val opcode = Input(UInt(4.W))
  val a = Input(UInt(8.W))
  val b = Input(UInt(8.W))
  val rawResult = Input(UInt(8.W))
  val y = Output(UInt(8.W))
  val zero = Output(Bool())
  val overflow = Output(Bool())
}

final class AluFourStateOutputs(variant: AluVariant)
    extends BlackBox(
      Map(
        "DEFAULT_VALUE" -> variant.defaultValue,
        "ZERO_MODE" -> variant.zeroOverride.fold(0)(value => if (value) 2 else 1),
        "OVERFLOW_MODE" -> variant.overflowOverride.fold(0)(value => if (value) 2 else 1)
      )
    )
    with HasBlackBoxInline {
  val io = IO(new AluFourStateOutputsIO)

  setInline(
    "AluFourStateOutputs.sv",
    """
      |module AluFourStateOutputs #(
      |  parameter [7:0] DEFAULT_VALUE = 8'h00,
      |  parameter integer ZERO_MODE = 0,
      |  parameter integer OVERFLOW_MODE = 0
      |) (
      |  input  [3:0] opcode,
      |  input  [7:0] a,
      |  input  [7:0] b,
      |  input  [7:0] rawResult,
      |  output reg [7:0] y,
      |  output reg zero,
      |  output reg overflow
      |);
      |  always @(*) begin
      |    case (opcode)
      |      4'b0000, 4'b0001, 4'b0010, 4'b0011, 4'b0100,
      |      4'b0101, 4'b0110, 4'b0111, 4'b1000, 4'b1001:
      |        y = rawResult;
      |      default:
      |        y = DEFAULT_VALUE;
      |    endcase
      |
      |    if (ZERO_MODE == 1)
      |      zero = 1'b0;
      |    else if (ZERO_MODE == 2)
      |      zero = 1'b1;
      |    else if (y == 8'b0000_0000)
      |      zero = 1'b1;
      |    else
      |      zero = 1'b0;
      |
      |    if (OVERFLOW_MODE == 1)
      |      overflow = 1'b0;
      |    else if (OVERFLOW_MODE == 2)
      |      overflow = 1'b1;
      |    else if ((opcode == 4'b0000 && a[7] == b[7] && a[7] != y[7]) ||
      |             (opcode == 4'b0001 && a[7] != b[7] && a[7] != y[7]))
      |      overflow = 1'b1;
      |    else
      |      overflow = 1'b0;
      |  end
      |endmodule
      |""".stripMargin
  )
}

/** Combinational 8-bit ALU matching the effective two-state Wit-HW behavior. */
final class Alu(variant: AluVariant) extends Module {
  override def desiredName: String = "alu"

  val opcode = IO(Input(UInt(4.W)))
  val a = IO(Input(UInt(8.W)))
  val b = IO(Input(UInt(8.W)))
  val y = IO(Output(UInt(8.W)))
  val zero = IO(Output(Bool()))
  val overflow = IO(Output(Bool()))

  // The source exposes clk even though its always @(*) logic never consumes it.
  dontTouch(clock)

  val result = WireDefault(variant.defaultValue.U(8.W))
  switch(opcode) {
    is("b0000".U) {
      result := (if (variant.swapAddSub) a - b else a + b)
    }
    is("b0001".U) {
      result := (if (variant.swapAddSub) a + b else a - b)
    }
    is("b0010".U) { result := (a * b)(7, 0) }
    is("b0011".U) { result := a / b }
    is("b0100".U) { result := a & b }
    is("b0101".U) { result := a | b }
    is("b0110".U) { result := a ^ b }
    is("b0111".U) { result := ~a }
    is("b1000".U) { result := (a << b)(7, 0) }
    is("b1001".U) { result := a >> b }
  }

  val outputs = Module(new AluFourStateOutputs(variant))
  outputs.io.opcode := opcode
  outputs.io.a := a
  outputs.io.b := b
  outputs.io.rawResult := result
  y := outputs.io.y
  zero := outputs.io.zero
  overflow := outputs.io.overflow
}

object EmitAluVariants extends App {
  val outputRoot = Paths.get(args.headOption.getOrElse("generated")).toAbsolutePath

  AluVariants.all.foreach { variant =>
    val targetDir = outputRoot.resolve(variant.name).toString
    ChiselStage.emitSystemVerilogFile(
      new Alu(variant),
      args = Array("--target-dir", targetDir),
      firtoolOpts = Array(
        "--disable-all-randomization",
        "--strip-debug-info",
        "--lowering-options=disallowLocalVariables,disallowPackedArrays"
      )
    )

    // emitSystemVerilogFile concatenates inline black-box resources into the
    // top file, followed by a non-Verilog .f resource. Keep the reviewed inline
    // module but remove that trailing file-list payload from the deliverable.
    val topPath = outputRoot.resolve(variant.name).resolve("alu.sv")
    val emitted = Files.readString(topPath)
    val fileListMarker =
      "\n// ----- 8< ----- FILE \"firrtl_black_box_resource_files.f\" ----- 8< -----"
    val markerIndex = emitted.indexOf(fileListMarker)
    require(markerIndex >= 0, s"missing inline-resource marker in $topPath")
    Files.writeString(topPath, emitted.substring(0, markerIndex).stripTrailing + "\n")
  }
}
