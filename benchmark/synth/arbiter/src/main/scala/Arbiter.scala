package withw.arbiter

import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage

import java.nio.file.Paths

/** One Wit-HW arbiter source variant.
  *
  * The flags describe the effective mutations in the corresponding Verilog.
  * In particular, bug 2 copies the already-updated coda2 value into coda1
  * because the source uses ordered blocking assignments inside a clocked block.
  */
final case class ArbiterVariant(
  name: String,
  request3Tag: Int = 1,
  request3CopiesUpdatedCoda2: Boolean = false,
  request1UsesPreviousHigh: Boolean = false
) {
  require(request3Tag >= 0 && request3Tag <= 7)
}

object ArbiterVariants {
  val all: Seq[ArbiterVariant] = Seq(
    ArbiterVariant("arbiter"),
    // Bug 1: a new request 3 is enqueued with request 2's tag.
    ArbiterVariant("arbiter_buggy_1", request3Tag = 2),
    // Bug 2: request 3 duplicates coda1 into both coda2 and coda1.
    ArbiterVariant("arbiter_buggy_2", request3CopiesUpdatedCoda2 = true),
    // Bug 3: request 1 is enqueued when it was already high, not on its rising edge.
    ArbiterVariant("arbiter_buggy_3", request1UsesPreviousHigh = true)
  )
}

/** Stateful four-request arbiter preserving the source's clock-edge ordering. */
final class Arbiter(variant: ArbiterVariant) extends Module {
  override def desiredName: String = "arbiter"

  val REQUEST1 = IO(Input(Bool()))
  val REQUEST2 = IO(Input(Bool()))
  val REQUEST3 = IO(Input(Bool()))
  val REQUEST4 = IO(Input(Bool()))
  val GRANT_O = IO(Output(UInt(4.W)))

  private val U1 = "b100".U(3.W)
  private val U2 = "b010".U(3.W)
  private val U3 = "b001".U(3.W)
  private val U4 = "b111".U(3.W)

  private val Init = 0.U(2.W)
  private val AnalyzeRequests = 1.U(2.W)
  private val Assign = 2.U(2.W)

  val state = RegInit(Init)
  val queue = RegInit(VecInit(Seq.fill(4)(0.U(3.W))))
  val rememberedRequests = RegInit(VecInit(Seq.fill(4)(false.B)))
  val previousRequests = RegInit(VecInit(Seq.fill(4)(false.B)))
  val grant = RegInit(0.U(4.W))
  val grantOutput = RegInit(0.U(4.W))

  GRANT_O := grantOutput

  val inputs = VecInit(REQUEST1, REQUEST2, REQUEST3, REQUEST4)

  // Four explicit queue stages model the source's blocking assignments. More
  // than one request may therefore be pushed, in request-number order, during
  // one AnalyzeRequests edge.
  val afterRequest1 = WireDefault(queue)
  val pushRequest1 = rememberedRequests(0) &&
    (if (variant.request1UsesPreviousHigh) previousRequests(0) else !previousRequests(0))
  when(pushRequest1) {
    afterRequest1(3) := queue(2)
    afterRequest1(2) := queue(1)
    afterRequest1(1) := queue(0)
    afterRequest1(0) := U1
  }

  val afterRequest2 = WireDefault(afterRequest1)
  when(rememberedRequests(1) && !previousRequests(1)) {
    afterRequest2(3) := afterRequest1(2)
    afterRequest2(2) := afterRequest1(1)
    afterRequest2(1) := afterRequest1(0)
    afterRequest2(0) := U2
  }

  val afterRequest3 = WireDefault(afterRequest2)
  when(rememberedRequests(2) && !previousRequests(2)) {
    afterRequest3(3) := afterRequest2(2)
    afterRequest3(2) := afterRequest2(1)
    // In buggy_2, coda2 has already received old coda1 before coda1=coda2.
    afterRequest3(1) := (if (variant.request3CopiesUpdatedCoda2) afterRequest2(1) else afterRequest2(0))
    afterRequest3(0) := variant.request3Tag.U(3.W)
  }

  val afterRequest4 = WireDefault(afterRequest3)
  when(rememberedRequests(3) && !previousRequests(3)) {
    afterRequest4(3) := afterRequest3(2)
    afterRequest4(2) := afterRequest3(1)
    afterRequest4(1) := afterRequest3(0)
    afterRequest4(0) := U4
  }

  switch(state) {
    is(Init) {
      rememberedRequests := inputs
      state := AnalyzeRequests
    }

    is(AnalyzeRequests) {
      grantOutput := grant
      queue := afterRequest4
      previousRequests := rememberedRequests
      state := Assign
    }

    is(Assign) {
      when(previousRequests.asUInt.orR) {
        grant := MuxLookup(queue(0), 0.U(4.W))(
          Seq(
            U1 -> "b1000".U(4.W),
            U2 -> "b0100".U(4.W),
            U3 -> "b0010".U(4.W),
            U4 -> "b0001".U(4.W)
          )
        )
        queue(0) := queue(1)
        queue(1) := queue(2)
        queue(2) := queue(3)
        queue(3) := 0.U
      }
      rememberedRequests := inputs
      state := AnalyzeRequests
    }
  }
}

object EmitArbiterVariants extends App {
  val outputRoot = Paths.get(args.headOption.getOrElse("generated")).toAbsolutePath

  ArbiterVariants.all.foreach { variant =>
    val targetDir = outputRoot.resolve(variant.name).toString
    ChiselStage.emitSystemVerilogFile(
      new Arbiter(variant),
      args = Array("--target-dir", targetDir),
      firtoolOpts = Array(
        "--disable-all-randomization",
        "--strip-debug-info",
        "--lowering-options=disallowLocalVariables,disallowPackedArrays"
      )
    )
  }
}
