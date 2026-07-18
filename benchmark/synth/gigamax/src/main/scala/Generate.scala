package withw.gigamax

import _root_.circt.stage.ChiselStage

import java.nio.file.Paths

object EmitGigamaxVariants extends App {
  private val outputRoot = Paths.get(args.headOption.getOrElse("generated")).toAbsolutePath

  GigamaxGenerators.all.foreach { case (sourceStem, generator) =>
    ChiselStage.emitSystemVerilogFile(
      generator(),
      args = Array("--target-dir", outputRoot.resolve(sourceStem).toString),
      firtoolOpts = Array(
        "--disable-all-randomization",
        "--strip-debug-info",
        "--lowering-options=disallowLocalVariables,disallowPackedArrays"
      )
    )
  }
}
