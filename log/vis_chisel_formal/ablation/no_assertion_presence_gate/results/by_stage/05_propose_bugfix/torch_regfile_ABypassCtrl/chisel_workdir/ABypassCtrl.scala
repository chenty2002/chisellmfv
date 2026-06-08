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

  // Track whether the previous Phi1 cycle was stalled (for assertion stability checks)
  val stallPrevPhi1 = RegInit(false.B)

  // Control Logic
  // Qualify the clocks
  val Delta2_q2_wire = (~IStall_s2 | MemStall_s2) & Phi2
  io.Delta2_q2 := Delta2_q2_wire

  // Bypass control logic
  io.ASBypassLoad_s1e := io.ASBypBmem_s1e & BIsLoad_s1w & ~io.AKill_s1e
  io.ATBypassLoad_s1e := io.ATBypBmem_s1e & BIsLoad_s1w & ~io.AKill_s1e
  io.ASBypassData_s1e := ~(io.ASBypBmem_s1e & BIsLoad_s1w) & ~io.AKill_s1e
  io.ATBypassData_s1e := ~(io.ATBypBmem_s1e & BIsLoad_s1w) & ~io.AKill_s1e

  // Delay IStall and track previous stall status
  when(io.Phi1) {
    IStall_s2 := io.IStall_s1
    MemStall_s2 := io.MemStall_s1
    stallPrevPhi1 := io.Stall_s1
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

  // =====================================================================
  // FORMAL ASSERTIONS
  // =====================================================================

  // ---------------------------------------------------------------------
  // SAFETY 1: Load bypass and data bypass are mutually exclusive
  // ---------------------------------------------------------------------
  // The bypass control logic ensures ASBypassLoad_s1e and ASBypassData_s1e
  // are complementary when AKill_s1e is low. Verify they are never both true.
  fvAssert(!(io.ASBypassLoad_s1e && io.ASBypassData_s1e),
           "ASBypassLoad_Data_mutex")
  fvAssert(!(io.ATBypassLoad_s1e && io.ATBypassData_s1e),
           "ATBypassLoad_Data_mutex")

  // ---------------------------------------------------------------------
  // SAFETY 2: Load bypass implies the B-side instruction is tracked as a load
  // ---------------------------------------------------------------------
  fvAssert(!io.ASBypassLoad_s1e || (io.ASBypBmem_s1e && BIsLoad_s1w && !io.AKill_s1e),
           "ASBypassLoad_requires_load_tracking")
  fvAssert(!io.ATBypassLoad_s1e || (io.ATBypBmem_s1e && BIsLoad_s1w && !io.AKill_s1e),
           "ATBypassLoad_requires_load_tracking")

  // ---------------------------------------------------------------------
  // SAFETY 3: Data bypass implies the B-side instruction is NOT a load
  // ---------------------------------------------------------------------
  fvAssert(!io.ASBypassData_s1e || (!(io.ASBypBmem_s1e && BIsLoad_s1w) && !io.AKill_s1e),
           "ASBypassData_requires_no_load")
  fvAssert(!io.ATBypassData_s1e || (!(io.ATBypBmem_s1e && BIsLoad_s1w) && !io.AKill_s1e),
           "ATBypassData_requires_no_load")

  // ---------------------------------------------------------------------
  // SAFETY 4: Stall preserves pipeline register state
  // ---------------------------------------------------------------------
  // When Stall_s1 is high on Phi1, all pipeline registers should hold.
  // Use stallPrevPhi1 to only check on consecutive stalled cycles, avoiding
  // false violations when a register was legitimately updated in the preceding
  // non-stalled cycle.
  val stallCond = io.Phi1 && io.Stall_s1 && stallPrevPhi1
  assertStableWhen(stallCond, AValid_s2e.asUInt, "AValid_s2e_holds_when_stalled")
  assertStableWhen(stallCond, AValid_s1m.asUInt, "AValid_s1m_holds_when_stalled")
  assertStableWhen(stallCond, AValid_s2m.asUInt, "AValid_s2m_holds_when_stalled")
  assertStableWhen(stallCond, AValid_s1w.asUInt, "AValid_s1w_holds_when_stalled")

  assertStableWhen(stallCond, BValid_s2e.asUInt, "BValid_s2e_holds_when_stalled")
  assertStableWhen(stallCond, BValid_s1m.asUInt, "BValid_s1m_holds_when_stalled")
  assertStableWhen(stallCond, BValid_s2m.asUInt, "BValid_s2m_holds_when_stalled")

  assertStableWhen(stallCond, ABoosted_s2e.asUInt, "ABoosted_s2e_holds_when_stalled")
  assertStableWhen(stallCond, ABoostValid_s2e.asUInt, "ABoostValid_s2e_holds_when_stalled")

  assertStableWhen(stallCond, BBoosted_s2e.asUInt, "BBoosted_s2e_holds_when_stalled")
  assertStableWhen(stallCond, BBoostValid_s2e.asUInt, "BBoostValid_s2e_holds_when_stalled")

  assertStableWhen(stallCond, BIsLoad_s2e.asUInt, "BIsLoad_s2e_holds_when_stalled")
  assertStableWhen(stallCond, BIsLoad_s1m.asUInt, "BIsLoad_s1m_holds_when_stalled")
  assertStableWhen(stallCond, BIsLoad_s2m.asUInt, "BIsLoad_s2m_holds_when_stalled")
  assertStableWhen(stallCond, BIsLoad_s1w.asUInt, "BIsLoad_s1w_holds_when_stalled")

  // ---------------------------------------------------------------------
  // SAFETY 5: Kill/Except/NoDest clears the valid pipeline (A-side)
  // ---------------------------------------------------------------------
  // When AKill_s1e, ANoDest_s1e, or Except_s1w fires on an advancing cycle,
  // AValid_s2e must be false in that same cycle (the register update captures
  // the negation).
  fvAssert(!(io.Phi1 && !io.Stall_s1 && (io.AKill_s1e || io.ANoDest_s1e || io.Except_s1w))
           || !AValid_s2e,
           "AValid_s2e_cleared_by_kill_or_except")

  // ---------------------------------------------------------------------
  // SAFETY 6: Kill/Except/NoDest clears the valid pipeline (B-side)
  // ---------------------------------------------------------------------
  fvAssert(!(io.Phi1 && !io.Stall_s1 && (io.BKill_s1e || io.BNoDest_s1e || io.Except_s1w))
           || !BValid_s2e,
           "BValid_s2e_cleared_by_kill_or_except")

  // ---------------------------------------------------------------------
  // SAFETY 7: A-side pipeline propagation (s2e -> s1m on Phi2)
  // ---------------------------------------------------------------------
  // When Phi2 fires and AValid_s2e is true and not ignored, AValid_s1m
  // must be asserted in that cycle (the register update produces it).
  fvAssert(!(Phi2 && AValid_s2e && !io.AIgnore_s2e) || AValid_s1m,
           "A_valid_propagates_s2e_to_s1m")

  // ---------------------------------------------------------------------
  // SAFETY 8: B-side pipeline propagation (s2e -> s1m on Phi2)
  // ---------------------------------------------------------------------
  fvAssert(!(Phi2 && BValid_s2e && !io.BIgnore_s2e) || BValid_s1m,
           "B_valid_propagates_s2e_to_s1m")

  // ---------------------------------------------------------------------
  // SAFETY 9: A-side pipeline propagation (s1m -> s2m on Phi1 & not stalled)
  // ---------------------------------------------------------------------
  fvAssert(!(io.Phi1 && !io.Stall_s1 && AValid_s1m && !io.Except_s1w) || AValid_s2m,
           "A_valid_propagates_s1m_to_s2m")

  // ---------------------------------------------------------------------
  // SAFETY 10: A-side pipeline propagation (s2m -> s1w on Phi2)
  // ---------------------------------------------------------------------
  fvAssert(!(Phi2 && AValid_s2m) || AValid_s1w,
           "A_valid_propagates_s2m_to_s1w")

  // ---------------------------------------------------------------------
  // SAFETY 11: B-side pipeline propagation (s1m -> s2m on Phi1 & not stalled)
  // ---------------------------------------------------------------------
  fvAssert(!(io.Phi1 && !io.Stall_s1 && BValid_s1m && !io.Except_s1w) || BValid_s2m,
           "B_valid_propagates_s1m_to_s2m")

  // ---------------------------------------------------------------------
  // SAFETY 12: Alpha1/Beta1 imply their respective valid signals
  // ---------------------------------------------------------------------
  fvAssert(!io.Alpha1_s1m || AValid_s1m, "Alpha1_requires_AValid_s1m")
  fvAssert(!io.Beta1_s1m || BValid_s1m, "Beta1_requires_BValid_s1m")

  // ---------------------------------------------------------------------
  // SAFETY 13: BIsLoad pipeline consistency
  // ---------------------------------------------------------------------
  // BIsLoad_s2e should propagate to BIsLoad_s1m on Phi2
  fvAssert(!(Phi2 && BIsLoad_s2e) || BIsLoad_s1m,
           "BIsLoad_propagates_s2e_to_s1m")

  // BIsLoad_s1m should propagate to BIsLoad_s2m on Phi1 & not stalled
  fvAssert(!(io.Phi1 && !io.Stall_s1 && BIsLoad_s1m) || BIsLoad_s2m,
           "BIsLoad_propagates_s1m_to_s2m")

  // BIsLoad_s2m should propagate to BIsLoad_s1w on Phi2
  fvAssert(!(Phi2 && BIsLoad_s2m) || BIsLoad_s1w,
           "BIsLoad_propagates_s2m_to_s1w")

  // ---------------------------------------------------------------------
  // LIVENESS 14: A-side pipeline forward progress
  // ---------------------------------------------------------------------
  // When a valid instruction is in the s2e stage while the pipeline is
  // advancing (Phi1, not stalled), it must reach writeback within a bounded
  // number of cycles.  The pipeline depth s2e→s1m→s2m→s1w is ~3 cycles;
  // we allow 10 cycles margin for stalls and exceptions.
  // If AValid_s1w fires, the timer resets, indicating progress.
  assertLivenessTimer(
    AValid_s2e && !io.Stall_s1 && io.Phi1,
    AValid_s1w,
    10,
    "A_pipeline_forward_progress")

  // ---------------------------------------------------------------------
  // LIVENESS 15: B-side pipeline forward progress
  // ---------------------------------------------------------------------
  // B-side valid progresses from s2e → s1m → s2m.
  assertLivenessTimer(
    BValid_s2e && !io.Stall_s1 && io.Phi1,
    BValid_s2m,
    10,
    "B_pipeline_forward_progress")

  // ---------------------------------------------------------------------
  // LIVENESS 16: IStall/MemStall pipeline forward progress
  // ---------------------------------------------------------------------
  // The IStall condition should not persist indefinitely when the stall
  // source is no longer active.
  assertLivenessTimer(
    IStall_s2,
    !IStall_s2,
    10,
    "IStall_forward_progress")
}

object VerilogGenerator extends App {
  emitVerilog(new ABypassCtrl(), args)
}
