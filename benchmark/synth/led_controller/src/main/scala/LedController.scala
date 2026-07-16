package withw.ledcontroller

import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage

import java.nio.file.Paths

/** Behavior changes intentionally retained from one Wit-HW source variant. */
final case class LedControllerVariant(
  name: String,
  goExpiredLight: Int = 2,
  warnPedestrianLight: Int = 1,
  stopCountLimit: Int = 4,
  warnPedestrianNextState: Int = 3
) {
  require(Seq(goExpiredLight, warnPedestrianLight).forall(value => value >= 0 && value < 8))
  require(stopCountLimit >= 0)
  require(warnPedestrianNextState >= 0 && warnPedestrianNextState < 4)
}

object LedControllerVariants {
  val all: Seq[LedControllerVariant] = Seq(
    LedControllerVariant("led_controller"),
    // Bug 1: after count reaches 6 in GO, GREEN is retained instead of YELLOW.
    LedControllerVariant("led_controller_buggy_1", goExpiredLight = 4),
    // Bug 2: the pedestrian branch in WARN drives GREEN instead of RED.
    LedControllerVariant("led_controller_buggy_2", warnPedestrianLight = 4),
    // Bug 3: STOP exits when count reaches 2 instead of when it reaches 4.
    LedControllerVariant("led_controller_buggy_3", stopCountLimit = 2),
    // Bug 4: the pedestrian branch in WARN returns to GO instead of entering STOP.
    LedControllerVariant("led_controller_buggy_4", warnPedestrianNextState = 1)
  )
}

/** Exact synthesizable translation of the Wit-HW led_controller FSM. */
final class LedController(variant: LedControllerVariant) extends Module {
  override def desiredName: String = "led_controller"

  val pedestrian_button = IO(Input(Bool()))
  val car_sensor = IO(Input(Bool()))
  val lights = IO(Output(UInt(3.W)))

  private val waitState = 0.U(2.W)
  private val goState = 1.U(2.W)
  private val warnState = 2.U(2.W)
  private val stopState = 3.U(2.W)

  private val red = 1.U(3.W)
  private val yellow = 2.U(3.W)
  private val green = 4.U(3.W)

  // The Verilog state registers use an active-high asynchronous reset. The
  // 32-bit SInt retains Verilog integer width and signed comparison semantics.
  val (state, count) = withReset(reset.asAsyncReset) {
    (RegInit(waitState), RegInit(0.S(32.W)))
  }

  val nextState = WireDefault(waitState)
  lights := yellow

  switch(state) {
    is(waitState) {
      when(car_sensor) {
        lights := red
        nextState := goState
      }.otherwise {
        lights := yellow
        nextState := warnState
      }
    }
    is(goState) {
      when(count < 6.S(32.W)) {
        lights := green
        nextState := goState
      }.otherwise {
        lights := variant.goExpiredLight.U(3.W)
        nextState := warnState
      }
    }
    is(warnState) {
      when(count < 2.S(32.W)) {
        lights := yellow
        nextState := warnState
      }.elsewhen(pedestrian_button) {
        lights := variant.warnPedestrianLight.U(3.W)
        nextState := variant.warnPedestrianNextState.U(2.W)
      }.otherwise {
        lights := red
        nextState := goState
      }
    }
    is(stopState) {
      when(count < variant.stopCountLimit.S(32.W)) {
        lights := red
        nextState := stopState
      }.otherwise {
        lights := green
        nextState := goState
      }
    }
  }

  state := nextState
  when(count === 10.S(32.W)) {
    count := 0.S(32.W)
  }.otherwise {
    count := count + 1.S(32.W)
  }
}

object EmitLedControllerVariants extends App {
  val outputRoot = Paths.get(args.headOption.getOrElse("generated")).toAbsolutePath

  LedControllerVariants.all.foreach { variant =>
    ChiselStage.emitSystemVerilogFile(
      new LedController(variant),
      args = Array("--target-dir", outputRoot.resolve(variant.name).toString),
      firtoolOpts = Array(
        "--disable-all-randomization",
        "--strip-debug-info",
        "--lowering-options=disallowLocalVariables,disallowPackedArrays"
      )
    )
  }
}
