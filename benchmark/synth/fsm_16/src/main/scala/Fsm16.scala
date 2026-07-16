package withw.fsm

import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage

import java.nio.file.Paths

/** Effective two-state transition table for one Wit-HW fsm_16 variant.
  *
  * Each row is indexed by the current state. Within a row, entries correspond
  * to input1/input2 values 00, 01, 10, and 11. The buggy variants deliberately
  * retain their injected transition defects.
  */
final case class Fsm16Variant(name: String, transitions: Seq[Seq[Int]]) {
  require(transitions.length == 16, "one row is required for each state")
  require(transitions.forall(_.length == 4), "one target is required for each input pair")
  require(transitions.flatten.forall(target => target >= 0 && target < 16))
}

object Fsm16Variants {
  private val reference = Seq(
    Seq(2, 2, 2, 1),
    Seq(4, 3, 4, 4),
    Seq(6, 6, 5, 6),
    Seq(7, 8, 8, 8),
    Seq(10, 9, 9, 9),
    Seq(11, 11, 12, 11),
    Seq(13, 14, 13, 13),
    Seq(15, 15, 15, 0),
    Seq(2, 2, 2, 1),
    Seq(4, 3, 4, 4),
    Seq(6, 6, 5, 6),
    Seq(7, 8, 8, 8),
    Seq(10, 9, 9, 9),
    Seq(11, 11, 12, 11),
    Seq(13, 14, 13, 13),
    Seq(15, 15, 15, 0)
  )

  val all: Seq[Fsm16Variant] = Seq(
    Fsm16Variant("fsm_16", reference),
    // Bug 1: S7 with input1/input2=11 transitions to S1 instead of S0.
    Fsm16Variant("fsm_16_buggy_1", reference.updated(7, Seq(15, 15, 15, 1))),
    // Bug 2: S11 with input1/input2=00 transitions to S6 instead of S7.
    Fsm16Variant("fsm_16_buggy_2", reference.updated(11, Seq(6, 8, 8, 8))),
    // Bug 3: S9 selects S3 only for input1/input2=11.
    Fsm16Variant("fsm_16_buggy_3", reference.updated(9, Seq(4, 4, 4, 3))),
    // Bug 4: the S2 and S3 transition bodies are exchanged.
    Fsm16Variant(
      "fsm_16_buggy_4",
      reference.updated(2, reference(3)).updated(3, reference(2))
    )
  )
}

/** Wit-HW 16-state synchronous finite-state machine.
  *
  * The source uses a positive-edge state register and synchronous active-high
  * reset. Chisel's implicit clock and reset ports map directly to those ports.
  */
final class Fsm16(variant: Fsm16Variant) extends Module {
  override def desiredName: String = "fsm_16"

  val input1 = IO(Input(Bool()))
  val input2 = IO(Input(Bool()))
  val state = IO(Output(UInt(4.W)))

  val stateReg = RegInit(0.U(4.W))
  val transitionTable = VecInit(
    variant.transitions.flatten.map(target => target.U(4.W))
  )

  stateReg := transitionTable(Cat(stateReg, input1, input2))
  state := stateReg
}

object EmitFsm16Variants extends App {
  val outputRoot = Paths.get(args.headOption.getOrElse("generated")).toAbsolutePath

  Fsm16Variants.all.foreach { variant =>
    val targetDir = outputRoot.resolve(variant.name).toString
    ChiselStage.emitSystemVerilogFile(
      new Fsm16(variant),
      args = Array("--target-dir", targetDir),
      firtoolOpts = Array(
        "--disable-all-randomization",
        "--strip-debug-info",
        "--lowering-options=disallowLocalVariables,disallowPackedArrays"
      )
    )
  }
}
