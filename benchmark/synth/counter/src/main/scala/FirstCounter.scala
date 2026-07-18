package withw.counter

import chisel3._

/** Elaboration parameters for the public four-bit counter generator. */
final case class CounterVariant(
    sourceStem: String,
    increment: Int,
    overflowAtMax: Boolean,
    resetCounter: Boolean
) {
  require(increment >= 0 && increment <= 15)
}

/** Four-bit synchronous counter, including its source-level priority.
  *
  * Plain Reg values are intentional. The source has no declaration-time
  * initialization, and a configuration may omit the counter reset. The
  * separate final when reads the old counter value, so its overflow assignment
  * has priority over the reset assignment in the same clock event.
  */
final class FirstCounter(variant: CounterVariant) extends Module {
  override def desiredName: String = "first_counter"

  val enable = IO(Input(Bool()))
  val counter_out = IO(Output(UInt(4.W)))
  val overflow_out = IO(Output(Bool()))

  val counter = Reg(UInt(4.W))
  val overflow = Reg(Bool())

  // Keep the actual state element so firtool cannot replace observable
  // pre-reset state with a constant.
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
