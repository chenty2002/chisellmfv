package withw.sha3

import _root_.circt.stage.ChiselStage
import java.nio.file.Paths

/** Verification-only emitter for one opaque generator configuration. */
object EmitSpecFlow extends App {
  require(args.nonEmpty, "output directory is required")
  val parameters = args.drop(1).map { argument =>
    val parts = argument.split("=", 2)
    require(parts.length == 2, s"invalid parameter: $argument")
    parts(0) -> parts(1)
  }.toMap
  require(parameters.keySet == Set("variantIndex"))
  val variantIndex = parameters("variantIndex").toInt
  require(variantIndex >= 0 && variantIndex < Sha3Variants.all.length)
  val targetDir = Paths.get(args(0)).resolve("rtl").toAbsolutePath.toString
  ChiselStage.emitSystemVerilogFile(
    new Keccak(Sha3Variants.all(variantIndex)),
    args = Array("--target-dir", targetDir),
    firtoolOpts = Array(
      "--disable-all-randomization",
      "--lowering-options=disallowLocalVariables,disallowPackedArrays"
    )
  )
}
