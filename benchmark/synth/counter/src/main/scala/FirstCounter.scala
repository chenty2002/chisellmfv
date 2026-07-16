package withw.counter

import chisel3._
import _root_.circt.stage.ChiselStage

import java.nio.file.Paths

/** Exact mutation parameters for one Wit-HW counter source file. */
final case class CounterVariant(
    sourceStem: String,
    increment: Int,
    overflowAtMax: Boolean,
    resetCounter: Boolean
) {
  require(increment >= 0 && increment <= 15)
}

object CounterVariants {
  val all: Seq[CounterVariant] = Seq(
    CounterVariant("first_counter_overflow", increment = 1, overflowAtMax = true, resetCounter = true),
    // Bug 1: each enabled clock advances the four-bit counter by two.
    CounterVariant("first_counter_overflow_buggy_1", increment = 2, overflowAtMax = true, resetCounter = true),
    // Bug 2: observing the old counter value 15 writes overflow_out low.
    CounterVariant("first_counter_overflow_buggy_2", increment = 1, overflowAtMax = false, resetCounter = true),
    // Bug 3: reset deliberately leaves counter_out unchanged/uninitialized.
    CounterVariant("first_counter_overflow_buggy_3", increment = 1, overflowAtMax = true, resetCounter = false)
  )
}

/** Wit-HW's four-bit synchronous counter, including its source-level priority.
  *
  * Plain Reg values are intentional. The source has no declaration-time
  * initialization, and buggy_3 never resets counter_out. The separate final
  * when reproduces the second Verilog `if`: it reads the old counter value and
  * its overflow assignment has priority over the reset assignment in the same
  * clock event.
  */
final class FirstCounter(variant: CounterVariant) extends Module {
  override def desiredName: String = "first_counter"

  val enable = IO(Input(Bool()))
  val counter_out = IO(Output(UInt(4.W)))
  val overflow_out = IO(Output(Bool()))

  val counter = Reg(UInt(4.W))
  val overflow = Reg(Bool())

  // Buggy_2 can only write zero but still holds an unknown power-on value
  // before its first reset/qualifying write. Keep the actual state element so
  // firtool cannot replace that observable pre-reset behavior with constant 0.
  dontTouch(overflow)

  when(reset.asBool) {
    if (variant.resetCounter) {
      counter := 0.U
    }
    overflow := false.B
  }.elsewhen(enable) {
    counter := counter +% variant.increment.U
  }

  when(counter === 15.U) {
    overflow := variant.overflowAtMax.B
  }

  counter_out := counter
  overflow_out := overflow
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
