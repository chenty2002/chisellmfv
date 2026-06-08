package chiselFv

import chisel3.experimental.SourceInfo
import chisel3._
import chisel3.ltl.AssertProperty
import chisel3.util.Cat
import chisel3.util.PopCount
import chisel3.util.log2Ceil


trait Formal {
  this: Module => 
  
  private val resetCounter = Module(new ResetCounter)
  resetCounter.io.clk := this.clock
  resetCounter.io.reset := this.reset.asBool
  val timeSinceReset = resetCounter.io.timeSinceReset
  val notChaos = resetCounter.io.notChaos

  private val DefaultLivenessBound = 64

  private def requireNonNegative(value: Int, name: String): Unit = {
    require(value >= 0, s"$name must be non-negative")
  }

  private def requirePositive(value: Int, name: String): Unit = {
    require(value > 0, s"$name must be positive")
  }

  private def counterWidth(maxValue: Int): Int = {
    math.max(1, log2Ceil(maxValue + 2))
  }

  private def delayedBool(cond: Bool, n: Int, sticky: Boolean): Bool = {
    requirePositive(n, "n")

    val pipe = RegInit(0.U(n.W))
    val nextIn = if (sticky) {
      pipe(0) || cond
    } else {
      cond
    }
    val nextPipe = if (n == 1) {
      nextIn.asUInt
    } else {
      Cat(pipe(n - 2, 0), nextIn)
    }

    when(!notChaos) {
      pipe := 0.U
    }.otherwise {
      pipe := nextPipe
    }

    pipe(n - 1)
  }

  private def assertBoundedResponse(req: Bool, resp: Bool, n: Int, msg: String)
                                   (implicit sourceInfo: SourceInfo): Unit = {
    requireNonNegative(n, "n")

    val pending = RegInit(false.B)
    val timer = RegInit(0.U(counterWidth(n).W))
    val nextPending = notChaos && !resp && (pending || req)
    val nextTimer = Mux(pending && !resp, timer + 1.U, 0.U)

    pending := nextPending
    timer := Mux(nextPending, nextTimer, 0.U)

    fvAssert(!nextPending || nextTimer <= n.U, msg)
  }

  def fvAssert(cond: Bool, msg: String = "")
              (implicit sourceInfo: SourceInfo): Unit = {
    when(notChaos) {
      AssertProperty(cond, msg)
    }
  }

  def assertAt(n: UInt, cond: Bool, msg: String = "")
              (implicit sourceInfo: SourceInfo): Unit = {
    when(timeSinceReset === n) {
      fvAssert(cond, msg)
    }
  }

  def assertAfterNStepWhen(cond: Bool, n: Int, asert: Bool, msg: String = "")
                          (implicit sourceInfo: SourceInfo): Unit = {
    when(delayedBool(cond && notChaos, n, sticky = false)) {
      fvAssert(asert, msg)
    }
  }

  def assertNextStepWhen(cond: Bool, asert: Bool, msg: String = "")
                        (implicit sourceInfo: SourceInfo): Unit = {
    assertAfterNStepWhen(cond, 1, asert, msg)
  }

  def assertAlwaysAfterNStepWhen(cond: Bool, n: Int, asert: Bool, msg: String = "")
                                (implicit sourceInfo: SourceInfo): Unit = {
    when(delayedBool(cond && notChaos, n, sticky = true)) {
      fvAssert(asert, msg)
    }
  }

  def past[T <: Data](value: T, n: Int)(block: T => Any)
                     (implicit sourceInfo: SourceInfo): Unit = {
    requireNonNegative(n, "n")

    when(notChaos && timeSinceReset >= n.U) {
      block(Delay(value, n))
    }
  }

  def initialReg(w: Int, v: Int): InitialReg = {
    val reg = Module(new InitialReg(w, v))
    reg.io.clk := clock
    reg.io.reset := reset.asBool
    reg
  }

  def anyconst(w: Int): UInt = {
    val cst = Module(new AnyConst(w))
    cst.io.out
  }

  def astLiveness(req: Bool, resp: Bool)(implicit sourceInfo: SourceInfo): Unit = {
    astLiveness(req, resp, DefaultLivenessBound, "")
  }

  def astLiveness(req: Bool, resp: Bool, msg: String)(implicit sourceInfo: SourceInfo): Unit = {
    astLiveness(req, resp, DefaultLivenessBound, msg)
  }

  def astLiveness(req: Bool, resp: Bool, n: Int)(implicit sourceInfo: SourceInfo): Unit = {
    astLiveness(req, resp, n, "")
  }

  def astLiveness(req: Bool, resp: Bool, n: Int, msg: String)
                 (implicit sourceInfo: SourceInfo): Unit = {
    assertBoundedResponse(req, resp, n, msg)
  }

  def astRelaxedLiveness(req: Bool, resp: Bool, n: Int, msg: String = "")
                        (implicit sourceInfo: SourceInfo): Unit = {
    assertBoundedResponse(req, resp, n, msg)
  }

  def assertLivenessTimer(cond: Bool, reset: Bool, n: Int, msg: String = "")
                         (implicit sourceInfo: SourceInfo): Unit = {
    requireNonNegative(n, "n")

    val timer = RegInit(0.U(counterWidth(n).W))
    val nextTimer = Mux(!notChaos || reset, 0.U, Mux(cond, timer + 1.U, timer))

    timer := nextTimer
    fvAssert(nextTimer <= n.U, msg)
  }

  def assertMutex(conds: Seq[Bool], msg: String = "")
                 (implicit sourceInfo: SourceInfo): Unit = {
    val atMostOne = if (conds.lengthCompare(2) < 0) {
      true.B
    } else {
      PopCount(conds) <= 1.U
    }

    fvAssert(atMostOne, msg)
  }

  def assertOneHot(signal: UInt, msg: String = "")
                  (implicit sourceInfo: SourceInfo): Unit = {
    fvAssert(PopCount(signal) === 1.U, msg)
  }

  def assertOneHot0(signal: UInt, msg: String = "")
                   (implicit sourceInfo: SourceInfo): Unit = {
    fvAssert(PopCount(signal) <= 1.U, msg)
  }

  def assertStable[T <: Data](signal: T, msg: String = "")
                             (implicit sourceInfo: SourceInfo): Unit = {
    past(signal, 1) { previous =>
      fvAssert(signal === previous, msg)
    }
  }

  def assertStableWhen[T <: Data](en: Bool, signal: T, msg: String = "")
                                 (implicit sourceInfo: SourceInfo): Unit = {
    past(signal, 1) { previous =>
      fvAssert(!en || signal === previous, msg)
    }
  }

  def assertOnRise(signal: Bool, cond: Bool, msg: String = "")
                  (implicit sourceInfo: SourceInfo): Unit = {
    past(signal, 1) { previous =>
      fvAssert(!(signal && !previous) || cond, msg)
    }
  }

  def assertOnFall(signal: Bool, cond: Bool, msg: String = "")
                  (implicit sourceInfo: SourceInfo): Unit = {
    past(signal, 1) { previous =>
      fvAssert(!(!signal && previous) || cond, msg)
    }
  }

  def assertImplies(antecedent: Bool, consequent: Bool, msg: String = "")
                   (implicit sourceInfo: SourceInfo): Unit = {
    fvAssert(!antecedent || consequent, msg)
  }

  def assertImpliesDelay(antecedent: Bool, consequent: Bool, n: Int, msg: String = "")
                        (implicit sourceInfo: SourceInfo): Unit = {
    requireNonNegative(n, "n")

    if (n == 0) {
      assertImplies(antecedent, consequent, msg)
    } else {
      when(delayedBool(antecedent && notChaos, n, sticky = false)) {
        fvAssert(consequent, msg)
      }
    }
  }
}
