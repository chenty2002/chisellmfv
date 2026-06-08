package llmverify

import chisel3._
import chisel3.util._
import chiselFv._

class ABypassCtrl extends Module with Formal {
  val io = IO(new Bundle {
    // Clocks & Stalls
    val Phi1 = Input(Bool())
    val Stall_s1 = Input(Bool())
    val IStall_s1 = Input(Bool())
    val MemStall_s1 = Input(Bool())

    // Boosting information
    val ABoosted_s1e = Input(Bool())
    val BBoosted_s1e = Input(Bool())
    val ABoostValid_v1e = Input(Bool())
    val BBoostValid_v1e = Input(Bool())

    // Instruction WB cancel
    val AKill_s1e = Input(Bool())
    val AIgnore_s2e = Input(Bool())
    val BKill_s1e = Input(Bool())
    val BIgnore_s2e = Input(Bool())
    val ANoDest_s1e = Input(Bool())
    val BNoDest_s1e = Input(Bool())
    val ADestIsZero_v1e = Input(Bool())
    val BDestIsZero_v1e = Input(Bool())

    val ASBypBmem_s1e = Input(Bool())
    val ATBypBmem_s1e = Input(Bool())
    val BIsLoad_s1e = Input(Bool())

    // Branches & Exceptions
    val Commit_s1e = Input(Bool())
    val Squash_s1e = Input(Bool())
    val Except_s1w = Input(Bool())

    // Outputs
    val Alpha1_s1m = Output(Bool())
    val Beta1_s1m = Output(Bool())
    val Delta2_q2 = Output(Bool())
    val ASBypassLoad_s1e = Output(Bool())
    val ASBypassData_s1e = Output(Bool())
    val ATBypassLoad_s1e = Output(Bool())
    val ATBypassData_s1e = Output(Bool())

    val ABoosted_s2e = Output(Bool())
    val ABoosted_s2m = Output(Bool())
    val AValid_s1e = Output(Bool())
    val AValid_s2e = Output(Bool())
    val AValid_s2m = Output(Bool())
    val AValid_s1w = Output(Bool())
    val BBoosted_s2e = Output(Bool())
    val BBoosted_s2m = Output(Bool())
    val BValid_s2e = Output(Bool())
    val BValid_s2m = Output(Bool())
  })

  // Internal signals
  val Phi2 = ~io.Phi1

  // Delayed version of Stall
  val IStall_s2 = RegInit(false.B)
  val MemStall_s2 = RegInit(false.B)

  // Kill Chain registers
  val AValid_s2e = RegInit(false.B)
  val AValid_s1m = RegInit(false.B)
  val AValid_s2m = RegInit(false.B)
  val AValid_s1w = RegInit(false.B)

  val BValid_s2e = RegInit(false.B)
  val BValid_s1m = RegInit(false.B)
  val BValid_s2m = RegInit(false.B)

  val ABoosted_s2e = RegInit(false.B)
  val ABoosted_s1m = RegInit(false.B)
  val ABoosted_s2m = RegInit(false.B)

  val ABoostValid_s2e = RegInit(false.B)
  val ABoostValid_s1m = RegInit(false.B)
  val ABoostValid_s2m = RegInit(false.B)

  val BBoostValid_s2e = RegInit(false.B)
  val BBoostValid_s1m = RegInit(false.B)
  val BBoostValid_s2m = RegInit(false.B)

  val BBoosted_s2e = RegInit(false.B)
  val BBoosted_s1m = RegInit(false.B)
  val BBoosted_s2m = RegInit(false.B)

  val BIsLoad_s2e = RegInit(false.B)
  val BIsLoad_s1m = RegInit(false.B)
  val BIsLoad_s2m = RegInit(false.B)
  val BIsLoad_s1w = RegInit(false.B)

  // Control Logic
  // Qualify the clocks
  val Delta2_q2_wire = (~IStall_s2 | MemStall_s2) & Phi2
  io.Delta2_q2 := Delta2_q2_wire

  // Bypass control logic
  io.ASBypassLoad_s1e := io.ASBypBmem_s1e & BIsLoad_s1w & ~io.AKill_s1e
  io.ATBypassLoad_s1e := io.ATBypBmem_s1e & BIsLoad_s1w & ~io.AKill_s1e
  io.ASBypassData_s1e := ~(io.ASBypBmem_s1e & BIsLoad_s1w) & ~io.AKill_s1e
  io.ATBypassData_s1e := ~(io.ATBypBmem_s1e & BIsLoad_s1w) & ~io.AKill_s1e

  // Delay IStall
  when(io.Phi1) {
    IStall_s2 := io.IStall_s1
    MemStall_s2 := io.MemStall_s1
  }

  // Random Logic
  io.AValid_s1e := ~(io.AKill_s1e | io.ANoDest_s1e | io.Except_s1w)

  when(io.Phi1 & ~io.Stall_s1) {
    AValid_s2e := ~(io.AKill_s1e | io.ANoDest_s1e | io.Except_s1w)
    AValid_s2m := AValid_s1m & ~io.Except_s1w

    // Boost logic
    ABoosted_s2e := io.ABoosted_s1e ^ (io.Commit_s1e & io.ABoostValid_v1e)
    ABoostValid_s2e := io.ABoostValid_v1e & ~(io.Commit_s1e | io.Squash_s1e)
    ABoosted_s2m := ABoosted_s2e ^ (io.Commit_s1e & io.ABoostValid_v1e)
    ABoostValid_s2m := ABoostValid_s1m & ~(io.Commit_s1e | io.Squash_s1e)
  }

  when(Phi2) {
    AValid_s1m := AValid_s2e & ~io.AIgnore_s2e
    AValid_s1w := AValid_s2m
    ABoosted_s1m := ABoosted_s2e
    ABoostValid_s1m := ABoostValid_s2e
  }

  when(io.Phi1 & ~io.Stall_s1) {
    BValid_s2e := ~(io.BKill_s1e | io.BNoDest_s1e | io.Except_s1w)
    BValid_s2m := BValid_s1m & ~io.Except_s1w

    // Boost logic for B
    BBoosted_s2e := io.BBoosted_s1e ^ (io.Commit_s1e & io.BBoostValid_v1e)
    BBoostValid_s2e := io.BBoostValid_v1e & ~(io.Commit_s1e | io.Squash_s1e)
    BBoosted_s2m := BBoosted_s2e ^ (io.Commit_s1e & io.BBoostValid_v1e)
    BBoostValid_s2m := BBoostValid_s1m & ~(io.Commit_s1e | io.Squash_s1e)

    // Keep track of which B-side instrs are loads
    BIsLoad_s2e := io.BIsLoad_s1e
    BIsLoad_s2m := BIsLoad_s1m
  }

  when(Phi2) {
    BValid_s1m := BValid_s2e & ~io.BIgnore_s2e
    BBoosted_s1m := BBoosted_s2e
    BBoostValid_s1m := BBoostValid_s2e
    BIsLoad_s1m := BIsLoad_s2e
    BIsLoad_s1w := BIsLoad_s2m
  }

  // Don't toggle datapath latches if instruction was killed
  io.Alpha1_s1m := AValid_s1m & ~io.Stall_s1
  io.Beta1_s1m := BValid_s1m & ~io.Stall_s1

  // Connect outputs
  io.ABoosted_s2e := ABoosted_s2e
  io.ABoosted_s2m := ABoosted_s2m
  io.AValid_s2e := AValid_s2e
  io.AValid_s2m := AValid_s2m
  io.AValid_s1w := AValid_s1w
  io.BBoosted_s2e := BBoosted_s2e
  io.BBoosted_s2m := BBoosted_s2m
  io.BValid_s2e := BValid_s2e
  io.BValid_s2m := BValid_s2m

  // ============================================================
  // FORMAL ASSERTIONS
  // ============================================================

  // ---- SAFETY: Bypass mutual exclusion ----

  // ASBypassLoad_s1e and ASBypassData_s1e must never both be true
  fvAssert(!(io.ASBypassLoad_s1e & io.ASBypassData_s1e),
    "ASBypassLoad_and_ASBypassData_mutex")

  // ATBypassLoad_s1e and ATBypassData_s1e must never both be true
  fvAssert(!(io.ATBypassLoad_s1e & io.ATBypassData_s1e),
    "ATBypassLoad_and_ATBypassData_mutex")

  // When not killed, exactly one of ASBypassLoad/ASBypassData is true (XOR relationship)
  fvAssert(io.AKill_s1e | (io.ASBypassLoad_s1e ^ io.ASBypassData_s1e),
    "ASBypass_exactly_one_when_not_killed")

  // When not killed, exactly one of ATBypassLoad/ATBypassData is true
  fvAssert(io.AKill_s1e | (io.ATBypassLoad_s1e ^ io.ATBypassData_s1e),
    "ATBypass_exactly_one_when_not_killed")

  // ---- SAFETY: Pipeline validity definition ----

  // AValid_s1e must equal ~(AKill_s1e | ANoDest_s1e | Except_s1w)
  fvAssert(io.AValid_s1e === ~(io.AKill_s1e | io.ANoDest_s1e | io.Except_s1w),
    "AValid_s1e_definition")

  // ---- SAFETY: Pipeline register stability during stalls ----

  // When stalled on Phi1, AValid_s2e must hold its value (register does not update)
  // Use past() to defer stability check: only verify after stall has been established
  // for at least one full cycle, avoiding false failure when Stall_s1 transitions 0->1
  // simultaneously with the register update from the previous non-stalled cycle.
  past(io.Phi1 & io.Stall_s1, 1) { stallPrev =>
    assertStableWhen(stallPrev, AValid_s2e,
      "AValid_s2e_stable_during_stall")
  }

  // When stalled on Phi1, AValid_s2m must hold its value
  past(io.Phi1 & io.Stall_s1, 1) { stallPrev =>
    assertStableWhen(stallPrev, AValid_s2m,
      "AValid_s2m_stable_during_stall")
  }

  // ---- SAFETY: Pipeline stage update correctness ----

  // On Phi2, AValid_s1m must equal (AValid_s2e & ~AIgnore_s2e) from the PREVIOUS cycle.
  // Use past() because AValid_s1m is a register updated when(Phi2): the current value
  // holds the RHS sampled at the last Phi2 update (one cycle ago).
  past(AValid_s2e & ~io.AIgnore_s2e, 1) { s1mPrev =>
    assertImplies(Phi2,
      AValid_s1m === s1mPrev,
      "AValid_s1m_Phi2_update")
  }

  // On Phi2, AValid_s1w must equal AValid_s2m from the previous cycle
  past(AValid_s2m, 1) { s1wPrev =>
    assertImplies(Phi2,
      AValid_s1w === s1wPrev,
      "AValid_s1w_Phi2_update")
  }

  // On Phi1 & ~Stall, AValid_s2e must equal ~(AKill | ANoDest | Except) from the previous cycle
  past(~(io.AKill_s1e | io.ANoDest_s1e | io.Except_s1w), 1) { s2ePrev =>
    assertImplies(io.Phi1 & ~io.Stall_s1,
      AValid_s2e === s2ePrev,
      "AValid_s2e_Phi1_update")
  }

  // On Phi1 & ~Stall, AValid_s2m must equal (AValid_s1m & ~Except_s1w) from the previous cycle
  past(AValid_s1m & ~io.Except_s1w, 1) { s2mPrev =>
    assertImplies(io.Phi1 & ~io.Stall_s1,
      AValid_s2m === s2mPrev,
      "AValid_s2m_Phi1_update")
  }

  // On Phi2, BValid_s1m must equal (BValid_s2e & ~BIgnore_s2e) from the previous cycle
  past(BValid_s2e & ~io.BIgnore_s2e, 1) { s1mPrev =>
    assertImplies(Phi2,
      BValid_s1m === s1mPrev,
      "BValid_s1m_Phi2_update")
  }

  // On Phi1 & ~Stall, BValid_s2e must equal ~(BKill | BNoDest | Except) from the previous cycle
  past(~(io.BKill_s1e | io.BNoDest_s1e | io.Except_s1w), 1) { s2ePrev =>
    assertImplies(io.Phi1 & ~io.Stall_s1,
      BValid_s2e === s2ePrev,
      "BValid_s2e_Phi1_update")
  }

  // On Phi1 & ~Stall, BValid_s2m must equal (BValid_s1m & ~Except_s1w) from the previous cycle
  past(BValid_s1m & ~io.Except_s1w, 1) { s2mPrev =>
    assertImplies(io.Phi1 & ~io.Stall_s1,
      BValid_s2m === s2mPrev,
      "BValid_s2m_Phi1_update")
  }

  // ---- LIVENESS: Pipeline progress ----

  // A-side pipeline: When a valid instruction enters (AValid_s1e, not stalled),
  // it must reach AValid_s1w within 10 cycles, or be canceled (kill/except/squash).
  // Pipeline depth is 4 register stages (s1e→s2e→s1m→s2m→s1w); bound of 10 is generous.
  astRelaxedLiveness(
    io.AValid_s1e & ~io.Stall_s1,
    io.AValid_s1w | io.AKill_s1e | io.Except_s1w | io.Squash_s1e,
    10,
    "A_pipeline_progress"
  )

  // B-side pipeline: When a valid instruction enters, it must reach BValid_s2m
  // within 10 cycles, or be canceled.
  // B pipeline depth is 3 register stages; bound of 10 is generous.
  astRelaxedLiveness(
    ~(io.BKill_s1e | io.BNoDest_s1e | io.Except_s1w) & ~io.Stall_s1,
    io.BValid_s2m | io.BKill_s1e | io.Except_s1w | io.Squash_s1e,
    10,
    "B_pipeline_progress"
  )

  // ---- LIVENESS: Pipeline must not get stuck ----
  // Timer-based: if AValid_s1e is true continuously for >20 cycles without
  // AValid_s1w being asserted or a pipeline-clearing event, the pipeline is stuck.
  assertLivenessTimer(
    io.AValid_s1e,
    io.AValid_s1w | io.Except_s1w | io.Squash_s1e,
    20,
    "A_pipeline_not_stuck"
  )
}

object VerilogGenerator extends App {
  emitVerilog(new ABypassCtrl(), args)
}
