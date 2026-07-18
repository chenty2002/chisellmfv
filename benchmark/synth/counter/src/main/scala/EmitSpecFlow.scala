package withw.counter

import _root_.circt.stage.ChiselStage

import java.nio.file.Paths

/** Verification-only emitter. Source locations are intentionally retained. */
object EmitSpecFlow extends App {
  require(args.nonEmpty, "output directory is required")
  val parameters = args.drop(1).map { argument =>
    val parts = argument.split("=", 2)
    require(parts.length == 2, s"invalid parameter: $argument")
    parts(0) -> parts(1)
  }.toMap
  require(parameters.keySet == Set("increment", "overflowAtMax", "resetCounter"))

  val variant = CounterVariant(
    sourceStem = "opaque",
    increment = parameters("increment").toInt,
    overflowAtMax = parameters("overflowAtMax").toBoolean,
    resetCounter = parameters("resetCounter").toBoolean
  )
  val targetDir = Paths.get(args(0)).resolve("rtl").toAbsolutePath.toString
  ChiselStage.emitSystemVerilogFile(
    new FirstCounter(variant),
    args = Array("--target-dir", targetDir),
    firtoolOpts = Array(
      "--disable-all-randomization",
      "--lowering-options=disallowLocalVariables,disallowPackedArrays"
    )
  )
}
