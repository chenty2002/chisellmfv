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

  // =====================================================
  // Formal Verification Assertions
  // =====================================================

  // === Safety: Bypass signal exclusivity ===
  // The bypass load and data paths are designed to be mutually exclusive.
  // ASBypassLoad_s1e uses (ASBypBmem_s1e & BIsLoad_s1w) as the select,
  // while ASBypassData_s1e uses its complement. Both gated by ~AKill.
  assertMutex(Seq(io.ASBypassLoad_s1e, io.ASBypassData_s1e), "ASBypass_load_data_mutex")
  assertMutex(Seq(io.ATBypassLoad_s1e, io.ATBypassData_s1e), "ATBypass_load_data_mutex")

  // === Safety: Exactly one bypass path active when not killed ===
  // When AKill_s1e is false, exactly one of the bypass paths is active.
  // When AKill_s1e is true, both are zero (killed).
  fvAssert((io.ASBypassLoad_s1e | io.ASBypassData_s1e) === ~io.AKill_s1e,
    "ASBypass_one_active_when_not_killed")
  fvAssert((io.ATBypassLoad_s1e | io.ATBypassData_s1e) === ~io.AKill_s1e,
    "ATBypass_one_active_when_not_killed")

  // === Safety: Kill cancels A-side valid ===
  // When a kill arrives for an A-side instruction, the combinational
  // AValid_s1e must be deasserted in the same cycle.
  fvAssert(!io.AKill_s1e || !io.AValid_s1e, "AKill_clears_AValid_s1e")

  // === Safety: Kill cancels B-side valid on register update ===
  // When a kill arrives and the B-side pipeline advances (Phi1 & ~Stall),
  // BValid_s2e must be deasserted in the following cycle.
  assertNextStepWhen(
    io.Phi1 & ~io.Stall_s1 & io.BKill_s1e,
    !BValid_s2e,
    "BKill_clears_BValid_s2e"
  )

  // === Safety: Output enable implies valid ===
  // The datapath latch enable Alpha1/Beta1 should only fire when the
  // corresponding instruction is valid in that pipeline stage.
  fvAssert(!io.Alpha1_s1m || AValid_s1m, "Alpha1_implies_AValid_s1m")
  fvAssert(!io.Beta1_s1m || BValid_s1m, "Beta1_implies_BValid_s1m")

  // === Safety: AValid_s1e follows its combinational definition ===
  // The valid-in-execute signal is defined as the inverse of
  // (kill OR no-dest OR exception). This is an architected invariant.
  fvAssert(io.AValid_s1e === ~(io.AKill_s1e | io.ANoDest_s1e | io.Except_s1w),
    "AValid_s1e_definition")

  // === Liveness: A-side pipeline forward progress ===
  // When the pipeline advances (Phi1 & ~Stall) with a valid instruction at
  // the s2e stage, that valid signal must propagate to writeback (s1w)
  // within a bounded number of cycles. The pipeline path is:
  //   s2e -> (Phi2) -> s1m -> (Phi1 & ~Stall) -> s2m -> (Phi2) -> s1w
  // Under continuous unstalled operation this takes at most 3 transitions;
  // we allow up to 20 cycles to account for stalls and phase alignment.
  astRelaxedLiveness(
    io.Phi1 & ~io.Stall_s1 & AValid_s2e,
    AValid_s1w,
    20,
    "A_valid_pipeline_progress"
  )
}

object VerilogGenerator extends App {
  emitVerilog(new ABypassCtrl(), args)
}
