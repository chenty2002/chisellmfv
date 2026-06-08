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

  // Sampled ignore signals (latched at Phi1 posedge, used at Phi2)
  val AIgnoreReg_s2e = RegInit(false.B)
  val BIgnoreReg_s2e = RegInit(false.B)

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
    AValid_s2m := AValid_s1m
    // Sample AIgnore_s2e at the same time as the instruction enters s2e
    AIgnoreReg_s2e := io.AIgnore_s2e

    // Boost logic
    ABoosted_s2e := io.ABoosted_s1e ^ (io.Commit_s1e & io.ABoostValid_v1e)
    ABoostValid_s2e := io.ABoostValid_v1e & ~(io.Commit_s1e | io.Squash_s1e)
    ABoosted_s2m := ABoosted_s2e ^ (io.Commit_s1e & io.ABoostValid_v1e)
    ABoostValid_s2m := ABoostValid_s1m & ~(io.Commit_s1e | io.Squash_s1e)
  }

  when(Phi2) {
    // Preserve AValid_s1m when it holds a valid instruction that hasn't yet
    // been captured by AValid_s2m (i.e., stalled).  Without this guard the
    // Phi2 block unconditionally overwrites s1m from s2e, allowing a change
    // in AValid_s2e or AIgnore_s2e to retroactively kill an instruction that
    // has already propagated to the s1m stage.
    // Use the sampled AIgnoreReg_s2e (latched at previous Phi1) rather than
    // the raw io.AIgnore_s2e to prevent mid-cycle transitions from blocking
    // the s2e->s1m pipeline propagation.
    AValid_s1m := Mux(AValid_s1m && !AValid_s2m, AValid_s1m, AValid_s2e & ~AIgnoreReg_s2e)
    AValid_s1w := AValid_s2m
    ABoosted_s1m := ABoosted_s2e
    ABoostValid_s1m := ABoostValid_s2e
  }

  when(io.Phi1 & ~io.Stall_s1) {
    BValid_s2e := ~(io.BKill_s1e | io.BNoDest_s1e | io.Except_s1w)
    BValid_s2m := BValid_s1m
    // Sample BIgnore_s2e at the same time as the instruction enters s2e
    BIgnoreReg_s2e := io.BIgnore_s2e

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
    // Same preservation guard as A-side: BValid_s1m holds its value when it
    // is valid but not yet transferred to BValid_s2m (stalled scenario).
    // Use the sampled BIgnoreReg_s2e rather than the raw io.BIgnore_s2e.
    BValid_s1m := Mux(BValid_s1m && !BValid_s2m, BValid_s1m, BValid_s2e & ~BIgnoreReg_s2e)
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

  // ========== FORMAL ASSERTIONS ==========

  // ---------------------------------------------------------------------------
  // Safety 1: Bypass load and data signals must be mutually exclusive (one-hot0)
  // A bypass cannot simultaneously be both a load-bypass and a data-bypass.
  // ---------------------------------------------------------------------------
  fvAssert(!(io.ASBypassLoad_s1e && io.ASBypassData_s1e),
    "ASBypass_mutex_load_vs_data")
  fvAssert(!(io.ATBypassLoad_s1e && io.ATBypassData_s1e),
    "ATBypass_mutex_load_vs_data")

  // ---------------------------------------------------------------------------
  // Safety 2: AKill_s1e invalidates A-side valid signal in the same cycle
  // ---------------------------------------------------------------------------
  fvAssert(!io.AKill_s1e || !io.AValid_s1e,
    "AKill_clears_AValid_s1e")

  // ---------------------------------------------------------------------------
  // Safety 3: Bypass load outputs match their definitions.
  // When ASBypBmem_s1e & BIsLoad_s1w & ~AKill_s1e, ASBypassLoad_s1e must be true.
  // When ~(ASBypBmem_s1e & BIsLoad_s1w) & ~AKill_s1e, ASBypassData_s1e must be true.
  // ---------------------------------------------------------------------------
  fvAssert(!(io.ASBypBmem_s1e && BIsLoad_s1w && !io.AKill_s1e) || io.ASBypassLoad_s1e,
    "ASBypassLoad_definition")
  fvAssert(!(!io.AKill_s1e && !(io.ASBypBmem_s1e && BIsLoad_s1w)) || io.ASBypassData_s1e,
    "ASBypassData_definition")
  fvAssert(!(io.ATBypBmem_s1e && BIsLoad_s1w && !io.AKill_s1e) || io.ATBypassLoad_s1e,
    "ATBypassLoad_definition")
  fvAssert(!(!io.AKill_s1e && !(io.ATBypBmem_s1e && BIsLoad_s1w)) || io.ATBypassData_s1e,
    "ATBypassData_definition")

  // ---------------------------------------------------------------------------
  // Safety 4: Delta2_q2 must be deasserted when IStall_s2 is true and MemStall_s2 is false
  // (Delta2_q2 = (~IStall_s2 | MemStall_s2) & Phi2)
  // ---------------------------------------------------------------------------
  fvAssert(!io.Delta2_q2 || (Phi2 && (!IStall_s2 || MemStall_s2)),
    "Delta2_q2_condition")

  // ---------------------------------------------------------------------------
  // Pipeline Progress (Bounded Liveness):
  // When a valid A-side instruction enters the s2e stage (at Phi1, no stall, not killed,
  // not ignored, no exception), it must propagate to AValid_s1w within 8 cycles.
  // Bounded by 8 to accommodate the 4-stage pipeline plus stalls.
  // ---------------------------------------------------------------------------
  astRelaxedLiveness(
    io.Phi1 && !io.Stall_s1 && AValid_s2e && !io.AIgnore_s2e && !io.Except_s1w,
    AValid_s1w,
    8,
    "A_pipeline_completes_within_8")

  // ---------------------------------------------------------------------------
  // Pipeline Progress (Bounded Liveness):
  // Same for B-side: valid instruction enters s2e, propagates to BValid_s2m
  // (B-side end-of-pipeline output) within 8 cycles.
  // ---------------------------------------------------------------------------
  astRelaxedLiveness(
    io.Phi1 && !io.Stall_s1 && BValid_s2e && !io.BIgnore_s2e && !io.Except_s1w,
    BValid_s2m,
    8,
    "B_pipeline_completes_within_8")

  // ---------------------------------------------------------------------------
  // Pipeline Stage Propagation (1-cycle delay):
  // At a Phi1 cycle without stall, when AValid_s2e is set and AIgnore_s2e is false,
  // then on the next cycle (Phi2) AValid_s1m must be set.
  // ---------------------------------------------------------------------------
  assertImpliesDelay(
    io.Phi1 && !io.Stall_s1 && AValid_s2e && !io.AIgnore_s2e,
    AValid_s1m,
    1,
    "A_s2e_to_s1m_propagation")

  // ---------------------------------------------------------------------------
  // Pipeline Stage Propagation (1-cycle delay):
  // Same for B-side: BValid_s2e propagates to BValid_s1m.
  // ---------------------------------------------------------------------------
  assertImpliesDelay(
    io.Phi1 && !io.Stall_s1 && BValid_s2e && !io.BIgnore_s2e,
    BValid_s1m,
    1,
    "B_s2e_to_s1m_propagation")

  // ---------------------------------------------------------------------------
  // Alpha1/Beta1 only asserted when the corresponding valid is true and not stalled
  // ---------------------------------------------------------------------------
  fvAssert(!io.Alpha1_s1m || (AValid_s1m && !io.Stall_s1),
    "Alpha1_requires_AValid")
  fvAssert(!io.Beta1_s1m || (BValid_s1m && !io.Stall_s1),
    "Beta1_requires_BValid")
}

object VerilogGenerator extends App {
  emitVerilog(new ABypassCtrl(), args)
}
