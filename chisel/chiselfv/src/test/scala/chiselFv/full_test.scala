package chiselFv

import chisel3._
import chisel3.util.Cat
import circt.stage.ChiselStage
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.regex.Pattern

class FullTest extends AnyFlatSpec with Matchers {
  behavior of "Formal"

  it should "emit and verify every public assertion helper" in {
    val sv = ChiselStage.emitSystemVerilog(new FullTestDut)
    val out = Path.of("verilog", "FullTestDut.sv")
    Files.createDirectories(out.getParent)
    Files.writeString(out, sv, StandardCharsets.UTF_8)

    sv should include("module FullTestDut")
    assertionLabels.foreach { label =>
      sv should include(s"$label:")
    }
    sv should include("disable iff (~hasBeenReset)")
    sv should include("< 7'h41")
    sv should include("< 3'h5")
    countOccurrences(sv, "assert property") shouldBe assertionLabels.size
    countOccurrences(sv, "assert(") shouldBe 0
  }

  private val assertionLabels = Seq(
    "fvAssert",
    "assertAt",
    "assertAfterNStepWhen",
    "assertNextStepWhen",
    "assertAlwaysAfterNStepWhen",
    "past",
    "astLivenessDefault",
    "astLivenessBounded",
    "astRelaxedLiveness",
    "assertLivenessTimer",
    "assertMutex",
    "assertOneHot",
    "assertOneHot0",
    "assertStable",
    "assertStableWhen",
    "assertOnRise",
    "assertOnFall",
    "assertImplies",
    "assertImpliesDelay"
  )

  private def countOccurrences(text: String, needle: String): Int = {
    Pattern.compile(Pattern.quote(needle)).matcher(text).results().count().toInt
  }
}

private class FullTestDut extends Module with Formal {
  val io = IO(new Bundle {
    val req = Input(Bool())
    val resp = Input(Bool())
    val data = Input(UInt(4.W))
    val out = Output(UInt(4.W))
  })

  val symbolic = anyconst(4)
  val init = initialReg(4, 3)
  val prevData = RegNext(io.data, 0.U)
  val validData = io.data =/= 15.U

  init.io.in := io.data
  io.out := init.io.out ^ symbolic

  fvAssert(validData, "fvAssert")
  assertAt(1.U, validData, "assertAt")
  assertAfterNStepWhen(io.req, 2, validData, "assertAfterNStepWhen")
  assertNextStepWhen(io.req, validData, "assertNextStepWhen")
  assertAlwaysAfterNStepWhen(io.req, 2, validData, "assertAlwaysAfterNStepWhen")

  past(io.data, 1) { pastData =>
    fvAssert(pastData === prevData, "past")
  }

  astLiveness(io.req, io.resp, "astLivenessDefault")
  astLiveness(io.req, io.resp, 4, "astLivenessBounded")
  astRelaxedLiveness(io.req, io.resp, 4, "astRelaxedLiveness")
  assertLivenessTimer(io.req, io.resp, 4, "assertLivenessTimer")

  assertMutex(Seq(io.req, io.resp, validData), "assertMutex")
  assertOneHot(Cat(io.req, io.resp), "assertOneHot")
  assertOneHot0(Cat(io.req, io.resp), "assertOneHot0")
  assertStable(io.data, "assertStable")
  assertStableWhen(io.req, io.data, "assertStableWhen")
  assertOnRise(io.req, io.resp, "assertOnRise")
  assertOnFall(io.req, !io.resp, "assertOnFall")
  assertImplies(io.req, io.resp, "assertImplies")
  assertImpliesDelay(io.req, io.resp, 2, "assertImpliesDelay")
}
