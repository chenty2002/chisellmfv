package withw.decoder

import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage

import java.nio.file.Paths

/** Effective two-state truth table of one Wit-HW decoder variant.
  *
  * The original RTL's duplicate case items and permuted concatenation targets
  * are represented by their actual output values. This deliberately retains
  * every benchmark bug rather than repairing the source-level mutation.
  */
final case class DecoderVariant(name: String, enabledOutputs: Seq[Int]) {
  require(enabledOutputs.length == 8, "one output is required for each ABC value")
  require(enabledOutputs.forall(value => value >= 0 && value <= 0xff))
}

object DecoderVariants {
  private val reference = Seq(0xfe, 0xfd, 0xfb, 0xf7, 0xef, 0xdf, 0xbf, 0x7f)

  val all: Seq[DecoderVariant] = Seq(
    DecoderVariant("decoder_3_to_8", reference),
    // Bug 1: input 1000 drives all outputs inactive.
    DecoderVariant("decoder_3_to_8_buggy_1", reference.updated(0, 0xff)),
    // Bug 2: input 1101 also drives Y0 low.
    DecoderVariant("decoder_3_to_8_buggy_2", reference.updated(5, 0xde)),
    // Bug 3: duplicated 1100 item wins; 1011 falls through to default.
    DecoderVariant(
      "decoder_3_to_8_buggy_3",
      reference.updated(3, 0xff).updated(4, 0xf7)
    ),
    // Bug 4: the first 1001 item wins; 1010 falls through to default.
    DecoderVariant("decoder_3_to_8_buggy_4", reference.updated(2, 0xff)),
    // Bug 5: Y0/Y1 are swapped only for input 1001.
    DecoderVariant("decoder_3_to_8_buggy_5", reference.updated(1, 0xfe)),
    // Bug 6: Y5/Y6 are swapped only for input 1110.
    DecoderVariant("decoder_3_to_8_buggy_6", reference.updated(6, 0xdf))
  )
}

/** Active-high-enabled, active-low-output 3-to-8 decoder.
  *
  * This is intentionally a normal Module: Chisel supplies its implicit clock.
  * The decoder logic is combinational and therefore does not consume it.
  */
final class Decoder3to8(variant: DecoderVariant) extends Module {
  override def desiredName: String = "decoder_3to8"

  val A = IO(Input(Bool()))
  val B = IO(Input(Bool()))
  val C = IO(Input(Bool()))
  val en = IO(Input(Bool()))

  val Y7 = IO(Output(Bool()))
  val Y6 = IO(Output(Bool()))
  val Y5 = IO(Output(Bool()))
  val Y4 = IO(Output(Bool()))
  val Y3 = IO(Output(Bool()))
  val Y2 = IO(Output(Bool()))
  val Y1 = IO(Output(Bool()))
  val Y0 = IO(Output(Bool()))

  // Retain the implicit clock port even though the translated design is purely
  // combinational, matching the unused clock input in the Wit-HW source.
  dontTouch(clock)

  val selector = Cat(en, A, B, C)
  val decoded = MuxLookup(selector, "hff".U(8.W))(
    variant.enabledOutputs.zipWithIndex.map { case (value, abc) =>
      (8 + abc).U(4.W) -> value.U(8.W)
    }
  )

  Y7 := decoded(7)
  Y6 := decoded(6)
  Y5 := decoded(5)
  Y4 := decoded(4)
  Y3 := decoded(3)
  Y2 := decoded(2)
  Y1 := decoded(1)
  Y0 := decoded(0)
}

object EmitDecoderVariants extends App {
  val outputRoot = Paths.get(args.headOption.getOrElse("generated")).toAbsolutePath

  DecoderVariants.all.foreach { variant =>
    val targetDir = outputRoot.resolve(variant.name).toString
    ChiselStage.emitSystemVerilogFile(
      new Decoder3to8(variant),
      args = Array("--target-dir", targetDir),
      firtoolOpts = Array(
        "--disable-all-randomization",
        "--strip-debug-info",
        "--lowering-options=disallowLocalVariables,disallowPackedArrays"
      )
    )
  }
}
