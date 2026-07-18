package withw.counter

import _root_.circt.stage.ChiselStage

import java.nio.file.Paths

/** Evaluation/build registry. This file is excluded from the authoring model view. */
object CounterVariants {
  val all: Seq[CounterVariant] = Seq(
    CounterVariant("first_counter_overflow", increment = 1, overflowAtMax = true, resetCounter = true),
    CounterVariant("first_counter_overflow_buggy_1", increment = 2, overflowAtMax = true, resetCounter = true),
    CounterVariant("first_counter_overflow_buggy_2", increment = 1, overflowAtMax = false, resetCounter = true),
    CounterVariant("first_counter_overflow_buggy_3", increment = 1, overflowAtMax = true, resetCounter = false)
  )
}

object EmitCounterVariants extends App {
  val outputRoot = Paths.get(args.headOption.getOrElse("generated")).toAbsolutePath

  CounterVariants.all.foreach { variant =>
    val targetDir = outputRoot.resolve(variant.sourceStem).toString
    ChiselStage.emitSystemVerilogFile(
      new FirstCounter(variant),
      args = Array("--target-dir", targetDir),
      firtoolOpts = Array(
        "--disable-all-randomization",
        "--strip-debug-info",
        "--lowering-options=disallowLocalVariables,disallowPackedArrays"
      )
    )
  }
}
