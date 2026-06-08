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

  // ============ FORMAL VERIFICATION ASSERTIONS ============

  // Safety 1: ASBypassLoad_s1e and ASBypassData_s1e are mutually exclusive
  // (they drive the same physical bypass mux for the A-side store port)
  fvAssert(!(io.ASBypassLoad_s1e && io.ASBypassData_s1e), "ASBypass_load_data_exclusive")

  // Safety 2: ATBypassLoad_s1e and ATBypassData_s1e are mutually exclusive
  // (same bypass mux for the A-side load-data port)
  fvAssert(!(io.ATBypassLoad_s1e && io.ATBypassData_s1e), "ATBypass_load_data_exclusive")

  // Safety 3: AKill_s1e correctly forces AValid_s1e low in the same cycle
  // (the kill overrides all other conditions on the s1e valid signal)
  fvAssert(!io.AKill_s1e || !io.AValid_s1e, "AKill_clears_AValid_s1e")

  // Safety 4: Pipeline tracking — AValid_s2e must shadow the sampled s1e valid value
  // Sampled register captures io.AValid_s1e exactly when the pipeline updates s2e
  val a_valid_s1e_sampled = RegInit(false.B)
  when(io.Phi1 & ~io.Stall_s1) {
    a_valid_s1e_sampled := io.AValid_s1e
  }
  fvAssert(a_valid_s1e_sampled === AValid_s2e, "AValid_s2e_tracks_AValid_s1e")

  // Safety 5: Pipeline tracking for B-side — BValid_s2e shadows the combinational
  // B-side s1e valid (which is computed but not exposed as a top-level output)
  val b_valid_s1e_sampled = RegInit(false.B)
  when(io.Phi1 & ~io.Stall_s1) {
    b_valid_s1e_sampled := ~(io.BKill_s1e | io.BNoDest_s1e | io.Except_s1w)
  }
  fvAssert(b_valid_s1e_sampled === BValid_s2e, "BValid_s2e_tracks_BValid_s1e")

  // Safety 6: Pipeline registers hold their values during stalls
  // (Stall_s1 being high prevents the Phi1-gated pipeline stage from being overwritten)
  assertStableWhen(io.Stall_s1, AValid_s2e, "AValid_s2e_stable_during_stall")
  assertStableWhen(io.Stall_s1, BValid_s2e, "BValid_s2e_stable_during_stall")
  assertStableWhen(io.Stall_s1, ABoosted_s2e, "ABoosted_s2e_stable_during_stall")
  assertStableWhen(io.Stall_s1, BBoosted_s2e, "BBoosted_s2e_stable_during_stall")

  // Safety 7: Mutex — ABoosted_s2e and ABoostValid_s2e should not both be true.
  // A boosted register entry that is still valid would indicate a contradictory state.
  fvAssert(!(ABoosted_s2e && ABoostValid_s2e), "ABoost_state_contradiction")

  // Safety 8: Mutex — BBoosted_s2e and BBoostValid_s2e (same reasoning as A-side)
  fvAssert(!(BBoosted_s2e && BBoostValid_s2e), "BBoost_state_contradiction")

  // Safety 9: Squash_s1e must always clear the boost-valid tracking bits.
  // When Squash fires, ABoostValid/BBoostValid at s2e must be false (sampled at update).
  // We assert the consequence: ABoostValid_s2e is false after a Squash event.
  fvAssert(!io.Squash_s1e || !ABoostValid_s2e, "Squash_clears_ABoostValid")
  fvAssert(!io.Squash_s1e || !BBoostValid_s2e, "Squash_clears_BBoostValid")

  // Bounded liveness 10: A valid instruction at s2e (not ignored) must reach s1m
  // within a small bounded number of cycles. The path is s2e -> (next Phi2) -> s1m,
  // so the expected delay is 1–2 cycles; bound of 8 provides ample margin.
  astRelaxedLiveness(
    AValid_s2e && !io.AIgnore_s2e,
    AValid_s1m,
    8,
    "AValid_progress_s2e_to_s1m"
  )

  // Bounded liveness 11: A valid B instruction at s2e (not ignored) must reach s1m
  astRelaxedLiveness(
    BValid_s2e && !io.BIgnore_s2e,
    BValid_s1m,
    8,
    "BValid_progress_s2e_to_s1m"
  )

  // Bounded liveness 12: When no instruction-stall is active, the qualified clock
  // Delta2_q2 must pulse within a bounded number of cycles, proving the clock
  // generation pipeline (IStall delay → qualifying logic) does not deadlock.
  astRelaxedLiveness(
    !io.IStall_s1 && !io.MemStall_s1,
    io.Delta2_q2,
    8,
    "Delta2_q2_pulses_when_not_stalled"
  )
}

object VerilogGenerator extends App {
  emitVerilog(new ABypassCtrl(), args)
}
